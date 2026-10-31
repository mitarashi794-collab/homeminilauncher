package com.example.minilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 画面右端に縦に並ぶ A〜Z と # のスライダー。
 * 指でなぞると、その位置の文字のインデックスを onSelect で通知する。
 */
class AlphabetSlider(context: Context) : View(context) {

    companion object {
        val LETTERS: List<String> = ('A'..'Z').map { it.toString() } + "#"
    }

    var onSelect: ((Int) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private var active = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    private val maxTextPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
    )

    // 右端のジェスチャーナビ（戻るスワイプ）と競合しないよう、スライダー中央部を除外領域にする
    // （システム側の上限は縦200dpまで）
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= 29) {
            val h = bottom - top
            val rectH = minOf(h, (200 * resources.displayMetrics.density).toInt())
            val t = (h - rectH) / 2
            systemGestureExclusionRects = listOf(Rect(0, t, right - left, t + rectH))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val step = height.toFloat() / LETTERS.size
        paint.textSize = minOf(step * 0.7f, maxTextPx)
        val centerX = width / 2f
        for ((i, letter) in LETTERS.withIndex()) {
            paint.color = if (i == active) Color.rgb(255, 193, 7) else Color.WHITE
            paint.isFakeBoldText = i == active
            val centerY = step * i + step / 2f
            val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letter, centerX, baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = (event.y / height * LETTERS.size).toInt()
                    .coerceIn(0, LETTERS.size - 1)
                if (index != active) {
                    active = index
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelect?.invoke(index)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
                onRelease?.invoke()
            }
        }
        return true
    }
}
