package com.andropilot.agent

import android.os.Bundle
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andropilot.android.AndroPilot

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
    LifecycleResumeEffect(Unit) {
        serviceEnabled = AndroPilot.isServiceEnabled(context)
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
                }
            }
        }

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
        OutlinedTextField(
            value = config.telemetryEndpoint,
            onValueChange = { config = config.copy(telemetryEndpoint = it) },
            label = { Text("Telemetry endpoint (optional)") },
            placeholder = { Text("https://desk.local:8766/ingest") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = config.telemetryIncludesText,
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

private fun statusOf(state: LinkState): String = when (state) {
    is LinkState.Idle -> "Not connected."
    is LinkState.Connecting -> "Connecting to ${state.endpoint}..."
    is LinkState.Connected -> "Connected to ${state.endpoint}. This host can read and tap your screen."
    is LinkState.Failed -> "Disconnected: ${state.message} Retrying in ${state.retryInMs / 1000}s."
}
