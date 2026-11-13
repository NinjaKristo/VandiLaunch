plugins {
    id("com.android.application")
}

android {
    namespace = "app.vandilaunch"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vandilaunch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only ships org.json stubs; unit tests need a real one.
    testImplementation("org.json:json:20240303")
}
