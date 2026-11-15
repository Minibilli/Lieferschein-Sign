plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.lieferschein.signer"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.lieferschein.signer"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Fester Schlüssel, damit neue Versionen sich als Update installieren lassen
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Barcode-/QR-Scanner (Google Code Scanner, braucht keine Kamera-Berechtigung)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // PDF bearbeiten (Unterschrift als Vektorgrafik einfügen)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Netzlaufwerke (SMB2/SMB3: Windows-Freigaben, NAS)
    implementation("com.hierynomus:smbj:0.13.0")
}

// pdfbox-android und smbj bringen BouncyCastle in zwei Varianten mit – nur eine behalten
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}
