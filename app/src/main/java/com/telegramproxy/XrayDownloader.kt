package com.telegramproxy

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    fun getBinaryFile(context: Context): File = File(context.filesDir, "xray")

    fun isReady(context: Context): Boolean {
        val f = getBinaryFile(context)
        return f.exists() && f.length() > 1000 && f.canExecute()
    }

    suspend fun ensureReady(context: Context): Boolean {
        if (isReady(context)) {
            _state.value = State.READY
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                _state.value = State.CHECKING
                _errorMessage.value = null
                val downloadUrl = resolveDownloadUrl()
                Log.i(TAG, "Download URL: $downloadUrl")
                downloadAndExtract(context, downloadUrl)
                val binary = getBinaryFile(context)
                if (binary.exists() && binary.length() > 1000) {
                    binary.setExecutable(true)
                    _state.value = State.READY
                    _progress.value = 1f
                    Log.i(TAG, "Xray binary ready: ${binary.absolutePath} (${binary.length()} bytes)")
                    true
                } else {
                    _state.value = State.ERROR
                    _errorMessage.value = "Бинарник повреждён"
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = State.ERROR
                _errorMessage.value = "Ошибка загрузки: ${e.message}"
                false
            }
        }
    }

    private fun resolveDownloadUrl(): String {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: throw IllegalStateException("Empty response")
        response.close()

        val abiName = getAssetName()
        val regex = Regex(""""browser_download_url"\s*:\s*"([^"]*${Regex.escape(abiName)}\.zip)"""")
        val match = regex.find(body) ?: throw IllegalStateException("Asset $abiName not found in release")
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
            throw IllegalStateException("HTTP ${response.code}")
        }

        val contentLength = response.body?.contentLength()?.toFloat() ?: 1f
        val zipFile = File(context.filesDir, "xray.zip")

        response.body?.byteStream()?.use { input ->
            FileOutputStream(zipFile).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    if (contentLength > 0) {
                        _progress.value = (totalRead / contentLength).coerceIn(0f, 1f)
                    }
                }
            }
        }
        response.close()

        extractXrayBinary(zipFile, getBinaryFile(context))
        zipFile.delete()
    }

    private fun extractXrayBinary(zipFile: File, targetFile: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xray" && !entry.isDirectory) {
                    FileOutputStream(targetFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    Log.i(TAG, "Extracted xray from zip (${targetFile.length()} bytes)")
                    return
                }
                entry = zis.nextEntry
            }
        }
        throw IllegalStateException("xray binary not found inside zip archive")
    }

    fun delete(context: Context) {
        getBinaryFile(context).delete()
        File(context.filesDir, "xray.zip").delete()
        _state.value = State.IDLE
        _progress.value = 0f
        _errorMessage.value = null
    }
}
