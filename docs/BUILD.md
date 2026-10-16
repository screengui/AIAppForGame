# Build and toolchain

Everything needed to build, test and ship the debug APK, plus the reasoning behind the versions that
are pinned.

---

## 1. Compatibility matrix

These versions are pinned to a mutually compatible, stable set. Moving any *one* of them without the
others is what breaks builds.

| Component | Version | Notes |
| --- | --- | --- |
| Gradle | **8.14.6** | `gradle/wrapper/gradle-wrapper.properties`; the wrapper (`./gradlew`) is committed, so no local Gradle install is needed. |
| Android Gradle Plugin | **8.13.2** | `gradle/libs.versions.toml` → `agp`. |
| Kotlin | **2.3.21** | `kotlin`; the Compose compiler plugin ships with Kotlin (`org.jetbrains.kotlin.plugin.compose`). |
| JDK | **17** | `kotlin { jvmToolchain(17) }` and `compileOptions`/`targetCompatibility`. Temurin 17 in CI. |
| `compileSdk` | **36** | AGP 8.13.2's maximum. |
| `targetSdk` | **36** | Declared behaviour: Android 14/15/16 restrictions are all in force. |
| `minSdk` | **26** | See §2. |
| AndroidX | core-ktx 1.17.0, lifecycle 2.9.4, activity-compose 1.11.0, Compose BOM 2025.09.01 | Deliberately held at the newest wave that still compiles against `compileSdk = 36`. |
| kotlinx-coroutines | 1.11.0 | Core (exposed as `api` by `:core`) + `-android` + `-test`. |
| JUnit 4 | 4.13.2 | Unit tests, both modules. AndroidX Test/Espresso for the device smoke test. |

### Why AndroidX is not on the newest release

Later AndroidX releases (core-ktx 1.18+, lifecycle 2.10+, activity 1.12+, Compose BOM 2025.11+) are
built against `android-37` and refuse to be consumed by AGP 8.x, which caps out at `compileSdk = 36`.
Adopting them means moving AGP to 9.x **and** Gradle to 9.x in the same commit. Lint reports the
newer versions as informational `GradleDependency` warnings; that is the intended state of this
repository, not an oversight. The upgrade path is in §6.

---

## 2. Why `minSdk = 26`

`MediaProjection` itself exists from API 21, so the capture requirement alone would allow a much
lower floor. 26 (Android 8.0 Oreo) was chosen because:

* **Notification channels and `startForegroundService()` are mandatory from API 26.** A screen
  capture session must run as a foreground service, and Android requires its notification. Targeting
  anything below 26 means maintaining a second, legacy notification code path for a shrinking set of
  devices — complexity in the most lifecycle-sensitive part of the app.
* **Adaptive launcher icons** (API 26) are used, so no legacy PNG icon set has to be maintained.
* **The foreground-service type model** that Android 10/14 tightened is declared and handled at
  runtime; the app checks `SDK_INT` where a newer API level genuinely changes what it must do.
* **Every device the project can be tested on is ≥ 26.** The brief targets a modern phone, and a
  lower floor would only add untestable code.

The cost is that Android 7.1 and older are unsupported, which the README states.

---

## 3. Build commands

```bash
# Everything that must pass before a commit
./gradlew :core:test :app:testDebugUnitTest      # unit tests (168 + 38)
./gradlew :core:lint :app:lintDebug              # Android lint, abortOnError = true
./gradlew :app:assembleDebug                     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest          # compiles the instrumented test sources

# Local-only: needs a connected phone with USB debugging, or a local emulator
./gradlew :app:connectedDebugAndroidTest
```

`:core:lint` only exists because `core/build.gradle.kts` applies `id("com.android.lint")`. Without
it, `:app:lint` treats `:core` as an opaque prebuilt jar and analyses nothing in it. The plugin is
applied **without a version** on purpose: the Android Gradle Plugin is already on the build
classpath (because `:app` applies it), and Gradle refuses a second, versioned request for a plugin
that is already loaded.

