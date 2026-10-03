package fr.alerteresidents.domain

import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.Resident

/** Ce que le moteur doit faire après une nouvelle position. */
enum class TransitionEvent { NONE, EXIT_PENDING, EXIT_CONFIRMED, RETURNED, REMINDER }

data class TransitionResult(val resident: Resident, val event: TransitionEvent)

/**
 * Logique pure de transition dedans/dehors, avec confirmation de sortie (hystérésis) pour éviter
 * les fausses alertes dues à l'imprécision GPS en bordure de zone.
 *
 * Une sortie est confirmée si :
 *  - le fix est hors zone même en retirant sa marge d'imprécision, ou
 *  - un second fix (différent) est aussi hors zone, ou
 *  - la sortie est en attente depuis [CONFIRM_DELAY_MS] sans nouveau fix.
 */
object ZoneTransition {
    const val CONFIRM_DELAY_MS = 60_000L

    fun apply(
        prev: Resident,
        eval: ZoneEvaluation,
        fixId: String,
        now: Long,
        reminderMs: Long,
        alarmRinging: Boolean
    ): TransitionResult {
        if (prev.isInZone) {
            if (eval.inside) {
                return TransitionResult(prev.copy(pendingExitFixId = null, pendingExitAt = null), TransitionEvent.NONE)
            }
            val pendingAt = prev.pendingExitAt
            val confirmed = eval.clearlyOutside ||
                (prev.pendingExitFixId != null && prev.pendingExitFixId != fixId) ||
                (pendingAt != null && now - pendingAt >= CONFIRM_DELAY_MS)
            return if (confirmed) {
                TransitionResult(
                    prev.copy(
                        isInZone = false,
                        exitedAt = now,
                        alertState = AlertState.ACTIVE,
                        alertHandledBy = null,
                        alertHandledAt = null,
                        lastAlarmAt = now,
                        pendingExitFixId = null,
                        pendingExitAt = null
                    ),
                    TransitionEvent.EXIT_CONFIRMED
                )
            } else {
                TransitionResult(
                    prev.copy(pendingExitFixId = prev.pendingExitFixId ?: fixId, pendingExitAt = pendingAt ?: now),
                    TransitionEvent.EXIT_PENDING
                )
            }
        }

        // Déjà dehors
        if (eval.inside) {
            return TransitionResult(
                prev.copy(
                    isInZone = true,
                    exitedAt = null,
                    alertState = AlertState.NONE,
                    alertHandledBy = null,
                    alertHandledAt = null,
                    lastAlarmAt = null,
                    pendingExitFixId = null,
                    pendingExitAt = null
                ),
                TransitionEvent.RETURNED
            )
        }
        val last = prev.lastAlarmAt
        val needsReminder = prev.alertState == AlertState.ACTIVE && !alarmRinging &&
            (last == null || now - last >= reminderMs)
        return if (needsReminder) {
            TransitionResult(prev.copy(lastAlarmAt = now), TransitionEvent.REMINDER)
        } else {
            TransitionResult(prev, TransitionEvent.NONE)
        }
    }
}
