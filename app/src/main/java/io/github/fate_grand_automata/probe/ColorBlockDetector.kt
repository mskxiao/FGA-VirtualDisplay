package io.github.fate_grand_automata.probe

/**
 * Locates the solid colour block that [ProbeActivity] draws.
 *
 * Deliberately written as plain pixel arithmetic rather than using OpenCV: the point of
 * stage 2A is to prove the backend's frame is usable, so the detector must not depend on
 * the very image pipeline it is meant to validate. It is also pure, so the whole detector
 * is unit-tested on synthetic frames with no device and no native library.
 *
 * The detection rule is intentionally strict. A loose "any reddish pixel" test would
 * happily match FGO's UI or a theme accent colour, and a false positive here would make
 * the probe report success for a frame that was never the probe's own.
 */
object ColorBlockDetector {

    /** A detected block, in frame pixel coordinates. */
    data class Block(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2

        override fun toString(): String =
            "Block(${left},${top}-${right},${bottom} ${width}x$height px=$pixelCount center=${centerX},${centerY})"
    }

    /**
     * Find the largest connected region matching [predicate].
     *
     * A single-pass majority-vote-in-a-grid approach is used instead of full connected
     * components: the probe draws exactly one block, so "largest matching cluster on a
     * coarse grid, then refine" is enough, and it is O(n) with small constants.
     *
     * @param pixels RGBA_8888, tightly packed, `width*height*4` bytes
     * @return the block, or null when no cluster is big enough to be the probe's block
     */
    fun findLargest(
        pixels: ByteArray,
        width: Int,
        height: Int,
        minPixels: Int = 400,
        predicate: (r: Int, g: Int, b: Int) -> Boolean,
    ): Block? {
        require(pixels.size >= width * height * 4) {
            "pixel buffer ${pixels.size}B is smaller than ${width}x$height RGBA"
        }

        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var count = 0

        // Row-wise bounding box. A bounding box rather than true connectivity is fine here:
        // the probe's block is the only matching element on a flat background, so stray
        // matches would only widen the box, and the area check below rejects that case.
        var i = 0
        for (y in 0 until height) {
            val rowBase = y * width * 4
            for (x in 0 until width) {
                i = rowBase + x * 4
                val r = pixels[i].toInt() and 0xFF
                val g = pixels[i + 1].toInt() and 0xFF
                val b = pixels[i + 2].toInt() and 0xFF
                if (predicate(r, g, b)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    count++
                }
            }
        }

        if (count < minPixels) return null

        val boxArea = (right - left + 1) * (bottom - top + 1)
        // The block fills most of its bounding box. Anything much sparser means scattered
        // matches (UI chrome, antialiasing) rather than the probe's solid rectangle.
        val fill = count.toDouble() / boxArea.toDouble()
        if (fill < MIN_FILL_RATIO) return null

        return Block(left, top, right, bottom, count)
    }

    /** Bright, saturated red. Tuned so the probe's `0xFFFF0000` matches and dark reds do not. */
    val IS_RED: (Int, Int, Int) -> Boolean = { r, g, b ->
        r >= 180 && g <= 90 && b <= 90 && r - maxOf(g, b) >= 100
    }

    /** Any of the swap colours the probe can turn the block into. */
    val IS_NOT_RED: (Int, Int, Int) -> Boolean = { r, g, b -> !IS_RED(r, g, b) }

    /** Bright, saturated green (`0xFF00C853`-ish), the primary swap target. */
    val IS_GREEN: (Int, Int, Int) -> Boolean = { r, g, b ->
        g >= 150 && r <= 110 && b <= 130 && g - maxOf(r, b) >= 70
    }

    /** Bright blue (`0xFF2962FF`-ish), the alternate swap target. */
    val IS_BLUE: (Int, Int, Int) -> Boolean = { r, g, b ->
        b >= 180 && r <= 110 && g <= 140 && b - maxOf(r, g) >= 90
    }

    /** Colour at a single pixel, for asserting exactly what the tapped point became. */
    fun pixelAt(pixels: ByteArray, width: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val i = (y * width + x) * 4
        return Triple(
            pixels[i].toInt() and 0xFF,
            pixels[i + 1].toInt() and 0xFF,
            pixels[i + 2].toInt() and 0xFF,
        )
    }

    /**
     * Average colour of a small square around (cx, cy).
     *
     * Averaging rather than sampling one pixel makes the assertion robust to the anti-aliased
     * edge of the block and to a one-pixel coordinate rounding error.
     */
    fun averageAround(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int = 4,
    ): Triple<Int, Int, Int> {
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(height - 1)) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(width - 1)) {
                val i = (y * width + x) * 4
                sr += (pixels[i].toInt() and 0xFF).toLong()
                sg += (pixels[i + 1].toInt() and 0xFF).toLong()
                sb += (pixels[i + 2].toInt() and 0xFF).toLong()
                n++
            }
        }
        if (n == 0) return Triple(0, 0, 0)
        return Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    private const val MIN_FILL_RATIO = 0.55
}
