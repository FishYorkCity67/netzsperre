import java.util.Properties

plugins {
    id("com.android.application")
}

val keyProps = Properties().apply {
    val f = rootProject.file("keystore/key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.netzsperre"
    compileSdk = 36

    defaultConfig {
        // App-ID nie aendern, sonst sind keine Updates ueber die vorhandene Installation moeglich.
        applicationId = "app.netzsperre"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/netzsperre.keystore")
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
