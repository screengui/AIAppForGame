# GameAgent — an Android AI companion for mobile games

GameAgent is a research prototype: an Android app that captures the screen with the user's explicit
permission, interprets what it can see, asks a decision-maker for an action, validates that action,
and executes it through a supported Android input mechanism — then looks again.

The long-term ambition is an AI that can play *Mobile Legends: Bang Bang* (MLBB) in a controlled
practice environment. **That capability does not exist yet, and this README says so plainly**: the
perception that ships today is a deterministic colour/geometry detector calibrated for the app's own
mock game screen, not a trained model for MLBB. What *is* finished and testable is the reliable
Android foundation the rest of the work needs.

---

## 1. What actually works today

| Area | Status | Detail |
| --- | --- | --- |
| Android app + dashboard | **Implemented** | Dark, mobile-first Compose UI: status, permission state, capture controls, live preview, frame metrics, AI status, detected state, latest decision, controller status, event feed, emergency stop. |
| Screen capture (MediaProjection) | **Implemented** | System consent dialog, foreground service of type `mediaProjection`, `VirtualDisplay` + `ImageReader`, explicit resource release, orientation/size-change handling, bounded frame buffer. |
| Screenshot processing | **Implemented** | Uniform resize with preserved aspect ratio, padded-row (stride) repacking, optional cropping, bounded queue that drops stale frames, per-stage latency metrics. |
| Perception | **Implemented (deterministic only)** | Colour/geometry detector with a calibratable `PerceptionProfile`. Reports `UNKNOWN` and low confidence instead of inventing detections. |
| Decision engine | **Implemented** | `GameAgent.decide(observation)` with a deterministic rule-based policy (no network, no key, no randomness) and an *optional* OpenAI-compatible vision provider behind the same interface. |
| Action validation | **Implemented** | `ActionValidator`: action-type allow-list, coordinate bounds, duration limits, rate limit, per-session action cap, global enable switch, confidence floor, geometry staleness, emergency stop. |
| Input controller | **Implemented** | Two targets: the app's own mock game loop (no permission needed) and an opt-in `AccessibilityService` that can reach other apps (single pointer, tap/swipe/long-press). |
| Session manager | **Implemented** | `IDLE → REQUESTING_PERMISSION → CAPTURING → PROCESSING → READY → RUNNING ⇄ PAUSED → STOPPING → ERROR` with validated transitions and a latched emergency stop. |
| Logging and diagnostics | **Implemented** | Bounded in-memory structured event log, latency trackers, diagnostics screen, user-triggered clear. Nothing is uploaded and nothing is written to disk except opt-in screenshots. |
| Local test interface | **Implemented** | A mock game HUD with a movable player token, draggable joystick area, ability buttons, enemies and a phase control; the *same* renderer feeds perception, so capture→perceive→decide→validate→act→observe runs end to end without MLBB and without permissions. |
| Unit tests | **Implemented** | 168 JVM tests in `:core` and 38 in `:app`, plus one device smoke test that needs a phone (`connectedDebugAndroidTest`). |
| Lint | **Implemented** | `lint.abortOnError = true`; `:core` applies `com.android.lint` so its sources are analysed rather than skipped. |
| CI + APK artifact | **Implemented** | `.github/workflows/android.yml` runs tests, lint, builds the debug APK and uploads it as a workflow artifact. |
| MLBB perception (trained model) | **Not implemented** | Milestone 5. No model is bundled, and none is claimed to work. |
| Multi-touch / joystick gestures | **Not implemented** | Android *can* express multi-stroke gestures, but whether a target app interprets synthesised multi-pointer input like a human one cannot be promised. The controller declares `supportsMultitouch = false`. |
| Standalone `RELEASE` action | **Not implemented** | Needs gesture continuation across dispatches, whose behaviour varies by target app. The controller refuses it with a reason instead of guessing. |
| Autonomous MLBB play | **Not implemented** | Explicitly out of scope until real perception and permitted-environment device tests exist. |
| Diagnostics upload | **Not implemented** | The settings switch exists for explicitness and changes no behaviour; there is no upload path at all. |

