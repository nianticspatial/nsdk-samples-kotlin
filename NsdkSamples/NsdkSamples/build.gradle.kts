plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nianticspatial.nsdk.externalsamples"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nianticspatial.nsdk.externalsamples"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "4.0.0"

        // Paste a Scaniverse site ID + access token locally for testing the "VPS2 (with site ID)" scene; do not commit real values.
        buildConfigField("String", "DEFAULT_SITE_ID", "\"YOUR_SITE_ID\"")
        buildConfigField("String", "ACCESS_TOKEN", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // For release builds, using debug keystore for distribution
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("arm64Api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation (libs.play.services.location)
    implementation("com.google.ar:core:1.47.0")
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.browser:browser:1.8.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.2.0-alpha13")
    implementation("com.nianticspatial:nsdk:4.5.0-26110800")
    // VpsDebuggerEventBuffer serializes VpsDebuggerDataEvent to JSON with protobuf-java-util's
    // JsonFormat, which NSDK uses internally but does not expose. protobuf-java itself already
    // comes from NSDK's `api` dependencies; it is listed so both stay on the same version.
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.appcompat)
    implementation(libs.maplibre.compose)
    testImplementation(libs.junit)
    implementation("io.github.sceneview:arsceneview:2.3.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Fail early if Git LFS files are still pointer stubs, e.g. a clone made without git-lfs.
// Otherwise the stubs are packaged as-is and the app crashes at startup loading materials.
val verifyLfsFiles = tasks.register("verifyLfsFiles") {
    val sources = fileTree("src")
    val projectDir = layout.projectDirectory.asFile
    inputs.files(sources)
    doLast {
        val header = "version https://git-lfs.github.com/spec/".toByteArray()
        val stubs = sources.files.filter { f ->
            f.length() < 1024 && f.readBytes().let { bytes ->
                bytes.size >= header.size && bytes.copyOfRange(0, header.size).contentEquals(header)
            }
        }
        if (stubs.isNotEmpty()) {
            throw GradleException(
                "These files are Git LFS pointers, not the real files:\n" +
                    stubs.joinToString("\n") { "  " + it.relativeTo(projectDir) } +
                    "\nInstall Git LFS and fetch them, then rebuild:\n  git lfs install && git lfs pull"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyLfsFiles)
}

tasks.register<Wrapper>("wrapper") {
  gradleVersion = "7.2"
}

tasks.register("prepareKotlinBuildScriptModel") {}
