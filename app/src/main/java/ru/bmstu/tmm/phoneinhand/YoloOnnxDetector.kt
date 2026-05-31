package ru.bmstu.tmm.phoneinhand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class YoloOnnxDetector(context: Context) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_NAME).use { it.readBytes() }
        session = environment.createSession(modelBytes, OrtSession.SessionOptions())
    }

    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val letterbox = bitmap.toLetterbox()
        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(letterbox.floatData),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        input.use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                val raw = result[0].value as Array<Array<FloatArray>>
                return raw[0].mapNotNull { row -> row.toDetection(letterbox, bitmap.width, bitmap.height) }
            }
        }
    }

    private fun FloatArray.toDetection(letterbox: LetterboxFrame, imageWidth: Int, imageHeight: Int): DetectionBox? {
        if (size < 6) return null
        val score = this[4]
        if (score < SCORE_THRESHOLD) return null

        val classId = this[5].roundToInt()
        val label = COCO_LABELS.getOrNull(classId) ?: return null
        if (label != "person" && label != "cell phone") return null

        val left = ((this[0] - letterbox.padX) / letterbox.scale).coerceIn(0f, imageWidth.toFloat())
        val top = ((this[1] - letterbox.padY) / letterbox.scale).coerceIn(0f, imageHeight.toFloat())
        val right = ((this[2] - letterbox.padX) / letterbox.scale).coerceIn(0f, imageWidth.toFloat())
        val bottom = ((this[3] - letterbox.padY) / letterbox.scale).coerceIn(0f, imageHeight.toFloat())
        if (right - left < MIN_BOX_SIZE || bottom - top < MIN_BOX_SIZE) return null

        return DetectionBox(
            label = label,
            score = score,
            rect = RectF(left, top, right, bottom)
        )
    }

    private fun Bitmap.toLetterbox(): LetterboxFrame {
        val scale = min(INPUT_SIZE.toFloat() / width.toFloat(), INPUT_SIZE.toFloat() / height.toFloat())
        val scaledWidth = (width * scale).roundToInt()
        val scaledHeight = (height * scale).roundToInt()
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f

        val resized = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scaled = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
        canvas.drawBitmap(scaled, padX, padY, paint)
        if (scaled !== this) scaled.recycle()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        resized.recycle()

        val area = INPUT_SIZE * INPUT_SIZE
        val data = FloatArray(3 * area)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            data[i] = ((pixel shr 16) and 0xFF) / 255f
            data[i + area] = ((pixel shr 8) and 0xFF) / 255f
            data[i + 2 * area] = (pixel and 0xFF) / 255f
        }
        return LetterboxFrame(data, scale, padX, padY)
    }

    override fun close() {
        session.close()
    }

    private data class LetterboxFrame(
        val floatData: FloatArray,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    companion object {
        private const val MODEL_NAME = "yolo11m.onnx"
        private const val INPUT_NAME = "images"
        private const val INPUT_SIZE = 640
        private const val SCORE_THRESHOLD = 0.18f
        private const val MIN_BOX_SIZE = 8f

        private val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }
}
