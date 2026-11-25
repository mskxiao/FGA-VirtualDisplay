package io.github.fate_grand_automata.vdisplay

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `parses both physical and virtual surfaceflinger ids`() {
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        assertEquals(2, ids.size)
        assertTrue(ids.containsKey(4630946738963277955L))
        assertTrue(ids.containsKey(11529215048376362670L))
    }

    @Test
    fun `surfaceflinger ids exceed int range and must be parsed as long`() {
        // 11529215048376362670 > Int.MAX_VALUE. Parsing as Int would truncate it and
        // capture would silently target the wrong display.
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        val virtual = ids.keys.first { it > Int.MAX_VALUE.toLong() }
        assertEquals(11529215048376362670L, virtual)
    }

    @Test
    fun `keeps the virtual display name so it can be preferred when resolving`() {
        val ids = DisplayOutputParsers.parseSurfaceFlingerDisplayIds(sfOutput)
        assertTrue(ids.getValue(11529215048376362670L).contains("Virtual"))
    }

    @Test
    fun `ignores unparseable lines instead of failing the whole dump`() {
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
    fun `parses logical display type uniqueId and layerStack`() {
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
        // This invariant was verified three times on device; capture pairing relies on it.
        assertEquals(virtual.displayId, virtual.layerStack)
    }

    @Test
    fun `parses ids-only output`() {
        assertEquals(listOf(0, 22), DisplayOutputParsers.parseDisplayIdsOnly("0\n22\n"))
        assertEquals(listOf(0), DisplayOutputParsers.parseDisplayIdsOnly("0"))
        assertEquals(emptyList(), DisplayOutputParsers.parseDisplayIdsOnly(""))
    }

    // ------------------------------------------------------------- screencap raw

    /** Build a header + fake payload the way `screencap` emits it. */
    private fun rawScreencap(width: Int, height: Int, format: Int = 1, includeExtra: Boolean = true): ByteArray {
        val size = width * height * 4
        val out = ByteArray(if (includeExtra) 16 else 12 + size)
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
    fun `parses 16-byte header used on api 26+`() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720))
        assertNotNull(h)
        assertEquals(1280, h.width)
        assertEquals(720, h.height)
        assertTrue(h.isRgba8888)
        assertEquals(16, h.headerBytes)
        assertEquals(1280 * 720 * 4, h.pixelBytes)
    }

    @Test
    fun `parses 12-byte header when no extra int is present`() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720, includeExtra = false))
        assertNotNull(h)
        assertEquals(12, h.headerBytes)
    }

    @Test
    fun `returns null for a buffer too short to hold a header`() {
        assertNull(DisplayOutputParsers.parseScreencapHeader(ByteArray(8)))
    }

    @Test
    fun `rejects a non-rgba8888 format`() {
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720, format = 5))
        assertNotNull(h)
        assertTrue(!h.isRgba8888)
    }

    @Test
    fun `matches the payload size actually measured on device`() {
        // Verified: screencap -d <sfid> for a 1280x720 display gave exactly 3686416 bytes.
        val expected = 1280 * 720 * 4 + 16
        assertEquals(3686416, expected)
        val h = DisplayOutputParsers.parseScreencapHeader(rawScreencap(1280, 720))
        assertNotNull(h)
        assertEquals(expected, h.headerBytes + h.pixelBytes)
    }

    // -------------------------------------------------------------- activities

    @Test
    fun `groups packages by display id`() {
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
    fun `reports no entry for an app that is not running`() {
        val map = DisplayOutputParsers.parseActivitiesByDisplay(
            "Display #0 (activities from top to bottom):\n  packageName=com.miui.home\n"
        )
        assertTrue(map.values.none { "com.bilibili.fatego" in it })
    }
}
