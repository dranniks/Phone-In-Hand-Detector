package ru.bmstu.tmm.phoneinhand

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PhoneInHandAnalyzer {
    private var positiveStreak = 0
    private var negativeStreak = 0
    private var lastState = PhoneState.NO_PHONE

    fun analyze(
        detections: List<DetectionBox>,
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

        val phone = phones.firstOrNull()
        if (phone == null) {
            negativeStreak++
            positiveStreak = 0
            val state = if (negativeStreak >= 2) PhoneState.NO_PHONE else lastState
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
                allDetections = detections
            )
        }

        val linkedPerson = people.maxByOrNull { personAssociationScore(phone.rect, it.rect) }
        val rawInHand = linkedPerson != null && isPhoneInHandZone(phone.rect, linkedPerson.rect)

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
            PhoneState.PHONE_IN_HAND -> "Телефон связан с областью рук человека"
            PhoneState.PHONE_VISIBLE -> "Телефон найден, связь с руками не подтверждена"
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
}
