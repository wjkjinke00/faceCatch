package com.snow.facecatch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import android.graphics.Color
import android.graphics.Paint
import android.view.ViewGroup
import android.util.Size
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.camera.core.ResolutionInfo

class MainActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var switchCameraButton: FloatingActionButton
    private lateinit var cameraTypeTextView: TextView
    private lateinit var faceStatusTextView: TextView
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService
    private var imageCapture: ImageCapture? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isBackCamera = true
    private var selectedCameraId: String? = null
    
    private val PREFS_NAME = "camera_settings"
    private val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
    
    private var isCapturing = false
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN = 5000L // 5秒冷却时间
    
    // 用于裁剪的状态
    private var latestFaceBoundingBox: RectF? = null
    private var latestImageWidth = 0
    private var latestImageHeight = 0
    private var latestRotationDegrees = 0
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        captureButton = findViewById(R.id.captureButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        cameraTypeTextView = findViewById(R.id.cameraTypeTextView)
        faceStatusTextView = findViewById(R.id.faceStatusTextView)
        
        // 强制使用 TextureView 模式以提高动态缩放稳定性并防止黑屏
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        
        // 从 SharedPreferences 读取已选择的摄像头 ID
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedCameraId = prefs.getString(KEY_SELECTED_CAMERA_ID, null)
        
        // 检查权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        // 初始化线程池
        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        
        // 设置抓取按钮点击事件
        captureButton.setOnClickListener {
            captureFaceImage()
        }
        
        // 设置摄像头切换按钮点击事件
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // 根据 ID 选择摄像头，如果没有 ID 或找不到则默认后置
                val availableCameras = cameraProvider.availableCameraInfos
                val targetCameraInfo = availableCameras.find { 
                    Camera2CameraInfo.from(it).cameraId == selectedCameraId 
                } ?: availableCameras.firstOrNull { 
                    it.lensFacing == CameraSelector.LENS_FACING_BACK 
                } ?: availableCameras.firstOrNull()

                if (targetCameraInfo != null) {
                    currentCameraSelector = targetCameraInfo.cameraSelector
                    isBackCamera = targetCameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
                    selectedCameraId = Camera2CameraInfo.from(targetCameraInfo).cameraId
                }

                updateCameraTypeIndicator()

                // 预览用例
                val preview = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // 图像分析用例（用于人脸检测）
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .setTargetRotation(windowManager.defaultDisplay.rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, FaceDetectionAnalyzer { faces, imageWidth, imageHeight, rotationDegrees ->
                            runOnUiThread {
                                if (faces.isNotEmpty()) {
                                    val face = faces[0]
                                    
                                    // 计算并显示人脸状态
                                    val eulerX = face.headEulerAngleX // 仰俯角 (正值向上)
                                    val eulerY = face.headEulerAngleY // 偏航角 (正值向右)
                                    
                                    val statusList = mutableListOf<String>()
                                    
                                    // 根据偏航角判断左右 (镜像处理交由 UI 显示逻辑)
                                    if (eulerY > 15) {
                                        statusList.add("右侧脸")
                                    } else if (eulerY < -15) {
                                        statusList.add("左侧脸")
                                    }
                                    
                                    // 根据仰俯角判断上下
                                    if (eulerX > 15) {
                                        statusList.add("抬头")
                                    } else if (eulerX < -15) {
                                        statusList.add("低头")
                                    }
                                    
                                    if (statusList.isEmpty()) {
                                        faceStatusTextView.text = "正脸"
                                        
                                        // 1. 确定视觉上的宽高（旋转后的预览布局宽高）
                                        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                                        val visualWidth = if (isRotated) imageHeight else imageWidth
                                        val visualHeight = if (isRotated) imageWidth else imageHeight
                                        
                                        // 自动抓拍核心逻辑：
                                        // A. 正脸判定 (角度 < 10)
                                        // B. 面积占比判定 (> 30%)
                                        val faceArea = face.boundingBox.width() * face.boundingBox.height()
                                        val totalArea = visualWidth * visualHeight
                                        val areaRatio = faceArea.toFloat() / totalArea.toFloat()
                                        println("faceArea:${faceArea},totalArea:${totalArea},areaRatio:${areaRatio}")

                                        if (kotlin.math.abs(eulerX) < 10 && kotlin.math.abs(eulerY) < 10 && areaRatio > 0.085) {
                                            val currentTime = System.currentTimeMillis()
                                            if (!isCapturing && (currentTime - lastCaptureTime > CAPTURE_COOLDOWN)) {
                                                // 存储当前的人脸框和图像尺寸，供拍照完成后裁剪使用
                                                latestFaceBoundingBox = RectF(face.boundingBox)
                                                latestImageWidth = visualWidth
                                                latestImageHeight = visualHeight
                                                latestRotationDegrees = rotationDegrees
                                                
                                                onFrontFaceDetected()
                                            }
                                        }
                                    } else {
                                        faceStatusTextView.text = statusList.joinToString(", ")
                                    }

                                    val boundingBox = RectF(face.boundingBox)
                                    
                                    val viewWidth = previewView.width
                                    val viewHeight = previewView.height
                                    
                                    // 1. 确定视觉上的宽高（旋转后的预览布局宽高）
                                    val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                                    val visualWidth = if (isRotated) imageHeight else imageWidth
                                    val visualHeight = if (isRotated) imageWidth else imageHeight
                                    
                                    // 2. 计算缩放比例 (FILL_CENTER / CENTER_CROP)
                                    val scaleX = viewWidth.toFloat() / visualWidth.toFloat()
                                    val scaleY = viewHeight.toFloat() / visualHeight.toFloat()
                                    val scale = kotlin.math.max(scaleX, scaleY)
                                    
                                    // 3. 计算偏移量以实现居中
                                    val offsetX = (viewWidth - visualWidth * scale) / 2f
                                    val offsetY = (viewHeight - visualHeight * scale) / 2f
                                    
                                    // 4. 映射坐标
                                    // ML Kit 的 boundingBox 已经是在 upright (纠正旋转后) 的坐标系中
                                    val mappedRect = RectF(
                                        boundingBox.left * scale + offsetX,
                                        boundingBox.top * scale + offsetY,
                                        boundingBox.right * scale + offsetX,
                                        boundingBox.bottom * scale + offsetY
                                    )
                                    
                                    // 5. 处理前置摄像头镜像
                                    if (!isBackCamera) {
                                        val left = viewWidth - mappedRect.right
                                        val right = viewWidth - mappedRect.left
                                        mappedRect.left = left
                                        mappedRect.right = right
                                    }
                                    
                                    // 6. 扩张范围以覆盖整个头部 (比界面显示更宽，确保不丢耳朵/额头)
                                    val width = mappedRect.width()
                                    val height = mappedRect.height()
                                    
                                    val finalRect = RectF(
                                        mappedRect.left, //- width * 0.10f,  // 左右各扩 10%
                                        mappedRect.top - height * 0.20f,  // 顶部向上扩 20% (头发)
                                        mappedRect.right, //+ width * 0.10f,
                                        mappedRect.bottom //+ height * 0.10f // 底部向下扩 10% (脖子)
                                    )
                                    
                                    overlayView.setFaceRect(finalRect)
                                } else {
                                    overlayView.setFaceRect(null)
                                    faceStatusTextView.text = "无检测"
                                }
                            }
                        })
                    }
                
                // 图像捕获用例
                imageCapture = ImageCapture.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                try {
                    // 解绑之前的用例
                    cameraProvider.unbindAll()
                    Log.d(TAG, "正在绑定用例至生命周期...")
                    
                    // 绑定预览、图像分析和图像捕获用例
                    cameraProvider.bindToLifecycle(
                        this, currentCameraSelector, preview, imageAnalyzer, imageCapture!!
                    )
                    Log.d(TAG, "用例绑定成功")

                    // 绑定后尝试获取分辨率并调整预览大小
                    Log.d(TAG, "尝试获取分辨率信息...")
                    val resInfo = preview.resolutionInfo
                    if (resInfo != null) {
                        adjustPreviewViewSize(resInfo.resolution, resInfo.rotationDegrees)
                    } else {
                        Log.w(TAG, "Preview resolutionInfo 为空，将在 post 中重试")
                        previewView.post {
                            preview.resolutionInfo?.let {
                                adjustPreviewViewSize(it.resolution, it.rotationDegrees)
                            }
                        }
                    }
                } catch (exc: Exception) {
                    Log.e(TAG, "相机绑定失败: ${exc.message}", exc)
                    Toast.makeText(
                        this, "相机绑定失败: ${exc.message}", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (exc: ExecutionException) {
                Log.e(TAG, "相机提供者获取失败: ${exc.message}", exc)
                Toast.makeText(
                    this, "相机提供者获取失败: ${exc.message}", 
                    Toast.LENGTH_SHORT
                ).show()
            } catch (exc: InterruptedException) {
                Log.e(TAG, "相机操作被中断: ${exc.message}", exc)
                Toast.makeText(
                    this, "相机操作被中断: ${exc.message}", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun onFrontFaceDetected() {
        Log.d(TAG, "检测到正脸，触发开发者回调")
        // 开发者可在此处处理自定义逻辑
        captureFaceImage()
    }
    
    private fun captureFaceImage() {
        val imageCapture = imageCapture ?: return
        if (isCapturing) return
        
        // 如果没有保存的人脸框（例如手动点击时可能没来得及分析），则暂时不抓取或抓取全图
        // 这里为了安全性，如果是自动触发，latestFaceBoundingBox 肯定有值
        
        isCapturing = true
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processAndSaveCapturedImage(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "照片捕获失败: ${exception.message}", exception)
                    isCapturing = false
                    lastCaptureTime = System.currentTimeMillis()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processAndSaveCapturedImage(image: ImageProxy) {
        try {
            val rotationDegrees = image.imageInfo.rotationDegrees
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // 1. 处理 Bitmap 旋转（确保处于正确朝向）
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val currentFaceBox = latestFaceBoundingBox
            val sourceWidth = latestImageWidth
            val sourceHeight = latestImageHeight

            if (currentFaceBox != null && sourceWidth > 0 && sourceHeight > 0) {
                // 2. 将分析时的坐标转换到照片的坐标系
                val scale = bitmap.width.toFloat() / sourceWidth.toFloat()
                
                val mappedRect = RectF(
                    currentFaceBox.left * scale,
                    currentFaceBox.top * scale,
                    currentFaceBox.right * scale,
                    currentFaceBox.bottom * scale
                )

                // 3. 应用相同的扩张逻辑（包含整个头部）
                val w = mappedRect.width()
                val h = mappedRect.height()
                
                val cropRect = RectF(
                    mappedRect.left - w * 0.20f,
                    mappedRect.top - h * 0.60f,
                    mappedRect.right + w * 0.20f,
                    mappedRect.bottom + h * 0.10f
                )

                // 4. 边界检查
                val finalRect = Rect(
                    kotlin.math.max(0, cropRect.left.toInt()),
                    kotlin.math.max(0, cropRect.top.toInt()),
                    kotlin.math.min(bitmap.width, cropRect.right.toInt()),
                    kotlin.math.min(bitmap.height, cropRect.bottom.toInt())
                )

                // 5. 执行裁剪
                if (finalRect.width() > 0 && finalRect.height() > 0) {
                    bitmap = Bitmap.createBitmap(bitmap, finalRect.left, finalRect.top, finalRect.width(), finalRect.height())
                }
            }
            
            // 6. 如果是前置摄像头，对裁剪后的结果进行镜像（此时裁剪范围已定，不会错位）
            if (!isBackCamera) {
                val matrix = Matrix()
                matrix.postScale(-1f, 1f)
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            // 7. 保存到本地
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "face_crop_${name}.jpg")
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            Log.d(TAG, "裁剪后的照片已保存: ${file.absolutePath}")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "人脸已抓取 (仅头部): ${file.name}", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理保存照片失败", e)
        } finally {
            image.close()
            isCapturing = false
            lastCaptureTime = System.currentTimeMillis()
        }
    }
    
    private fun switchCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val availableCameras = cameraProvider.availableCameraInfos
            
            val cameraItems = availableCameras.map { info ->
                val id = Camera2CameraInfo.from(info).cameraId
                val facing = when (info.lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "后置"
                    CameraSelector.LENS_FACING_FRONT -> "前置"
                    CameraSelector.LENS_FACING_EXTERNAL -> "外置"
                    else -> "未知"
                }
                "摄像头 $id ($facing)"
            }.toTypedArray()

            val currentIndex = availableCameras.indexOfFirst { 
                Camera2CameraInfo.from(it).cameraId == selectedCameraId 
            }.let { if (it == -1) 0 else it }

            AlertDialog.Builder(this)
                .setTitle("选择摄像头")
                .setSingleChoiceItems(cameraItems, currentIndex) { dialog, which ->
                    val selectedInfo = availableCameras[which]
                    selectedCameraId = Camera2CameraInfo.from(selectedInfo).cameraId
                    
                    // 保存选择到 SharedPreferences
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SELECTED_CAMERA_ID, selectedCameraId)
                        .apply()

                    dialog.dismiss()
                    startCamera()
                }
                .setNegativeButton("取消", null)
                .show()
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun updateCameraTypeIndicator() {
        runOnUiThread {
            val facingText = if (isBackCamera) "后置" else "前置"
            cameraTypeTextView.text = "$facingText 摄像头 (ID: $selectedCameraId)"
        }
    }

    private fun adjustPreviewViewSize(resolution: Size, rotationDegrees: Int) {
        previewView.post {
            try {
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val width = if (isRotated) resolution.height else resolution.width
                val height = if (isRotated) resolution.width else resolution.height
                
                val ratio = width.toFloat() / height.toFloat()
                
                val parent = previewView.parent as? ViewGroup ?: return@post
                val parentWidth = parent.width
                val parentHeight = parent.height
                
                if (parentWidth <= 0 || parentHeight <= 0) {
                    Log.w(TAG, "父容器尚未测量完成 (W:$parentWidth, H:$parentHeight)，50ms 后重试")
                    previewView.postDelayed({
                        adjustPreviewViewSize(resolution, rotationDegrees)
                    }, 50)
                    return@post
                }
                
                // 【核心修复】必须为两个 View 分别创建不同的 LayoutParams 实例，严禁共享，否则 ConstraintLayout 会显示异常
                val previewLp = previewView.layoutParams as ConstraintLayout.LayoutParams
                val overlayLp = overlayView.layoutParams as ConstraintLayout.LayoutParams
                
                val screenRatio = parentWidth.toFloat() / parentHeight.toFloat()
                
                if (screenRatio > ratio) {
                    // 屏幕比预览更宽，高度填满
                    previewLp.height = parentHeight
                    previewLp.width = (parentHeight * ratio).toInt()
                } else {
                    // 屏幕比预览更窄，宽度填满
                    previewLp.width = parentWidth
                    previewLp.height = (parentWidth / ratio).toInt()
                }
                
                // 确保尺寸不为 0 (防止相机 Surface 创建失败)
                if (previewLp.width <= 0) previewLp.width = 1
                if (previewLp.height <= 0) previewLp.height = 1
                
                // 将 OverlayView 的尺寸同步给同样的值，但必须是独立赋值
                overlayLp.width = previewLp.width
                overlayLp.height = previewLp.height
                
                previewView.layoutParams = previewLp
                overlayView.layoutParams = overlayLp 
                
                Log.d(TAG, "调整预览大小成功: ${previewLp.width}x${previewLp.height}, 比例: $ratio")
            } catch (e: Exception) {
                Log.e(TAG, "调整预览大小时出错: ${e.message}")
            }
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "权限被拒绝，无法启动相机",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}