# Architecture

This document explains how GameAgent is put together, why the boundaries sit where they do, and what
flows through one cycle of the loop.

---

## 1. Module split

```text
:app   →  Android application. Depends on :core.
:core  →  Pure Kotlin/JVM. Depends on nothing Android. Depended on by :app.
```

The dependency arrow only ever points one way. `:core` is compiled as a plain `java-library` with
the Kotlin JVM plugin, so:

* it can be unit tested on the JVM with no emulator, no Robolectric and no instrumentation;
* its logic cannot accidentally reach for `Context`, a `View`, or an Android API;
* the Android-specific surface (permissions, services, Compose, the Keystore, `ImageReader`) is
  forced into `:app`, where it is thin enough to inspect by eye.

`:core` also applies `com.android.lint` (without a version — the Android Gradle Plugin is already on
the build classpath via `:app`) so `lint` analyses its sources instead of skipping them as an opaque
prebuilt jar.

### `:core` packages

| Package | Responsibility |
| --- | --- |
| `model` | `GameState`, `AgentDecision`, `ValidatedAction`, `ActionType`, `SessionState`, `RejectionCode`, primitives. Pure data with `init` validation. |
| `geometry` | `NormalizedPoint`, `PixelPoint`, `Bounds`, `ScreenGeometry`, `CoordinateMapper`. Normalised coordinates are the *only* thing that crosses a stage boundary. |
| `perception` | `PerceptionProvider` interface, `HeuristicScreenPerceptionProvider`, `PerceptionProfile`, `ImageFrame`. |
| `agent` | `GameAgent` interface, `AgentPolicyConfig`, `DeterministicPolicyAgent`. |
| `validation` | `ActionValidator`, `ValidationConfig`, `ValidationContext`, `ValidatorStats`, `EmergencyStop`. |
| `input` | `InputController` interface, `ControllerCapabilities`, `ExecutionOutcome`, plus `RecordingInputController` and `UnavailableInputController` for tests and dry runs. |
| `session` | `AgentSession` (the coordinator) and `SessionStateMachine`. |
| `pipeline` | `FrameBuffer`, the bounded drop-oldest queue between capture and processing. |
| `provider` | `VisionModelProvider`, `HttpTransport`, `DecisionParser`, `OpenAiCompatibleVisionProvider`, `MiniJson`. |
| `diagnostics` | `DiagnosticEvent`, `DiagnosticEventSink`, `DiagnosticEventLog`, `LatencyTracker`, `SessionMetrics`, `CaptureMetrics`. |
| `fixtures` | The mock game: `TestSceneState`, `SyntheticSceneRenderer`, `TestScene`, `TestScenePerceptionProfile`, `TestSceneInputController`. |

### `:app` packages

| Package | Responsibility |
| --- | --- |
| `capture` | `MediaProjectionCaptureEngine` (projection + `VirtualDisplay` + `ImageReader`), `ScreenCaptureService` (foreground service), `CaptureSizeCalculator`, `ScreenshotStore`, `CaptureState`. |
| `image` | `ImageBufferLayout` — repacking `ImageReader` buffers whose `rowStride` is padded. |
| `input` | `GameAgentAccessibilityService`, `AccessibilityInputController`, `GesturePlanner`. |
| `data` | `AppSettings` + `AppSettingsCodec` (pure, testable), `SettingsRepository`, `SecureKeyStore` (Keystore-backed API key). |
| `provider` | `AndroidHttpTransport` — the `HttpTransport` implementation backed by `HttpURLConnection`. |
| `runtime` | `AgentRuntime` — owns the session, adapts capture frames into `:core`, and exposes everything the UI observes as `StateFlow`s. `DelegatingProviders` lets perception and the agent be swapped live when settings change. |
| `ui` | `MainActivity`, `GameAgentApp` (navigation + emergency-stop affordance), `DashboardScreen`, `TestScreenScreen`, `DiagnosticsScreen`, `SettingsScreen`, `theme`, reusable `components`. |
| root | `GameAgentApplication` owns the process-wide `AppGraph`. |

---

## 2. Why a hand-wired graph

`AppGraph` is a plain class constructed once in `GameAgentApplication`. Reasons:

* Nine singleton-scoped objects, every one of which needs a `Context` anyway.
* Construction order matters and is easy to read: the capture engine must exist before the runtime
  that consumes its frames.
* The graph must have **process** lifetime, not activity lifetime: `MediaProjection` is owned by a
  foreground service, and the accessibility service must be able to reach the same session while no
  activity is on screen. Surviving rotation falls out of that for free.
