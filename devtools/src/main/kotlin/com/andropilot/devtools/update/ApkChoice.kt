package com.andropilot.devtools.update

/** One APK on a release, reduced to what choosing between them needs. */
internal data class ApkCandidate(val name: String, val url: String, val size: Long)

/** The outcome of picking which APK on a release belongs to this app. */
internal sealed interface ApkChoice {
    data class Chosen(val asset: ApkCandidate) : ApkChoice
    data object None : ApkChoice

    /** Several APKs and nothing to tell them apart. Named so the message can say which. */
    data class Ambiguous(val candidates: List<String>) : ApkChoice
}

/**
 * Picks the APK asset belonging to the app that is asking.
 *
 * A release used to carry exactly one APK, so "the first one ending in .apk" was correct.
 * Then a second app shipped from the same repository and that rule silently began choosing
 * by alphabetical accident: the GitHub API returns assets sorted by name, so
 * `andropilot-agent-*.apk` sorts before `andropilot-demo-*.apk` and the inspector started
 * downloading and installing the agent instead of itself. Both apps share a signing key, so
 * the install succeeded -- the wrong app moved forward and the right one never did, with no
 * error anywhere.
 *
 * With more than one candidate and no [hint], this refuses rather than guesses, and says
 * which ones it found. A wrong guess here installs a different application; that is not a
 * place to be clever.
 */
internal fun chooseApk(assets: List<ApkCandidate>, hint: String?): ApkChoice {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return ApkChoice.None

    val candidates = if (hint.isNullOrBlank()) apks else apks.filter { it.name.contains(hint, ignoreCase = true) }
    return when (candidates.size) {
        0 -> ApkChoice.None
        1 -> ApkChoice.Chosen(candidates.single())
        else -> ApkChoice.Ambiguous(candidates.map { it.name })
    }
}
