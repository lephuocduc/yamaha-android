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
import vn.tftlink.util.NetworkUtils
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

    private val httpClient = NetworkUtils.configureSsl(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
    ).build()

    // Regex tìm URL trong chuỗi
    private val URL_REGEX = Pattern.compile("https?://[^\\s]+")

    // Regex trích xuất tọa độ dạng @lat,lng trong URL của Google Maps
    private val AT_COORD_REGEX = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

    // Regex trích xuất tọa độ dạng data=!3dlat!4dlng (toạ độ ghim chính xác trong Google Maps place URL)
    private val DATA_COORD_REGEX = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")

    // Regex trích xuất tọa độ dạng ?q=lat,lng hoặc &query=lat,lng hoặc near=lat,lng
    private val Q_COORD_REGEX = Pattern.compile("[?&](?:q|query|daddr|destination|near|ll)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

    // Regex trích xuất tọa độ trực tiếp trong văn bản (VD: "10.7725, 106.6578")
    private val PURE_COORD_REGEX = Pattern.compile("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)")

    // Regex trích xuất tên địa điểm trong URL dạng /place/Tên+Địa+Điểm/
    private val PLACE_NAME_REGEX = Pattern.compile("/place/([^/@]+)")

    // Regex trích xuất tham số tìm kiếm ?q=Tên+Địa+Điểm
    private val SEARCH_QUERY_REGEX = Pattern.compile("[?&](?:q|query)=([^&]+)")

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

        // 1. Tìm toạ độ chính xác dạng !3dlat!4dlng trong dữ liệu place của Google Maps
        val dataMatcher = DATA_COORD_REGEX.matcher(finalUrl)
        if (dataMatcher.find()) {
            val lat = dataMatcher.group(1)?.toDoubleOrNull()
            val lng = dataMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = hintTitle ?: extractPlaceNameFromUrl(finalUrl) ?: reverseGeocode(context, lat, lng) ?: "Vị trí Google Maps"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // 2. Tìm toạ độ @lat,lng
        val atMatcher = AT_COORD_REGEX.matcher(finalUrl)
        if (atMatcher.find()) {
            val lat = atMatcher.group(1)?.toDoubleOrNull()
            val lng = atMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = hintTitle ?: extractPlaceNameFromUrl(finalUrl) ?: reverseGeocode(context, lat, lng) ?: "Vị trí Google Maps"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // 3. Tìm toạ độ ?q=lat,lng hoặc ll=lat,lng
        val qMatcher = Q_COORD_REGEX.matcher(finalUrl)
        if (qMatcher.find()) {
            val lat = qMatcher.group(1)?.toDoubleOrNull()
            val lng = qMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val title = hintTitle ?: extractPlaceNameFromUrl(finalUrl) ?: reverseGeocode(context, lat, lng) ?: "Vị trí Google Maps"
                return ResolvedDestination(lat, lng, title)
            }
        }

        // 4. Nếu trong URL có tên địa điểm /place/Tên+Địa+Điểm
        val placeName = extractPlaceNameFromUrl(finalUrl) ?: hintTitle
        if (!placeName.isNullOrBlank()) {
            val geocoded = geocodeAddress(context, placeName)
            if (geocoded != null) {
                return ResolvedDestination(geocoded.first, geocoded.second, placeName)
            }
        }

        // 5. Nếu trong URL có tham số tìm kiếm ?q=Tên+Địa+Điểm
        val queryMatcher = SEARCH_QUERY_REGEX.matcher(finalUrl)
        if (queryMatcher.find()) {
            val queryText = queryMatcher.group(1)?.let {
                try {
                    URLDecoder.decode(it.replace("+", " "), StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    it.replace("+", " ")
                }
            }
            if (!queryText.isNullOrBlank()) {
                val geocoded = geocodeAddress(context, queryText)
                if (geocoded != null) {
                    return ResolvedDestination(geocoded.first, geocoded.second, queryText)
                }
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
            var finalUrl = response.request.url.toString()
            val body = response.body?.string() ?: ""
            response.close()

            // Nếu redirect chưa ra URL Google Maps chứa toạ độ, tìm link meta refresh hoặc canonical trong body
            if (!finalUrl.contains("@") && !finalUrl.contains("!3d")) {
                val metaMatcher = Pattern.compile("<meta[^>]+content=[\"'][^\"']*url=([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(body)
                if (metaMatcher.find()) {
                    finalUrl = metaMatcher.group(1) ?: finalUrl
                } else {
                    val canonicalMatcher = Pattern.compile("<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(body)
                    if (canonicalMatcher.find()) {
                        finalUrl = canonicalMatcher.group(1) ?: finalUrl
                    }
                }
            }
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

    private suspend fun geocodeAddress(context: Context, address: String): Pair<Double, Double>? {
        // 1. Thử dùng Android Geocoder tích hợp
        try {
            val geocoder = Geocoder(context, Locale("vi", "VN"))
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(address, 1)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(address, 1)
            }
            val first = list?.firstOrNull()
            if (first != null) {
                return Pair(first.latitude, first.longitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android Geocoder thất bại cho '$address': ${e.message}")
        }

        // 2. Dự phòng bằng Photon OpenStreetMap Geocoding API (Hỗ trợ 100% thiết bị không có Google Play Services)
        return try {
            val encoded = java.net.URLEncoder.encode(address.trim(), "UTF-8").replace("+", "%20")
            val req = Request.Builder()
                .url("https://photon.komoot.io/api/?q=$encoded&limit=1")
                .header("User-Agent", "NConnect-Yamaha-Android/1.0 (contact: info@tftlink.vn)")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = org.json.JSONObject(resp.body?.string() ?: "")
                val features = json.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    val geom = features.getJSONObject(0).optJSONObject("geometry")
                    val coords = geom?.optJSONArray("coordinates")
                    if (coords != null && coords.length() >= 2) {
                        val lon = coords.getDouble(0)
                        val lat = coords.getDouble(1)
                        return Pair(lat, lon)
                    }
                }
            }
            null
        } catch (_: Exception) {
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
