package com.wisp.app.ml

import kotlin.math.exp

class NSpamClassifier(private val weights: NSpamWeights) {
    fun score(notes: List<NoteInput>): Float? {
        if (notes.isEmpty()) return null
        val capped = if (notes.size > 10) notes.sortedByDescending { it.createdAt }.take(10) else notes
        val features = NSpamFeatures.extractFeatures(capped)
        val margin = weights.model.rawMargin(features)
        val rawScore = sigmoid(margin).toFloat()
        return calibrate(rawScore, weights.calibX, weights.calibY)
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun calibrate(raw: Float, cx: FloatArray, cy: FloatArray): Float {
        if (cx.isEmpty()) return raw
        if (raw <= cx[0]) return cy[0]
        if (raw >= cx.last()) return cy.last()
        for (i in 0 until cx.size - 1) {
            if (raw >= cx[i] && raw < cx[i + 1]) {
                val t = (raw - cx[i]) / (cx[i + 1] - cx[i])
                return cy[i] + t * (cy[i + 1] - cy[i])
            }
        }
        return cy.last()
    }
}
