package vn.tftlink.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.data.model.bike.BikeDevice
import vn.tftlink.data.model.bike.BikeTelemetry
import vn.tftlink.data.model.location.ParkedBikeLocation
import vn.tftlink.data.model.location.SavedPlace
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.OSMRoute
import vn.tftlink.data.model.trip.TripRecord
import vn.tftlink.service.BikeForegroundService
import vn.tftlink.service.ble.YamahaCCUManager
import vn.tftlink.service.nav.NavigationEngine
import vn.tftlink.service.nav.ParkingLocationManager
import vn.tftlink.service.nav.SavedPlacesManager
import vn.tftlink.service.renderer.TFTStreamRenderer
import vn.tftlink.service.trip.TripHistoryManager
import vn.tftlink.service.update.AppUpdateInfo
import vn.tftlink.service.update.AppUpdateManager
import vn.tftlink.service.update.UpdateStatus
import android.content.Intent
import android.location.LocationManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.tftlink.service.nav.GoogleMapsShareResolver
import vn.tftlink.service.nav.ResolvedDestination
import vn.tftlink.service.renderer.ClusterMapSource
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 1. Core Hardware & BLE CCU Manager
    val ccuManager = YamahaCCUManager.getInstance(application, viewModelScope)
    val streamRenderer = TFTStreamRenderer(application, viewModelScope)

    // 2. Navigation & Map Services
    val navEngine = NavigationEngine.getInstance(application, viewModelScope)
    val parkingManager = ParkingLocationManager.getInstance(application, viewModelScope)
    val savedPlacesManager = SavedPlacesManager.getInstance(application)

    // 3. Trip History Manager
    val tripManager = TripHistoryManager.getInstance(application, viewModelScope)

    // 4. In-App Software Updater
    val updateManager = AppUpdateManager.getInstance(application, viewModelScope)
    val updateStatus: StateFlow<UpdateStatus> = updateManager.status

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

    private val _zoomDescription = MutableStateFlow("200m (Chuẩn)")
    val zoomDescription: StateFlow<String> = _zoomDescription.asStateFlow()

    // GPS & Navigation State Flows
    val currentLocation: StateFlow<LatLng?> = navEngine.currentLocation
    val currentHeading: StateFlow<Float> = navEngine.currentHeading
    val currentSpeedKmh: StateFlow<Double> = navEngine.currentSpeedKmh
    val isNavigating: StateFlow<Boolean> = navEngine.isNavigating
    val activeRoute: StateFlow<OSMRoute?> = navEngine.activeRoute
    val alternativeRoutes: StateFlow<List<OSMRoute>> = navEngine.alternativeRoutes
    val allCalculatedRoutes: StateFlow<List<OSMRoute>> = navEngine.allCalculatedRoutes
    val currentInstruction: StateFlow<NavigationInstruction> = navEngine.currentInstruction
    val isCalculatingRoute: StateFlow<Boolean> = navEngine.isCalculatingRoute
    val isSearching: StateFlow<Boolean> = navEngine.isSearching
    val searchResults: StateFlow<List<SavedPlace>> = navEngine.searchResults
    val nearbyGasStations: StateFlow<List<SavedPlace>> = navEngine.nearbyGasStations
    val arrivalMessage: StateFlow<String?> = navEngine.arrivalMessage
    val isRerouting: StateFlow<Boolean> = navEngine.isRerouting
    val destinationTitle: StateFlow<String> = navEngine.destinationTitle

    private val _isNorthUp = MutableStateFlow(false)
    val isNorthUp: StateFlow<Boolean> = _isNorthUp.asStateFlow()

    // Cluster Map Engine Mode (Google Maps Traffic vs OSM CartoDB)
    private val _clusterMapSource = MutableStateFlow(streamRenderer.clusterMapSource)
    val clusterMapSource: StateFlow<ClusterMapSource> = _clusterMapSource.asStateFlow()

    // Shared destination from Google Maps app
    private val _sharedDestinationEvent = MutableStateFlow<ResolvedDestination?>(null)
    val sharedDestinationEvent: StateFlow<ResolvedDestination?> = _sharedDestinationEvent.asStateFlow()

    // Route calculation error message
    val routeErrorMessage: StateFlow<String?> = navEngine.routeErrorMessage
    fun clearRouteErrorMessage() = navEngine.clearRouteErrorMessage()

    // Parking Location (Find My Bike)
    val parkedLocation: StateFlow<ParkedBikeLocation?> = parkingManager.lastParkedLocation

    // Saved Places
    val homePlace: StateFlow<SavedPlace?> = savedPlacesManager.homePlace
    val workPlace: StateFlow<SavedPlace?> = savedPlacesManager.workPlace
    val favoritePlaces: StateFlow<List<SavedPlace>> = savedPlacesManager.favoritePlaces

    // Trip History from Room Database
    val tripHistory: StateFlow<List<TripRecord>> = tripManager.allTrips
    val isRecordingSession: StateFlow<Boolean> = tripManager.isRecordingSession
    val sessionDistanceMeters: StateFlow<Double> = tripManager.sessionDistanceMeters
    val sessionDurationSeconds: StateFlow<Long> = tripManager.sessionDurationSeconds

    // Fused Location Client for foreground UI updates
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)
    private var locationCallback: LocationCallback? = null

    init {
        // Link cross-service references
        navEngine.ccuManager = ccuManager
        navEngine.streamRenderer = streamRenderer

        setupCCUCallbacks()
        startLocationUpdates()
        updateZoomText()
        ensurePreviewLoopRunning()

        // Khởi động foreground service duy trì kết nối nền
        try {
            BikeForegroundService.start(application)
        } catch (_: Exception) {}
    }

    private fun setupCCUCallbacks() {
        ccuManager.onZoomIn = { zoomIn() }
        ccuManager.onZoomOut = { zoomOut() }
        ccuManager.onStopRoute = { stopNavigation() }
        ccuManager.onGoHome = { navigateToHome() }
        ccuManager.onGoToWork = { navigateToWork() }
        ccuManager.onFindGasStation = { navEngine.fetchNearbyGasStations() }
        ccuManager.onBikeConnected = {
            ccuManager.log("Đã kết nối xe máy Yamaha - Bắt đầu truyền luồng màn hình")
            if (_isStreaming.value) {
                ccuManager.startStreaming()
            }
        }
        ccuManager.onBikeDisconnected = {
            val loc = navEngine.currentLocation.value
            val bikeName = connectedBike.value?.name ?: "Yamaha NMAX"
            if (loc != null) {
                parkingManager.recordParkingLocation(loc, bikeName)
                tripManager.endTripSession(loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val latLng = LatLng(loc.latitude, loc.longitude)
                val speedKmh = loc.speed * 3.6

                navEngine.updateLocation(latLng, speedKmh, loc.bearing)
                streamRenderer.updateLocation(latLng, loc.bearing)
                tripManager.updateLocation(latLng, speedKmh)
            }
        }

        try {
            // Lấy vị trí ngay lập tức từ LocationManager nếu FusedLocation chưa sẵn sàng
            val locMgr = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = locMgr?.getProviders(true) ?: emptyList()
            for (p in providers) {
                val l = locMgr?.getLastKnownLocation(p)
                if (l != null) {
                    val latLng = LatLng(l.latitude, l.longitude)
                    navEngine.updateLocation(latLng, l.speed * 3.6, l.bearing)
                    streamRenderer.updateLocation(latLng, l.bearing)
                    break
                }
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    val speedKmh = loc.speed * 3.6
                    navEngine.updateLocation(latLng, speedKmh, loc.bearing)
                    streamRenderer.updateLocation(latLng, loc.bearing)
                }
            }
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    // =========================================================================
    // CCU & BLUETOOTH ACTIONS
    // =========================================================================

    fun startScan() {
        ccuManager.startScanning()
    }

    fun startScanning() = startScan()

    fun stopScan() {
        ccuManager.stopScanning()
    }

    fun stopScanning() = stopScan()

    fun connectBike(device: BikeDevice) {
        ccuManager.connect(device)
    }

    fun disconnectBike() {
        ccuManager.disconnect()
    }

    fun disconnect() = disconnectBike()

    // =========================================================================
    // TFT STREAM RENDERER ACTIONS
    // =========================================================================

    fun ensurePreviewLoopRunning() {
        streamRenderer.startStreamingLoop(
            getLocation = { navEngine.currentLocation.value ?: LatLng(10.7769, 106.7009) },
            getCourse = { navEngine.currentHeading.value },
            getActiveRoute = { navEngine.activeRoute.value },
            getAlternativeRoutes = { navEngine.alternativeRoutes.value },
            getInstruction = { navEngine.currentInstruction.value },
            getSpeed = { navEngine.currentSpeedKmh.value },
            getIsRerouting = { navEngine.isRerouting.value },
            onFrameReady = { jpegBytes ->
                if (_isStreaming.value && (ccuManager.state.value == BikeConnectionState.CONNECTED || ccuManager.state.value == BikeConnectionState.STREAMING)) {
                    ccuManager.sendJPEGFrame(jpegBytes)
                }
            }
        )
    }

    fun startStreaming() {
        _isStreaming.value = true
        if (ccuManager.state.value == BikeConnectionState.CONNECTED) {
            ccuManager.startStreaming()
        }
        ensurePreviewLoopRunning()
    }

    fun stopStreaming() {
        _isStreaming.value = false
        ccuManager.stopStreaming()
    }

    fun toggleStreaming() {
        if (_isStreaming.value) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    fun zoomIn() {
        streamRenderer.zoomIn { jpegBytes ->
            if (_isStreaming.value && (ccuManager.state.value == BikeConnectionState.CONNECTED || ccuManager.state.value == BikeConnectionState.STREAMING)) {
                ccuManager.sendJPEGFrame(jpegBytes)
            }
        }
        updateZoomText()
    }

    fun zoomOut() {
        streamRenderer.zoomOut { jpegBytes ->
            if (_isStreaming.value && (ccuManager.state.value == BikeConnectionState.CONNECTED || ccuManager.state.value == BikeConnectionState.STREAMING)) {
                ccuManager.sendJPEGFrame(jpegBytes)
            }
        }
        updateZoomText()
    }

    private fun updateZoomText() {
        val zoom = streamRenderer.currentZoomLevel
        _zoomDescription.value = when (zoom) {
            19 -> "100m (Cận cảnh)"
            18 -> "200m (Chuẩn)"
            17 -> "400m (Toàn cảnh)"
            16 -> "800m (Đường dài)"
            15 -> "1.6km (Khu vực)"
            14 -> "3.0km (Thành phố)"
            else -> "${zoom}00m"
        }
    }

    // =========================================================================
    // NAVIGATION & ROUTING ACTIONS
    // =========================================================================

    fun searchPlaces(query: String) {
        navEngine.searchPlaces(query)
    }

    fun reverseGeocode(latLng: LatLng, onResult: (title: String, address: String) -> Unit) {
        navEngine.reverseGeocode(latLng, onResult)
    }

    fun fetchNearbyGasStations() {
        navEngine.fetchNearbyGasStations()
    }

    fun calculateRoute(
        destination: LatLng,
        destinationTitle: String = "Điểm đến",
        autoStart: Boolean = false
    ) {
        val origin = navEngine.currentLocation.value ?: navEngine.getEffectiveLocation()
        navEngine.calculateRoute(
            origin = origin,
            destination = destination,
            destinationTitle = destinationTitle,
            autoStart = autoStart
        )
    }

    fun calculateRouteWithStops(
        origin: LatLng? = null,
        intermediateStops: List<LatLng> = emptyList(),
        destination: LatLng,
        destinationTitle: String = "Điểm đến",
        autoStart: Boolean = true
    ) {
        navEngine.calculateRoute(
            origin = origin ?: navEngine.currentLocation.value,
            intermediateStops = intermediateStops,
            destination = destination,
            destinationTitle = destinationTitle,
            autoStart = autoStart
        )
    }

    fun selectRoute(route: OSMRoute) {
        navEngine.selectRoute(route)
    }

    fun startNavigation() {
        navEngine.startActiveNavigation()
        val loc = navEngine.currentLocation.value
        if (loc != null) {
            tripManager.startTripSession(loc, connectedBike.value?.name ?: "Yamaha NMAX")
        }
    }

    fun stopNavigation() {
        navEngine.stopNavigation()
        ccuManager.stopNavigationOnTFT()
    }

    fun toggleNorthUp() {
        _isNorthUp.value = !_isNorthUp.value
    }

    fun startNavigationTo(destination: LatLng, title: String = "Điểm đến") {
        calculateRoute(destination, title, autoStart = true)
    }

    fun clearArrivalMessage() {
        stopNavigation()
    }

    fun navigateToHome() {
        savedPlacesManager.homePlace.value?.let { home ->
            calculateRoute(home.coordinate, home.name, autoStart = true)
        }
    }

    fun navigateToWork() {
        savedPlacesManager.workPlace.value?.let { work ->
            calculateRoute(work.coordinate, work.name, autoStart = true)
        }
    }

    // =========================================================================
    // PARKING (FIND MY BIKE) ACTIONS
    // =========================================================================

    fun recordParkingLocation(coord: LatLng, bikeName: String = "Yamaha NMAX", address: String? = null) {
        parkingManager.recordParkingLocation(coord, bikeName, explicitAddress = address)
    }

    fun clearParkingLocation() {
        parkingManager.clearParkingLocation()
    }

    fun openGoogleMapsWalking(context: Context) {
        parkingManager.openGoogleMapsWalking(context)
    }

    // =========================================================================
    // SAVED PLACES ACTIONS
    // =========================================================================

    fun setHomePlace(name: String, address: String, coord: LatLng) {
        savedPlacesManager.setHome(name, address, coord)
    }

    fun setHomePlace(place: SavedPlace) {
        savedPlacesManager.setHome(place.name, place.address, place.coordinate)
    }

    fun removeHomePlace() {
        savedPlacesManager.removeHome()
    }

    fun setWorkPlace(name: String, address: String, coord: LatLng) {
        savedPlacesManager.setWork(name, address, coord)
    }

    fun setWorkPlace(place: SavedPlace) {
        savedPlacesManager.setWork(place.name, place.address, place.coordinate)
    }

    fun removeWorkPlace() {
        savedPlacesManager.removeWork()
    }

    fun addFavoritePlace(place: SavedPlace) {
        savedPlacesManager.addFavorite(place)
    }

    fun removeFavoritePlace(id: String) {
        savedPlacesManager.removeFavorite(id)
    }

    // =========================================================================
    // TRIP HISTORY ACTIONS
    // =========================================================================

    fun addSampleTrip() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
            tripManager.insertTrip(sample)
        }
    }

    fun deleteTrip(id: String) {
        tripManager.deleteTrip(id)
    }

    fun clearAllTrips() {
        tripManager.clearAllTrips()
    }

    // =========================================================================
    // IN-APP SOFTWARE UPDATER
    // =========================================================================

    fun checkForUpdate(silentIfUpToDate: Boolean = false) {
        updateManager.checkForUpdate(silentIfUpToDate)
    }

    fun startDownloadAndInstall(info: AppUpdateInfo) {
        updateManager.startDownloadAndInstall(info)
    }

    fun installApk(file: File) {
        updateManager.installApk(file)
    }

    fun resetUpdateStatus() {
        updateManager.resetStatus()
    }

    fun getCurrentVersionName(): String = updateManager.getCurrentVersionName()
    fun getCurrentVersionCode(): Int = updateManager.getCurrentVersionCode()

    // =========================================================================
    // GOOGLE MAPS TRAFFIC & SHARE LOCATION ACTIONS
    // =========================================================================

    fun setClusterMapSource(source: ClusterMapSource) {
        _clusterMapSource.value = source
        streamRenderer.clusterMapSource = source
    }

    fun clearSharedDestinationEvent() {
        _sharedDestinationEvent.value = null
    }

    fun handleIncomingIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.data

            viewModelScope.launch {
                val resolved = GoogleMapsShareResolver.resolve(getApplication(), sharedText, uri)
                if (resolved != null) {
                    _sharedDestinationEvent.value = resolved
                    val target = LatLng(resolved.latitude, resolved.longitude)
                    calculateRoute(target, resolved.title, autoStart = false)
                    navEngine.speakInstruction("Đã nhận điểm đến từ Google Maps: ${resolved.title}.")
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Không thể nhận diện toạ độ từ liên kết chia sẻ.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        streamRenderer.stopStreamingLoop()
    }
}
