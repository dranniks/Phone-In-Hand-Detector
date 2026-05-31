package ru.bmstu.tmm.phoneinhand

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

class HandLandmarkerDetector(context: Context) : AutoCloseable {
    private val landmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_NAME)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinHandPresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long): HandPose? {
        val image = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(image, timestampMs)
        val handedness = result.handedness()
        val hands = result.landmarks().mapIndexedNotNull { index, landmarks ->
            if (landmarks.size < HandPose.HAND_LANDMARK_COUNT) return@mapIndexedNotNull null
            val score = handedness.getOrNull(index)?.maxOfOrNull { it.score() } ?: 1f
            if (score < HandPose.MIN_HAND_SCORE) return@mapIndexedNotNull null
            DetectedHand(
                landmarks = landmarks.map {
                    PosePoint(
                        x = it.x() * bitmap.width,
                        y = it.y() * bitmap.height,
                        confidence = score
                    )
                },
                score = score
            )
        }
        return HandPose(hands).takeIf { it.hasReliableHand() }
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        private const val MODEL_NAME = "hand_landmarker.task"
        private const val MAX_HANDS = 2
        private const val MIN_DETECTION_CONFIDENCE = 0.55f
        private const val MIN_PRESENCE_CONFIDENCE = 0.55f
        private const val MIN_TRACKING_CONFIDENCE = 0.60f
    }
}
