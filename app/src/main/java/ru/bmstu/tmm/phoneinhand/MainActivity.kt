package ru.bmstu.tmm.phoneinhand

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var objectDetector: ObjectDetector? = null
    private var poseDetector: PoseDetector? = null
    private var bitmapBuffer: Bitmap? = null
    private val analyzer = PhoneInHandAnalyzer()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(createContentView())
        setupModels()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetector?.close()
        poseDetector?.close()
    }

    private fun createContentView(): ViewGroup {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlayView = OverlayView(this)
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this).apply {
            text = "Starting camera"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.status_panel)
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            setMargins(24, 42, 24, 0)
        }
        root.addView(statusText, statusParams)

        metricsText = TextView(this).apply {
            text = "Inference: -- | confidence: --"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.status_panel)
        }
        val metricsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            setMargins(24, 0, 24, 42)
        }
        root.addView(metricsText, metricsParams)

        return root
    }

    private fun setupModels() {
        try {
            val objectOptions = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                .setScoreThreshold(0.35f)
                .setMaxResults(8)
                .build()

            objectDetector = ObjectDetector.createFromFileAndOptions(
                this,
                MODEL_NAME,
                objectOptions
            )

            val poseOptions = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(poseOptions)
        } catch (error: Exception) {
            statusText.text = "Model error: ${error.message}"
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image -> detect(image) }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detect(image: ImageProxy) {
        val detector = objectDetector
        if (detector == null) {
            image.close()
            return
        }

        val bitmap = bitmapBuffer?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                bitmapBuffer = it
            }

        val rotation = image.imageInfo.rotationDegrees
        image.use {
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
        }

        try {
            val orientedBitmap = bitmap.rotate(rotation)
            val tensorImage = TensorImage.fromBitmap(orientedBitmap)

            val started = System.currentTimeMillis()
            val objectResults = detector.detect(tensorImage)
            val handPose = detectPose(orientedBitmap)
            val inferenceTime = System.currentTimeMillis() - started

            val analysis = analyzer.analyze(
                detections = objectResults.toDetectionBoxes(),
                handPose = handPose,
                inferenceTimeMs = inferenceTime,
                imageWidth = tensorImage.width,
                imageHeight = tensorImage.height
            )

            if (orientedBitmap !== bitmap) {
                orientedBitmap.recycle()
            }

            runOnUiThread {
                overlayView.submitAnalysis(analysis)
                renderStatus(analysis)
            }
        } catch (error: Exception) {
            runOnUiThread {
                statusText.text = "Analysis error: ${error.message}"
            }
        }
    }

    private fun detectPose(bitmap: Bitmap): HandPose? {
        val detector = poseDetector ?: return null
        return try {
            val pose = Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                POSE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            pose.toHandPose()
        } catch (_: Exception) {
            null
        }
    }

    private fun Pose.toHandPose(): HandPose {
        return HandPose(
            leftArm = ArmPose(
                shoulder = point(PoseLandmark.LEFT_SHOULDER),
                elbow = point(PoseLandmark.LEFT_ELBOW),
                wrist = point(PoseLandmark.LEFT_WRIST)
            ),
            rightArm = ArmPose(
                shoulder = point(PoseLandmark.RIGHT_SHOULDER),
                elbow = point(PoseLandmark.RIGHT_ELBOW),
                wrist = point(PoseLandmark.RIGHT_WRIST)
            )
        )
    }

    private fun Pose.point(type: Int): PosePoint? {
        val landmark = getPoseLandmark(type) ?: return null
        return PosePoint(
            x = landmark.position.x,
            y = landmark.position.y,
            confidence = landmark.inFrameLikelihood
        )
    }

    private fun List<Detection>.toDetectionBoxes(): List<DetectionBox> {
        return mapNotNull { detection ->
            val category = detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            DetectionBox(
                label = category.label,
                score = category.score,
                rect = detection.boundingBox
            )
        }
    }

    private fun renderStatus(analysis: PhoneAnalysis) {
        val status = when (analysis.state) {
            PhoneState.NO_PHONE -> "No phone"
            PhoneState.PHONE_VISIBLE -> "Phone visible"
            PhoneState.PHONE_IN_HAND -> "Phone in hand"
        }
        val color = when (analysis.state) {
            PhoneState.NO_PHONE -> Color.rgb(0, 184, 148)
            PhoneState.PHONE_VISIBLE -> Color.rgb(253, 203, 110)
            PhoneState.PHONE_IN_HAND -> Color.rgb(255, 82, 82)
        }
        statusText.text = "$status\n${analysis.reason}"
        statusText.setTextColor(color)
        metricsText.text = "Inference: ${analysis.inferenceTimeMs} ms | confidence: ${"%.2f".format(analysis.confidence)}"
    }

    private fun showPermissionError() {
        statusText.text = "Camera permission is required"
        statusText.setTextColor(Color.rgb(255, 82, 82))
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return this
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    companion object {
        private const val MODEL_NAME = "efficientdet-lite0.tflite"
        private const val POSE_TIMEOUT_MS = 450L
    }
}
