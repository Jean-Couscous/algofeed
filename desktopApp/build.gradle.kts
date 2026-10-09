import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.swing)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "algofeed.desktop.MainKt"
        // Package with the Java 21 the app is compiled and tested against, not whichever JDK runs Gradle.
        javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
            .get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "algofeed"
            // Bundles the embedding model + vocab (from the common/ subdir); at runtime they are at
            // compose.application.resources.dir.
            appResourcesRootDir.set(project(":shared").layout.buildDirectory.dir("embedding-desktop"))
            // CI passes -Palgofeed.versionName so desktop packages carry the release's version.
            packageVersion = providers.gradleProperty("algofeed.versionName").orNull ?: "0.1.0"
            // From suggestRuntimeModules, plus jdk.crypto.ec: HTTPS key exchange on Java 21 needs it,
            // and it is loaded as a provider, which the scan can't see.
            modules("java.instrument", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            linux {
                iconFile = project.file("icon.png")
            }
        }
    }
}

// The embedding model asset must be fetched before any task that runs or packages the app reads it.
tasks.matching {
    it.name in setOf("run", "runDistributable", "prepareAppResources", "createDistributable",
        "packageDistributionForCurrentOS", "packageReleaseDistributionForCurrentOS")
}.configureEach { dependsOn(":shared:prepareEmbeddingAssets") }

// Development tool: ./gradlew :desktopApp:screenshot --args="<db> <outdir> [sources...]"
tasks.register<JavaExec>("screenshot") {
    mainClass = "algofeed.desktop.ScreenshotKt"
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}
