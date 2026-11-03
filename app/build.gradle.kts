import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定簽章：金鑰由環境變數提供（本機或 GitHub Actions 的 Secrets），不放進 repo。
// 沒有提供時退回 Android 預設的 debug 金鑰（每台機器不同，無法覆蓋安裝）。
val fixedKeystoreFile: String? = System.getenv("NFC_KEYSTORE_FILE")
val fixedKeystorePassword: String? = System.getenv("NFC_KEYSTORE_PASSWORD")
val fixedKeyAlias: String = System.getenv("NFC_KEY_ALIAS") ?: "nfc-card-lab"
val useFixedSigning = !fixedKeystoreFile.isNullOrEmpty() && !fixedKeystorePassword.isNullOrEmpty()

android {
    namespace = "io.github.cornming.nfccardlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.cornming.nfccardlab"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
    }

    signingConfigs {
        if (useFixedSigning) {
            create("fixed") {
                storeFile = file(fixedKeystoreFile!!)
                storePassword = fixedKeystorePassword
                keyAlias = fixedKeyAlias
                keyPassword = fixedKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (useFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
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
