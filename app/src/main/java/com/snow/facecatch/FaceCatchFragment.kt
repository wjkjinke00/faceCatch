package com.snow.facecatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceCatchFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var switchCameraButton: FloatingActionButton
    private lateinit var cameraTypeTextView: TextView
    private lateinit var faceStatusTextView: TextView
    private lateinit var guidanceTextView: TextView
    private lateinit var captureProgressBar: ProgressBar
    
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isBackCamera = true
    private var selectedCameraId: String? = null
    
    private val PREFS_NAME = "camera_settings"
    private val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
    private val KEY_CAMERA_ROTATION_OFFSET = "camera_rotation_offset_" // 后缀接 cameraId
    
    private var isCapturing = false
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN = 5000L
    private var frontFaceHoldStartTime = 0L
    
    // 用于裁剪的状态
    private var latestFaceBoundingBox: RectF? = null
    private var latestImageWidth = 0
    private var latestImageHeight = 0
    private var latestRotationDegrees = 0
    private var latestEulerZ = 0f

    companion object {
        private const val TAG = "FaceCatchFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_face_catch, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        previewView = view.findViewById(R.id.previewView)
        overlayView = view.findViewById(R.id.overlayView)
        captureButton = view.findViewById(R.id.captureButton)
        switchCameraButton = view.findViewById(R.id.switchCameraButton)
        cameraTypeTextView = view.findViewById(R.id.cameraTypeTextView)
        faceStatusTextView = view.findViewById(R.id.faceStatusTextView)
        guidanceTextView = view.findViewById(R.id.guidanceTextView)
        captureProgressBar = view.findViewById(R.id.captureProgressBar)
        
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedCameraId = prefs.getString(KEY_SELECTED_CAMERA_ID, null)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        
        cleanupOldPhotos()
        
        captureButton.setOnClickListener { captureFaceImage() }
        switchCameraButton.setOnClickListener { switchCamera() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val availableCameras = cameraProvider.availableCameraInfos
                val targetCameraInfo = availableCameras.find { 
                    Camera2CameraInfo.from(it).cameraId == selectedCameraId 
                } ?: availableCameras.find { 
                    it.lensFacing == CameraSelector.LENS_FACING_BACK 
                } ?: availableCameras.firstOrNull()

                if (targetCameraInfo != null) {
                    currentCameraSelector = targetCameraInfo.cameraSelector
                    isBackCamera = targetCameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
                    selectedCameraId = Camera2CameraInfo.from(targetCameraInfo).cameraId
                }

                updateCameraTypeIndicator()

                val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rotationOffset = prefs.getInt(KEY_CAMERA_ROTATION_OFFSET + selectedCameraId, 0)
                
                // 将 0, 90, 180, 270 角度转换为 Surface.ROTATION_* 
                // CameraX 的 setTargetRotation 接受的是 Surface 旋转常量
                // 默认通常是 Surface.ROTATION_0 (0). 
                // 这里的逻辑需要根据设备默认方向和安装偏移来计算
                // 为了简单起见，我们直接让用户选择最终想要的“显示方向”或者相对于默认的偏移
                // 这里我们采用“直接指定目标旋转”的方式
                val targetRotation = when (rotationOffset) {
                    90 -> android.view.Surface.ROTATION_90
                    180 -> android.view.Surface.ROTATION_180
                    270 -> android.view.Surface.ROTATION_270
                    else -> android.view.Surface.ROTATION_0
                }

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(targetRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(targetRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, FaceDetectionAnalyzer { faces, imageWidth, imageHeight, rotationDegrees ->
                            activity?.runOnUiThread {
                                handleFaceDetectionResult(faces, imageWidth, imageHeight, rotationDegrees)
                            }
                        })
                    }

                imageCapture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(targetRotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        viewLifecycleOwner, currentCameraSelector, preview, imageAnalyzer, imageCapture!!
                    )
                    
                    val resInfo = preview.resolutionInfo
                    if (resInfo != null) {
                        adjustPreviewViewSize(resInfo.resolution, resInfo.rotationDegrees)
                    } else {
                        previewView.post {
                            preview.resolutionInfo?.let {
                                adjustPreviewViewSize(it.resolution, it.rotationDegrees)
                            }
                        }
                    }
                } catch (exc: Exception) {
                    Log.e(TAG, "相机绑定失败: ${exc.message}", exc)
                }
            } catch (exc: Exception) {
                Log.e(TAG, "相机启动异常: ${exc.message}", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun handleFaceDetectionResult(faces: List<com.google.mlkit.vision.face.Face>, imageWidth: Int, imageHeight: Int, rotationDegrees: Int) {
        if (faces.isNotEmpty()) {
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces[0]
            val eulerX = face.headEulerAngleX
            val eulerY = face.headEulerAngleY
            val statusList = mutableListOf<String>()
            
            if (eulerY > 15 || eulerY < -15) statusList.add("侧脸")
            if (eulerX > 15) statusList.add("抬头") else if (eulerX < -15) statusList.add("低头")
            
            if (statusList.isEmpty()) {
                faceStatusTextView.text = "正脸"
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val visualWidth = if (isRotated) imageHeight else imageWidth
                val visualHeight = if (isRotated) imageWidth else imageHeight
                
                val faceArea = face.boundingBox.width() * face.boundingBox.height()
                val totalArea = visualWidth * visualHeight
                val areaRatio = faceArea.toFloat() / totalArea.toFloat()

                val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f
                val isEyesOpen = leftEyeOpen > 0.45f && rightEyeOpen > 0.45f

                if (kotlin.math.abs(eulerX) < 10 && kotlin.math.abs(eulerY) < 10) {
                    if (areaRatio > 0.08) {
                        if (isEyesOpen) {
                            val currentTime = System.currentTimeMillis()
                            if (frontFaceHoldStartTime == 0L) frontFaceHoldStartTime = currentTime
                            val holdDuration = currentTime - frontFaceHoldStartTime
                            
                            if (holdDuration >= 800) {
                                captureProgressBar.visibility = View.INVISIBLE
                                captureProgressBar.progress = 100
                                if (!isCapturing && (currentTime - lastCaptureTime > CAPTURE_COOLDOWN)) {
                                    latestFaceBoundingBox = RectF(face.boundingBox)
                                    latestImageWidth = visualWidth
                                    latestImageHeight = visualHeight
                                    latestRotationDegrees = rotationDegrees
                                    latestEulerZ = face.headEulerAngleZ
                                    frontFaceHoldStartTime = 0L
                                    onFrontFaceDetected()
                                }
                            } else {
                                guidanceTextView.text = "保持住..."
                                guidanceTextView.setTextColor(Color.CYAN)
                                captureProgressBar.visibility = View.VISIBLE
                                captureProgressBar.progress = (holdDuration / 8).toInt()
                            }
                        } else {
                            guidanceTextView.text = "请睁开眼睛"
                            guidanceTextView.setTextColor(Color.parseColor("#FF9800"))
                            frontFaceHoldStartTime = 0L
                            captureProgressBar.visibility = View.INVISIBLE
                        }
                    } else {
                        guidanceTextView.text = "请靠近一点"
                        guidanceTextView.setTextColor(Color.YELLOW)
                        frontFaceHoldStartTime = 0L
                        captureProgressBar.visibility = View.INVISIBLE
                    }
                } else {
                    guidanceTextView.text = "请正对着摄像头"
                    guidanceTextView.setTextColor(Color.YELLOW)
                    frontFaceHoldStartTime = 0L
                    captureProgressBar.visibility = View.INVISIBLE
                }
            } else {
                faceStatusTextView.text = statusList.joinToString(", ")
                guidanceTextView.text = "请正对着摄像头"
                guidanceTextView.setTextColor(Color.YELLOW)
                frontFaceHoldStartTime = 0L
                captureProgressBar.visibility = View.INVISIBLE
            }

            drawFaceOverlay(face, imageWidth, imageHeight, rotationDegrees)
        } else {
            overlayView.setFaceRect(null)
            faceStatusTextView.text = "无检测"
            guidanceTextView.text = "寻找人脸中..."
            guidanceTextView.setTextColor(Color.parseColor("#FFEB3B"))
            captureProgressBar.visibility = View.INVISIBLE
        }
    }

    private fun drawFaceOverlay(face: com.google.mlkit.vision.face.Face, imageWidth: Int, imageHeight: Int, rotationDegrees: Int) {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val visualWidth = if (isRotated) imageHeight else imageWidth
        val visualHeight = if (isRotated) imageWidth else imageHeight
        
        val scaleX = viewWidth.toFloat() / visualWidth.toFloat()
        val scaleY = viewHeight.toFloat() / visualHeight.toFloat()
        val scale = kotlin.math.max(scaleX, scaleY)
        val offsetX = (viewWidth - visualWidth * scale) / 2f
        val offsetY = (viewHeight - visualHeight * scale) / 2f
        
        val boundingBox = RectF(face.boundingBox)
        val mappedRect = RectF(
            boundingBox.left * scale + offsetX,
            boundingBox.top * scale + offsetY,
            boundingBox.right * scale + offsetX,
            boundingBox.bottom * scale + offsetY
        )
        
        if (!isBackCamera) {
            val left = viewWidth - mappedRect.right
            val right = viewWidth - mappedRect.left
            mappedRect.left = left
            mappedRect.right = right
        }
        
        val height = mappedRect.height()
        val finalRect = RectF(
            mappedRect.left,
            mappedRect.top - height * 0.20f,
            mappedRect.right,
            mappedRect.bottom
        )
        overlayView.setFaceRect(finalRect)
    }

    private fun onFrontFaceDetected() {
        Log.d(TAG, "检测到正脸，触发开发者回调")
        captureFaceImage()
    }

    private fun captureFaceImage() {
        val imageCapture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processAndSaveCapturedImage(image)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "照片捕获失败: ${exception.message}")
                    isCapturing = false
                    lastCaptureTime = System.currentTimeMillis()
                }
            }
        )
    }

    private fun processAndSaveCapturedImage(image: ImageProxy) {
        try {
            val rotationDegrees = image.imageInfo.rotationDegrees
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotatedBitmap != bitmap) { bitmap.recycle(); bitmap = rotatedBitmap }
            }

            val currentFaceBox = latestFaceBoundingBox
            val sourceWidth = latestImageWidth
            val sourceHeight = latestImageHeight

            if (currentFaceBox != null && sourceWidth > 0 && sourceHeight > 0) {
                val scale = bitmap.width.toFloat() / sourceWidth.toFloat()
                val mappedRect = RectF(currentFaceBox.left * scale, currentFaceBox.top * scale, currentFaceBox.right * scale, currentFaceBox.bottom * scale)
                val w = mappedRect.width()
                val h = mappedRect.height()
                val cropRect = RectF(mappedRect.left - w * 0.40f, mappedRect.top - h * 0.80f, mappedRect.right + w * 0.40f, mappedRect.bottom + h * 0.30f)
                val finalRect = Rect(kotlin.math.max(0, cropRect.left.toInt()), kotlin.math.max(0, cropRect.top.toInt()), kotlin.math.min(bitmap.width, cropRect.right.toInt()), kotlin.math.min(bitmap.height, cropRect.bottom.toInt()))

                if (finalRect.width() > 0 && finalRect.height() > 0) {
                    val faceBitmap = Bitmap.createBitmap(bitmap, finalRect.left, finalRect.top, finalRect.width(), finalRect.height())
                    bitmap.recycle(); bitmap = faceBitmap
                    if (latestEulerZ != 0f) {
                        val correctionMatrix = Matrix().apply { postRotate(latestEulerZ) }
                        val correctedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, correctionMatrix, true)
                        if (correctedBitmap != bitmap) { bitmap.recycle(); bitmap = correctedBitmap }
                    }
                }
            }
            
            if (!isBackCamera) {
                val matrix = Matrix().apply { postScale(-1f, 1f) }
                val mirroredBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (mirroredBitmap != bitmap) { bitmap.recycle(); bitmap = mirroredBitmap }
            }

            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
            val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "face_crop_${name}.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }

            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "人脸已抓取: ${file.name}", Toast.LENGTH_SHORT).show()
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val availableCameras = cameraProvider.availableCameraInfos
            val cameraItems = availableCameras.map { info ->
                val id = Camera2CameraInfo.from(info).cameraId
                val facing = when (info.lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "后置"
                    CameraSelector.LENS_FACING_FRONT -> "前置"
                    else -> "未知"
                }
                "摄像头 $id ($facing)"
            }.toTypedArray()

            val currentIndex = availableCameras.indexOfFirst { Camera2CameraInfo.from(it).cameraId == selectedCameraId }.let { if (it == -1) 0 else it }

            AlertDialog.Builder(requireContext())
                .setTitle("选择摄像头")
                .setSingleChoiceItems(cameraItems, currentIndex) { dialog, which ->
                    val newCameraId = Camera2CameraInfo.from(availableCameras[which]).cameraId
                    dialog.dismiss()
                    showRotationDialog(newCameraId)
                }
                .setNegativeButton("取消", null)
                .show()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun showRotationDialog(cameraId: String) {
        val rotations = arrayOf("0° (默认)", "90°", "180°", "270°")
        val rotationValues = intArrayOf(0, 90, 180, 270)
        
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRotation = prefs.getInt(KEY_CAMERA_ROTATION_OFFSET + cameraId, 0)
        val currentIndex = rotationValues.indexOf(currentRotation).let { if (it == -1) 0 else it }

        AlertDialog.Builder(requireContext())
            .setTitle("调整旋转角度 (摄像头 $cameraId)")
            .setSingleChoiceItems(rotations, currentIndex) { dialog, which ->
                val selectedRotation = rotationValues[which]
                selectedCameraId = cameraId
                prefs.edit().apply {
                    putString(KEY_SELECTED_CAMERA_ID, cameraId)
                    putInt(KEY_CAMERA_ROTATION_OFFSET + cameraId, selectedRotation)
                    apply()
                }
                dialog.dismiss()
                startCamera()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCameraTypeIndicator() {
        activity?.runOnUiThread {
            val facingText = if (isBackCamera) "后置" else "前置"
            cameraTypeTextView.text = "$facingText 摄像头 (ID: $selectedCameraId)"
        }
    }

    private fun adjustPreviewViewSize(resolution: Size, rotationDegrees: Int) {
        previewView.post {
            try {
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val ratio = (if (isRotated) resolution.height else resolution.width).toFloat() / (if (isRotated) resolution.width else resolution.height).toFloat()
                val parentWidth = (previewView.parent as ViewGroup).width
                val parentHeight = (previewView.parent as ViewGroup).height
                if (parentWidth <= 0 || parentHeight <= 0) {
                    previewView.postDelayed({ adjustPreviewViewSize(resolution, rotationDegrees) }, 50)
                    return@post
                }
                val previewLp = previewView.layoutParams as ConstraintLayout.LayoutParams
                val overlayLp = overlayView.layoutParams as ConstraintLayout.LayoutParams
                if (parentWidth.toFloat() / parentHeight.toFloat() > ratio) {
                    previewLp.height = parentHeight
                    previewLp.width = (parentHeight * ratio).toInt()
                } else {
                    previewLp.width = parentWidth
                    previewLp.height = (parentWidth / ratio).toInt()
                }
                overlayLp.width = previewLp.width; overlayLp.height = previewLp.height
                previewView.layoutParams = previewLp; overlayView.layoutParams = overlayLp
            } catch (e: Exception) { Log.e(TAG, "调整预览大小出错: ${e.message}") }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera() else Toast.makeText(requireContext(), "权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupOldPhotos() {
        cameraExecutor.execute {
            try {
                val directory = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return@execute
                val currentTime = System.currentTimeMillis()
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".jpg") && (currentTime - file.lastModified() > 3600000L)) file.delete()
                }
            } catch (e: Exception) { Log.e(TAG, "清理照片失败: ${e.message}") }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