Anything not listed as implemented is *absent*, not hidden. No screen in the app shows a control that
does nothing.

---

## 2. Architecture

```text
                       ┌──────────────────────────────────────────────┐
   Android system      │                :app  (Android)               │
   consent dialog ────►│  MainActivity ─ GameAgentApp                 │
                       │    ├── DashboardScreen   ├── TestScreenScreen│
                       │    ├── DiagnosticsScreen ├── SettingsScreen  │
                       │  AppGraph (process-wide, hand-wired)         │
                       │    ├── ScreenCaptureService (foreground)     │
                       │    │     └── MediaProjectionCaptureEngine    │
                       │    │           VirtualDisplay + ImageReader  │
                       │    ├── AgentRuntime ────────────┐            │
                       │    ├── ScreenshotStore          │            │
                       │    ├── SettingsRepository       │            │
                       │    └── SecureKeyStore           │            │
                       │  GameAgentAccessibilityService ─┼─► InputController
                       │  AndroidHttpTransport ──────────┼─► HttpTransport
                       └─────────────────────────────────┼────────────┘
                                                         │  frames + geometry
                                                         ▼
                       ┌──────────────────────────────────────────────┐
                       │             :core  (pure Kotlin/JVM)          │
                       │  AgentSession  ← session state machine        │
                       │    ├── FrameBuffer        (bounded, drop-old) │
                       │    ├── PerceptionProvider → GameState         │
                       │    ├── GameAgent          → AgentDecision     │
                       │    ├── ActionValidator    → ValidatedAction   │
                       │    └── InputController    → ExecutionOutcome  │
                       │  CoordinateMapper · Diagnostics · MiniJson    │
                       │  fixtures: TestScene … (the mock game)        │
                       └──────────────────────────────────────────────┘
```

`:core` has **no Android dependency at all**. That is the single most important structural decision
in the project: perception, policy, validation, coordination and the mock game loop are all exercised
by JVM unit tests without a device, a Robolectric runtime or an emulator. See `ARCHITECTURE.md` for
module responsibilities, the data flow through one cycle, and the thread/ownership rules.

### The loop

```text
frame ──► GameState ──► AgentDecision ──► ValidatedAction ──► gesture ──► screen
  │           │               │                  │                │
  │           │               │                  │                └─ only ever reaches the
  │           │               │                  │                   controller as a validated
  │           │               │                  │                   pixel coordinate
  │           │               │                  └─ the ONLY input the controller accepts
  │           │               └─ plain data: type, target, point, duration, confidence
  │           └─ observed values only; unknown stays null
  └─ ImageFrame + ScreenGeometry (capture size + rotation + age)
```

No code path lets a raw model response reach the screen. `ValidatedAction` is the only type an
`InputController` accepts, and it can only be produced by `ActionValidator`.

---

## 3. Requirements

| Requirement | Value |
| --- | --- |
| Target device | Android 8.0 (API 26) or newer |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 36 / 36 |
| Build JDK | 17 (Temurin recommended) |
| Gradle | 8.14.6 (wrapper committed) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.21 |
| Root | **Not required** |
| Custom ROM / Termux / PC | **Not required** |
| Optional | An OpenAI-compatible vision endpoint + key (the app is fully usable without one) |

`minSdk = 26` rationale is in `docs/BUILD.md`. Nothing in the app needs a desktop: the APK can be
built by GitHub Actions and installed from the phone itself.

---

## 4. Building the APK from a phone

### 4.1 With GitHub Actions (no PC required)

1. Push to the repository (or run the workflow manually from the **Actions** tab → *Android CI* →
   **Run workflow**).
2. The workflow `Test, lint and build` runs:
   `:core:test :app:testDebugUnitTest` → `:core:lint :app:lintDebug` →
   `:app:assembleDebug :app:assembleDebugAndroidTest` → uploads `app-debug.apk`.
3. Open the finished run in the Actions tab.

### 4.2 Downloading the artifact on Android

