package com.gameagent.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gameagent.app.ui.MainActivity
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


/**
 * Device smoke test.
 *
 * Scope is deliberately tiny: it proves the application object, the dependency graph, the single
 * activity and the session's starting state actually come up on a real device or emulator, and that
 * the graph reached from the activity is the process-wide one the capture and accessibility
 * services will use. Everything that can be checked without a device is covered by the JVM unit
 * tests instead.
 *
 * This test needs a connected device (`./gradlew connectedDebugAndroidTest`) and therefore does not
 * run in CI - see README.md.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityStartsAndWiresTheProcessGraph() {
        activityRule.scenario.onActivity { activity ->
            val application = activity.application as GameAgentApplication
            val graph = application.graph

            assertNotNull("the process-wide graph must exist once the app is running", graph)

            assertEquals(
                "the application id and the runtime package must agree",
                "com.gameagent",
                activity.packageName,
            )

            // Application.onCreate logs a start-up event, so a non-empty log proves the graph is
            // live and reachable from the activity rather than a stub.
            val events = graph.eventLog.snapshot()
            assertFalse("the application should have logged its start-up event", events.isEmpty())
            assertTrue(
                "the start-up event must be categorised as SYSTEM",
                events.any { it.category == EventCategory.SYSTEM },
            )

            // A freshly launched process must be idle and must not be able to inject input: the
            // shipped defaults have to be safe on their own, not only because the UI hides a button.
            assertEquals(
                "a freshly launched app must be idle",
                SessionState.IDLE,
                graph.runtime.sessionState.value,
            )
            assertFalse(
                "actions must be disabled until the user explicitly enables them",
                graph.settingsRepository.settings.value.actionsEnabled,
            )
        }
    }
}
