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

    /** @param repository the GitHub repository holding the releases, as `owner/name`. */
    public fun configure(repository: String) {
        this.repository = repository
    }

    public fun isConfigured(): Boolean = repository != null

    internal fun requireRepository(): String = repository ?: error(
        "AppUpdates.configure(\"owner/repo\") must be called before using UpdateViewModel.",
    )
}
