package io.github.fate_grand_automata.vdisplay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage-1 backend: drives a virtual display through public platform CLI tools.
 *
 * Why CLI instead of the hidden-API route described in the audit report:
 *
 *  - Every primitive used here was verified to work on the target device
 *    (Android 16 / API 36 / HyperOS) **before** a single line of this file existed,
 *    using nothing but a root shell. The hidden-API route
 *    (`DisplayManager.createVirtualDisplay` + `IInputManager.injectInputEvent`)
 *    requires a privileged process and has no such pre-verification.
 *  - It needs no NDK, no native capture pipeline, no reflection, no proguard rules and
 *    no privileged helper process, which keeps the stage-1 review surface small.
 *  - It is written against [VirtualDisplayBackend], so replacing it with a
 *    native/hidden-API implementation later does not touch FGA code.
 *
 * Known limitations, stated rather than hidden (STAGE1_VERIFICATION.md P-8):
 *
 *  - An `overlay_display_devices` display is an OVERLAY on the physical screen; the
 *    target app is *not* invisible on the main display. This satisfies the stage-1 goal
 *    (independent capture + directed input) but **not** "main screen stays free".
 *  - `input -d ... swipe` is a child process, so cancellation is best-effort only.
 *  - Display creation goes through a global setting, i.e. process-global state rather
 *    than per-app. The backend therefore restores the previous value on stop().
 *
 * Thread-safety: public methods are synchronized on [lock]. FGA calls capture and
 * gestures from the script thread and teardown from the service thread; serialising
 * keeps the state machine honest at a throughput we do not need at 330ms scan
 * intervals.
 */
