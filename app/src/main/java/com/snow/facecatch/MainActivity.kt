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
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // 图像分析用例（用于人脸检测）
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .setTargetRotation(previewView.display.rotation)
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
                                    
                                    // 6. 水平方向收缩 15%，使其更贴合面部而不是整个头部（包括耳朵）
                                    val width = mappedRect.width()
                                    val centerX = mappedRect.centerX()
                                    val shrunkWidth = width * 0.85f
                                    mappedRect.left = centerX - shrunkWidth / 2f
                                    mappedRect.right = centerX + shrunkWidth / 2f
                                    
                                    // 7. 使用极紧凑的边距（仅上方保留 10 像素以覆盖发际线）
                                    val finalRect = RectF(
                                        mappedRect.left,
                                        mappedRect.top - 10,
                                        mappedRect.right,
                                        mappedRect.bottom
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
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                try {
                    // 解绑之前的用例
                    cameraProvider.unbindAll()
                    
                    // 绑定预览、图像分析和图像捕获用例
                    cameraProvider.bindToLifecycle(
                        this, currentCameraSelector, preview, imageAnalyzer, imageCapture!!
                    )
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
    
    private fun captureFaceImage() {
        val imageCapture = imageCapture ?: return
        
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "face_${name}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        
        val imageSavedCallback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri ?: file.toURI()
                Log.d(TAG, "人脸照片已保存: ${savedUri}")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "人脸照片已保存: ${file.absolutePath}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            
            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "照片保存失败: ${exception.message}", exception)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "人脸抓取失败: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            imageSavedCallback
        )
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