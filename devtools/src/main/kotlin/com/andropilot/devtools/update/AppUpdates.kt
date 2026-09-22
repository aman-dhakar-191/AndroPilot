package com.andropilot.devtools.update

/**
 * Where [UpdateViewModel] gets its repository from.
 *
 * A ViewModel built by the default factory takes only an `Application`, so the repository
 * cannot be passed through the constructor without forcing every consumer to write a
 * factory. One call in `Application.onCreate` is the smaller imposition.
 *
 * There is no default repository on purpose: one would silently point an app at someone
 * else's releases, and the failure would look like "no update available" rather than an
 * error.
 */
public object AppUpdates {

    @Volatile
    private var repository: String? = null

    @Volatile
    private var apkAsset: String? = null

    /**
     * @param repository the GitHub repository holding the releases, as `owner/name`.
     * @param apkAsset part of the APK filename identifying this app, needed when the
     *   repository ships more than one app and every release therefore carries more than one
     *   APK. Without it the updater refuses to choose rather than picking whichever sorts
     *   first, which is not a guess worth making when the cost is installing a different
     *   application.
     */
    @JvmOverloads
    public fun configure(repository: String, apkAsset: String? = null) {
        this.repository = repository
        this.apkAsset = apkAsset
    }

    public fun isConfigured(): Boolean = repository != null

    internal fun requireRepository(): String = repository ?: error(
        "AppUpdates.configure(\"owner/repo\") must be called before using UpdateViewModel.",
    )

    internal fun apkAsset(): String? = apkAsset
}
