package cooking.zap.app

/** Central toggles for features not yet ready to ship. */
object FeatureFlags {
    /**
     * Note translation is powered by ML Kit, which depends on Google Play Services.
     * The zapstore (non-Play) build can't rely on that, so the translation UI is
     * hidden until it's reliably available. Flip to true to re-enable it everywhere.
     */
    const val TRANSLATION_ENABLED = false

    /**
     * Cheffy Note Photo Review (CHEFFY_NOTE_REVIEW_PLAN.md) — dark-shipped
     * while Phases 2–5 land. Gates the PostCard "Ask Cheffy about this dish"
     * menu entry and the draft modal. Flip in Phase 6.
     */
    const val NOTE_REVIEW_ENABLED = false
}
