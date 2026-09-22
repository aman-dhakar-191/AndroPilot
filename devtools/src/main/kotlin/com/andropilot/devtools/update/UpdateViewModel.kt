package com.andropilot.devtools.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Drives the update card. Holds no logic of its own beyond sequencing [AppUpdater]. */
public class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val updater = AppUpdater(application, AppUpdates.requireRepository(), AppUpdates.apkAsset())

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    public val state: StateFlow<UpdateState> = _state.asStateFlow()

    public val installedVersion: String get() = updater.installedVersionName()

    public fun downloadedBytes(): Long = updater.downloadedBytes()

    public fun check() {
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            _state.value = updater.check()
        }
    }

    public fun download(release: AvailableRelease) {
        viewModelScope.launch {
            _state.value = UpdateState.Downloading(release, 0)
            _state.value = updater.download(release) { percent ->
                _state.value = UpdateState.Downloading(release, percent)
            }
        }
    }

    public fun install(state: UpdateState.ReadyToInstall) {
        _state.value = updater.install(state.file)
    }

    public fun grantInstallPermission() {
        updater.requestInstallPermission()
    }

    /** Frees everything the updater downloaded. */
    public fun clearDownloads() {
        updater.cleanUpDownloads()
        _state.value = UpdateState.Idle
    }
}
