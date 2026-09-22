package com.andropilot.host

import java.io.File

/** Notes about driving one app, or general Android behavior for `_global`. */
public data class Skill(
    val packageName: String,
    val notes: String,
)

/**
 * Per-app guidance, loaded from a directory of Markdown files.
 *
 * Almost every automation failure on Android is app-specific rather than general: a
 * Continue button that is an unlabelled icon, a setting buried two screens deep, a list
 * that must be scrolled before the item exists. A model rediscovers those facts on every
 * run and often gets them wrong. Writing them down once, next to the package they belong
 * to, is the cheapest reliability improvement available.
 *
 * Layout:
 * ```
 * skills/_global/SKILL.md
 * skills/com.android.settings/SKILL.md
 * skills/com.whatsapp/SKILL.md
 * ```
 *
 * The notes are injected host-side, into the model's context. Nothing about them reaches
 * the SDK: this is knowledge about apps, not a change to how the SDK perceives them, and
 * keeping it here means editing a file is enough to change an agent's behaviour.
 */
public class Skills(private val directory: File) {

    /** Re-read on every access so an edit takes effect without restarting the host. */
    public fun all(): List<Skill> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isDirectory }
            ?.filterNot { it.name == GLOBAL_NAME || it.name == UNKNOWN_NAME }
            ?.mapNotNull { dir ->
                val file = File(dir, FILE_NAME).takeIf { it.isFile } ?: return@mapNotNull null
                val notes = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Skill(dir.name, notes)
            }
            ?.sortedBy { it.packageName }
            ?: emptyList()
    }

    /** General Android guidance, injected before app-specific notes. */
    public fun global(): Skill? = read(File(directory, GLOBAL_NAME))

    /**
     * The playbook for an app nothing was written about.
     *
     * A library of app notes is always incomplete -- a phone has a hundred apps and
     * somebody writes notes for a dozen. Without this the uncovered case is the *common*
     * case and gets no help at all, which is backwards.
     */
    public fun unknown(): Skill? = read(File(directory, UNKNOWN_NAME))

    /**
     * One line per app, for the system prompt.
     *
     * The full notes are far too large to hold every app's in context on every turn, and
     * almost all of them are about apps a given run never opens. What the model needs up
     * front is only that notes exist and for which package, so it can recognise the name
     * when the notes arrive.
     */
    public fun index(): List<String> = all().map { skill ->
        val title = skill.notes.lineSequence()
            .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        if (title.isNullOrBlank()) skill.packageName else "${skill.packageName} -- $title"
    }

    public fun forPackage(packageName: String?): Skill? {
        if (packageName.isNullOrBlank()) return null
        return all().firstOrNull { it.packageName == packageName }
    }

    private fun read(dir: File): Skill? {
        val file = File(dir, FILE_NAME).takeIf { it.isFile } ?: return null
        val notes = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return Skill(dir.name, notes)
    }

    private companion object {
        const val FILE_NAME = "SKILL.md"
        const val GLOBAL_NAME = "_global"
        const val UNKNOWN_NAME = "_unknown"
    }
}
