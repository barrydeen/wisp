package com.wisp.app.relay

import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Proxy

enum class TorStatus { DISABLED, STARTING, CONNECTED, ERROR }

object TorManager {
    private const val TAG = "TorManager"

    private val _status = MutableStateFlow(TorStatus.DISABLED)
    val status: StateFlow<TorStatus> = _status

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort

    val proxy: Proxy?
        get() {
            val port = _socksPort.value ?: return null
            if (_status.value != TorStatus.CONNECTED) return null
            return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        }

    fun isEnabled(): Boolean = _status.value == TorStatus.CONNECTED

    private var runtime: TorRuntime? = null

    fun initialize(context: Context) {
        if (runtime != null) return

        val workDir = context.filesDir.resolve("tor")
        val cacheDir = context.cacheDir.resolve("tor")
        workDir.mkdirs()
        cacheDir.mkdirs()

        val environment = TorRuntime.Environment.Builder(
            workDir, cacheDir, ResourceLoaderTorExec::getOrCreate
        )

        runtime = TorRuntime.Builder(environment) {
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                val socksLine = listeners.toString()
                Log.d(TAG, "Listeners update: $socksLine")
                val port = parseSocksPort(socksLine)
                if (port != null) {
                    _socksPort.value = port
                    if (_status.value == TorStatus.STARTING) {
                        _status.value = TorStatus.CONNECTED
                        Log.d(TAG, "Tor connected on SOCKS port $port")
                    }
                }
            }

            observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { state ->
                Log.d(TAG, "State: $state")
            }

            TorEvent.entries().forEach { event ->
                observerStatic(event, OnEvent.Executor.Immediate) { data ->
                    Log.d(TAG, "TorEvent[$event]: $data")
                }
            }

            required(TorEvent.ERR)
            required(TorEvent.WARN)

            config { environment ->
                TorOption.SocksPort.configure { auto() }
            }
        }
    }

    suspend fun start() {
        val rt = runtime ?: run {
            Log.e(TAG, "TorManager not initialized")
            _status.value = TorStatus.ERROR
            return
        }
        if (_status.value == TorStatus.CONNECTED || _status.value == TorStatus.STARTING) return

        _status.value = TorStatus.STARTING
        _socksPort.value = null
        try {
            rt.startDaemonAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Tor: ${e.message}", e)
            _status.value = TorStatus.ERROR
        }
    }

    suspend fun stop() {
        val rt = runtime ?: return
        try {
            rt.stopDaemonAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Tor: ${e.message}", e)
        }
        _socksPort.value = null
        _status.value = TorStatus.DISABLED
    }

    private fun parseSocksPort(listenersString: String): Int? {
        // TorListeners.toString() contains socks addresses like "127.0.0.1:9050"
        // Try to extract port from common patterns
        val regex = Regex("""127\.0\.0\.1:(\d+)""")
        return regex.find(listenersString)?.groupValues?.get(1)?.toIntOrNull()
    }
}
