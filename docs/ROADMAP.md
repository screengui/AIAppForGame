# Roadmap

Milestones are ordered by dependency, not by excitement. Each one lists what "done" means, so a
milestone can be checked instead of believed. Nothing later is started before the exit condition of
the current one is met.

Status legend: **Done** · **In progress** · **Next** · **Planned**

---

## M1 — Project foundation — **Done**

Android project, modular architecture, build and CI.

* `:app` (Android, Compose) and `:core` (pure Kotlin/JVM, no Android dependency).
* Gradle 8.14.6 / AGP 8.13.2 / Kotlin 2.3.21 / JDK 17, pinned in a version catalog.
* Dark, mobile-first dashboard with status, permission state, controls, metrics and emergency stop.
* Unit-test plumbing in both modules; `.github/workflows/android.yml` tests, lints, builds the debug
  APK and uploads it as a workflow artifact.

**Exit condition (met):** a debug APK builds in CI, installs from a phone, opens, and the app reports
its own state honestly.

---

## M2 — Screen capture — **Done**

MediaProjection capture with correct lifecycle handling.

* System consent flow, including denial and cancellation; the capture foreground service is promoted
  *before* the projection is requested (required on Android 14+).
* `VirtualDisplay` + `ImageReader`, stride-aware repacking (`ImageBufferLayout`), uniform resize that
  preserves aspect ratio (`CaptureSizeCalculator`), bounded frame buffer that drops stale frames.
* Live preview, frame rate/timestamp metrics, configurable interval and scale, explicit resource
  release on stop/error/size change, opt-in screenshot storage with count and size caps.
* Diagnostics for capture performance, including dropped frames.

**Exit condition (met):** captured frames appear in the app at a steady rate and every capture
resource is released when capture stops — verified by unit tests for the sizing/stride maths and by
the device smoke test for lifecycle, with the caveat in "Known limitations".

---

## M3 — Mock AI pipeline — **Done**

Perception → decision → validation → coordination, all deterministic and all testable.

* `GameState` in which anything not observed is `null`/`UNKNOWN`, never a plausible default.
* `PerceptionProvider` interface + a deterministic colour/geometry detector with a calibratable
  profile; `UNKNOWN` and low confidence when the profile does not match.
* `GameAgent.decide(observation)` interface + `DeterministicPolicyAgent` (no model, no network, no
  randomness) and a documented rule order.
* `ActionValidator` with an allow-list, bounds, duration window, rate limit, per-session cap,
  confidence floor, geometry-staleness check and a latched emergency stop.
* `AgentSession` + `SessionStateMachine` with declared transitions, pause/resume and teardown.
* `DecisionParser` + an optional OpenAI-compatible provider behind `VisionModelProvider`, with a
  deterministic fallback on every failure mode.

**Exit condition (met):** 168 `:core` tests plus 38 `:app` tests pass, and the whole loop runs on
fixtures with no MLBB, no permissions and no API key.

---

## M4 — Test controller — **Done**

End-to-end action execution inside the app's own environment.

* Mock game screen rendered by `SyntheticSceneRenderer` and perceived from its pixels.
* `TestSceneInputController` applies validated actions back to the scene; the loop closes.
* `GameAgentAccessibilityService` + `AccessibilityInputController` + `GesturePlanner` for the
  opt-in path that can reach other apps, with honest `ControllerCapabilities` and documented limits
  (`docs/INPUT_MECHANISMS.md`).
* Emergency stop: latched, cancels pending work, releases the controller, blocks capture, and
  independent of any network or AI response.

**Exit condition (met):** enabling action execution makes the mock player move in response to
validated decisions, and emergency stop halts it immediately — covered by `EndToEndLoopTest` and
demonstrable by hand on the test screen.

---

## M5 — Real vision — **Next**

The milestone that turns this from a foundation into something that can see a real match.

**Work**

1. Choose two or three candidate on-device options and record the evaluation: a bundled TFLite model,
   a server-side vision endpoint behind the existing `VisionModelProvider`, and continued hand-written
   detection for the parts where it is actually reliable (fixed HUD geometry — health bars, ability
   icons, the minimap frame). Record licence, input format, hardware requirements and expected
   memory/latency for whatever is adopted.
2. Build a labelled screenshot set from a **permitted practice environment** (custom practice match
   with bots against your own device; not ranked, not public matches). Store images outside the
   repository; commit only the labels and the harness.
3. Implement UI/state detection with explicit confidence thresholds and an `UNKNOWN` outcome that the
   policy already handles correctly.
4. Measure, per frame: inference latency (p50/p95), peak memory, and accuracy against the labelled set.
   Publish the numbers in the README — including the cases where it is wrong.
5. Add a perception regression test that replays the labelled frames through the real provider.

**Exit condition:** a documented perception subsystem with measured latency, memory and accuracy on a
representative screenshot set, whose failures are visible as low confidence rather than as confident
nonsense.

**Explicitly out of scope for M5:** claiming the agent can play MLBB. Detection quality on a live
match is a research result, not a build artifact.

---

## M6 — AI decision integration — **Planned**

* Structured observation → structured action, with the parser hardened further as new failure modes
  appear (already strict: unknown types, injected payloads, out-of-range coordinates, oversized
  bodies, timeouts).
* Request accounting: per-session request count, latency and an estimated cost shown next to the
  provider status, so a user cannot enable it by accident and be surprised.
* Offline behaviour: an explicit "provider unavailable, using local policy" state in the UI rather
  than a silent fallback.
* A deterministic replay test that feeds recorded provider responses through the pipeline.

**Exit condition:** enabling the provider cannot change what is *executable* — only what is proposed —
and every failure mode degrades to the deterministic policy within a bounded time.

---

## M7 — Compatibility evaluation — **Planned**

* Test the supported actions on the target device in a permitted practice environment and write down
  what works, what is unreliable and what is impossible.
* Document unsupported interactions (multi-touch joystick, drag-to-aim, item shop flows) rather than
  approximating them.
* Assess input latency end to end on a real device and record it.
* Publish a clear statement of the remaining manual steps.

**Exit condition:** a written compatibility report that separates measured results from expectations,
with no claim beyond what was observed.

---

## Known limitations carried forward

These are not backlog items; they are properties of the platform that shape everything above:

* A normal app **cannot** inject touches into another app. The only supported route is a
  user-enabled `AccessibilityService`, and Android shows it prominently.
* Screen-capture consent cannot be bypassed, and a stopped projection needs fresh consent.
* OEM battery management can stop the foreground service; the app reports it rather than pretending
  otherwise.
* Multi-touch and standalone `RELEASE` are not dependable through the gesture API and are refused
  with a reason instead of shipped half-working.
* The bundled perception is calibrated for the app's own mock HUD, not for MLBB.

---

## Long-term (deliberately uncommitted)

Eventually the loop could drive practice-mode play: recognise the match state, hold a lane, farm,
retreat at low health, and recover from death. That capability will only be claimed once real
perception and real action execution have been demonstrated on a device in a controlled environment,
measured, and documented — including where it fails. Until then, this roadmap stops at measurement
and honest reporting.
