# Contributing

Thanks for looking at this. The project's whole value is that its claims are checkable, so the most
useful contribution is usually a test that pins down a boundary — or a change that removes something
that was not really implemented.

---

## 1. Ground rules

1. **Working build, always.** `./gradlew :core:test :app:testDebugUnitTest :core:lint :app:lintDebug :app:assembleDebug`
   must pass before a change is pushed. CI runs exactly these tasks.
2. **Never overstate a capability.** If something needs a device, a key or a permission that is not
   present, say so in the code, the UI and the README. A feature that "mostly works" is documented as
   partial.
3. **No fabricated results.** No hard-coded test outcomes, no mocked-out assertions, no screenshots
   presented as device evidence.
4. **Keep `:core` Android-free.** If a change needs `Context`, it belongs in `:app`. This boundary is
   what makes the logic testable without a device.
5. **No secrets.** Nothing resembling a key, token, keystore or password is committed — not in
   Gradle files, not in CI, not in a fixture. `*.jks`, `keystore.properties`, `.env*` are ignored for
   a reason.
6. **No evasion.** No root, no hidden APIs, no anti-cheat bypass, no attempt to conceal the
   accessibility service. Changes in that direction will be rejected.

---

## 2. Setting up

Any of these works:

* **Android Studio** (Ladybug or newer) — open the repository root; Gradle sync does the rest.
* **Command line with a PC** — JDK 17 + Android SDK platform 36.
* **Phone only** — push and let GitHub Actions build the APK for you (see `README.md` §4).

```bash
git clone <your-fork>
cd GameAgent
./gradlew :core:test :app:testDebugUnitTest    # ~1 min on a cold cache
```

No `local.properties` is needed in CI. Locally, either set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or let
Android Studio generate `local.properties` (it is git-ignored — do not commit it).

---

## 3. Where to make a change

| You want to… | Look at |
| --- | --- |
| Interpret a new visual signal | `core/perception/` — add a `PerceptionProfile` or a new provider. |
| Change how the agent decides | `core/agent/GameAgent.kt` — `DeterministicPolicyAgent` or a new `GameAgent`. |
| Add or tighten a safety rule | `core/validation/ActionValidator.kt` + `ValidationConfig`, then add a boundary test. |
| Add an action type | `core/model/Primitives.kt` (`ActionType`), the validator, `app/input/GesturePlanner`, and every `when` over it — the compiler will list them because they are exhaustive. |
| Add an input mechanism | `core/input/InputController.kt` (honest `ControllerCapabilities`) + an implementation in `app/input/`. |
| Change the session lifecycle | `core/session/SessionStateMachine.kt` — transitions are declared in one table; update its test first. |
| Touch the UI | `app/ui/`. Read `docs/INPUT_MECHANISMS.md` first if you are changing anything about action execution. |
| Document a limitation | `README.md` §8, and `docs/INPUT_MECHANISMS.md` for input specifics. |

---

## 4. Running the tests

```bash
./gradlew :core:test                 # 168 JVM tests: the logic
./gradlew :app:testDebugUnitTest     # 38 JVM tests: stride maths, sizing, gesture planning, settings
./gradlew :core:lint :app:lintDebug  # abortOnError is on; warnings are allowed but should not grow
./gradlew :app:assembleDebug         # the artifact that gets installed
./gradlew :app:assembleDebugAndroidTest  # compiles the on-device test sources (no device needed)
./gradlew :app:connectedDebugAndroidTest # needs a connected device
```

Focused runs while iterating:

```bash
./gradlew :core:test --tests 'com.gameagent.core.validation.ActionValidatorTest'
./gradlew :app:testDebugUnitTest --tests '*GesturePlannerTest'
```

Failed unit tests leave a readable report at
`core/build/reports/tests/test/index.html` (or `app/build/reports/tests/testDebugUnitTest/`).

### Instrumented tests

`app/src/androidTest` holds a single smoke test. It asserts that the application, the process-wide
graph and the activity come up on a real device, that the start-up event was logged, and that a fresh
process is `IDLE` with action execution **disabled**. It needs a phone or emulator:

```bash
# phone connected over USB with debugging enabled
./gradlew :app:connectedDebugAndroidTest
```

CI cannot run it — GitHub-hosted runners have no Android device and this project does not emulate one
there — so it stays a local step. Do not move a claim into the README that depends on it without
running it.

---

## 5. Test-coverage map

The brief lists twelve scenarios. Here is exactly where each one is pinned down, so a change that
breaks one is caught rather than discovered on a phone.

