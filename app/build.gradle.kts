plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de version fourni par GitHub Actions (-PversionCode=N), 1 en local.
val appVersion = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.faxydre.smbsynchdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.faxydre.smbsynchdroid"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersion
        versionName = appVersion.toString()
    }

    signingConfigs {
        // Clé fixe pour que chaque nouvelle version s'installe par-dessus l'ancienne.
        create("fixed") {
            storeFile = file("smbsynchdroid.keystore")
            storePassword = "smbsynchdroid"
            keyAlias = "smbsynchdroid"
            keyPassword = "smbsynchdroid"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.hierynomus:smbj:0.13.0")
}
