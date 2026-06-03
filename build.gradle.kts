// Build script for the xphp PhpStorm plugin.
//
// Uses the IntelliJ Platform Gradle Plugin 2.x DSL -- the successor to the
// legacy `gradle-intellij-plugin`.  The 2.x DSL is the supported path going
// forward and the one JetBrains' own plugin template uses.
//
// All version pins live in gradle.properties so a single edit there propagates
// to every coordinate (since-build, IDE target, kotlin runtime, jvm toolchain).
// Reach them with `providers.gradleProperty("...")` rather than
// `project.findProperty(...)` so Gradle's configuration cache stays happy.

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.net.URI

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get().toInt()))
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

// Repositories live here (not in settings.gradle.kts) because the settings-
// level `org.jetbrains.intellij.platform.settings` plugin can't expose its
// `intellijPlatform { defaultRepositories() }` Kotlin accessor reliably --
// see settings.gradle.kts for the longer rationale.  Applying the main
// plugin at the project level via `plugins {}` above gives us the accessor
// here without any of that grief.
repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // PhpStorm matches the LSP-API target window we picked in
        // gradle.properties.  `create(IntelliJPlatformType.PhpStorm, version)`
        // resolves the binary IDE distribution from JetBrains' installers repo
        // (the `jetbrainsIdeInstallers` entry registered by
        // `defaultRepositories()`), not from a Maven artifact -- there is no
        // Maven publication for the full PhpStorm distribution.
        create(
            IntelliJPlatformType.PhpStorm,
            providers.gradleProperty("platformVersion").get(),
        )

        // The bundled PHP plugin gives us the platform PHP language classes
        // (file type registry, PSI, PhpStorm-specific configurables).  Without
        // it our File-Type registration sits alongside PHP's instead of being
        // recognised as a sibling.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(",") })

        // v2 plugin model: extension points declared inside a plugin's
        // `<content><module ...>` sub-module aren't reachable through a
        // plain `<depends>` on the parent plugin id.  The TextMate plugin
        // declares `com.intellij.textmate.bundleProvider` and the supporting
        // classes under its `intellij.textmate` sub-module, so we need it
        // on the compile classpath (mirroring the
        // `<dependencies><module name="intellij.textmate"/></dependencies>`
        // entry in plugin.xml).
        bundledModule("intellij.textmate")

        // Toolchain components used by the build / verify pipeline.
        pluginVerifier()
        zipSigner()
    }

    // Plain JUnit 5 for unit-level tests against pure-data classes
    // (XphpLanguage, XphpFileType).  We deliberately do NOT pull in
    // `intellijPlatform { testFramework(TestFrameworkType.Platform) }`
    // here -- that injects IntelliJ's `PathClassLoader` over the test
    // runtime, which breaks plain JUnit 5 dispatch.  Anything that
    // genuinely needs `BasePlatformTestCase` should live in a separate
    // source set with its own test task; the build verifier already
    // covers structural plugin validation without booting the IDE.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

intellijPlatform {
    pluginConfiguration {
        version.set(providers.gradleProperty("pluginVersion"))

        ideaVersion {
            sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
            untilBuild.set(providers.gradleProperty("pluginUntilBuild"))
        }
    }

    pluginVerification {
        ides {
            // PhpStorm-only.  `plugin.xml` declares
            // `<depends>com.intellij.modules.php</depends>`, so the plugin
            // can't load in IDEA / WebStorm / RustRover / RubyMine / etc. --
            // verifying against `recommended()` (the JetBrains-curated set
            // of every IntelliJ Platform IDE) downloads ~6-8 IDE bundles
            // (~10-15 GB) the plugin would never run on, and on GitHub
            // ubuntu-latest runners that's enough to exhaust the ~14 GB
            // free disk and crash the runner worker with
            // "No space left on device".
            //
            // Pinning to the same PhpStorm version the sandbox + the
            // platform dependency use checks the API surface the plugin
            // actually targets.  Reads from `platformVersion` in
            // gradle.properties (same value the `dependencies.intellijPlatform.create(...)`
            // call above resolves) so a single bump there moves both
            // the runtime dependency and the verifier target -- no drift.
            create(
                IntelliJPlatformType.PhpStorm,
                providers.gradleProperty("platformVersion").get(),
            )
        }
    }
}

