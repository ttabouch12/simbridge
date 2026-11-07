import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.simbridge.gateway"
        minSdk = 30
        targetSdk = 36
        versionCode = 300
        versionName = "1.0.0"
        // Filter all transitive JNI libraries as well as PJSIP's own module outputs.
        ndk {
            //abiFilters += listOf("arm64-v8a", "x86_64")
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from ~/.gradle/gradle.properties (vsa.signing.*) or a git-ignored
    // signing.properties in the project root (never from the repo); without it the release APK is
    // built unsigned.
    val localSigning = Properties().apply {
        rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("vsa.signing.$it").orNull ?: localSigning.getProperty(it) }
    val releaseSigning = if (signingProps.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signingProps.getValue("storeFile")!!)
            storePassword = signingProps["storePassword"]
            keyAlias = signingProps["keyAlias"]
            keyPassword = signingProps["keyPassword"]
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation(project(":voice-api"))
    implementation(project(":sip"))
    implementation(project(":gateway"))
    implementation(project(":telephony"))
    implementation(project(":privileged"))
    // Declared by this module's manifest; keep it on the app compile classpath for lint.
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
