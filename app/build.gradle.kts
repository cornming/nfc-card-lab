import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.cornming.nfccardlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.cornming.nfccardlab"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 刻意不加任何執行期相依套件：只用 Android 內建 API，APK 內沒有第三方程式碼。
dependencies {
    testImplementation("junit:junit:4.13.2")
}
