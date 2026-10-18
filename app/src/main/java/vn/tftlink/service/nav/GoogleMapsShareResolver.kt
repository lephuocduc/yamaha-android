package vn.tftlink.service.nav

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ResolvedDestination(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val address: String? = null
)

object GoogleMapsShareResolver {

    private const val TAG = "GoogleMapsShareResolver"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Regex tìm URL trong chuỗi
    private val URL_REGEX = Pattern.compile("https?://[^\\s]+")

    // Regex trích xuất tọa độ dạng @lat,lng trong URL của Google Maps
    private val AT_COORD_REGEX = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

    // Regex trích xuất tọa độ dạng ?q=lat,lng hoặc &query=lat,lng
    private val Q_COORD_REGEX = Pattern.compile("[?&](?:q|query|daddr|destination)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

    // Regex trích xuất tọa độ trực tiếp trong văn bản (VD: "10.7725, 106.6578")
    private val PURE_COORD_REGEX = Pattern.compile("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)")

    // Regex trích xuất tên địa điểm trong URL dạng /place/Tên+Địa+Điểm/
    private val PLACE_NAME_REGEX = Pattern.compile("/place/([^/@]+)")

    suspend fun resolve(context: Context, sharedText: String?, uri: Uri?): ResolvedDestination? {
        return withContext(Dispatchers.IO) {
            try {
                // Ưu tiên 1: Phân tích URI trực tiếp (geo: hoặc link Google Maps)
                if (uri != null) {
                    val fromUri = resolveFromUri(context, uri)
                    if (fromUri != null) return@withContext fromUri
                }

                // Ưu tiên 2: Phân tích nội dung chia sẻ dạng text (Intent.EXTRA_TEXT từ app Google Maps)
                if (!sharedText.isNullOrBlank()) {
                    val fromText = resolveFromText(context, sharedText.trim())
                    if (fromText != null) return@withContext fromText
                }

                null
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi phân tích địa điểm chia sẻ: ${e.message}", e)
                null
            }
        }
    }

    private suspend fun resolveFromUri(context: Context, uri: Uri): ResolvedDestination? {
        val scheme = uri.scheme?.lowercase()
        val schemeSpecific = uri.schemeSpecificPart

        if (scheme == "geo") {
            // Định dạng geo:lat,lng hoặc geo:0,0?q=lat,lng(label) hoặc geo:0,0?q=địa_chỉ
            val query = uri.getQueryParameter("q")
            if (!query.isNullOrBlank()) {
                val labelMatcher = Pattern.compile("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)(?:\\((.*)\\))?").matcher(query)
                if (labelMatcher.find()) {
                    val lat = labelMatcher.group(1)?.toDoubleOrNull()
                    val lng = labelMatcher.group(2)?.toDoubleOrNull()
                    val label = labelMatcher.group(3)?.takeIf { it.isNotBlank() } ?: "Vị trí được chia sẻ"
                    if (lat != null && lng != null) {
                        return ResolvedDestination(lat, lng, label)
                    }
                } else {
                    // query là địa chỉ văn bản
                    val geocoded = geocodeAddress(context, query)
                    if (geocoded != null) {
                        return ResolvedDestination(geocoded.first, geocoded.second, query)
                    }
                }
            }

            // Parse trực tiếp phần toạ độ: geo:10.7725,106.6578
            val pureMatcher = PURE_COORD_REGEX.matcher(schemeSpecific ?: "")
            if (pureMatcher.find()) {
                val lat = pureMatcher.group(1)?.toDoubleOrNull()
                val lng = pureMatcher.group(2)?.toDoubleOrNull()
                if (lat != null && lng != null) {
                    val title = reverseGeocode(context, lat, lng) ?: "Vị trí được chia sẻ"
                    return ResolvedDestination(lat, lng, title)
                }
            }
        } else if (scheme == "http" || scheme == "https") {
            return resolveFromUrl(context, uri.toString(), null)
        }

        return null
    }

    private suspend fun resolveFromText(context: Context, text: String): ResolvedDestination? {
        // Tách lấy URL nếu có trong đoạn văn bản
        val urlMatcher = URL_REGEX.matcher(text)
        var extractedUrl: String? = null
        if (urlMatcher.find()) {
            extractedUrl = urlMatcher.group(0)
        }

        // Tách phần tên/mô tả trước URL (Google Maps thường gửi: "Tên địa điểm, Địa chỉ\nhttps://maps.app.goo.gl/...")
        val textBeforeUrl = if (extractedUrl != null) {
            text.replace(extractedUrl, "").trim().lines().firstOrNull { it.isNotBlank() }
        } else {
            text
        }

        if (extractedUrl != null) {
            val resolved = resolveFromUrl(context, extractedUrl, textBeforeUrl)
            if (resolved != null) return resolved
        }

        // Nếu không có URL hoặc URL không ra tọa độ, kiểm tra xem text có chứa tọa độ không
        val coordMatcher = PURE_COORD_REGEX.matcher(text)
        if (coordMatcher.find()) {
            val lat = coordMatcher.group(1)?.toDoubleOrNull()
            val lng = coordMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = textBeforeUrl?.takeIf { it.isNotBlank() } ?: reverseGeocode(context, lat, lng) ?: "Vị trí chia sẻ"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // Thử geocode cả đoạn text nếu đó là tên địa chỉ
        if (text.length in 5..150) {
            val geocoded = geocodeAddress(context, text)
            if (geocoded != null) {
                return ResolvedDestination(geocoded.first, geocoded.second, text)
            }
        }

        return null
    }

    private suspend fun resolveFromUrl(context: Context, rawUrl: String, hintTitle: String?): ResolvedDestination? {
        var finalUrl = rawUrl

        // Nếu là URL rút gọn (maps.app.goo.gl hoặc goo.gl), gửi request HTTP để lấy URL chuyển hướng đích
        if (rawUrl.contains("maps.app.goo.gl") || rawUrl.contains("goo.gl/maps")) {
            finalUrl = followRedirects(rawUrl)
        }

        Log.d(TAG, "Đã mở rộng URL Google Maps: $finalUrl")

        // 1. Tìm toạ độ @lat,lng
        val atMatcher = AT_COORD_REGEX.matcher(finalUrl)
        if (atMatcher.find()) {
            val lat = atMatcher.group(1)?.toDoubleOrNull()
            val lng = atMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = hintTitle ?: extractPlaceNameFromUrl(finalUrl) ?: reverseGeocode(context, lat, lng) ?: "Vị trí Google Maps"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // 2. Tìm toạ độ ?q=lat,lng
        val qMatcher = Q_COORD_REGEX.matcher(finalUrl)
        if (qMatcher.find()) {
            val lat = qMatcher.group(1)?.toDoubleOrNull()
            val lng = qMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = hintTitle ?: extractPlaceNameFromUrl(finalUrl) ?: reverseGeocode(context, lat, lng) ?: "Vị trí Google Maps"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // 3. Nếu trong URL có tên địa điểm /place/Tên+Địa+Điểm
        val placeName = extractPlaceNameFromUrl(finalUrl) ?: hintTitle
        if (!placeName.isNullOrBlank()) {
            val geocoded = geocodeAddress(context, placeName)
            if (geocoded != null) {
                return ResolvedDestination(geocoded.first, geocoded.second, placeName)
            }
        }

        return null
    }

    private fun followRedirects(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()
            finalUrl
        } catch (e: Exception) {
            Log.w(TAG, "Không thể theo dõi redirect cho $url: ${e.message}")
            url
        }
    }

    private fun extractPlaceNameFromUrl(url: String): String? {
        val matcher = PLACE_NAME_REGEX.matcher(url)
        if (matcher.find()) {
            val encodedName = matcher.group(1) ?: return null
            return try {
                URLDecoder.decode(encodedName.replace("+", " "), StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                encodedName.replace("+", " ")
            }
        }
        return null
    }

    private fun geocodeAddress(context: Context, address: String): Pair<Double, Double>? {
        return try {
            val geocoder = Geocoder(context, Locale("vi", "VN"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val list = geocoder.getFromLocationName(address, 1)
                list?.firstOrNull()?.let { Pair(it.latitude, it.longitude) }
            } else {
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocationName(address, 1)
                list?.firstOrNull()?.let { Pair(it.latitude, it.longitude) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder thất bại cho địa chỉ '$address': ${e.message}")
            null
        }
    }

    private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale("vi", "VN"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val list = geocoder.getFromLocation(lat, lng, 1)
                list?.firstOrNull()?.getAddressLine(0)
            } else {
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(lat, lng, 1)
                list?.firstOrNull()?.getAddressLine(0)
            }
        } catch (_: Exception) {
            null
        }
    }
}
