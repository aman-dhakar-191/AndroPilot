package com.andropilot.updater

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import android.app.Application
import com.andropilot.devtools.update.AvailableRelease
import com.andropilot.devtools.update.UpdateState
import com.andropilot.devtools.update.UpdateTarget
import com.andropilot.devtools.update.UpdateViewModel

/**
 * One screen that keeps the other AndroPilot apps current.
 *
 * Its own app rather than a second screen inside one of the others, because the permission
 * to install packages then lives somewhere that cannot also read or tap a screen. The two
 * apps it looks after both run an accessibility service; this one has no SDK and no
 * automation code at all.
 *
 * It never installs silently. Each APK goes to Android's `PackageInstaller`, which shows
 * its own confirmation, and nothing here touches that dialog.
 */
public class UpdaterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { UpdaterScreen() }
            }
        }
    }
}

@Composable
private fun UpdaterScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // targetSdk 35 draws this edge to edge whether or not it was written for it.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AndroPilot Updates", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Fetches the newest release and hands each APK to Android's installer, which " +
                "asks before anything is replaced.",
            style = MaterialTheme.typography.bodySmall,
        )

        UpdaterApplication.TARGETS.forEach { target ->
            AppCard(target)
        }

        Text(
            "Updating an app that runs an accessibility service switches that service off. " +
                "Android drops the binding when the package is replaced and does not " +
                "reconnect on its own, so re-enable it in Accessibility settings afterwards.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AppCard(target: UpdateTarget) {
    val model: UpdateViewModel = viewModel(
        key = target.packageName,
        factory = updateViewModelFactory(target),
    )
    val state by model.state.collectAsStateWithLifecycle()

    // Re-check on resume: coming back from the installer is exactly when the answer changed.
    LifecycleResumeEffect(target.packageName) {
        model.check()
        onPauseOrDispose {}
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(target.label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (model.isInstalled) "Installed: ${model.installedVersion}" else "Not installed",
                style = MaterialTheme.typography.bodySmall,
            )

            when (val current = state) {
                is UpdateState.Idle, is UpdateState.Checking ->
                    Text("Checking...", style = MaterialTheme.typography.bodySmall)

                is UpdateState.UpToDate ->
                    Text("Up to date.", style = MaterialTheme.typography.bodyMedium)

                is UpdateState.Available -> {
                    Text(
                        if (model.isInstalled) "${current.release.version} is available."
                        else "${current.release.version} can be installed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { model.download(current.release) }) {
                        Text(if (model.isInstalled) "Download and update" else "Download and install")
                    }
                }

                is UpdateState.Downloading -> {
                    Text("Downloading ${current.percent}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { current.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateState.ReadyToInstall -> {
                    Text("${current.release.version} is ready.", style = MaterialTheme.typography.bodyMedium)
                    if (target.disablesAccessibilityService) {
                        Text(
                            "This will switch off its accessibility service; re-enable it " +
                                "afterwards.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.install(current) }) { Text("Install") }
                        OutlinedButton(onClick = { model.clearDownloads() }) { Text("Discard") }
                    }
                }

                is UpdateState.AwaitingUserConfirmation ->
                    Text("Waiting for the system installer.", style = MaterialTheme.typography.bodySmall)

                is UpdateState.NeedsPermission -> {
                    Text(current.message, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.grantInstallPermission() }) {
                        Text("Open install permission")
                    }
                }

                is UpdateState.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { model.check() }) { Text("Try again") }
                }
            }
        }
    }
}

/**
 * One view model per app.
 *
 * `viewModel()` builds from an `Application` alone, so the target has to arrive through a
 * factory; sharing a single model between both cards would show whichever check finished
 * last against both names.
 */
private fun updateViewModelFactory(target: UpdateTarget) = viewModelFactory {
    initializer {
        UpdateViewModel(
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application,
            target,
        )
    }
}