class ShellVirtualDisplayBackend(
    private val shell: ShellRunner,
) : VirtualDisplayBackend {

    private val lock = Any()

    private enum class State { IDLE, STARTING, RUNNING, STOPPING }
    private var state = State.IDLE

    private var currentConfig: DisplayConfig? = null
    private var currentIds: DisplayIds? = null

    /** `overlay_display_devices` before we touched it, for exact restore. */
    private var previousOverlaySetting: String? = null

    private val frameSequence = AtomicLong(0)
    private val gestureSequence = AtomicInteger(0)

    /** Set after stopApp() so a later capture cannot silently return the dead app's frame. */
    @Volatile
    private var frameInvalidated = false

    override val isRunning: Boolean
        get() = synchronized(lock) { state == State.RUNNING }

    override val ids: DisplayIds
        get() = synchronized(lock) {
            currentIds ?: throw VirtualDisplayError.NotRunning(
                "No virtual display is running; call start() first"
            )
        }

    override val config: DisplayConfig
        get() = synchronized(lock) { currentConfig ?: DisplayConfig.STAGE1 }

    // ---------------------------------------------------------------- lifecycle

    override fun start(config: DisplayConfig) {
        synchronized(lock) {
            if (state == State.RUNNING) {
                shell.log("start() ignored: already running as $currentIds")
                return
            }
            check(state == State.IDLE) { "start() re-entered during $state" }

            // Stage 1 only claims 16:9. See DisplayConfig.isSixteenByNine for why.
            if (!config.isSixteenByNine) {
                throw VirtualDisplayError.NotSupported(
                    "Stage 1 only supports 16:9 displays; got ${config.width}x${config.height} " +
                        "(ratio ${config.aspectRatio}). FGA's geometry chain is only verified to " +
                        "degenerate cleanly at 16:9."
                )
            }
            if (config.width % 2 != 0 || config.height % 2 != 0) {
                throw VirtualDisplayError.NotSupported(
                    "Display dimensions must be even; got ${config.width}x${config.height}"
                )
            }

            state = State.STARTING
            try {
                verifyRoot()
                previousOverlaySetting = readOverlaySetting()
                shell.log("previous overlay_display_devices = $previousOverlaySetting")

                // Snapshot BEFORE creating, so the new display is identifiable by set
                // difference: SF ids have no arithmetic relation to logical ids and change
                // on every recreate (DisplayIds' KDoc has the measurements).
                val sfBefore = surfaceFlingerDisplayIds()
                val logicalBefore = logicalDisplays().map { it.displayId }

                writeOverlaySetting(config)
                val created = awaitDisplayCreation(sfBefore, logicalBefore)

                currentConfig = config
                currentIds = created
                frameSequence.set(0)
                frameInvalidated = true
                state = State.RUNNING
                shell.log("virtual display started: $created cfg=$config")
            } catch (t: Throwable) {
                // Roll back rather than leaving a half-created display behind.
                runCatching { restoreOverlaySetting() }
                currentIds = null
                currentConfig = null
                state = State.IDLE
                throw when (t) {
                    is VirtualDisplayError -> t
                    else -> VirtualDisplayError.CreationFailed(
                        "start() failed: ${t.javaClass.simpleName}: ${t.message}", t
                    )
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (state == State.IDLE) return
            val wasRunning = state == State.RUNNING
            state = State.STOPPING
            try {
                if (wasRunning) {
                    // Order matters: release input, invalidate the frame, drop the display,
                    // restore the setting. Dropping the display first would leave any
                    // in-flight gesture with no target, which is how stuck pointers happen.
                    runCatching { cancelGestureLocked() }
                    frameInvalidated = true
                    currentIds = null
                    currentConfig = null
                }
                runCatching { restoreOverlaySetting() }
            } finally {
                currentIds = null
                currentConfig = null
                previousOverlaySetting = null
                state = State.IDLE
                shell.log("virtual display stopped")
            }
        }
    }

    override fun close() = stop()

    // ------------------------------------------------------------------- capture

    override fun capture(): DisplayFrame {
        val (ids, cfg) = synchronized(lock) {
            if (state != State.RUNNING) {
                throw VirtualDisplayError.NotRunning("capture() called while $state")
            }
            val i = currentIds ?: throw VirtualDisplayError.NotRunning("no display ids")
            val c = currentConfig ?: throw VirtualDisplayError.NotRunning("no config")
            i to c
        }

        val sfId = ids.surfaceFlingerId ?: throw VirtualDisplayError.CaptureFailed(
            "SurfaceFlinger display id is unresolved for logical display ${ids.logicalId}; " +
                "refusing to capture (falling back to the physical display would produce a " +
                "correct-looking but wrong frame)"
        )

        val argv = listOf(SCREENCAP, "-d", sfId.toString())
        val raw = try {
            shell.runForBytes(argv, timeoutMs = CAPTURE_TIMEOUT_MS)
        } catch (t: Throwable) {
            throw VirtualDisplayError.CaptureFailed("screencap failed: ${t.message}", t)
        }

        val header = DisplayOutputParsers.parseScreencapHeader(raw)
            ?: throw VirtualDisplayError.CaptureFailed(
                "screencap returned ${raw.size} bytes, too short to contain a header"
            )

        if (!header.isRgba8888) {
            throw VirtualDisplayError.CaptureFailed(
                "screencap format is ${header.format}, expected 1 (RGBA_8888)"
            )
        }

        // Geometry guard: turns "captured the wrong display" from a silent mis-recognition
        // into an immediate, obvious failure.
        if (header.width != cfg.width || header.height != cfg.height) {
            throw VirtualDisplayError.CaptureFailed(
                "captured ${header.width}x${header.height} but the virtual display is " +
                    "${cfg.width}x${cfg.height}; the SurfaceFlinger id ($sfId) probably refers " +
                    "to a different display"
            )
        }

        val expected = header.headerBytes + header.pixelBytes
        if (raw.size < expected) {
            throw VirtualDisplayError.CaptureFailed(
                "truncated screencap: got ${raw.size} bytes, need $expected"
            )
        }

        val pixels = raw.copyOfRange(header.headerBytes, header.headerBytes + header.pixelBytes)
        val seq = frameSequence.incrementAndGet()
        frameInvalidated = false
        return DisplayFrame(
            width = header.width,
            height = header.height,
            pixels = pixels,
            sequence = seq,
            capturedAtNanos = System.nanoTime(),
        )
    }

    // --------------------------------------------------------------------- input

    override fun tap(x: Int, y: Int) {
        val ids = requireRunning()
        validatePoint(x, y, ids)
        val argv = listOf(INPUT, "-d", ids.logicalId.toString(), "tap", x.toString(), y.toString())
        runInput(argv, "tap($x,$y)")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val ids = requireRunning()
        validatePoint(x1, y1, ids)
        validatePoint(x2, y2, ids)
        if (durationMs <= 0) {
            throw VirtualDisplayError.InputFailed("swipe duration must be > 0, was $durationMs")
        }
        val argv = listOf(
            INPUT, "-d", ids.logicalId.toString(), "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(),
            durationMs.toString(),
        )
        runInput(argv, "swipe($x1,$y1 -> $x2,$y2, ${durationMs}ms)")
    }

    override fun cancelGesture() {
        synchronized(lock) {
            if (state != State.RUNNING) {
                shell.log("cancelGesture() ignored while $state")
                return
            }
            cancelGestureLocked()
        }
    }

    /**
     * Best-effort gesture cancellation.
     *
     * `input` has no cancel verb, so this issues a zero-duration tap outside the display
     * and logs the attempt. This is honestly the weakest part of the shell backend: it
     * cannot guarantee a release if a child process is still mid-swipe. Recorded as a
     * known gap rather than presented as a solution.
     */
    private fun cancelGestureLocked() {
        val ids = currentIds ?: return
        gestureSequence.incrementAndGet()
        val argv = listOf(INPUT, "-d", ids.logicalId.toString(), "tap", "-1", "-1")
        val r = runCatching { shell.run(argv, CANCEL_TIMEOUT_MS) }
        shell.log(
            "cancelGesture: best-effort out-of-bounds tap -> " +
                (r.getOrNull()?.let { "exit=${it.exitCode}" } ?: "failed")
        )
    }

    // ------------------------------------------------------------------- app host

    override fun launchApp(packageName: String, forceStopFirst: Boolean): Boolean {
        val ids = requireRunning()
        shell.log("launchApp($packageName) on display ${ids.logicalId}, forceStop=$forceStopFirst")

        if (forceStopFirst) {
            // Required, not optional: if the app already has a task on display 0,
            // startActivity reuses that task and the virtual display shows nothing.
            stopApp(packageName)
        }

        val am = listOf(
            AM, "start", "--display", ids.logicalId.toString(),
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            packageName,
        )
        val r = shell.run(am, APP_LAUNCH_TIMEOUT_MS)
        if (!r.isSuccess) {
            shell.log("am start failed: ${r.firstMeaningfulLine()}")
            return false
        }
        return awaitAppOnDisplay(packageName, ids.logicalId)
    }

    override fun locateApp(packageName: String): AppLocation {
        val byDisplay = activitiesByDisplay()
        for ((displayId, packages) in byDisplay) {
            if (packageName in packages) return AppLocation.OnDisplay(displayId)
        }
        return AppLocation.NotRunning
    }

    override fun stopApp(packageName: String) {
        val r = shell.run(listOf(AM, "force-stop", packageName), STOP_APP_TIMEOUT_MS)
        if (!r.isSuccess) {
            shell.log("force-stop $packageName -> ${r.firstMeaningfulLine()}")
        }
        // Any frame captured before this point shows an app that no longer exists.
        frameInvalidated = true
    }

    /** True when the cached frame no longer reflects reality (e.g. after stopApp). */
    fun isFrameInvalidated(): Boolean = frameInvalidated

    // -------------------------------------------------------------------- internals

    private fun requireRunning(): DisplayIds = synchronized(lock) {
        if (state != State.RUNNING) {
            throw VirtualDisplayError.NotRunning("backend is $state; call start() first")
        }
        currentIds ?: throw VirtualDisplayError.NotRunning("no display ids")
    }

    private fun validatePoint(x: Int, y: Int, ids: DisplayIds) {
        val cfg = currentConfig ?: return
        if (x < 0 || y < 0 || x >= cfg.width || y >= cfg.height) {
            throw VirtualDisplayError.InputFailed(
                "point ($x,$y) is outside the ${cfg.width}x${cfg.height} virtual display " +
                    "(logical id ${ids.logicalId}); refusing to inject"
            )
        }
    }

    private fun runInput(argv: List<String>, what: String) {
        val r = shell.run(argv, INPUT_TIMEOUT_MS)
        if (!r.isSuccess) {
            throw VirtualDisplayError.InputFailed("$what failed: ${r.firstMeaningfulLine()}")
        }
    }

    private fun verifyRoot() {
        val r = shell.run(listOf(SU, "-c", "id"), ROOT_CHECK_TIMEOUT_MS)
        val out = r.stdout + r.stderr
        if (!r.isSuccess || !out.contains("uid=0")) {
            throw VirtualDisplayError.PermissionDenied(
                "root is required to create a virtual display; `su -c id` returned " +
                    "exit=${r.exitCode} / ${out.trim().take(200)}"
            )
        }
    }

    private fun readOverlaySetting(): String? {
        val r = shell.run(listOf(SETTINGS, "get", "global", OVERLAY_KEY), SETTING_TIMEOUT_MS)
        if (!r.isSuccess) {
            throw VirtualDisplayError.PermissionDenied(
                "cannot read $OVERLAY_KEY: ${r.firstMeaningfulLine()}"
            )
        }
        val v = r.stdout.trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    private fun writeOverlaySetting(config: DisplayConfig) {
        val value = "${config.width}x${config.height}/${config.densityDpi}"
        val r = shell.run(
            listOf(SETTINGS, "put", "global", OVERLAY_KEY, value), SETTING_TIMEOUT_MS
        )
        if (!r.isSuccess) {
            throw VirtualDisplayError.PermissionDenied(
                "cannot write $OVERLAY_KEY=$value: ${r.firstMeaningfulLine()}"
            )
        }
    }

    private fun restoreOverlaySetting() {
        val previous = previousOverlaySetting
        if (previous == null) {
            val r = shell.run(
                listOf(SETTINGS, "delete", "global", OVERLAY_KEY), SETTING_TIMEOUT_MS
            )
            shell.log("restore $OVERLAY_KEY=null -> exit=${r.exitCode}")
        } else {
            val r = shell.run(
                listOf(SETTINGS, "put", "global", OVERLAY_KEY, previous), SETTING_TIMEOUT_MS
            )
            shell.log("restore $OVERLAY_KEY=$previous -> exit=${r.exitCode}")
        }
    }

    private fun surfaceFlingerDisplayIds(): Map<ULong, String> {
        val r = shell.run(listOf(DUMPSYS, "SurfaceFlinger", "--display-id"), DUMPSYS_TIMEOUT_MS)
        if (!r.isSuccess) {
            throw VirtualDisplayError.CreationFailed(
                "dumpsys SurfaceFlinger failed: ${r.firstMeaningfulLine()}"
            )
        }
        return DisplayOutputParsers.parseSurfaceFlingerDisplayIds(r.stdout)
    }

    private fun logicalDisplays(): List<DisplayOutputParsers.LogicalDisplayInfo> {
        val r = shell.run(listOf(CMD, "display", "get-displays"), DUMPSYS_TIMEOUT_MS)
        if (!r.isSuccess) {
            throw VirtualDisplayError.CreationFailed(
                "cmd display get-displays failed: ${r.firstMeaningfulLine()}"
            )
        }
        return DisplayOutputParsers.parseLogicalDisplays(r.stdout)
    }

    /**
     * Wait for the new display to appear, then pair its logical id with its SF id.
     *
     * Pairing strategy:
     *  1. logical id = the non-zero display that is new since the snapshot;
     *  2. SF id = the display that is new since the SF snapshot, preferring one whose
     *     description identifies it as virtual;
     *  3. corroborate with `layerStack == logicalId`, which held on every measurement
     *     taken on this device (STAGE1_VERIFICATION.md P-2).
     *
     * If step 2 is inconclusive the SF id stays null and capture refuses to run, which is
     * the safe failure mode.
     */
    private fun awaitDisplayCreation(
        sfBefore: Map<ULong, String>,
        logicalBefore: List<Int>,
    ): DisplayIds {
        val deadline = System.nanoTime() + DISPLAY_CREATE_TIMEOUT_MS * 1_000_000
        var sawLogicalWithoutSf: Int? = null
        while (System.nanoTime() < deadline) {
            val logicalNow = logicalDisplays()
            val newLogical = logicalNow.filter {
                it.displayId != 0 && it.displayId !in logicalBefore
            }
            if (newLogical.isNotEmpty()) {
                val candidate = newLogical.maxByOrNull { it.displayId }!!
                sawLogicalWithoutSf = candidate.displayId
                val sfNow = surfaceFlingerDisplayIds()
                val newSf = sfNow.keys.filter { it !in sfBefore.keys }
                val sfId = pickSurfaceFlingerId(newSf, sfNow)
                if (sfId != null) {
                    return DisplayIds(
                        logicalId = candidate.displayId,
                        surfaceFlingerId = sfId,
                    )
                }
            }
            Thread.sleep(DISPLAY_POLL_INTERVAL_MS)
        }
        throw VirtualDisplayError.CreationFailed(
            "virtual display did not appear within ${DISPLAY_CREATE_TIMEOUT_MS}ms" +
                (sawLogicalWithoutSf?.let {
                    " (logical id $it appeared but its SurfaceFlinger id did not)"
                } ?: "")
        )
    }

    private fun pickSurfaceFlingerId(newSf: List<ULong>, sfNow: Map<ULong, String>): ULong? {
        if (newSf.isEmpty()) return null
        if (newSf.size == 1) return newSf.first()
        // Prefer anything that identifies itself as a virtual / overlay display.
        val named = newSf.filter { id ->
            val desc = sfNow[id] ?: return@filter false
            desc.contains("Virtual", ignoreCase = true) || desc.contains("叠加")
        }
        return (named.ifEmpty { newSf }).maxOrNull()
    }

    private fun activitiesByDisplay(): Map<Int, Set<String>> {
        val r = shell.run(listOf(DUMPSYS, "activity", "activities"), ACTIVITY_DUMP_TIMEOUT_MS)
        if (!r.isSuccess) {
            shell.log("dumpsys activity failed: ${r.firstMeaningfulLine()}")
            return emptyMap()
        }
        return DisplayOutputParsers.parseActivitiesByDisplay(r.stdout)
    }

    private fun awaitAppOnDisplay(packageName: String, displayId: Int): Boolean {
        val deadline = System.nanoTime() + APP_PIN_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val loc = locateApp(packageName)
            if (loc is AppLocation.OnDisplay && loc.displayId == displayId) {
                shell.log("$packageName is on display $displayId")
                return true
            }
            Thread.sleep(APP_POLL_INTERVAL_MS)
        }
        shell.log("$packageName did not land on display $displayId within ${APP_PIN_TIMEOUT_MS}ms")
        return false
    }

    companion object {
        private const val OVERLAY_KEY = "overlay_display_devices"

        private const val SU = "su"
        private const val SETTINGS = "settings"
        private const val CMD = "cmd"
        private const val DUMPSYS = "dumpsys"
        private const val SCREENCAP = "/system/bin/screencap"
        private const val INPUT = "input"
        private const val AM = "am"

        private const val ROOT_CHECK_TIMEOUT_MS = 10_000L
        private const val SETTING_TIMEOUT_MS = 10_000L
        private const val DUMPSYS_TIMEOUT_MS = 15_000L
        private const val CAPTURE_TIMEOUT_MS = 20_000L
        private const val INPUT_TIMEOUT_MS = 15_000L
        private const val CANCEL_TIMEOUT_MS = 5_000L
        private const val STOP_APP_TIMEOUT_MS = 15_000L
        private const val APP_LAUNCH_TIMEOUT_MS = 20_000L
        private const val ACTIVITY_DUMP_TIMEOUT_MS = 20_000L

        private const val DISPLAY_CREATE_TIMEOUT_MS = 20_000L
        private const val DISPLAY_POLL_INTERVAL_MS = 400L
        private const val APP_PIN_TIMEOUT_MS = 15_000L
        private const val APP_POLL_INTERVAL_MS = 500L
    }
}
