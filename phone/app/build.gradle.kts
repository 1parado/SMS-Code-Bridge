plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parado.smsbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parado.smsbridge"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.4.0"
    }

    signingConfigs {
        // 正式签名只在 CI 提供 keystore 时使用，未配置则回退调试签名
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 用于「关于」页与版本号一致性测试
        buildConfig = true
    }

    lint {
        // 先不把 lint 作为合并门禁，待 UI 稳定后再收紧
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 仅测试用：Android 运行时使用系统内置的 org.json，不打包进 APK
    testImplementation("org.json:json:20240303")
}
