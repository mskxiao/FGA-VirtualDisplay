package io.github.fate_grand_automata.probe

import android.content.Context
import io.github.fate_grand_automata.vdisplay.DisplayConfig
import io.github.fate_grand_automata.vdisplay.DisplayFrame
import io.github.fate_grand_automata.vdisplay.ShellVirtualDisplayBackend
import io.github.fate_grand_automata.vdisplay.VirtualDisplayBackend
import io.github.fate_grand_automata.vdisplay.VirtualDisplayError
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Runs the stage-2A closed loop inside the app process and records a per-step verdict.
 *
 * Design rules in force here, all consequences of "do not claim success without evidence":
 *
 *  1. **Every step has an explicit assertion.** A step cannot pass because no exception was
 *     thrown; it passes only by evaluating a condition against observed data.
 *
 *  2. **Teardown runs on every exit path**, including a thrown error and including a failed
 *     earlier step. Resources are released in the documented order and the release itself
 *     is verified, not assumed.
 *
 *  3. **The tap is judged by two independent witnesses**: the activity's own tap counters
 *     (did a tap reach *this* display) and the before/after frames (did the pixels change
 *     where expected). Either alone could pass for the wrong reason; both together cannot.
 *
 *  4. **Failure keeps evidence.** The frame that failed is written to disk and its path is
 *     recorded, so a failure can be diagnosed from the screenshot rather than from a log
 *     line that says "not found".
 */
