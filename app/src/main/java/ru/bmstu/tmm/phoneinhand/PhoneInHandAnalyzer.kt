package ru.bmstu.tmm.phoneinhand

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PhoneInHandAnalyzer {
    private var positiveStreak = 0
    private var negativeStreak = 0
    private var noPhoneStreak = 0
    private var lastState = PhoneState.NO_PHONE
    private var trackedPhone: DetectionBox? = null

    fun analyze(
        detections: List<DetectionBox>,
        handPose: HandPose?,
        inferenceTimeMs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): PhoneAnalysis {
        val phoneDetections = detections
            .filter { it.label.isPhoneLabel() }
            .sortedByDescending { it.score }
        val phoneLikeDetections = detections
            .filter { it.label == PHONE_LIKE_LABEL }
            .sortedByDescending { it.score }
        val people = detections
            .filter { it.label.equals("person", ignoreCase = true) }
            .sortedByDescending { it.score }

        val detectedPhone = choosePhone(phoneDetections, phoneLikeDetections)
        val phone = updateTrackedPhone(detectedPhone)
        if (phone == null) {
            noPhoneStreak++
            negativeStreak++
            positiveStreak = 0
            val state = if (noPhoneStreak >= NO_PHONE_CONFIRM_FRAMES) PhoneState.NO_PHONE else lastState
            lastState = state
            return PhoneAnalysis(
                state = state,
                phone = null,
                person = null,
                confidence = 0f,
                reason = "Телефон не обнаружен",
                inferenceTimeMs = inferenceTimeMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                handPose = handPose,
                allDetections = detections
            )
        }

        val linkedPerson = people.maxByOrNull { personAssociationScore(phone.rect, it.rect) }
        val reliableHands = handPose?.takeIf { it.hasReliableHand() }
        val handContact = reliableHands?.let { isPhoneNearHandLandmarks(phone.rect, it, linkedPerson) } ?: false
        val fallbackContact = reliableHands == null && linkedPerson != null && isPhoneInHandZone(phone.rect, linkedPerson.rect)
        val rawInHand = handContact || fallbackContact

        if (rawInHand) {
            positiveStreak++
            negativeStreak = 0
        } else {
            negativeStreak++
            positiveStreak = 0
        }

        val smoothedState = when {
            positiveStreak >= IN_HAND_CONFIRM_FRAMES -> PhoneState.PHONE_IN_HAND
            negativeStreak >= VISIBLE_CONFIRM_FRAMES -> PhoneState.PHONE_VISIBLE
            else -> lastState.takeIf { it != PhoneState.NO_PHONE } ?: PhoneState.PHONE_VISIBLE
        }
        lastState = smoothedState

        val reason = when (smoothedState) {
            PhoneState.PHONE_IN_HAND -> if (handContact) {
                "Телефон рядом с кистью"
            } else {
                "Телефон находится в зоне руки"
            }
            PhoneState.PHONE_VISIBLE -> if (reliableHands == null) {
                "Телефон найден, кисть не видна"
            } else {
                "Телефон найден, контакта с кистью нет"
            }
            PhoneState.NO_PHONE -> "Телефон не обнаружен"
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
            handPose = reliableHands,
            allDetections = detections
        )
    }

    private fun choosePhone(phones: List<DetectionBox>, phoneLikes: List<DetectionBox>): DetectionBox? {
        val previous = trackedPhone ?: return phones.first()
        val best = phones.maxByOrNull { phoneTrackingScore(previous.rect, it.rect, it.score) }
        if (best != null && phoneTrackingScore(previous.rect, best.rect, best.score) > TRACK_MATCH_THRESHOLD) {
            return best
        }
        if (phones.isNotEmpty()) {
            return phones.firstOrNull { it.score >= STRONG_PHONE_SCORE }
        }

        val phoneLikeTrack = phoneLikes
            .maxByOrNull { phoneLikeTrackingScore(previous.rect, it.rect, it.score) }
            ?.takeIf { phoneLikeTrackingScore(previous.rect, it.rect, it.score) > PHONE_LIKE_TRACK_THRESHOLD }
        if (phoneLikeTrack != null) {
            return phoneLikeTrack.copy(label = "cell phone", score = min(phoneLikeTrack.score, previous.score * 0.88f))
        }
        return null
    }

    private fun updateTrackedPhone(detectedPhone: DetectionBox?): DetectionBox? {
        if (detectedPhone == null) {
            noPhoneStreak++
            if (noPhoneStreak > MAX_PHONE_MEMORY_FRAMES) {
                trackedPhone = null
                return null
            }
            return trackedPhone
        }

        noPhoneStreak = 0
        val previous = trackedPhone
        val smoothed = if (previous != null) {
            detectedPhone.copy(
                score = max(detectedPhone.score, previous.score * 0.92f),
                rect = smoothRect(previous.rect, detectedPhone.rect, PHONE_SMOOTHING)
            )
        } else {
            detectedPhone
        }
        trackedPhone = smoothed
        return smoothed
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

    private fun isPhoneNearHandLandmarks(phone: RectF, handPose: HandPose, person: DetectionBox?): Boolean {
        val phoneSize = max(phone.width(), phone.height()).coerceAtLeast(1f)
        val personWidth = person?.rect?.width()?.coerceAtLeast(1f) ?: phoneSize * 4f
        val expandedPhone = expanded(phone, 0.55f)
        val pointThreshold = max(phoneSize * 0.95f, personWidth * 0.055f).coerceAtLeast(24f)
        val wristThreshold = max(phoneSize * 1.20f, personWidth * 0.075f).coerceAtLeast(30f)
        val palmThreshold = max(phoneSize * 1.05f, personWidth * 0.065f).coerceAtLeast(26f)

        val fingerContact = handPose.fingerPoints.any { point ->
            expandedPhone.contains(point.x, point.y) || distanceToRect(point, phone) <= pointThreshold
        }
        if (fingerContact) return true

        val palmContact = handPose.palmPoints.any { point ->
            distanceToRect(point, phone) <= palmThreshold
        }
        if (palmContact) return true

        return handPose.wristPoints.any { point ->
            distanceToRect(point, phone) <= wristThreshold
        }
    }

    private fun phoneTrackingScore(previous: RectF, current: RectF, score: Float): Float {
        val iouScore = iou(previous, current)
        val diagonal = sqrt(previous.width() * previous.width() + previous.height() * previous.height()).coerceAtLeast(1f)
        val centerDistance = distance(previous.centerX(), previous.centerY(), current.centerX(), current.centerY()) / diagonal
        return iouScore * 2.2f + score - centerDistance * 0.75f
    }

    private fun phoneLikeTrackingScore(previous: RectF, current: RectF, score: Float): Float {
        val sizeRatio = min(previous.area(), current.area()) / max(previous.area(), current.area()).coerceAtLeast(1f)
        val aspectRatio = min(previous.aspectRatio(), current.aspectRatio()) / max(previous.aspectRatio(), current.aspectRatio()).coerceAtLeast(0.1f)
        val centerDistance = distance(previous.centerX(), previous.centerY(), current.centerX(), current.centerY())
        val maxAllowedShift = max(previous.width(), previous.height()) * 0.55f
        if (centerDistance > maxAllowedShift) return -1f
        if (sizeRatio < 0.55f || aspectRatio < 0.70f) return -1f
        return phoneTrackingScore(previous, current, score) + sizeRatio * 0.45f + aspectRatio * 0.35f
    }

    private fun smoothRect(previous: RectF, current: RectF, alpha: Float): RectF {
        return RectF(
            previous.left * (1f - alpha) + current.left * alpha,
            previous.top * (1f - alpha) + current.top * alpha,
            previous.right * (1f - alpha) + current.right * alpha,
            previous.bottom * (1f - alpha) + current.bottom * alpha
        )
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

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = intersectionArea(a, b)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
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

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun RectF.area(): Float = width() * height()

    private fun RectF.aspectRatio(): Float {
        val shortSide = min(width(), height()).coerceAtLeast(1f)
        val longSide = max(width(), height()).coerceAtLeast(1f)
        return longSide / shortSide
    }

    companion object {
        private const val IN_HAND_CONFIRM_FRAMES = 2
        private const val VISIBLE_CONFIRM_FRAMES = 3
        private const val NO_PHONE_CONFIRM_FRAMES = 3
        private const val MAX_PHONE_MEMORY_FRAMES = 5
        private const val PHONE_SMOOTHING = 0.55f
        private const val STRONG_PHONE_SCORE = 0.30f
        private const val TRACK_MATCH_THRESHOLD = 0.10f
        private const val PHONE_LIKE_TRACK_THRESHOLD = 1.15f
        private const val PHONE_LIKE_LABEL = "phone-like"
    }
}
