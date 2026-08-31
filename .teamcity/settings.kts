import jetbrains.buildServer.configs.kotlin.v2018_1.*
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.ScriptBuildStep
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.finishBuildTrigger

/*
 * TeamCity Kotlin DSL - CONAN third-party project (IN-658 Conan package builds).
 * Target server: TeamCity Enterprise 2018.1.3  =>  API version "2018.1".
 *
 * Three templated package builds: grpc, fmt, gtest. Each is one <PKG> subtree
 * (Linux x86_64 / Linux ARM arm+arm64 / Windows x64 + a PUBLISH config). Describe a
 * package ONCE via conanPackage()/grpcLine(); adding a package is one line, bumping a
 * version is one string. Build logic itself lives in the conan-recipes test-astra
 * shell drivers - the DSL only wires configs + parameters.
 *
 * CAUTION: Kotlin block comments NEST (unlike Java). Never put the two-character
 * sequence slash-then-star inside a comment (a path followed by a shell glob is the
 * classic way) - it opens a nested comment that silently swallows the rest of the
 * file until the next star-then-slash, and TC reports "Expecting an element" at a
 * seemingly random spot inside a string literal far below.
 *
 * VCS: builds check out the conan-recipes repo through the EXISTING shared, parametrized
 * root  AbsoluteId("Bitbucket")  (url scm/%repoProject%/%repoName%.git, auth Password/git).
 * repoProject/repoName below point it at dev/conan. No new GitVcsRoot, no authMethod.
 *
 * NOTE: this file must reach the TeamCity settings VCS repo BYTE-EXACT (via git, not
 * copy-paste / web editor). ASCII only; keep straight double-quote chars intact.
 */

version = "2018.1"

// ---------------------------------------------------------------------------
// Data model + factories - describe everything template-style
// ---------------------------------------------------------------------------
data class ConanPkg(
    val name: String,
    val version: String,
    val arches: List<String> = listOf("x86_64", "x86", "arm", "arm64"),
    val windows: Boolean = true,
    // Windows nupkg currently carry a wrong compiler tag (v194 instead of legacy v143,
    // deployer _short_compiler fix pending) - keep building the win leaf, but do NOT
    // feed it into PUBLISH until the deployer emits legacy tags. Flip per package.
    val publishWindows: Boolean = false,
    // two-letter legacy config code (GR = grpc, GT = gtest, FM = fmt, ...);
    // empty = derived from the first two letters of the name
    val code: String = ""
)

/*
 * Config codes: <XX><digits> where XX = two-letter package code (GR/GT/FM/...).
 * The digit block names the BUILD TEMPLATE that produces the config, per the
 * Root-project template registry. Taken there already: 1xx CMAKE builds,
 * 200/250/260 DOTNETCORE*, 300/310 DOTNET, 500/550 GENDOC/PRODUCT, 700/710,
 * 800-860, 888 DOCKER, 900/910 CMAKE PACKAGE/RELEASE.
 * OUR Conan templates take the FREE 4xx block, tail digits mirror CMAKE:
 *
 *     400 = Windows x86 StaticRT (ConanBuildWindows; CMAKE analog 100)
 *     401 = Windows x64 StaticRT (ConanBuildWindows; CMAKE analog 101)
 *     402 = Windows x86 DynRT    (ConanBuildWindows; CMAKE analog 102)
 *     403 = Windows x64 DynRT    (ConanBuildWindows; CMAKE analog 103)
 *     412 = Linux   x86 DynRT    (ConanBuildLinux x86, 32-bit; CMAKE analog 112)
 *     413 = Linux   x64 DynRT    (ConanBuildLinux x86_64; CMAKE analog 113)
 *     421 = Linux   ARM DynRT    (ConanBuildLinux arm;    CMAKE analog 121)
 *     422 = Linux ARM64 DynRT    (ConanBuildLinux arm64;  CMAKE analog 122)
 *     920 = Conan publish stage  (Publish template; CMAKE PACKAGE is 900) - free in 9xx
 *
 * StaticRT is a Windows-only slot (runtime /MT + LEGACY_NUPKG_LINKAGE=static);
 * Linux is DynamicRT-slot only, content always static .a. Numbers live in ONE
 * place below - trivial to change if the lead assigns a different block.
 */
