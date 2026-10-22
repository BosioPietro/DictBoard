import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.bosiopietro.dictboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.bosiopietro.dictboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The dictionary and its license ship as assets, stored uncompressed so
    // the dictionary can be mapped into memory rather than read into it.
    sourceSets["main"].assets.srcDir(rootProject.file("data"))
    androidResources {
        noCompress += "wdict"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":dictionary"))
}
