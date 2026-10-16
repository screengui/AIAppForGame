package com.gameagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.annotation.DrawableRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gameagent.app.AppGraph
import com.gameagent.app.R
import com.gameagent.app.ui.theme.GameAgentColors

private enum class Destination(@StringRes val label: Int, @DrawableRes val icon: Int) {
    DASHBOARD(R.string.nav_dashboard, R.drawable.ic_nav_dashboard),
    TEST(R.string.nav_test, R.drawable.ic_nav_test),
    DIAGNOSTICS(R.string.nav_diagnostics, R.drawable.ic_nav_diagnostics),
    SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings),
}

/**
 * Root composable.
 *
 * A bottom navigation bar rather than a nav graph: there are four static destinations, no deep
 * links and no back stack worth modelling, so a `rememberSaveable` index is the whole navigation
 * implementation. The emergency stop sits *above* the navigation bar so it is reachable from every
 * screen without navigating anywhere.
 */
@Composable
fun GameAgentApp(
    graph: AppGraph,
    onRequestCaptureConsent: () -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(Destination.DASHBOARD.name) }
    val destination = Destination.entries.firstOrNull { it.name == destinationName } ?: Destination.DASHBOARD

    Scaffold(
        containerColor = GameAgentColors.Background,
        bottomBar = {
            Column {
                EmergencyStopBar(graph)
                NavigationBar(containerColor = GameAgentColors.Surface, tonalElevation = 0.dp) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { destinationName = item.name },
                            icon = {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = stringResource(item.label),
                                )
                            },
                            label = {
                                Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GameAgentColors.Accent,
                                selectedTextColor = GameAgentColors.Accent,
                                unselectedIconColor = GameAgentColors.TextSecondary,
                                unselectedTextColor = GameAgentColors.TextSecondary,
                                indicatorColor = GameAgentColors.SurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            AppHeader()
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(graph, onRequestCaptureConsent)
                Destination.TEST -> TestScreenScreen(graph)
                Destination.DIAGNOSTICS -> DiagnosticsScreen(graph)
                Destination.SETTINGS -> SettingsScreen(graph)
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameAgentColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "GameAgent",
            style = MaterialTheme.typography.titleLarge,
            color = GameAgentColors.TextPrimary,
        )
        Text(
            text = "Android companion — capture, perceive, decide, validate, act",
            style = MaterialTheme.typography.bodySmall,
            color = GameAgentColors.TextSecondary,
        )
    }
}

/**
 * The emergency stop.
 *
 * Always visible, always enabled, and implemented as a latch in the core kill switch: pressing it
 * stops the session, releases gestures and stops capture, and nothing can act again until the user
 * explicitly clears it.
 */
@Composable
private fun EmergencyStopBar(graph: AppGraph) {
    val stopState by graph.runtime.emergencyStop.state.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameAgentColors.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stopState.engaged) {
            Button(
                onClick = { graph.runtime.resetEmergencyStop() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GameAgentColors.Warning,
                    contentColor = GameAgentColors.Background,
                ),
            ) {
                Text("Reset emergency stop", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { graph.runtime.stopCapture("user stopped capture after an emergency stop") },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Stop capture")
            }
        } else {
            Button(
                onClick = { graph.runtime.emergencyStop() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GameAgentColors.Danger,
                    contentColor = GameAgentColors.Background,
                ),
            ) {
                Text("EMERGENCY STOP", fontWeight = FontWeight.Bold)
            }
        }
    }
}
