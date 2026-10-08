import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val releaseStoreFile = localProps.getProperty("FLACTIFY_STORE_FILE")
    ?: System.getenv("FLACTIFY_STORE_FILE")
val releaseStorePassword = localProps.getProperty("FLACTIFY_STORE_PASSWORD")
    ?: System.getenv("FLACTIFY_STORE_PASSWORD")
val releaseKeyAlias = localProps.getProperty("FLACTIFY_KEY_ALIAS")
    ?: System.getenv("FLACTIFY_KEY_ALIAS")
val releaseKeyPassword = localProps.getProperty("FLACTIFY_KEY_PASSWORD")
    ?: System.getenv("FLACTIFY_KEY_PASSWORD")

val steamAudioSdkSha256 = "4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449"
val steamAudioLicenseSha256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
val cipic124SofaSha256 = "c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6"
val steamAudioSdkUrl =
    "https://github.com/ValveSoftware/steam-audio/releases/download/v4.8.1/steamaudio_4.8.1.zip"
val steamAudioLicenseUrl =
    "https://raw.githubusercontent.com/ValveSoftware/steam-audio/v4.8.1/LICENSE.md"
val cipic124SofaUrl =
    "https://raw.githubusercontent.com/ValveSoftware/steam-audio/v4.8.1/core/data/hrtf/cipic_124.sofa"

fun verifiedSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

val steamAudioSdkRoot = layout.buildDirectory.dir("generated/steamAudioSdk")
val configuredSteamAudioSdkZip = providers.gradleProperty("flactifySteamAudioSdkZip")
    .orElse(providers.environmentVariable("FLACTIFY_STEAMAUDIO_SDK_ZIP"))
val stageSteamAudioSdk = tasks.register("stageSteamAudioSdk") {
    inputs.property("sdkArchiveSha256", steamAudioSdkSha256)
    inputs.property("cipic124SofaSha256", cipic124SofaSha256)
    inputs.property("configuredArchivePath", configuredSteamAudioSdkZip.orElse("download"))
    outputs.dir(steamAudioSdkRoot)

    doLast {
        val sdkArchive = configuredSteamAudioSdkZip.orNull?.let(::file)
            ?: layout.buildDirectory.file("downloads/steamaudio_4.8.1.zip").get().asFile
        val licenseFile = layout.buildDirectory.file("downloads/steam-audio-4.8.1-LICENSE.md").get().asFile
        val cipic124SofaFile =
            layout.buildDirectory.file("downloads/steam-audio-4.8.1-cipic_124.sofa").get().asFile

        fun download(url: String, destination: File, expectedSha256: String) {
            if (!destination.exists()) {
                destination.parentFile.mkdirs()
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }.getInputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
            check(verifiedSha256(destination) == expectedSha256) {
                "SHA-256 mismatch for ${destination.name}"
            }
        }

        if (configuredSteamAudioSdkZip.isPresent) {
            check(sdkArchive.isFile) {
                "The configured Steam Audio SDK archive does not exist: $sdkArchive"
            }
            check(verifiedSha256(sdkArchive) == steamAudioSdkSha256) {
                "SHA-256 mismatch for ${sdkArchive.name}"
            }
        } else {
            download(steamAudioSdkUrl, sdkArchive, steamAudioSdkSha256)
        }
        download(steamAudioLicenseUrl, licenseFile, steamAudioLicenseSha256)
        download(cipic124SofaUrl, cipic124SofaFile, cipic124SofaSha256)

        val outputRoot = steamAudioSdkRoot.get().asFile
        outputRoot.deleteRecursively()
        val abiDirectories = mapOf(
            "armeabi-v7a" to "android-armv7",
            "arm64-v8a" to "android-armv8",
            "x86" to "android-x86",
            "x86_64" to "android-x64"
        )
        ZipFile(sdkArchive).use { zip ->
            fun extract(entryPath: String, destination: File) {
                val entry = zip.getEntry(entryPath)
                    ?: error("Steam Audio SDK archive is missing $entryPath")
                destination.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }

            extract("steamaudio/include/phonon.h", File(outputRoot, "include/phonon.h"))
            extract(
                "steamaudio/include/phonon_version.h",
                File(outputRoot, "include/phonon_version.h")
            )
            extract(
                "steamaudio/THIRDPARTY.md",
                File(outputRoot, "assets/licenses/steam-audio-4.8.1-THIRDPARTY.md")
            )
            abiDirectories.forEach { (abi, sdkPlatform) ->
                extract(
                    "steamaudio/lib/$sdkPlatform/libphonon.so",
                    File(outputRoot, "jniLibs/$abi/libphonon.so")
                )
            }
        }
        licenseFile.copyTo(
            File(outputRoot, "assets/licenses/steam-audio-4.8.1-LICENSE.md"),
            overwrite = true
        )
        cipic124SofaFile.copyTo(
            File(outputRoot, "assets/hrtf/cipic_124.sofa"),
            overwrite = true
        )
    }
}

