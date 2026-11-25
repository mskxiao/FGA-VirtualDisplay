package io.github.fate_grand_automata.vdisplay

/**
 * Geometry and density of a virtual display.
 *
 * Stage 1 locks the prototype to 16:9 with no cutout. This is deliberate, not
 * laziness: on a 1280x720 display the FGA geometry chain degenerates to the
 * identity, which removes the largest single source of coordinate error from
 * the prototype. See STAGE1_ARCHITECTURE.md section "Why 16:9 is locked".
 *
 *
 * (This file intentionally has no android.* imports so the module can be
 * compiled and unit-tested as a plain JVM library. See the module build file.)
 *
 * @property width  display width in pixels, in the display's natural orientation
 * @property height display height in pixels, in the display's natural orientation
 * @property densityDpi density in dpi, used to derive the app's density scale
 */
data class DisplayConfig(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val densityDpi: Int = DEFAULT_DENSITY_DPI,
) {
    init {
        require(width > 0) { "width must be > 0, was $width" }
        require(height > 0) { "height must be > 0, was $height" }
        require(densityDpi > 0) { "densityDpi must be > 0, was $densityDpi" }
    }

    /** Width / height as a Float, for aspect-ratio assertions. */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /**
     * True when this config is 16:9 (within a 1% tolerance for rounding).
     *
     * Stage 1 refuses to start the backend unless this is true, because the
     * upstream FGA geometry (FgoGameAreaManager / Scale / Transformer) was only
     * verified to degenerate cleanly at 16:9. Non-16:9 is not "unsupported", it
     * is "not yet verified" - the check exists to keep an unverified assumption
     * from silently producing mis-targeted taps.
     */
    val isSixteenByNine: Boolean
        get() = kotlin.math.abs(aspectRatio - 16f / 9f) < 0.01f

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DENSITY_DPI = 240

        /**
         * The config stage 1 ships with. Matches the 1280x720@240 default that
         * MaaFwApp uses, which is the only density/resolution pair we have any
         * external evidence for on this class of device.
         */
        val STAGE1 = DisplayConfig()
    }
}
