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
 * 写真の上に重ねて表示し、指のドラッグで印刷したい範囲(矩形)を選択させるためのView。
 * 選択範囲は「このViewの座標系」で保持する。ImageView上のBitmap座標への変換は
 * MainActivity側でImageViewのimageMatrixを使って行う。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 選択範囲(このView上の座標)
    private var selectionRect: RectF? = null

    private var startX = 0f
    private var startY = 0f

    // ドラッグ中に既存の矩形をリサイズしているかどうかを判定するための余白
    private val touchSlop = 40f

    private enum class DragMode { NONE, NEW, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val rectPaint = Paint().apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    /** 現在の選択範囲(このView座標)。未選択ならnull */
    fun getSelectionRect(): RectF? = selectionRect

    /** 新しい写真を読み込んだ際に選択範囲をリセットする */
    fun reset() {
        selectionRect = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val rect = selectionRect
                dragMode = when {
                    rect != null && isNearCorner(x, y, rect.left, rect.top) -> DragMode.RESIZE_TL
                    rect != null && isNearCorner(x, y, rect.right, rect.top) -> DragMode.RESIZE_TR
                    rect != null && isNearCorner(x, y, rect.left, rect.bottom) -> DragMode.RESIZE_BL
                    rect != null && isNearCorner(x, y, rect.right, rect.bottom) -> DragMode.RESIZE_BR
                    rect != null && rect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NEW
                }
                startX = x
                startY = y
                lastTouchX = x
                lastTouchY = y
                if (dragMode == DragMode.NEW) {
                    selectionRect = RectF(x, y, x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (dragMode) {
                    DragMode.NEW -> {
                        selectionRect = RectF(
                            min(startX, x), min(startY, y),
                            max(startX, x), max(startY, y)
                        )
                    }
                    DragMode.MOVE -> {
                        selectionRect?.let { r ->
                            val newRect = RectF(r)
                            newRect.offset(dx, dy)
                            // 画面外に出ないように制限
                            if (newRect.left >= 0 && newRect.right <= width &&
                                newRect.top >= 0 && newRect.bottom <= height
                            ) {
                                selectionRect = newRect
                            }
                        }
                    }
                    DragMode.RESIZE_TL -> selectionRect?.let { it.left = x; it.top = y }
                    DragMode.RESIZE_TR -> selectionRect?.let { it.right = x; it.top = y }
                    DragMode.RESIZE_BL -> selectionRect?.let { it.left = x; it.bottom = y }
                    DragMode.RESIZE_BR -> selectionRect?.let { it.right = x; it.bottom = y }
                    DragMode.NONE -> {}
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 矩形の向きを正規化(左上<右下になるように)
                selectionRect?.let { r ->
                    selectionRect = RectF(
                        min(r.left, r.right), min(r.top, r.bottom),
                        max(r.left, r.right), max(r.top, r.bottom)
                    )
                }
                dragMode = DragMode.NONE
            }
        }
        invalidate()
        return true
    }

    private fun isNearCorner(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        return abs(x - cx) < touchSlop && abs(y - cy) < touchSlop
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selectionRect ?: return

        // 選択範囲の外側を暗くして分かりやすくする
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)

        canvas.drawRect(rect, rectPaint)

        val handleRadius = 14f
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }
}
