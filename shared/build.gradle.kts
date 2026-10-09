import java.io.File
import java.net.URI
import java.security.MessageDigest
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
                // The embedder is compiled against the ai.onnxruntime API, identical across the two
                // artifacts; each leaf provides its own runtime (desktop JARs vs the Android AAR).
                compileOnly(libs.onnxruntime)
            }
        }
        androidMain.dependencies {
            implementation(libs.core.ktx)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.dash)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.ui)
            implementation(libs.onnxruntime.android)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.room.testing)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)
                implementation(libs.onnxruntime)
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

// --- embedding model asset ---
// The ~112 MB model is fetched at build time (never committed to the public repo) and converted to a
// compact SentencePiece vocab. Both app modules bundle shared/build/embedding as their model assets.
// Android reads a flat assets dir; Compose Desktop's appResourcesRootDir only collects files under a
// `common` (or per-OS) subdirectory, so the task writes both layouts.
val embeddingAssets = layout.buildDirectory.dir("embedding")
val embeddingResources = layout.buildDirectory.dir("embedding-desktop")

tasks.register("prepareEmbeddingAssets") {
    description = "Downloads the multilingual-e5-small ONNX model and builds its SentencePiece vocab."
    val base = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"
    val modelSha = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
    val tokSha = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    val outDir = embeddingAssets
    val desktopDir = embeddingResources
    inputs.property("modelSha", modelSha)
    inputs.property("tokSha", tokSha)
    outputs.dir(outDir)
    outputs.dir(desktopDir)
    doLast {
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }
        fun fetch(url: String, dest: File, sha256: String) {
            if (dest.exists() && sha256Of(dest) == sha256) return
            dest.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            val actual = sha256Of(dest)
            check(actual == sha256) { "checksum mismatch for $url: expected $sha256, got $actual" }
        }
        val dir = outDir.get().asFile.apply { mkdirs() }
        fetch("$base/onnx/model_quantized.onnx", File(dir, "model_quantized.onnx"), modelSha)
        val tokJson = File(dir, "tokenizer.json.tmp")
        fetch("$base/tokenizer.json", tokJson, tokSha)
        val script = """
            import json, sys
            vocab = json.load(open(sys.argv[1], encoding='utf-8'))['model']['vocab']
            with open(sys.argv[2], 'w', encoding='utf-8') as f:
                f.write('\n'.join(p + '\t' + repr(float(s)) for p, s in vocab))
        """.trimIndent()
        val proc = ProcessBuilder("python3", "-c", script, tokJson.absolutePath, File(dir, "tokenizer.vocab").absolutePath)
            .redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        tokJson.delete()
        check(code == 0) { "vocab conversion failed (exit $code):\n$log" }
        // Desktop layout: Compose collects appResourcesRootDir/common/*.
        val common = File(desktopDir.get().asFile, "common").apply { mkdirs() }
        for (name in listOf("model_quantized.onnx", "tokenizer.vocab")) {
            File(dir, name).copyTo(File(common, name), overwrite = true)
        }
    }
}