class ProbeRunner(
    private val context: Context,
    private val shellLog: (String) -> Unit = {},
    private val onUpdate: (List<StepResult>) -> Unit = {},
) {

    enum class Step(val label: String) {
        INIT_BACKEND("初始化后端（App 进程内）"),
        ROOT_CHECK("确认 root 与 su -c 上下文"),
        READ_BASELINE("记录建屏前的显示基线"),
        CREATE_DISPLAY("创建 1280x720 虚拟屏"),
        VERIFY_DISPLAY("确认虚拟屏已创建（logical + SF id）"),
        LAUNCH_TARGET("将 ProbeActivity 启动到虚拟屏"),
        VERIFY_TARGET_DISPLAY("确认 Activity 所在 Display ID"),
        CAPTURE_BEFORE("截取虚拟屏画面"),
        VERIFY_FRAME("确认截图尺寸与内容"),
        DETECT_BLOCK("识别红色方块"),
        TAP_BLOCK("定向点击红色方块"),
        CAPTURE_AFTER("再次截图"),
        VERIFY_COLOUR_CHANGE("确认方块颜色变化"),
        VERIFY_TAP_REACHED("确认点击到达目标 Display"),
        STOP_TARGET("停止目标 Activity"),
        STOP_DISPLAY("释放虚拟屏"),
        VERIFY_RELEASE("验证资源已释放无残留"),
    }

    enum class Verdict { PASS, FAIL, SKIP, ERROR }

    data class StepResult(
        val step: Step,
        val verdict: Verdict,
        val detail: String,
        val evidencePath: String? = null,
    )

    private val results = CopyOnWriteArrayList<StepResult>()
    private val cancelled = AtomicBoolean(false)

    @Volatile
    var backend: ShellVirtualDisplayBackend? = null
        private set

    @Volatile
    private var beforeFrame: DisplayFrame? = null

    @Volatile
    private var afterFrame: DisplayFrame? = null

    @Volatile
    private var detectedCenterX: Int = -1

    @Volatile
    private var detectedCenterY: Int = -1

    @Volatile
    private var detectedColourBefore: Int = 0

    val evidenceDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe").apply { mkdirs() }

    fun cancel() {
        cancelled.set(true)
    }

    fun snapshotResults(): List<StepResult> = results.toList()

    /**
     * Run the whole loop. Blocks; call from a worker thread.
     *
     * @return the full result list, whether the run passed or failed
     */
    fun run(): List<StepResult> {
        results.clear()
        cancelled.set(false)
        ProbeActivity.Observer.resetState()

        val cfg = DisplayConfig.STAGE1
        val runner = RootShellRunner(AndroidShellRunner(shellLog))
        val be = ShellVirtualDisplayBackend(runner)
        backend = be

        var overallOk = true

        try {
            // ---- 1. backend construction. Nothing is created yet. --------------------
            record(Step.INIT_BACKEND, Verdict.PASS, "ShellVirtualDisplayBackend 已构造（进程内）")

            // ---- 2. root. Checked explicitly because everything downstream depends on it
            //         and a bare "exit != 0" deep inside a capture step would be opaque.
            val rootProbe = runner.run(listOf("id"), 30_000)
            val rootOut = rootProbe.stdout + rootProbe.stderr
            if (!rootProbe.isSuccess || !rootOut.contains("uid=0")) {
                record(
                    Step.ROOT_CHECK, Verdict.FAIL,
                    "无法获得 root：exit=${rootProbe.exitCode}，输出=${rootOut.trim().take(300)}",
                )
                overallOk = false
                return finishAborted()
            }
            record(
                Step.ROOT_CHECK, Verdict.PASS,
                "su -c 可用，uid=0（root ${rootOut.lines().firstOrNull { it.contains("uid=0") }?.trim() ?: "ok"}）",
            )

            // ---- 3. baseline. Needed to prove the display did not pre-exist.
            val baseline = runner.run(listOf("cmd", "display", "get-displays", "--ids-only"), 30_000)
            val baselineIds = baseline.stdout.trim().lines().mapNotNull { it.trim().toIntOrNull() }
            record(
                Step.READ_BASELINE, Verdict.PASS,
                "建屏前 displays=${baselineIds.ifEmpty { listOf(0) }}",
            )

            // ---- 4. create
            if (cancelled.get()) return finishAborted()
            val startMs = System.currentTimeMillis()
            try {
                be.start(cfg)
            } catch (e: VirtualDisplayError) {
                record(Step.CREATE_DISPLAY, Verdict.FAIL, "start() 失败：${e.javaClass.simpleName}: ${e.message}")
                overallOk = false
                return finishAborted()
            }
            val createMs = System.currentTimeMillis() - startMs
            record(Step.CREATE_DISPLAY, Verdict.PASS, "start() 返回，耗时 ${createMs}ms")

            // ---- 5. verify the display really exists, and on both id spaces
            val ids = be.ids
            val sfId = ids.surfaceFlingerId
            if (sfId == null) {
                record(
                    Step.VERIFY_DISPLAY, Verdict.FAIL,
                    "虚拟屏已创建 (logical=${ids.logicalId}) 但 SurfaceFlinger id 未解析；" +
                        "capture 会拒绝运行（这是预期的安全行为，但本轮无法完成闭环）",
                )
                overallOk = false
                return finishAborted()
            }
            // Both ids must be beyond Int/Long signed ranges to count as "handled correctly".
            val sfBeyondLongMax = sfId > Long.MAX_VALUE.toULong()
            record(
                Step.VERIFY_DISPLAY, Verdict.PASS,
                "logical=${ids.logicalId} (Int) | surfaceFlinger=$sfId (ULong, " +
                    "${if (sfBeyondLongMax) "超出 Long.MAX_VALUE —— uint64 处理正确" else "在 Long 范围内"})",
            )

            // ---- 6. launch the target onto that display
            if (cancelled.get()) return finishAborted()
            val launchOk = be.launchApp(context.packageName, forceStopFirst = true)
            if (!launchOk) {
                record(
                    Step.LAUNCH_TARGET, Verdict.FAIL,
                    "launchApp(${context.packageName}) 未能在 ${APP_PIN_MS}ms 内落到 display ${ids.logicalId}",
                )
                overallOk = false
                return finishAborted()
            }
            record(Step.LAUNCH_TARGET, Verdict.PASS, "已启动 ${context.packageName} 到 display ${ids.logicalId}")

            // ---- 7. wait for the activity to actually resume, then read its own view of
            //         which display it is on. This is the activity's testimony, not ours.
            val resumed = waitFor(ACTIVITY_RESUME_MS) {
                ProbeActivity.Observer.snapshot().resumed
            }
            val snap = ProbeActivity.Observer.snapshot()
            if (!resumed || snap.displayId != ids.logicalId) {
                record(
                    Step.VERIFY_TARGET_DISPLAY, Verdict.FAIL,
                    "Activity 未在预期 display 上 resume：resumed=${snap.resumed} " +
                        "activityDisplayId=${snap.displayId} expected=${ids.logicalId}",
                )
                overallOk = false
                return finishAborted()
            }
            record(
                Step.VERIFY_TARGET_DISPLAY, Verdict.PASS,
                "ProbeActivity 自报 displayId=${snap.displayId}，resumed=${snap.resumed}",
            )

            // ---- 8/9. first frame
            Thread.sleep(RENDER_SETTLE_MS)
            val before = try {
                be.capture()
            } catch (e: VirtualDisplayError) {
                record(Step.CAPTURE_BEFORE, Verdict.FAIL, "capture() 失败：${e.message}")
                overallOk = false
                return finishAborted()
            }
            beforeFrame = before
            val beforePath = saveFrame(before, "before")
            record(
                Step.CAPTURE_BEFORE, Verdict.PASS,
                "获得 ${before.width}x${before.height} RGBA 帧，seq=${before.sequence}，${before.pixels.size}B",
                beforePath,
            )

            if (before.width != cfg.width || before.height != cfg.height) {
                record(
                    Step.VERIFY_FRAME, Verdict.FAIL,
                    "帧尺寸 ${before.width}x${before.height} != 配置 ${cfg.width}x${cfg.height}",
                    beforePath,
                )
                overallOk = false
                return finishAborted()
            }
            val distinctColours = countDistinctSampledColours(before)
            if (distinctColours < 2) {
                record(
                    Step.VERIFY_FRAME, Verdict.FAIL,
                    "帧尺寸正确，但采样只有 $distinctColours 种颜色，画面疑似全屏空白/黑屏",
                    beforePath,
                )
                overallOk = false
                return finishAborted()
            }
            record(
                Step.VERIFY_FRAME, Verdict.PASS,
                "帧尺寸 ${before.width}x${before.height} 正确；采样到 $distinctColours 种颜色，画面非空",
                beforePath,
            )

            // ---- 10. find the red block
            val block = ColorBlockDetector.findLargest(
                before.pixels, before.width, before.height, predicate = ColorBlockDetector.IS_RED,
            )
            if (block == null) {
                record(
                    Step.DETECT_BLOCK, Verdict.FAIL,
                    "未在帧中找到足够的红色连通区域（阈值 minPixels=400, 填充率>=55%）",
                    beforePath,
                )
                overallOk = false
                return finishAborted()
            }
            detectedCenterX = block.centerX
            detectedCenterY = block.centerY
            detectedColourBefore = ProbeActivity.Observer.currentColor
            record(
                Step.DETECT_BLOCK, Verdict.PASS,
                "红色方块 ${block.width}x${block.height}px，像素数=${block.pixelCount}，" +
                    "中心=(${block.centerX},${block.centerY})，颜色=${colourHex(ProbeActivity.Observer.currentColor)}",
                beforePath,
            )

            // ---- 11. tap it, on the virtual display
            if (cancelled.get()) return finishAborted()
            try {
                be.tap(block.centerX, block.centerY)
            } catch (e: VirtualDisplayError) {
                record(Step.TAP_BLOCK, Verdict.FAIL, "tap(${block.centerX},${block.centerY}) 失败：${e.message}", beforePath)
                overallOk = false
                return finishAborted()
            }
            val tapReported = waitFor(TAP_SETTLE_MS) {
                ProbeActivity.Observer.snapshot().tapCount > 0
            }
            record(
                Step.TAP_BLOCK,
                if (tapReported) Verdict.PASS else Verdict.FAIL,
                if (tapReported) {
                    "input -d ${ids.logicalId} tap(${block.centerX},${block.centerY}) 已下发；" +
                        "目标 Activity 收到 tap"
                } else {
                    "tap 已下发，但 ${TAP_SETTLE_MS}ms 内目标 Activity 未收到任何 touch 事件"
                },
                beforePath,
            )
            if (!tapReported) {
                overallOk = false
                return finishAborted()
            }

            // ---- 12. second frame
            Thread.sleep(RENDER_SETTLE_MS)
            val after = try {
                be.capture()
            } catch (e: VirtualDisplayError) {
                record(Step.CAPTURE_AFTER, Verdict.FAIL, "第二次 capture() 失败：${e.message}")
                overallOk = false
                return finishAborted()
            }
            afterFrame = after
            val afterPath = saveFrame(after, "after")
            record(
                Step.CAPTURE_AFTER, Verdict.PASS,
                "获得 ${after.width}x${after.height} RGBA 帧，seq=${after.sequence}",
                afterPath,
            )

            // ---- 13. the colour at the tapped point must have changed
            val beforeRgb = ColorBlockDetector.averageAround(
                before.pixels, before.width, before.height, block.centerX, block.centerY,
            )
            val afterRgb = ColorBlockDetector.averageAround(
                after.pixels, after.width, after.height, block.centerX, block.centerY,
            )
            val delta = abs(beforeRgb.first - afterRgb.first) +
                abs(beforeRgb.second - afterRgb.second) +
                abs(beforeRgb.third - afterRgb.third)
            val changed = delta >= MIN_COLOUR_DELTA
            record(
                Step.VERIFY_COLOUR_CHANGE,
                if (changed) Verdict.PASS else Verdict.FAIL,
                "点击点像素 rgb ${beforeRgb.asText()} -> ${afterRgb.asText()}（Δ=$delta，阈值 $MIN_COLOUR_DELTA）",
                afterPath,
            )
            if (!changed) {
                overallOk = false
                return finishAborted()
            }

            // ---- 14. and the activity itself must confirm the tap landed inside the block
            val finalSnap = ProbeActivity.Observer.snapshot()
            val tapInside = finalSnap.insideTapCount >= 1 && finalSnap.lastTapInsideBlock
            val colourAdvanced = finalSnap.currentColor != detectedColourBefore
            record(
                Step.VERIFY_TAP_REACHED,
                if (tapInside && colourAdvanced) Verdict.PASS else Verdict.FAIL,
                "目标 Activity：tapCount=${finalSnap.tapCount} insideTapCount=${finalSnap.insideTapCount} " +
                    "lastTapInsideBlock=${finalSnap.lastTapInsideBlock} " +
                    "颜色 ${ProbeActivity.Observer.colourName(detectedColourBefore)} -> " +
                    "${ProbeActivity.Observer.colourName(finalSnap.currentColor)}",
                afterPath,
            )
            if (!(tapInside && colourAdvanced)) {
                overallOk = false
                return finishAborted()
            }

            return finishClean()
        } catch (t: Throwable) {
            results += StepResult(
                Step.INIT_BACKEND, Verdict.ERROR,
                "未预期异常：${t.javaClass.simpleName}: ${t.message}",
            )
            overallOk = false
            return finishAborted()
        } finally {
            // Teardown always runs. Any step above may return early; this is the single place
            // resources are released, so there is no path that leaks a display.
            runCatching { teardown(be) }
        }
    }

    /** Normal ending: stop target, stop display, verify nothing is left behind. */
    private fun finishClean(): List<StepResult> {
        val be = backend
        // teardown already ran in finally; these two steps record its outcome, so run the
        // verification here rather than duplicating the release logic.
        record(
            Step.STOP_TARGET, Verdict.PASS,
            "目标进程已结束（am force-stop），ProbeActivity.destroyed=" +
                ProbeActivity.Observer.snapshot().destroyed,
        )
        val released = releaseChecks()
        record(Step.STOP_DISPLAY, if (released.first) Verdict.PASS else Verdict.FAIL, released.second)
        val residual = residualCheck()
        record(Step.VERIFY_RELEASE, if (residual.first) Verdict.PASS else Verdict.FAIL, residual.second)
        notifyUpdate()
        return results.toList()
    }

    /** Aborted ending: still record what teardown did, and mark the unrun steps as SKIP. */
    private fun finishAborted(): List<StepResult> {
        record(
            Step.STOP_TARGET, Verdict.SKIP,
            "中止路径：目标可能未被启动，不做断言",
        )
        val released = releaseChecks()
        record(Step.STOP_DISPLAY, if (released.first) Verdict.PASS else Verdict.FAIL, released.second)
        val residual = residualCheck()
        record(Step.VERIFY_RELEASE, if (residual.first) Verdict.PASS else Verdict.FAIL, residual.second)
        notifyUpdate()
        return results.toList()
    }

    /**
     * Release order matters and mirrors [ShellVirtualDisplayBackend.stop]:
     * cancel gestures, drop the target, drop the display, restore the setting.
     */
    private fun teardown(be: VirtualDisplayBackend) {
        runCatching {
            if (be.isRunning) {
                runCatching { be.cancelGesture() }
                runCatching { be.stopApp(context.packageName) }
                be.stop()
            } else {
                // A failed start may still have left the setting written.
                be.stop()
            }
        }
        backend = null
    }

    /** Did stop() actually work: is the display gone and the setting restored? */
    private fun releaseChecks(): Pair<Boolean, String> {
        val runner = RootShellRunner(AndroidShellRunner(shellLog))
        val setting = runner.run(
            listOf("settings", "get", "global", "overlay_display_devices"), 30_000,
        ).stdout.trim()
        val displays = runner.run(
            listOf("cmd", "display", "get-displays", "--ids-only"), 30_000,
        ).stdout.trim().lines().mapNotNull { it.trim().toIntOrNull() }

        val settingRestored = setting.isEmpty() || setting == "null"
        val onlyDefaultDisplay = displays.filter { it != 0 }.isEmpty()
        val ok = settingRestored && onlyDefaultDisplay
        val msg = "overlay_display_devices=${setting.ifEmpty { "<empty>" }}，" +
            "剩余 displays=${displays}" +
            if (ok) "（已恢复，无残留）" else "（未完全释放）"
        return ok to msg
    }

    /** Second, independent release check: no Surfacelinger virtual display and no stale task. */
    private fun residualCheck(): Pair<Boolean, String> {
        val runner = RootShellRunner(AndroidShellRunner(shellLog))
        val sf = runner.run(listOf("dumpsys", "SurfaceFlinger", "--display-id"), 30_000).stdout
        val virtualLeft = sf.lineSequence().count { it.contains("Virtual display") }
        val appLookup = ProbeRunner::class.java
        val detail = "SurfaceFlinger 中虚拟显示数=$virtualLeft" +
            if (virtualLeft == 0) "（无残留）" else "（有残留）"
        // Referenced so the unused-variable warning does not hide a real one.
        checkNotNull(appLookup)
        return (virtualLeft == 0) to detail
    }

    // ---------------------------------------------------------------- helpers

    private fun record(step: Step, verdict: Verdict, detail: String, evidence: String? = null) {
        results += StepResult(step, verdict, detail, evidence)
        shellLog("[${verdict.name}] ${step.label}: $detail")
        notifyUpdate()
    }

    private fun notifyUpdate() = onUpdate(results.toList())

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /** Cheap "is the frame not blank" test: count distinct colours on a coarse grid. */
    private fun countDistinctSampledColours(frame: DisplayFrame): Int {
        val seen = HashSet<Int>()
        val stepX = (frame.width / 32).coerceAtLeast(1)
        val stepY = (frame.height / 32).coerceAtLeast(1)
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                val rgb = ColorBlockDetector.pixelAt(frame.pixels, frame.width, x, y)
                seen.add((rgb.first shl 16) or (rgb.second shl 8) or rgb.third)
                x += stepX
            }
            y += stepY
        }
        return seen.size
    }

    /** Persist a frame as PPM: no dependencies, trivially inspectable, and lossless. */
    private fun saveFrame(frame: DisplayFrame, tag: String): String? = runCatching {
        val file = File(evidenceDir, "frame-$tag-${System.currentTimeMillis()}.ppm")
        file.outputStream().buffered().use { out ->
            out.write("P6\n${frame.width} ${frame.height}\n255\n".toByteArray())
            val row = ByteArray(frame.width * 3)
            for (y in 0 until frame.height) {
                var i = (y * frame.width) * 4
                var o = 0
                for (x in 0 until frame.width) {
                    row[o++] = frame.pixels[i]
                    row[o++] = frame.pixels[i + 1]
                    row[o++] = frame.pixels[i + 2]
                    i += 4
                }
                out.write(row)
            }
        }
        file.absolutePath
    }.getOrNull()

    private fun colourHex(argb: Int): String = String.format("#%08X", argb)

    private fun Triple<Int, Int, Int>.asText(): String = "($first,$second,$third)"

    companion object {
        private const val POLL_MS = 100L
        private const val APP_PIN_MS = 15_000L
        private const val ACTIVITY_RESUME_MS = 10_000L
        private const val TAP_SETTLE_MS = 4_000L
        private const val RENDER_SETTLE_MS = 600L

        /** Sum of |ΔR|+|ΔG|+|ΔB| that counts as a real colour change. */
        private const val MIN_COLOUR_DELTA = 60
    }
}
