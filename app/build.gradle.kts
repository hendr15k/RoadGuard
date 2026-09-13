plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// --- Dedicated release signing identity -------------------------------------
// The key material never reaches the repo: it lives in
// /root/roadguard-signing/credentials.env (mode 600) and CI restores it from
// the ROADGUARD_KEYSTORE_BASE64 secret. Load it through tools/roadguard-gradle.sh.
//
// Every variant is signed with this one key. Before this existed, each build
// host signed with its own debug keystore (locally eb3b6b03..., on CI a
// per-run key such as c0ce2a3f...), so an APK from a release could not be
// installed over the one already on the device.
val roadguardSigningEnv = mapOf(
    "ROADGUARD_KEYSTORE_PATH" to System.getenv("ROADGUARD_KEYSTORE_PATH").orEmpty(),
    "ROADGUARD_KEYSTORE_PASSWORD" to System.getenv("ROADGUARD_KEYSTORE_PASSWORD").orEmpty(),
    "ROADGUARD_KEY_ALIAS" to System.getenv("ROADGUARD_KEY_ALIAS").orEmpty(),
    "ROADGUARD_KEY_PASSWORD" to System.getenv("ROADGUARD_KEY_PASSWORD").orEmpty()
)
val roadguardMissingSigningInputs: List<String> = buildList {
    roadguardSigningEnv.filterValues { it.isEmpty() }.keys.forEach { add(it) }
    val path = roadguardSigningEnv.getValue("ROADGUARD_KEYSTORE_PATH")
    if (path.isNotEmpty() && !file(path).exists()) add("ROADGUARD_KEYSTORE_PATH (no such file: $path)")
}
val roadguardSigningReady = roadguardMissingSigningInputs.isEmpty()

// Tasks that produce an installable artifact. Any other path to an APK
// (assemble*, install*, bundle*, extract*FromBundle*) depends on one of these,
// so gating exactly this set covers them all without guessing from name
// prefixes - a `startsWith("package") && endsWith("Release")` test missed
// packageReleaseBundle and packageReleaseUniversalApk.
val roadguardPackagingTasks = setOf(
    "packageDebug", "packageRelease",
    "packageDebugAndroidTest", "packageReleaseAndroidTest",
    "packageDebugBundle", "packageReleaseBundle",
    "packageDebugUniversalApk", "packageReleaseUniversalApk",
    "bundleDebug", "bundleRelease"
)

android {
    namespace = "com.roadguard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roadguard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "v1.0.65"

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
    signingConfigs {
        create("release") {
            if (roadguardSigningReady) {
                storeFile = file(roadguardSigningEnv.getValue("ROADGUARD_KEYSTORE_PATH"))
                storePassword = roadguardSigningEnv.getValue("ROADGUARD_KEYSTORE_PASSWORD")
                keyAlias = roadguardSigningEnv.getValue("ROADGUARD_KEY_ALIAS")
                keyPassword = roadguardSigningEnv.getValue("ROADGUARD_KEY_PASSWORD")
            }
        }
    }
    // Debug shares the release identity: the APK that ships is the debug build,
    // and a debug-keyed one would refuse to update over a shipped release
    // ("signer changed") and force a data-wiping uninstall.
    buildTypes.forEach { type ->
        val cfg = signingConfigs.getByName("release")
        if (cfg.storeFile != null) {
            type.signingConfig = cfg
        }
    }
    // Fail closed on EVERY installable artifact, not just the release variant:
    // app-debug.apk is what gets attached to a release, and with the key absent
    // AGP silently falls back to the host's ~/.android/debug.keystore - the
    // exact third-signer failure this build change exists to remove.
    gradle.taskGraph.whenReady {
        val packaging = allTasks.filter { it.name in roadguardPackagingTasks }
        if (packaging.isNotEmpty() && !roadguardSigningReady) {
            throw GradleException(
                "RoadGuard signing is incomplete, refusing to package " +
                    "${packaging.first().name}. Missing: " +
                    roadguardMissingSigningInputs.joinToString(", ") +
                    ". Run through tools/roadguard-gradle.sh, or set the four " +
                    "ROADGUARD_* variables (see README, 'Release builds')."
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
    description = "Fetch the bundled UFLD model (validates size, skipped when present)"
    // The logic lives in tools/fetch_ufld_model.sh (resolved relative to this
    // module: Exec runs with the module dir as cwd). It validates an
    // already-present file too — a curl killed mid-transfer leaves a truncated
    // file behind and its non-zero exit short-circuits any `&&` chain, so an
    // existence-only guard accepted that file on every later build.
    commandLine("bash", "tools/fetch_ufld_model.sh")
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
