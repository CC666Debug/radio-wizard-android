plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.cc666debug.radiowizard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.cc666debug.radiowizard"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "1.12"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.mediarouter:mediarouter:1.9.0-beta01")
    implementation("com.google.android.gms:play-services-cast-framework:23.0.0")
}