* No annotation processor to add build time to a project whose CI runs on a small runner.

---

## 3. Interfaces that define the seams

```kotlin
interface PerceptionProvider {                 // pixels in, observations out
    val id: String
    suspend fun observe(frame: ImageFrame, geometry: ScreenGeometry): GameState
}

interface GameAgent {                          // observations in, a proposal out
    val id: String
    val disclosure: String                     // shown verbatim in the UI
    suspend fun decide(observation: GameState): AgentDecision
}

interface InputController {                    // VALIDATED actions only
    val capabilities: ControllerCapabilities
    fun isAvailable(): Boolean
    suspend fun execute(action: ValidatedAction): ExecutionOutcome
    suspend fun cancelActive(): Boolean
    fun release()
}

interface VisionModelProvider {                // optional, never mandatory
    suspend fun isConfigured(): Boolean
    suspend fun requestDecision(observation: GameState, timeoutMs: Long): ProviderResponse
}

interface HttpTransport {                      // so the provider is testable offline
    suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult
}
```

Each seam exists because something on the other side is replaceable: the heuristic detector can be
swapped for a trained model, the deterministic policy for a provider, the accessibility controller
for the test-scene controller, the real HTTP transport for a fake.

`ControllerCapabilities` is deliberately verbose — `supportsTap`, `supportsSwipe`,
`supportsLongPress`, `supportsHoldRelease`, `supportsMultitouch`, `targetsOtherApps`,
`requiresUserEnablement` — because "can this app drive another app?" has no single honest answer on
Android. The UI renders these flags instead of implying uniform power.

---

## 4. One cycle, end to end

```text
ScreenCaptureService (foreground, type mediaProjection)
  │  ImageReader.onImageAvailable
  │  ├─ acquireLatestImage(); drop older queued images
  │  ├─ ImageBufferLayout.repackRgba(...)         stride-aware copy into an IntArray
  │  ├─ ImageFrame(pixels, width, height, timestampMs, sequence)   (immutable)
  │  ├─ ScreenGeometry(captureWidth, captureHeight, rotation, capturedAtMs)
  │  └─ optional preview bitmap + optional ScreenshotStore write
  ▼
AgentRuntime
  │  session.submitFrame(frame, geometry)         non-blocking
  ▼
FrameBuffer (capacity 2, drops oldest)             capture never blocks on processing
  ▼
AgentSession worker (single coroutine)
  ├─ state machine: CAPTURING/READY → PROCESSING → RUNNING → READY
  ├─ perception.observe(frame, geometry)      → GameState          [latency tracked]
  ├─ agent.decide(state)                      → AgentDecision      [latency tracked]
  ├─ validator.validate(decision, context)    → Accepted | Rejected(code, reason)
  │     context carries: settings limits, action count, last-action time,
  │                      emergency-stop latch, geometry age + mapper
  ├─ mapper.toPixels(normalizedPoint)         → PixelPoint in display space
  └─ controller.execute(ValidatedAction)      → ExecutionOutcome   [latency tracked]
  ▼
UI observes StateFlows: sessionState, metrics, lastDecision, latestGameState, lastOutcome,
validatorStats, stateHistory, eventLog.revision
```

### The safety invariant

`ValidatedAction` is the only type `InputController.execute` accepts, and `ActionValidator.validate`
is the only thing that constructs one. A raw `AgentDecision` — deterministic or from a cloud
provider — therefore has no path to the screen. Validation happens *after* the agent in both cases,
so choosing the AI provider cannot widen what is executable.

What the validator enforces, in evaluation order:

1. Emergency stop latched → `EMERGENCY_STOP`.
2. `actionsEnabled` false → `ACTIONS_DISABLED`.
3. Action type not in the allow-list → `UNSUPPORTED_ACTION`.
4. Decision confidence below the floor → `LOW_CONFIDENCE`.
5. Missing coordinates for a pointer action → `MISSING_COORDINATES`.
6. Geometry older than `maxGeometryAgeMs` → `STALE_GEOMETRY`.
7. Point outside the captured surface → `OUT_OF_BOUNDS`.
8. Gesture duration outside the configured window → `DURATION_TOO_SHORT` / `DURATION_TOO_LONG`.
9. Two actions closer than `minIntervalBetweenActionsMs` → `RATE_LIMITED`.
10. Per-session action cap reached → `ACTION_BUDGET_EXHAUSTED`.
11. Controller does not support the action → `UNSUPPORTED_BY_CONTROLLER`.