val ARCH_CODE = mapOf("x86_64" to "413", "x86" to "412", "arm" to "421", "arm64" to "422")
val PUBLISH_CODE = "920"

/** The full legacy Windows matrix (mirrors GR100-103). */
data class WinVariant(
    val codeDigits: String,   // 400..403
    val idSuffix: String,     // build-config id tail (stable, never rename)
    val profile: String,      // conan profile in conan-recipes/profiles/
    val linkage: String,      // deployer slot tag: shared = DynRT, static = StaticRT
    val slotDir: String,      // artifact subdir
    val label: String
)
val WIN_VARIANTS = listOf(
    WinVariant("400", "win_x86_static", "win-v142-x86-static", "static", "win-x86-static", "Windows x86 StaticRT"),
    WinVariant("401", "win_x64_static", "win-v142-x64-static", "static", "win-x64-static", "Windows x64 StaticRT"),
    WinVariant("402", "win_x86",        "win-v142-x86",        "shared", "win-x86",        "Windows x86 DynamicRT"),
    WinVariant("403", "win_x64",        "win-v142-x64",        "shared", "win-x64",        "Windows x64 DynamicRT")
)

/*
 * Tree shape produced by both factories (matches the TC-generated FMT_CONAN):
 *
 *   <PKG>                (display name; subproject ID keeps the _CONAN tail -
 *                         renaming an ID makes TC recreate the project)
 *     - <XX>920 PUBLISH                      (publish config at package level)
 *     - Linux        -> <PKG> BUILD Conan x86_64
 *     - Linux ARM    -> <PKG> BUILD Conan arm, arm64
 *     - Windows      -> <PKG> BUILD Conan Windows x64
 */

