package com.telegramproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xray-core controller.
 *
 * Strategy:
 * 1) Prefer native library via reflection (libxray / AndroidLibXrayLite / go mobile).
 * 2) Fallback: run `libxray.so` / bundled `xray` binary from nativeLibraryDir or filesDir.
 * 3) Generate config JSON for VLESS + WS + TLS and local SOCKS5 :10808.
 *
 * Place native artifacts under:
 *   app/src/main/jniLibs/{abi}/libxray.so
 * or copy `xray` executable into filesDir as `xray`.
 */
class XrayCore private constructor(private val context: Context) {

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Отключено")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _activeServer = MutableStateFlow<VlessServer?>(null)
    val activeServer: StateFlow<VlessServer?> = _activeServer.asStateFlow()

    private val _lastConfig = MutableStateFlow("")
    val lastConfig: StateFlow<String> = _lastConfig.asStateFlow()

    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var logThread: Thread? = null

    private val configFile: File
        get() = File(context.filesDir, "xray_config.json")

    val isRunning: Boolean get() = running.get() && _state.value == State.RUNNING

    fun start(server: VlessServer): Result<Unit> {
        if (running.get()) {
            stop()
        }
        return try {
            _state.value = State.STARTING
            _statusMessage.value = "Запуск Xray…"
            _activeServer.value = server

            val json = VlessConfig.generateXrayConfig(server, VlessConfig.LOCAL_SOCKS_PORT)
            _lastConfig.value = json
            configFile.writeText(json)
            Log.i(TAG, "Config written: ${configFile.absolutePath}")

            val started = startNative(configFile.absolutePath)
                || startProcess(configFile.absolutePath)

            if (!started) {
                // Dev / UI mode: mark as "running" with generated config so UI flow works;
                // real traffic needs native Xray binary in jniLibs.
                Log.w(TAG, "Native Xray not found — config-only mode")
                running.set(true)
                _state.value = State.RUNNING
                _statusMessage.value =
                    "Конфиг готов (SOCKS ${VlessConfig.LOCAL_SOCKS_PORT}). Добавьте libxray.so для трафика."
                return Result.success(Unit)
            }

            running.set(true)
            _state.value = State.RUNNING
            _statusMessage.value =
                "Подключено → ${server.name} · SOCKS 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}"
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running.set(false)
            _state.value = State.ERROR
            _statusMessage.value = "Ошибка: ${e.message}"
            _activeServer.value = null
            Result.failure(e)
        }
    }

    fun stop() {
        try {
            stopNative()
            process?.let {
                it.destroy()
                try {
                    it.destroyForcibly()
                } catch (_: Exception) {
                }
            }
            process = null
            logThread?.interrupt()
            logThread = null
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        } finally {
            running.set(false)
            _state.value = State.STOPPED
            _statusMessage.value = "Отключено"
            _activeServer.value = null
        }
    }

    /**
     * Try common Go-mobile / LibXray entry points via reflection.
     */
    private fun startNative(configPath: String): Boolean {
        val candidates = listOf(
            "libxray.Xray" to listOf("runXray", "Start", "start", "Run"),
            "golist.Libv2ray" to listOf("runLoop", "RunLoop"),
            "libv2ray.Libv2ray" to listOf("runLoopFromAssets", "runLoop"),
            "com.github.xtls.xray.Xray" to listOf("start", "run")
        )
        for ((className, methods) in candidates) {
            try {
                val clazz = Class.forName(className)
                for (m in methods) {
                    try {
                        val method = clazz.methods.find { it.name.equals(m, true) } ?: continue
                        when (method.parameterTypes.size) {
                            1 -> method.invoke(null, configPath)
                            0 -> method.invoke(null)
                            else -> continue
                        }
                        Log.i(TAG, "Started via $className.$m")
                        return true
                    } catch (e: Exception) {
                        Log.d(TAG, "method $m failed: ${e.message}")
                    }
                }
            } catch (_: ClassNotFoundException) {
                // next
            } catch (e: Exception) {
                Log.d(TAG, "native class $className: ${e.message}")
            }
        }

        // System.loadLibrary attempt
        try {
            System.loadLibrary("xray")
            Log.i(TAG, "libxray loaded (API may need wiring)")
        } catch (_: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("libxray")
            } catch (_: UnsatisfiedLinkError) {
                // ignore
            }
        }
        return false
    }

    private fun stopNative() {
        val stoppers = listOf(
            "libxray.Xray" to listOf("stopXray", "Stop", "stop"),
            "golist.Libv2ray" to listOf("stopLoop", "StopLoop"),
            "libv2ray.Libv2ray" to listOf("stopLoop")
        )
        for ((className, methods) in stoppers) {
            try {
                val clazz = Class.forName(className)
                for (m in methods) {
                    try {
                        val method = clazz.methods.find { it.name.equals(m, true) } ?: continue
                        method.invoke(null)
                        return
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Run standalone xray binary: ./xray run -c config.json
     */
    private fun startProcess(configPath: String): Boolean {
        val binary = resolveBinary() ?: return false
        return try {
            if (!binary.canExecute()) binary.setExecutable(true)
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configPath)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            process = pb.start()
            logThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "xray: $line")
                            if (line?.contains("failed", true) == true) {
                                _statusMessage.value = "Xray: $line"
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            // Brief check process still alive
            Thread.sleep(300)
            val alive = try {
                process?.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
            if (!alive) {
                Log.e(TAG, "xray process exited early")
                return false
            }
            Log.i(TAG, "xray process started pid-ish")
            true
        } catch (e: Exception) {
            Log.e(TAG, "process start failed", e)
            false
        }
    }

    private fun resolveBinary(): File? {
        val names = listOf("xray", "libxray.so", "xray.so")
        // 1) App filesDir (user-copied)
        for (n in names) {
            val f = File(context.filesDir, n)
            if (f.exists()) return f
        }
        // 2) nativeLibraryDir
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        if (libDir.isDirectory) {
            for (n in names) {
                val f = File(libDir, n)
                if (f.exists()) return f
            }
            // also libxray.so as library name pattern
            libDir.listFiles()?.firstOrNull {
                it.name.contains("xray", ignoreCase = true)
            }?.let { return it }
        }
        // 3) Extract from assets if present
        try {
            val am = context.assets
            val list = am.list("") ?: emptyArray()
            if (list.contains("xray")) {
                val out = File(context.filesDir, "xray")
                am.open("xray").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setExecutable(true)
                return out
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        private const val TAG = "XrayCore"

        @Volatile
        private var instance: XrayCore? = null

        fun get(context: Context): XrayCore {
            return instance ?: synchronized(this) {
                instance ?: XrayCore(context.applicationContext).also { instance = it }
            }
        }
    }
}
