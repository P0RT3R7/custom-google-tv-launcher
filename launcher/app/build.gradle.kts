import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadProps(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProps = loadProps("keystore.properties")
// Optional: trakt.properties (git-ignored) with trakt.clientId / trakt.clientSecret.
val traktProps = loadProps("trakt.properties")

android {
    namespace = "com.custom.hisenselauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.custom.hisenselauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${traktProps.getProperty("trakt.clientId", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${traktProps.getProperty("trakt.clientSecret", "")}\"")
    }

    buildFeatures { buildConfig = true }

    // keystore.properties (git-ignored) is optional; without it, release builds use the local debug key.
    val hasKeystore = rootProject.file("keystore.properties").exists()
    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile", "../release.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
