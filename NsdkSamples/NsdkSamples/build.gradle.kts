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

        buildConfigField("String", "DEFAULT_VPS_PAYLOAD", "\"YOUR_PAYLOAD\"")
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
    implementation("com.nianticspatial:nsdk:4.1.0-c.279607")
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
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register<Wrapper>("wrapper") {
  gradleVersion = "7.2"
}

tasks.register("prepareKotlinBuildScriptModel") {}
