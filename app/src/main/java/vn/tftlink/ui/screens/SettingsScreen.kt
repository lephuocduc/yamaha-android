package vn.tftlink.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import vn.tftlink.service.renderer.ClusterMapSource
import vn.tftlink.service.renderer.OSMTileEngine
import vn.tftlink.service.update.UpdateStatus
import vn.tftlink.ui.MainViewModel
import vn.tftlink.ui.theme.DarkBackground
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

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedBike by viewModel.connectedBike.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val clusterMapSource by viewModel.clusterMapSource.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val headerFontSize by viewModel.headerFontSize.collectAsState()

    var mapSourceMenuExpanded by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

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
                    Text("BẢN ĐỒ XE", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Lựa chọn nguồn bản đồ truyền lên cụm đồng hồ
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = clusterMapSource.title,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Nguồn bản đồ chiếu lên đồng hồ xe") },
                            trailingIcon = {
                                IconButton(onClick = { mapSourceMenuExpanded = !mapSourceMenuExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { mapSourceMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = mapSourceMenuExpanded,
                            onDismissRequest = { mapSourceMenuExpanded = false }
                        ) {
                            ClusterMapSource.values().forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.title) },
                                    onClick = {
                                        viewModel.setClusterMapSource(source)
                                        mapSourceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (clusterMapSource == ClusterMapSource.OSM_CARTO) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedStyle,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Giao diện ngói bản đồ OSM") },
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
                    }

                    if (clusterMapSource == ClusterMapSource.GOOGLE_MAPS_TRAFFIC) {
                        Text("✓ Google Maps: Mật độ giao thông thời gian thực (Xanh lá / Vàng / Đỏ kẹt xe)", color = NeonGreen, fontSize = 11.sp)
                        Text("✓ Góc nhìn 3D nghiêng 45° xoay theo hướng xe chạy, HUD chỉ dẫn rẽ và tốc độ", color = NeonGreen, fontSize = 11.sp)
                        Text("✓ Hỗ trợ bấm 'Chia sẻ' từ ứng dụng Google Maps trực tiếp sang app", color = YamahaCyan, fontSize = 11.sp)
                    } else {
                        Text("✓ Bản đồ ngói CartoDB Voyager sạch bóng, không watermark", color = NeonGreen, fontSize = 11.sp)
                        Text("✓ Tối ưu định tuyến xe máy chuẩn đường bộ Việt Nam", color = NeonGreen, fontSize = 11.sp)
                        Text("✓ Tốc độ nạp ngói nhanh qua mạng lưới CDN toàn cầu", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        // Section: Màn hình chiếu đồng hồ xe (TFT Projection)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MÀN HÌNH CHIẾU ĐỒNG HỒ XE (TFT)",
                            color = YamahaCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = YamahaCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. CỠ CHỮ HEADER CHỈ DẪN
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cỡ chữ Header dẫn đường",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${headerFontSize.toInt()} pt",
                            color = YamahaCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Thanh chỉ dẫn Turn-by-Turn trên cùng (Cự ly rẽ + Tên đường)",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 4 nút preset nhanh: 14pt, 18pt (chuẩn), 22pt, 26pt
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            14f to "Nhỏ (14)",
                            18f to "Chuẩn (18)",
                            22f to "Lớn (22)",
                            26f to "Rất lớn (26)"
                        ).forEach { (size, label) ->
                            val isSelected = kotlin.math.abs(headerFontSize - size) < 1f
                            OutlinedButton(
                                onClick = { viewModel.setHeaderFontSize(size) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) YamahaCyan.copy(alpha = 0.2f) else Color.Transparent
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) YamahaCyan else DarkSurfaceBorder
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) YamahaCyan else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Slider tùy chỉnh mượt mà từ 14 đến 28 pt
                    Slider(
                        value = headerFontSize,
                        onValueChange = { viewModel.setHeaderFontSize(it) },
                        valueRange = 14f..28f,
                        steps = 13,
                        colors = SliderDefaults.colors(
                            thumbColor = YamahaCyan,
                            activeTrackColor = YamahaCyan,
                            inactiveTrackColor = DarkSurfaceBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // THẺ XEM TRƯỚC TRỰC TIẾP (LIVE PREVIEW BANNER)
                    Text(
                        text = "Xem trước giao diện Header trên xe:",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0A0F17))
                            .border(1.dp, YamahaCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = (headerFontSize * 0.35f).dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "400m Rẽ trái ",
                                color = NeonGreen,
                                fontSize = headerFontSize.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Nguyễn Huệ",
                                color = Color.White,
                                fontSize = headerFontSize.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBackground, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Độ phân giải: Siêu nét Full HD (960 × 468)",
                                color = NeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tự động kích hoạt mật độ điểm ảnh tối đa kết hợp ngói bản đồ Retina @2x",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
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
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = YamahaCyan
                                )
                                Text(
                                    text = "Đang kiểm tra phiên bản mới từ máy chủ...",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.resetUpdateStatus() }) {
                                    Text("Hủy", color = TextSecondary, fontSize = 12.sp)
                                }
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

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 160.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    formatReleaseNotesList(info.releaseNotes).forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text("•", color = YamahaCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = item,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
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
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TextButton(onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(vn.tftlink.service.update.AppUpdateManager.FALLBACK_RELEASE_URL)).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }) {
                                        Text("Mở trình duyệt", color = NeonOrange)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
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

        // Section: Chẩn đoán & Nhật ký hệ thống (Troubleshooting)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("CHẨN ĐOÁN & NHẬT KÝ HỆ THỐNG", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Xem và xuất log để gỡ lỗi", color = TextSecondary, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .background(DarkSurfaceHighlight, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${diagnosticLogs.size} logs",
                                color = NeonGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Monospace preview box of latest 3 logs
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBackground, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (diagnosticLogs.isEmpty()) {
                            Text(
                                text = "Chưa có log sự kiện nào được ghi nhận.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            diagnosticLogs.takeLast(3).forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showLogDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            border = BorderStroke(1.dp, YamahaCyan.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = YamahaCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Xem log", color = YamahaCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }

                        OutlinedButton(
                            onClick = { viewModel.copyDiagnosticLogsToClipboard(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            border = BorderStroke(1.dp, DarkSurfaceBorder)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sao chép", color = TextPrimary, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    Button(
                        onClick = { viewModel.exportDiagnosticLogs(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Xuất / chia sẻ file log", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Section: Thông tin ứng dụng & Tác giả
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THÔNG TIN PHẦN MỀM", color = YamahaCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("MyNMAX v${viewModel.getCurrentVersionName()} (Build ${viewModel.getCurrentVersionCode()})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground)
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/lephuocduc20")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Author", color = TextSecondary, fontSize = 11.sp)
                            Text("Lê Phước Đức", color = YamahaCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("facebook.com/lephuocduc20 ↗", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
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
                        text = "Nội dung cập nhật:",
                        color = YamahaCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        formatReleaseNotesList(info.releaseNotes).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("•", color = YamahaCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = item,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
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

    if (showLogDialog) {
        val fullLog = remember(showLogDialog) { viewModel.getFullDiagnosticLogText() }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            containerColor = DarkSurfaceCard,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nhật ký hệ thống & CCU",
                        color = YamahaCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showLogDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextSecondary)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Bao gồm thông tin thiết bị, GPS, BLE CCU packet và lộ trình dẫn đường.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .background(DarkBackground, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = fullLog,
                                    color = TextPrimary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.exportDiagnosticLogs(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = YamahaCyan)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chia sẻ / Gửi log", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.copyDiagnosticLogsToClipboard(context) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sao chép", color = TextPrimary)
                }
            }
        )
    }
}

/**
 * Tách nội dung cập nhật phần mềm thành từng dòng mục riêng biệt (theo số thứ tự 1), 2) hoặc dấu chấm phẩy / xuống dòng)
 */
private fun formatReleaseNotesList(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val splitter = Regex("\\s*;\\s*|\\s+(?=\\d+[\\)\\.]\\s)")
    val numberPrefix = Regex("^\\d+[\\)\\.]\\s*")
    val result = raw.lines()
        .flatMap { it.split(splitter) }
        .map { it.trim().replace(numberPrefix, "").trim() }
        .filter { it.isNotEmpty() }
    return result.ifEmpty { listOf(raw.trim()) }
}
