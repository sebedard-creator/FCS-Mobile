plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.fcsmobile.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fcsmobile.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 13
        versionName = "2.8.1"
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
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // OkHttp for file uploads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Persistent backups, constrained to charging and a local Wi-Fi connection.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
}
