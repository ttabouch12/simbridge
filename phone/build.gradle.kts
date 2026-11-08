import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.simbridge"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "io.github.simbridge"
        minSdk = 34
        targetSdk = 36
        versionCode = 220
        versionName = "2.1.0"
    }
    // Same signing source as the gateway app: a git-ignored signing.properties in the project root.
    val localSigning = Properties().apply {
        rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val releaseSigning = if (listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { localSigning.getProperty(it) != null }) {
        signingConfigs.create("release") {
            storeFile = file(localSigning.getProperty("storeFile"))
            storePassword = localSigning.getProperty("storePassword")
            keyAlias = localSigning.getProperty("keyAlias")
            keyPassword = localSigning.getProperty("keyPassword")
        }
    } else null
    buildTypes {
        release {
            signingConfig = releaseSigning
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // full: Google's code scanner for pairing, update check on. foss: no Google code (F-Droid);
    // pair with the camera app (it opens the simbridge://pair link) or paste the link.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "UPDATE_CHECK_DEFAULT", "true")
        }
        create("foss") {
            dimension = "store"
            buildConfigField("boolean", "UPDATE_CHECK_DEFAULT", "false")
        }
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation(project(":voice-api"))
    implementation(project(":sip"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.kotlinx.coroutines.android)
    // QR scanning for pairing via Google Play services (no camera permission needed).
    "fullImplementation"("com.google.android.gms:play-services-code-scanner:16.1.0")
    // On-device Turkish/English translation for call captions (offline fallback).
    "fullImplementation"("com.google.mlkit:translate:17.0.3")
}