1. On the phone, open the workflow run in a browser and sign in to GitHub.
2. Scroll to **Artifacts** and tap **gameagent-debug-apk**.
3. GitHub downloads a **`.zip`** containing `app-debug.apk`. (GitHub always wraps artifacts, even a
   single file, so a `.zip` is expected — not a build failure.)
4. Unzip it with the phone's file manager (Files by Google, My Files, ZArchiver, …).

### 4.3 Installing the APK

1. Move `app-debug.apk` somewhere you can find it (Downloads is fine).
2. Tap it. Android asks you to allow your file manager / browser to *install unknown apps* — allow it
   for that one app only. This is required for any APK obtained outside the Play Store.
3. Tap **Install**. Play Protect may warn that the app is from an unknown developer; choose
   *Install anyway*. The app requests no permission it does not need (see §5).
4. Open **GameAgent**. The dashboard should show *Session: IDLE* and *Input: disabled*.

### 4.4 Building locally (optional)

```bash
# JDK 17 and an Android SDK (platform 36, build-tools matching) are required.
./gradlew :core:test :app:testDebugUnitTest    # unit tests
./gradlew :core:lint :app:lintDebug            # lint
./gradlew :app:assembleDebug                   # -> app/build/outputs/apk/debug/app-debug.apk
```

Signed release builds are deliberately **not** configured; the repository contains no keystore. The
documented release steps are in `README` §12 and `docs/BUILD.md`.

---

## 5. Android permissions

| Permission | Why | When it is used |
| --- | --- | --- |
| *(MediaProjection consent)* | Capturing the screen needs the system consent dialog; there is no manifest permission for it. | Only when you press **Start capture**. Declining is handled gracefully. |
| `FOREGROUND_SERVICE` | Android requires a foreground service to hold a projection. | While capture is running. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required since API 34 for a `mediaProjection`-type service. | Same. |
| `POST_NOTIFICATIONS` | The capture notification is mandatory; without permission it may not be visible. | Requested on first launch on Android 13+. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Only for the **optional** AI provider. | Never, unless you configure a provider and select it. |
| *(Accessibility service)* | Lets the app deliver gestures to other apps. | Only if you enable *GameAgent input controller* yourself in Android Settings; the app cannot and does not enable it. |

The app never requests the "draw over other apps" permission, never uses root, hidden/system APIs, or
any anti-cheat evasion technique.

---

## 6. Using the screenshot preview

1. Open **GameAgent** and stay on *Dashboard*.
2. *(Recommended)* Tap **Allow action execution** → leave it **off** until you have read §7. Capture
   and analysis work with it off.
3. Tap **Start capture** and accept the system dialog. If you decline, the app reports the denial and
   returns to `IDLE` — nothing is bypassed.
4. The thumbnail updates on the interval shown (*Preview: every 400 ms*), together with the capture
   size, frames captured, dropped and skipped, and the age of the newest frame.
5. Turn the preview off in *Settings → Show the live preview* to stop spending work on the bitmap;
   analysis continues at the same rate.
6. Tap **Stop capture** (or **EMERGENCY STOP**) to release the projection, the virtual display and
   the image reader. Release is logged in *Diagnostics*.

---

## 7. Running the local test interface

The **Test screen** tab is a complete mock game: a player token, a draggable joystick region, ability
buttons, enemies and a phase control. It is rendered by the same code the fixtures use, so the agent
genuinely perceives it from pixels.

1. Open the **Test** tab. Confirm the mock HUD renders.
2. *Settings → Input target* is **TEST_SCENE** by default, so actions go to this mock loop and to
   nothing else.
3. Press **Analyse one frame** for a single deterministic cycle: perceive → decide → validate →
   act → observe. The decision, its confidence, its explanation and whether it was accepted or
   rejected (with a reason) appear immediately.
4. Flip **Run the agent on this test screen** on to let the loop run continuously. It feeds rendered
   frames straight into the session — no capture permission needed.
5. Enable *Allow action execution* on the dashboard to let validated actions actually move the token
   and increment *Actions applied to the scene*.
6. Press **EMERGENCY STOP** at any time. The stop is latched: pending work is cancelled, the
   controller is released, and further actions are refused until you explicitly reset it.

