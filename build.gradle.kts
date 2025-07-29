plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    alias(libs.plugins.dokka)
    alias(libs.plugins.deployer)
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://frcmaven.wpi.edu/artifactory/release/")
    }
}

dependencies {
    dokka(project(":core"))
    dokka(project(":ftc"))
}