/** Standalone single package (gtest, fmt, zlib, ...) as a <PKG>_CONAN subtree. */
fun Project.conanPackage(p: ConanPkg) {
    val idBase = p.name.capitalize().replace("-", "")   // Kotlin 1.2 API (TC 2018.1 compiler)
    val code = if (p.code.isNotEmpty()) p.code else p.name.take(2).toUpperCase()
    val leaves = mutableListOf<BuildType>()

    fun linuxLeaf(sp: Project, arch: String) = sp.buildType {
        id("${idBase}_Build_${arch.replace("-", "_")}")
        name = "$code${ARCH_CODE.getValue(arch)} BUILD Conan Linux $arch"
        templates(ConanBuildLinux)
        params {
            param("pkg.name", p.name)
            param("pkg.version", p.version)
            param("pkg.arch", arch)
            // 32-bit builds run inside the x86_64 mirror image (-m32), no x86 image exists
            if (arch == "x86") param("docker.image", "%REGISTRY%/grpc-tc-mirror-x86_64:0.1.0")
        }
    }.also { leaves.add(it) }

    subProject {
        id("${idBase}_CONAN")
        name = p.name.toUpperCase()

        subProject {
            id("${idBase}_Linux")
            name = "Linux"
            p.arches.filter { it == "x86_64" || it == "x86" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("${idBase}_LinuxARM")
            name = "Linux ARM"
            p.arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (p.windows) subProject {
            id("${idBase}_Windows")
            name = "Windows"
            WIN_VARIANTS.forEach { w ->
                val winLeaf = buildType {
                    id("${idBase}_Build_${w.idSuffix}")
                    name = "$code${w.codeDigits} BUILD Conan ${w.label}"
                    templates(ConanBuildWindows)
                    params {
                        param("pkg.name", p.name)
                        param("pkg.version", p.version)
                        param("win.profile", w.profile)
                        param("win.slot", w.slotDir)
                        // StaticRT slot: override the project-level "shared"
                        if (w.linkage == "static") param("env.LEGACY_NUPKG_LINKAGE", "static")
                    }
                }
                if (p.publishWindows) leaves.add(winLeaf)
            }
        }

        buildType {
            id("${idBase}_Publish")
            name = "$code$PUBLISH_CODE PUBLISH"
            templates(Publish)
            buildNumberPattern = "${p.version}-%build.counter%"
            // snapshot + same-chain artifacts: one publish waits for ALL leaves of the
            // same chain and never mixes builds of different generations; a failed or
            // cancelled leaf blocks the publish entirely.
            dependencies {
                leaves.forEach { b ->
                    dependency(b) {
                        snapshot {
                            onDependencyFailure = FailureAction.FAIL_TO_START
                            onDependencyCancel = FailureAction.CANCEL
                            reuseBuilds = ReuseBuilds.SUCCESSFUL
                        }
                        artifacts {
                            buildRule = sameChainOrLastFinished()
                            artifactRules = "**/*.nupkg => nupkg/"
                            cleanDestination = true
                        }
                    }
                }
            }
            triggers {
                leaves.forEach { b ->
                    finishBuildTrigger {
                        // v2018_1 API: buildTypeExtId (renamed to buildType in 2019.x);
                        // successfulOnly defaults to FALSE in the DSL - set explicitly
                        buildTypeExtId = b.id.toString()
                        successfulOnly = true
                    }
                }
            }
        }
    }
}

/**
 * grpc line - the exception. Version is NOT a free variable: build_<line>_nodocker.sh
 * pins a 7-package stack and needs grpc/target_info/grpc_<ver>.yml. So `line` selects
 * the driver; `version` is display-only. Same <PKG>_CONAN tree shape as conanPackage().
 */
fun Project.grpcLine(
    line: String,
    version: String,
    arches: List<String> = listOf("x86_64", "x86", "arm", "arm64"),
    windows: Boolean = true,
    publishWindows: Boolean = false   // see ConanPkg.publishWindows
) {
    val leaves = mutableListOf<BuildType>()

    fun linuxLeaf(sp: Project, arch: String) = sp.buildType {
        id("Grpc_${line}_Build_${arch.replace("-", "_")}")
        name = "GR${ARCH_CODE.getValue(arch)} BUILD Conan Linux $arch"
        templates(ConanBuildLinux)
        params {
            param("pkg.name", "grpc")
            param("pkg.version", version)                       // display only
            param("pkg.arch", arch)
            param("pkg.driver", "build_${line}_nodocker.sh")    // override derived
            param("pkg.output", "output-grpc-$line-$arch")      // override derived
            if (arch == "x86") param("docker.image", "%REGISTRY%/grpc-tc-mirror-x86_64:0.1.0")
        }
    }.also { leaves.add(it) }

    subProject {
        id("Grpc_${line}_CONAN")
        name = "GRPC_$line"

        subProject {
            id("Grpc_${line}_Linux")
            name = "Linux"
            arches.filter { it == "x86_64" || it == "x86" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("Grpc_${line}_LinuxARM")
            name = "Linux ARM"
            arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (windows) subProject {
            id("Grpc_${line}_Windows")
            name = "Windows"
            WIN_VARIANTS.forEach { w ->
                val winLeaf = buildType {
                    id("Grpc_${line}_Build_${w.idSuffix}")
                    name = "GR${w.codeDigits} BUILD Conan ${w.label}"
                    templates(ConanBuildWindows)
                    params {
                        param("pkg.name", "grpc")
                        param("pkg.version", version)
                        param("win.profile", w.profile)
                        param("win.slot", w.slotDir)
                        param("pkg.driver.win", "run_grpc_${line}_win.bat")
                        param("pkg.output.win", "output-grpc-$line-win")
                        if (w.linkage == "static") param("env.LEGACY_NUPKG_LINKAGE", "static")
                    }
                }
                if (publishWindows) leaves.add(winLeaf)
            }
        }

        buildType {
            id("Grpc_${line}_Publish")
            name = "GR$PUBLISH_CODE PUBLISH"
            templates(Publish)
            buildNumberPattern = "$version-%build.counter%"
            dependencies {
                leaves.forEach { b ->
                    dependency(b) {
                        snapshot {
                            onDependencyFailure = FailureAction.FAIL_TO_START
                            onDependencyCancel = FailureAction.CANCEL
                            reuseBuilds = ReuseBuilds.SUCCESSFUL
                        }
                        artifacts {
                            buildRule = sameChainOrLastFinished()
                            artifactRules = "**/*.nupkg => nupkg/"
                            cleanDestination = true
                        }
                    }
                }
            }
            // NO auto-trigger on grpc publishes, run them MANUALLY for now.
            // Reason: the deployer maps abseil of EVERY line to the same legacy id
            // absl @ 0.2.0 (LEGACY_DEP_VERSION_MAP), so two lines auto-publishing race
            // for the same package id on the feed and the loser is silently 409-skipped
            // with ABI-incompatible bytes left behind. Per-line version suffix scheme is
            // a lead decision; until then: build automatically, publish by hand.
        }
    }
}

// ---------------------------------------------------------------------------
// Templates - the shared shape every leaf inherits
// ---------------------------------------------------------------------------
object ConanBuildLinux : Template({
    id("ConanBuildLinux")
    name = "CONAN 412/413/421/422 BUILD Linux DynRT"
    description = "One Conan package, one arch, built inside grpc-tc-mirror docker image -> legacy .nupkg"

    // legacy-style human-readable build numbers: #1.17.0-5 instead of #5
    buildNumberPattern = "%pkg.version%-%build.counter%"

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

    params {
        param("pkg.name", "")
        param("pkg.version", "")
        param("pkg.arch", "x86_64")   // x86_64 | arm | arm64
        param("docker.image", "%REGISTRY%/grpc-tc-mirror-%pkg.arch%:0.1.0")
        param("pkg.driver", "build_%pkg.name%_nodocker.sh")
        param("pkg.output", "output-%pkg.name%-%pkg.arch%")
    }

    steps {
        script {
            name = "conan build"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                export REGISTRY="%REGISTRY%"
                ARCH=%pkg.arch% PKG_VERSION=%pkg.version% bash ./test-astra/%pkg.driver%
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
    }

    artifactRules = "%pkg.output%/*.nupkg"   // flat: filename already carries the arch

    requirements {
        equals("system.agent.type", "build-linux")
        equals("system.agent.version", "2")
    }
})

object ConanBuildWindows : Template({
    id("ConanBuildWindows")
    name = "CONAN 400-403 BUILD Windows (x86/x64, StaticRT/DynRT)"
    description = "One Conan package on a native MSVC agent (no docker) -> legacy .nupkg. NOT yet legacy-byte-validated."

    // legacy-style human-readable build numbers: #1.17.0-5 instead of #5
    buildNumberPattern = "%pkg.version%-%build.counter%"

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

    params {
        param("pkg.name", "")
        param("pkg.version", "")
        param("win.profile", "win-v142-x64")   // legacy x64 slot = v142 (msvc 192); x86 slot: win-v142-x86
        param("win.slot", "win-x64")
        param("pkg.driver.win", "run_%pkg.name%_win.bat")
        param("pkg.output.win", "output-%pkg.name%-win")
        // Агент крутится под SYSTEM -> дефолтный конан-кэш попадает в
        // C:\Windows\System32\config\systemprofile\.conan2. Для 32-битных тулзов
        // (nmake из vcvars x86) WoW64-редиректор подменяет System32 на SysWOW64 -
        // nmake "не видит" makefile, который сам же Configure создал (U1064).
        // Кэш ВНЕ System32 снимает редирекцию для всей матрицы (x64 не страдает).
        param("env.CONAN_HOME", """C:\ProgramData\conan2""")
    }

    steps {
        script {
            name = "conan build (win)"
            scriptContent = "set PROFILE_NAME=%win.profile% & set PKG_VERSION=%pkg.version% & test-windows\\%pkg.driver.win%"
        }
    }

    artifactRules = "%pkg.output.win%\\*.nupkg"   // flat: filename already carries arch+slot

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows")
        // provisioned agents only (conan needs python) - matches the proven manual config
        exists("python.path")
    }
})

object Publish : Template({
    id("ConanPublish")
    name = "CONAN 920 PUBLISH"
    description = "Collect leaf .nupkg (via artifact deps) and push to the conan NuGet feed on ProGet"

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

    steps {
        script {
            name = "publish nupkg -> ProGet"
            // explicit bash: the exec bit does not survive every checkout path (exit 126)
            scriptContent = "API_KEY=%ProGet.ApiKey% PROGET_URL=%PROGET_URL% FEED=%FEED% NUPKG_DIR=nupkg bash ./test-astra/tc_publish_conan.sh"
        }
    }

    // keep the exact pushed set downloadable from the publish build itself
    artifactRules = "nupkg/**/*.nupkg"

    // bash publish must land on a Linux build agent (not a Windows one from the same pool)
    requirements {
        startsWith("system.agent.type", "build-")
        equals("system.agent.version", "2")
        doesNotEqual("system.agent.type", "build-windows")
    }
})

// ---------------------------------------------------------------------------
// Project root - global params + the package list (THIS is what you edit daily)
// ---------------------------------------------------------------------------
project {
    template(ConanBuildLinux)
    template(ConanBuildWindows)
    template(Publish)

    params {
        param("REGISTRY", "proget.inc.elara.local/main")
        param("PROGET_URL", "http://proget.inc.elara.local")
        param("FEED", "conan")
        // drive the shared AbsoluteId("Bitbucket") root at the conan-recipes repo
        param("repoProject", "dev")
        param("repoName", "conan")
        param("env.LEGACY_NUPKG_LINKAGE", "shared")        // StaticRT slot -> "static"
        param("env.LEGACY_NUPKG_VERSION_SUFFIX", "")       // ".1" to coexist with legacy on ProGet
        // ProGet.ApiKey is deliberately NOT defined here. Secrets never go through the
        // synced DSL: define it once as a password parameter on the PARENT project
        // (SANDBOX, which is not under versioned settings) - CONAN inherits it and
        // %ProGet.ApiKey% in the publish step resolves at build time. Defining a fake
        // credentialsJSON placeholder here breaks apply ("could not decrypt" + TC
        // auto-commits a patches/ file that then fails with "parameter not found").
    }

    // ===== the templated package builds =====
    conanPackage(ConanPkg("gtest", "1.17.0"))
    conanPackage(ConanPkg("fmt", "11.2.0"))
    conanPackage(ConanPkg("cjson", "1.7.19"))   // wave 1: code derived = CJ
    conanPackage(ConanPkg("expat", "2.8.2"))              // EX
    conanPackage(ConanPkg("tinyxml2", "11.0.0"))          // TI
    conanPackage(ConanPkg("sqlite3", "3.53.3"))           // SQ (легаси sqlite 3.15.2; имя новое)
    conanPackage(ConanPkg("jansson", "2.15.1"))           // JA (апстрим новее CCI)
    conanPackage(ConanPkg("jsoncpp", "1.9.8"))            // JS (апстрим новее CCI)
    conanPackage(ConanPkg("lua", "5.5.0"))                // LU (легаси 5.4.2 - мажор-бамп)
    conanPackage(ConanPkg("libzip", "1.11.4", code = "LZ"))
    conanPackage(ConanPkg("nlohmann_json", "3.12.0", code = "NJ"))   // легаси json 3.7.0; имя новое
    // pthreads4w - Windows-only пакет: линуксовых арок нет вовсе
    conanPackage(ConanPkg("pthreads4w", "3.0.0", code = "PW", arches = listOf()))

    // ===== волна 2 =====
    conanPackage(ConanPkg("libcurl", "8.21.0", code = "CU"))
    conanPackage(ConanPkg("libxml2", "2.13.8", code = "XL"))
    conanPackage(ConanPkg("mbedtls", "3.6.6", code = "MB"))
    conanPackage(ConanPkg("libssh2", "1.11.1", code = "SH"))
    conanPackage(ConanPkg("zeromq", "4.3.5", code = "ZM"))
    conanPackage(ConanPkg("mosquitto", "2.0.22", code = "MQ"))
    conanPackage(ConanPkg("libmodbus", "3.1.12", code = "MD"))
    conanPackage(ConanPkg("net-snmp", "5.9.4", code = "NS"))
    conanPackage(ConanPkg("libpq", "16.14", code = "PQ"))
    conanPackage(ConanPkg("xerces-c", "3.3.0", code = "XC"))
    conanPackage(ConanPkg("pcre", "8.45", code = "PC"))   // транзитив net-snmp

    // ===== волна 3 (часть: рецепт есть/написан с нуля) =====
    conanPackage(ConanPkg("libiec61850", "1.6.1", code = "IE"))
    conanPackage(ConanPkg("mongoose", "7.22", code = "MG"))
    conanPackage(ConanPkg("nanopb", "0.4.9.1", code = "NP"))
    conanPackage(ConanPkg("soem", "2.0.0", code = "SO"))   // Linux/Windows only (не проверялся на Mac)
    // dbus: единственный meson-пакет; драйвер сам доставляет meson/ninja из
    // packages-linux/. Win-слоты выключены до ground-truth сверки легаси DBUS
    // (есть ли win .nupkg вообще) + meson/ninja на win-агенте.
    conanPackage(ConanPkg("dbus", "1.15.8", windows = false, code = "DB"))
    // snap7: рецепт с нуля (upstream = sourceforge .7z, переупакован в src/,
    // HELP [30]); win-сборка CMake-бэкендом не валидирована — не публикуется.
    conanPackage(ConanPkg("snap7", "1.4.2", code = "SN"))
    // matiec: tool-пакет (iec2c/iec2iec, либ нет); версия = 0.1.<дата коммита>
    // (тегов у upstream нет), flex/bison-выходы предзапечены в src/ (HELP [31]).
    // Windows OFF: upstream MinGW-only (<getopt.h> в main.cc), MSVC не поддержан.
    conanPackage(ConanPkg("matiec", "0.1.20260512", windows = false, code = "MT"))
    // qwt: Qt НЕ пакетируется — на линуксе 5.15.2 в базовом образе станка
    // (QT5_ROOT_DIR), на win-агенте нужна Qt-инсталляция + env QT5_ROOT_DIR
    // (корень; msvc*_64 рецепт найдёт сам). qmake запрещён (commercial-Qt
    // licheck) — сборка CMake-графтом. Linux только x86_64 (arm-образы Qt
    // не несут); x86-win-слоты требуют 32-битный Qt на агенте — есть ли он,
    // покажет первый прогон. Версия = легаси-пин el_conf 6.2.0 (HELP [32]).
    conanPackage(ConanPkg("qwt", "6.2.0", arches = listOf("x86_64"), code = "QW"))
    // qwindowkit/qxorm: Qt-схема как у qwt (QT5_ROOT_DIR, CMake); Linux
    // только x86_64. Версии = upstream latest (легаси-пинов нет).
    // qxorm: лицензия GPL3/коммерч QXPL — промоушен через лида (HELP [34]).
    conanPackage(ConanPkg("qwindowkit", "1.5.0", arches = listOf("x86_64"), code = "WK"))
    conanPackage(ConanPkg("qxorm", "1.5.1", arches = listOf("x86_64"), code = "QX"))
    // волна 4 (пропуски бэклога): классическая пара glog->gflags. Опции
    // (nothreads=False, with_unwind=False) зашиты в драйверы. glog-ран
    // эмитит оба .nupkg — gflags-конфиг опционален.
    conanPackage(ConanPkg("gflags", "2.3.0", code = "GF"))
    conanPackage(ConanPkg("glog", "0.7.1", code = "GL"))
    // rapidjson: header-only. Апстрим-релиз один и древний (1.1.0, 2016), все
    // живут на master — CCI версионирует снимки как cci.<дата>. NuGet такую
    // строку не принимает, поэтому deployer объявляет её как 1.1.20250205
    // (LEGACY_DEP_VERSION_MAP).
    conanPackage(ConanPkg("rapidjson", "cci.20250205", code = "RJ"))
    // grpc lines - driver-pinned (7-package stack each); version is display only.
    // Each line is its own GRPC_<line>_CONAN subtree; add a line = add a call.
    grpcLine("1601", "1.60.1")   // parity with legacy GR910
    grpcLine("1781", "1.78.1")   // newest line
}
