plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing reads its key from ~/.gradle/gradle.properties (never from the repository):
//   quietHome.storeFile, quietHome.storePassword, quietHome.keyAlias, quietHome.keyPassword
// Without them, as on CI or for other builders, release builds fall back to the debug key.
val releaseStoreFile: String? = providers.gradleProperty("quietHome.storeFile").orNull

android {
    namespace = "io.github.nicolaspogorzelski.quiethome"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nicolaspogorzelski.quiethome"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("quietHome.storePassword").get()
                keyAlias = providers.gradleProperty("quietHome.keyAlias").get()
                keyPassword = providers.gradleProperty("quietHome.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
