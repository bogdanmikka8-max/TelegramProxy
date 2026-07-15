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
            configFile.writeText(json)
            Log.i(TAG, "Config written: ${configFile.absolutePath}")

            if (!XrayDownloader.isReady(context)) {
                _state.value = State.ERROR
                _statusMessage.value = "Xray не загружен. Обновите приложение."
                return Result.failure(IllegalStateException("Xray binary not found"))
            }

            val started = startProcess(configFile.absolutePath)

            if (!started) {
                _state.value = State.ERROR
                _statusMessage.value = "Ошибка запуска Xray"
                _activeServer.value = null
                return Result.failure(IllegalStateException("Xray process failed to start"))
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

    private fun startProcess(configPath: String): Boolean {
        val binary = XrayDownloader.getBinaryFile(context)
        if (!binary.exists()) return false

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
                            if (line?.contains("failed", true) == true ||
                                line?.contains("error", true) == true
                            ) {
                                _statusMessage.value = "Xray: $line"
                            }
                            if (line?.contains("listening", true) == true) {
                                _statusMessage.value =
                                    "Подключено → ${_activeServer.value?.name ?: "Сервер"} · SOCKS 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}"
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                if (running.get()) {
                    running.set(false)
                    _state.value = State.STOPPED
                    _statusMessage.value = "Отключено (процесс завершён)"
                    _activeServer.value = null
                }
            }.also {
                it.isDaemon = true
                it.start()
            }

            Thread.sleep(500)
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
            Log.i(TAG, "xray process started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "process start failed", e)
            false
        }
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
