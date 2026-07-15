package com.telegramproxy

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object XrayDownloader {

    private const val TAG = "XrayDownloader"
    private const val RELEASE_URL = "https://api.github.com/repos/XTLS/Xray-core/releases/latest"

    enum class State {
        IDLE, CHECKING, DOWNLOADING, READY, ERROR
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getBinaryFile(context: Context): File = File(context.filesDir, "xray")

    fun isReady(context: Context): Boolean {
        val f = getBinaryFile(context)
        return f.exists() && f.length() > 1000
    }

    fun ensureReadyAsync(context: Context) {
        scope.launch {
            ensureReady(context)
        }
    }

    suspend fun ensureReady(context: Context): Boolean {
        if (isReady(context)) {
            _state.value = State.READY
            _statusText.value = "Xray готов к работе"
            _errorMessage.value = null
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                _state.value = State.CHECKING
                _errorMessage.value = null
                _progress.value = 0f
                _statusText.value = "Получение ссылки на загрузку…"

                val downloadUrl = resolveDownloadUrl()
                Log.i(TAG, "Download URL: $downloadUrl")

                _statusText.value = "Загрузка Xray (${getAssetName()})…"
                downloadAndExtract(context, downloadUrl)

                val binary = getBinaryFile(context)
                if (binary.exists() && binary.length() > 1000) {
                    makeExecutable(binary)
                    _state.value = State.READY
                    _progress.value = 1f
                    _statusText.value = "Xray готов (${binary.length() / 1024} КБ)"
                    Log.i(TAG, "Xray binary ready: ${binary.absolutePath}")
                    true
                } else {
                    _state.value = State.ERROR
                    _statusText.value = "Ошибка: бинарник повреждён"
                    _errorMessage.value = "Бинарник повреждён (${binary.length()} байт)"
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = State.ERROR
                _statusText.value = "Ошибка: ${e.message}"
                _errorMessage.value = "Ошибка загрузки: ${e.message}"
                false
            }
        }
    }

    private fun makeExecutable(file: File) {
        file.setExecutable(true, false)
        file.setReadable(true, false)
        try {
            val p = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
            p.waitFor(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "chmod failed: ${e.message}")
        }
    }

    private fun resolveDownloadUrl(): String {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: throw IllegalStateException("Пустой ответ от GitHub")
        response.close()

        val abiName = getAssetName()
        val regex = Regex(""""browser_download_url"\s*:\s*"([^"]*${Regex.escape(abiName)}\.zip)"""")
        val match = regex.find(body)
            ?: throw IllegalStateException("Архив $abiName не найден в релизе. Ответ: ${body.take(200)}")
        return match.groupValues[1]
    }

    private fun getAssetName(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when (abi) {
            "arm64-v8a" -> "Xray-android-arm64-v8a"
            "armeabi-v7a" -> "Xray-android-armeabi-v7a"
            "x86_64" -> "Xray-android-amd64"
            "x86" -> "Xray-android-x86"
            else -> "Xray-android-arm64-v8a"
        }
    }

    private fun downloadAndExtract(context: Context, url: String) {
        _state.value = State.DOWNLOADING
        _progress.value = 0f

        val request = Request.Builder().url(url).build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code} при загрузке")
        }

        val contentLength = response.body?.contentLength()?.toFloat() ?: 1f
        val zipFile = File(context.filesDir, "xray.zip")

        _statusText.value = "Загрузка… 0%"

        response.body?.byteStream()?.use { input ->
            FileOutputStream(zipFile).use { output ->
                val buffer = ByteArray(16384)
                var totalRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    if (contentLength > 0) {
                        val pct = (totalRead / contentLength * 100).toInt()
                        _progress.value = (totalRead / contentLength * 0.9f).coerceIn(0f, 0.9f)
                        if (pct % 10 == 0) {
                            _statusText.value = "Загрузка… $pct%"
                        }
                    }
                }
            }
        }
        response.close()

        _statusText.value = "Распаковка…"
        _progress.value = 0.9f
        extractXrayBinary(zipFile, getBinaryFile(context))
        zipFile.delete()
        _progress.value = 1f
    }

    private fun extractXrayBinary(zipFile: File, targetFile: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val isXrayBinary = !entry.isDirectory && (
                    name == "xray" ||
                    name.endsWith("/xray") ||
                    name.endsWith("\\xray") ||
                    name == "xray.exe"
                )
                if (isXrayBinary) {
                    FileOutputStream(targetFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    Log.i(TAG, "Extracted '$name' (${targetFile.length()} bytes)")
                    return
                }
                entry = zis.nextEntry
            }
        }
        throw IllegalStateException("Бинарник xray не найден в архиве")
    }

    fun delete(context: Context) {
        getBinaryFile(context).delete()
        File(context.filesDir, "xray.zip").delete()
        _state.value = State.IDLE
        _progress.value = 0f
        _errorMessage.value = null
        _statusText.value = ""
    }
}
