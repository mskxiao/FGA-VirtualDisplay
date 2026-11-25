package io.github.fate_grand_automata.probe

import android.content.Context

/**
 * Release path that does not go through [ProbeRunner].
 *
 * Rationale: if the runner is what is stuck (a blocked `su`, a hung `screencap`), then its
 * own teardown cannot be relied on, and the user needs a way out that does not require
 * killing the app. Everything here is best-effort by construction: it issues the same
 * shell commands the runner would, with short timeouts, and reports what each returned
 * rather than asserting success.
 *
 * Deliberately does not touch anything it did not create: it only clears the setting this
 * probe owns, force-stops this probe's own package, and never touches other apps.
 */
object EmergencyRelease {

    /**
     * @return one human-readable line per action attempted, in execution order
     */
    fun releaseEverything(context: Context): List<String> {
        val runner = RootShellRunner(AndroidShellRunner { android.util.Log.d("VDProbe", it) })
        val lines = mutableListOf<String>()

        fun step(what: String, argv: List<String>, timeoutMs: Long = 20_000) {
            val r = runCatching { runner.run(argv, timeoutMs) }
                .getOrElse { return@step lines.add("$what -> 异常: ${it.javaClass.simpleName}") }
            lines.add("$what -> exit=${r.exitCode} ${r.stderr.trim().take(120)}")
        }

        // 1. Drop any tapped-but-not-released gesture first, while the display still exists.
        val overlay = runCatching {
            runner.run(listOf("settings", "get", "global", "overlay_display_devices"), 15_000)
                .stdout.trim()
        }.getOrDefault("")
        lines.add("当前 overlay_display_devices=${overlay.ifEmpty { "<empty>" }}")

        val ids = runCatching {
            runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 15_000)
                .stdout.trim().lines().mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }
        }.getOrDefault(emptyList())
        for (id in ids) {
            step("cancelGesture display $id", listOf("input", "-d", id.toString(), "tap", "-1", "-1"), 5_000)
        }

        // 2. Stop the probe's own target, and only that.
        step("force-stop ${context.packageName}", listOf("am", "force-stop", context.packageName))

        // 3. Remove the setting this probe is responsible for.
        step("delete overlay_display_devices", listOf("settings", "delete", "global", "overlay_display_devices"))

        // 4. Report what is actually left, so the user sees the real end state.
        val after = runCatching {
            runner.run(listOf("settings", "get", "global", "overlay_display_devices"), 15_000)
                .stdout.trim()
        }.getOrDefault("")
        val afterIds = runCatching {
            runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 15_000)
                .stdout.trim().replace("\n", ",")
        }.getOrDefault("")
        lines.add("释放后 overlay=${after.ifEmpty { "<empty>" }}")
        lines.add("释放后 displays=$afterIds")

        return lines
    }
}
