package com.privatelock.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** A content-free custom 3x3 pattern control; its caller canonicalizes and then wipes the path. */
class PatternInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val selected = mutableListOf<Int>()
    private val visited = mutableSetOf<Int>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff30543e.toInt(); strokeWidth = dp(4f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff6d7d70.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff30543e.toInt(); style = Paint.Style.FILL }
    var onPatternComplete: ((List<Int>) -> Unit)? = null

    init { contentDescription = "3 by 3 pattern input"; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val gap = side / 4f
        if (selected.size > 1) {
            val path = Path()
            selected.forEachIndexed { index, dot ->
                val x = left + ((dot - 1) % 3 + 1) * gap
                val y = top + ((dot - 1) / 3 + 1) * gap
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }
        for (dot in 1..9) {
            val x = left + ((dot - 1) % 3 + 1) * gap
            val y = top + ((dot - 1) / 3 + 1) * gap
            val radius = dp(10f)
            if (dot in visited) canvas.drawCircle(x, y, radius, selectedPaint)
            else canvas.drawCircle(x, y, radius, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern()
                addHit(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.historySize) addHit(event.getHistoricalX(index), event.getHistoricalY(index))
                addHit(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                addHit(event.x, event.y)
                val completed = selected.toList()
                clearPattern()
                onPatternComplete?.invoke(completed)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { clearPattern(); return true }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun addHit(x: Float, y: Float) {
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val gap = side / 4f
        val hitRadius = min(dp(36f), gap * .46f)
        for (dot in 1..9) {
            val cx = left + ((dot - 1) % 3 + 1) * gap
            val cy = top + ((dot - 1) / 3 + 1) * gap
            if (dot !in visited && hypot((x - cx).toDouble(), (y - cy).toDouble()) <= hitRadius) {
                selected += dot
                visited += dot
                invalidate()
                return
            }
        }
    }

    private fun clearPattern() {
        selected.clear()
        visited.clear()
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
