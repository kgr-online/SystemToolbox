package com.kgr.systemtoolbox.modules

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.kgr.systemtoolbox.core.RootShell

/**
 * Standalone "Slim List" Recents: a thumbnail-free vertical list of running
 * tasks, entirely independent of the launcher's own RecentsView - reads live
 * task state via root `dumpsys activity recents` and switches tasks via a
 * plain `am start`. Nothing to hook, no launcher-crash risk.
 *
 * Ported from Key2Toolbox's SlimRecentsController with the Masonry-only
 * pieces dropped - snapshot loading/caching from the system snapshot cache,
 * live-screenshot capture for the "hero" tile, and per-app banner colour via
 * Palette. Slim List is thumbnail-free by design, so none of that is needed.
 *
 * Resume-not-restart: Key2Toolbox verified on its device that `am start -n
 * <component> -f 0x00020000` (Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) brings an
 * existing task to front in place rather than recreating its top activity, so
 * long as <component> is the task's *current* top activity - not necessarily
 * the task's root/launch intent, which `dumpsys`'s own `mActivityComponent=` /
 * `intent=` fields describe instead. Re-verify this and the dumpsys line
 * formats below against the P9P's actual Android 17 Beta 3 build before
 * relying on them - `dumpsys activity recents` output has changed across AOSP
 * versions before.
 */
object SlimRecentsController {

    data class SlimTask(
        val taskId: Int,
        val packageName: String,
        /** The task's current top activity, e.g. "com.foo.bar/.MainActivity" - what gets passed to `am start -n`. */
        val topComponent: String,
        val label: String,
        val icon: Drawable?,
    )

    private val TASK_HEADER = Regex("""Task\{[0-9a-f]+ #(\d+) type=(\S+)""")
    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{\S+ u\d+ ([\w.]+/[\w.${'$'}]+) t\d+\}""")

    /** Packages excluded from the list regardless of task type. */
    private val EXCLUDED_PACKAGES = setOf(
        "com.google.android.apps.nexuslauncher",
        "de.mm20.launcher2.nightly", // Kvesitso
    )

    /**
     * `type=` values Key2Toolbox saw in real dumps: `standard` for a task
     * that's been the foreground task this boot session, `undefined` for a
     * cold app task restored from the persisted task list with no live
     * process behind it (still a real switchable task - it just launches
     * fresh rather than resuming, since there's no process to reorder to
     * front). `home`/`recents` are the launcher's own non-app entries.
     * Re-verify these values against a real `dumpsys activity recents` dump
     * on the P9P before trusting the filter - AOSP has changed this format
     * across versions.
     */
    private val EXCLUDED_TYPES = setOf("home", "recents")

    /**
     * Live, ordered (most-recent-first, matching the dump's own order) list of
     * switchable tasks. `type=standard` already excludes home/recents/assistant
     * entries; [EXCLUDED_PACKAGES] is dropped as a second belt-and-braces filter
     * in case this ROM reports those differently. SystemToolbox's own task is
     * intentionally included - Slim List is a standalone overlay that can be
     * triggered while any app (including SystemToolbox itself) is foreground.
     * Blocking (runs a root shell command) - call off the main thread.
     */
    fun listTasks(context: Context): List<SlimTask> {
        val dump = RootShell.run("dumpsys activity recents").outString
        val chunks = splitIntoTaskChunks(dump)
        val pm = context.packageManager

        val out = ArrayList<SlimTask>(chunks.size)
        for (chunk in chunks) {
            val header = chunk.first()
            val m = TASK_HEADER.find(header) ?: continue
            val taskId = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2] in EXCLUDED_TYPES) continue

            val topComponent = topComponentOf(chunk) ?: continue
            val pkg = topComponent.substringBefore("/")
            if (pkg.isEmpty() || pkg in EXCLUDED_PACKAGES) continue

            val (label, icon) = labelAndIcon(pm, pkg)
            out.add(SlimTask(taskId, pkg, topComponent, label, icon))
        }
        return out
    }

    /**
     * Bring [task] to front without recreating it - see class doc for why
     * this specific flag. Blocking - call off the main thread.
     */
    fun resumeTask(task: SlimTask) {
        RootShell.run("am start -n ${task.topComponent} -f 0x00020000")
    }

    /**
     * Removes [task] from the recents list outright - not just killing its
     * process. Key2Toolbox confirmed on its device: `am force-stop` only kills
     * the process and leaves the task record in `dumpsys activity recents`
     * untouched, while `am stack remove <taskId>` actually deletes the task
     * record. Also better than force-stop alone for a package with multiple
     * simultaneous tasks: this targets one task by ID rather than closing all
     * of that package's tasks at once. Chains a force-stop afterward too -
     * unconfirmed whether `stack remove` alone also kills the process or could
     * leave it as a background orphan; the force-stop is a no-op if the
     * process already exited on its own. Blocking - call off the main thread.
     */
    fun dismissTask(task: SlimTask) {
        RootShell.run("am stack remove ${task.taskId} ; am force-stop ${task.packageName}")
    }

    /** Removes every listed task in one shell round-trip. */
    fun dismissAll(tasks: List<SlimTask>) {
        if (tasks.isEmpty()) return
        val cmd = tasks.joinToString(" ; ") { "am stack remove ${it.taskId} ; am force-stop ${it.packageName}" }
        RootShell.run(cmd)
    }

    // --- parsing ------------------------------------------------------

    private fun splitIntoTaskChunks(dump: String): List<List<String>> {
        val chunks = mutableListOf<MutableList<String>>()
        for (line in dump.lineSequence()) {
            if (line.trimStart().startsWith("* Recent #")) {
                chunks.add(mutableListOf(line))
            } else if (chunks.isNotEmpty()) {
                chunks.last().add(line)
            }
        }
        return chunks
    }

    /** The task's current top activity: last entry in `Activities=[...]`, falling back to `mActivityComponent=`. */
    private fun topComponentOf(chunk: List<String>): String? {
        val activitiesLine = chunk.firstOrNull { it.trimStart().startsWith("Activities=[") }
        val fromActivities = activitiesLine
            ?.let { line -> ACTIVITY_RECORD.findAll(line).map { it.groupValues[1] }.lastOrNull() }
        if (fromActivities != null) return fromActivities

        return chunk.firstOrNull { it.trimStart().startsWith("mActivityComponent=") }
            ?.substringAfter("mActivityComponent=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun labelAndIcon(pm: PackageManager, pkg: String): Pair<String, Drawable?> = runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
    }.getOrElse { pkg to null }
}
