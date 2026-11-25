package io.github.fate_grand_automata.probe

import io.github.fate_grand_automata.vdisplay.ShellResult
import io.github.fate_grand_automata.vdisplay.ShellRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [ShellRunner] backed by [ProcessBuilder] inside the app process.
 *
 * This is where the app-process run genuinely differs from the root-shell probes, so
 * the differences are handled explicitly rather than assumed away:
 *
 *  1. **`su` is not a shell builtin here.** M0 ran commands in a shell that was already
 *     root. Inside the app, every privileged command has to go through
 *     `ProcessBuilder("su", "-c", ...)`, which triggers the su manager's per-invocation
 *     authorisation and has a real startup cost (~50-200ms).
 *
 *  2. **stderr and stdout must be drained concurrently.** `screencap` writes megabytes to
 *     stdout while stderr stays open. Reading them sequentially deadlocks as soon as the
 *     pipe buffer fills (this is the classic ProcessBuilder trap, and it would look like
 *     a hang rather than an error). Both streams are drained on their own threads.
 *
 *  3. **`waitFor` alone is not enough.** A child can outlive its timeout if a grandchild
 *     keeps the pipe open. The timeout path therefore destroys the process *and* closes
 *     the streams, then reports a timeout instead of blocking forever.
 *
 *  4. **A non-zero exit is not necessarily a failure of the command reachability.** `su`
 *     exits 1 both when the user denies the prompt and when the inner command fails. The
 *     distinction matters for diagnosis, so [ShellResult] carries both streams and callers
 *     decide.
 */
class AndroidShellRunner(
    private val logSink: (String) -> Unit = {},
) : ShellRunner {

    override fun run(argv: List<String>, timeoutMs: Long): ShellResult = execute(argv, timeoutMs).let {
        ShellResult(
            exitCode = it.exitCode,
            stdout = String(it.stdout, Charsets.UTF_8),
            stderr = String(it.stderr, Charsets.UTF_8),
        )
    }

    override fun runForBytes(argv: List<String>, timeoutMs: Long): ByteArray =
        execute(argv, timeoutMs).stdout

    override fun log(message: String) = logSink(message)

    private class RawResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

    private fun execute(argv: List<String>, timeoutMs: Long): RawResult {
        require(argv.isNotEmpty()) { "empty command" }
        log("exec: ${argv.joinToString(" ")} (timeout=${timeoutMs}ms)")

        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            // Most likely cause: the binary does not exist, or su is unavailable.
            return RawResult(
                exitCode = EXIT_LAUNCH_FAILED,
                stdout = ByteArray(0),
                stderr = "failed to start ${argv.first()}: ${t.javaClass.simpleName}: ${t.message}"
                    .toByteArray(),
            )
        }

        // Drain both streams concurrently. See class KDoc point 2.
        var out = ByteArray(0)
        var err = ByteArray(0)
        val outThread = thread(name = "shell-out", isDaemon = true) {
            out = runCatching { process.inputStream.use { it.readAllBytesCompat() } }.getOrDefault(ByteArray(0))
        }
        val errThread = thread(name = "shell-err", isDaemon = true) {
            err = runCatching { process.errorStream.use { it.readAllBytesCompat() } }.getOrDefault(ByteArray(0))
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return RawResult(EXIT_TIMEOUT, ByteArray(0), "interrupted".toByteArray())
        }

        if (!finished) {
            log("timeout after ${timeoutMs}ms, destroying process")
            process.destroyForcibly()
            // Give the drain threads a moment to collect what was already produced, so a
            // timeout still yields diagnostics instead of nothing.
            outThread.join(500)
            errThread.join(500)
            return RawResult(
                exitCode = EXIT_TIMEOUT,
                stdout = out,
                stderr = (String(err, Charsets.UTF_8) +
                    "\ntimeout after ${timeoutMs}ms; process destroyed").toByteArray(),
            )
        }

        outThread.join(2000)
        errThread.join(2000)
        val code = process.exitValue()
        log("exit=$code out=${out.size}B err=${err.size}B")
        return RawResult(code, out, err)
    }

    /**
     * `InputStream.readAllBytes` exists from API 33; this module's minSdk is 24, so a
     * plain loop is used instead of a Java 9+ API that would need desugaring.
     */
    private fun InputStream.readAllBytesCompat(): ByteArray {
        val buffer = ByteArrayOutputStream(INITIAL_BUFFER)
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    companion object {
        const val EXIT_LAUNCH_FAILED = -1
        const val EXIT_TIMEOUT = -2
        private const val INITIAL_BUFFER = 1 shl 16
    }
}
