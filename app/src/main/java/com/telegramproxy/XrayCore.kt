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
        STOPPED, STARTING, RUNNING, ERROR
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Отключено")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _activeServer = MutableStateFlow<VlessServer?>(null)
    val activeServer: StateFlow<VlessServer?> = _activeServer.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var logThread: Thread? = null

    private val configFile: File
        get() = File(context.filesDir, "xray_config.json")

    val isRunning: Boolean get() = running.get() && _state.value == State.RUNNING

    private fun addLog(line: String) {
        Log.d(TAG, line)
        val current = _logLines.value.toMutableList()
        current.add(line)
        if (current.size > 50) current.removeFirst()
        _logLines.value = current
    }

    fun start(server: VlessServer): Result<Unit> {
        if (running.get()) stop()

        return try {
            _state.value = State.STARTING
            _statusMessage.value = "Запуск Xray…"
            _activeServer.value = server
            _logLines.value = emptyList()

            addLog("Подготовка конфигурации для ${server.name}")

            val json = VlessConfig.generateXrayConfig(server, VlessConfig.LOCAL_SOCKS_PORT)
            configFile.writeText(json)
            addLog("Конфиг записан: ${configFile.absolutePath}")

            val binary = XrayDownloader.getBinaryFile(context)
            if (!binary.exists()) {
                addLog("ОШИБКА: Бинарник xray не найден: ${binary.absolutePath}")
                _state.value = State.ERROR
                _statusMessage.value = "Бинарник xray не найден"
                return Result.failure(IllegalStateException("Xray binary not found"))
            }
            addLog("Бинарник: ${binary.absolutePath} (${binary.length()} байт)")

            binary.setExecutable(true, false)
            binary.setReadable(true, false)
            addLog("isExecutable: ${binary.canExecute()}")

            addLog("Запуск: ${binary.absolutePath} run -c ${configFile.absolutePath}")

            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath

            process = pb.start()
            addLog("Process запущен, PID: ${getPid(process)}")

            logThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            addLog("xray: $l")
                            if (l.contains("listening", true) || l.contains("started", true)) {
                                _statusMessage.value =
                                    "Подключено → ${server.name} · SOCKS 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}"
                            }
                            if (l.contains("failed", true) || l.contains("error", true) || l.contains("panic", true)) {
                                _statusMessage.value = "Xray: $l"
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("Ошибка чтения лога: ${e.message}")
                }
                addLog("Процесс xray завершён")
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

            Thread.sleep(1000)
            val alive = try {
                process?.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
            if (!alive) {
                val exitCode = try { process?.exitValue() } catch (_: Exception) { "?" }
                addLog("ОШИБКА: Процесс завершился сразу (exit code: $exitCode)")
                _state.value = State.ERROR
                _statusMessage.value = "Xray завершился (код: $exitCode)"
                return Result.failure(IllegalStateException("Xray exited with code $exitCode"))
            }

            running.set(true)
            _state.value = State.RUNNING
            _statusMessage.value =
                "Подключено → ${server.name} · SOCKS 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}"
            addLog("Xray запущен и работает")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            addLog("ОШИБКА: ${e.message}")
            running.set(false)
            _state.value = State.ERROR
            _statusMessage.value = "Ошибка: ${e.message}"
            _activeServer.value = null
            Result.failure(e)
        }
    }

    private fun getPid(p: Process?): String {
        return try {
            val f = p?.javaClass?.getDeclaredField("pid")
            f?.isAccessible = true
            f?.getInt(p)?.toString() ?: "?"
        } catch (_: Exception) { "?" }
    }

    fun stop() {
        try {
            process?.let {
                it.destroy()
                try { it.destroyForcibly() } catch (_: Exception) {}
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