android {
    namespace = "com.flactify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flactify"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "2.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val lastfmKey = localProps.getProperty("LASTFM_API_KEY")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("LASTFM_API_KEY").orEmpty()
        val escapedLastfmKey = lastfmKey
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "LASTFM_API_KEY", "\"$escapedLastfmKey\"")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile?.let(::file)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".spatialdev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        create("releaseValidation") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasevalidation"
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            pickFirsts += "META-INF/AL2.0"
            pickFirsts += "META-INF/LGPL2.1"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.jvmArgs("-noverify")
        }
    }
    sourceSets.getByName("androidTest").apply {
        assets.srcDir("src/test/resources")
    }
    sourceSets.getByName("main").apply {
        jniLibs.srcDir(steamAudioSdkRoot.get().dir("jniLibs").asFile)
        assets.srcDir(steamAudioSdkRoot.get().dir("assets").asFile)
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

tasks.configureEach {
    if (
        name == "preBuild" ||
        name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        name.contains("JniLibFolders")
    ) {
        dependsOn(stageSteamAudioSdk)
    }
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// ⚠️ ここから下が重要です！ implementation はこの dependencies の中に入れます
val jaudiotaggerOverride = providers.gradleProperty("flactifyJaudiotaggerJar")
    .orNull
    ?.let(::file)

if (jaudiotaggerOverride != null) {
    require(jaudiotaggerOverride.isFile) {
        "flactifyJaudiotaggerJar must point to an existing compatible JAR"
    }
}

tasks.register("verifyJaudiotaggerOverride") {
    doLast {
        val suppliedJar = jaudiotaggerOverride
            ?: error("Set -PflactifyJaudiotaggerJar=/path/to/compatible.jar")
        val resolvedFiles = configurations
            .getByName("releaseValidationRuntimeClasspath")
            .incoming.artifacts.artifacts
            .map { it.file.canonicalFile }
        check(suppliedJar.canonicalFile in resolvedFiles) {
            "The supplied jaudiotagger JAR was not selected for releaseValidation"
        }
        check(resolvedFiles.none { it.name == "jaudiotagger-3.0.1.jar" }) {
            "The stock jaudiotagger Maven artifact is still present in releaseValidation"
        }
    }
}

dependencies {
    val media3_version = "1.11.1"

    // Core & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 音楽再生 (Media3)
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-session:$media3_version")
    implementation("androidx.media3:media3-common:$media3_version")

    // アイコン
    implementation("androidx.compose.material:material-icons-extended")

    // ファイル操作
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.mockk:mockk-agent-jvm:1.13.8")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ⭕ Coil (Jetpack Compose用) を追加。これが超高速キャッシュと非同期読み込みを担います
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.media3:media3-datasource-okhttp:$media3_version")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    if (jaudiotaggerOverride == null) {
        implementation("net.jthink:jaudiotagger:3.0.1")
    } else {
        implementation(files(jaudiotaggerOverride))
    }


}
