package vn.tftlink.service.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isUpdateAvailable: Boolean
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpToDate(val versionName: String) : UpdateStatus()
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateStatus()
    data class Downloading(val percent: Int, val downloadedMb: Double, val totalMb: Double) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class AppUpdateManager private constructor(
    private val context: Context
) {
    companion object {
        const val VERSION_CHECK_URL =
            "https://raw.githubusercontent.com/lephuocduc/yamaha-android/main/version.json"
        const val FALLBACK_RELEASE_URL =
            "https://github.com/lephuocduc/yamaha-android/releases/latest"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }

        // Backward compatibility overload
        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context, scope: CoroutineScope): AppUpdateManager {
            return getInstance(context)
        }
    }

    // Process-lifelong scope using SupervisorJob to prevent child failures from cancelling the scope
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedicated client with ConnectionPool(0, ...) to ensure each check opens a fresh TCP/TLS handshake.
    // This completely eliminates zombie/dead pooled sockets caused by carrier NAT idle drop on 4G/5G.
    private val checkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val downloadHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val gson = Gson()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {}
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.5.34"
        } catch (_: Exception) {
            "0.5.34"
        }
    }

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            64
        }
    }

    private suspend fun fetchRemoteVersionInfo(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val endpoints = listOf(
            // 1. Raw GitHub with timestamp cache-busting (Direct repo content, no rate limit)
            "$VERSION_CHECK_URL?t=$now" to false,
            // 2. jsDelivr Global Edge CDN (Fast edge servers in VN/SG, no rate limit)
            "https://cdn.jsdelivr.net/gh/lephuocduc/yamaha-android@main/version.json?t=$now" to false,
            // 3. Official GitHub Contents API (Fallback if direct raw domain is blocked by DNS/ISP)
            "https://api.github.com/repos/lephuocduc/yamaha-android/contents/version.json?t=$now" to true
        )

        var lastException: Exception? = null

        for ((url, isGitHubContentsApi) in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 NConnect-Yamaha-Android/1.0")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Connection", "close")
                    .build()

                val response = checkHttpClient.newCall(request).await()
                val jsonBody = response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception("HTTP ${resp.code}")
                    }
                    val rawBody = resp.body?.string() ?: throw Exception("Empty response")
                    if (isGitHubContentsApi) {
                        val root = gson.fromJson(rawBody, Map::class.java)
                        val base64Content = (root["content"] as? String)?.replace("\n", "")?.replace("\r", "")
                            ?: throw Exception("No content field in API response")
                        val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                        String(decodedBytes, Charsets.UTF_8)
                    } else {
                        rawBody
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(jsonBody, Map::class.java) as? Map<String, Any>
                if (parsed != null && parsed.containsKey("versionCode")) {
                    return parsed
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("Không thể kết nối đến máy chủ cập nhật (Vui lòng kiểm tra kết nối mạng)")
    }

    fun checkForUpdate(silentIfUpToDate: Boolean = false) {
        checkJob?.cancel()
        _status.value = UpdateStatus.Checking

        checkJob = updateScope.launch {
            try {
                withTimeout(8_000L) {
                    val parsed = fetchRemoteVersionInfo()

                    val remoteCode = (parsed["versionCode"] as? Number)?.toInt() ?: 0
                    val remoteName = parsed["versionName"] as? String ?: "Mới nhất"
                    val releaseNotes = parsed["releaseNotes"] as? String ?: "Bản cập nhật mới"
                    val downloadUrl = parsed["downloadUrl"] as? String
                        ?: "https://github.com/lephuocduc/yamaha-android/releases/download/latest/app-debug.apk"

                    val localCode = getCurrentVersionCode()
                    val isNewer = remoteCode > localCode

                    val updateInfo = AppUpdateInfo(
                        versionCode = remoteCode,
                        versionName = remoteName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        isUpdateAvailable = isNewer
                    )

                    withContext(Dispatchers.Main) {
                        if (isNewer) {
                            _status.value = UpdateStatus.UpdateAvailable(updateInfo)
                        } else {
                            if (!silentIfUpToDate) {
                                _status.value = UpdateStatus.UpToDate(getCurrentVersionName())
                            } else {
                                _status.value = UpdateStatus.Idle
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        if (e is CancellationException && checkJob?.isCancelled == true) {
                            _status.value = UpdateStatus.Idle
                        } else {
                            val errorMsg = when {
                                e is kotlinx.coroutines.TimeoutCancellationException ->
                                    "Hết thời gian chờ phản hồi từ máy chủ (Quá 8 giây)"
                                e.localizedMessage?.contains("403") == true ->
                                    "Máy chủ GitHub đang bận hoặc giới hạn kết nối, vui lòng thử lại sau giây lát"
                                else ->
                                    e.localizedMessage ?: "Không thể kết nối đến máy chủ cập nhật"
                            }
                            _status.value = UpdateStatus.Error(errorMsg)
                        }
                    }
                }
            }
        }
    }

    fun startDownloadAndInstall(info: AppUpdateInfo) {
        downloadJob?.cancel()
        _status.value = UpdateStatus.Downloading(0, 0.0, 0.0)

        downloadJob = updateScope.launch {
            try {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val updateDir = File(baseDir, "updates")
                if (!updateDir.exists()) {
                    updateDir.mkdirs()
                }
                val apkFile = File(updateDir, "app-update-${info.versionName}.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val request = Request.Builder()
                    .url(info.downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 NConnect-Yamaha-Android/1.0")
                    .build()

                val response = downloadHttpClient.newCall(request).await()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception("Tải tập tin thất bại (Mã lỗi: ${resp.code})")
                    }

                    val body = resp.body ?: throw Exception("Nội dung tải về rỗng")
                    val totalBytes = body.contentLength()

                    body.byteStream().use { inputStream ->
                        FileOutputStream(apkFile).use { outputStream ->
                            val buffer = ByteArray(32768)
                            var downloadedBytes = 0L
                            var read: Int
                            var lastProgressUpdateMs = 0L

                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                                downloadedBytes += read

                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastProgressUpdateMs >= 100 || (totalBytes > 0 && downloadedBytes == totalBytes)) {
                                    lastProgressUpdateMs = nowMs
                                    val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                    val downMb = downloadedBytes / (1024.0 * 1024.0)
                                    val totMb = totalBytes / (1024.0 * 1024.0)

                                    withContext(Dispatchers.Main) {
                                        _status.value = UpdateStatus.Downloading(percent, downMb, totMb)
                                    }
                                }
                            }
                            outputStream.flush()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _status.value = UpdateStatus.ReadyToInstall(apkFile)
                    installApk(apkFile)
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        if (e is CancellationException && downloadJob?.isCancelled == true) {
                            _status.value = UpdateStatus.Idle
                        } else {
                            _status.value = UpdateStatus.Error("Tải bản cập nhật thất bại: ${e.localizedMessage}")
                        }
                    }
                }
            }
        }
    }

    fun installApk(apkFile: File) {
        try {
            // Kiểm tra quyền cài đặt ứng dụng không rõ nguồn gốc trên Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri("update", apkUri)
            }

            // Cấp quyền đọc URI cho mọi package installer
            val resInfoList = context.packageManager.queryIntentActivities(
                installIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    apkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            try {
                context.grantUriPermission("com.google.android.packageinstaller", apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.grantUriPermission("com.android.packageinstaller", apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            context.startActivity(installIntent)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Error("Không thể mở trình cài đặt: ${e.localizedMessage}")
        }
    }

    fun resetStatus() {
        checkJob?.cancel()
        downloadJob?.cancel()
        _status.value = UpdateStatus.Idle
    }
}
