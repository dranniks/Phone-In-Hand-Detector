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

data class ArmPose(
    val shoulder: PosePoint?,
    val elbow: PosePoint?,
    val wrist: PosePoint?
)

data class HandPose(
    val leftArm: ArmPose,
    val rightArm: ArmPose
) {
    val wristPoints: List<PosePoint>
        get() = listOfNotNull(leftArm.wrist, rightArm.wrist)

    val armSegments: List<Pair<PosePoint, PosePoint>>
        get() = listOfNotNull(
            leftArm.elbow?.let { elbow -> leftArm.wrist?.let { wrist -> elbow to wrist } },
            rightArm.elbow?.let { elbow -> rightArm.wrist?.let { wrist -> elbow to wrist } },
            leftArm.shoulder?.let { shoulder -> leftArm.elbow?.let { elbow -> shoulder to elbow } },
            rightArm.shoulder?.let { shoulder -> rightArm.elbow?.let { elbow -> shoulder to elbow } }
        )
}

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
