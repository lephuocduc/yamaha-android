package vn.tftlink.service.nav

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.tftlink.data.model.location.SavedPlace
import vn.tftlink.data.model.nav.NavigationDirection
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.NavigationStep
import vn.tftlink.data.model.nav.OSMRoute
import vn.tftlink.service.ble.YamahaCCUManager
import vn.tftlink.service.renderer.TFTStreamRenderer
import vn.tftlink.util.GeoUtils
import vn.tftlink.util.NetworkUtils
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

import kotlinx.coroutines.SupervisorJob

class NavigationEngine private constructor(
    private val context: Context
) {
    companion object {
        const val VALHALLA_DEFAULT_URL = "https://valhalla1.openstreetmap.de"
        const val OSM_ROUTING_DE_URL = "https://routing.openstreetmap.de/routed-car"
        const val OSRM_DEFAULT_URL = "https://router.project-osrm.org"
        const val OSRM_FALLBACK_HTTP_URL = "http://router.project-osrm.org"
        const val PHOTON_URL = "https://photon.komoot.io"

        @Volatile
        private var instance: NavigationEngine? = null

        fun getInstance(context: Context): NavigationEngine {
            return instance ?: synchronized(this) {
                instance ?: NavigationEngine(context.applicationContext).also { instance = it }
            }
        }

        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context, scope: CoroutineScope): NavigationEngine {
            return getInstance(context)
        }
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = NetworkUtils.configureSsl(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
    ).build()
    private val gson = Gson()

    // CCU & Renderer bindings
    var ccuManager: YamahaCCUManager? = null
    var streamRenderer: TFTStreamRenderer? = null

    // State flows
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    // Cảm biến La bàn điện tử (Compass / Rotation Vector) cho chế độ đứng yên hoặc tốc độ thấp (< 3 km/h)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)

    private val sensorListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)
        private var lastAzimuth = 0f

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            // Chuẩn iOS: Chỉ sử dụng la bàn cảm biến khi xe đứng yên hoặc tốc độ rất thấp (< 3.0 km/h)
            if (_currentSpeedKmh.value >= 3.0) return

            val azimuth = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    (Math.toDegrees(orientationAngles[0].toDouble()).toFloat() + 360f) % 360f
                }
                Sensor.TYPE_ORIENTATION -> {
                    (event.values[0] + 360f) % 360f
                }
                else -> return
            }

            var diff = (azimuth - lastAzimuth) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            // Bộ lọc suy hao dao động (Jitter Damping) triệt tiêu rung lắc la bàn
            if (kotlin.math.abs(diff) >= 2.0f) {
                val smoothed = (lastAzimuth + diff * 0.4f + 360f) % 360f
                lastAzimuth = smoothed
                _currentHeading.value = smoothed
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startCompass() {
        rotationSensor?.let { sensor ->
            sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopCompass() {
        sensorManager?.unregisterListener(sensorListener)
    }

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _activeRoute = MutableStateFlow<OSMRoute?>(null)
    val activeRoute: StateFlow<OSMRoute?> = _activeRoute.asStateFlow()

    private val _alternativeRoutes = MutableStateFlow<List<OSMRoute>>(emptyList())
    val alternativeRoutes: StateFlow<List<OSMRoute>> = _alternativeRoutes.asStateFlow()

    private val _allCalculatedRoutes = MutableStateFlow<List<OSMRoute>>(emptyList())
    val allCalculatedRoutes: StateFlow<List<OSMRoute>> = _allCalculatedRoutes.asStateFlow()

    private val _currentInstruction = MutableStateFlow(NavigationInstruction())
    val currentInstruction: StateFlow<NavigationInstruction> = _currentInstruction.asStateFlow()

    private val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SavedPlace>>(emptyList())
    val searchResults: StateFlow<List<SavedPlace>> = _searchResults.asStateFlow()

    private val _nearbyGasStations = MutableStateFlow<List<SavedPlace>>(emptyList())
    val nearbyGasStations: StateFlow<List<SavedPlace>> = _nearbyGasStations.asStateFlow()

    private val _isFetchingGasStations = MutableStateFlow(false)
    val isFetchingGasStations: StateFlow<Boolean> = _isFetchingGasStations.asStateFlow()

    private val _gasStationError = MutableStateFlow<String?>(null)
    val gasStationError: StateFlow<String?> = _gasStationError.asStateFlow()

    private val _arrivalMessage = MutableStateFlow<String?>(null)
    val arrivalMessage: StateFlow<String?> = _arrivalMessage.asStateFlow()

    private val _isRerouting = MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    private val _destinationTitle = MutableStateFlow("Điểm đến")
    val destinationTitle: StateFlow<String> = _destinationTitle.asStateFlow()

    private val _currentRoadName = MutableStateFlow("")
    val currentRoadName: StateFlow<String> = _currentRoadName.asStateFlow()

    var isVoiceGuidanceEnabled: Boolean = true

    // Internal navigation state
    private var routeSteps: List<NavigationStep> = emptyList()
    private var currentStepIndex: Int = 0
    private var destinationCoordinate: LatLng? = null
    private var intermediateStops: List<LatLng> = emptyList()
    private var offRouteConsecutiveTicks: Int = 0
    private var lastRerouteTimeMs: Long = 0
    private var searchJob: Job? = null

    // Text to Speech
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastSpokenStepIndex = -1
    private var hasSpoken300mAlert = false
    private var hasSpoken100mAlert = false

    private val _routeErrorMessage = MutableStateFlow<String?>(null)
    val routeErrorMessage: StateFlow<String?> = _routeErrorMessage.asStateFlow()

    fun clearRouteErrorMessage() {
        _routeErrorMessage.value = null
    }

    init {
        initTts()
        restoreLastKnownLocation()
    }

    fun isValidCoordinate(loc: LatLng?): Boolean {
        if (loc == null) return false
        return loc.latitude != 0.0 && loc.longitude != 0.0 &&
                loc.latitude in -90.0..90.0 &&
                loc.longitude in -180.0..180.0
    }

    private fun restoreLastKnownLocation() {
        try {
            val prefs = context.getSharedPreferences("nav_engine_prefs", Context.MODE_PRIVATE)
            val lat = prefs.getString("last_lat", null)?.toDoubleOrNull()
            val lng = prefs.getString("last_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val loc = LatLng(lat, lng)
                if (isValidCoordinate(loc)) {
                    _currentLocation.value = loc
                }
            }
        } catch (_: Exception) {}
    }

    fun getEffectiveLocation(): LatLng {
        _currentLocation.value?.let {
            if (isValidCoordinate(it)) return it
        }
        try {
            val prefs = context.getSharedPreferences("nav_engine_prefs", Context.MODE_PRIVATE)
            val lat = prefs.getString("last_lat", null)?.toDoubleOrNull()
            val lng = prefs.getString("last_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                val loc = LatLng(lat, lng)
                if (isValidCoordinate(loc)) {
                    _currentLocation.value = loc
                    return loc
                }
            }
        } catch (_: Exception) {}

        try {
            val locMgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = locMgr?.getProviders(true) ?: emptyList()
            for (p in providers) {
                @SuppressLint("MissingPermission")
                val l = locMgr?.getLastKnownLocation(p)
                if (l != null) {
                    val loc = LatLng(l.latitude, l.longitude)
                    if (isValidCoordinate(loc)) {
                        _currentLocation.value = loc
                        return loc
                    }
                }
            }
        } catch (_: Exception) {}

        val defaultLoc = LatLng(10.7769, 106.7009) // TP. Hồ Chí Minh
        _currentLocation.value = defaultLoc
        return defaultLoc
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("vi", "VN"))
                    isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
                }
            }
        } catch (_: Exception) {}
    }

    fun speakInstruction(text: String) {
        if (!isVoiceGuidanceEnabled || !isTtsReady || text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NavGuidance")
        } catch (_: Exception) {}
    }

    fun updateLocation(latLng: LatLng, speedKmh: Double = 0.0, heading: Float = 0f) {
        if (!isValidCoordinate(latLng)) return
        _currentLocation.value = latLng
        _currentSpeedKmh.value = speedKmh

        // Chuẩn logic iOS:
        // - Khi di chuyển (speedKmh >= 3.0 km/h) và GPS có course/bearing (> 0°): ưu tiên course từ GPS.
        // - Khi đứng yên (speedKmh < 3.0 km/h): giữ nguyên hướng hoặc dùng la bàn cảm biến từ sensorListener.
        if (speedKmh >= 3.0 && heading > 0f) {
            _currentHeading.value = heading
        } else if (_currentHeading.value == 0f && heading > 0f) {
            _currentHeading.value = heading
        }

        // Lưu vết vị trí cuối cùng để khôi phục ngay khi mở app
        try {
            context.getSharedPreferences("nav_engine_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_lat", latLng.latitude.toString())
                .putString("last_lng", latLng.longitude.toString())
                .apply()
        } catch (_: Exception) {}

        if (_isNavigating.value && _activeRoute.value != null) {
            processNavigationProgress(latLng, speedKmh)
        }
    }

    /**
     * Tìm kiếm địa điểm bằng Photon OpenStreetMap Geocoding API (Hỗ trợ 100% địa danh Việt Nam)
     */
    fun searchPlaces(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        searchJob = engineScope.launch(Dispatchers.IO) {
            delay(200) // Debounce
            try {
                // Photon and Nominatim expect %20 for spaces in query string
                val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
                val userLoc = _currentLocation.value ?: LatLng(10.7769, 106.7009)
                val urlStr = "$PHOTON_URL/api/?q=$encoded&limit=15&lat=${userLoc.latitude}&lon=${userLoc.longitude}"

                val request = Request.Builder()
                    .url(urlStr)
                    .header("User-Agent", "NConnect-Yamaha-Android/1.0 (contact: info@tftlink.vn)")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.use { resp -> if (resp.isSuccessful) resp.body?.string() ?: "" else "" }
                val items = mutableListOf<SavedPlace>()

                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val features = json.optJSONArray("features") ?: JSONArray()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val geom = f.optJSONObject("geometry") ?: continue
                        val coords = geom.optJSONArray("coordinates") ?: continue
                        if (coords.length() < 2) continue
                        val lon = coords.getDouble(0)
                        val lat = coords.getDouble(1)

                        val props = f.optJSONObject("properties") ?: JSONObject()
                        val name = props.optString("name", trimmed)
                        val street = props.optString("street", "")
                        val district = props.optString("district", props.optString("county", props.optString("locality", "")))
                        val city = props.optString("city", props.optString("state", "Việt Nam"))

                        val parts = listOf(street, district, city).filter { it.isNotBlank() }
                        val address = if (parts.isNotEmpty()) parts.joinToString(", ") else city

                        items.add(
                            SavedPlace(
                                name = name,
                                address = address,
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    }
                }

                if (items.isEmpty()) {
                    fallbackNominatimSearch(trimmed)
                } else {
                    withContext(Dispatchers.Main) {
                        _searchResults.value = items
                        _isSearching.value = false
                    }
                }
            } catch (e: Exception) {
                fallbackNominatimSearch(trimmed)
            }
        }
    }

    private suspend fun fallbackNominatimSearch(query: String) {
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=12&countrycodes=vn&addressdetails=1"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) NConnect/1.0 (Yamaha-TFT-Vietnam)")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "vi,en;q=0.9")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.use { resp -> if (resp.isSuccessful) resp.body?.string() ?: "" else "" }
                val array = JSONArray(body)
                val items = mutableListOf<SavedPlace>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name").ifBlank {
                        obj.optString("display_name").split(",").firstOrNull()?.trim() ?: query
                    }
                    val address = obj.optString("display_name")
                    val lat = obj.optDouble("lat", 0.0)
                    val lon = obj.optDouble("lon", 0.0)
                    if (lat != 0.0 && lon != 0.0) {
                        items.add(SavedPlace(name = name, address = address, latitude = lat, longitude = lon))
                    }
                }

                withContext(Dispatchers.Main) {
                    _searchResults.value = items
                    _isSearching.value = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    _isSearching.value = false
                }
            }
        }
    }

    /**
     * Tra cứu địa chỉ chi tiết từ toạ độ (Reverse Geocode).
     * Ưu tiên 1: Android Geocoder (dữ liệu Google Maps chi tiết có số nhà, tên đường, phường xã, quận huyện).
     * Ưu tiên 2: OpenStreetMap Nominatim zoom=18 (hỗ trợ số nhà OSM).
     * Ưu tiên 3: Photon Komoot OSM.
     */
    suspend fun reverseGeocodeSuspend(latLng: LatLng): Pair<String, String> = withContext(Dispatchers.IO) {
        var defaultTitle = "Vị trí đã chọn"
        var defaultAddress = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)

        // 1. Ưu tiên hàng đầu: Android native Geocoder (kết nối trực tiếp cơ sở dữ liệu số nhà Google Maps tại VN)
        try {
            val geocoder = Geocoder(context, Locale("vi", "VN"))
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!list.isNullOrEmpty()) {
                val p = list[0]
                val fullAddressLine = p.getAddressLine(0)
                val houseNumber = p.subThoroughfare?.trim().orEmpty()
                val street = p.thoroughfare?.trim().orEmpty()
                val feature = p.featureName?.trim().orEmpty()

                val streetWithNum = when {
                    houseNumber.isNotBlank() && street.isNotBlank() -> {
                        if (street.contains(houseNumber)) street else "$houseNumber $street"
                    }
                    street.isNotBlank() -> street
                    houseNumber.isNotBlank() -> houseNumber
                    else -> ""
                }

                val title = when {
                    feature.isNotBlank() && feature != street && feature != houseNumber && !feature.matches(Regex("""^-?\d+\.\d+.*""")) -> feature
                    streetWithNum.isNotBlank() -> streetWithNum
                    else -> p.subLocality ?: p.locality ?: defaultTitle
                }

                val parts = listOfNotNull(
                    streetWithNum.ifBlank { null },
                    p.subLocality?.ifBlank { null },
                    p.locality?.ifBlank { null },
                    p.subAdminArea?.ifBlank { null },
                    p.adminArea?.ifBlank { null }
                )
                val fallbackFormatted = if (parts.isNotEmpty()) parts.joinToString(", ") else defaultAddress
                val address = fullAddressLine?.takeIf { it.isNotBlank() } ?: fallbackFormatted

                return@withContext Pair(title, address)
            }
        } catch (_: Exception) {}

        // 2. Dự phòng: Nominatim OpenStreetMap (zoom=18 chi tiết đến số nhà và toà nhà)
        try {
            val nominatimUrl = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${latLng.latitude}&lon=${latLng.longitude}&zoom=18&addressdetails=1"
            val request = Request.Builder()
                .url(nominatimUrl)
                .header("User-Agent", "TFTLink-YamahaCompanion/1.0 (motorcycle nav)")
                .header("Accept-Language", "vi,en")
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val displayName = json.optString("display_name", "")
                    val addrObj = json.optJSONObject("address")
                    if (addrObj != null) {
                        val houseNum = addrObj.optString("house_number", "")
                        val road = addrObj.optString("road", addrObj.optString("pedestrian", ""))
                        val suburb = addrObj.optString("suburb", addrObj.optString("quarter", ""))
                        val cityDistrict = addrObj.optString("city_district", addrObj.optString("district", ""))
                        val city = addrObj.optString("city", addrObj.optString("state", "Việt Nam"))

                        val streetPart = if (houseNum.isNotBlank() && road.isNotBlank()) "$houseNum $road" else road
                        val title = streetPart.ifBlank { suburb.ifBlank { city.ifBlank { defaultTitle } } }
                        val formatted = listOfNotNull(
                            streetPart.ifBlank { null },
                            suburb.ifBlank { null },
                            cityDistrict.ifBlank { null },
                            city.ifBlank { null }
                        ).joinToString(", ")
                        val address = if (formatted.isNotBlank()) formatted else displayName.ifBlank { defaultAddress }
                        return@withContext Pair(title, address)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Dự phòng cấp 3: Photon OSM
        try {
            val url = "$PHOTON_URL/reverse?lat=${latLng.latitude}&lon=${latLng.longitude}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NConnect-Yamaha/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val features = json.optJSONArray("features")
                    if (features != null && features.length() > 0) {
                        val props = features.getJSONObject(0).optJSONObject("properties")
                        if (props != null) {
                            val name = props.optString("name", "")
                            val street = props.optString("street", "")
                            val housenumber = props.optString("housenumber", "")
                            val district = props.optString("district", props.optString("county", props.optString("locality", "")))
                            val city = props.optString("city", props.optString("state", "Việt Nam"))

                            val streetPart = if (housenumber.isNotBlank() && street.isNotBlank()) "$housenumber $street" else street
                            val cleanTitle = when {
                                name.isNotBlank() -> name
                                streetPart.isNotBlank() -> streetPart
                                district.isNotBlank() -> district
                                city.isNotBlank() -> city
                                else -> defaultTitle
                            }

                            val addrParts = listOfNotNull(
                                if (name.isNotBlank() && name != streetPart) name else null,
                                streetPart.ifBlank { null },
                                district.ifBlank { null },
                                city.ifBlank { null }
                            )
                            val address = if (addrParts.isNotEmpty()) addrParts.joinToString(", ") else defaultAddress
                            return@withContext Pair(cleanTitle, address)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        Pair(defaultTitle, defaultAddress)
    }

    /**
     * Tra cứu địa chỉ từ toạ độ (Reverse Geocode) có gọi lại callback trên Main thread.
     */
    fun reverseGeocode(latLng: LatLng, onResult: (title: String, address: String) -> Unit) {
        engineScope.launch(Dispatchers.IO) {
            val (title, address) = reverseGeocodeSuspend(latLng)
            withContext(Dispatchers.Main) {
                onResult(title, address)
            }
        }
    }

    /**
     * Tìm các cây xăng thật gần nhất (tối đa [maxResults]) bằng Photon với bộ lọc OSM `amenity=fuel`.
     * Photon chỉ ưu tiên (bias) theo vị trí chứ không giới hạn cự ly, nên kết quả được lọc trong bán kính 15km
     * rồi sắp xếp theo khoảng cách thực tế từ vị trí hiện tại.
     */
    fun fetchNearbyGasStations(maxResults: Int = 3) {
        val loc = getEffectiveLocation()
        _isFetchingGasStations.value = true
        _gasStationError.value = null
        _nearbyGasStations.value = emptyList()

        engineScope.launch(Dispatchers.IO) {
            try {
                val queries = listOf("tr%E1%BA%A1m%20x%C4%83ng", "c%C3%A2y%20x%C4%83ng", "petrol")
                val unique = LinkedHashMap<String, SavedPlace>()

                for (q in queries) {
                    if (unique.size >= maxResults * 3) break
                    try {
                        val url = "$PHOTON_URL/api/?q=$q&lat=${loc.latitude}&lon=${loc.longitude}&limit=20&osm_tag=amenity:fuel"
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                            .build()
                        val body = httpClient.newCall(request).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                        }
                        if (body.isBlank()) continue
                        val features = JSONObject(body).optJSONArray("features") ?: continue
                        for (i in 0 until features.length()) {
                            val f = features.getJSONObject(i)
                            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                            if (coords.length() < 2) continue
                            val lon = coords.getDouble(0)
                            val lat = coords.getDouble(1)
                            val props = f.optJSONObject("properties") ?: JSONObject()
                            val dist = GeoUtils.computeDistanceMeters(loc, LatLng(lat, lon))
                            if (dist > 15_000) continue

                            val key = "${props.optLong("osm_id", 0L)}_${"%.5f".format(Locale.US, lat)}_${"%.5f".format(Locale.US, lon)}"
                            val name = props.optString("name", "").ifBlank { "Trạm xăng" }
                            val housenumber = props.optString("housenumber", "")
                            val street = props.optString("street", "")
                            val district = props.optString("district", props.optString("locality", ""))
                            val city = props.optString("city", "")
                            val addr = listOf("$housenumber $street".trim(), district, city).filter { it.isNotBlank() }.joinToString(", ")
                            unique.putIfAbsent(key, SavedPlace(name = name, address = addr, latitude = lat, longitude = lon))
                        }
                    } catch (e: Exception) {
                        ccuManager?.log("Tìm trạm xăng (q=$q) lỗi: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                val nearest = unique.values
                    .sortedBy { GeoUtils.computeDistanceMeters(loc, it.coordinate) }
                    .take(maxResults)
                ccuManager?.log("Tìm trạm xăng: ${nearest.size} kết quả gần (${loc.latitude}, ${loc.longitude})")

                withContext(Dispatchers.Main) {
                    _nearbyGasStations.value = nearest
                    _gasStationError.value = if (nearest.isEmpty()) "Không tìm thấy trạm xăng nào trong bán kính 15 km. Kiểm tra kết nối mạng rồi thử lại." else null
                    _isFetchingGasStations.value = false
                }
            } catch (e: Exception) {
                ccuManager?.log("Tìm trạm xăng thất bại: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    _gasStationError.value = "Không thể tìm trạm xăng. Kiểm tra kết nối mạng rồi thử lại."
                    _isFetchingGasStations.value = false
                }
            }
        }
    }

    /**
     * Lập lộ trình dẫn đường bằng Valhalla Motorcycle Routing Engine (hoặc dự phòng OSRM)
     */
    fun calculateRoute(
        origin: LatLng? = null,
        intermediateStops: List<LatLng> = emptyList(),
        destination: LatLng,
        destinationTitle: String = "Điểm đến",
        autoStart: Boolean = false
    ) {
        val start = if (origin != null && isValidCoordinate(origin)) origin else getEffectiveLocation()
        if (!isValidCoordinate(destination)) {
            _routeErrorMessage.value = "Toạ độ đích không hợp lệ."
            return
        }

        if (intermediateStops.isEmpty() && GeoUtils.computeDistanceMeters(start, destination) < 30.0) {
            ccuManager?.log("Bỏ qua lập lộ trình: điểm đến '$destinationTitle' trùng vị trí hiện tại (<30m).")
            _routeErrorMessage.value = "Điểm đến '$destinationTitle' trùng với vị trí hiện tại của bạn. Hãy kiểm tra lại địa chỉ đã lưu."
            return
        }

        this.destinationCoordinate = destination
        this.intermediateStops = intermediateStops
        _destinationTitle.value = destinationTitle
        _isCalculatingRoute.value = true
        _routeErrorMessage.value = null
        _alternativeRoutes.value = emptyList()
        _allCalculatedRoutes.value = emptyList()

        engineScope.launch(Dispatchers.IO) {
            ccuManager?.log("Bắt đầu lập lộ trình: (${start.latitude}, ${start.longitude}) -> (${destination.latitude}, ${destination.longitude}) '$destinationTitle'")

            // 1. Thử Valhalla Motorcycle Routing
            var routes = requestValhallaRoutes(start, intermediateStops, destination)
            if (routes.isNotEmpty()) {
                ccuManager?.log("Valhalla thành công: ${routes.size} tuyến đường.")
            } else {
                ccuManager?.log("Valhalla không phản hồi, thử OSRM Routing Germany...")
                // 2. Dự phòng: OSRM Routing
                routes = requestOSRMRoutes(start, destination)
                if (routes.isNotEmpty()) {
                    ccuManager?.log("OSRM thành công: ${routes.size} tuyến đường.")
                } else {
                    ccuManager?.log("Lỗi: Cả Valhalla và OSRM đều không tìm thấy đường đi.")
                }
            }

            withContext(Dispatchers.Main) {
                _isCalculatingRoute.value = false
                if (routes.isNotEmpty()) {
                    applyCalculatedRoutes(routes, autoStart)
                } else {
                    _routeErrorMessage.value = "Không thể tìm thấy lộ trình đến '$destinationTitle'. Vui lòng kiểm tra lại kết nối mạng hoặc chọn vị trí khác."
                }
            }
        }
    }

    private suspend fun requestValhallaRoutes(
        start: LatLng,
        stops: List<LatLng>,
        destination: LatLng
    ): List<OSMRoute> = withContext(Dispatchers.IO) {
        val url = "$VALHALLA_DEFAULT_URL/route"
        val locationsArray = JSONArray()
        locationsArray.put(JSONObject().put("lat", start.latitude).put("lon", start.longitude).put("type", "break"))
        for (stop in stops) {
            locationsArray.put(JSONObject().put("lat", stop.latitude).put("lon", stop.longitude).put("type", "break"))
        }
        locationsArray.put(JSONObject().put("lat", destination.latitude).put("lon", destination.longitude).put("type", "break"))

        // Các biến thể payload từ đầy đủ -> tối giản. Máy chủ công cộng có thể từ chối costing_options/language
        // (HTTP 400) nên tự động hạ cấp thay vì bỏ cuộc ngay.
        data class Variant(val label: String, val build: () -> JSONObject)
        val variants = listOf(
            Variant("motorcycle+options") {
                JSONObject().apply {
                    put("locations", locationsArray)
                    put("costing", "motorcycle")
                    put("costing_options", JSONObject().put("motorcycle", JSONObject().apply {
                        put("use_highways", 0.0) // Tránh cao tốc cấm xe máy ở Việt Nam
                        put("use_primary", 0.9)  // Ưu tiên đường lớn, đại lộ
                        put("use_tolls", 0.0)    // Tránh trạm thu phí
                        put("use_trails", 0.0)   // Tránh đường mòn
                    }))
                    put("alternates", if (stops.isEmpty()) 2 else 0)
                    put("directions_options", JSONObject().put("units", "kilometers").put("language", "vi-VN"))
                }
            },
            Variant("motorcycle") {
                JSONObject().apply {
                    put("locations", locationsArray)
                    put("costing", "motorcycle")
                    put("directions_options", JSONObject().put("units", "kilometers"))
                }
            },
            Variant("motor_scooter") {
                JSONObject().apply {
                    put("locations", locationsArray)
                    put("costing", "motor_scooter")
                    put("directions_options", JSONObject().put("units", "kilometers"))
                }
            }
        )

        for (variant in variants) {
            val jsonPayload = variant.build().toString()
            var code = 0
            var body = ""

            // 1. Thử qua OkHttpClient (với Tls13SocketFactory)
            try {
                val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("User-Agent", "TFTLink/0.1 (personal motorcycle display app)")
                    .header("X-Client-Id", "TFTLink-YamahaCompanion")
                    .header("Accept", "application/json")
                    .build()

                val outcome: Pair<Int, String> = httpClient.newCall(request).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
                code = outcome.first
                body = outcome.second
            } catch (e: Exception) {
                // 2. Dự phòng: Thử bằng HttpsURLConnection (GET ?json=...) như app NConnect gốc
                try {
                    val fallbackOutcome = fetchValhallaViaHttpURLConnection(jsonPayload)
                    code = fallbackOutcome.first
                    body = fallbackOutcome.second
                    if (code in 200..299 && body.isNotBlank()) {
                        ccuManager?.log("Valhalla[${variant.label}] qua HttpURLConnection thành công")
                    }
                } catch (fallbackEx: Exception) {
                    ccuManager?.log("Valhalla[${variant.label}] lỗi mạng: ${e.javaClass.simpleName}: ${e.message} / HttpURLConnection: ${fallbackEx.javaClass.simpleName}: ${fallbackEx.message}")
                    return@withContext emptyList()
                }
            }

            if (code in 200..299 && body.isNotBlank()) {
                val parsed = try { parseValhallaRoutes(body) } catch (e: Exception) {
                    ccuManager?.log("Valhalla[${variant.label}] lỗi phân tích JSON: ${e.message}")
                    emptyList()
                }
                if (parsed.isNotEmpty()) {
                    ccuManager?.log("Valhalla[${variant.label}] OK")
                    return@withContext parsed
                }
                ccuManager?.log("Valhalla[${variant.label}] HTTP $code nhưng không có tuyến.")
            } else if (code > 0) {
                ccuManager?.log("Valhalla[${variant.label}] HTTP $code: ${body.take(160).replace('\n', ' ')}")
                if (code >= 500 || code == 429) return@withContext emptyList()
            }
        }
        emptyList()
    }

    /**
     * Gửi yêu cầu định tuyến Valhalla qua GET ?json=... bằng HttpsURLConnection
     * theo cơ chế chuẩn của app NConnect gốc.
     */
    private fun fetchValhallaViaHttpURLConnection(jsonStr: String): Pair<Int, String> {
        val encoded = URLEncoder.encode(jsonStr, "UTF-8")
        val getUrl = URL("$VALHALLA_DEFAULT_URL/route?json=$encoded")
        val conn = NetworkUtils.openHttpsConnection(getUrl).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TFTLink/0.1 (personal motorcycle display app)")
            setRequestProperty("X-Client-Id", "TFTLink-YamahaCompanion")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "vi,en")
            connectTimeout = 8000
            readTimeout = 15000
        }
        return try {
            val respCode = conn.responseCode
            val stream = if (respCode in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            respCode to respBody
        } finally {
            conn.disconnect()
        }
    }

    private fun parseValhallaRoutes(jsonStr: String): List<OSMRoute> {
        val root = JSONObject(jsonStr)
        val trip = root.optJSONObject("trip") ?: return emptyList()
        val routesList = mutableListOf<OSMRoute>()

        // 1. Tuyến chính
        val primaryRoute = parseSingleValhallaTrip(trip, isFastest = true)
        if (primaryRoute != null) {
            routesList.add(primaryRoute)
        }

        // 2. Tuyến thay thế (alternates)
        val alternates = root.optJSONArray("alternates")
        if (alternates != null && primaryRoute != null) {
            for (i in 0 until alternates.length()) {
                val altTrip = alternates.getJSONObject(i).optJSONObject("trip") ?: continue
                val altRoute = parseSingleValhallaTrip(altTrip, isFastest = false)
                if (altRoute != null) {
                    altRoute.timeDifferenceSeconds = altRoute.durationSeconds - primaryRoute.durationSeconds
                    altRoute.isFastest = false
                    routesList.add(altRoute)
                }
            }
        }

        return routesList
    }

    private fun parseSingleValhallaTrip(trip: JSONObject, isFastest: Boolean): OSMRoute? {
        val summary = trip.optJSONObject("summary") ?: return null
        val distanceKm = summary.optDouble("length", 0.0)
        val durationSec = summary.optDouble("time", 0.0)
        val legs = trip.optJSONArray("legs") ?: return null

        val allPoints = mutableListOf<LatLng>()
        val allSteps = mutableListOf<NavigationStep>()
        var mainRoad = ""

        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val shape = leg.optString("shape", "")
            if (shape.isNotBlank()) {
                allPoints.addAll(decodeValhallaPolyline(shape))
            }

            val maneuvers = leg.optJSONArray("maneuvers") ?: continue
            for (m in 0 until maneuvers.length()) {
                val man = maneuvers.getJSONObject(m)
                val type = man.optInt("type", 0)
                val instructionText = man.optString("instruction", "")
                val lengthKm = man.optDouble("length", 0.0)
                val lengthMeters = (lengthKm * 1000).toInt()
                val streetNames = man.optJSONArray("street_names")
                val road = if (streetNames != null && streetNames.length() > 0) streetNames.getString(0) else ""
                if (mainRoad.isBlank() && road.isNotBlank()) mainRoad = road

                val direction = mapValhallaManeuverToDirection(type)
                val coord = if (allPoints.isNotEmpty()) allPoints.last() else LatLng(0.0, 0.0)

                allSteps.add(
                    NavigationStep(
                        instruction = instructionText,
                        distanceMeters = lengthMeters,
                        direction = direction,
                        coordinate = coord,
                        roadName = road
                    )
                )
            }
        }

        if (allPoints.isEmpty()) return null

        val routeTitle = if (mainRoad.isNotBlank()) "Qua $mainRoad" else "Tuyến đường chính"
        return OSMRoute(
            coordinates = allPoints,
            distanceMeters = distanceKm * 1000.0,
            durationSeconds = durationSec,
            steps = allSteps,
            routeName = routeTitle,
            isFastest = isFastest
        )
    }

    private fun mapValhallaManeuverToDirection(type: Int): NavigationDirection {
        return when (type) {
            1, 2, 3 -> NavigationDirection.STRAIGHT
            4, 5 -> NavigationDirection.DESTINATION
            6 -> NavigationDirection.DESTINATION
            7 -> NavigationDirection.DESTINATION
            8 -> NavigationDirection.SLIGHT_RIGHT
            9 -> NavigationDirection.RIGHT
            10 -> NavigationDirection.SHARP_RIGHT
            11 -> NavigationDirection.U_TURN
            12 -> NavigationDirection.U_TURN
            13 -> NavigationDirection.SHARP_LEFT
            14 -> NavigationDirection.LEFT
            15 -> NavigationDirection.SLIGHT_LEFT
            16 -> NavigationDirection.STRAIGHT
            17, 18, 19 -> NavigationDirection.ROUNDABOUT
            else -> NavigationDirection.STRAIGHT
        }
    }

    private fun decodeValhallaPolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat.toDouble() / 1e6, lng.toDouble() / 1e6))
        }
        return poly
    }

    private suspend fun requestOSRMRoutes(start: LatLng, destination: LatLng): List<OSMRoute> = withContext(Dispatchers.IO) {
        val coordPath = "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}"
        val queryParams = "overview=full&steps=true&geometries=geojson"

        // 1. Thử OSM Routing Germany Bike (xe máy / xe hai bánh tránh cao tốc)
        var routes = requestOSMRoutesFromUrl("https://routing.openstreetmap.de/routed-bike/route/v1/driving/$coordPath?$queryParams")
        if (routes.isNotEmpty()) return@withContext routes

        // 2. Thử OSM Routing Germany Car
        routes = requestOSMRoutesFromUrl("$OSM_ROUTING_DE_URL/route/v1/driving/$coordPath?$queryParams")
        if (routes.isNotEmpty()) return@withContext routes

        // 3. Thử Project OSRM HTTPS
        routes = requestOSMRoutesFromUrl("$OSRM_DEFAULT_URL/route/v1/driving/$coordPath?$queryParams")
        if (routes.isNotEmpty()) return@withContext routes

        // 4. Thử Project OSRM HTTP cleartext fallback
        routes = requestOSMRoutesFromUrl("$OSRM_FALLBACK_HTTP_URL/route/v1/driving/$coordPath?$queryParams")
        routes
    }

    private fun requestOSMRoutesFromUrl(url: String): List<OSMRoute> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TFTLink/0.1 (personal motorcycle display app)")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseOSRMJson(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseOSRMJson(body: String): List<OSMRoute> {
        val json = JSONObject(body)
        val routes = json.optJSONArray("routes") ?: return emptyList()
        if (routes.length() == 0) return emptyList()

        val r = routes.getJSONObject(0)
        val distance = r.optDouble("distance", 0.0)
        val duration = r.optDouble("duration", 0.0)

        val geom = r.optJSONObject("geometry")
        val coords = geom?.optJSONArray("coordinates") ?: return emptyList()
        val points = mutableListOf<LatLng>()
        for (i in 0 until coords.length()) {
            val pt = coords.getJSONArray(i)
            points.add(LatLng(pt.getDouble(1), pt.getDouble(0)))
        }

        val stepsList = mutableListOf<NavigationStep>()
        var mainRoadName = ""
        val legs = r.optJSONArray("legs")
        if (legs != null && legs.length() > 0) {
            val legSteps = legs.getJSONObject(0).optJSONArray("steps")
            if (legSteps != null) {
                for (i in 0 until legSteps.length()) {
                    val s = legSteps.getJSONObject(i)
                    val name = s.optString("name", "")
                    if (mainRoadName.isBlank() && name.isNotBlank()) mainRoadName = name
                    val dist = s.optDouble("distance", 0.0).toInt()
                    val man = s.optJSONObject("maneuver")
                    val manType = man?.optString("type", "turn") ?: "turn"
                    val modifier = man?.optString("modifier", "") ?: ""
                    val dir = mapOSRMModifierToDirection(manType, modifier)
                    val stepLoc = man?.optJSONArray("location")
                    val coord = if (stepLoc != null) LatLng(stepLoc.getDouble(1), stepLoc.getDouble(0)) else points.first()

                    val instructionText = when (dir) {
                        NavigationDirection.DESTINATION -> "Đến điểm đích"
                        NavigationDirection.U_TURN -> "Quay đầu xe"
                        NavigationDirection.ROUNDABOUT -> "Đi vào bùng binh"
                        NavigationDirection.RIGHT, NavigationDirection.SHARP_RIGHT, NavigationDirection.SLIGHT_RIGHT -> if (name.isNotBlank()) "Rẽ phải vào $name" else "Rẽ phải"
                        NavigationDirection.LEFT, NavigationDirection.SHARP_LEFT, NavigationDirection.SLIGHT_LEFT -> if (name.isNotBlank()) "Rẽ trái vào $name" else "Rẽ trái"
                        else -> if (name.isNotBlank()) "Đi thẳng trên $name" else "Đi thẳng"
                    }

                    stepsList.add(
                        NavigationStep(
                            instruction = instructionText,
                            distanceMeters = dist,
                            direction = dir,
                            coordinate = coord,
                            roadName = name
                        )
                    )
                }
            }
        }

        val routeTitle = if (mainRoadName.isNotBlank()) "Qua $mainRoadName" else "Tuyến đường tối ưu"
        return listOf(
            OSMRoute(
                coordinates = points,
                distanceMeters = distance,
                durationSeconds = duration,
                steps = stepsList,
                routeName = routeTitle,
                isFastest = true
            )
        )
    }

    private fun mapOSRMModifierToDirection(type: String, modifier: String): NavigationDirection {
        if (type == "arrive") return NavigationDirection.DESTINATION
        if (type == "roundabout") return NavigationDirection.ROUNDABOUT
        return when (modifier) {
            "slight right" -> NavigationDirection.SLIGHT_RIGHT
            "right" -> NavigationDirection.RIGHT
            "sharp right" -> NavigationDirection.SHARP_RIGHT
            "uturn" -> NavigationDirection.U_TURN
            "sharp left" -> NavigationDirection.SHARP_LEFT
            "left" -> NavigationDirection.LEFT
            "slight left" -> NavigationDirection.SLIGHT_LEFT
            else -> NavigationDirection.STRAIGHT
        }
    }

    private fun applyCalculatedRoutes(routes: List<OSMRoute>, autoStart: Boolean) {
        val primary = routes.firstOrNull() ?: return
        _allCalculatedRoutes.value = routes
        _alternativeRoutes.value = routes.drop(1)
        applyCalculatedRoute(primary, autoStart)
    }

    fun selectRoute(route: OSMRoute) {
        if (_activeRoute.value?.id == route.id) return
        val target = _allCalculatedRoutes.value.firstOrNull { it.id == route.id } ?: route
        _activeRoute.value = target
        _alternativeRoutes.value = _allCalculatedRoutes.value.filter { it.id != target.id }
        this.routeSteps = target.steps
        this.currentStepIndex = 0
        _arrivalMessage.value = null
        updateCurrentInstructionUI()
    }

    fun applyCalculatedRoute(route: OSMRoute, autoStart: Boolean = true) {
        _isCalculatingRoute.value = false
        _isRerouting.value = false
        offRouteConsecutiveTicks = 0
        _activeRoute.value = route
        this.routeSteps = route.steps
        this.currentStepIndex = 0
        _arrivalMessage.value = null
        _isNavigating.value = autoStart

        updateRemainingDistanceAndETA()
        if (autoStart) {
            updateCurrentInstructionUI()
            val firstStep = routeSteps.firstOrNull()
            if (firstStep != null) {
                speakInstruction("Bắt đầu dẫn đường. ${firstStep.instruction}")
            }
        }
    }

    fun startActiveNavigation() {
        if (_activeRoute.value == null) return
        _isNavigating.value = true
        updateRemainingDistanceAndETA()
        updateCurrentInstructionUI()
        val first = routeSteps.firstOrNull()
        if (first != null) {
            speakInstruction("Bắt đầu dẫn đường. ${first.instruction}")
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _alternativeRoutes.value = emptyList()
        _allCalculatedRoutes.value = emptyList()
        _arrivalMessage.value = null
        _currentInstruction.value = NavigationInstruction()
        ccuManager?.updateRoadNameOnTFT("")
    }

    /**
     * Tiến trình cập nhật Turn-by-Turn, cự ly còn lại, phát hiện lệch tuyến và đến nơi
     */
    private fun processNavigationProgress(currentLoc: LatLng, speedKmh: Double) {
        val route = _activeRoute.value ?: return
        val dest = destinationCoordinate

        // 1. Kiểm tra đã đến đích chưa (< 25 mét)
        if (dest != null) {
            val distToDest = GeoUtils.computeDistanceMeters(currentLoc, dest)
            if (distToDest <= 25.0) {
                val msg = "Bạn đã đến nơi: ${_destinationTitle.value}"
                _arrivalMessage.value = msg
                _isNavigating.value = false
                speakInstruction("Bạn đã đến nơi.")
                ccuManager?.updateRoadNameOnTFT("ĐÃ ĐẾN NƠI")
                return
            }
        }

        // 2. Cập nhật bước rẽ tiếp theo (Step Progression)
        if (currentStepIndex < routeSteps.size) {
            val currentStep = routeSteps[currentStepIndex]
            val distToStep = GeoUtils.computeDistanceMeters(currentLoc, currentStep.coordinate)

            // Cảnh báo giọng nói
            if (currentStepIndex != lastSpokenStepIndex) {
                lastSpokenStepIndex = currentStepIndex
                hasSpoken300mAlert = false
                hasSpoken100mAlert = false
            }
            if (!hasSpoken300mAlert && distToStep in 180.0..320.0) {
                hasSpoken300mAlert = true
                speakInstruction("Khoảng 300 mét nữa, ${currentStep.instruction}")
            } else if (!hasSpoken100mAlert && distToStep in 60.0..120.0) {
                hasSpoken100mAlert = true
                speakInstruction("Khoảng 100 mét nữa, ${currentStep.instruction}")
            }

            // Chuyển sang bước kế tiếp khi đã vượt qua điểm rẽ (< 25 mét)
            if (distToStep < 25.0 && currentStepIndex < routeSteps.size - 1) {
                currentStepIndex++
                hasSpoken300mAlert = false
                hasSpoken100mAlert = false
            }
        }

        // 3. Cập nhật thông số HUD & đồng hồ TFT
        updateRemainingDistanceAndETA()
        updateCurrentInstructionUI()

        // 4. Phát hiện lệch tuyến (Off-route deviance check)
        checkRouteDeviance(currentLoc)
    }

    private fun checkRouteDeviance(currentLoc: LatLng) {
        val route = _activeRoute.value ?: return
        val dest = destinationCoordinate ?: return
        val points = route.coordinates
        if (points.isEmpty()) return

        // Tìm khoảng cách ngắn nhất từ vị trí hiện tại tới polyline
        var minDistance = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val dist = distanceToSegment(currentLoc, points[i], points[i + 1])
            if (dist < minDistance) {
                minDistance = dist
            }
        }

        // Nếu lệch khỏi tuyến đường > 45 mét trong 3 lần cập nhật GPS liên tiếp
        if (minDistance > 45.0) {
            offRouteConsecutiveTicks++
            if (offRouteConsecutiveTicks >= 3) {
                val now = System.currentTimeMillis()
                if (now - lastRerouteTimeMs > 10000) { // Không reroute quá dồn dập trong 10s
                    lastRerouteTimeMs = now
                    offRouteConsecutiveTicks = 0
                    _isRerouting.value = true
                    speakInstruction("Đang tính toán lại lộ trình...")
                    calculateRoute(origin = currentLoc, intermediateStops = intermediateStops, destination = dest, destinationTitle = _destinationTitle.value, autoStart = true)
                }
            }
        } else {
            offRouteConsecutiveTicks = 0
        }
    }

    private fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val l2 = (b.latitude - a.latitude) * (b.latitude - a.latitude) + (b.longitude - a.longitude) * (b.longitude - a.longitude)
        if (l2 == 0.0) return GeoUtils.computeDistanceMeters(p, a)
        var t = ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / l2
        t = t.coerceIn(0.0, 1.0)
        val projection = LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
        return GeoUtils.computeDistanceMeters(p, projection)
    }

    private fun updateRemainingDistanceAndETA() {
        val route = _activeRoute.value ?: return
        val loc = _currentLocation.value ?: return
        val dest = destinationCoordinate ?: return

        val distToDest = GeoUtils.computeDistanceMeters(loc, dest).toInt()
        val speedMps = (_currentSpeedKmh.value * 1000.0 / 3600.0).coerceAtLeast(6.0) // Giả định tốc độ tối thiểu 20 km/h
        val remainingSec = (distToDest / speedMps).toInt()

        val etaTime = Date(System.currentTimeMillis() + remainingSec * 1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val etaStr = sdf.format(etaTime)
        val mins = (remainingSec / 60).coerceAtLeast(1)
        val timeFormatted = if (mins < 60) "$mins phút" else "${mins / 60}h ${mins % 60}p"

        val inst = _currentInstruction.value.copy(
            totalDistanceRemainingMeters = distToDest,
            totalTimeRemainingSeconds = remainingSec,
            etaString = etaStr,
            remainingTimeFormatted = timeFormatted,
            destinationName = _destinationTitle.value
        )
        _currentInstruction.value = inst
    }

    private fun updateCurrentInstructionUI() {
        val loc = _currentLocation.value
        val step = if (currentStepIndex in routeSteps.indices) routeSteps[currentStepIndex] else null
        val distToNextTurn = if (step != null && loc != null) {
            GeoUtils.computeDistanceMeters(loc, step.coordinate).roundToInt()
        } else {
            step?.distanceMeters ?: 0
        }

        val direction = step?.direction ?: NavigationDirection.STRAIGHT
        val road = step?.roadName ?: ""
        val detailed = step?.instruction ?: "Đi thẳng"

        _currentRoadName.value = road

        val updated = _currentInstruction.value.copy(
            direction = direction,
            distanceMeters = distToNextTurn,
            nextRoadName = road,
            detailedInstruction = detailed,
            isNavigating = _isNavigating.value
        )
        _currentInstruction.value = updated

        // Bắn sang CCU xe máy để hiển thị trên màn hình TFT xe
        ccuManager?.updateRoadNameOnTFT(if (road.isNotBlank()) road else detailed)
        ccuManager?.sendTurnByTurn(updated)
    }
}
