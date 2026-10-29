plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки GitHub Actions: каждая новая сборка получает версию выше прежней,
// поэтому телефон ставит её поверх старой без удаления.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.tongway.translator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tongway.translator"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        // Только процессоры телефонов: части для компьютеров (x86) удваивали размер файла.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Печать (ключ подписи) — одна для всех сборок, иначе Android не даст обновить приложение.
        // Настоящая печать хранится в секрете GitHub TONGWAY_KEYSTORE; сборка кладёт её во временный
        // файл и передаёт путь в TONGWAY_KEYSTORE_FILE. Без секрета — старая открытая печать из репозитория
        // (только чтобы сборка не ломалась, пока секрет не добавлен).
        create("tongway") {
            val secretKeystore = System.getenv("TONGWAY_KEYSTORE_FILE")
            if (secretKeystore != null) {
                storeFile = file(secretKeystore)
                storePassword = "tongway-release"
                keyAlias = "tongway"
                keyPassword = "tongway-release"
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tongway")
        }
        // Окончательная (не отладочная) версия: закрыта от копания внутри.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tongway")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Проверка стиля кода не должна останавливать сборку окончательной версии.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // Распознавание напечатанного латинского текста на снимке экрана (модель внутри приложения).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
