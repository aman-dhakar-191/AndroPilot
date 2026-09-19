package com.andropilot.host

import java.io.File

/** Notes about driving one app, written by hand and keyed on its package name. */
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
            ?.mapNotNull { dir ->
                val file = File(dir, FILE_NAME).takeIf { it.isFile } ?: return@mapNotNull null
                val notes = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Skill(dir.name, notes)
            }
            ?.sortedBy { it.packageName }
            ?: emptyList()
    }

    public fun forPackage(packageName: String?): Skill? {
        if (packageName.isNullOrBlank()) return null
        return all().firstOrNull { it.packageName == packageName }
    }

    private companion object {
        const val FILE_NAME = "SKILL.md"
    }
}
