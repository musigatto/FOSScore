import java.io.IOException
import java.net.URI
import java.security.MessageDigest

// MusicXML support: lays out MusicXML scores for the screen with Verovio (LGPL-3.0, built from
// its release source, without any change) and draws the pages through MuPDF. See README.md.
plugins {
    alias(libs.plugins.android.library)
}

// Verovio's release, as RISM publishes it on GitHub. To update it: change both values (the
// checksum is the SHA-256 of the .zip below) and DATA_VERSION in Verovio.kt.
val verovioVersion = "6.3.0"
val verovioSha256 = "c6d64d024febd233152c69a7ed9cb67f1a2bae0c15d2248f6ac1727f96dff25a"

/**
 * The source of Verovio [version], downloaded by the first build like any other library, and
 * checked against [sha256] before use. It is kept in `third_party/` (ignored by Git and by
 * Clean), so later builds work offline.
 *
 * This runs while Gradle reads the build, not in a task: Android Studio runs CMake as soon as it
 * syncs the project, and CMake needs the source then.
 */
fun verovioSource(version: String, sha256: String): File {
    val parent = file("third_party")
    val dir = parent.resolve("verovio-$version")
    if (dir.resolve("cmake/CMakeLists.txt").isFile) return dir

    val url = "https://github.com/rism-digital/verovio/archive/refs/tags/version-$version.zip"
    logger.lifecycle("Downloading Verovio $version (first build only): $url")
    parent.mkdirs()
    val zip = parent.resolve("verovio-$version.zip.part")
    try {
        URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
    } catch (e: IOException) {
        zip.delete()
        throw GradleException("Could not download Verovio $version from $url: the first build needs an internet connection", e)
    }
    val actual = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
    if (actual != sha256) {
        zip.delete()
        throw GradleException("Verovio $version from $url is not the expected file (SHA-256 $actual)")
    }
    // unpacked beside, then renamed: an interrupted build never leaves a half source tree behind
    val unpacked = parent.resolve("verovio-$version.part")
    delete(unpacked)
    copy {
        from(zipTree(zip))
        into(unpacked)
    }
    check(unpacked.resolve("verovio-version-$version").renameTo(dir)) { "could not move Verovio into $dir" }
    delete(unpacked, zip)
    return dir
}

val verovioDir: File = verovioSource(verovioVersion, verovioSha256)

android {
    namespace = "com.musigatto.fosscore.musicxml"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // The app keeps only the ABIs it ships (its own abiFilters).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DVEROVIO_DIR=${verovioDir.invariantSeparatorsPath}",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * Copies Verovio's font data into the assets. Only the default music font (Leipzig), the
 * fallback (Bravura) and the text fonts are shipped: the other music fonts add about 4 MB.
 */
abstract class CopyVerovioData : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(dataDir) {
                exclude("Gootville*", "Gootville/**", "Leland*", "Leland/**", "Petaluma*", "Petaluma/**")
                into("verovio/data")
            }
            into(outputDir)
        }
    }
}

val copyVerovioData = tasks.register<CopyVerovioData>("copyVerovioData") {
    dataDir.set(verovioDir.resolve("data"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyVerovioData, CopyVerovioData::outputDir)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mupdf.fitz)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
