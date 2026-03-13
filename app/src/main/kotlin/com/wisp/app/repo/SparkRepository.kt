package com.wisp.app.repo

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import breez_sdk_spark.CheckLightningAddressRequest
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.RegisterLightningAddressRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.SyncWalletRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wisp.app.BuildConfig
import java.io.File
import java.security.SecureRandom

class SparkRepository(
    private val context: Context,
    pubkeyHex: String? = null
) : WalletProvider {
    private val TAG = "SparkRepository"

    companion object {
        private val BREEZ_API_KEY: String get() = BuildConfig.BREEZ_API_KEY

        // BIP39 English wordlist subset is large; for mnemonic generation we use
        // the SDK's Seed.Mnemonic which validates the mnemonic on connect.
        // We generate a 16-byte entropy and convert to mnemonic externally.
        // For now, we'll generate a random 12-word phrase placeholder that the user
        // should replace with a proper BIP39 mnemonic from the SDK's built-in generator.

        private val BIP39_WORDS: List<String> by lazy {
            // Load the BIP39 wordlist from the bundled resource, or use a minimal fallback
            try {
                val stream = SparkRepository::class.java.getResourceAsStream("/bip39-english.txt")
                stream?.bufferedReader()?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encPrefs = EncryptedSharedPreferences.create(
        context,
        if (pubkeyHex != null) "wisp_spark_$pubkeyHex" else "wisp_spark",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private var sdk: breez_sdk_spark.BreezSdk? = null
    private var eventListenerId: String? = null
    private var scope: CoroutineScope? = null

    private val _balance = MutableStateFlow<Long?>(null)
    override val balance: StateFlow<Long?> = _balance

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusLog = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val statusLog: SharedFlow<String> = _statusLog

    private val _paymentReceived = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    override val paymentReceived: SharedFlow<Long> = _paymentReceived

    private fun emitStatus(msg: String) {
        Log.d(TAG, msg)
        _statusLog.tryEmit(msg)
    }

    // --- Mnemonic management ---

    fun hasMnemonic(): Boolean = encPrefs.getString("spark_mnemonic", null) != null

    override fun hasConnection(): Boolean = hasMnemonic()

    fun newMnemonic(): String {
        val wordlist = BIP39_WORDS
        if (wordlist.size < 2048) {
            // Fallback: generate 16 random bytes and hex-encode as a placeholder.
            // The user should provide a proper BIP39 mnemonic via restore.
            error("BIP39 wordlist not available. Bundle bip39-english.txt in resources.")
        }
        val random = SecureRandom()
        val entropy = ByteArray(16) // 128 bits → 12 words
        random.nextBytes(entropy)
        return entropyToMnemonic(entropy, wordlist)
    }

    private fun entropyToMnemonic(entropy: ByteArray, wordlist: List<String>): String {
        // SHA-256 hash of entropy for checksum
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4 // 4 bits for 16 bytes

        // Convert entropy + checksum to bits
        val bits = StringBuilder()
        for (b in entropy) bits.append(String.format("%8s", Integer.toBinaryString(b.toInt() and 0xFF)).replace(' ', '0'))
        val hashBits = String.format("%8s", Integer.toBinaryString(hash[0].toInt() and 0xFF)).replace(' ', '0')
        bits.append(hashBits.substring(0, checksumBits))

        // Split into 11-bit groups
        val words = mutableListOf<String>()
        val bitStr = bits.toString()
        for (i in bitStr.indices step 11) {
            val end = minOf(i + 11, bitStr.length)
            val index = Integer.parseInt(bitStr.substring(i, end), 2)
            words.add(wordlist[index])
        }
        return words.joinToString(" ")
    }

    fun saveMnemonic(mnemonic: String) {
        encPrefs.edit().putString("spark_mnemonic", mnemonic).apply()
    }

    fun getMnemonic(): String? = encPrefs.getString("spark_mnemonic", null)

    fun clearMnemonic() {
        encPrefs.edit().remove("spark_mnemonic").apply()
        _balance.value = null
        _isConnected.value = false
    }

    // --- SDK lifecycle ---

    private val storageDir: File
        get() = File(context.filesDir, "spark_data").also { it.mkdirs() }

    override fun connect() {
        val mnemonic = getMnemonic() ?: run {
            emitStatus("No mnemonic configured")
            return
        }

        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            try {
                emitStatus("Initializing Spark SDK...")

                val config = defaultConfig(Network.MAINNET)
                config.apiKey = BREEZ_API_KEY
                config.supportLnurlVerify = true

                val seed = Seed.Mnemonic(mnemonic, null)
                val request = ConnectRequest(
                    config = config,
                    seed = seed,
                    storageDir = storageDir.absolutePath
                )

                val instance = connect(request)
                sdk = instance

                // Register event listener
                val listener = object : EventListener {
                    override suspend fun onEvent(e: SdkEvent) {
                        when (e) {
                            is SdkEvent.Synced -> {
                                emitStatus("Synced")
                            }
                            is SdkEvent.PaymentSucceeded -> {
                                emitStatus("Payment succeeded")
                                refreshBalanceInternal()
                                if (e.payment.paymentType == PaymentType.RECEIVE) {
                                    _paymentReceived.tryEmit(e.payment.amount.toLong() * 1000)
                                }
                            }
                            is SdkEvent.PaymentFailed -> {
                                emitStatus("Payment failed")
                            }
                            is SdkEvent.PaymentPending -> {
                                emitStatus("Payment pending")
                            }
                            else -> {}
                        }
                    }
                }
                eventListenerId = instance.addEventListener(listener)

                _isConnected.value = true
                emitStatus("Connected to Spark")

                refreshBalanceInternal()
            } catch (e: Exception) {
                emitStatus("Connection failed: ${e.message}")
                Log.e(TAG, "Spark connect failed", e)
                _isConnected.value = false
            }
        }
    }

    override fun disconnect() {
        val instance = sdk
        val listenerId = eventListenerId
        sdk = null
        eventListenerId = null
        scope?.cancel()
        scope = null
        _isConnected.value = false

        // Clean up native SDK on a standalone scope so cancelling our main scope
        // doesn't kill the teardown coroutine
        if (instance != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (listenerId != null) {
                        instance.removeEventListener(listenerId)
                    }
                    instance.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Spark disconnect error", e)
                }
            }
        }
    }

    // --- Balance ---

    private suspend fun refreshBalanceInternal() {
        try {
            val instance = sdk ?: return
            val info = instance.getInfo(GetInfoRequest(ensureSynced = false))
            _balance.value = info.balanceSats.toLong() * 1000 // convert sats to msats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh balance", e)
        }
    }

    override suspend fun fetchBalance(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
            val info = instance.getInfo(GetInfoRequest(ensureSynced = false))
            val balanceMsats = info.balanceSats.toLong() * 1000
            _balance.value = balanceMsats
            Result.success(balanceMsats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Send ---

    override suspend fun payInvoice(bolt11: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
            emitStatus("Preparing payment...")

            val prepareReq = PrepareSendPaymentRequest(paymentRequest = bolt11)
            val prepareResponse = instance.prepareSendPayment(prepareReq)

            emitStatus("Sending payment...")
            val options = SendPaymentOptions.Bolt11Invoice(
                preferSpark = false,
                completionTimeoutSecs = 30u
            )
            val sendResponse = instance.sendPayment(
                SendPaymentRequest(prepareResponse, options)
            )

            val paymentId = sendResponse.payment.id
            emitStatus("Payment sent")
            Result.success(paymentId)
        } catch (e: Exception) {
            emitStatus("Payment failed: ${e.message}")
            Result.failure(e)
        }
    }

    // --- Receive ---

    override suspend fun makeInvoice(amountMsats: Long, description: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
                emitStatus("Creating invoice...")

                val amountSats = (amountMsats / 1000).toULong()
                val method = ReceivePaymentMethod.Bolt11Invoice(
                    description = description.ifEmpty { "Wisp wallet" },
                    amountSats = amountSats,
                    expirySecs = 3600u,
                    paymentHash = null
                )
                val response = instance.receivePayment(ReceivePaymentRequest(method))
                emitStatus("Invoice created")
                Result.success(response.paymentRequest)
            } catch (e: Exception) {
                emitStatus("Invoice creation failed: ${e.message}")
                Result.failure(e)
            }
        }

    // --- Sync polling ---

    /** Trigger an SDK sync to speed up payment detection. */
    suspend fun syncWallet() {
        withContext(Dispatchers.IO) {
            try {
                sdk?.syncWallet(SyncWalletRequest)
            } catch (e: Exception) {
                Log.d(TAG, "Sync failed: ${e.message}")
            }
        }
    }

    // --- Transactions ---

    override suspend fun listTransactions(limit: Int): Result<List<WalletTransaction>> =
        withContext(Dispatchers.IO) {
            try {
                val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
                val response = instance.listPayments(ListPaymentsRequest(
                    limit = limit.toUInt(),
                    sortAscending = false
                ))
                val transactions = response.payments.map { payment ->
                    val lightningDetails = payment.details as? PaymentDetails.Lightning
                    val description = lightningDetails?.description

                    // Extract payment hash from bolt11 invoice (Spark uses UUIDs as payment.id)
                    val bolt11Hash = lightningDetails?.invoice?.let {
                        com.wisp.app.nostr.Bolt11.decode(it)?.paymentHash
                    }

                    WalletTransaction(
                        type = when (payment.paymentType) {
                            PaymentType.SEND -> "outgoing"
                            else -> "incoming"
                        },
                        description = description,
                        paymentHash = bolt11Hash ?: payment.id,
                        amountMsats = payment.amount.toLong() * 1000,
                        feeMsats = payment.fees.toLong() * 1000,
                        createdAt = payment.timestamp.toLong(),
                        settledAt = payment.timestamp.toLong()
                    )
                }
                Result.success(transactions)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- Lightning Address ---

    suspend fun getLightningAddress(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
            val info = instance.getLightningAddress()
            Result.success(info?.lightningAddress)
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun checkLightningAddressAvailable(username: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
                val available = instance.checkLightningAddressAvailable(
                    CheckLightningAddressRequest(username)
                )
                Result.success(available)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteLightningAddress(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
            instance.deleteLightningAddress()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerLightningAddress(
        username: String,
        description: String = "Wisp wallet"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val instance = sdk ?: return@withContext Result.failure(Exception("Not connected"))
            val info = instance.registerLightningAddress(
                RegisterLightningAddressRequest(username, description)
            )
            Result.success(info.lightningAddress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
