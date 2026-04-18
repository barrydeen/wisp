package com.wisp.app.ml

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class LightGbmModel(private val trees: Array<Tree>) {
    class Tree(
        val splitFeature: IntArray,
        val threshold: FloatArray,
        val leftChild: IntArray,
        val rightChild: IntArray,
        val leafValue: FloatArray
    )

    fun rawMargin(features: FloatArray): Double {
        var sum = 0.0
        for (t in trees) {
            var node = 0
            while (node >= 0) {
                val idx = t.splitFeature[node]
                val x = if (idx < features.size) features[idx] else 0f
                node = if (x <= t.threshold[node]) t.leftChild[node] else t.rightChild[node]
            }
            sum += t.leafValue[-node - 1]
        }
        return sum
    }

    companion object {
        fun parse(stream: InputStream): LightGbmModel {
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val trees = ArrayList<Tree>(512)
            var section: MutableMap<String, String>? = null

            reader.useLines { seq ->
                for (raw in seq) {
                    val line = raw.trim()
                    if (line.startsWith("Tree=")) {
                        section?.let { trees.add(buildTree(it)) }
                        section = HashMap(16)
                    } else if (line == "end of trees") {
                        section?.let { trees.add(buildTree(it)) }
                        section = null
                        break
                    } else {
                        val current = section ?: continue
                        val eq = line.indexOf('=')
                        if (eq > 0) {
                            val key = line.substring(0, eq)
                            if (key in TREE_FIELDS) {
                                current[key] = line.substring(eq + 1)
                            }
                        }
                    }
                }
            }

            return LightGbmModel(trees.toTypedArray())
        }

        private val TREE_FIELDS = setOf(
            "split_feature", "threshold", "left_child", "right_child", "leaf_value"
        )

        private fun buildTree(f: Map<String, String>): Tree = Tree(
            splitFeature = parseInts(f["split_feature"] ?: error("missing split_feature")),
            threshold = parseFloats(f["threshold"] ?: error("missing threshold")),
            leftChild = parseInts(f["left_child"] ?: error("missing left_child")),
            rightChild = parseInts(f["right_child"] ?: error("missing right_child")),
            leafValue = parseFloats(f["leaf_value"] ?: error("missing leaf_value"))
        )

        private fun parseInts(s: String): IntArray {
            val parts = s.split(' ')
            val out = IntArray(parts.size)
            var n = 0
            for (p in parts) {
                if (p.isEmpty()) continue
                out[n++] = p.toInt()
            }
            return if (n == out.size) out else out.copyOf(n)
        }

        private fun parseFloats(s: String): FloatArray {
            val parts = s.split(' ')
            val out = FloatArray(parts.size)
            var n = 0
            for (p in parts) {
                if (p.isEmpty()) continue
                out[n++] = p.toFloat()
            }
            return if (n == out.size) out else out.copyOf(n)
        }
    }
}