| # | Scenario | Test(s) |
| --- | --- | --- |
| 1 | Permission granted | `AgentSessionTest.permission granted brings the session to ready` |
| 2 | Permission denied | `AgentSessionTest.permission denied returns to idle with a warning`, `SessionStateMachineTest.permission denial returns to idle` |
| 3 | Capture session stopped | `AgentSessionTest.stopping drains the queue, releases input and returns to idle`, `FrameBufferTest.drain discards everything pending and counts it` |
| 4 | Capture produces no frame | `AgentSessionTest.no capture yet means no geometry and therefore no action`, `FrameBufferTest.offering to a closed buffer drops the frame rather than throwing` |
| 5 | Invalid model response | `DecisionParserTest` (21 cases: unknown action, injected payload, bad confidence, absurd duration, non-JSON …), `VisionProviderTest.a malformed envelope is invalid`, `AgentSessionTest.an invalid AI response falls back and the session keeps working` |
| 6 | Out-of-bounds touch coordinates | `AgentSessionTest.out of bounds coordinates coming from a provider are rejected, not clamped`, `ActionValidatorTest.coordinates that could not be mapped are rejected with the mapper's reason`, `GesturePlannerTest.coordinates outside the display are refused`, `CoordinateMapperTest.maps normalized corners onto the last addressable pixel` |
| 7 | Emergency stop mid-session | `AgentSessionTest.emergency stop halts everything immediately and latches`, `ActionValidatorTest.emergency stop wins over everything else`, `EndToEndLoopTest.nothing is executed while the emergency stop is latched` |
| 8 | App backgrounded during capture | **Partly covered, honestly:** `AgentSessionTest.pausing stops interpretation and no action is executed` and `a screen size change is picked up on the next frame` cover the session side; `assembleDebugAndroidTest` only proves the sources compile. Real backgrounding is an Android lifecycle behaviour and can only be verified on a device — see `README.md` §11 about OEM battery optimisation. |
| 9 | Screen dimensions change | `AgentSessionTest.a screen size change is picked up on the next frame`, `CoordinateMapperTest.rotation change requires a fresh snapshot`, `CaptureSizeCalculatorTest.landscape displays are handled the same way` |
| 10 | AI provider unavailable | `VisionProviderTest.an unconfigured provider never touches the network`, `…a network failure is reported as unavailable, not as an exception`, `AgentSessionTest.an unavailable AI provider falls back and the session keeps working` |
| 11 | Repeated start and stop | `AgentSessionTest.repeated start and stop operations are safe`, `SessionStateMachineTest.self transitions are refused so repeated presses are visible` |
| 12 | Restart after an interrupted session | `AgentSessionTest.an interrupted session leaves no residue for the next one`, `AppSettingsCodecTest.empty preferences decode to the safe defaults`, `AppSettingsCodecTest.defaults cannot act and cannot leave the device`, plus the device smoke test's "fresh process is IDLE with actions disabled" |

Beyond the twelve:

* geometry: `CoordinateMapperTest` (10 cases), `CaptureSizeCalculatorTest` (8), `ImageBufferLayoutTest` (9)
* model validation: `ModelValidationTest.states` — `UNKNOWN` is distinct from a measured zero
* parser hardening: `MiniJsonTest` (13 cases including depth limits and malformed numbers)
* the mock game: `TestSceneTest` (11), `EndToEndLoopTest` (4)
* diagnostics: `DiagnosticsTest` (10), including ring-buffer bounds

---

## 6. Code standards

* **Kotlin, explicit and boring.** Small classes, one responsibility, nullable only where a real
  unknown exists. `UNKNOWN`/`null` is preferred to a plausible default — that rule is what keeps the
  agent from acting on invented data.
* **Data over behaviour at the seams.** `GameState`, `AgentDecision` and `ValidatedAction` are data
  classes; validation lives in `ActionValidator`, not in the model.
* **Coroutines, not threads.** One worker coroutine per session, `Dispatchers.Default`, `StateFlow`
  for anything the UI observes. Never block the main thread; never block capture on processing.
* **Bounded everything.** Queues, event logs, screenshot storage and latencies all have limits.
  Unbounded growth in a long-running capture loop is a bug.
* **Comments explain *why*.** The interesting comments in this codebase are about Android lifecycle
  rules, silently-failing platform APIs and safety decisions. Do not add comments that restate the
  code.
* **Errors are data.** Provider failures, perception failures and gesture refusals return values with
  a reason (`ExecutionOutcome`, `RejectionCode`, `ProviderResponse`) instead of throwing.
* **Pure functions for anything worth testing.** Coordinate maths, stride maths, gesture planning and
  the settings codec are Android-free precisely so they can be tested exhaustively.
* **Formatting.** `kotlin.code.style=official`; 4-space indent, trailing commas in multi-line
  argument lists, imports alphabetised without wildcards. Match the file you are editing.

---

## 7. Pull request checklist

* [ ] `./gradlew :core:test :app:testDebugUnitTest :core:lint :app:lintDebug :app:assembleDebug` passes.
* [ ] New logic has a test that would fail without the change (boundary cases included).
* [ ] Any new user-visible control actually does something, or is labelled as not implemented.
* [ ] `README.md` / `ARCHITECTURE.md` updated if behaviour, permissions or limitations changed.
* [ ] No secret, key, token, keystore or signed artifact is included.
* [ ] No new dependency without a reason in the PR description — this project keeps its dependency
      list deliberately short.
* [ ] If you changed a version, you changed the whole compatible set (see `docs/BUILD.md` §6).

CI must be green before merge. If a check fails for an environmental reason (a flaky network fetch,
an SDK download), say so and re-run — do not disable the check.
