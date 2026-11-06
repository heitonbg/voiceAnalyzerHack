import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.13"
}

android {
    namespace = "com.epicwalk.voiceanalyzer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.epicwalk.voiceanalyzer"
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

abstract class PrepareModelSmokeAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        fileSystemOperations.sync {
            from(modelFiles)
            into(outputDirectory)
        }
    }
}

// Every application APK contains the complete pinned offline model pack.
androidComponents {
    onVariants { variant ->
        val bundledModels = tasks.register<PrepareModelSmokeAssets>(
            "bundle${variant.name.replaceFirstChar { it.uppercaseChar() }}OfflineModels"
        ) {
            modelFiles.from(rootProject.fileTree("research/models") {
                include("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt",
                    "silero_vad.onnx", "titanet.onnx", "Qwen3-0.6B-Q8_0.gguf",
                    "sherpa-onnx-pyannote-segmentation-3-0/model.onnx")
            })
            outputDirectory.set(layout.buildDirectory.dir("generated/offlineModels/${variant.name}"))
            doFirst {
                val required = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt",
                    "silero_vad.onnx", "titanet.onnx", "Qwen3-0.6B-Q8_0.gguf",
                    "sherpa-onnx-pyannote-segmentation-3-0/model.onnx")
                required.forEach { check(rootProject.file("research/models/$it").isFile) { "Missing bundled model: $it" } }
            }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundledModels, PrepareModelSmokeAssets::outputDirectory)
    }
}

// Additional public fixtures belong only to the instrumentation APK.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val prepareAssets = tasks.register<PrepareModelSmokeAssets>(
            "prepare${variant.name.replaceFirstChar { it.uppercaseChar() }}ModelSmokeAssets"
        ) {
            modelFiles.from(rootProject.fileTree("research/models") {
                include("*.onnx", "*.wav", "tokens.txt", "sherpa-onnx-pyannote-segmentation-3-0/*.onnx")
            })
            outputDirectory.set(layout.buildDirectory.dir("generated/modelSmokeAssets/${variant.name}"))
        }
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
            prepareAssets, PrepareModelSmokeAssets::outputDirectory
        )
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}