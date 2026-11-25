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
 * `cmd activity start --display` take a **DisplayManager logical** id. Using the
 * wrong one for `screencap` fails loudly (good), but a sloppy backend could also
 * capture the physical screen and then inject the tap into the virtual one - which
 * would silently produce a correct-looking frame with mis-targeted input.
 *
 * Keeping both ids in one object, populated from one resolution pass, makes that
 * class of bug structurally hard to write.
 */
data class DisplayIds(
    /** DisplayManager logical id. Used by `input -d` and `cmd activity start --display`. */
    val logicalId: Int,
    /**
     * SurfaceFlinger display id. Used by `screencap -d`.
     *
     * Nullable because it is resolved by a separate query (`dumpsys SurfaceFlinger
     * --display-id`) that can fail or lag behind display creation. A null here must
     * make the frame source refuse to capture, rather than fall back to the default
     * display.
     */
    val surfaceFlingerId: Long?,
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
