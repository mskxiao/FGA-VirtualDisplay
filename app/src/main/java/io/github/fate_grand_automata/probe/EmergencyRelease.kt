package io.github.fate_grand_automata.probe

import android.content.Context

/**
 * Release path that does not go through [ProbeRunner].
 *
 * Rationale: if the runner is what is stuck (a blocked `su`, a hung `screencap`), then its
 * own teardown cannot be relied on, so the user needs a way out that does not require
 * killing the app. Everything here is best-effort by construction: it issues the same shell
 * commands the runner would, with short timeouts, and reports what each returned rather
 * than asserting success.
 *
 * Deliberately does not touch anything it did not create: it only clears the setting this
 * probe owns and force-stops this probe's own package.
 */
object EmergencyRelease {

    /**
     * @return one human-readable line per action attempted, in execution order
     */
    fun releaseEverything(context: Context): List<String> {
        val runner = RootShellRunner(AndroidShellRunner { android.util.Log.d("VDProbe", it) })
        val lines = mutableListOf<String>()

        fun step(what: String, argv: List<String>, timeoutMs: Long = 20_000) {
            val result = runCatching { runner.run(argv, timeoutMs) }
            val message = result.fold(
                onSuccess = { r ->
                    val err = r.stderr.trim().take(120)
                    "$what -> exit=${r.exitCode}" + if (err.isEmpty()) "" else " $err"
                },
                onFailure = { t -> "$what -> 异常: ${t.javaClass.simpleName}: ${t.message}" },
            )
            lines.add(message)
        }

        // 1. Record what we are about to touch, so the report shows the before state.
        val overlayBefore = runCatching {
            runner.run(listOf("settings", "get", "global", "overlay_display_devices"), 15_000)
                .stdout.trim()
        }.getOrDefault("")
        lines.add("当前 overlay_display_devices=${overlayBefore.ifEmpty { "<empty>" }}")

        val ids = runCatching {
            runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 15_000)
                .stdout.trim().lines().mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }
        }.getOrDefault(emptyList())

        // 2. Release any pressed pointer first, while the display still exists.
        for (id in ids) {
            step(
                "cancelGesture display $id",
                listOf("input", "-d", id.toString(), "tap", "-1", "-1"),
                5_000,
            )
        }

        // 3. Stop this probe's own target, and only that.
        step("force-stop ${context.packageName}", listOf("am", "force-stop", context.packageName))

        // 4. Remove the setting this probe is responsible for.
        step(
            "delete overlay_display_devices",
            listOf("settings", "delete", "global", "overlay_display_devices"),
        )

        // 5. Report the actual end state instead of claiming success.
        val overlayAfter = runCatching {
            runner.run(listOf("settings", "get", "global", "overlay_display_devices"), 15_000)
                .stdout.trim()
        }.getOrDefault("")
        val displaysAfter = runCatching {
            runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 15_000)
                .stdout.trim().replace("\n", ",")
        }.getOrDefault("")
        lines.add("释放后 overlay=${overlayAfter.ifEmpty { "<empty>" }}")
        lines.add("释放后 displays=$displaysAfter")

        return lines
    }
}
