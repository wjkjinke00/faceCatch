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
    
    fun setFaceRect(rect: RectF?) {
        faceRect = rect
        postInvalidate() // 使用postInvalidate以提高性能
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        faceRect?.let { rect ->
            canvas.drawRect(rect, paint)
        }
    }
}