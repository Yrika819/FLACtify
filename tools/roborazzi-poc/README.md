# Roborazzi Compose Cloud PoC

This is an isolated, synthetic-only JVM screenshot test. It is intentionally a
separate Gradle build under `tools/` and is not included by the app's root
`settings.gradle.kts`; it cannot alter the app dependency graph.

The test uses Roborazzi `1.79.0` (Apache-2.0), Robolectric `4.18.1`, and an
isolated Compose BOM (`2026.02.01`). The app itself remains on Compose BOM
`2024.12.01`; this newer BOM is required by the current Roborazzi runtime API
and is intentionally not added to the app. It captures only the literal text
`Synthetic FLACtify fixture`; no application or private media data is loaded.

From the repository root:

```sh
./gradlew -p tools/roborazzi-poc :poc:testDebugUnitTest \
  -Proborazzi.test.record=true \
  -ProbolectricProxyHost=proxy -ProbolectricProxyPort=8080 \
  -PjavaTrustStore=/etc/ssl/certs/java/cacerts \
  --no-daemon \
  -Dorg.gradle.java.home=/workspace/flactify-toolchains/flactify-temurin-21 \
  -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit
```

The proxy/trust-store properties are needed only in this Cloud image. Robolectric
downloads its `android-all-instrumented` jar during the JVM test and does not use
Gradle's proxy flags unless they are forwarded to the test worker.

The recorded PNG is disposable output under
`tools/roborazzi-poc/poc/build/outputs/roborazzi/` and is ignored by the
repository's `**/build/` rule. The PoC is a compatibility probe, not app UI
acceptance or device validation.
