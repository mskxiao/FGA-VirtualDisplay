package io.github.fate_grand_automata.probe

import io.github.fate_grand_automata.vdisplay.ShellResult
import io.github.fate_grand_automata.vdisplay.ShellRunner

/**
 * Runs every command as root, by wrapping it in `su -c`.
 *
 * ---------------------------------------------------------------------------
 * Why this wrapper has to exist
 * ---------------------------------------------------------------------------
 * The M0 probes ran inside a shell that was *already* root, so `screencap`, `input`,
 * `settings` and `dumpsys` all had the privileges they needed without any prefix.
 * Inside the app process they do not:
 *
 * | command | needs | why the app lacks it |
 * |---|---|---|
 * | `settings put global` | `WRITE_SECURE_SETTINGS` | signature\|privileged, not grantable to a normal app |
 * | `screencap -d` | read another display's buffers | capturing a non-default display is refused |
 * | `input -d` | `INJECT_EVENTS` | signature\|privileged |
 * | `dumpsys SurfaceFlinger` | dump permission | reduced output or refusal |
 * | `am start --display` | start on another display | may be refused |
 *
 * So *every* command goes through `su`. That is the single biggest behavioural
 * difference between the M0 probe and the in-app run, and it is the reason this class
 * exists rather than reusing [AndroidShellRunner] directly.
 *
 * ---------------------------------------------------------------------------
 * Quoting
 * ---------------------------------------------------------------------------
 * `su -c` takes one string and hands it to a shell, so argv has to be joined into a
 * command line. Doing that naively would reintroduce exactly the shell-injection hole
 * that [ShellRunner]'s argv-based API was designed to prevent.
 *
 * Every argument is therefore single-quoted, and an embedded single quote is emitted as
 * `'\''` (close quote, escaped quote, reopen quote). Inside single quotes a POSIX shell
 * treats everything else literally - `$`, backticks, `;`, `|`, newlines included - so this
 * form is safe for arbitrary content. It is the standard `shlex.quote` algorithm, and it
 * is unit-tested in this module.
 */
class RootShellRunner(
    private val inner: ShellRunner,
    /** `su` invocations that may wait for the su manager prompt. */
    private val suBinary: String = "su",
) : ShellRunner {

    override fun run(argv: List<String>, timeoutMs: Long): ShellResult =
        inner.run(wrap(argv), timeoutMs + SU_PROMPT_ALLOWANCE_MS)

    override fun runForBytes(argv: List<String>, timeoutMs: Long): ByteArray =
        inner.runForBytes(wrap(argv), timeoutMs + SU_PROMPT_ALLOWANCE_MS)

    override fun log(message: String) = inner.log(message)

    /**
     * Build `[su, -c, <quoted command line>]`.
     *
     * `--` guards against an argument that looks like a `su` option being consumed by su
     * itself rather than passed to the inner command.
     */
    private fun wrap(argv: List<String>): List<String> {
        require(argv.isNotEmpty()) { "empty command" }
        val commandLine = argv.joinToString(" ") { quote(it) }
        return listOf(suBinary, "-c", "--", commandLine)
    }

    companion object {
        /** The su manager may show a dialog the first time; allow for a human. */
        const val SU_PROMPT_ALLOWANCE_MS = 25_000L

        /**
         * POSIX single-quote escaping. Exposed so it can be unit-tested independently of
         * any device: a regression here is a security regression, not a style issue.
         */
        fun quote(arg: String): String {
            if (arg.isEmpty()) return "''"
            // Only wrap when needed, to keep logs readable; safe characters are exactly
            // those the shell will not reinterpret and that cannot terminate the token.
            if (arg.all { it in SAFE_CHARS }) return arg
            return "'" + arg.replace("'", "'\\''") + "'"
        }

        private val SAFE_CHARS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '-', '.', '/', ':', ',', '=', '@', '+')).toSet()
    }
}
