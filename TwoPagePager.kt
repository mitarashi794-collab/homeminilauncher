package com.example.minilauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 2ページ固定の横スワイプコンテナ（androidx不要）。
 * 1ページ目 = お気に入りホーム、2ページ目 = アプリ一覧。左スワイプで2ページ目へ。
 */
class TwoPagePager(context: Context) : FrameLayout(context) {

    var page = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null

    // 0f = 1ページ目、1f = 2ページ目
    private var progress = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyProgress()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    private fun applyProgress() {
        for (i in 0 until childCount) {
            getChildAt(i).translationX = (i - progress) * width
        }
    }

    fun goTo(target: Int, animate: Boolean = true) {
        stopAnimator()
        if (!animate || width == 0) {
            progress = target.toFloat()
            applyProgress()
            setPage(target)
            return
        }
        val a = ValueAnimator.ofFloat(progress, target.toFloat())
        a.duration = 220
        a.addUpdateListener {
            progress = it.animatedValue as Float
            applyProgress()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === a) {
                    animator = null
                    setPage(target)
                }
            }
        })
        animator = a
        a.start()
    }

    private fun setPage(target: Int) {
        if (page != target) {
            page = target
            onPageChanged?.invoke(target)
        }
    }

    private fun stopAnimator() {
        val a = animator
        animator = null
        a?.cancel()
    }

    private fun settleIfNeeded() {
        if (progress != 0f && progress != 1f) {
            goTo(if (progress > 0.5f) 1 else 0)
        }
    }

    private fun resetTracker() {
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
    }

    private fun maybeStartDrag(ev: MotionEvent) {
        if (dragging) return
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
            dragging = true
            lastX = ev.x
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopAnimator()
                downX = ev.x
                downY = ev.y
                dragging = false
                resetTracker()
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                maybeStartDrag(ev)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    recycleTracker()
                    settleIfNeeded()
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                maybeStartDrag(ev)
                if (dragging) {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    progress = (progress - dx / width).coerceIn(0f, 1f)
                    applyProgress()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val target = when {
                        vx < -1000f -> 1
                        vx > 1000f -> 0
                        progress > 0.5f -> 1
                        else -> 0
                    }
                    goTo(target)
                } else {
                    settleIfNeeded()
                }
                dragging = false
                recycleTracker()
            }
        }
        return true
    }
}
