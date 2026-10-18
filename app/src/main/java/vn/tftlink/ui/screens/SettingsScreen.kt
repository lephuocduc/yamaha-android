package vn.tftlink.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tftlink.service.protocol.StreetCrossProtocol
import vn.tftlink.service.renderer.OSMTileEngine
import vn.tftlink.service.update.UpdateStatus
import vn.tftlink.ui.MainViewModel
import vn.tftlink.ui.theme.DarkBackground
import vn.tftlink.ui.theme.DarkSurfaceBorder
import vn.tftlink.ui.theme.DarkSurfaceCard
import vn.tftlink.ui.theme.DarkSurfaceHighlight
import vn.tftlink.ui.theme.NeonGreen
import vn.tftlink.ui.theme.NeonRed
import vn.tftlink.ui.theme.TextMuted
import vn.tftlink.ui.theme.TextPrimary
import vn.tftlink.ui.theme.TextSecondary
import vn.tftlink.ui.theme.YamahaCyan

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedBike by viewModel.connectedBike.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()

    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.UpdateAvailable) {
            showUpdateDialog = true
        }
    }

    var selectedBikeModel by remember { mutableStateOf("Yamaha NMAX 155") }
    var autoReconnect by remember { mutableStateOf(true) }
    var selectedStyle by remember { mutableStateOf("CartoDB Voyager (Khuyên dùng - Bản quyền API)") }
    var cartoApiKey by remember { mutableStateOf(OSMTileEngine.defaultCartoApiKey) }

    val bikeModels = listOf(
        "Yamaha NMAX 155",
        "Yamaha XMAX 300",
        "Yamaha NVX / Aerox 155",
        "Yamaha YZF-R9",
        "Yamaha MT-09 / SP",
        "Yamaha XSR900 GP",
        "Xe hỗ trợ Garmin StreetCross khác"
    )

    val tileStyles = listOf(
        "CartoDB Voyager (Khuyên dùng - Bản quyền API)",
        "CartoDB Positron (Bản đồ sáng tối giản)",
        "CartoDB Dark Matter (Bản đồ đêm chuyên dụng)",
        "OpenStreetMap Chuẩn (FOSSGIS)",
        "OpenStreetMap Quốc Tế (Wikimedia)",
        "OpenStreetMap Xe 2 Bánh (CyclOSM)"
    )

    var bikeMenuExpanded by remember { mutableStateOf(false) }
    var styleMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cài đặt hệ thống",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Cấu hình xe, bản đồ và giao diện chiếu đồng hồ",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Section: Dòng xe Yamaha
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("DÒNG XE YAMAHA", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedBikeModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Mẫu xe đang dùng") },
                            trailingIcon = {
                                IconButton(onClick = { bikeMenuExpanded = !bikeMenuExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { bikeMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = bikeMenuExpanded,
                            onDismissRequest = { bikeMenuExpanded = false }
                        ) {
                            bikeModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        selectedBikeModel = model
                                        bikeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tự động kết nối lại", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Kết nối ngay khi mở chìa khóa xe", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoReconnect,
                            onCheckedChange = { autoReconnect = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = YamahaCyan, checkedTrackColor = YamahaCyan.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }

        // Section: Bản đồ & Thuật toán dẫn đường
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BẢN ĐỒ & THUẬT TOÁN DẪN ĐƯỜNG", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedStyle,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Giao diện ngói bản đồ") },
                            trailingIcon = {
                                IconButton(onClick = { styleMenuExpanded = !styleMenuExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { styleMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = styleMenuExpanded,
                            onDismissRequest = { styleMenuExpanded = false }
                        ) {
                            tileStyles.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style) },
                                    onClick = {
                                        selectedStyle = style
                                        styleMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = cartoApiKey,
                        onValueChange = { cartoApiKey = it },
                        label = { Text("CartoDB API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tầng thuật toán", color = TextSecondary, fontSize = 13.sp)
                        Text("Valhalla Engine (Motorcycle)", color = NeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("✓ Bản đồ ngói CartoDB Voyager sạch bóng, không watermark", color = NeonGreen, fontSize = 11.sp)
                    Text("✓ Thuật toán Valhalla Engine định tuyến xe máy chuẩn Việt Nam", color = NeonGreen, fontSize = 11.sp)
                    Text("✓ Tốc độ nạp ngói cực nhanh qua mạng lưới CDN toàn cầu", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        // Section: Thông tin thiết bị kết nối
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THÔNG TIN PHẦN CỨNG & KẾT NỐI", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Trạng thái BLE CCU", color = TextSecondary, fontSize = 13.sp)
                        Text(connectionState.rawValue, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    connectedBike?.let { bike ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tên Bluetooth xe", color = TextSecondary, fontSize = 13.sp)
                            Text(bike.name, color = NeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Độ phân giải màn hình TFT", color = TextSecondary, fontSize = 13.sp)
                        Text("480 × 234 pixels", color = TextPrimary, fontSize = 13.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mã phần mềm Garmin", color = TextSecondary, fontSize = 13.sp)
                        Text(StreetCrossProtocol.softwarePartNumber, color = TextPrimary, fontSize = 13.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kênh truyền đồ họa", color = TextSecondary, fontSize = 13.sp)
                        Text("SPP RFCOMM Socket / BLE Fallback", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        }

        // Section: Cập nhật phần mềm OTA
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(
                    1.dp,
                    if (updateStatus is UpdateStatus.UpdateAvailable) NeonGreen else DarkSurfaceBorder
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CẬP NHẬT PHẦN MỀM",
                                color = YamahaCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Phiên bản: v${viewModel.getCurrentVersionName()} (Build ${viewModel.getCurrentVersionCode()})",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Cập nhật phần mềm",
                            tint = YamahaCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    when (val status = updateStatus) {
                        is UpdateStatus.Idle -> {
                            Text(
                                text = "Tự động kiểm tra bản dựng mới nhất từ GitHub và nâng cấp trực tiếp trong app mà không cần gỡ bỏ hay mất dữ liệu cấu hình.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                            Button(
                                onClick = { viewModel.checkForUpdate(silentIfUpToDate = false) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Kiểm tra cập nhật phần mềm",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        is UpdateStatus.Checking -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = YamahaCyan
                                )
                                Text(
                                    text = "Đang kiểm tra phiên bản mới từ máy chủ...",
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        is UpdateStatus.UpToDate -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = NeonGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Bạn đang dùng phiên bản mới nhất!",
                                        color = NeonGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = "Ứng dụng v${status.versionName} đã được cập nhật đầy đủ và ổn định.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.checkForUpdate(silentIfUpToDate = false) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, DarkSurfaceBorder)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Kiểm tra lại",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        is UpdateStatus.UpdateAvailable -> {
                            val info = status.info
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NewReleases,
                                        contentDescription = null,
                                        tint = NeonGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Đã có bản cập nhật mới: v${info.versionName} (Build ${info.versionCode})",
                                        color = NeonGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                Text(
                                    text = "Nội dung cập nhật:",
                                    color = YamahaCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = info.releaseNotes,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.startDownloadAndInstall(info) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Tải & Cập nhật ngay",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = { viewModel.resetUpdateStatus() },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                                ) {
                                    Text("Để sau", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        }

                        is UpdateStatus.Downloading -> {
                            val downMbStr = "%.1f".format(java.util.Locale.US, status.downloadedMb)
                            val totMbStr = if (status.totalMb > 0) "%.1f MB".format(java.util.Locale.US, status.totalMb) else "..."
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Đang tải bản cập nhật...",
                                        color = YamahaCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "${status.percent}%",
                                        color = YamahaCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { (status.percent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    color = YamahaCyan,
                                    trackColor = DarkSurfaceHighlight
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$downMbStr MB / $totMbStr",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Tự động cài đặt khi tải xong",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        is UpdateStatus.ReadyToInstall -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = NeonGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Tải về hoàn tất!",
                                        color = NeonGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = "Đang khởi chạy gói cài đặt hệ thống. Nếu màn hình cài đặt chưa xuất hiện, nhấn nút bên dưới để mở:",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Button(
                                    onClick = { viewModel.installApk(status.apkFile) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Cài đặt ngay bây giờ",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        is UpdateStatus.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkBackground, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = NeonRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Không thể cập nhật",
                                        color = NeonRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = status.message,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { viewModel.checkForUpdate(silentIfUpToDate = false) }) {
                                        Text("Thử lại", color = YamahaCyan)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { viewModel.resetUpdateStatus() }) {
                                        Text("Đóng", color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Thông tin ứng dụng
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("THÔNG TIN PHẦN MỀM", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("NConnect Android (Yamaha CCU Companion)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Phiên bản: ${viewModel.getCurrentVersionName()} (Build ${viewModel.getCurrentVersionCode()})", color = TextSecondary, fontSize = 12.sp)
                    Text("Package: vn.tftlink", color = TextMuted, fontSize = 11.sp)
                    Text("Kiến trúc: 100% Kotlin • Jetpack Compose • Material 3 • Coroutines/StateFlow • Room DB", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showUpdateDialog && updateStatus is UpdateStatus.UpdateAvailable) {
        val info = (updateStatus as UpdateStatus.UpdateAvailable).info
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            containerColor = DarkSurfaceCard,
            title = {
                Text(
                    text = "Có bản cập nhật mới v${info.versionName}",
                    color = YamahaCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Build ${info.versionCode} • Tự động cài đè không mất dữ liệu",
                        color = NeonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = info.releaseNotes,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        viewModel.startDownloadAndInstall(info)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                ) {
                    Text("Cập nhật ngay", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUpdateDialog = false }) {
                    Text("Để sau", color = TextSecondary)
                }
            }
        )
    }
}
