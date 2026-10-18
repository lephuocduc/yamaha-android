package vn.tftlink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import vn.tftlink.ui.screens.LiveNavigationScreen
import vn.tftlink.ui.screens.MainDashboardScreen
import vn.tftlink.ui.screens.SettingsScreen
import vn.tftlink.ui.screens.TripHistoryScreen
import vn.tftlink.ui.theme.DarkBackground
import vn.tftlink.ui.theme.DarkSurface
import vn.tftlink.ui.theme.DarkSurfaceBorder
import vn.tftlink.ui.theme.NConnectTheme
import vn.tftlink.ui.theme.TextMuted
import vn.tftlink.ui.theme.TextPrimary
import vn.tftlink.ui.theme.YamahaCyan

import android.content.Intent
import android.widget.Toast

sealed class NavigationTab(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : NavigationTab("dashboard", "Trang chủ", Icons.Default.Speed)
    object Map : NavigationTab("map", "Bản đồ", Icons.Default.Map)
    object Trips : NavigationTab("trips", "Lộ trình", Icons.Default.History)
    object Settings : NavigationTab("settings", "Cài đặt", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.handleIncomingIntent(intent)

        setContent {
            NConnectTheme {
                MainAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }
}

@Composable
fun MainAppRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val isNavigating by viewModel.isNavigating.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsState()
    val sharedDest by viewModel.sharedDestinationEvent.collectAsState()

    // Tự động chuyển qua tab Bản đồ khi nhận địa điểm từ Google Maps
    LaunchedEffect(sharedDest) {
        sharedDest?.let { dest ->
            selectedTab = 1
            Toast.makeText(context, "Đã nhận vị trí Google Maps: ${dest.title}", Toast.LENGTH_LONG).show()
            viewModel.clearSharedDestinationEvent()
        }
    }

    // Tự động chuyển qua tab Bản đồ khi bắt đầu dẫn đường hoặc có lộ trình mới
    LaunchedEffect(isNavigating, activeRoute, isCalculatingRoute) {
        if (isNavigating || activeRoute != null || isCalculatingRoute) {
            selectedTab = 1
        }
    }

    // Xin quyền truy cập thiết bị (Bluetooth, Location, Notification)
    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    val tabs = listOf(
        NavigationTab.Dashboard,
        NavigationTab.Map,
        NavigationTab.Trips,
        NavigationTab.Settings
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = YamahaCyan,
                            selectedTextColor = YamahaCyan,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = YamahaCyan.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TabTransition"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> MainDashboardScreen(
                        viewModel = viewModel,
                        onNavigateToMapTab = { selectedTab = 1 }
                    )
                    1 -> LiveNavigationScreen(viewModel = viewModel)
                    2 -> TripHistoryScreen(viewModel = viewModel)
                    3 -> SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
