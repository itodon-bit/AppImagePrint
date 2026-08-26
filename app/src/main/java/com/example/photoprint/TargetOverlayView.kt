package com.example.photoprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * カメラのプレビュー画面に重ねて表示する「狙いを定める枠」。
 * 四隅をドラッグしてサイズ変更、内側をドラッグして移動できる。
 * 枠が変化するたびに [onRectChanged] を呼び出し、呼び出し側(CameraActivity)で
 * カメラのズーム倍率を調整できるようにしている(枠を小さくする = もっと寄って撮りたい、とみなす)。
 */
class TargetOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var guideRect: RectF? = null
    private val touchSlop = 70f
    private val minSize = 150f

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** 枠のサイズ・位置が変化するたびに呼ばれるコールバック(View座標のRectFを渡す) */
    var onRectChanged: ((RectF) -> Unit)? = null

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val framePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (guideRect == null && w > 0 && h > 0) {
            guideRect = createDefaultRect(w, h)
        }
    }

    private fun createDefaultRect(w: Int, h: Int): RectF {
        val marginRatioHorizontal = 0.08f
        val marginRatioVertical = 0.30f
        return RectF(
            w * marginRatioHorizontal,
            h * marginRatioVertical,
            w * (1 - marginRatioHorizontal),
            h * (1 - marginRatioVertical)
        )
    }

    /** 現在のガイド枠(このView座標)。レイアウト確定前はnull */
    fun getGuideRect(): RectF? = guideRect

    /** 枠を標準サイズ・標準位置に戻す */
    fun resetToDefault() {
        if (width > 0 && height > 0) {
            val defaultRect = createDefaultRect(width, height)
            guideRect = defaultRect
            onRectChanged?.invoke(defaultRect)
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = guideRect ?: return true
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    isNear(x, y, rect.left, rect.top) -> DragMode.RESIZE_TL
                    isNear(x, y, rect.right, rect.top) -> DragMode.RESIZE_TR
                    isNear(x, y, rect.left, rect.bottom) -> DragMode.RESIZE_BL
                    isNear(x, y, rect.right, rect.bottom) -> DragMode.RESIZE_BR
                    rect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                lastTouchX = x
                lastTouchY = y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (dragMode) {
                    DragMode.MOVE -> {
                        val newRect = RectF(rect)
                        newRect.offset(dx, dy)
                        if (newRect.left >= 0 && newRect.right <= width &&
                            newRect.top >= 0 && newRect.bottom <= height
                        ) {
                            guideRect = newRect
                        }
                    }
                    DragMode.RESIZE_TL -> {
                        rect.left = max(0f, min(x, rect.right - minSize))
                        rect.top = max(0f, min(y, rect.bottom - minSize))
                    }
                    DragMode.RESIZE_TR -> {
                        rect.right = min(width.toFloat(), max(x, rect.left + minSize))
                        rect.top = max(0f, min(y, rect.bottom - minSize))
                    }
                    DragMode.RESIZE_BL -> {
                        rect.left = max(0f, min(x, rect.right - minSize))
                        rect.bottom = min(height.toFloat(), max(y, rect.top + minSize))
                    }
                    DragMode.RESIZE_BR -> {
                        rect.right = min(width.toFloat(), max(x, rect.left + minSize))
                        rect.bottom = min(height.toFloat(), max(y, rect.top + minSize))
                    }
                    DragMode.NONE -> {}
                }
                lastTouchX = x
                lastTouchY = y
                onRectChanged?.invoke(guideRect ?: rect)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
            }
        }
        return true
    }

    private fun isNear(x: Float, y: Float, cx: Float, cy: Float): Boolean =
        abs(x - cx) < touchSlop && abs(y - cy) < touchSlop

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = guideRect ?: return

        // 枠の外側を暗くする
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)

        // 枠の輪郭
        canvas.drawRect(rect, framePaint)

        // 四隅のコーナーマーク
        val cornerLength = min(rect.width(), rect.height()) * 0.12f
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, cornerPaint)

        // ドラッグ可能なことが分かりやすいよう、四隅に丸いつまみを表示
        val handleRadius = 18f
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }
}
