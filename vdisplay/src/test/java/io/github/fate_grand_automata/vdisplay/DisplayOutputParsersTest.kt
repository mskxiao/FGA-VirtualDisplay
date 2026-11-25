package io.github.fate_grand_automata.vdisplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every sample string below is a **verbatim capture** from the target device
 * (Redmi K70 / Android 16 / API 36), not invented input. That is the point of this
 * test file: it freezes the parsing rules against real output so a later refactor
 * cannot quietly break them.
 */
class DisplayOutputParsersTest {

    // ------------------------------------------------------- SurfaceFlinger ids

    private val sfOutput = """
        Display 4630946738963277955 (HWC display 0): port=131 pnpId=QCM displayName=""
        Display 11529215048376362670 (Virtual display): displayName="叠加视图 #1"
    """.trimIndent()

    @Test
    fun parsesBothPhysicalAndVirtualSurfaceFlingerIds() {
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        assertEquals(2, ids.size)
        assertTrue(ids.containsKey(4630946738963277955L))
        assertTrue(ids.containsKey(11529215048376362670L))
    }

    @Test
    fun surfaceFlingerIdsExceedIntRangeAndMustBeParsedAsLong() {
        // 11529215048376362670 > Int.MAX_VALUE. Parsing as Int would truncate it and
        // capture would silently target the wrong display.
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        val virtual = ids.keys.first { it > Int.MAX_VALUE.toLong() }
        assertEquals(11529215048376362670L, virtual)
    }

    @Test
    fun keepsTheVirtualDisplayNameSoItCanBePreferredWhenResolving() {
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        val desc = ids.getValue(11529215048376362670L)
        assertTrue(desc.contains("Virtual"), "expected the SF description to mention Virtual, was: $desc")
    }

    @Test
    fun ignoresUnparseableLinesInsteadOfFailingTheWholeDump() {
        val messy = """
            some header line
            Display notanumber (Virtual display): displayName="x"

            Display 42 (Virtual display): displayName="y"
        """.trimIndent()
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(messy)
        assertEquals(1, ids.size)
        assertTrue(ids.containsKey(42L))
    }

    // ---------------------------------------------------------- logical displays

    @Test
    fun parsesLogicalDisplayTypeUniqueIdAndLayerStack() {
        val output = """
            Displays:
            Display id 0: DisplayInfo{"内置屏幕", displayId 0, displayGroupId 0, FLAG_SECURE, real 1440 x 3200, uniqueId "local:4630946738963277955", type INTERNAL, layerStack 0, canHostTasks true}
            Display id 24: DisplayInfo{"叠加视图 #1", displayId 24, displayGroupId 0, FLAG_PRESENTATION, real 1280 x 720, uniqueId "overlay:1", type OVERLAY, layerStack 24, canHostTasks false}
        """.trimIndent()
        val list = DisplayOutputParsers.parseLogicalDisplays(output)
        assertEquals(2, list.size)

        val virtual = list.first { it.displayId == 24 }
        assertEquals("OVERLAY", virtual.type)
        assertEquals("overlay:1", virtual.uniqueId)
        // This invariant was verified three times on device; id pairing relies on it.
        assertEquals(virtual.displayId, virtual.layerStack)
    }

    @Test
    fun parsesIdsOnlyOutput() {
        assertEquals(listOf(0, 22), DisplayOutputParsers.parseDisplayIdsOnly("0\n22\n"))
        assertEquals(listOf(0), DisplayOutputParsers.parseDisplayIdsOnly("0"))
        assertEquals(emptyList(), DisplayOutputParsers.parseDisplayIdsOnly(""))
    }

    // ------------------------------------------------------------- screencap raw

    /** Build a header the way `screencap` emits it, plus a correctly sized payload. */
    private fun rawScreencap(
        width: Int,
        height: Int,
        format: Int = 1,
        includeExtra: Boolean = true,
    ): ByteArray {
        val pixelBytes = width * height * 4
        val headerBytes = if (includeExtra) 16 else 12
        val out = ByteArray(headerBytes + pixelBytes)
        fun putInt(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            out[offset + 2] = ((v shr 16) and 0xFF).toByte()
            out[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        putInt(0, width)
        putInt(4, height)
        putInt(8, format)
        if (includeExtra) putInt(12, 1)
        return out
    }

    @Test
    fun parses16ByteHeaderUsedOnApi26Plus() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720))
        assertNotNull(h)
        assertEquals(1280, h.width)
        assertEquals(720, h.height)
        assertTrue(h.isRgba8888)
        assertEquals(16, h.headerBytes)
        assertEquals(1280 * 720 * 4, h.pixelBytes)
    }

    @Test
    fun parses12ByteHeaderWhenNoExtraIntIsPresent() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720, includeExtra = false))
        assertNotNull(h)
        assertEquals(12, h.headerBytes)
    }

    @Test
    fun returnsNullForABufferTooShortToHoldAHeader() {
        assertNull(DisplayOutputParsers.parseScreencapHeader(ByteArray(8)))
    }

    @Test
    fun rejectsANonRgba8888Format() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720, format = 5))
        assertNotNull(h)
        assertFalse(h.isRgba8888, "format 5 must not be reported as RGBA_8888")
    }

    @Test
    fun matchesThePayloadSizeActuallyMeasuredOnDevice() {
        // Verified: screencap -d <sfid> for a 1280x720 display gave exactly 3686416 bytes.
        val expected = 1280 * 720 * 4 + 16
        assertEquals(3686416, expected)
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720))
        assertNotNull(h)
        assertEquals(expected, h.headerBytes + h.pixelBytes)
    }

    // -------------------------------------------------------------- activities

    @Test
    fun groupsPackagesByDisplayId() {
        val output = """
            Display #22 (activities from top to bottom):

              * Task{add6294 #63985 type=standard A=1000:com.android.settings.root U=0}
                packageName=com.android.settings processName=com.android.settings
                topResumedActivity=ActivityRecord{100288001 u0 com.android.settings/.MiuiSettings t63985}
            Display #0 (activities from top to bottom):
              packageName=com.miui.home
              packageName=com.ai.assistance.operit
        """.trimIndent()
        val map = DisplayOutputParsers.parseActivitiesByDisplay(output)

        assertEquals(setOf("com.android.settings"), map[22])
        assertEquals(setOf("com.miui.home", "com.ai.assistance.operit"), map[0])
    }

    @Test
    fun reportsNoEntryForAnAppThatIsNotRunning() {
        val map = DisplayOutputParsers.parseActivitiesByDisplay(
            "Display #0 (activities from top to bottom):\n  packageName=com.miui.home\n"
        )
        val allPackages = map.values.flatten()
        assertFalse("com.bilibili.fatego" in allPackages)
    }
}
