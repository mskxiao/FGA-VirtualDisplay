package io.github.fate_grand_automata.vdisplay

/**
 * Minimal process/logging surface, so the backend stays a plain JVM library.
 *
 * Without this, ShellVirtualDisplayBackend would import android.util.Log and
 * java.lang.ProcessBuilder directly, and the module could not be unit-tested in CI
 * without an emulator. Tests substitute a fake; the app substitutes the real thing.
 */
interface ShellRunner {
    /**
     * Run [argv] and return its result. Must be blocking.
     *
     * Implementations must NOT shell-quote or join argv: the command is passed as an
     * argument array so a package name or path can never be reinterpreted as shell syntax.
     */
    fun run(argv: List<String>, timeoutMs: Long): ShellResult

    /**
     * Run [argv] and return the raw stdout bytes, for binary payloads such as the
     * raw RGBA stream from `screencap`. Blocking.
     */
    fun runForBytes(argv: List<String>, timeoutMs: Long): ByteArray

    /** Human-readable log hook; the app wires this to Timber. */
    fun log(message: String)
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** First non-blank line of either stream, for compact error messages. */
    fun firstMeaningfulLine(): String =
        (stderr.lineSequence() + stdout.lineSequence())
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "(no output)"
}
