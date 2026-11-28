plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.flactify.roborazzi.poc"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("-noverify")
        }
    }
}

dependencies {
    // Roborazzi 1.79.0 is compiled against the current Compose runtime API.
    // The app remains on 2024.12.01; this newer BOM is isolated to the PoC.
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("junit:junit:4.13.2")
}

// Robolectric downloads its Android SDK jar from Maven at test runtime. Cloud's
// Java test worker does not inherit Gradle's proxy JVM flags automatically, so
// forward them only when explicitly supplied by the caller.
val robolectricProxyHost = providers.gradleProperty("robolectricProxyHost").orNull
val robolectricProxyPort = providers.gradleProperty("robolectricProxyPort").orNull
val javaTrustStore = providers.gradleProperty("javaTrustStore").orNull
tasks.withType<Test>().configureEach {
    robolectricProxyHost?.let { systemProperty("robolectric.dependency.proxy.host", it) }
    robolectricProxyPort?.let { systemProperty("robolectric.dependency.proxy.port", it) }
    javaTrustStore?.let {
        systemProperty("javax.net.ssl.trustStore", it)
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
    }
}
