package ru.bmstu.tmm.phoneinhand

import android.graphics.RectF

data class DetectionBox(
    val label: String,
    val score: Float,
    val rect: RectF
)

data class PosePoint(
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class HandPose(
    val hands: List<DetectedHand>
) {
    val wristPoints: List<PosePoint>
        get() = hands.mapNotNull { it.landmarks.getOrNull(HAND_WRIST) }

    val palmPoints: List<PosePoint>
        get() = hands.flatMap { hand -> PALM_LANDMARKS.mapNotNull { hand.landmarks.getOrNull(it) } }

    val fingerPoints: List<PosePoint>
        get() = hands.flatMap { hand -> FINGER_LANDMARKS.mapNotNull { hand.landmarks.getOrNull(it) } }

    val handSegments: List<Pair<PosePoint, PosePoint>>
        get() = hands.flatMap { hand ->
            HAND_CONNECTIONS.mapNotNull { (start, end) ->
                val a = hand.landmarks.getOrNull(start)
                val b = hand.landmarks.getOrNull(end)
                if (a != null && b != null) a to b else null
            }
        }

    fun hasReliableHand(): Boolean = hands.any {
        it.score >= MIN_HAND_SCORE && it.landmarks.size >= HAND_LANDMARK_COUNT
    }

    companion object {
        const val HAND_WRIST = 0
        const val HAND_LANDMARK_COUNT = 21
        const val MIN_HAND_SCORE = 0.45f

        val PALM_LANDMARKS = listOf(0, 1, 2, 5, 9, 13, 17)
        val FINGER_LANDMARKS = listOf(4, 8, 12, 16, 20)
        val HAND_CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20
        )
    }
}

data class DetectedHand(
    val landmarks: List<PosePoint>,
    val score: Float
)

enum class PhoneState {
    NO_PHONE,
    PHONE_VISIBLE,
    PHONE_IN_HAND
}

data class PhoneAnalysis(
    val state: PhoneState,
    val phone: DetectionBox?,
    val person: DetectionBox?,
    val confidence: Float,
    val reason: String,
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val handPose: HandPose?,
    val allDetections: List<DetectionBox>
)
