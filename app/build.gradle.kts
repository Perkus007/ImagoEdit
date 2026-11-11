plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.perkus.imagoedit"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.perkus.imagoedit"
        minSdk = 30
        targetSdk = 37
        versionCode = 7
        versionName = "1.6"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt") // собственные кодировщики JPEG и WebP (libjpeg-turbo, libwebp), попиксельные циклы кистей
            version = "3.22.1"
        }
    }

    // Own release key: path and password in ~/.gradle/gradle.properties (outside the repo); PKCS12 → key password = store password
    signingConfigs {
        providers.gradleProperty("imagoedit.storeFile").orNull?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = providers.gradleProperty("imagoedit.password").get()
                keyAlias = "imagoedit"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            // Without the key (a fresh clone) — the debug key, so the APK still installs on a phone
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization { enable = true } // R8 на весь код и ресурсы
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.avif.coder) // единственный способ получить AVIF-кодировщик: в платформе Android его нет
}