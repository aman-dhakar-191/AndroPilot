package com.andropilot.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andropilot.android.AndroPilot
import com.andropilot.core.model.UiElement
import com.andropilot.core.safety.ConfirmationOutcome

/**
 * A developer tool, not a product.
 *
 * It answers the questions an integrator has while wiring the SDK up: what does the SDK see
 * right now, which elements did it detect, what happened when I triggered an action, and
 * why did it fail. Everything shown is rendered from SDK types, so if it looks right here
 * an agent will receive the same thing.
 */
class InspectorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InspectorScreen()
                }
            }
        }
    }
}

@Composable
private fun InspectorScreen(viewModel: InspectorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.serviceConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var targetPackage by remember { mutableStateOf("com.android.settings") }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PermissionCard(
                    // Deliberately NOT `connected || isServiceEnabled`. The setting says the
                    // user switched it on; `connected` says it is actually running. Where
                    // they disagree the service has stopped, which is the state worth
                    // reporting rather than papering over.
                    bound = connected,
                    enabledInSettings = AndroPilot.isServiceEnabled(context),
                    onOpenSettings = { AndroPilot.openAccessibilitySettings(context) },
                )
            }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Perception")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::observe, enabled = !state.busy) { Text("Observe") }
                    OutlinedButton(onClick = viewModel::observeWithVision, enabled = !state.busy) {
                        Text("Observe + vision")
                    }
                    OutlinedButton(onClick = viewModel::screenshot, enabled = !state.busy) {
                        Text("Screenshot")
                    }
                }
  }
            }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Navigation")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::back, enabled = !state.busy) { Text("Back") }
                    OutlinedButton(onClick = viewModel::home, enabled = !state.busy) { Text("Home") }
                    OutlinedButton(onClick = viewModel::scrollDown, enabled = !state.busy) { Text("Scroll ↓") }
                    OutlinedButton(onClick = viewModel::scrollUp, enabled = !state.busy) { Text("Scroll ↑") }
                }
  }
            }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Target an element")
                OutlinedTextField(
                    value = state.selectorText,
                    onValueChange = viewModel::onSelectorChanged,
                    label = { Text("Selector text (fuzzy matched)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::findElement, enabled = !state.busy) { Text("Find") }
                    OutlinedButton(onClick = viewModel::click, enabled = !state.busy) { Text("Click") }
                    OutlinedButton(onClick = viewModel::longPress, enabled = !state.busy) { Text("Long press") }
                    OutlinedButton(onClick = viewModel::waitForSelector, enabled = !state.busy) { Text("Wait for") }
                }
  }
            }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Text entry")
                OutlinedTextField(
                    value = state.typeText,
                    onValueChange = viewModel::onTypeTextChanged,
                    label = { Text("Text to type into the targeted field") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::typeText, enabled = !state.busy) { Text("Type") }
                    OutlinedButton(onClick = viewModel::clearText, enabled = !state.busy) { Text("Clear") }
                }
  }
            }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Applications")
                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.launch(targetPackage) }, enabled = !state.busy) {
                        Text("Launch")
                    }
                    OutlinedButton(
                        onClick = { viewModel.runSequence(targetPackage) },
                        enabled = !state.busy,
                    ) { Text("Run 4-step sequence") }
                }
  }
            }

            if (state.pending.isNotEmpty()) {
                item { SectionTitle("Awaiting your confirmation") }
                items(state.pending, key = { it.id }) { confirmation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(confirmation.description, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Risk: ${confirmation.risk.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            confirmation.reasons.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton(onClick = {
                                    viewModel.resolve(confirmation.id, ConfirmationOutcome.APPROVED)
                                }) { Text("Approve") }
                                TextButton(onClick = {
                                    viewModel.resolve(confirmation.id, ConfirmationOutcome.REJECTED)
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
            }


            item { TraceCard() }

            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("What the agent would receive")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.lastSummary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
  }
            }

            state.snapshot?.let { snapshot ->
                item {
                    SectionTitle(
                        "Detected elements (${snapshot.elements.size}) in ${snapshot.packageName ?: "unknown"}",
                    )
                }
                items(snapshot.elements, key = { it.id }) { element ->
                    ElementRow(element, highlighted = state.matches.any { it.id == element.id })
                }
            }

            if (state.trace.isNotEmpty()) {
                item { SectionTitle("Action trace (most recent first)") }
                items(state.trace, key = { it.sequence }) { entry ->
                    Text(
                        text = entry.format(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    bound: Boolean,
    enabledInSettings: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                bound -> Text(
                    "Accessibility service: connected",
                    style = MaterialTheme.typography.titleMedium,
                )

                enabledInSettings -> {
                    Text(
                        "Accessibility service: switched on, but not running",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "The system has it enabled but nothing is bound. This usually means " +
                            "the binding died -- often the process being reclaimed by the " +
                            "device -- and Android does NOT retry on its own, so it stays " +
                            "this way while still looking switched on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Switch the service off and on again to clear it. Check you are on " +
                            "the right row: every app built on this SDK appears here, and " +
                            "they are told apart only by their label. If it keeps coming " +
                            "back, exempt this app from battery optimisation and enable " +
                            "autostart, then look in Logcat for a crash.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onOpenSettings) { Text("Open accessibility settings") }
                }

                else -> {
                    Text(
                        "Accessibility service: not enabled",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "AndroPilot needs the accessibility permission to read the screen and " +
                            "perform gestures. Until it is granted, every action returns " +
                            "PERMISSION_REQUIRED rather than failing silently.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onOpenSettings) { Text("Open accessibility settings") }
                }
            }
        }
    }
}

@Composable
private fun ElementRow(element: UiElement, highlighted: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = (if (highlighted) "▶ " else "") + element.describe(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "id=${element.id} source=${element.source.name.lowercase()} " +
                    "actions=${element.actions.joinToString(",") { it.name.lowercase() }}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Fetches the newest release and hands it to the system installer.
 *
 * Present because sideloading each CI build by hand is the slowest part of testing on a real
 * device. The install itself is always confirmed by the user in Android's own dialog.
 */
@Composable
private fun TraceCard() {
    val context = LocalContext.current
    val file = DemoApplication.traceFile
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recorded trace", style = MaterialTheme.typography.titleMedium)
            if (file == null) {
                Text(
                    "Recording is not configured for this build.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val bytes = if (file.exists()) file.length() else 0L
                Text(
                    "${bytes / 1024} KB at ${file.absolutePath}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "One JSON object per action, including the screen the SDK perceived. " +
                        "This build records screen text, so treat the file as you would a " +
                        "screenshot.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareTrace(context, file) }, enabled = bytes > 0) {
                        Text("Share")
                    }
                    OutlinedButton(
                        onClick = { runCatching { file.writeText("") } },
                        enabled = bytes > 0,
                    ) { Text("Clear") }
                }
            }
        }
    }
}

private fun shareTrace(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.traces",
            file,
        )
        context.startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share AndroPilot trace",
            ),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}
