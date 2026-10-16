# Input mechanisms on Android

This document is the honest answer to: *"how does a normal Android app touch another app's screen?"*

Short version: **it cannot, unless the user explicitly enables an AccessibilityService, and even then
only a subset of gestures is dependable.** Everything below explains what was evaluated, what was
implemented, and what is deliberately refused.

---

## 1. What is *not* available to a normal app

| Mechanism | Verdict | Why |
| --- | --- | --- |
| `Instrumentation.sendPointerSync` | **Unusable** | Only works inside the process under test. An app can only instrument itself (and it must be declared as the instrumentation target). It cannot drive MLBB or any other app. |
| `MotionEvent` injection into another window | **Unusable** | `WindowManager`/`InputManager` injection APIs are `@hide`/system-only. Ordinary apps have no permission to inject into a foreign window. |
| `adb shell input tap` | **Unusable in-app** | Needs a host machine and ADB. The brief requires a phone-only workflow. Also, MLBB is a competitive online game; automating it through ADB is not something this project will do. |
| `Root` / Magisk modules / `input` binary | **Not used** | Explicitly out of scope: the brief forbids root, and root-based injection plus any anti-cheat evasion is out of bounds. |
| Accessibility "click on a node" (`ACTION_CLICK`) | **Partly usable** | Works on real accessibility nodes in other apps, but a game surface is usually a single `SurfaceView`/`GLSurfaceView` with **no** accessible nodes. Useless for a game HUD. |
| **`AccessibilityService.dispatchGesture`** | **Implemented** | The one supported, non-root way to deliver touch to another app. Requires the user to enable the service in Android Settings. |
| Overlay window with `FLAG_NOT_TOUCHABLE` + synthetic input | **Unusable** | The overlay can be touched by the *user*, not by the app's own code. It does not become an injection primitive. |

The app therefore never claims that an ordinary application can inject arbitrary touches, and never
uses a hidden API, a security bypass, or anything designed to avoid detection.

---

## 2. The implemented mechanism: `AccessibilityService` gestures

Declared in `AndroidManifest.xml` and configured by `res/xml/accessibility_service_config.xml`:

```xml
<service android:name=".input.GameAgentAccessibilityService"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" ... />
```

```xml
android:canPerformGestures="true"
android:canRetrieveWindowContent="false"
android:isAccessibilityTool="false"
```

* `canRetrieveWindowContent="false"` — the service cannot read text, view hierarchies, credentials or
  messages. It only sends gestures. Perception in this project works from captured **pixels**, so
  window content is not needed and is not requested.
* `isAccessibilityTool="false"` — an honest declaration on Android 14+: this is an automation
  companion, not an assistive technology. Android surfaces the distinction to the user instead of
  letting an app claim to be one.
* The service is visible in Android's accessibility list and can be turned off there at any time. It
  never enables itself, never hides itself, and does nothing until the app dispatches a validated
  gesture — `onAccessibilityEvent` is deliberately empty.

### Capabilities declared to the UI

| Capability | Value | Meaning |
| --- | --- | --- |
| `supportsTap` | `true` | A tap is a zero-length path with a ~60 ms duration. |
| `supportsSwipe` | `true` | A straight path from start to end over the requested duration. |
| `supportsLongPress` | `true` | A `HOLD` is a zero-length path with the requested duration. |
| `supportsHoldRelease` | `false` | See §4. |
| `supportsMultitouch` | `false` | See §4. |
| `targetsOtherApps` | `true` | This is the mechanism's whole point. |
| `requiresUserEnablement` | `true` | Nothing works until the user enables it in Settings. |

---

## 3. Platform limits enforced client-side

Android silently rejects a gesture that is too large, which would look like "the app did nothing".
`GesturePlanner` checks the limits **before** dispatching, so the failure is reported instead of
vanishing:

| Platform limit | Our check |
| --- | --- |
| At most **20 strokes** per gesture | `GesturePlanner.MAX_STROKES = 20` |
| At most **60 s** total gesture duration | `GesturePlanner.MAX_GESTURE_DURATION_MS = 60_000` |
| A tap must not become a press | tap duration clamped to 60–200 ms |
| Coordinates must be inside the display | start and end validated against the live display size |

