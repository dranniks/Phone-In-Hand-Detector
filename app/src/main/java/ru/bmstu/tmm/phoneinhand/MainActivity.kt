package ru.bmstu.tmm.phoneinhand

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var metricsText: TextView
    private lateinit var phoneText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var contactText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var objectDetector: YoloOnnxDetector? = null
    private var handDetector: HandLandmarkerDetector? = null
    private var bitmapBuffer: Bitmap? = null
    private var lastUiFrameTimeMs = 0L
    private var smoothFps = 0f
    private var frameIndex = 0
    private var cachedHandPose: HandPose? = null
    private var cachedHandAge = MAX_HAND_CACHE_FRAMES + 1
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
        handDetector?.close()
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

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.status_panel)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        statusText = TextView(this).apply {
            text = "Запуск камеры"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        hintText = TextView(this).apply {
            text = "Наведите камеру на человека и телефон"
            setTextColor(Color.rgb(226, 232, 240))
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        statusPanel.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        statusPanel.addView(hintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
        })
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            setMargins(dp(16), dp(34), dp(16), 0)
        }
        root.addView(statusPanel, statusParams)

        val metricsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.status_panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        metricsText = metricView("Кадр/с", "--")
        phoneText = metricView("Телефоны", "--")
        confidenceText = metricView("Уверенность", "--")
        contactText = metricView("Рука", "--")
        listOf(metricsText, phoneText, confidenceText, contactText).forEachIndexed { index, view ->
            metricsRow.addView(view, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        metricsPanel.addView(metricsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val metricsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            setMargins(dp(16), 0, dp(16), dp(34))
        }
        root.addView(metricsPanel, metricsParams)

        return root
    }

    private fun setupModels() {
        try {
            objectDetector = YoloOnnxDetector(this)
            handDetector = HandLandmarkerDetector(this)
        } catch (error: Exception) {
            statusText.text = "Ошибка запуска"
            hintText.text = "Не удалось подготовить распознавание"
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
                .setTargetResolution(Size(640, 480))
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

            val started = System.currentTimeMillis()
            val objectResults = detector.detect(orientedBitmap)
            val handPose = detectHandsIfNeeded(orientedBitmap, objectResults)
            val inferenceTime = System.currentTimeMillis() - started

            val analysis = analyzer.analyze(
                detections = objectResults,
                handPose = handPose,
                inferenceTimeMs = inferenceTime,
                imageWidth = orientedBitmap.width,
                imageHeight = orientedBitmap.height
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
                statusText.text = "Ошибка анализа"
                hintText.text = "Попробуйте перезапустить приложение"
            }
        }
    }

    private fun detectHandsIfNeeded(bitmap: Bitmap, detections: List<DetectionBox>): HandPose? {
        frameIndex++
        val phoneVisible = detections.any { it.label.isPhoneLabel() }
        val shouldRefresh = phoneVisible && (
            cachedHandPose == null ||
                cachedHandAge >= MAX_HAND_CACHE_FRAMES ||
                frameIndex % HAND_DETECTION_INTERVAL == 0
            )

        if (shouldRefresh) {
            cachedHandPose = try {
                handDetector?.detect(bitmap, SystemClock.uptimeMillis())
            } catch (_: Exception) {
                null
            }
            cachedHandAge = 0
        } else {
            cachedHandAge++
        }

        return cachedHandPose?.takeIf { cachedHandAge <= MAX_HAND_CACHE_FRAMES }
    }

    private fun renderStatus(analysis: PhoneAnalysis) {
        val status = when (analysis.state) {
            PhoneState.NO_PHONE -> "Телефон не найден"
            PhoneState.PHONE_VISIBLE -> "Телефон в кадре"
            PhoneState.PHONE_IN_HAND -> "Телефон в руке"
        }
        val color = when (analysis.state) {
            PhoneState.NO_PHONE -> Color.rgb(0, 184, 148)
            PhoneState.PHONE_VISIBLE -> Color.rgb(253, 203, 110)
            PhoneState.PHONE_IN_HAND -> Color.rgb(255, 82, 82)
        }
        val hint = when (analysis.state) {
            PhoneState.NO_PHONE -> "Покажите телефон ближе к камере"
            PhoneState.PHONE_VISIBLE -> "Телефон виден, контакта с рукой нет"
            PhoneState.PHONE_IN_HAND -> "Обнаружен телефон рядом с кистью"
        }
        val phoneCount = analysis.allDetections.count { it.label.isPhoneLabel() }
        val fps = updateFps()

        statusText.text = status
        statusText.setTextColor(color)
        hintText.text = hint
        metricsText.text = "Кадр/с\n${if (fps > 0f) "%.1f".format(fps) else "--"}"
        phoneText.text = "Телефоны\n$phoneCount"
        confidenceText.text = "Уверенность\n${if (analysis.confidence > 0f) "${(analysis.confidence * 100).toInt()}%" else "--"}"
        contactText.text = "Рука\n${if (analysis.state == PhoneState.PHONE_IN_HAND) "да" else "нет"}"
    }

    private fun showPermissionError() {
        statusText.text = "Нет доступа к камере"
        hintText.text = "Разрешите доступ в настройках приложения"
        statusText.setTextColor(Color.rgb(255, 82, 82))
    }

    private fun updateFps(): Float {
        val now = System.currentTimeMillis()
        if (lastUiFrameTimeMs == 0L) {
            lastUiFrameTimeMs = now
            return 0f
        }
        val delta = (now - lastUiFrameTimeMs).coerceAtLeast(1L)
        lastUiFrameTimeMs = now
        val currentFps = 1000f / delta.toFloat()
        smoothFps = if (smoothFps == 0f) currentFps else smoothFps * 0.85f + currentFps * 0.15f
        return smoothFps
    }

    private fun metricView(title: String, value: String): TextView {
        return TextView(this).apply {
            text = "$title\n$value"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    }

    private fun String.isPhoneLabel(): Boolean {
        val normalized = lowercase()
        return normalized == "cell phone" ||
            normalized == "mobile phone" ||
            normalized == "phone" ||
            normalized.contains("cellphone")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return this
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
    companion object {
        private const val HAND_DETECTION_INTERVAL = 5
        private const val MAX_HAND_CACHE_FRAMES = 6
    }
}
