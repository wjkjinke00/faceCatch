package com.snow.facecatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    
    private val paint = Paint().apply {
        color = android.graphics.Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true // 启用抗锯齿
    }
    
    private var faceRect: RectF? = null
    private var smoothedRect: RectF? = null
    private val smoothingFactor = 0.15f // 平滑系数，越小越平滑但延迟越高
    
    fun setFaceRect(rect: RectF?) {
        if (rect == null) {
            faceRect = null
            smoothedRect = null
        } else {
            faceRect = rect
            if (smoothedRect == null) {
                smoothedRect = RectF(rect)
            } else {
                // 低通滤波算法 (LFP): smoothed = smoothed * (1 - factor) + target * factor
                smoothedRect!!.left = smoothedRect!!.left * (1 - smoothingFactor) + rect.left * smoothingFactor
                smoothedRect!!.top = smoothedRect!!.top * (1 - smoothingFactor) + rect.top * smoothingFactor
                smoothedRect!!.right = smoothedRect!!.right * (1 - smoothingFactor) + rect.right * smoothingFactor
                smoothedRect!!.bottom = smoothedRect!!.bottom * (1 - smoothingFactor) + rect.bottom * smoothingFactor
            }
        }
        postInvalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothedRect?.let { rect ->
            canvas.drawRect(rect, paint)
        }
    }
}