`AccessibilityInputController` additionally caps how long it will wait for the platform callback
(4 s, `DEFAULT_GESTURE_TIMEOUT_MS`) so a stuck gesture cannot wedge the pipeline.

Gesture duration is *also* bounded by the validator (`minGestureDurationMs` / `maxGestureDurationMs`,
user-configurable), so an unreasonable duration is refused before a plan is ever built.

---

## 4. Deliberately not implemented

**Multi-touch.** Android's gesture API can describe up to 20 overlapping strokes, so a two-finger
gesture is expressible. Whether a game interprets a synthesised multi-pointer gesture exactly like a
human one is a different question, and it cannot be promised. Mobile Legends in particular relies on
a virtual joystick whose behaviour under synthesised input is not documented. The controller reports
`supportsMultitouch = false` and the planner only ever emits a single stroke, rather than shipping
something that half-works and looks like a bug.

**Standalone `RELEASE`.** Releasing a pointer that is still held requires continuing a stroke from a
previous gesture (`StrokeDescription.continueStroke`), which is only valid under specific conditions
and behaves differently per target app. Dispatch is asynchronous, so a "release" arriving after the
hold completed would be a no-op or an error. `GesturePlanner` refuses `RELEASE` with
`UNSUPPORTED_BY_CONTROLLER` and an explanation, and `HOLD` is delivered as one **completed**
long-press stroke.

**Cancelling another app's in-flight gesture.** Android cancels any in-progress gesture (including a
real user's) when a new one is dispatched. That is a side effect of the platform API, not a
capability this app uses: the app only ever dispatches its own validated actions, and
`cancelActive()` returns `false` rather than exploiting it.

---

## 5. The other implemented controller: the local test scene

`InputTarget.TEST_SCENE` routes validated actions to `TestSceneInputController` in `:core`, which
applies them to the app's own mock game loop. It needs:

* no permission of any kind,
* no accessibility service,
* no captured screen (the runtime can render frames directly).

That is what makes the whole pipeline demonstrable on a fresh install: enable *Allow action
execution*, choose the test scene, and watch validated taps and swipes move the mock player. This is
also the only target the automated tests exercise, because it is the only one that behaves
identically on every device.

---

## 6. Choosing a controller at runtime

| Setting | Controller | Behaviour when unavailable |
| --- | --- | --- |
| `TEST_SCENE` | `TestSceneInputController` | Always available. |
| `ACCESSIBILITY` | `AccessibilityInputController` | Reports `Unsupported` with "enable it in Android accessibility settings"; the session records the reason and continues to perceive. Never silently falls back to another target. |
| neither | `UnavailableInputController` | Reports `Unsupported`. Exists so the app can never pretend a stub is a real controller. |

`AgentRuntime` also exposes a `RecordingInputController` ("dry run") path used by tests: it records
what *would* have been executed and proves the pipeline without producing any input at all.

---

## 7. Scope limits this project imposes on itself

* Actions are only ever produced from a validated decision; there is no manual "tap here" injection
  facility in the UI.
* The shipped default has action execution **off** and the test scene as the target.
* Ranked and public competitive matches are out of scope. The documented use case is a controlled
  practice environment.
* No anti-cheat evasion, no stealth, no concealment. The service's purpose is stated in
  `strings.xml`, in the manifest, and in the accessibility summary the user reads before enabling it.

---

## 8. If you want to extend this

* **Multi-touch / joystick:** implement a second `GesturePlan` shape (multiple strokes), declare
  `supportsMultitouch = true`, and validate it on a device in a permitted environment before relying
  on it. The planner and controller are the only two files that need to change.
* **A different mechanism:** implement `InputController` and declare its real
  `ControllerCapabilities`. Do not overstate the flags — the UI renders them as the truth.
* **Instrumented self-test:** `src/androidTest` can drive the app's own UI with Espresso without any
  injection permission, which is the right way to test on-device behaviour automatically.
