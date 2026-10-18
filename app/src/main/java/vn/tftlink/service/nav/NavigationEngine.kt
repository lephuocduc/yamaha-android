package vn.tftlink.service.nav

import android.content.Context
import android.location.Geocoder
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class NavigationEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val VALHALLA_DEFAULT_URL = "https://valhalla1.openstreetmap.de"
        const val OSRM_DEFAULT_URL = "https://router.project-osrm.org"
        const val PHOTON_URL = "https://photon.komoot.io/api/"

        @Volatile
        private var instance: NavigationEngine? = null

        fun getInstance(context: Context, scope: CoroutineScope): NavigationEngine {
            return instance ?: synchronized(this) {
                instance ?: NavigationEngine(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
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

    init {
        initTts()
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
        _currentLocation.value = latLng
        _currentSpeedKmh.value = speedKmh
        _currentHeading.value = heading

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
        searchJob = scope.launch(Dispatchers.IO) {
            delay(250) // Debounce
            try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                var urlStr = "$PHOTON_URL?q=$encoded&limit=12&lang=vi"
                _currentLocation.value?.let { loc ->
                    urlStr += "&lat=${loc.latitude}&lon=${loc.longitude}"
                }

                val request = Request.Builder()
                    .url(urlStr)
                    .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    fallbackNominatimSearch(trimmed)
                    return@launch
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val features = json.optJSONArray("features") ?: JSONArray()
                val items = mutableListOf<SavedPlace>()

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
                    val district = props.optString("district", props.optString("county", ""))
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

                withContext(Dispatchers.Main) {
                    _searchResults.value = items
                    _isSearching.value = false
                }
            } catch (e: Exception) {
                fallbackNominatimSearch(trimmed)
            }
        }
    }

    private suspend fun fallbackNominatimSearch(query: String) {
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=10&accept-language=vi"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val array = JSONArray(body)
                val items = mutableListOf<SavedPlace>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("display_name").split(",").firstOrNull()?.trim() ?: query
                    val address = obj.optString("display_name")
                    val lat = obj.optDouble("lat")
                    val lon = obj.optDouble("lon")
                    items.add(SavedPlace(name = name, address = address, latitude = lat, longitude = lon))
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
     * Tra cứu địa chỉ từ toạ độ (Reverse Geocode)
     */
    fun reverseGeocode(latLng: LatLng, onResult: (title: String, address: String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var title = "Vị trí đã chọn"
            var address = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)

            // 1. Thử Android Geocoder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val geocoder = Geocoder(context, Locale("vi", "VN"))
                    val list = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    if (!list.isNullOrEmpty()) {
                        val p = list[0]
                        title = p.featureName ?: p.thoroughfare ?: title
                        val parts = listOfNotNull(p.subThoroughfare, p.thoroughfare, p.subLocality, p.locality, p.adminArea)
                        if (parts.isNotEmpty()) address = parts.joinToString(", ")
                        withContext(Dispatchers.Main) { onResult(title, address) }
                        return@launch
                    }
                } catch (_: Exception) {}
            }

            // 2. Dự phòng OSM Nominatim
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${latLng.latitude}&lon=${latLng.longitude}&zoom=18&addressdetails=1&accept-language=vi"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val displayName = json.optString("display_name", "")
                val addrObj = json.optJSONObject("address")
                val road = addrObj?.optString("road", "")
                val name = json.optString("name", "")

                title = if (name.isNotBlank()) name else if (!road.isNullOrBlank()) road else displayName.split(",").firstOrNull() ?: title
                if (displayName.isNotBlank()) address = displayName
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                onResult(title, address)
            }
        }
    }

    /**
     * Tìm cây xăng lân cận (trong bán kính 8km)
     */
    fun fetchNearbyGasStations() {
        val loc = _currentLocation.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val url = "$PHOTON_URL?q=petrol&lat=${loc.latitude}&lon=${loc.longitude}&limit=15&lang=vi"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val features = json.optJSONArray("features") ?: JSONArray()
                val list = mutableListOf<SavedPlace>()

                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.optJSONObject("geometry") ?: continue
                    val coords = geom.optJSONArray("coordinates") ?: continue
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val props = f.optJSONObject("properties") ?: JSONObject()
                    val name = props.optString("name", "Cây xăng")
                    val street = props.optString("street", "")
                    val city = props.optString("city", "")
                    val dist = GeoUtils.computeDistanceMeters(loc, LatLng(lat, lon))
                    val distStr = if (dist < 1000) "Cách ${dist.toInt()} m" else String.format(Locale.US, "Cách %.1f km", dist / 1000.0)
                    val addr = if (street.isNotBlank()) "$distStr • $street, $city" else "$distStr • $city"

                    list.add(SavedPlace(name = name, address = addr, latitude = lat, longitude = lon))
                }

                // Sắp xếp theo cự ly gần nhất
                val sorted = list.sortedBy { GeoUtils.computeDistanceMeters(loc, it.coordinate) }

                withContext(Dispatchers.Main) {
                    _nearbyGasStations.value = sorted
                }
            } catch (_: Exception) {}
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
        autoStart: Boolean = true
    ) {
        val start = origin ?: _currentLocation.value
        if (start == null) {
            return
        }

        this.destinationCoordinate = destination
        this.intermediateStops = intermediateStops
        _destinationTitle.value = destinationTitle
        _isCalculatingRoute.value = true
        _alternativeRoutes.value = emptyList()
        _allCalculatedRoutes.value = emptyList()

        scope.launch(Dispatchers.IO) {
            val routes = requestValhallaRoutes(start, intermediateStops, destination)
            val finalRoutes = if (routes.isNotEmpty()) {
                routes
            } else {
                requestOSRMRoutes(start, destination)
            }

            withContext(Dispatchers.Main) {
                _isCalculatingRoute.value = false
                if (finalRoutes.isNotEmpty()) {
                    applyCalculatedRoutes(finalRoutes, autoStart)
                }
            }
        }
    }

    private suspend fun requestValhallaRoutes(
        start: LatLng,
        stops: List<LatLng>,
        destination: LatLng
    ): List<OSMRoute> = withContext(Dispatchers.IO) {
        try {
            val url = "$VALHALLA_DEFAULT_URL/route"
            val locationsArray = JSONArray()
            locationsArray.put(JSONObject().put("lat", start.latitude).put("lon", start.longitude).put("type", "break"))
            for (stop in stops) {
                locationsArray.put(JSONObject().put("lat", stop.latitude).put("lon", stop.longitude).put("type", "break"))
            }
            locationsArray.put(JSONObject().put("lat", destination.latitude).put("lon", destination.longitude).put("type", "break"))

            val motorcycleOptions = JSONObject().apply {
                put("use_highways", 0.0) // Tránh cao tốc cấm xe máy ở Việt Nam
                put("use_primary", 0.9)  // Ưu tiên đường lớn, đại lộ
                put("use_tolls", 0.0)    // Tránh trạm thu phí
                put("use_trails", 0.0)   // Tránh đường mòn
                put("alley_factor", 5.0) // Tránh hẻm nhỏ
                put("top_speed", 65)     // Giới hạn tốc độ xe máy
            }

            val payload = JSONObject().apply {
                put("locations", locationsArray)
                put("costing", "motorcycle")
                put("costing_options", JSONObject().put("motorcycle", motorcycleOptions))
                put("alternates", if (stops.isEmpty()) 2 else 0)
                put("directions_options", JSONObject().apply {
                    put("units", "kilometers")
                    put("language", "vi-VN")
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            parseValhallaRoutes(body)
        } catch (_: Exception) {
            emptyList()
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

        val routeTitle = if (mainRoad.isNotBlank()) "Qua $mainRoad" else "Tuyến chính"
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
        try {
            val url = "$OSRM_DEFAULT_URL/route/v1/driving/${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}?overview=full&steps=true&geometries=geojson"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NConnect-Yamaha-Android/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val routes = json.optJSONArray("routes") ?: return@withContext emptyList()
            if (routes.length() == 0) return@withContext emptyList()

            val r = routes.getJSONObject(0)
            val distance = r.optDouble("distance", 0.0)
            val duration = r.optDouble("duration", 0.0)

            val geom = r.optJSONObject("geometry")
            val coords = geom?.optJSONArray("coordinates") ?: return@withContext emptyList()
            val points = mutableListOf<LatLng>()
            for (i in 0 until coords.length()) {
                val pt = coords.getJSONArray(i)
                points.add(LatLng(pt.getDouble(1), pt.getDouble(0)))
            }

            val stepsList = mutableListOf<NavigationStep>()
            val legs = r.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
                val legSteps = legs.getJSONObject(0).optJSONArray("steps")
                if (legSteps != null) {
                    for (i in 0 until legSteps.length()) {
                        val s = legSteps.getJSONObject(i)
                        val name = s.optString("name", "")
                        val dist = s.optDouble("distance", 0.0).toInt()
                        val man = s.optJSONObject("maneuver")
                        val manType = man?.optString("type", "turn") ?: "turn"
                        val modifier = man?.optString("modifier", "") ?: ""
                        val dir = mapOSRMModifierToDirection(manType, modifier)
                        val stepLoc = man?.optJSONArray("location")
                        val coord = if (stepLoc != null) LatLng(stepLoc.getDouble(1), stepLoc.getDouble(0)) else points.first()

                        stepsList.add(
                            NavigationStep(
                                instruction = "$modifier vào $name",
                                distanceMeters = dist,
                                direction = dir,
                                coordinate = coord,
                                roadName = name
                            )
                        )
                    }
                }
            }

            listOf(
                OSMRoute(
                    coordinates = points,
                    distanceMeters = distance,
                    durationSeconds = duration,
                    steps = stepsList,
                    routeName = "Tuyến OSRM dự phòng",
                    isFastest = true
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
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
