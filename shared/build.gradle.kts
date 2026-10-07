import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")
    android {
        namespace = "algofeed.shared"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
    }

    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons)
            implementation(libs.compose.resources)
            api(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigationevent.compose)
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.ktor.client.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.rssparser)
            implementation(libs.ksoup)
            implementation(libs.coil.compose)
            implementation(libs.coil.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmSharedMain by getting {
            dependencies {
                implementation(libs.readability4j)
                implementation(libs.ktor.client.okhttp)
            }
        }
        androidMain.dependencies {
            implementation(libs.core.ktx)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.room.testing)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "algofeed.resources"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
}
