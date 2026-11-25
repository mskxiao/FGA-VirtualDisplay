package io.github.fate_grand_automata.vdisplay

/**
 * Failures the virtual display backend can hit, kept as a sealed hierarchy so
 * callers are forced to think about each one instead of catching a bare Exception.
 *
 * The distinction that matters operationally: [Recoverable] means "retry or pick a
 * different config", [Fatal] means "stop, the device will not do this".
 */
sealed class VirtualDisplayError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The host OS refused the operation. Retrying is pointless. */
    class NotSupported(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /** Missing root, or root present but the command was denied. */
    class PermissionDenied(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /** Display creation was accepted but no display appeared, or the id could not be resolved. */
    class CreationFailed(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /** Capture failed, timed out, or returned a frame whose geometry does not match the config. */
    class CaptureFailed(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /** Input did not reach the target display. */
    class InputFailed(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /**
     * The target app could not be pinned to the virtual display, or drifted off it.
     *
     * This is a first-class error rather than a warning because FGA would otherwise
     * keep matching patterns against a blank or stale frame and report confusing
     * "not found" failures far from the real cause.
     */
    class TargetNotOnDisplay(message: String, cause: Throwable? = null) : VirtualDisplayError(message, cause)

    /** The backend was used before start(), or after stop(). */
    class NotRunning(message: String) : VirtualDisplayError(message)
}