Every rejection carries a machine code **and** a human reason, both of which appear in the UI.

---

## 5. Threading and ownership

| Work | Owner | Notes |
| --- | --- | --- |
| Frame acquisition + pixel repack | `ImageReader` handler thread inside the capture engine | Never touches the main thread or the session lock. |
| Preview bitmap | Same capture path | Skipped entirely when the preview is disabled. |
| Perception → decision → validation → input | One coroutine per session (`Dispatchers.Default`) | Serialised by design: one cycle at a time, so the rate limiter and the action budget are meaningful. |
| UI | Compose, main thread | Reads `StateFlow`s only; never blocks on capture or analysis. |
| Settings | `SettingsRepository` (SharedPreferences + in-memory `StateFlow`) | Writes are cheap and observable. |
| API key | `SecureKeyStore` | Keystore-backed; the value is never logged or exposed to the UI layer. |

`FrameBuffer` capacity is deliberately 2: capture must not block, and processing a frame that is
already two frames stale is worse than dropping it. Dropped frames are counted and surfaced in
diagnostics rather than hidden.

---

## 6. State machine

```text
IDLE ──requestPermission──► REQUESTING_PERMISSION ──consent──► CAPTURING
  ▲                                  │ denied                    │ first frame
  │                                  ▼                           ▼
  │                          ERROR ◄──failure─────────────── READY ⇄ PROCESSING
  │                            │                                 │ start
  └────────recover─────────────┘                                 ▼
                                                             RUNNING ⇄ PAUSED
                                                                 │ stop
                                                                 ▼
                                                             STOPPING ──► IDLE
```

Rules enforced by `SessionStateMachine`:

* Only declared transitions are accepted; every attempt is recorded as a `StateChange` with a reason
  and a timestamp, which the diagnostics screen shows.
* `holdsCaptureResources` is a property of the state, so teardown happens exactly once.
* The emergency stop is a **latched** flag, not a state: it survives `PAUSED`, blocks validation, and
  is cleared only by an explicit reset. `stop`/teardown never depends on a network call or an AI
  response.

---

## 7. The mock game (`:core/fixtures`)

A single renderer (`SyntheticSceneRenderer`) draws a `TestSceneState` into an `IntArray` at a fixed
canonical size. It is used twice: the Compose test screen draws that bitmap, and the test harness
feeds those same pixels through the real pipeline. `TestScenePerceptionProfile` describes the flat
colours and regions of that HUD, so `HeuristicScreenPerceptionProvider` genuinely detects the player,
abilities, enemies and phase from pixels rather than being told.

`TestSceneInputController` applies validated actions back to the scene (moving the token, pressing a
skill, honouring the joystick region) and records them, closing the loop:

```text
render → capture → perceive → decide → validate → act → scene changes → render …
```

---

## 8. Coordinate mapping

* The UI, fixtures and agent exchange **normalised** coordinates (`0..1`, origin top-left) so nothing
  depends on the capture resolution.
* `CoordinateMapper` converts normalised → display pixels using a `ScreenGeometry` snapshot.
* The mapper refuses to convert when the snapshot is older than `maxGeometryAgeMs` (default 1200 ms)
  or when the display rotated, which is why `STALE_GEOMETRY` is a distinct rejection code.
* The capture surface always preserves the display's aspect ratio (`CaptureSizeCalculator`), because
  a skewed surface would silently misplace every derived touch.

---

## 9. Diagnostics model

`DiagnosticEventLog` is a bounded (400-record) in-memory ring buffer behind `DiagnosticEventSink`.
It publishes a `revision` counter rather than the whole list, so the UI recomposes once per change
instead of copying the log on every write. Events carry at most a small `Map<String, String>` of
scalars — never an image, never a key, never personal data. `LatencyTracker` keeps min/mean/max/p50
for perception, decision and input latency; `SessionMetrics` and `CaptureMetrics` aggregate the rest.

---

## 10. Extension points

| To add… | Implement | Touching nothing else |
| --- | --- | --- |
| A trained on-device model | `PerceptionProvider` | Swap via `PerceptionProfile`/settings; the policy and validator stay. |
| A different decision policy | `GameAgent` | `DelegatingProviders` swaps it live. |
| A different cloud provider | `VisionModelProvider` (+ `HttpTransport`) | Response parsing is already strict and shared. |
| A different input mechanism | `InputController` | Declare honest `ControllerCapabilities`. |
| A new action type | `ActionType` + validator + planner | The `when` blocks are exhaustive, so the compiler lists every site. |
