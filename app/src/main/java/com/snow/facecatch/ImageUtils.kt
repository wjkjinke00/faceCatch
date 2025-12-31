package com.snow.facecatch

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

object ImageUtils {
    
    private const val TAG = "ImageUtils"
    
    /**
     * 从ImageProxy中裁剪出指定区域的图像
     */
    fun cropImage(image: ImageProxy, cropRect: RectF): Bitmap? {
        return try {
            // 获取图像的Bitmap表示
            val bitmap = image.toBitmap()
            
            // 计算实际裁剪区域（需要将归一化坐标转换为像素坐标）
            val cropLeft = (cropRect.left * bitmap.width).toInt().coerceIn(0, bitmap.width)
            val cropTop = (cropRect.top * bitmap.height).toInt().coerceIn(0, bitmap.height)
            val cropRight = (cropRect.right * bitmap.width).toInt().coerceIn(cropLeft, bitmap.width)
            val cropBottom = (cropRect.bottom * bitmap.height).toInt().coerceIn(cropTop, bitmap.height)
            
            // 确保裁剪区域在图像范围内
            val actualCropLeft = max(0, cropLeft)
            val actualCropTop = max(0, cropTop)
            val actualCropRight = min(bitmap.width, cropRight)
            val actualCropBottom = min(bitmap.height, cropBottom)
            
            // 检查裁剪区域是否有效
            if (actualCropRight <= actualCropLeft || actualCropBottom <= actualCropTop) {
                Log.e(TAG, "无效的裁剪区域: $actualCropLeft, $actualCropTop, $actualCropRight, $actualCropBottom")
                return null
            }
            
            // 裁剪图像
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                actualCropLeft,
                actualCropTop,
                actualCropRight - actualCropLeft,
                actualCropBottom - actualCropTop
            )
            
            bitmap.recycle()
            croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "图像裁剪失败", e)
            null
        }
    }
    
    /**
     * 调整图像大小以优化存储
     */
    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }
        
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)
        
        val matrix = Matrix().apply {
            postScale(scale, scale)
        }
        
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
    }
}