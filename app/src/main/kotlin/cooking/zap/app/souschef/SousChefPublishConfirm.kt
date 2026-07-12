package cooking.zap.app.souschef

/**
 * Pure enablement + copy for the Sous Chef Publish / Cookbook-save confirm steps.
 * The UI must open a dialog when [shouldOpenConfirm] is true and only call
 * [cooking.zap.app.viewmodel.SousChefViewModel.publish] /
 * [cooking.zap.app.viewmodel.SousChefViewModel.saveToCookbook] from the dialog's
 * confirm button — never from the primary tap itself.
 */
object SousChefPublishConfirm {
    const val MESSAGE =
        "Publish to your followers? This posts the recipe publicly under your account."

    /** Honest copy: Cookbook membership requires a published a-coordinate. */
    const val COOKBOOK_SAVE_MESSAGE =
        "Save to your Cookbook? This publishes the recipe publicly under your account and adds it to your Saved list."

    fun shouldOpenConfirm(canSign: Boolean, hasImage: Boolean, publishing: Boolean): Boolean =
        canSign && hasImage && !publishing

    /** Format-agnostic cookbook a-coordinate (`kind:pubkey:dTag`). */
    fun cookbookCoordinate(kind: Int, author: String, dTag: String): String =
        "$kind:$author:$dTag"
}
