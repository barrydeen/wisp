package cooking.zap.app.repo

import pro.branta.BrantaClientOptions
import pro.branta.enums.BrantaServerBaseUrl
import pro.branta.enums.PrivacyMode
import pro.branta.v2.BrantaService
import pro.branta.v2.models.PaymentsResult

/**
 * Send-side Branta Guardrail integration — verifies a payment destination
 * (Lightning invoice, on-chain address) against Branta's registry before the
 * user sends. If the destination is registered, returns platform attribution
 * (logo, description, verifyUrl) to show a "Verified by Branta" badge.
 *
 * Matches the web client's [BrantaBadge] behavior: silent on failure (null on
 * error/exception — absence ≠ malicious; never show "not verified"). Uses
 * [PrivacyMode.Strict] (ZK lookups only; plain on-chain addresses pasted
 * without a QR return empty, which is correct — bolt11/ark/silent-payment work).
 */
object BrantaGuardrail {

    private val service = BrantaService(
        BrantaClientOptions(
            baseUrl = BrantaServerBaseUrl.Production,
            privacy = PrivacyMode.Strict,
        )
    )

    /**
     * Verify a scanned QR code's payload. Preferred over [verifyDestination] —
     * handles multi-value ZK QR payloads correctly. Returns null on error or
     * when the destination isn't registered.
     */
    suspend fun verifyQrCode(qrText: String): PaymentsResult? = try {
        service.getPaymentsByQrCode(qrText)
    } catch (_: Exception) {
        null
    }

    /**
     * Verify a pasted destination string (invoice, address, LNURL). Returns
     * null on error or when not registered. In Strict mode, only self-encrypted
     * types (bolt11, ark, silent payment) return results.
     */
    suspend fun verifyDestination(destination: String): PaymentsResult? = try {
        service.getPayments(destination)
    } catch (_: Exception) {
        null
    }
}
