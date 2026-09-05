plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.roadguard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roadguard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "v1.0.56"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // .tflite must stay uncompressed: FileUtil.loadMappedFile needs
    // AssetManager.openFd, which fails on compressed assets.
    aaptOptions {
        noCompress += "tflite"
    }
}

// UFLD lane model (~117 MB) ships inside the APK but must not live in git
// (GitHub blocks files >100 MB, no LFS here). The canonical copy is the
// release asset below; this task fetches it into assets/ before the build
// when missing. Local builds and CI both get a bundled model, the repo
// stays source-only (see .gitignore).
val ufldModelFile = layout.projectDirectory.file("src/main/assets/ufld_tusimple_float16.tflite")
val downloadUfldModel by tasks.registering(Exec::class) {
    description = "Fetch the bundled UFLD model (skipped when present)"
    commandLine(
        "sh", "-c",
        "if [ ! -f src/main/assets/ufld_tusimple_float16.tflite ]; then " +
            "curl -sSL -o src/main/assets/ufld_tusimple_float16.tflite " +
            "https://github.com/hendr15k/RoadGuard/releases/download/v1.0.50-models/ufld_tusimple_float16.tflite; fi"
    )
    outputs.file(ufldModelFile)
}
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(downloadUfldModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    implementation("com.google.mlkit:object-detection:17.0.0")

    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
