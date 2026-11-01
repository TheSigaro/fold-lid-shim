import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.pavlov.foldlidshim"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "dev.pavlov.foldlidshim"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            // The three Bouncy Castle jars carry the same Java 9 OSGi metadata.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        aidl = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.material)
    testImplementation(libs.junit)
    implementation(libs.hiddenapibypass)
    implementation(libs.bouncycastle)
    implementation(libs.conscrypt)
    implementation(libs.boringssl)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
