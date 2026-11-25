package io.github.fate_grand_automata.probe

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
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
 * that a coincidence cannot satisfy together:
 *
 *  - the frame contains a block of the expected colour at the expected place, and
 *  - after a tap delivered to this display, that block changed colour.
 *
 * The tap counters held here are the ground truth for "the tap reached *this* display",
 * independent of what any screenshot shows. A false pass would require the tap to be
 * delivered to this activity *and* land inside the block.
 *
 * Deliberately not Compose: the probe must build on the `ci` buildType without dragging
 * the Compose toolchain in, and a plain View makes the drawn geometry predictable.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var blockView: BlockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Observer.resetState()
        Observer.displayId = display?.displayId ?: -1

        blockView = BlockView()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Observer.BACKGROUND)
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
        Observer.resumed = true
        Observer.displayId = display?.displayId ?: Observer.displayId
    }

    override fun onPause() {
        Observer.resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        Observer.destroyed = true
        super.onDestroy()
    }

    private inner class BlockView : View(this@ProbeActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var blockLeft = 0f
            private set
        var blockTop = 0f
            private set
        var blockSide = 0f
            private set

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Centre the block, sized as a fraction of the shorter side, so the geometry is
            // predictable at 1280x720 and at any other size.
            val side = (minOf(w, h) * Observer.BLOCK_FRACTION).coerceAtLeast(Observer.MIN_BLOCK_PX)
            val left = (w - side) / 2f
            val top = (h - side) / 2f

            blockLeft = left
            blockTop = top
            blockSide = side

            paint.color = Observer.currentColor
            canvas.drawRect(left, top, left + side, top + side, paint)

            // A small dark dot marks the last tap, so the frame visibly changes even if a
            // future palette edit makes two colours hard to tell apart. Dark enough not to
            // be mistaken for the block by ColorBlockDetector.
            val tappedSide = Observer.lastTapSide
            if (tappedSide != null) {
                paint.color = 0xFF202020.toInt()
                canvas.drawCircle(
                    Observer.lastTapX, Observer.lastTapY, Observer.TAP_MARKER_RADIUS_PX, paint,
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val side = blockView.blockSide
            val left = blockView.blockLeft
            val top = blockView.blockTop
            val inside = side > 0f &&
                x >= left && x <= left + side &&
                y >= top && y <= top + side

            Observer.tapCount.incrementAndGet()
            Observer.lastTapX = x
            Observer.lastTapY = y
            Observer.lastTapSide = side
            if (inside) {
                Observer.insideTapCount.incrementAndGet()
                Observer.lastTapInsideBlock = true
                Observer.advanceColour()
            } else {
                Observer.lastTapInsideBlock = false
            }
            blockView.invalidate()
            return true
        }
        return true
    }

    /**
     * Probe state that the runner asserts on while the activity lives in its own task on
     * another display. Same process, different task - so plain volatile fields suffice and
     * no IPC is involved; they are volatile because the runner polls from a worker thread.
     */
    object Observer {
        const val BACKGROUND = 0xFF101014.toInt()

        /**
         * Red first, then green then blue: "the block is red" is the precondition and "the
         * block is no longer red" is the post-tap assertion.
         */
        @JvmField
        val PALETTE = intArrayOf(
            0xFFFF0000.toInt(), // red - start
            0xFF00C853.toInt(), // green
            0xFF2962FF.toInt(), // blue
        )

        /** Block is a quarter of the shorter side: 180px at 720p, comfortably tappable. */
        const val BLOCK_FRACTION = 0.25f
        const val MIN_BLOCK_PX = 8f
        const val TAP_MARKER_RADIUS_PX = 6f

        @Volatile
        @JvmField
        var currentColor: Int = PALETTE[0]

        @JvmField
        val tapCount = AtomicInteger(0)

        @JvmField
        val insideTapCount = AtomicInteger(0)

        @Volatile
        @JvmField
        var lastTapInsideBlock: Boolean = false

        @Volatile
        @JvmField
        var lastTapX: Float = 0f

        @Volatile
        @JvmField
        var lastTapY: Float = 0f

        /** Non-null once at least one tap has been received; draws the tap marker. */
        @Volatile
        @JvmField
        var lastTapSide: Float? = null

        @Volatile
        @JvmField
        var displayId: Int = -1

        @Volatile
        @JvmField
        var resumed: Boolean = false

        @Volatile
        @JvmField
        var destroyed: Boolean = false

        /** Reset between runs so repeated executions are independent. */
        fun resetState() {
            currentColor = PALETTE[0]
            tapCount.set(0)
            insideTapCount.set(0)
            lastTapInsideBlock = false
            lastTapX = 0f
            lastTapY = 0f
            lastTapSide = null
            displayId = -1
            resumed = false
            destroyed = false
        }

        fun advanceColour() {
            val idx = PALETTE.indexOf(currentColor)
            currentColor = PALETTE[(idx + 1) % PALETTE.size]
        }

        /** Immutable snapshot, so an assertion cannot straddle a concurrent change. */
        data class Snapshot(
            val displayId: Int,
            val currentColor: Int,
            val tapCount: Int,
            val insideTapCount: Int,
            val lastTapInsideBlock: Boolean,
            val lastTapX: Float,
            val lastTapY: Float,
            val resumed: Boolean,
            val destroyed: Boolean,
        )

        fun snapshot(): Snapshot = Snapshot(
            displayId = displayId,
            currentColor = currentColor,
            tapCount = tapCount.get(),
            insideTapCount = insideTapCount.get(),
            lastTapInsideBlock = lastTapInsideBlock,
            lastTapX = lastTapX,
            lastTapY = lastTapY,
            resumed = resumed,
            destroyed = destroyed,
        )

        fun colourName(argb: Int): String = when (argb) {
            PALETTE[0] -> "red"
            PALETTE[1] -> "green"
            PALETTE[2] -> "blue"
            else -> String.format("#%08X", argb)
        }
    }
}
