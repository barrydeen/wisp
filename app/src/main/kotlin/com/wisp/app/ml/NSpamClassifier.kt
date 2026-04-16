package com.wisp.app.ml

import kotlin.math.exp

class NSpamClassifier(private val weights: NSpamWeights) {
    fun score(notes: List<NoteInput>): Float? {
        if (notes.isEmpty()) return null
        val capped = if (notes.size > 10) notes.sortedByDescending { it.createdAt }.take(10) else notes
        val features = NSpamFeatures.extractFeatures(capped)
        val rawScore = sigmoid(dotProduct(features, weights.coef) + weights.intercept)
        return calibrate(rawScore, weights.calibX, weights.calibY)
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            sum += a[i].toDouble() * b[i].toDouble()
        }
        return sum.toFloat()
    }

    private fun sigmoid(x: Float): Float {
        val ex = exp(-x.toDouble())
        return (1.0 / (1.0 + ex)).toFloat()
    }

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
