package io.github.fate_grand_automata.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quoting test is the important one here: `su -c` takes a single string, so argv has to
 * be joined, and getting the escaping wrong turns a package name into shell syntax. This is
 * a security property, not a formatting detail.
 */
class RootShellRunnerTest {

    @Test
    fun leavesSafeArgumentsUnquoted() {
        assertEquals("settings", RootShellRunner.quote("settings"))
        assertEquals("1280x720/240", RootShellRunner.quote("1280x720/240"))
        assertEquals("com.bilibili.fatego", RootShellRunner.quote("com.bilibili.fatego"))
        assertEquals("a:b,c=d@e+f", RootShellRunner.quote("a:b,c=d@e+f"))
    }

    @Test
    fun quotesAnEmptyArgument() {
        assertEquals("''", RootShellRunner.quote(""))
    }

    @Test
    fun quotesAndEscapesASingleQuote() {
        // ' -> '\'' : close, escaped quote, reopen.
        assertEquals("'it'\\''s'", RootShellRunner.quote("it's"))
    }

    @Test
    fun neutralisesShellMetacharacters() {
        // None of these may be able to terminate the token or start a new command.
        val dangerous = listOf(
            "; rm -rf /",
            "$(reboot)",
            "`reboot`",
            "a | b",
            "a && b",
            "a\nb",
            "a > /sdcard/x",
            "a;b",
        )
        for (arg in dangerous) {
            val quoted = RootShellRunner.quote(arg)
            assertTrue(quoted.startsWith("'"), "should be single-quoted: $arg -> $quoted")
            assertTrue(quoted.endsWith("'"), "should be single-quoted: $arg -> $quoted")
            // The only way a single quote may appear inside is as the '\'' sequence.
            val inner = quoted.substring(1, quoted.length - 1)
            assertFalse(Regex("(?<!\\\\)'").containsMatchIn(inner), "unescaped quote in $quoted")
        }
    }

    @Test
    fun aMetacharacterHeavyArgumentStaysOneToken() {
        // Simulates what a POSIX shell would do: count unquoted ';' outside the wrapping
        // quotes. If quoting is correct there are none, because everything is inside '...'.
        val arg = ";rm -rf /data; echo pwned"
        val quoted = RootShellRunner.quote(arg)
        assertEquals("';rm -rf /data; echo pwned'", quoted)
        val outsideQuotes = quoted.substringBefore("'") + quoted.substringAfterLast("'")
        assertFalse(outsideQuotes.contains(";"), "semicolon escaped its token: $quoted")
    }
}

class ColorBlockDetectorTest {

    /** Build a synthetic RGBA_8888 frame with one filled rectangle. */
    private fun frameWithRect(
        width: Int,
        height: Int,
        bg: Int,
        rect: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): ByteArray {
        val px = ByteArray(width * height * 4)
        fun put(x: Int, y: Int, argb: Int) {
            val i = (y * width + x) * 4
            px[i] = ((argb shr 16) and 0xFF).toByte()
            px[i + 1] = ((argb shr 8) and 0xFF).toByte()
            px[i + 2] = (argb and 0xFF).toByte()
            px[i + 3] = 0xFF.toByte()
        }
        for (y in 0 until height) for (x in 0 until width) put(x, y, bg)
        for (y in top..bottom) for (x in left..right) put(x, y, rect)
        return px
    }

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00C853.toInt()
    private val blue = 0xFF2962FF.toInt()
    private val bg = 0xFF101014.toInt()

    @Test
    fun findsTheRedBlockAndItsCentre() {
        val w = 1280
        val h = 720
        // Block is 180x180 centred, matching ProbeActivity's 0.25 * min(w,h).
        val px = frameWithRect(w, h, bg, red, 550, 270, 729, 449)
        val block = ColorBlockDetector.findLargest(px, w, h, predicate = ColorBlockDetector.IS_RED)
        assertNotNull(block)
        assertEquals(550, block.left)
        assertEquals(270, block.top)
        assertEquals(639, block.centerX)
        assertEquals(359, block.centerY)
    }

