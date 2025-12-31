package com.snow.facecatch

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectionAnalyzer(
    // 返回人脸列表，图像缓冲区宽高，以及图像旋转角度
    private val onFaceDetected: (List<com.google.mlkit.vision.face.Face>, Int, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {
    
    companion object {
        private const val TAG = "FaceDetectionAnalyzer"
    }
    
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.10f) // 设置最小人脸检测尺寸
        .enableTracking() // 启用跟踪以提高性能
        .build()
    
    private val detector = FaceDetection.getClient(options)
    
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            
            detector.process(image)
                .addOnSuccessListener { faces ->
                    // 这里的 imageProxy.width/height 是图像缓冲区的原始宽高（未旋转）
                    onFaceDetected(faces, imageProxy.width, imageProxy.height, rotationDegrees)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "人脸检测失败: ${e.message}", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}