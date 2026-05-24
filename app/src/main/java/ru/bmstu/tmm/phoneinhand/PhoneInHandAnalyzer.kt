package ru.bmstu.tmm.phoneinhand

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PhoneInHandAnalyzer {
    private var positiveStreak = 0
    private var negativeStreak = 0
    private var lastState = PhoneState.NO_PHONE
    private var phoneMissStreak = 0
    private var lastPhone: DetectionBox? = null

    fun analyze(
        detections: List<DetectionBox>,
        handPose: HandPose?,
        inferenceTimeMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): PhoneAnalysis {
        val phones = detections
            .filter { it.label.isPhoneLabel() }
            .sortedByDescending { it.score }
        val people = detections
            .filter { it.label.equals("person", ignoreCase = true) }
            .sortedByDescending { it.score }

        val detectedPhone = phones.firstOrNull()
        val phone = detectedPhone ?: lastPhone?.takeIf { phoneMissStreak < MAX_PHONE_MEMORY_FRAMES }
        if (phone == null) {
            negativeStreak++
            positiveStreak = 0
            phoneMissStreak++
            val state = if (negativeStreak >= 2) PhoneState.NO_PHONE else lastState
            lastState = state
            return PhoneAnalysis(
                state = state,
                phone = null,
                person = null,
                confidence = 0f,
                reason = "No phone detected",
                inferenceTimeMs = inferenceTimeMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                handPose = handPose,
                allDetections = detections
            )
        }

        if (detectedPhone != null) {
            phoneMissStreak = 0
            lastPhone = detectedPhone
        } else {
            phoneMissStreak++
        }

        val linkedPerson = people.maxByOrNull { personAssociationScore(phone.rect, it.rect) }
        val reliablePose = handPose?.hasReliableArm() == true
        val poseContact = handPose?.takeIf { reliablePose }?.let { isPhoneNearPoseHands(phone.rect, it, linkedPerson) } ?: false
        val fallbackContact = linkedPerson != null && isPhoneInHandZone(phone.rect, linkedPerson.rect)
        val rawInHand = poseContact || (!reliablePose && fallbackContact)

        if (rawInHand) {
            positiveStreak++
            negativeStreak = 0
        } else {
            negativeStreak++
            positiveStreak = 0
        }

        val smoothedState = when {
            positiveStreak >= 2 -> PhoneState.PHONE_IN_HAND
            negativeStreak >= 4 -> PhoneState.PHONE_VISIBLE
            else -> lastState.takeIf { it != PhoneState.NO_PHONE } ?: PhoneState.PHONE_VISIBLE
        }
        lastState = smoothedState

        val reason = when (smoothedState) {
            PhoneState.PHONE_IN_HAND -> if (poseContact) {
                "Phone is close to wrist or forearm landmarks"
            } else {
                "Phone is in a plausible hand zone"
            }
            PhoneState.PHONE_VISIBLE -> if (handPose == null) {
                "Phone found, pose landmarks are not visible"
            } else if (!reliablePose) {
                "Phone found, full arm landmarks are not reliable"
            } else {
                "Phone found, but it is not close to hands"
            }
            PhoneState.NO_PHONE -> "No phone detected"
        }

        return PhoneAnalysis(
            state = smoothedState,
            phone = phone,
            person = linkedPerson,
            confidence = phone.score,
            reason = reason,
            inferenceTimeMs = inferenceTimeMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            handPose = handPose,
            allDetections = detections
        )
    }

    private fun String.isPhoneLabel(): Boolean {
        val normalized = lowercase()
        return normalized == "cell phone" ||
            normalized == "mobile phone" ||
            normalized == "phone" ||
            normalized.contains("cellphone")
    }

    private fun isPhoneInHandZone(phone: RectF, person: RectF): Boolean {
        val personWidth = person.width().coerceAtLeast(1f)
        val personHeight = person.height().coerceAtLeast(1f)
        val centerX = phone.centerX()
        val centerY = phone.centerY()
        val normalizedX = (centerX - person.left) / personWidth
        val normalizedY = (centerY - person.top) / personHeight

        val insideExpandedPerson = expanded(person, 0.15f).contains(centerX, centerY)
        val overlapRatio = intersectionArea(phone, person) / max(1f, phone.width() * phone.height())
        val plausibleArmHeight = normalizedY in 0.28f..0.92f
        val nearBodySide = normalizedX in -0.12f..0.35f || normalizedX in 0.65f..1.12f
        val centralTorso = normalizedX in 0.35f..0.65f && normalizedY in 0.45f..0.88f

        return insideExpandedPerson && plausibleArmHeight && (nearBodySide || centralTorso || overlapRatio > 0.35f)
    }

    private fun isPhoneNearPoseHands(phone: RectF, handPose: HandPose, person: DetectionBox?): Boolean {
        val reliableArms = listOf(handPose.leftArm, handPose.rightArm)
            .filter { it.isReliableArm() }
        if (reliableArms.isEmpty()) return false

        val phoneSize = max(phone.width(), phone.height()).coerceAtLeast(1f)
        val personWidth = person?.rect?.width()?.coerceAtLeast(1f) ?: phoneSize * 4f
        val wristThreshold = max(phoneSize * 1.35f, personWidth * 0.10f).coerceAtLeast(34f)
        val segmentThreshold = max(phoneSize * 1.15f, personWidth * 0.085f).coerceAtLeast(30f)
        val expandedPhone = expanded(phone, 0.65f)

        val wristContact = reliableArms.mapNotNull { it.wrist }.any { wrist ->
            wrist.confidence >= MIN_LANDMARK_CONFIDENCE &&
                (expandedPhone.contains(wrist.x, wrist.y) || distanceToRect(wrist, phone) <= wristThreshold)
        }
        if (wristContact) return true

        val phoneCenter = PosePoint(phone.centerX(), phone.centerY(), 1f)
        return reliableArms.any { arm ->
            val elbow = arm.elbow ?: return@any false
            val wrist = arm.wrist ?: return@any false
            distancePointToSegment(phoneCenter, elbow, wrist) <= segmentThreshold
        }
    }

    private fun HandPose.hasReliableArm(): Boolean {
        return leftArm.isReliableArm() || rightArm.isReliableArm()
    }

    private fun ArmPose.isReliableArm(): Boolean {
        return (shoulder?.confidence ?: 0f) >= MIN_LANDMARK_CONFIDENCE &&
            (elbow?.confidence ?: 0f) >= MIN_LANDMARK_CONFIDENCE &&
            (wrist?.confidence ?: 0f) >= MIN_LANDMARK_CONFIDENCE
    }

    private fun personAssociationScore(phone: RectF, person: RectF): Float {
        val centerDistance = abs(phone.centerX() - person.centerX()) / person.width().coerceAtLeast(1f) +
            abs(phone.centerY() - person.centerY()) / person.height().coerceAtLeast(1f)
        val overlap = intersectionArea(phone, person) / max(1f, phone.width() * phone.height())
        return overlap * 3f - centerDistance
    }

    private fun expanded(rect: RectF, fraction: Float): RectF {
        val dx = rect.width() * fraction
        val dy = rect.height() * fraction
        return RectF(rect.left - dx, rect.top - dy, rect.right + dx, rect.bottom + dy)
    }

    private fun intersectionArea(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        return max(0f, right - left) * max(0f, bottom - top)
    }

    private fun distanceToRect(point: PosePoint, rect: RectF): Float {
        val dx = when {
            point.x < rect.left -> rect.left - point.x
            point.x > rect.right -> point.x - rect.right
            else -> 0f
        }
        val dy = when {
            point.y < rect.top -> rect.top - point.y
            point.y > rect.bottom -> point.y - rect.bottom
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }

    private fun distancePointToSegment(point: PosePoint, start: PosePoint, end: PosePoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (dx == 0f && dy == 0f) return distance(point, start)

        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy))
            .coerceIn(0f, 1f)
        val projection = PosePoint(start.x + t * dx, start.y + t * dy, 1f)
        return distance(point, projection)
    }

    private fun distance(a: PosePoint, b: PosePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val MIN_LANDMARK_CONFIDENCE = 0.35f
        private const val MAX_PHONE_MEMORY_FRAMES = 5
    }
}
