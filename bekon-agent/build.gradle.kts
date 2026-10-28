import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "pro.potoki.bekon.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "pro.potoki.bekon.agent"
        minSdk = 24
        targetSdk = 36
        versionCode = 36
        versionName = "1.0.35"
    }

    // full: GitHub/sideload APK — AccessibilityService + optional Bekon Keys IME (autonomous agent).
    // playStore: Google Play — guidance mode only (no AccessibilityService); MediaProjection + SAW overlays + OCR.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY_STORE", "false")
            buildConfigField("boolean", "GUIDANCE_MODE", "false")
            buildConfigField("boolean", "HAS_IME", "true")
        }
        create("playStore") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY_STORE", "true")
            buildConfigField("boolean", "GUIDANCE_MODE", "true")
            buildConfigField("boolean", "HAS_IME", "false")
        }
    }

    // Upload key for Google Play: read from env or (gitignored) local.properties. Never commit secrets.
    // Keys: BEKON_UPLOAD_STORE_FILE, BEKON_UPLOAD_STORE_PASSWORD, BEKON_UPLOAD_KEY_ALIAS, BEKON_UPLOAD_KEY_PASSWORD
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingProp(k: String): String? = System.getenv(k) ?: localProps.getProperty(k)
    val uploadStoreFile = signingProp("BEKON_UPLOAD_STORE_FILE")
    val hasUploadKey = uploadStoreFile != null && file(uploadStoreFile).exists() &&
        signingProp("BEKON_UPLOAD_STORE_PASSWORD") != null
    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = signingProp("BEKON_UPLOAD_STORE_PASSWORD")
                keyAlias = signingProp("BEKON_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = signingProp("BEKON_UPLOAD_KEY_PASSWORD") ?: signingProp("BEKON_UPLOAD_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without an upload key the release bundle is left unsigned (not uploadable).
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    // Ktor CIO server for the on-phone HTTP + MCP endpoint
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // QR
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.ktor:ktor-client-core:2.3.12")
    testImplementation("io.ktor:ktor-client-cio:2.3.12")

    // Bundled on-device OCR (Latin; Cyrillic UI fonts work well enough for guidance marks on modern ML Kit).
    // playStore guidance mode uses this; full flavor also gets it for shared code paths / tests.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
