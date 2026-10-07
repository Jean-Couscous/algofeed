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
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "algofeed"
            packageVersion = "0.1.0"
            linux {
                iconFile = project.file("icon.png")
            }
        }
    }
}

// Development tool: ./gradlew :desktopApp:screenshot --args="<db> <outdir> [sources...]"
tasks.register<JavaExec>("screenshot") {
    mainClass = "algofeed.desktop.ScreenshotKt"
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}
