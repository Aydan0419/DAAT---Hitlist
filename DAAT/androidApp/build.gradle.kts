import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

android {
    namespace = "com.daat.hitlist.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Must match the Android app registered in Firebase (google-services.json).
        // To publish under a new id, register it in the Firebase console first,
        // download the new google-services.json, then change this line.
        applicationId = "com.example.daat"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.0"
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEMO", "false")
        }
        // Demo: every screen with sample players and no Firebase. For showing the app
        // before the backend is deployed. Same app id, so it replaces the real app.
        create("demo") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "DEMO", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
