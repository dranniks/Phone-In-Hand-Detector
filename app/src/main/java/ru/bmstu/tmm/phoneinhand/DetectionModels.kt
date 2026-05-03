package ru.bmstu.tmm.phoneinhand

import android.graphics.RectF

data class DetectionBox(
    val label: String,
    val score: Float,
    val rect: RectF
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
    val allDetections: List<DetectionBox>
)
