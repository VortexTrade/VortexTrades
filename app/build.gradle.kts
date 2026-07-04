import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load the Anthropic API key from local.properties so it never lands in source control.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val anthropicApiKey: String = localProps.getProperty("ANTHROPIC_API_KEY", "")

android {
    namespace = "com.vortextrade.blackjackoverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vortextrade.blackjackoverlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines for off-main-thread API calls.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Official Anthropic SDK (the Java SDK also serves Kotlin).
    implementation("com.anthropic:anthropic-java:2.34.0")
}
