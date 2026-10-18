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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import java.net.URL
import kotlin.math.abs
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
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val homePlace by viewModel.homePlace.collectAsState()
    val workPlace by viewModel.workPlace.collectAsState()
    val destinationTitle by viewModel.destinationTitle.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isBikeConnected = connectionState == BikeConnectionState.CONNECTED

    var showMiniPreview by remember { mutableStateOf(true) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var mapStyle by remember { mutableStateOf("traffic") } // "traffic", "satellite", "osm"
    var droppedPin by remember { mutableStateOf<LatLng?>(null) }
    var droppedPinTitle by remember { mutableStateOf("Điểm đã ghim") }
    var droppedPinAddress by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val defaultPosition = LatLng(10.7769, 106.7009)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLoc ?: defaultPosition, 16f)
    }

    // Auto follow user location
    LaunchedEffect(currentLoc, heading, isNorthUp) {
        val loc = currentLoc ?: return@LaunchedEffect
        val bearing = if (isNorthUp) 0f else heading
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(loc)
                    .zoom(if (isNavigating) 17.5f else 16f)
                    .bearing(bearing)
                    .tilt(if (isNorthUp) 0f else 48f)
                    .build()
            ),
            700
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Google Maps Map View with High-Resolution Raster Tile Overlay
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = true,
                isTrafficEnabled = false,
                mapType = MapType.NONE // Using TileOverlay avoids empty beige canvas when Google Cloud vector API key is missing/revoked
            ),
            uiSettings = MapUiSettings(
                compassEnabled = true,
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false
            ),
            onMapClick = {
                isSearchFocused = false
            },
            onMapLongClick = { latLng ->
                isSearchFocused = false
                droppedPin = latLng
                viewModel.reverseGeocode(latLng) { title, address ->
                    droppedPinTitle = title
                    droppedPinAddress = address
                }
            }
        ) {
            // Live Tile Overlay (Google Traffic / Google Satellite / OpenStreetMap)
            TileOverlay(
                tileProvider = remember(mapStyle) {
                    object : UrlTileProvider(256, 256) {
                        override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
                            val server = abs((x + y) % 4)
                            return try {
                                when (mapStyle) {
                                    "satellite" -> URL("https://mt$server.google.com/vt/lyrs=y,traffic&hl=vi&x=$x&y=$y&z=$zoom")
                                    "osm" -> {
                                        val subdomains = arrayOf("a", "b", "c")
                                        val s = subdomains[abs((x + y) % subdomains.size)]
                                        URL("https://$s.tile.openstreetmap.org/$zoom/$x/$y.png")
                                    }
                                    else -> URL("https://mt$server.google.com/vt/lyrs=m,traffic&hl=vi&x=$x&y=$y&z=$zoom")
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                },
                transparency = 0f,
                zIndex = 0f
            )
            // Draw Alternative Routes in Dark / Gray Polyline
            alternativeRoutes.forEach { altRoute ->
                Polyline(
                    points = altRoute.coordinates,
                    color = Color(0xFF64748B),
                    width = 12f,
                    clickable = true,
                    onClick = { viewModel.selectRoute(altRoute) }
                )
            }

            // Draw Active Route Polyline in Yamaha Cyan / Neon Green
            activeRoute?.let { route ->
                if (route.coordinates.isNotEmpty()) {
                    Polyline(
                        points = route.coordinates,
                        color = YamahaCyan,
                        width = 16f
                    )
                    Marker(
                        state = MarkerState(position = route.coordinates.last()),
                        title = destinationTitle,
                        snippet = instruction.nextRoadName
                    )
                }
            }

            // Dropped pin marker
            droppedPin?.let { pin ->
                Marker(
                    state = MarkerState(position = pin),
                    title = droppedPinTitle,
                    snippet = droppedPinAddress
                )
            }

            // Parked Bike marker
            parkedLoc?.let { parked ->
                Marker(
                    state = MarkerState(position = parked.coordinate),
                    title = "Vị trí đỗ xe (${parked.bikeName})",
                    snippet = parked.relativeTimeString
                )
            }
        }

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
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                homePlace?.let { home ->
                                    Button(
                                        onClick = {
                                            viewModel.calculateRoute(home.coordinate, home.name, autoStart = true)
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
                                            viewModel.calculateRoute(work.coordinate, work.name, autoStart = true)
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

                                Button(
                                    onClick = {
                                        viewModel.fetchNearbyGasStations()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceHighlight),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cây xăng", fontSize = 11.sp, color = TextPrimary)
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
                                                    viewModel.calculateRoute(place.coordinate, place.name, autoStart = true)
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
                border = BorderStroke(1.dp, YamahaCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(droppedPinTitle, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        IconButton(onClick = { droppedPin = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                        }
                    }
                    if (droppedPinAddress.isNotBlank()) {
                        Text(droppedPinAddress, color = TextSecondary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            viewModel.calculateRoute(pin, droppedPinTitle, autoStart = true)
                            droppedPin = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dẫn đường đến điểm này", color = Color.Black, fontWeight = FontWeight.Bold)
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
                onClick = { viewModel.toggleNorthUp() },
                containerColor = DarkSurfaceCard,
                contentColor = if (isNorthUp) NeonOrange else YamahaCyan,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.Explore, contentDescription = "Xoay bản đồ", modifier = Modifier.size(22.dp))
            }

            FloatingActionButton(
                onClick = {
                    currentLoc?.let {
                        coroutineScope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 16.5f))
                        }
                    }
                },
                containerColor = DarkSurfaceCard,
                contentColor = YamahaCyan,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Vị trí của tôi", modifier = Modifier.size(22.dp))
            }
        }

        // 6. Mini TFT Mirror Card (Bottom Left)
        if (showMiniPreview && !isNavigating) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .width(160.dp)
                    .aspectRatio(480f / 234f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, if (isStreaming) NeonGreen else YamahaCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { viewModel.toggleStreaming() },
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (lastFrameBitmap != null) {
                        Image(
                            bitmap = lastFrameBitmap!!.asImageBitmap(),
                            contentDescription = "TFT Stream",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "TFT Mirror",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Text(
                        text = when {
                            isStreaming && isBikeConnected -> "LIVE TFT"
                            isStreaming && !isBikeConnected -> "CHỜ XE..."
                            else -> "PAUSED"
                        },
                        color = when {
                            isStreaming && isBikeConnected -> NeonGreen
                            isStreaming && !isBikeConnected -> NeonOrange
                            else -> TextMuted
                        },
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
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
