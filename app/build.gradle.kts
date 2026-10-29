plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.musigatto.fosscore"
    // The same NDK as the musicxml module: packaging strips the native libraries with it. Without
    // it, they ship with their debug symbols (Verovio alone grows from about 10 MB to 100 MB per ABI).
    ndkVersion = "27.2.12479018"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.musigatto.fosscore"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // MuPDF ships one .so per ABI (~7-11 MB each). armeabi-v7a and x86 cover older 32-bit
        // tablets (minSdk 23): x86 is rare today but real, e.g. the Intel Atom Galaxy Tab 3 10.1.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // the sound bank is read in place, mapped from the APK: it must be stored uncompressed (its
    // samples are compressed already, in Ogg Vorbis)
    androidResources {
        noCompress += "sf3"
    }

    lint {
        // Translations follow the code at their own pace (contributors, Crowdin): a string not
        // translated yet shows in English, a key left in a translation is never shown. Reported,
        // but not blocking the build.
        warning += setOf("MissingTranslation", "ExtraTranslation")
    }

    // The language is chosen inside the app: a bundle that splits by language would not install
    // the strings of the chosen one (French on a Spanish tablet, for instance).
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Migration tests create old databases from the exported schemas.
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

// Room writes the schema of each database version here. These files are committed: they let
// tests check every migration, and Room generate automatic migrations.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.mupdf.fitz)
    implementation(project(":musicxml"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}