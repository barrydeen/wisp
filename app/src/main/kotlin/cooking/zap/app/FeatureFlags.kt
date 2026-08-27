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
     * Cheffy Note Photo Review (CHEFFY_NOTE_REVIEW_PLAN.md) — gates the
     * PostCard "Ask Cheffy about this dish" menu entry and the draft
     * modal. Flipped ON for release in Phase 6; the flag is RETAINED as
     * the kill switch (web precedent: `NOTE_REVIEW_POST_ENABLED` kept
     * post-launch) — do not delete it or inline its call sites.
     */
    const val NOTE_REVIEW_ENABLED = true

    /**
     * Plan with Cheffy (meal-planner port Phase 5). Entry points in the
     * planner header and empty banner. Enabled 2026-08-26 after the A14
     * device checklist passed — see #236 for the full results.
     */
    const val CHEFFY_MEAL_PLAN_ENABLED = true
}
