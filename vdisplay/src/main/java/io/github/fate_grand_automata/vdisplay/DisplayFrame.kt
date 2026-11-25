package io.github.fate_grand_automata.vdisplay

/**
 * A raw frame captured from a display: tightly packed `w*h*4` RGBA_8888 bytes,
 * top-left origin, no row padding.
 *
 * RGBA_8888 rather than a Bitmap on purpose: this is exactly what
 * `/system/bin/screencap` emits and exactly what OpenCV wants for `COLOR_RGBA2BGR`,
 * so the whole pipeline avoids a PNG round-trip on every frame. FGA's
 * RootScreenshotService makes the same choice for the same reason.
 *
 * Ownership: [pixels] is a plain ByteArray that the producer hands over; the caller
 * owns it and may keep it. Implementations must not return a buffer they intend to
 * reuse behind the caller's back, because Pattern instances outlive single calls
 * when FGA caches a frame via `useSameSnapIn`.
 */
class DisplayFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    /**
     * Monotonic counter of successfully captured frames, used to detect a stale
     * frame after the target app is killed. Mirrors MaaFwApp's `g_frame_count`
     * idea without copying its code.
     */
    val sequence: Long,
    /** Wall-clock time the capture completed, in `System.nanoTime()` terms. */
    val capturedAtNanos: Long,
) {
    init {
        require(width > 0 && height > 0) { "bad frame size ${width}x$height" }
        require(pixels.size == width * height * 4) {
            "pixel buffer is ${pixels.size} bytes, expected ${width * height * 4} for " +
                "${width}x$height RGBA_8888"
        }
    }

    val size get() = PixelRect(0, 0, width, height)

    override fun toString(): String =
        "DisplayFrame(${width}x$height, seq=$sequence, ${pixels.size}B)"
}
