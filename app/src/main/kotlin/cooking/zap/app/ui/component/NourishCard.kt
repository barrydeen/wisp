package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cooking.zap.app.nostr.NourishDimension
import cooking.zap.app.nostr.NourishScore
import kotlin.math.max

/**
 * Nourish health-score card — a Compose port of the web `NourishResult` (the
 * "green island" styling). Pure visual: renders the existing [NourishScore]
 * (concern 2.4a/b) unchanged.
 *
 * Deliberately non-judgmental, mirroring the web: **green only** (never amber /
 * red / letter grade), soft language for low scores, and no per-dimension
 * numeric. A compact overall header anchors the card (the web's quick-take /
 * per-dim reasons / ingredient signals aren't in [NourishScore], so they're
 * omitted). The Nourish greens are ported constants — a green accent
 * independent of the orange brand theme; only structural/neutral colors come
 * from M3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NourishCard(score: NourishScore) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ── Overall header — number + leaf + green, the card's anchor. The
        // stark "Low"/"Fair" grades are suppressed; only the affirming end
        // (Moderate+) shows a textual label.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Eco,
                contentDescription = null,
                tint = NourishGreen.Strong,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Nourish",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${score.overall}/10",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NourishGreen.Strong,
            )
            affirmingLabel(score.overall, score.overallLabel)?.let { label ->
                Spacer(Modifier.size(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NourishGreen.Strong,
                )
            }
        }

        // ── "What this meal brings" — strength pills (top 2–3 dims ≥5).
        val strengths = topStrengths(score.dimensions)
        if (strengths.isNotEmpty()) {
            Spacer(Modifier.size(14.dp))
            SectionLabel("What this meal brings")
            Spacer(Modifier.size(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                strengths.forEach { StrengthPill(it) }
            }
        }

        // ── "Nourish Profile" — 2-column tile grid (8 tiles, 4 rows).
        Spacer(Modifier.size(14.dp))
        SectionLabel("Nourish Profile")
        Spacer(Modifier.size(8.dp))
        score.dimensions.chunked(2).forEach { rowDims ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowDims.forEach { dim ->
                    DimensionTile(dim, Modifier.weight(1f))
                }
                // Keep the last odd tile from stretching full-width (8 is even, so
                // this is just defensive for a legacy <8-dim score).
                if (rowDims.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
        }

        // ── "Simple upgrades" — improvements as left-green-border rows.
        if (score.improvements.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            SectionLabel("Simple upgrades")
            Spacer(Modifier.size(6.dp))
            score.improvements.forEach { tip -> UpgradeRow(tip) }
        }

        // ── Footer disclaimer.
        Spacer(Modifier.size(14.dp))
        Text(
            text = "Profiles are estimates based on ingredients. Not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun StrengthPill(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(NourishGreen.Strong.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Outlined.Eco, contentDescription = null, tint = NourishGreen.Strong, modifier = Modifier.size(12.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = NourishGreen.Strong,
        )
    }
}

@Composable
private fun DimensionTile(dim: NourishDimension, modifier: Modifier) {
    val meta = DIMENSION_META[dim.name] ?: DimMeta("🌱", dim.name, dim.name)
    val tier = tierFor(dim.score)
    Column(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meta.icon, fontSize = 14.sp)
            Spacer(Modifier.size(4.dp))
            Text(
                text = meta.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(6.dp))
        // Green-tier bar — track + fraction-width fill. No numeric (faithful).
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            val fraction = if (dim.score == 0) 0f else max(0.12f, dim.score * 0.10f)
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(tier.color.copy(alpha = tier.alpha)),
                )
            }
        }
        // Soft language for low scores — never a stark number.
        softLabel(dim.score)?.let { soft ->
            Spacer(Modifier.size(4.dp))
            Text(
                text = soft,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun UpgradeRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        // Left green border accent (the web's `border-left`).
        Box(
            Modifier
                .width(2.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(NourishGreen.Strong.copy(alpha = 0.22f)),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Nourish palette (ported web constants — independent of the orange brand) ──
private object NourishGreen {
    val Strong = Color(0xFF22C55E)
    val Moderate = Color(0xFF4ADE80)
    val Light = Color(0xFF86EFAC)
}

private data class Tier(val color: Color, val alpha: Float)

private fun tierFor(score: Int): Tier = when {
    score >= 7 -> Tier(NourishGreen.Strong, 1.0f)
    score >= 4 -> Tier(NourishGreen.Moderate, 0.85f)
    else -> Tier(NourishGreen.Light, 0.55f)
}

private fun softLabel(score: Int): String? = when {
    score == 0 -> "Not a focus here"
    score <= 2 -> "Lightly present"
    else -> null
}

/** Suppress the stark "Low"/"Fair" grades; show only the affirming end (Moderate+). */
private fun affirmingLabel(overall: Int, label: String): String? =
    label.takeIf { overall >= 5 }

/** Top 2–3 dimensions scoring ≥5, by their affirming strength label. */
private fun topStrengths(dimensions: List<NourishDimension>): List<String> =
    dimensions
        .filter { it.score >= 5 }
        .sortedByDescending { it.score }
        .take(3)
        .mapNotNull { DIMENSION_META[it.name]?.strength }

private data class DimMeta(val icon: String, val label: String, val strength: String)

// Keyed by the [NourishScore] dimension names (NourishParser order). Icons +
// friendly labels + affirming strength labels mirror the web registry.
private val DIMENSION_META: Map<String, DimMeta> = mapOf(
    "Real Food" to DimMeta("🥬", "Real Food", "Whole foods"),
    "Gut" to DimMeta("🌱", "Gut Health", "Gut-friendly"),
    "Protein" to DimMeta("💪", "Protein", "Protein-rich"),
    "Anti-Inflammatory" to DimMeta("🧘", "Anti-inflammatory", "Anti-inflammatory"),
    "Blood Sugar" to DimMeta("⚖️", "Blood Sugar", "Steady energy"),
    "Immune" to DimMeta("🛡️", "Immune-supportive", "Immune-supporting"),
    "Brain" to DimMeta("🧠", "Brain Health", "Brain-supporting"),
    "Heart" to DimMeta("🫀", "Heart-healthy", "Heart-healthy"),
)
