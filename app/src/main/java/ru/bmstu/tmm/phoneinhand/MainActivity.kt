package ru.bmstu.tmm.phoneinhand

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var objectDetector: ObjectDetector? = null
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
        setupDetector()

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
            text = "Инициализация камеры"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(ru.bmstu.tmm.phoneinhand.R.drawable.status_panel)
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
            text = "FPS: -- | Inference: --"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(ru.bmstu.tmm.phoneinhand.R.drawable.status_panel)
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

    private fun setupDetector() {
        try {
            val baseOptionsBuilder = BaseOptions.builder().setNumThreads(4)
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setScoreThreshold(0.35f)
                .setMaxResults(8)
                .build()

            objectDetector = ObjectDetector.createFromFileAndOptions(
                this,
                MODEL_NAME,
                options
            )
        } catch (error: Exception) {
            statusText.text = "Ошибка модели: ${error.message}"
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
            val processor = ImageProcessor.Builder()
                .add(Rot90Op(-rotation / 90))
                .build()
            val tensorImage = processor.process(TensorImage.fromBitmap(bitmap))

            val started = System.currentTimeMillis()
            val results = detector.detect(tensorImage)
            val inferenceTime = System.currentTimeMillis() - started
            val boxes = results.toDetectionBoxes()
            val analysis = analyzer.analyze(
                detections = boxes,
                inferenceTimeMs = inferenceTime,
                imageWidth = tensorImage.width,
                imageHeight = tensorImage.height
            )

            runOnUiThread {
                overlayView.submitAnalysis(analysis)
                renderStatus(analysis)
            }
        } catch (error: Exception) {
            runOnUiThread {
                statusText.text = "Ошибка анализа: ${error.message}"
            }
        }
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
            PhoneState.NO_PHONE -> "Телефон не обнаружен"
            PhoneState.PHONE_VISIBLE -> "Телефон найден"
            PhoneState.PHONE_IN_HAND -> "Телефон в руке"
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
        statusText.text = "Для работы нужен доступ к камере"
        statusText.setTextColor(Color.rgb(255, 82, 82))
    }

    companion object {
        private const val MODEL_NAME = "efficientdet-lite0.tflite"
    }
}
