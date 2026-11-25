package io.github.fate_grand_automata.probe

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.fate_grand_automata.vdisplay.DisplayConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device UI for the stage-2A probe: runs the loop, shows a per-step verdict, and
 * records results the owner can screenshot or export.
 *
 * Kept as a separate Activity from [ProbeActivity] on purpose:
 *
 *  - [ProbeActivity] is the *target* under test and must stay trivially predictable, so
 *    it draws only a colour block and holds no test logic;
 *  - this Activity is the *harness*: it must survive on the main display while the target
 *    is launched onto the virtual display, and it must not accidentally become the thing
 *    being screenshotted.
 *
 * Mixing the two would make "the frame contains a red block" true for the wrong reason.
 */
class ProbeHarnessActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var stepView: TextView
    private lateinit var runButton: Button
    private lateinit var releaseButton: Button

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "probe-runner").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile
    private var runner: ProbeRunner? = null

    private val startedAt = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(24, 24, 24, 12)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        stepView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(24, 0, 24, 24)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        runButton = Button(this).apply {
            text = "运行测试"
            setOnClickListener { startRun() }
        }
        releaseButton = Button(this).apply {
            text = "强制释放"
            enabled = false
            setOnClickListener { forceRelease() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 24)
            addView(runButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(releaseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(stepView)
            addView(buttons)
        }

        setContentView(ScrollView(this).apply { addView(column) })

        statusView.text = buildHeader()
    }

    override fun onDestroy() {
        runner?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildHeader(): String = buildString {
        appendLine("阶段 2A：App 进程内 VirtualDisplay 验证")
        appendLine("目标：${DisplayConfig.STAGE1.width}x${DisplayConfig.STAGE1.height}" +
            " @ ${DisplayConfig.STAGE1.densityDpi}dpi（16:9）")
        appendLine("进程：${packageName}")
        appendLine("说明：本测试会把 ProbeActivity 启动到虚拟屏并点击它。")
        appendLine("      请勿在运行期间操作主屏。")
        appendLine("      本后端是 OVERLAY 叠加屏，不是真正独立后台屏。")
        appendLine("═".repeat(40))
    }

    private fun startRun() {
        if (!running.compareAndSet(false, true)) return
        runButton.isEnabled = false
        releaseButton.isEnabled = true
        startedAt.setLength(0)
        startedAt.append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))

        stepView.text = "开始运行…\n"
        statusView.text = buildHeader() + "状态：运行中（开始于 ${startedAt}）\n"

        val r = ProbeRunner(
            context = this,
            shellLog = { line -> android.util.Log.d("VDProbe", line) },
            onUpdate = { results -> main.post { renderSteps(results) } },
        )
        runner = r

        worker.execute {
            val results = try {
                r.run()
            } catch (t: Throwable) {
                listOf(
                    ProbeRunner.StepResult(
                        ProbeRunner.Step.INIT_BACKEND,
                        ProbeRunner.Verdict.ERROR,
                        "运行器崩溃：${t.javaClass.simpleName}: ${t.message}",
                    ),
                )
            }
            val summary = summarise(results)
            main.post {
                renderSteps(results)
                statusView.text = buildHeader() + summary
                runButton.isEnabled = true
                releaseButton.isEnabled = false
                running.set(false)
                runner = null
            }
        }
    }

    private fun forceRelease() {
        runner?.cancel()
        // A second, independent path to release, for when the run hangs. Deliberately not
        // routed through the runner: if the runner is the thing that is stuck, its own
        // teardown cannot be relied on.
        worker.execute {
            val released = EmergencyRelease.releaseEverything(this)
            main.post {
                statusView.text = buildHeader() + "强制释放：${released.joinToString("; ")}\n"
                running.set(false)
                runButton.isEnabled = true
                releaseButton.isEnabled = false
            }
        }
    }

    private fun renderSteps(results: List<ProbeRunner.StepResult>) {
        val sb = StringBuilder()
        for (r in results) {
            val mark = when (r.verdict) {
                ProbeRunner.Verdict.PASS -> "[PASS]"
                ProbeRunner.Verdict.FAIL -> "[FAIL]"
                ProbeRunner.Verdict.SKIP -> "[SKIP]"
                ProbeRunner.Verdict.ERROR -> "[ERR ]"
            }
            sb.appendLine("$mark ${r.step.label}")
            sb.appendLine("       ${r.detail}")
            r.evidencePath?.let { sb.appendLine("       证据: $it") }
        }
        stepView.text = sb.toString()
    }

    private fun summarise(results: List<ProbeRunner.StepResult>): String {
        val passed = results.count { it.verdict == ProbeRunner.Verdict.PASS }
        val failed = results.count { it.verdict == ProbeRunner.Verdict.FAIL }
        val errors = results.count { it.verdict == ProbeRunner.Verdict.ERROR }
        val skipped = results.count { it.verdict == ProbeRunner.Verdict.SKIP }
        val ok = failed == 0 && errors == 0
        return buildString {
            appendLine("═".repeat(40))
            appendLine(if (ok) "结果：全部通过" else "结果：存在失败项")
            appendLine("通过 $passed / 失败 $failed / 异常 $errors / 跳过 $skipped")
            appendLine("证据目录：${runner?.evidenceDir?.absolutePath ?: "(已释放)"}")
        }
    }
}