    @Test
    fun doesNotFindGreenAsRed() {
        val w = 320
        val h = 240
        val px = frameWithRect(w, h, bg, green, 100, 80, 219, 159)
        assertNull(ColorBlockDetector.findLargest(px, w, h, predicate = ColorBlockDetector.IS_RED))
    }

    @Test
    fun greenAndBluePredicatesMatchThePaletteButNotRed() {
        assertTrue(ColorBlockDetector.IS_GREEN(0x00, 0xC8, 0x53))
        assertTrue(ColorBlockDetector.IS_BLUE(0x29, 0x62, 0xFF))
        assertFalse(ColorBlockDetector.IS_GREEN(0xFF, 0x00, 0x00))
        assertFalse(ColorBlockDetector.IS_BLUE(0xFF, 0x00, 0x00))
        assertFalse(ColorBlockDetector.IS_RED(0x00, 0xC8, 0x53))
    }

    @Test
    fun ignoresABlockThatIsTooSmallToBeTheProbes() {
        val w = 320
        val h = 240
        // 10x10 = 100 px, below the 400 px threshold.
        val px = frameWithRect(w, h, bg, red, 10, 10, 19, 19)
        assertNull(ColorBlockDetector.findLargest(px, w, h, predicate = ColorBlockDetector.IS_RED))
    }

    @Test
    fun rejectsScatteredMatchesBecauseFillRatioIsTooLow() {
        val w = 200
        val h = 200
        val px = ByteArray(w * h * 4)
        // Background everywhere, plus isolated red pixels in two far corners. Their bounding
        // box covers almost the whole frame while the count stays small-ish, so only the fill
        // ratio check can reject this. 500 isolated pixels keeps us above minPixels.
        for (i in px.indices step 4) {
            px[i] = 0x10
            px[i + 1] = 0x10
            px[i + 2] = 0x14
            px[i + 3] = 0xFF.toByte()
        }
        fun put(x: Int, y: Int) {
            val i = (y * w + x) * 4
            px[i] = 0xFF.toByte(); px[i + 1] = 0; px[i + 2] = 0
        }
        // Two dense clusters far apart: each 25x10 = 250 px, total 500, bbox ~ whole frame.
        for (y in 0 until 10) for (x in 0 until 25) put(x, y)
        for (y in 190 until 200) for (x in 175 until 200) put(x, y)

        assertNull(
            ColorBlockDetector.findLargest(px, w, h, predicate = ColorBlockDetector.IS_RED),
            "scattered clusters spanning the frame must be rejected by the fill-ratio check",
        )
    }

    @Test
    fun averageAroundReadsTheBlockColour() {
        val w = 320
        val h = 240
        val px = frameWithRect(w, h, bg, blue, 100, 80, 219, 159)
        val rgb = ColorBlockDetector.averageAround(px, w, h, 160, 120, radius = 4)
        assertEquals(0x29, rgb.first)
        assertEquals(0x62, rgb.second)
        assertEquals(0xFF, rgb.third)
    }

    @Test
    fun averageAroundClampsAtTheEdges() {
        val w = 16
        val h = 16
        val px = frameWithRect(w, h, bg, red, 0, 0, 15, 15)
        // radius 8 around (0,0) would go negative; must clamp rather than throw.
        val rgb = ColorBlockDetector.averageAround(px, w, h, 0, 0, radius = 8)
        assertEquals(0xFF, rgb.first)
    }

    @Test
    fun pixelAtReadsExactCoordinates() {
        val w = 32
        val h = 32
        val px = frameWithRect(w, h, bg, red, 8, 8, 23, 23)
        assertEquals(Triple(0xFF, 0x00, 0x00), ColorBlockDetector.pixelAt(px, w, 16, 16))
        assertEquals(Triple(0x10, 0x10, 0x14), ColorBlockDetector.pixelAt(px, w, 0, 0))
    }
}
