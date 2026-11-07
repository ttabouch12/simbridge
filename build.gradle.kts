plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Optional, git-ignored per-machine tweaks (extra tasks, output copies). Not needed to build.
if (file("local.gradle.kts").exists()) apply(from = "local.gradle.kts")
