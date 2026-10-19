package vn.tftlink.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlinx.coroutines.launch
import vn.tftlink.data.model.bike.BikeConnectionState
import vn.tftlink.data.model.location.SavedPlace
import vn.tftlink.data.model.nav.NavigationDirection
import vn.tftlink.data.model.nav.OSMRoute
import vn.tftlink.ui.MainViewModel
import vn.tftlink.ui.theme.DarkBackground
import vn.tftlink.ui.theme.DarkSurface
import vn.tftlink.ui.theme.DarkSurfaceBorder
import vn.tftlink.ui.theme.DarkSurfaceCard
import vn.tftlink.ui.theme.DarkSurfaceHighlight
import vn.tftlink.ui.theme.NeonGreen
import vn.tftlink.ui.theme.NeonOrange
import vn.tftlink.ui.theme.NeonRed
import vn.tftlink.ui.theme.TextMuted
import vn.tftlink.ui.theme.TextPrimary
import vn.tftlink.ui.theme.TextSecondary
import vn.tftlink.ui.theme.YamahaCyan
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.material.icons.filled.BookmarkAdd
import vn.tftlink.R

private val GoogleTrafficTileSource = object : OnlineTileSourceBase(
    "GoogleTrafficHD",
    1, 20, 512, ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=m,traffic&hl=vi&scale=2",
        "https://mt1.google.com/vt/lyrs=m,traffic&hl=vi&scale=2",
        "https://mt2.google.com/vt/lyrs=m,traffic&hl=vi&scale=2",
        "https://mt3.google.com/vt/lyrs=m,traffic&hl=vi&scale=2"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl&x=$x&y=$y&z=$zoom"
    }
}