JDK 17 and an Android SDK (platform 36 + matching build-tools) are prerequisites for a local build.
`ANDROID_HOME`/`ANDROID_SDK_ROOT` must point at the SDK, or `local.properties` must contain
`sdk.dir=`. `local.properties` is git-ignored and must never be committed.

---

## 4. Continuous integration

`.github/workflows/android.yml`:

1. `actions/checkout` (pinned by full commit SHA),
2. `actions/setup-java` — Temurin 17,
3. `gradle/actions/setup-gradle` — Gradle + dependency caching,
4. `./gradlew :core:test :app:testDebugUnitTest`,
5. `./gradlew :core:lint :app:lintDebug`,
6. `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`,
7. upload `reports` (always) and `gameagent-debug-apk`.

Actions are pinned to full commit SHAs with the tag recorded in a comment, and the workflow runs with
`permissions: contents: read`. It never signs anything and holds no secrets.

**It does not run instrumented tests.** GitHub-hosted runners have no Android device, and this
project does not run an emulator in CI. `assembleDebugAndroidTest` is the CI-side guarantee that the
instrumented sources still compile; executing them is a local step.

---

## 5. Release builds and signing

No signing configuration exists in this repository, deliberately: a keystore or a password in Git is
a permanent leak. To produce a signed release:

1. Generate a keystore outside the repository and keep it out of Git
   (`*.jks`, `*.keystore`, `keystore.properties` are already ignored).
2. Add a `signingConfigs { release { ... } }` block to `app/build.gradle.kts` reading from an
   untracked `keystore.properties`, or from GitHub Actions secrets (`KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) decoded inside the workflow.
3. `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
4. Release builds have R8 (`isMinifyEnabled = true`) and resource shrinking on; re-run the unit tests
   and a smoke test on the minified output before distributing.
5. If the keystore is lost, you cannot update an installed app — back it up somewhere durable.

---

## 6. Moving the toolchain forward

Do it in one commit, in this order, verifying after each line:

1. AGP → 9.x in `gradle/libs.versions.toml`.
2. Gradle wrapper → the version AGP 9.x requires
   (`./gradlew wrapper --gradle-version X.Y` — commit `gradle-wrapper.jar` and
   `gradle-wrapper.properties` together).
3. `compileSdk`/`targetSdk` → 37 (AGP 9.x), reviewing any new behaviour-change requirement.
4. AndroidX + Compose BOM → the releases built against `android-37`.
5. `./gradlew :core:test :app:testDebugUnitTest :core:lint :app:lintDebug :app:assembleDebug` and fix
   whatever the new lint version reports. New AGP means new lint checks; treat them as work, not
   noise.

Do **not** bump Kotlin independently of AGP: the Compose compiler plugin version must match the
Kotlin version exactly, and AGP validates the Kotlin/AGP pairing at configuration time.

---

## 7. Gradle properties that matter

`gradle.properties` is tuned for a small CI runner and for a phone-first workflow:

| Property | Why |
| --- | --- |
| `org.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=768m` | Modest heap so the build runs on a small runner without being killed. |
| `kotlin.compiler.execution.strategy=in-process` | Avoids a second multi-hundred-MB JVM for the Kotlin daemon. |
| `org.gradle.parallel=true`, `org.gradle.caching=true` | Two modules, both cacheable. |
| `org.gradle.configuration-cache=false` | Kept off deliberately while AGP 8.x + Kotlin 2.x configuration-cache support settles; turning it on is a safe, separable experiment. |
| `android.useAndroidX=true`, `android.nonTransitiveRClass=true` | Required/desired defaults. |
| `android.defaults.buildfeatures.buildconfig=false` | No `BuildConfig` is used; disable it instead of generating an empty class. |
| `android.suppressUnsupportedCompileSdk=36` | Silences the "compileSdk is newer than the AGP-tested one" warning for a combination that is exercised by CI on every push. |
