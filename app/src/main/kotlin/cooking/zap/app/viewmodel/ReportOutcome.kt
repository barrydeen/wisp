package cooking.zap.app.viewmodel

/**
 * What a NIP-56 report submission did, and therefore what the reporter is told.
 *
 * [hidesReportedMessage] lives on the outcome rather than at the call site so the two can't drift.
 * The reported content disappearing is what the reporter reads as confirmation, so it may only
 * disappear once the relay has taken the event — a disappearance is not a receipt, and hiding it
 * on a failure also removes the only thing left to retry from.
 */
enum class ReportOutcome(val hidesReportedMessage: Boolean) {
    /** The relay accepted the signed event. Not a claim that a moderator has seen it. */
    SENT(true),

    /** Signing or the send failed. The reporter can try again. */
    FAILED(false),

    /** The account holds no signing key, so it cannot report. Distinct from [FAILED]: nothing
     *  went wrong and retrying as-is won't help. */
    NEEDS_KEY(false);

    companion object {
        /**
         * Classify a submission.
         *
         * @param hasSigner the active account can sign (READ_ONLY, and a LOCAL/REMOTE account whose
         *        key or signer package is missing, cannot).
         * @param hasRelay the room's relay pool is initialised.
         * @param relayAccepted the relay took the event. Only meaningful when the first two hold.
         */
        fun of(hasSigner: Boolean, hasRelay: Boolean, relayAccepted: Boolean): ReportOutcome = when {
            !hasSigner -> NEEDS_KEY
            !hasRelay || !relayAccepted -> FAILED
            else -> SENT
        }
    }
}