private val GoogleSatelliteTileSource = object : OnlineTileSourceBase(
    "GoogleSatelliteHD",
    1, 20, 512, ".jpg",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y,traffic&hl=vi&scale=2",
        "https://mt1.google.com/vt/lyrs=y,traffic&hl=vi&scale=2",
        "https://mt2.google.com/vt/lyrs=y,traffic&hl=vi&scale=2",
        "https://mt3.google.com/vt/lyrs=y,traffic&hl=vi&scale=2"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl&x=$x&y=$y&z=$zoom"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun LiveNavigationScreen(viewModel: MainViewModel) {
    val currentLoc by viewModel.currentLocation.collectAsState()
    val heading by viewModel.currentHeading.collectAsState()
    val speedKmh by viewModel.currentSpeedKmh.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val alternativeRoutes by viewModel.alternativeRoutes.collectAsState()
    val instruction by viewModel.currentInstruction.collectAsState()
    val isNorthUp by viewModel.isNorthUp.collectAsState()
    val lastFrameBitmap by viewModel.lastFrameBitmap.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val parkedLoc by viewModel.parkedLocation.collectAsState()
    val arrivalMsg by viewModel.arrivalMessage.collectAsState()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsState()
    val routeErrorMsg by viewModel.routeErrorMessage.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val homePlace by viewModel.homePlace.collectAsState()
    val workPlace by viewModel.workPlace.collectAsState()
    val destinationTitle by viewModel.destinationTitle.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isBikeConnected = connectionState == BikeConnectionState.CONNECTED

    var isSearchFocused by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var mapStyle by remember { mutableStateOf("traffic") } // "traffic", "satellite", "osm"
    var droppedPin by remember { mutableStateOf<LatLng?>(null) }
    var droppedPinTitle by remember { mutableStateOf("Điểm đã ghim") }
    var droppedPinAddress by remember { mutableStateOf("") }
    var isFollowingLocation by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val defaultPosition = LatLng(10.7769, 106.7009)
    var osmdroidMapView by remember { mutableStateOf<MapView?>(null) }

    // Initialize Osmdroid Configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = "NConnect-Yamaha-Android/1.0"
        try {
            val prefs = context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            Configuration.getInstance().load(context, prefs)
        } catch (_: Exception) {}
    }

    // Khung nhìn bao quát toàn bộ lộ trình khi đã tính toán xong
    LaunchedEffect(activeRoute) {
        val route = activeRoute ?: return@LaunchedEffect
        if (route.coordinates.isNotEmpty() && osmdroidMapView != null) {
            val lats = route.coordinates.map { it.latitude }
            val lons = route.coordinates.map { it.longitude }
            val minLat = lats.minOrNull() ?: return@LaunchedEffect
            val maxLat = lats.maxOrNull() ?: return@LaunchedEffect
            val minLon = lons.minOrNull() ?: return@LaunchedEffect
            val maxLon = lons.maxOrNull() ?: return@LaunchedEffect

            val latMargin = ((maxLat - minLat) * 0.18).coerceAtLeast(0.005)
            val lonMargin = ((maxLon - minLon) * 0.18).coerceAtLeast(0.005)
            val box = BoundingBox(
                maxLat + latMargin,
                maxLon + lonMargin,
                minLat - latMargin,
                minLon - lonMargin
            )
            osmdroidMapView?.post {
                try {
                    osmdroidMapView?.zoomToBoundingBox(box, true, 130)
                } catch (_: Exception) {}
            }
        }
    }

    // Tự động bám theo vị trí xe khi bật chế độ theo dõi (vuốt bản đồ để tạm dừng bám)
    LaunchedEffect(currentLoc, isFollowingLocation, isNavigating) {
        val loc = currentLoc ?: return@LaunchedEffect
        if (isFollowingLocation) {
            osmdroidMapView?.controller?.animateTo(
                GeoPoint(loc.latitude, loc.longitude),
                if (isNavigating) 17.5 else 16.5,
                500L
            )
        }
    }

    var orientationAnimator by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }

    LaunchedEffect(heading, isNorthUp) {
        osmdroidMapView?.let { mv ->
            val target = if (isNorthUp) 0f else ((-heading % 360f) + 360f) % 360f
            val current = mv.mapOrientation
            var diff = (target - current) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f

            if (isNorthUp) {
                if (kotlin.math.abs(diff) >= 0.8f) {
                    orientationAnimator?.cancel()
                    orientationAnimator = android.animation.ValueAnimator.ofFloat(current, current + diff).apply {
                        duration = 350L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { anim ->
                            mv.mapOrientation = ((anim.animatedValue as Float) % 360f + 360f) % 360f
                        }
                        start()
                    }
                }
            } else {
                // Head-tracking: chỉ xoay khi độ lệch >= 2.0° để triệt tiêu rung giật la bàn (jitter damping)
                if (kotlin.math.abs(diff) >= 2.0f) {
                    orientationAnimator?.cancel()
                    orientationAnimator = android.animation.ValueAnimator.ofFloat(current, current + diff).apply {
                        duration = 200L
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { anim ->
                            mv.mapOrientation = ((anim.animatedValue as Float) % 360f + 360f) % 360f
                        }
                        start()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. High-Performance Osmdroid Map View with Google Live Traffic (No API Key Required)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    setTileSource(GoogleTrafficTileSource)
                    controller.setZoom(16.0)
                    val start = currentLoc ?: defaultPosition
                    controller.setCenter(GeoPoint(start.latitude, start.longitude))

                    // Khi người dùng chạm kéo bản đồ thủ công, tạm dừng tự động bám tâm vị trí
                    setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_MOVE && event.pointerCount >= 1) {
                            isFollowingLocation = false
                        }
                        false
                    }

                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            isSearchFocused = false
                            droppedPin = null
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean {
                            isSearchFocused = false
                            val latLng = LatLng(p.latitude, p.longitude)
                            droppedPin = latLng
                            droppedPinTitle = "Đang lấy địa chỉ..."
                            droppedPinAddress = String.format(java.util.Locale.US, "%.5f, %.5f", p.latitude, p.longitude)
                            viewModel.reverseGeocode(latLng) { title, address ->
                                droppedPinTitle = title
                                droppedPinAddress = address
                            }
                            return true
                        }
                    })
                    overlays.add(eventsOverlay)

                    osmdroidMapView = this
                }
            },
            update = { mapView ->
                // Update map style if toggled
                val targetSource = when (mapStyle) {
                    "satellite" -> GoogleSatelliteTileSource
                    "osm" -> TileSourceFactory.MAPNIK
                    else -> GoogleTrafficTileSource
                }
                if (mapView.tileProvider.tileSource.name() != targetSource.name()) {
                    mapView.setTileSource(targetSource)
                }

                // Clear dynamic route and marker overlays (preserve eventsOverlay at index 0)
                while (mapView.overlays.size > 1) {
                    mapView.overlays.removeAt(mapView.overlays.size - 1)
                }

                // Draw Alternative Routes in Dark / Gray Polyline
                alternativeRoutes.forEach { altRoute ->
                    if (altRoute.coordinates.isNotEmpty()) {
                        val poly = OsmPolyline().apply {
                            outlinePaint.color = android.graphics.Color.parseColor("#64748B")
                            outlinePaint.strokeWidth = 12f
                            setPoints(altRoute.coordinates.map { GeoPoint(it.latitude, it.longitude) })
                            setOnClickListener { _, _, _ ->
                                viewModel.selectRoute(altRoute)
                                true
                            }
                        }
                        mapView.overlays.add(poly)
                    }
                }

                // Draw Active Route in Yamaha Cyan / Neon Green Polyline
                activeRoute?.let { route ->
                    if (route.coordinates.isNotEmpty()) {
                        val poly = OsmPolyline().apply {
                            outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                            outlinePaint.strokeWidth = 16f
                            setPoints(route.coordinates.map { GeoPoint(it.latitude, it.longitude) })
                        }
                        mapView.overlays.add(poly)

                        val dest = route.coordinates.last()
                        val marker = OsmMarker(mapView).apply {
                            position = GeoPoint(dest.latitude, dest.longitude)
                            title = destinationTitle
                            snippet = instruction.nextRoadName
                            icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_google_pin)
                            setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // Dropped Pin Marker (Google Maps Red Teardrop Pin)
                droppedPin?.let { pin ->
                    val marker = OsmMarker(mapView).apply {
                        position = GeoPoint(pin.latitude, pin.longitude)
                        title = droppedPinTitle
                        snippet = droppedPinAddress
                        icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_google_pin)
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                }

                // Parked Bike Marker
                parkedLoc?.let { parked ->
                    val marker = OsmMarker(mapView).apply {
                        position = GeoPoint(parked.coordinate.latitude, parked.coordinate.longitude)
                        title = "Vị trí đỗ xe (${parked.bikeName})"
                        snippet = parked.relativeTimeString
                        icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_parked_bike)
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                }

                // Current User Location Marker with Direction Puck
                currentLoc?.let { loc ->
                    val userMarker = OsmMarker(mapView).apply {
                        position = GeoPoint(loc.latitude, loc.longitude)
                        title = "Vị trí của bạn"
                        icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_user_puck)
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                        // Khi North-Up: bản đồ cố định hướng Bắc -> mũi tên puck xoay theo heading của xe.
                        // Khi Head-Tracking: bản đồ xoay theo -heading -> mũi tên puck luôn hướng thẳng lên phía trước (0°).
                        rotation = if (isNorthUp) heading else 0f
                    }
                    mapView.overlays.add(userMarker)
                }

                mapView.invalidate()
            }
        )

        // 2. Top Bar: Turn-by-Turn Guidance HUD OR Search Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            // Arrival message banner
            arrivalMsg?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                    border = BorderStroke(1.5.dp, NeonGreen),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ĐÃ ĐẾN NƠI!", color = NeonGreen, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text(msg, color = TextPrimary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { viewModel.stopNavigation() }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                        }
                    }
                }
            }

            if (isNavigating) {
                // Active Turn-by-Turn HUD Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard.copy(alpha = 0.96f)),
                    border = BorderStroke(1.5.dp, YamahaCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Direction Maneuver Icon
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(YamahaCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getDirectionIcon(instruction.direction),
                                    contentDescription = null,
                                    tint = YamahaCyan,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = instruction.formattedDistance,
                                        color = NeonGreen,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (instruction.nextRoadName.isNotBlank()) instruction.nextRoadName else instruction.detailedInstruction,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Đến nơi lúc ${instruction.etaString} (${instruction.remainingTimeFormatted})",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            IconButton(onClick = { viewModel.stopNavigation() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dừng dẫn đường", tint = NeonRed)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Bottom status row in HUD
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🏍 Tốc độ: ${speedKmh.toInt()} km/h",
                                color = YamahaCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when {
                                    isStreaming && isBikeConnected -> NeonGreen
                                    isStreaming && !isBikeConnected -> NeonOrange
                                    else -> TextMuted
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = when {
                                        isStreaming && isBikeConnected -> "Đang chiếu lên đồng hồ xe"
                                        isStreaming && !isBikeConnected -> "Đang đợi kết nối với xe..."
                                        else -> "Chưa bật chiếu TFT"
                                    },
                                    color = dotColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                // Top Direct Search Bar & Inline Results (No redundant popup dialog!)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard.copy(alpha = 0.96f)),
                    border = BorderStroke(1.dp, if (isSearchFocused || searchQuery.isNotBlank()) YamahaCyan else DarkSurfaceBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = if (isSearchFocused || searchQuery.isNotBlank()) YamahaCyan else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    viewModel.searchPlaces(it)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { focusState ->
                                        isSearchFocused = focusState.isFocused
                                    },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (searchQuery.isNotBlank()) {
                                            viewModel.searchPlaces(searchQuery)
                                        }
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Tìm địa điểm hoặc chạm để chỉ đường...",
                                            color = TextSecondary,
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        viewModel.searchPlaces("")
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Xóa tìm kiếm",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Quick Action Chips & Search Results shown directly in-place
                        if (isSearchFocused || searchQuery.isNotBlank() || searchResults.isNotEmpty()) {
                            if (homePlace != null || workPlace != null) {
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    homePlace?.let { home ->
                                        Button(
                                            onClick = {
                                                viewModel.calculateRoute(home.coordinate, home.name, autoStart = false)
                                                isSearchFocused = false
                                                searchQuery = ""
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceHighlight),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Home, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Về nhà", fontSize = 11.sp, color = TextPrimary)
                                        }
                                     }

                                     workPlace?.let { work ->
                                        Button(
                                            onClick = {
                                                viewModel.calculateRoute(work.coordinate, work.name, autoStart = false)
                                                isSearchFocused = false
                                                searchQuery = ""
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceHighlight),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Work, contentDescription = null, tint = NeonOrange, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Đi làm", fontSize = 11.sp, color = TextPrimary)
                                        }
                                     }
                                }
                            }

                            if (isSearching) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(70.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = YamahaCyan, modifier = Modifier.size(24.dp))
                                }
                            } else if (searchResults.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                ) {
                                    items(searchResults) { place ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                                .clickable {
                                                    viewModel.calculateRoute(place.coordinate, place.name, autoStart = false)
                                                    isSearchFocused = false
                                                    searchQuery = ""
                                                    viewModel.searchPlaces("")
                                                },
                                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                                            border = BorderStroke(1.dp, DarkSurfaceBorder)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = YamahaCyan, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(place.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text(place.address, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (searchQuery.isNotBlank() && !isSearching) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Không tìm thấy địa điểm",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Dropped Pin Action Card
        droppedPin?.let { pin ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 80.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.2.dp, YamahaCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_marker_google_pin),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    droppedPinTitle,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (droppedPinAddress.isNotBlank()) {
                                    Text(
                                        droppedPinAddress,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { droppedPin = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val favName = if (droppedPinTitle != "Điểm đã ghim" && droppedPinTitle != "Đang lấy địa chỉ...") droppedPinTitle else "Điểm ghim (${String.format(java.util.Locale.US, "%.3f, %.3f", pin.latitude, pin.longitude)})"
                                val favPlace = SavedPlace(
                                    name = favName,
                                    address = droppedPinAddress,
                                    latitude = pin.latitude,
                                    longitude = pin.longitude
                                )
                                viewModel.addFavoritePlace(favPlace)
                                Toast.makeText(context, "Đã thêm vào mục Yêu thích", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, YamahaCyan.copy(alpha = 0.8f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = YamahaCyan),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Yêu thích", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                viewModel.calculateRoute(pin, droppedPinTitle, autoStart = false)
                                droppedPin = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, tint = Color.Black, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Dẫn đường", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 3.5. Route Calculation Loading Indicator
        if (isCalculatingRoute) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.5.dp, YamahaCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(
                        color = YamahaCyan,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Đang lập lộ trình xe máy...",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Tối ưu hóa đường đi, ưu tiên đường lớn và tránh cao tốc",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 3.6. Route Calculation Error Notification
        routeErrorMsg?.let { errorMsg ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.5.dp, NeonRed),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(errorMsg, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearRouteErrorMessage() }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                    }
                }
            }
        }

        // 4. Route Selection Card (when calculated but not yet started)
        if (activeRoute != null && !isNavigating) {
            val route = activeRoute!!
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, NeonGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = destinationTitle,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${route.formattedDuration} • ${route.formattedDistance} • ${route.routeName}",
                                color = NeonGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = { viewModel.stopNavigation() }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    // Alternatives row
                    if (alternativeRoutes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.selectRoute(route) },
                                colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan.copy(alpha = 0.25f)),
                                border = BorderStroke(1.dp, YamahaCyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Tuyến 1: ${route.formattedDuration}", color = TextPrimary, fontSize = 12.sp)
                            }
                            alternativeRoutes.firstOrNull()?.let { alt ->
                                OutlinedButton(
                                    onClick = { viewModel.selectRoute(alt) },
                                    border = BorderStroke(1.dp, DarkSurfaceBorder),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Tuyến 2: ${alt.timeDiffString}", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.startNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("BẮT ĐẦU DẪN ĐƯỜNG", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }

        // 5. Floating Controls (Right Side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Map Layer Toggle (Traffic / Satellite / OSM)
            FloatingActionButton(
                onClick = {
                    mapStyle = when (mapStyle) {
                        "traffic" -> "satellite"
                        "satellite" -> "osm"
                        else -> "traffic"
                    }
                },
                containerColor = DarkSurfaceCard,
                contentColor = when (mapStyle) {
                    "satellite" -> NeonGreen
                    "osm" -> NeonOrange
                    else -> YamahaCyan
                },
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Đổi lớp bản đồ", modifier = Modifier.size(22.dp))
            }

            FloatingActionButton(
                onClick = {
                    val targetIsNorthUp = !isNorthUp
                    viewModel.toggleNorthUp()
                    osmdroidMapView?.let { mv ->
                        val target = if (targetIsNorthUp) 0f else ((-heading % 360f) + 360f) % 360f
                        val current = mv.mapOrientation
                        var diff = (target - current) % 360f
                        if (diff > 180f) diff -= 360f
                        if (diff < -180f) diff += 360f

                        orientationAnimator?.cancel()
                        orientationAnimator = android.animation.ValueAnimator.ofFloat(current, current + diff).apply {
                            duration = 350L
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addUpdateListener { anim ->
                                mv.mapOrientation = ((anim.animatedValue as Float) % 360f + 360f) % 360f
                            }
                            start()
                        }
                    }
                    val msg = if (targetIsNorthUp) "Cố định phương Bắc (North-Up)" else "Theo hướng xe chạy (Heading Tracking)"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                containerColor = Color(0xEB141E2E),
                contentColor = if (isNorthUp) Color(0xFFEF4444) else YamahaCyan,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .size(46.dp)
                    .border(
                        BorderStroke(
                            1.5.dp,
                            if (isNorthUp) Color(0xFFEF4444).copy(alpha = 0.85f) else YamahaCyan.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isNorthUp) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Cố định phương Bắc",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "BẮC",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 9.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Theo hướng xe chạy",
                            tint = YamahaCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "THEO XE",
                            color = YamahaCyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 9.sp
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    isFollowingLocation = true
                    currentLoc?.let { loc ->
                        osmdroidMapView?.controller?.animateTo(
                            GeoPoint(loc.latitude, loc.longitude),
                            if (isNavigating) 17.5 else 16.5,
                            500L
                        )
                    }
                    Toast.makeText(context, "Đang bám theo vị trí xe", Toast.LENGTH_SHORT).show()
                },
                containerColor = DarkSurfaceCard,
                contentColor = if (isFollowingLocation) YamahaCyan else TextSecondary,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Vị trí của tôi", modifier = Modifier.size(22.dp))
            }

            // Route Overview Button (Zoom to see whole route)
            if (activeRoute != null) {
                FloatingActionButton(
                    onClick = {
                        val route = activeRoute ?: return@FloatingActionButton
                        if (route.coordinates.isNotEmpty() && osmdroidMapView != null) {
                            val lats = route.coordinates.map { it.latitude }
                            val lons = route.coordinates.map { it.longitude }
                            val minLat = lats.minOrNull() ?: return@FloatingActionButton
                            val maxLat = lats.maxOrNull() ?: return@FloatingActionButton
                            val minLon = lons.minOrNull() ?: return@FloatingActionButton
                            val maxLon = lons.maxOrNull() ?: return@FloatingActionButton
                            val latMargin = ((maxLat - minLat) * 0.18).coerceAtLeast(0.005)
                            val lonMargin = ((maxLon - minLon) * 0.18).coerceAtLeast(0.005)
                            val box = BoundingBox(maxLat + latMargin, maxLon + lonMargin, minLat - latMargin, minLon - lonMargin)
                            osmdroidMapView?.zoomToBoundingBox(box, true, 130)
                        }
                    },
                    containerColor = DarkSurfaceCard,
                    contentColor = NeonGreen,
                    shape = CircleShape,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.AltRoute, contentDescription = "Xem toàn bộ lộ trình", modifier = Modifier.size(22.dp))
                }
            }
        }


    }
}

private fun getDirectionIcon(direction: NavigationDirection): ImageVector {
    return when (direction) {
        NavigationDirection.STRAIGHT -> Icons.Default.NearMe
        NavigationDirection.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
        NavigationDirection.RIGHT, NavigationDirection.SHARP_RIGHT -> Icons.Default.TurnRight
        NavigationDirection.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
        NavigationDirection.LEFT, NavigationDirection.SHARP_LEFT -> Icons.Default.TurnLeft
        NavigationDirection.U_TURN -> Icons.Default.Directions
        NavigationDirection.ROUNDABOUT -> Icons.Default.Explore
        NavigationDirection.DESTINATION -> Icons.Default.Check
    }
}
