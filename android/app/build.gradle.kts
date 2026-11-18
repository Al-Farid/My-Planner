plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every GitHub build gets a higher version number, so a new APK installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.alfarid.planner"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.alfarid.planner"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed signing key, so updates install over the previous version without uninstalling.
    signingConfigs {
        create("shared") {
            storeFile = file("planner.keystore")
            storePassword = "plannerwidget"
            keyAlias = "planner"
            keyPassword = "plannerwidget"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore")
}
