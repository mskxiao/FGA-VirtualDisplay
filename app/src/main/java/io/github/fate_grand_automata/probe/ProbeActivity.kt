package io.github.fate_grand_automata.probe

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * The target under test: a deliberately simple screen whose whole state is knowable.
 *
 * It draws one solid colour block on a flat background. A tap inside the block flips it to
 * the next colour and records the tap. That gives the probe two independent assertions
 * that cannot both be satisfied by a coincidence:
 *
 *  - the frame contains a block of the expected colour at the expected place, and
 *  - after a tap delivered to this display, that block changed colour.
 *
 * A false "success" would require a tap to be both delivered to this activity *and* land
 * inside the block, which is exactly what stage 2A is trying to demonstrate.
 *
 * Deliberately not using Compose: this must build and behave identically on the `ci`
 * buildType without pulling the Compose toolchain into the probe, and a plain View makes
 * the drawn geometry trivially predictable.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var blockView: BlockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Report the display this instance actually landed on, for the report.
        currentDisplayId = display?.displayId ?: -1

        blockView = BlockView()
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(
                blockView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        currentDisplayId = display?.displayId ?: currentDisplayId
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private inner class BlockView : View(this@ProbeActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Centre the block and make it a fixed fraction of the shorter side, so the
            // geometry is predictable at 1280x720 and any other size.
            val side = (minOf(w, h) * BLOCK_FRACTION).coerceAtLeast(8f)
            val left = (w - side) / 2f
            val top = (h - side) / 2f

            paint.color = currentColor
            canvas.drawRect(left, top, left + side, top + side, paint)
            lastBlockLeft = left
            lastBlockTop = top
            lastBlockSide = side
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val side = blockView.lastBlockSide
            val left = blockView.lastBlockLeft
            val top = blockView.lastBlockTop
            val inside = side > 0f && x >= left && x <= left + side && y >= top && y <= top + side

            tapCount.incrementAndGet()
            if (inside) {
                insideTapCount.incrementAndGet()
                lastTapInsideBlock = true
                advanceColour()
                blockView.invalidate()
            } else {
                lastTapInsideBlock = false
            }
            lastTapX = x
            lastTapY = y
            return true
        }
        return true
    }

    private fun advanceColour() {
        val palette = PALETTE
        val idx = palette.indexOf(currentColor)
        currentColor = palette[(idx + 1) % palette.size]
    }

    /**
     * Snapshot of everything the test needs to assert, read from outside the activity.
     * Static because the probe drives the activity as a separate task on another display.
     */
    data class Status(
        val displayId: Int,
        val currentColor: Int,
        val tapCount: Int,
        val insideTapCount: Int,
        val lastTapInsideBlock: Boolean,
        val lastTapX: Float,
        val lastTapY: Float,
        val resumed: Boolean,
        val destroyed: Boolean,
        val expectedBlockSidePx: Float,
    )

    companion object {
        const val BACKGROUND = 0xFF101014.toInt()

        /**
         * The starting colour is red and the alternates are green and blue, so "the block
         * is red" is the initial precondition and "the block is no longer red" is the
         * post-tap assertion.
         */
        val PALETTE = intArrayOf(
            0xFFFF0000.toInt(), // red - start
            0xFF00C853.toInt(), // green
            0xFF2962FF.toInt(), // blue
        )

        /** Block is a quarter of the shorter side: 180px at 720p, comfortably tappable. */
        const val BLOCK_FRACTION = 0.25f

        @Volatile
        private var currentColor: Int = PALETTE[0]
        private val tapCount = AtomicInteger(0)
        private val insideTapCount = AtomicInteger(0)

        @Volatile
        private var lastTapInsideBlock: Boolean = false
        @Volatile
        private var lastTapX: Float = 0f
        @Volatile
        private var lastTapY: Float = 0f

        @Volatile
        var currentDisplayId: Int = -1
            private set

        @Volatile
        var resumed: Boolean = false
            private set

        @Volatile
        var destroyed: Boolean = false
            private set

        /** Reset between runs so repeated executions are independent. */
        fun resetState() {
            currentColor = PALETTE[0]
            tapCount.set(0)
            insideTapCount.set(0)
            lastTapInsideBlock = false
            lastTapX = 0f
            lastTapY = 0f
            currentDisplayId = -1
            resumed = false
            destroyed = false
        }

        fun status(): Status = Status(
            displayId = currentDisplayId,
            currentColor = currentColor,
            tapCount = tapCount.get(),
            insideTapCount = insideTapCount.get(),
            lastTapInsideBlock = lastTapInsideBlock,
            lastTapX = lastTapX,
            lastTapY = lastTapY,
            resumed = resumed,
            destroyed = destroyed,
            expectedBlockSidePx = 0f,
        )

        /** Human-readable colour name, for the on-screen report and the log. */
        fun colourName(argb: Int): String = when (argb) {
            PALETTE[0] -> "red"
            PALETTE[1] -> "green"
            PALETTE[2] -> "blue"
            else -> String.format("#%08X", argb)
        }
    }
}
