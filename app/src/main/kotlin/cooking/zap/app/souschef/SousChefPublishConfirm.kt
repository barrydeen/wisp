package cooking.zap.app.souschef

/**
 * Pure enablement + copy for the Sous Chef Publish confirm step.
 * The UI must open a dialog when [shouldOpenConfirm] is true and only call
 * [cooking.zap.app.viewmodel.SousChefViewModel.publish] from the dialog's
 * confirm button — never from the primary tap itself.
 */
object SousChefPublishConfirm {
    const val MESSAGE =
        "Publish to your followers? This posts the recipe publicly under your account."

    fun shouldOpenConfirm(canSign: Boolean, hasImage: Boolean, publishing: Boolean): Boolean =
        canSign && hasImage && !publishing
}
