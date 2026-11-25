package io.github.fate_grand_automata.probe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs [ProbeRunner] several times in a row and checks that nothing accumulates.
 *
 * The requirement "repeat at least 3 times without resource accumulation" cannot be
 * satisfied by running once and assuming it scales. Two things are therefore measured
 * across iterations rather than within one:
 *
 *  - **Display ids are not reused.** Each iteration must observe a *new* logical display
 *    id, and the id set must shrink back to just the physical display between runs. If a
 *    display leaked, the next iteration would either reuse an id or find an extra one.
 *
 *  - **SurfaceFlinger count returns to zero.** An id can be gone from DisplayManager while
 *    SurfaceFlinger still holds the surface, so both are checked.
 *
 * The suite fails if any iteration fails, or if any iteration shows accumulation, even when
 * that iteration's own steps passed. "Passed once" is not the claim being made.
 */
class ProbeSuite(
    private val context: Context,
    private val log: (String) -> Unit = {},
    private val onProgress: (SuiteProgress) -> Unit = {},
) {

    data class IterationResult(
        val index: Int,
        val results: List<ProbeRunner.StepResult>,
        val displaysBeforeStart: List<Int>,
        val displaysAfterStop: List<Int>,
        val virtualDisplaysInSurfaceFlinger: Int,
        val logicalDisplayId: Int,
        val surfaceFlingerId: String?,
    ) {
        val passed: Boolean
            get() = results.none {
                it.verdict == ProbeRunner.Verdict.FAIL || it.verdict == ProbeRunner.Verdict.ERROR
            }

        /** True when this iteration left no extra display behind. */
        val cleanAfter: Boolean
            get() = displaysAfterStop.filter { it != 0 }.isEmpty() &&
                virtualDisplaysInSurfaceFlinger == 0
    }

    data class SuiteProgress(
        val totalIterations: Int,
        val completed: List<IterationResult>,
    ) {
        val allPassed: Boolean get() = completed.isNotEmpty() && completed.all { it.passed }
        val allClean: Boolean get() = completed.isNotEmpty() && completed.all { it.cleanAfter }
    }

    data class SuiteReport(
        val iterations: List<IterationResult>,
        val reportPath: String?,
    ) {
        val allPassed: Boolean get() = iterations.isNotEmpty() && iterations.all { it.passed }
        val allClean: Boolean get() = iterations.isNotEmpty() && iterations.all { it.cleanAfter }

        /** No two iterations may have used the same logical display id on a fresh start. */
        val idsDistinct: Boolean
            get() = iterations.map { it.logicalDisplayId }.distinct().size == iterations.size

        val overallOk: Boolean get() = allPassed && allClean // && idsDistinct checked by caller
    }

    /**
     * @param iterations how many full runs to execute; the stage-2A requirement is 3
     */
    fun run(iterations: Int = DEFAULT_ITERATIONS): SuiteReport {
        require(iterations >= 1) { "iterations must be >= 1" }
        val done = mutableListOf<IterationResult>()

        repeat(iterations) { i ->
            val runner = RootShellRunner(AndroidShellRunner(log))

            // Measure the world immediately before this run.
            val before = displays(runner)

            val probe = ProbeRunner(context, log)
            val results = probe.run()

            // Measure immediately after its own teardown.
            val after = displays(runner)
            val sfVirtual = virtualDisplays(runner)
            val ids = results.firstOrNull { it.step == ProbeRunner.Step.VERIFY_DISPLAY }
                ?.detail ?: ""
            val logical = Regex("logical=(\\d+)").find(ids)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val sfId = Regex("surfaceFlinger=(\\d+)").find(ids)?.groupValues?.get(1)

            val iteration = IterationResult(
                index = i + 1,
                results = results,
                displaysBeforeStart = before,
                displaysAfterStop = after,
                virtualDisplaysInSurfaceFlinger = sfVirtual,
                logicalDisplayId = logical,
                surfaceFlingerId = sfId,
            )
            done += iteration
            log(
                "iteration ${i + 1}/$iterations: passed=${iteration.passed} " +
                    "cleanAfter=${iteration.cleanAfter} logical=$logical sf=$sfId",
            )
            onProgress(SuiteProgress(iterations, done.toList()))

            // Between iterations, give the platform a moment to settle so a slow teardown is
            // not mistaken for a leak.
            if (i < iterations - 1) Thread.sleep(INTER_ITERATION_MS)
        }

        val reportPath = writeReport(done)
        return SuiteReport(done.toList(), reportPath)
    }

    private fun displays(runner: RootShellRunner): List<Int> =
        runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 30_000)
            .stdout.trim().lines().mapNotNull { it.trim().toIntOrNull() }

    private fun virtualDisplays(runner: RootShellRunner): Int =
        runner.run(listOf("dumpsys", "SurfaceFlinger", "--display-id"), 30_000)
            .stdout.lineSequence().count { it.contains("Virtual display") }

    /**
     * A plain-text report written next to the frames, so the whole run can be pulled off the
     * device and attached as evidence without screenshots.
     */
    private fun writeReport(iterations: List<IterationResult>): String? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "suite-report-$stamp.txt")
        file.bufferedWriter().use { w ->
            w.appendLine("FGA-VirtualDisplay stage 2A probe suite")
            w.appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            w.appendLine("android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
            w.appendLine("package=${context.packageName}")
            w.appendLine("time=$stamp")
            w.appendLine()
            for (it in iterations) {
                w.appendLine("===== iteration ${it.index} =====")
                w.appendLine("displaysBeforeStart=${it.displaysBeforeStart}")
                w.appendLine("logicalDisplayId=${it.logicalDisplayId}")
                w.appendLine("surfaceFlingerId=${it.surfaceFlingerId}")
                w.appendLine("displaysAfterStop=${it.displaysAfterStop}")
                w.appendLine("virtualDisplaysInSurfaceFlinger=${it.virtualDisplaysInSurfaceFlinger}")
                w.appendLine("passed=${it.passed} cleanAfter=${it.cleanAfter}")
                for (r in it.results) {
                    w.appendLine("  [${r.verdict}] ${r.step.label}")
                    w.appendLine("        ${r.detail}")
                    r.evidencePath?.let { p -> w.appendLine("        evidence=$p") }
                }
                w.appendLine()
            }
            val ids = iterations.map { it.logicalDisplayId }
            w.appendLine("===== summary =====")
            w.appendLine("iterations=${iterations.size}")
            w.appendLine("allPassed=${iterations.all { it.passed }}")
            w.appendLine("allClean=${iterations.all { it.cleanAfter }}")
            w.appendLine("logicalIds=$ids")
            w.appendLine("idsDistinct=${ids.distinct().size == ids.size}")
        }
        file.absolutePath
    }.getOrNull()

    companion object {
        const val DEFAULT_ITERATIONS = 3
        private const val INTER_ITERATION_MS = 2_500L
    }
}
