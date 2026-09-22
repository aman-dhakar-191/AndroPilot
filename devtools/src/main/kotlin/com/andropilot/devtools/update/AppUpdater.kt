package com.andropilot.devtools.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A release, reduced to what an updater needs. */
public data class AvailableRelease(
    val version: String,
    val versionCode: Int,
    val apkName: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Where the update flow currently is. */
public sealed interface UpdateState {
    public data object Idle : UpdateState
    public data object Checking : UpdateState
    public data class UpToDate(val current: String) : UpdateState
    public data class Available(val release: AvailableRelease) : UpdateState
    public data class Downloading(val release: AvailableRelease, val percent: Int) : UpdateState
    public data class ReadyToInstall(val release: AvailableRelease, val file: File) : UpdateState
    public data object AwaitingUserConfirmation : UpdateState
    public data class NeedsPermission(val message: String) : UpdateState
    public data class Failed(val message: String) : UpdateState
}

/**
 * Fetches the newest release from GitHub and hands the APK to the system installer.
 *
 * **This is a separate artifact from the SDK, and that boundary is deliberate.** The SDK has
 * no network code and must not gain any; an automation library that could also download and
 * install packages is a different and far more dangerous thing than one that taps buttons.
 * Combining them is a decision an integrator makes explicitly, by adding this dependency.
 *
 * It never installs silently. The APK is handed to Android's `PackageInstaller`, which shows
 * its own confirmation, and the user approves it. Nothing here drives that dialog, and the
 * accessibility service must never be pointed at it: a component that can tap "Install" and
 * also choose what to install would be a malware primitive, whatever the intent.
 *
 * Updates only work at all when every build is signed with the same key. Android refuses to
 * replace an app whose signature changed, which is what "App not installed as package
 * conflicts with an existing package" means. See the `dev` signing config in
 * `demo/build.gradle.kts` for how this repository does it.
 *
 * Consuming apps must declare `REQUEST_INSTALL_PACKAGES` themselves; this library does not
 * declare it, so no app inherits an install permission it did not ask for.
 */
public class AppUpdater(
    private val context: Context,
    /** The GitHub repository to read releases from, as `owner/name`. */
    private val repository: String,
    /**
     * Which app to look after. Defaults to the one asking.
     *
     * An updater that can only update itself has to live inside every app it serves, which
     * means every one of them carries the permission to install packages. Naming the target
     * lets that permission sit in one place instead.
     */
    private val target: UpdateTarget = UpdateTarget(context.packageName),
) {

    private val apkAsset: String? get() = target.apkAsset

    // Its own parser rather than the SDK's: this module does not depend on the SDK, so an
    // app can use the updater without embedding an automation library, and the SDK never
    // acquires a reason to grow network code.
    private val json = Json { ignoreUnknownKeys = true }

    private val downloadDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
            .apply { mkdirs() }

    /** Asks GitHub what the newest release is and compares it to what is installed. */
    public suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
                ?: return@withContext UpdateState.Failed(
                    "The latest release has no APK asset attached.",
                )
            val installed = installedVersionCode()
            if (release.versionCode <= installed) {
                UpdateState.UpToDate(installedVersionName())
            } else {
                UpdateState.Available(release)
            }
        } catch (e: Exception) {
            UpdateState.Failed(describe(e))
        }
    }

    /**
     * Streams the APK to app-private storage, reporting progress.
     *
     * Written to `getExternalFilesDir` so it needs no storage permission and is removed when
     * the app is uninstalled.
     */
    public suspend fun download(
        release: AvailableRelease,
        onProgress: (Int) -> Unit = {},
    ): UpdateState = withContext(Dispatchers.IO) {
        try {
            val target = File(downloadDir, release.apkName)
            if (target.exists() && target.length() == release.sizeBytes && release.sizeBytes > 0) {
                // Already fetched in a previous attempt; do not pay for it twice.
                return@withContext UpdateState.ReadyToInstall(release, target)
            }

            val connection = open(release.apkUrl)
            val total = release.sizeBytes.takeIf { it > 0 } ?: connection.contentLengthLong
            var read = 0L
            val partial = File(downloadDir, release.apkName + ".part")

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            // Rename only once complete, so an interrupted download is never mistaken for a
            // finished one on the next attempt.
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                return@withContext UpdateState.Failed("Could not finalise the download.")
            }
            UpdateState.ReadyToInstall(release, target)
        } catch (e: Exception) {
            UpdateState.Failed(describe(e))
        }
    }

    /**
     * Hands the APK to the system installer.
     *
     * Returns [UpdateState.NeedsPermission] when the app may not request installs; the user
     * has to grant that in system settings, and this cannot grant it for them.
     */
    public fun install(file: File): UpdateState {
        // Belt and braces over the asset choice above. Reading the archive's own package
        // name is the only check a filename cannot fool, and it matters more now that an
        // updater looks after apps other than itself: the question is no longer "is this
        // me" but "is this the app I said I was updating". When everything shares a signing
        // key the wrong answer installs cleanly and says nothing.
        val archived = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        }.getOrNull()
        if (archived != null && archived != target.packageName) {
            return UpdateState.Failed(
                "That download is $archived, not ${target.packageName}. Refusing to install " +
                    "a different app. Check which asset the updater is configured to take.",
            )
        }

        if (!canRequestInstalls()) {
            return UpdateState.NeedsPermission(
                "This app cannot install packages. Declare " +
                    "android.permission.REQUEST_INSTALL_PACKAGES in its manifest -- this " +
                    "library deliberately does not declare it for you -- and grant " +
                    "\"Install unknown apps\" in system settings.",
            )
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(statusIntent(sessionId).intentSender)
            }
            UpdateState.AwaitingUserConfirmation
        } catch (e: Exception) {
            UpdateState.Failed(describe(e))
        }
    }

    /** Opens the settings screen where "Install unknown apps" is granted. */
    public fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Deletes downloaded APKs this updater made, except [keep].
     *
     * Only this app's own update directory is touched. Files a browser put in the public
     * Downloads folder are outside any app-scoped directory and cannot be removed without
     * broad storage permissions that an app like this has no business holding.
     */
    public fun cleanUpDownloads(keep: File? = null): Int {
        val files = downloadDir.listFiles().orEmpty()
        var removed = 0
        files.forEach { file ->
            if (file.absolutePath != keep?.absolutePath && file.delete()) removed++
        }
        return removed
    }

    /** Total bytes currently held by downloaded updates. */
    public fun downloadedBytes(): Long =
        downloadDir.listFiles().orEmpty().sumOf { it.length() }

    /** "unknown" also covers the target not being installed at all. */
    public fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(target.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /** False when the target is not on the device, so the UI can offer an install instead. */
    public fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(target.packageName, 0)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    public fun installedVersionCode(): Int = runCatching {
        val info = context.packageManager.getPackageInfo(target.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(0)

    // ---- internals ---------------------------------------------------------------------

    private fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS)
            .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
        // MUTABLE is required: the installer fills in the status extras, including the
        // confirmation intent the receiver has to launch.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private fun fetchLatestRelease(): AvailableRelease? {
        val connection = open("https://api.github.com/repos/$repository/releases/latest")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val release = json.decodeFromString<GitHubRelease>(body)
        val candidates = release.assets.map { ApkCandidate(it.name, it.url, it.size) }
        val asset = when (val choice = chooseApk(candidates, apkAsset)) {
            is ApkChoice.Chosen -> choice.asset
            is ApkChoice.None -> return null
            is ApkChoice.Ambiguous -> error(
                "This release has ${choice.candidates.size} APKs and nothing says which is " +
                    "this app's: ${choice.candidates.joinToString()}. Pass the distinguishing " +
                    "part of the filename to AppUpdates.configure(repository, apkAsset = ...).",
            )
        }
        val version = release.tagName.removePrefix("v")
        return AvailableRelease(
            version = version,
            versionCode = versionCodeOf(version),
            apkName = asset.name,
            apkUrl = asset.url,
            sizeBytes = asset.size,
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AndroPilot-Inspector")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                error(
                    when (code) {
                        404 -> "No release found. If the repository is private, the GitHub " +
                            "API needs a token, which this updater deliberately does not store."
                        403 -> "GitHub rate-limited the request. Try again in a few minutes."
                        else -> "GitHub returned HTTP $code."
                    },
                )
            }
        }

    private fun describe(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "Unknown failure")

    public companion object {
        /**
         * Packs a semantic version into the single increasing integer Android compares.
         * Must match the scheme in `demo/build.gradle.kts`, or an update will look older
         * than what is installed and be refused.
         */
        public fun versionCodeOf(version: String): Int {
            val parts = version.substringBefore('-').split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)