Every scenario asked for in the brief — permission denied, no frames, invalid model response,
out-of-bounds coordinates, emergency stop mid-session, backgrounding, screen-size change, provider
unavailable, repeated start/stop, restart after interruption — has a unit test in `:core`. The
full list, with test names, is in `CONTRIBUTING.md`.

---

## 8. Known Android limitations (documented, not hidden)

* **A normal app cannot inject touches into another app.** There is no such public API. The support
  mechanism is an `AccessibilityService` with `canPerformGestures`, which the user must enable in
  system settings and which Android shows in the accessibility list. See `docs/INPUT_MECHANISMS.md`
  for the full comparison of mechanisms.
* **Screen capture consent cannot be bypassed**, and the consent token expires: a stopped projection
  cannot be resumed without asking the user again.
* **Android 14+ requires the foreground service to start before `getMediaProjection()`**; the app
  does this, and requires `FOREGROUND_SERVICE_MEDIA_PROJECTION` to be granted.
* **OEM battery optimisation may kill the service** when the app is backgrounded. The app requests no
  exemption and reports the interruption instead of pretending it can run forever.
* **Gesture limits:** at most 20 strokes per gesture and 60 s total duration. `GesturePlanner`
  enforces both client-side so a refusal is reported rather than silently dropped.
* **Single pointer only.** Multi-touch is expressible but not dependable across apps, so it is not
  produced.
* **No standalone `RELEASE`.** A `HOLD` is dispatched as one completed long-press stroke.
* **The bundled perception is not trained on MLBB.** Against a real match it will report `UNKNOWN`
  and low confidence, which the policy correctly turns into `WAIT`.
* **CI cannot run instrumented tests**: GitHub-hosted runners have no Android device, and this
  project does not run an emulator in CI.

---

## 9. Privacy and security

* Screenshots never leave the device. There is no upload path in this build.
* An optional cloud provider receives **compact numbers only** — a summary of the detected state —
  never screenshots, never the event log, never the API key back.
* The AI API key is stored with `EncryptedSharedPreferences` (AES-256-GCM) using a key held in the
  Android Keystore; it is never written to preferences, logs, backups or the UI. `allowBackup="false"`.
* The event log is bounded (400 records), in memory only, and the diagnostics screen can clear it.
* Opt-in screenshot storage is capped at 20 files / 64 MiB, pruned oldest-first, and deletable from
  *Settings*.
* No secret, key or token exists anywhere in this repository — not in CI, not in Gradle files.
* Nothing in the app tries to evade detection, hide itself, or interact with ranked matchmaking.

---

## 10. Current AI capabilities and limitations

**What the decision layer can do today**

* Read a `GameState` (phase, health, mana, abilities, enemies, objectives, minimap notes) in which
  every unobserved field is `null` rather than a plausible default.
* Apply a documented deterministic policy: engage a confidently located enemy, use a ready ability
  when health is low, walk to an objective, otherwise wait.
* Explain itself: every decision carries a one-line human-readable reason, shown verbatim in the UI.

**What it cannot do**

* It cannot see MLBB. The perception profile is calibrated for the app's own mock HUD.
* It cannot recognise heroes, items, cooldowns or the minimap from a real match.
* Its decisions are bounded heuristics, not learned play.

**Optional vision provider**

An OpenAI-compatible `chat/completions` endpoint can be configured with a base URL, model name and
key. It receives a compact JSON observation and must reply with a strict JSON action proposal. Every
response is parsed defensively: unknown action types, out-of-range coordinates, missing fields,
oversized bodies and slow responses are rejected, and **any** failure silently falls back to the
deterministic policy. The provider can never make the app act when the deterministic path would
refuse, because validation happens after both. Costs are billed by your provider; the app makes at
most one request per decision cycle at the interval you configure.

---

