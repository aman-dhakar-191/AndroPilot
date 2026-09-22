package com.andropilot.agent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andropilot.android.AndroPilot
import com.andropilot.core.safety.ConfirmationOutcome
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen: where the model is, and whether to connect to it.
 *
 * Everything here is configuration rather than a build constant, because the whole premise
 * is that the model runs on a machine its owner chose. A hard-coded endpoint would make
 * "point it at my PC" a rebuild.
 */
public class AgentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AgentScreen() }
            }
        }
    }
}

@Composable
private fun AgentScreen() {
    val context = LocalContext.current
    val settings = remember { AgentSettings(context) }
    var config by remember { mutableStateOf(settings.load()) }
    val state by AgentController.state.collectAsStateWithLifecycle()

    // Re-read on resume, not once. Granting the accessibility service means leaving for
    // system settings and coming back, which is exactly the moment this answer changes --
    // and a warning still showing after you have done what it asked reads like a bug in
    // the grant rather than in the screen.
    var serviceEnabled by remember { mutableStateOf(AndroPilot.isServiceEnabled(context)) }
    var overlayAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context))
    }
    LifecycleResumeEffect(Unit) {
        serviceEnabled = AndroPilot.isServiceEnabled(context)
        overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        onPauseOrDispose {}
    }

    // Enabled and bound are different questions, and the gap between them is where this
    // gets stuck: a binding that dies leaves the service switched on in settings with
    // nothing running, and Android never retries. Reporting only "off" would show nothing
    // at all in that state, which is the least helpful thing the screen could do.
    val serviceBound by AndroPilot.serviceConnected.collectAsStateWithLifecycle()
    val pending by AgentController.pending.collectAsStateWithLifecycle()
    val telemetryProblem by AgentController.telemetryProblem.collectAsStateWithLifecycle()
    val activity by AgentController.activity.collectAsStateWithLifecycle()
    val work = rememberCoroutineScope()

    // A confirmation raised while the screen was closed is still waiting, and nothing else
    // would ever surface it.
    LifecycleResumeEffect(Unit) {
        AgentController.refreshPending()
        onPauseOrDispose {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // targetSdk 35 means Android 15 draws this edge to edge whether or not it was
            // designed for it, so without this the heading sits under the status bar and
            // the buttons under the gesture bar. The demo never showed the problem because
            // it uses a Scaffold, which insets its content for you.
            //
            // Applied before the scroll modifier on purpose: the padding then bounds the
            // scrolling viewport, rather than scrolling content through the status bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AndroPilot agent", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusOf(state), style = MaterialTheme.typography.bodyMedium)
                if (!serviceEnabled) {
                    Text(
                        "The accessibility service is off, so actions will fail with " +
                            "PERMISSION_REQUIRED until you enable it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { AndroPilot.openAccessibilitySettings(context) }) {
                        Text("Enable accessibility service")
                    }
                } else if (!serviceBound) {
                    Text(
                        "The accessibility service is switched on but nothing is bound, so " +
                            "every action will fail. The binding died and Android does not " +
                            "retry it. Switch the service off and on again to clear that; if " +
                            "it keeps happening, exempt this app from battery optimisation " +
                            "and enable autostart.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { AndroPilot.openAccessibilitySettings(context) }) {
                        Text("Open accessibility settings")
                    }
                }
            }
        }

        if (!overlayAllowed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Show activity over other apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Allow this so a small indicator appears while the remote agent is working.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("Allow overlay") }
                }
            }
        }

        if (pending.isNotEmpty()) {
            Text("Waiting for you", style = MaterialTheme.typography.titleMedium)
            Text(
                "The agent stopped here and will not go further until you answer. Approving " +
                    "runs the action again against whatever is on screen now, not the screen " +
                    "it saw when it asked.",
                style = MaterialTheme.typography.bodySmall,
            )
            pending.forEach { confirmation ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(confirmation.description, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${confirmation.action.name} · ${confirmation.risk.name.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        confirmation.reasons.forEach {
                            Text("· $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                work.launch {
                                    AgentController.resolve(confirmation.id, ConfirmationOutcome.APPROVED)
                                }
                            }) { Text("Approve and run") }
                            OutlinedButton(onClick = {
                                work.launch {
                                    AgentController.resolve(confirmation.id, ConfirmationOutcome.REJECTED)
                                }
                            }) { Text("Refuse") }
                        }
                    }
                }
            }
        }

        telemetryProblem?.let { problem ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Telemetry is not running",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(problem, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Everything else is unaffected. Correct the endpoint below and " +
                            "restart the app, or clear it to turn telemetry off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Shown even when empty, because a section that appears only once it has content
        // is indistinguishable from a feature that is not there.
        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        Text(
            // Said plainly because the screen is otherwise misleading: it looks like a
            // live view and cannot be one.
            "While the agent works it is driving other apps, so this screen is not on top " +
                "to watch. It is here to look at afterwards.",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (activity.isEmpty()) {
                    Text(
                        "Nothing yet. Actions appear here as the host sends them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    activity.take(40).forEach { entry -> ActivityRow(entry) }
                }
            }
        }

        Text("Connection", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = config.endpoint,
            onValueChange = { config = config.copy(endpoint = it) },
            label = { Text("Host endpoint") },
            placeholder = { Text("wss://desk.local:8765/agent") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // ws:// is allowed because a home LAN is the common case, but it is not silently
            // fine: the token and everything on the screen cross the network in the clear.
            "Use wss:// wherever you can. ws:// sends the token and every screen you " +
                "observe in plain text over the network.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = config.token,
            onValueChange = { config = config.copy(token = it) },
            label = { Text("Shared token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.deviceName,
            onValueChange = { config = config.copy(deviceName = it) },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = config.telemetryEnabled,
                onCheckedChange = { config = config.copy(telemetryEnabled = it) },
            )
            Text(
                "Send telemetry to the host's ingest endpoint automatically. Off means no " +
                    "telemetry leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = config.telemetryIncludesText,
                enabled = config.telemetryEnabled,
                onCheckedChange = { config = config.copy(telemetryIncludesText = it) },
            )
            Text(
                "Include screen text in telemetry. Off by default: with it on, whatever is " +
                    "on your screen is uploaded, including messages and one-time codes. " +
                    "Takes effect the next time the app starts.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = config.isConfigured,
                onClick = {
                    settings.save(config)
                    AgentController.requestConnect(context)
                },
            ) {
                Text(if (state is LinkState.Idle) "Connect" else "Reconnect")
            }
            OutlinedButton(onClick = { AgentController.requestDisconnect(context) }) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val colour = when (entry.kind) {
        ActivityEntry.Kind.FAILURE -> MaterialTheme.colorScheme.error
        ActivityEntry.Kind.CONFIRMATION -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            TIME_FORMAT.format(Date(entry.at)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.text, style = MaterialTheme.typography.bodySmall, color = colour)
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun statusOf(state: LinkState): String = when (state) {
    is LinkState.Idle -> "Not connected."
    is LinkState.Connecting -> "Connecting to ${state.endpoint}..."
    is LinkState.Connected -> "Connected to ${state.endpoint}. This host can read and tap your screen."
    is LinkState.Failed -> "Disconnected: ${state.message} Retrying in ${state.retryInMs / 1000}s."
}
