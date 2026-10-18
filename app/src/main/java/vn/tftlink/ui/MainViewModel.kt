package vn.tftlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.tftlink.data.local.AppDatabase
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.data.model.bike.BikeDevice
import vn.tftlink.data.model.bike.BikeTelemetry
import vn.tftlink.data.model.location.ParkedBikeLocation
import vn.tftlink.data.model.location.SavedPlace
import vn.tftlink.data.model.nav.NavigationDirection
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.OSMRoute
import vn.tftlink.data.model.trip.TripRecord
import vn.tftlink.service.ble.YamahaCCUManager
import vn.tftlink.service.renderer.TFTStreamRenderer
import vn.tftlink.util.GeoUtils

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("nconnect_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val tripDao = AppDatabase.getInstance(application).tripDao()

    // CCU & Renderer
    val ccuManager = YamahaCCUManager.getInstance(application, viewModelScope)
    val streamRenderer = TFTStreamRenderer(application, viewModelScope)

    // State flows from CCU
    val connectionState: StateFlow<BikeConnectionState> = ccuManager.state
    val connectedBike: StateFlow<BikeDevice?> = ccuManager.connectedBike
    val discoveredBikes: StateFlow<List<BikeDevice>> = ccuManager.discoveredBikes
    val telemetry: StateFlow<BikeTelemetry> = ccuManager.telemetry
    val currentRoadNameOnTFT: StateFlow<String> = ccuManager.currentRoadNameOnTFT
    val diagnosticLogs: StateFlow<List<String>> = ccuManager.diagnosticLogs

    // TFT Mirror Bitmap
    val lastFrameBitmap: StateFlow<Bitmap?> = streamRenderer.lastFrameBitmap

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _zoomDescription = MutableStateFlow("ZOOM: 200m (OSM 18)")
    val zoomDescription: StateFlow<String> = _zoomDescription.asStateFlow()

    // GPS & Location tracking
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    // Navigation State
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _activeRoute = MutableStateFlow<OSMRoute?>(null)
    val activeRoute: StateFlow<OSMRoute?> = _activeRoute.asStateFlow()

    private val _currentInstruction = MutableStateFlow(NavigationInstruction())
    val currentInstruction: StateFlow<NavigationInstruction> = _currentInstruction.asStateFlow()

    private val _arrivalMessage = MutableStateFlow<String?>(null)
    val arrivalMessage: StateFlow<String?> = _arrivalMessage.asStateFlow()

    private val _isNorthUp = MutableStateFlow(false)
    val isNorthUp: StateFlow<Boolean> = _isNorthUp.asStateFlow()

    // Parking Location (Find My Bike)
    private val _parkedLocation = MutableStateFlow<ParkedBikeLocation?>(null)
    val parkedLocation: StateFlow<ParkedBikeLocation?> = _parkedLocation.asStateFlow()

    // Saved Places
    private val _homePlace = MutableStateFlow<SavedPlace?>(null)
    val homePlace: StateFlow<SavedPlace?> = _homePlace.asStateFlow()

    private val _workPlace = MutableStateFlow<SavedPlace?>(null)
    val workPlace: StateFlow<SavedPlace?> = _workPlace.asStateFlow()

    private val _favoritePlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val favoritePlaces: StateFlow<List<SavedPlace>> = _favoritePlaces.asStateFlow()

    // Trip History from Room Database
    val tripHistory: StateFlow<List<TripRecord>> = tripDao.getAllTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live Recording Session
    private val _isRecordingSession = MutableStateFlow(false)
    val isRecordingSession: StateFlow<Boolean> = _isRecordingSession.asStateFlow()

    private val _sessionDistanceMeters = MutableStateFlow(0.0)
    val sessionDistanceMeters: StateFlow<Double> = _sessionDistanceMeters.asStateFlow()

    private val _sessionDurationSeconds = MutableStateFlow(0L)
    val sessionDurationSeconds: StateFlow<Long> = _sessionDurationSeconds.asStateFlow()

    // Location callback
    private var locationCallback: LocationCallback? = null

    init {
        loadPersistedPlaces()
        loadPersistedParking()
        setupCCUCallbacks()
        startLocationUpdates()
        updateZoomText()
    }

    private fun setupCCUCallbacks() {
        ccuManager.onZoomIn = {
            zoomIn()
        }
        ccuManager.onZoomOut = {
            zoomOut()
        }
        ccuManager.onStopRoute = {
            stopNavigation()
        }
        ccuManager.onGoHome = {
            homePlace.value?.let { startNavigationTo(it.coordinate, it.name) }
        }
        ccuManager.onGoToWork = {
            workPlace.value?.let { startNavigationTo(it.coordinate, it.name) }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setMinUpdateDistanceMeters(1f)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    _currentLocation.value = latLng
                    if (loc.hasBearing()) {
                        _currentHeading.value = loc.bearing
                    }
                    if (loc.hasSpeed()) {
                        _currentSpeedKmh.value = loc.speed * 3.6
                    }

                    // Nếu đang trong chế độ preview/stream
                    if (_isStreaming.value) {
                        viewModelScope.launch(Dispatchers.Default) {
                            streamRenderer.renderLiveMapFrame(
                                location = latLng,
                                course = _currentHeading.value,
                                activeRoute = _activeRoute.value,
                                instruction = _currentInstruction.value,
                                speedKmh = _currentSpeedKmh.value
                            )
                        }
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                req,
                locationCallback as LocationCallback,
                Looper.getMainLooper()
            )

            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    _currentLocation.value = LatLng(loc.latitude, loc.longitude)
                    if (loc.hasBearing()) _currentHeading.value = loc.bearing
                    if (loc.hasSpeed()) _currentSpeedKmh.value = loc.speed * 3.6
                }
            }
        } catch (_: Exception) {
            // Permission might be requested later
        }
    }

    fun startScanning() {
        ccuManager.startScanning()
    }

    fun stopScanning() {
        ccuManager.stopScanning()
    }

    fun connectBike(bike: BikeDevice) {
        ccuManager.connect(bike)
    }

    fun disconnect() {
        stopStreaming()
        ccuManager.disconnect()
    }

    fun toggleStreaming() {
        if (_isStreaming.value) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    fun startStreaming() {
        _isStreaming.value = true
        ccuManager.startStreaming()
        streamRenderer.startStreamingLoop(
            getLocation = { _currentLocation.value ?: LatLng(10.7769, 106.7009) },
            getCourse = { _currentHeading.value },
            getActiveRoute = { _activeRoute.value },
            getAlternativeRoutes = { emptyList() },
            getInstruction = { _currentInstruction.value },
            getSpeed = { _currentSpeedKmh.value },
            getIsRerouting = { false },
            onFrameReady = { jpegBytes ->
                ccuManager.sendLiveMapFrameToTFT(jpegBytes)
            }
        )
    }

    fun stopStreaming() {
        _isStreaming.value = false
        streamRenderer.stopStreamingLoop()
        ccuManager.stopStreaming()
    }

    fun zoomIn() {
        streamRenderer.zoomIn { jpegBytes ->
            if (_isStreaming.value) {
                ccuManager.sendLiveMapFrameToTFT(jpegBytes)
            }
        }
        updateZoomText()
    }

    fun zoomOut() {
        streamRenderer.zoomOut { jpegBytes ->
            if (_isStreaming.value) {
                ccuManager.sendLiveMapFrameToTFT(jpegBytes)
            }
        }
        updateZoomText()
    }

    fun toggleNorthUp() {
        _isNorthUp.value = !_isNorthUp.value
        streamRenderer.isNorthUp = _isNorthUp.value
    }

    private fun updateZoomText() {
        val dist = TFTStreamRenderer.ZOOM_LEVELS.getOrNull(streamRenderer.currentZoomIndex) ?: 200.0
        val osmZoom = streamRenderer.currentOsmZoom
        _zoomDescription.value = "ZOOM: ${dist.toInt()}m (OSM $osmZoom)"
    }

    // Navigation triggers
    fun startNavigationTo(dest: LatLng, title: String) {
        val start = _currentLocation.value ?: LatLng(10.7769, 106.7009)
        val dist = GeoUtils.computeDistanceMeters(start, dest)
        val etaSecs = (dist / 11.1).toInt() // ~40km/h avg speed

        val route = OSMRoute(
            coordinates = listOf(start, LatLng((start.latitude + dest.latitude)/2, (start.longitude + dest.longitude)/2), dest),
            distanceMeters = dist,
            durationSeconds = etaSecs.toDouble()
        )
        _activeRoute.value = route
        _currentInstruction.value = NavigationInstruction(
            detailedInstruction = "Đi thẳng hướng $title",
            nextRoadName = title,
            currentRoadName = title,
            distanceMeters = dist.toInt(),
            direction = NavigationDirection.STRAIGHT,
            totalTimeRemainingSeconds = etaSecs
        )
        _isNavigating.value = true

        ccuManager.updateCurrentRoadNameOnTFT(
            rawRoadName = title,
            distanceMeters = dist,
            maneuverIcon = NavigationDirection.STRAIGHT.rawValue,
            etaText = "${etaSecs / 60}P"
        )

        if (!_isStreaming.value) {
            startStreaming()
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _currentInstruction.value = NavigationInstruction()
        ccuManager.stopNavigationOnTFT()
    }

    fun clearArrivalMessage() {
        _arrivalMessage.value = null
    }

    // Parking Location
    fun recordParkingLocation(coord: LatLng, bikeName: String = "Yamaha NMAX", address: String = "") {
        val parking = ParkedBikeLocation(
            latitude = coord.latitude,
            longitude = coord.longitude,
            bikeName = bikeName,
            address = address
        )
        _parkedLocation.value = parking
        prefs.edit().putString("parked_location_json", gson.toJson(parking)).apply()
    }

    fun clearParkingLocation() {
        _parkedLocation.value = null
        prefs.edit().remove("parked_location_json").apply()
    }

    private fun loadPersistedParking() {
        val json = prefs.getString("parked_location_json", null)
        if (!json.isNullOrEmpty()) {
            try {
                _parkedLocation.value = gson.fromJson(json, ParkedBikeLocation::class.java)
            } catch (_: Exception) {}
        }
    }

    // Saved Places
    fun setHomePlace(place: SavedPlace) {
        _homePlace.value = place
        prefs.edit().putString("home_place_json", gson.toJson(place)).apply()
    }

    fun setWorkPlace(place: SavedPlace) {
        _workPlace.value = place
        prefs.edit().putString("work_place_json", gson.toJson(place)).apply()
    }

    fun addFavoritePlace(place: SavedPlace) {
        val list = _favoritePlaces.value.toMutableList()
        list.add(place)
        _favoritePlaces.value = list
        prefs.edit().putString("fav_places_json", gson.toJson(list)).apply()
    }

    fun removeFavoritePlace(id: String) {
        val list = _favoritePlaces.value.filter { it.id != id }
        _favoritePlaces.value = list
        prefs.edit().putString("fav_places_json", gson.toJson(list)).apply()
    }

    private fun loadPersistedPlaces() {
        prefs.getString("home_place_json", null)?.let {
            try { _homePlace.value = gson.fromJson(it, SavedPlace::class.java) } catch (_: Exception) {}
        }
        prefs.getString("work_place_json", null)?.let {
            try { _workPlace.value = gson.fromJson(it, SavedPlace::class.java) } catch (_: Exception) {}
        }
        prefs.getString("fav_places_json", null)?.let {
            try {
                val type = object : TypeToken<List<SavedPlace>>() {}.type
                _favoritePlaces.value = gson.fromJson(it, type)
            } catch (_: Exception) {}
        }
    }

    // Trip actions
    fun deleteTrip(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            tripDao.deleteTrip(id)
        }
    }

    fun clearAllTrips() {
        viewModelScope.launch(Dispatchers.IO) {
            tripDao.clearAllTrips()
        }
    }

    fun addSampleTrip() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val sample = TripRecord(
                startTime = now - 1800000,
                endTime = now,
                startLatitude = 10.7769,
                startLongitude = 106.7009,
                startAddress = "Nhà Thờ Đức Bà, Quận 1",
                endLatitude = 10.7932,
                endLongitude = 106.6917,
                endAddress = "Landmark 81, Bình Thạnh",
                distanceMeters = 8400.0,
                durationSeconds = 1800,
                averageSpeedKmh = 28.0,
                maxSpeedKmh = 54.0,
                parkingTimestamp = now,
                bikeName = connectedBike.value?.name ?: "Yamaha NMAX"
            )
            tripDao.insertTrip(sample)
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        streamRenderer.stopStreamingLoop()
    }
}
