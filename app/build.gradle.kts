import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) lives in the project root
// and is never published. Without it the build falls back to the debug key.
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "com.simpletvlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simpletvlauncher"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.6.1"
    }

    signingConfigs {
        if (ksProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += setOf("kotlin/**", "META-INF/*.version", "META-INF/com/android/build/gradle/**", "META-INF/version-control-info.textproto", "DebugProbesKt.bin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
