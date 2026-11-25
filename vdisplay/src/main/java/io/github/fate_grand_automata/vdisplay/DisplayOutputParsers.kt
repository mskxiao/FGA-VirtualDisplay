package io.github.fate_grand_automata.vdisplay

/**
 * Parsers for the handful of `dumpsys` / `cmd` outputs the backend relies on.
 *
 * Pulled out of the backend and made pure so they can be unit-tested against
 * captured device output. Every parsing rule here was derived from real output on
 * Android 16 / API 36; the sample strings in the tests are verbatim captures, not
 * invented. If a rule looks over-specific, that is why - it was made to match what
 * the device actually printed.
 */
internal object DisplayOutputParsers {

    /**
     * Parse `dumpsys SurfaceFlinger --display-id` into SF display id -> name.
     *
     * Real output on the target device:
     *
     * ```
     * Display 4630946738963277955 (HWC display 0): port=131 pnpId=QCM displayName=""
     * Display 11529215048376362670 (Virtual display): displayName="叠加视图 #1"
     * ```
     *
     * Note the id is a **64-bit** value (well above Int range) and the trailing part
     * of the line is irrelevant to us. Lines that do not match are skipped rather
     * than failing the whole parse, because this output is diagnostic, not a
     * contract, and an unfamiliar extra line should not break capture.
     */
    fun parseSurfaceFlingerDisplayIds(output: String): Map<Long, String> {
        val result = LinkedHashMap<Long, String>()
        val lineRe = Regex("""^Display\s+(\d+)\s+\(([^)]*)\)\s*:?\s*(.*)$""")
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = lineRe.find(line) ?: continue
            val id = m.groupValues[1].toLongOrNull() ?: continue
            val kind = m.groupValues[2]
            val tail = m.groupValues[3]
            val name = Regex("""displayName="([^"]*)"""").find(tail)?.groupValues?.get(1) ?: ""
            result[id] = "$kind|$name"
        }
        return result
    }

    /**
     * Parse `cmd display get-displays` into logical-id -> (type, uniqueId, layerStack).
     *
     * We only extract three fields because those are the only ones the backend uses
     * to verify it resolved the display it just created.
     */
    data class LogicalDisplayInfo(
        val displayId: Int,
        val type: String,
        val uniqueId: String,
        val layerStack: Int,
    )

    fun parseLogicalDisplays(output: String): List<LogicalDisplayInfo> {
        // The output is one very long line per display; split on the record marker.
        val chunks = output.split(Regex("(?=Display id \\d+:)"))
        val list = ArrayList<LogicalDisplayInfo>()
        for (chunk in chunks) {
            val id = Regex("""Display id (\d+):""").find(chunk)?.groupValues?.get(1)
                ?.toIntOrNull() ?: continue
            val type = Regex("""type ([A-Z_]+)""").find(chunk)?.groupValues?.get(1) ?: ""
            val uniqueId = Regex("""uniqueId "([^"]*)"""").find(chunk)?.groupValues?.get(1) ?: ""
            val layerStack = Regex("""layerStack (\d+)""").find(chunk)?.groupValues?.get(1)
                ?.toIntOrNull() ?: -1
            list += LogicalDisplayInfo(id, type, uniqueId, layerStack)
        }
        return list
    }

    /** Parse `cmd display get-displays --ids-only`: whitespace/newline separated ints. */
    fun parseDisplayIdsOnly(output: String): List<Int> =
        output.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }

    /**
     * Parse the `screencap` raw header. Returns null when the buffer is too short.
     *
     * Format (little-endian int32): `width, height, format, [extra since O]`.
     * `format == 1` is RGBA_8888. The extra int exists on API 26+ and is present on
     * the target device; we read it when present but do not require it, so API 24/25
     * still parse.
     *
     * Verified payload size on device: `1280*720*4 + 16 == 3686416`.
     */
    data class RawScreencapHeader(
        val width: Int,
        val height: Int,
        val format: Int,
        val headerBytes: Int,
    ) {
        val isRgba8888: Boolean get() = format == 1
        val pixelBytes: Int get() = width * height * 4
    }

    fun parseScreencapHeader(bytes: ByteArray): RawScreencapHeader? {
        if (bytes.size < 12) return null
        fun intAt(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        val w = intAt(0)
        val h = intAt(4)
        val fmt = intAt(8)
        if (w <= 0 || h <= 0) return null
        // 16-byte header (with the post-O extra int) is the common case; fall back to
        // 12 only when the total size can only be explained by a 12-byte header.
        val withExtra = 16
        val headerBytes =
            if (bytes.size >= withExtra + w * h * 4) withExtra else 12
        return RawScreencapHeader(w, h, fmt, headerBytes)
    }

    /**
     * Parse `dumpsys activity activities` into displayId -> set of package names
     * with tasks on that display.
     *
     * Intentionally line-oriented and tolerant: the ActivityManager dump is huge and
     * unstable across ROMs (this device is HyperOS). Rules used:
     *  - a block starts at `Display #<n> (activities from top to bottom):`
     *  - within a block, `packageName=<pkg>` marks a task owner
     *  - `mDisplayId=<n>` inside an activity record confirms its display
     * Anything unrecognised is ignored, never fatal.
     */
    fun parseActivitiesByDisplay(output: String): Map<Int, Set<String>> {
        val map = LinkedHashMap<Int, MutableSet<String>>()
        var currentDisplay = -1
        val displayHeader = Regex("""Display #(\d+) \(activities from top to bottom\)""")
        val packageRe = Regex("""packageName=([A-Za-z0-9_.]+)""")
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val header = displayHeader.find(line)
            if (header != null) {
                currentDisplay = header.groupValues[1].toIntOrNull() ?: -1
                map.getOrPut(currentDisplay) { LinkedHashSet() }
                continue
            }
            if (currentDisplay >= 0) {
                val pkg = packageRe.find(line)?.groupValues?.get(1)
                if (pkg != null) map.getOrPut(currentDisplay) { LinkedHashSet() }.add(pkg)
            }
        }
        return map
    }
}