// PHAR downloaded at build time from `xphpLspPharUrl` in gradle.properties.
// Bundled into the plugin jar at `bin/xphp-lsp.phar`; PharExtractor reads it
// from the classpath on first plugin load and copies it into PhpStorm's
// system directory.
//
// The download is a hard requirement: a build with `xphpLspPharUrl` unset or
// empty FAILS rather than silently shipping an LSP-less plugin.  This
// guarantees a release zip always carries an embedded LSP binary.
//
// gradle.properties is the single source of truth -- the same file every
// other build pin lives in -- so CI and releases read the URL straight from
// the committed value with no env var involved.  `providers.gradleProperty`
// also honours the standard Gradle overrides for free
// (`-PxphpLspPharUrl=...`, `ORG_GRADLE_PROJECT_xphpLspPharUrl=...`) for the
// occasional one-off local build against a different binary.
//
// Read through the `providers` API rather than `findProperty(...)` so
// Gradle's configuration cache tracks the value and re-runs the download
// when it changes.
val xphpLspPharUrl = providers.gradleProperty("xphpLspPharUrl")
val downloadedPharDir = layout.buildDirectory.dir("lsp")

val downloadLspPhar by tasks.registering {
    description = "Downloads xphp-lsp.phar (xphpLspPharUrl in gradle.properties) for bundling into the plugin jar."
    group = "build"

    // The URL is the only meaningful input -- change it and the PHAR
    // re-downloads; leave it unchanged and the task stays up-to-date so
    // repeated builds don't re-fetch.  Declared optional so configuration
    // (and tasks that never touch the PHAR, e.g. `help`) doesn't blow up
    // just because the var is unset; the hard requirement is enforced at
    // execution time in the action below.
    inputs.property("xphpLspPharUrl", xphpLspPharUrl).optional(true)
    outputs.dir(downloadedPharDir)

    // Capture providers into locals so the action body holds no reference to
    // `Project` -- keeps the task configuration-cache compatible.
    val urlProvider = xphpLspPharUrl
    val outDir = downloadedPharDir

    doLast {
        // takeIf{isNotBlank} so an empty value (e.g. `xphpLspPharUrl =` with
        // nothing after it, which a `providers` lookup reports as
        // present-but-"") is treated as unset and yields the actionable
        // message below instead of a cryptic "no protocol" URL error.
        val url = urlProvider.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "No xphp LSP PHAR URL configured.  Set `xphpLspPharUrl` in " +
                    "gradle.properties to the phar download URL (or pass " +
                    "-PxphpLspPharUrl=<url>) and re-run the build.  The build " +
                    "downloads the xphp LSP binary and bundles it at " +
                    "bin/xphp-lsp.phar."
            )

        val dir = outDir.get().asFile
        val phar = dir.resolve("xphp-lsp.phar")
        dir.mkdirs()
        phar.delete()

        logger.lifecycle("Downloading xphp-lsp.phar from $url")
        URI(url).toURL().openStream().use { input ->
            phar.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Downloaded xphp-lsp.phar (${phar.length()} bytes) to $phar")
    }
}

tasks {
    processResources {
        // Bundle the downloaded PHAR.  Depending on the task provider wires
        // the build dependency automatically and copies its output dir
        // contents (xphp-lsp.phar) into bin/.  If the env var is unset the
        // download task fails first, so processResources never runs without
        // a PHAR in hand.
        from(downloadLspPhar) {
            into("bin")
        }
    }

    test {
        useJUnitPlatform()
    }

    // Pre-empt the IntelliJ Platform Gradle Plugin's tendency to download a
    // brand-new JBR every clean -- pin to the bundled toolchain when CI runs.
    wrapper {
        gradleVersion = "9.0.0"
    }
}
