plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eagalts.still"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eagalts.still"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.9"
    }

    // Ключ и пароли в git не лежат: ~/.gradle/gradle.properties (still.storePassword и т. д.)
    // или переменные окружения STILL_STORE_FILE / STILL_STORE_PASSWORD / STILL_KEY_ALIAS / STILL_KEY_PASSWORD.
    fun secret(prop: String, env: String): String? =
        (findProperty(prop) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    val keystore = rootProject.file(secret("still.storeFile", "STILL_STORE_FILE") ?: "still.keystore")
    val storePass = secret("still.storePassword", "STILL_STORE_PASSWORD")
    val hasKey = keystore.isFile && storePass != null

    signingConfigs {
        if (hasKey) create("still") {
            storeFile = keystore
            storePassword = storePass
            keyAlias = secret("still.keyAlias", "STILL_KEY_ALIAS") ?: "still"
            keyPassword = secret("still.keyPassword", "STILL_KEY_PASSWORD") ?: storePass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // своего ключа ещё нет — подписываем отладочным: release встаёт поверх прежних сборок
            signingConfig = if (hasKey) signingConfigs.getByName("still") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.maxHeapSize = "2g"
            it.systemProperty("still.lang", "ru")
            // в Robolectric нет Android Keystore — ключ только в памяти
            it.systemProperty("still.vaultSoftware", "1")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // «Поделиться» без места съёмки: чистка EXIF/XMP в копии
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // видео и живые фото — только в просмотре: плеер создаётся при открытии и освобождается при закрытии
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // базовые профили Compose и библиотек ставятся при установке APK — код ленты сразу скомпилирован, без «разгона»
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
