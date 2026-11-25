package io.github.fate_grand_automata.vdisplay

/**
 * The whole platform surface the FGA adapters need, in one interface.
 *
 * Everything above this line (FGA's ScreenshotService / GestureService adapters)
 * is platform-neutral. Everything below it is replaceable: stage 1 ships a
 * shell-command backend, and the native MediaProjection-style backend described in
 * the audit report can be dropped in without touching FGA.
 *
 * Deliberately synchronous and blocking. FGA's automation core is a blocking loop
 * on a worker thread (EntryPoint.script runs on its own thread, and
 * AccessibilityGestures runs each gesture via runBlocking), so a backend that
 * returns before the gesture completes would change semantics under the scripts.
 */
interface VirtualDisplayBackend : AutoCloseable {

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean

    /**
     * The ids of the live virtual display.
     * @throws VirtualDisplayError.NotRunning before [start] or after [stop]
     */
    val ids: DisplayIds

    /** The geometry this backend was started with. */
    val config: DisplayConfig

    /**
     * Create the virtual display and resolve its ids.
     *
     * Idempotent: calling it while already running must not create a second display.
     *
     * @throws VirtualDisplayError.NotSupported if the config is outside the verified envelope
     * @throws VirtualDisplayError.PermissionDenied if root/shell is unavailable
     * @throws VirtualDisplayError.CreationFailed if no display appeared
     */
    fun start(config: DisplayConfig = DisplayConfig.STAGE1)

    /**
     * Acquire the most recent frame from the virtual display.
     *
     * Must never fall back to the physical display. If the SurfaceFlinger id is
     * unknown or capture fails, this throws rather than returning a plausible but
     * wrong image.
     */
    fun capture(): DisplayFrame

    /** Press and release at a point on the virtual display. */
    fun tap(x: Int, y: Int)

    /**
     * Press, move along a straight line, release - all on the virtual display.
     * @param durationMs total press-to-release time
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long)

    /**
     * Discard any in-flight gesture by releasing pressed pointers.
     *
     * Needed because `input -d ... swipe` is a fire-and-forget child process: if the
     * script is stopped mid-swipe, the child may still be moving its pointer. Callers
     * must invoke this on the teardown path (see STAGE1_ARCHITECTURE.md, teardown order).
     */
    fun cancelGesture()

    /**
     * Launch [packageName] on the virtual display, stopping it first if it is running.
     *
     * The force-stop is not optional: an already-running task on the physical display
     * gets reused by startActivity and the virtual display then shows a blank frame.
     * This behaviour is required, not a convenience.
     *
     * @return true if the app ended up on the virtual display
     */
    fun launchApp(packageName: String, forceStopFirst: Boolean = true): Boolean

    /** Where [packageName] is currently hosted, if it is running at all. */
    fun locateApp(packageName: String): AppLocation

    /** Stop the app and clear the frame buffer so later captures do not show it. */
    fun stopApp(packageName: String)

    /**
     * Destroy the virtual display and release every resource.
     * Must be idempotent and must be safe to call after a failed [start].
     */
    fun stop()
}

/** Result of asking the platform where an app lives. */
sealed class AppLocation {
    /** Not running at all. */
data object NotRunning : AppLocation()

    /** Running on the given display id. */
data class OnDisplay(val displayId: Int) : AppLocation()

    /** Running, but the platform would not tell us where. */
data object Unknown : AppLocation()
}
