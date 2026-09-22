plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.comprarador.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.comprarador.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // El model de reconeixement de text llatí s'inclou a l'APK i no es descarrega en el primer inici.
    implementation("com.google.mlkit:text-recognition:16.0.1") // Reserva explícita si PaddleOCR falla
    implementation(project(":ppocr-sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
}
