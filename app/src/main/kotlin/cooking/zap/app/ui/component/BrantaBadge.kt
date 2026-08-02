package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.repo.BrantaGuardrail
import pro.branta.v2.models.PaymentsResult

/**
 * A "Verified by Branta" badge for payment destinations — mirrors the web
 * client's `BrantaBadge.svelte`. Auto-verifies [paymentString] (or
 * [rawQrText] when provided) on mount; if the destination is registered with
 * Branta Guardrail, shows the platform logo + description, and the whole badge
 * is tappable to the Branta verify page. Renders **nothing** when the
 * destination isn't registered or the lookup fails — absence ≠ malicious.
 *
 * Place in any send/confirm flow where a payment destination is displayed.
 */
@Composable
fun BrantaBadge(
    paymentString: String,
    rawQrText: String? = null,
    modifier: Modifier = Modifier,
) {
    var result by remember(paymentString, rawQrText) {
        mutableStateOf<PaymentsResult?>(null)
    }

    LaunchedEffect(paymentString, rawQrText) {
        val input = rawQrText ?: paymentString
        if (input.isBlank()) return@LaunchedEffect
        result = if (rawQrText != null) {
            BrantaGuardrail.verifyQrCode(rawQrText)
        } else {
            BrantaGuardrail.verifyDestination(paymentString)
        }
    }

    val payments = result?.payments
    if (payments.isNullOrEmpty()) return

    val payment = payments.first()
    val verifyUrl = result?.verifyUrl
    val uriHandler = LocalUriHandler.current

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (verifyUrl != null) Modifier.clickable {
                    runCatching { uriHandler.openUri(verifyUrl) }
                } else Modifier
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val logoUrl = payment.platformLogoUrl
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }

            Text(
                text = payment.description
                    ?: payment.platform
                    ?: "Verified by Branta",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Icon(
                Icons.Default.Verified,
                contentDescription = "Verified",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
