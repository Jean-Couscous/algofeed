plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "algofeed.android"
    compileSdk = 37

    defaultConfig {
        // Final applicationId is still to be chosen before any release.
        applicationId = "algofeed.android"
        minSdk = 26
        targetSdk = 36
        // CI passes -Palgofeed.versionCode / -Palgofeed.versionName so each release can update the last one.
        versionCode = providers.gradleProperty("algofeed.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("algofeed.versionName").orNull ?: "0.1.0"
    }

    // The embedding model + vocab are bundled as assets (fetched at build time, see :shared).
    // A plain path (not a Provider) is required here; the task dependency is wired below.
    sourceSets["main"].assets.srcDir(project(":shared").layout.buildDirectory.dir("embedding").get().asFile)

    // The upload key lives outside the repo. To use one, add to ~/.gradle/gradle.properties:
    //   algofeed.release.storeFile=/absolute/path/to/upload.jks
    //   algofeed.release.storePassword=…
    //   algofeed.release.keyAlias=…
    //   algofeed.release.keyPassword=…
    // Without them, release builds are signed with the debug key, for local testing only.
    signingConfigs {
        val storeFile = providers.gradleProperty("algofeed.release.storeFile").orNull
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("algofeed.release.storePassword").get()
                keyAlias = providers.gradleProperty("algofeed.release.keyAlias").get()
                keyPassword = providers.gradleProperty("algofeed.release.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's FileDescriptor interceptor reflects into this JDK-internal package.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

// preBuild anchors the whole variant build, so asset merging, lint and packaging all run after the
// model is fetched. (A narrower merge*Assets dependency misses the lint-vital task, which also reads
// the assets dir and fails Gradle's strict input/output validation on release builds.)
tasks.named("preBuild") { dependsOn(":shared:prepareEmbeddingAssets") }

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.browser)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.glance.testing)
}