## 11. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| **Start capture** does nothing | The consent dialog was dismissed, or the notification permission is missing on Android 13+. Check *Diagnostics* for the reason; the app always logs one. |
| Permission granted but the preview stays black | The screen is off or an app using a secure window is in front (DRM/secure surfaces produce empty frames). Wake the phone and try again. |
| Capture stops when I leave the app | OEM battery optimisation killed the foreground service. Exclude *GameAgent* from battery optimisation in system settings; the app reports the interruption when you return. |
| Actions are refused with `ACTIONS_DISABLED` | *Allow action execution* is off. This is the shipped default. |
| Actions are refused with `STALE_GEOMETRY` | Rotation or a size change invalidated the mapping; coordinates are never reused across it. Wait for the next frame. |
| Actions are refused with `OUT_OF_BOUNDS` | The decision was aimed outside the captured surface. Usually a sign perception is out of calibration for the current screen. |
| Accessibility target refuses to act | Enable *GameAgent input controller* in Android Settings → Accessibility, then return to the app (it re-reads the state on resume). |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An APK with the same application id but a different signature is installed. Uninstall the old one first. |
| Play Protect blocks the install | Expected for a self-built APK: choose *Install anyway*, or turn off scanning for that single install. |
| Gradle build fails in CI with an SDK error | AGP 8.13.2 needs platform 36. The workflow's `gradle/actions` step installs what `compileSdk` requires; run `./gradlew --refresh-dependencies` locally if a stale cache is suspected. |

---

## 12. Signed release builds (documented, not configured)

The repository contains no signing material by design. To produce a signed release:

1. Generate a keystore **outside** the repository:

   ```bash
   keytool -genkeypair -v -keystore ~/gameagent-release.jks -keyalg RSA -keysize 4096 \
           -validity 10000 -alias gameagent
   ```

2. Keep it and its passwords out of Git (`*.jks`, `keystore.properties` are already ignored).
3. Provide credentials to the build either through a local, untracked `keystore.properties` or
   through GitHub Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`), decode them inside the workflow, and add a `signingConfig` to
   `app/build.gradle.kts`'s release build type.
4. `./gradlew :app:assembleRelease` — the artifact is `app/build/outputs/apk/release/app-release.apk`.
5. Consider enabling R8 fully (already on for release) and re-running the unit tests against the
   minified output before distributing.

---

## 13. Repository layout

```text
app/                          Android application module
  src/main/kotlin/com/gameagent/app/
    capture/                  MediaProjection engine, foreground service, screenshot store
    data/                     settings, encrypted key store
    image/                    ImageReader stride repacking
    input/                    accessibility controller, gesture planner, service
    provider/                 Android HTTP transport
    runtime/                  AgentRuntime: the wiring between UI, capture and :core
    ui/                       Compose screens, theme, components
  src/test/                   JVM unit tests (settings codec, gesture planner, sizing, strides)
  src/androidTest/            device smoke test (requires a phone)
core/                         pure-Kotlin module: all the logic worth testing
  src/main/kotlin/com/gameagent/core/
    agent/                    GameAgent interface + deterministic policy
    diagnostics/              event log, metrics, latency trackers
    fixtures/                 the mock game: scene, renderer, test controller
    geometry/                 normalised coordinates, CoordinateMapper
    input/                    InputController + capabilities + recording controller
    model/                    GameState, AgentDecision, ValidatedAction, SessionState
    perception/               provider interface, heuristic detector, profiles
    pipeline/                 FrameBuffer
    provider/                 optional vision provider, strict response parser
    session/                  AgentSession + state machine
    validation/               ActionValidator, emergency stop
  src/test/                   168 JVM tests
docs/                         BUILD.md, ROADMAP.md, INPUT_MECHANISMS.md
.github/workflows/android.yml CI: test, lint, build, upload APK
```

---

## 14. Roadmap

`docs/ROADMAP.md` holds the milestone detail. In short:

* **M1–M4 (done):** project, capture, mock AI pipeline, test controller.
* **M5 (next):** real vision — evaluate lightweight on-device options, label a representative
  screenshot set from a permitted practice environment, measure latency and memory, add confidence
  thresholds.
* **M6:** first-class AI provider hardening — streaming budgets, request accounting, cost display.
* **M7:** compatibility evaluation on the target device, documented unsupported interactions, and
  only then any claim about practice-mode play.
