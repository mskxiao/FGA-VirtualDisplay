package io.github.fate_grand_automata.vdisplay

/**
 * A pixel-space rectangle on a display, in the display's own coordinate space
 * (top-left origin, no rotation applied).
 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left) { "right ($right) must be >= left ($left)" }
        require(bottom >= top) { "bottom ($bottom) must be >= top ($top)" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    override fun toString(): String = "PixelRect($left, $top - $right, $bottom)"
}

/**
 * The two identifiers the platform hands out for one and the same virtual display,
 * and which are NOT interchangeable.
 *
 * Verified on the target device (Android 16 / API 36):
 *
 * ```
 * $ cmd display get-displays --ids-only      -> 0 22
 * $ dumpsys SurfaceFlinger --display-id       -> Display 11529215048376362670 (Virtual display)
 *
 * $ screencap -d 22                          -> Failed. Display Id '22' is not valid.
 * $ screencap -d 11529215048376362670        -> OK
 * $ input -d 22 tap 640 300                  -> OK (changed the virtual display only)
 * ```
 *
 * `screencap` takes a **SurfaceFlinger** display id, while `input` and
 * `cmd activity start --display` take a **DisplayManager logical** id.
 *
 * ---------------------------------------------------------------------------
 * Why [surfaceFlingerId] is a [ULong] and not a Long
 * ---------------------------------------------------------------------------
 * The SurfaceFlinger id is a `uint64_t` and routinely exceeds `Long.MAX_VALUE`:
 *
 * ```
 *   11529215048376362670  = 0xA0000000898FA6AE
 *   Long.MAX_VALUE        =  9223372036854775807
 *   as signed int64       = -6917529025333188946
 * ```
 *
 * Of the four ids measured on this device, two exceeded `Long.MAX_VALUE` while the
 * physical display's (`4630946738963277955`) did not, so a sample that happens to
 * hit a small id will not reveal the bug. Parsing into `Long` throws
 * `NumberFormatException` on the large ones - which is how this was found: the first
 * version of this class used Long and failed to compile its own test literal.
 *
 * Using `ULong` keeps the full 64-bit range and still renders the exact decimal
 * string `screencap -d` expects via `toString()`.
 */
data class DisplayIds(
    /** DisplayManager logical id. Used by `input -d` and `cmd activity start --display`. */
    val logicalId: Int,
    /**
     * SurfaceFlinger display id. Used by `screencap -d`. Unsigned 64-bit.
     *
     * Nullable because it is resolved by a separate query (`dumpsys SurfaceFlinger
     * --display-id`) that can fail or lag behind display creation. A null here must
     * make the frame source refuse to capture, rather than fall back to the default
     * display.
     */
    val surfaceFlingerId: ULong?,
) {
    init {
        require(logicalId > 0) {
            "logicalId must be a positive non-default display id, was $logicalId. " +
                "Display 0 is the physical screen and must never be the capture target."
        }
    }

    val hasSurfaceFlingerId: Boolean get() = surfaceFlingerId != null

    override fun toString(): String =
        "DisplayIds(logical=$logicalId, surfaceFlinger=${surfaceFlingerId ?: "unresolved"})"
}
