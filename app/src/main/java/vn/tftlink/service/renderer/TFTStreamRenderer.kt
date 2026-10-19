package vn.tftlink.service.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.tftlink.data.model.nav.NavigationInstruction
import vn.tftlink.data.model.nav.OSMRoute
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Các tùy chọn độ phân giải màn hình chiếu đồng hồ xe máy Yamaha (TFT Super-sampling)
 */
enum class TFTResolution(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val scaleFactor: Float,
    val description: String
) {
    SD_480("sd_480", "Tiêu chuẩn SD (480 × 234)", 480, 234, 1.0f, "Độ phân giải gốc ban đầu"),
    HD_720("hd_720", "Độ nét cao HD (720 × 351)", 720, 351, 1.5f, "Cân bằng độ nét và tốc độ"),
    FHD_960("fhd_960", "Siêu nét Full HD (960 × 468)", 960, 468, 2.0f, "Khuyên dùng - Nét gấp 4 lần điểm ảnh")
}

/**
 * Renderer tạo khung hình Bản Đồ OpenStreetMap cho màn hình TFT xe máy Yamaha
 * Tối ưu hóa 100% chạy nền qua Canvas / Bitmap pipeline, nén JPEG gửi lên xe
 */
enum class ClusterMapSource(
    val id: String,
    val title: String,
    val tileUrlTemplate: String,
    val styleKey: String,
    val attribution: String
) {
    GOOGLE_MAPS_TRAFFIC(
        "gmap_traffic",
        "Google Maps (Mật độ giao thông LIVE - Siêu nét HD)",
        "https://mt1.google.com/vt/lyrs=m,traffic&hl=vi&scale=2&x={x}&y={y}&z={z}",
        "gmaps_traffic_hd",
        "© Google Maps Traffic HD"
    ),
    OSM_CARTO(
        "osm_carto",
        "CartoDB Voyager (OpenStreetMap - Siêu nét HD)",
        "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png?key=cb1_4mz7_1_fb3f57cfec3a69a35eb8be87",
        "voyager_hd",
        "© OpenStreetMap • CartoDB HD"
    )
}

class TFTStreamRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    var targetWidth: Int = 960,
    var targetHeight: Int = 468
) {
    companion object {
        val ZOOM_LEVELS = doubleArrayOf(200.0, 400.0, 800.0, 1600.0, 3000.0)
        val OSM_ZOOMS = intArrayOf(18, 17, 16, 15, 14)
    }

    private val prefs = context.getSharedPreferences("tft_settings_prefs", Context.MODE_PRIVATE)

    // Màn hình bản đồ luôn cố định chế độ Siêu nét Full HD (960 × 468) với Canvas Super-sampling
    private val _tftResolution = MutableStateFlow(TFTResolution.FHD_960)
    val tftResolution: StateFlow<TFTResolution> = _tftResolution.asStateFlow()

    private val _headerFontSize = MutableStateFlow(
        prefs.getFloat("header_font_size", 18f)
    )
    val headerFontSize: StateFlow<Float> = _headerFontSize.asStateFlow()

    var headerFontSizeSp: Float
        get() = _headerFontSize.value
        set(value) {
            _headerFontSize.value = value
            prefs.edit().putFloat("header_font_size", value).apply()
            triggerImmediateReRender(null)
        }

    fun setResolution(res: TFTResolution) {
        _tftResolution.value = res
        targetWidth = res.width
        targetHeight = res.height
        prefs.edit().putString("tft_resolution", res.id).apply()
        triggerImmediateReRender(null)
    }

    fun setHeaderFontSizeValue(size: Float) {
        headerFontSizeSp = size
    }

    private val tileEngine = OSMTileEngine.getInstance(context)
    private val renderMutex = Mutex()

    var clusterMapSource: ClusterMapSource = ClusterMapSource.GOOGLE_MAPS_TRAFFIC
        set(value) {
            field = value
            tileEngine.updateStyle(value.tileUrlTemplate, value.styleKey)
        }

    init {
        val savedRes = _tftResolution.value
        targetWidth = savedRes.width
        targetHeight = savedRes.height
        tileEngine.updateStyle(clusterMapSource.tileUrlTemplate, clusterMapSource.styleKey)
    }

    private val _lastFrameBitmap = MutableStateFlow<Bitmap?>(null)
    val lastFrameBitmap: StateFlow<Bitmap?> = _lastFrameBitmap.asStateFlow()

    var currentZoomIndex: Int = 0
    var isNorthUp: Boolean = false

    val currentOsmZoom: Int
        get() = OSM_ZOOMS[currentZoomIndex.coerceIn(0, OSM_ZOOMS.size - 1)]

    val currentZoomLevel: Int
        get() = currentOsmZoom

    fun updateLocation(loc: LatLng, bearing: Float) {
        lastRenderedLocation = loc
        lastRenderedCourse = bearing
    }

    private var lastRenderedLocation: LatLng? = null
    private var lastRenderedCourse: Float = 0f
    private var lastRenderedRoute: OSMRoute? = null
    private var lastRenderedAlternativeRoutes: List<OSMRoute> = emptyList()
    private var lastRenderedInstruction: NavigationInstruction = NavigationInstruction()
    private var lastRenderedSpeedKmh: Double = 0.0
    private var lastRenderedIsRerouting: Boolean = false
    private var lastPrefetchedRouteKey: String = ""

    private var streamingJob: Job? = null

    fun zoomIn(onFrameReady: ((ByteArray) -> Unit)? = null) {
        if (currentZoomIndex > 0) {
            currentZoomIndex--
            triggerImmediateReRender(onFrameReady)
        }
    }

    fun zoomOut(onFrameReady: ((ByteArray) -> Unit)? = null) {
        if (currentZoomIndex < ZOOM_LEVELS.size - 1) {
            currentZoomIndex++
            triggerImmediateReRender(onFrameReady)
        }
    }

    private fun triggerImmediateReRender(onFrameReady: ((ByteArray) -> Unit)?) {
        val loc = lastRenderedLocation ?: return
        scope.launch(Dispatchers.Default) {
            val bytes = renderLiveMapFrame(
                location = loc,
                course = lastRenderedCourse,
                activeRoute = lastRenderedRoute,
                alternativeRoutes = lastRenderedAlternativeRoutes,
                instruction = lastRenderedInstruction,
                speedKmh = lastRenderedSpeedKmh,
                isRerouting = lastRenderedIsRerouting
            )
            bytes?.let { onFrameReady?.invoke(it) }
        }
    }

    /**
     * Bắt đầu luồng stream hình ảnh định kỳ lên màn hình TFT (chu kỳ ~1.2s)
     */
    fun startStreamingLoop(
        getLocation: () -> LatLng?,
        getCourse: () -> Float,
        getActiveRoute: () -> OSMRoute?,
        getAlternativeRoutes: () -> List<OSMRoute>,
        getInstruction: () -> NavigationInstruction,
        getSpeed: () -> Double,
        getIsRerouting: () -> Boolean,
        onFrameReady: (ByteArray) -> Unit
    ) {
        streamingJob?.cancel()
        streamingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val loc = getLocation()
                val course = getCourse()
                val route = getActiveRoute()
                val alts = getAlternativeRoutes()
                val inst = getInstruction()
                val speed = getSpeed()
                val rerouting = getIsRerouting()

                val bytes = renderLiveMapFrame(loc, course, route, alts, inst, speed, rerouting)
                if (bytes != null) {
                    onFrameReady(bytes)
                }
                delay(1200)
            }
        }
    }

    fun stopStreamingLoop() {
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * Render 1 khung hình bản đồ live hoàn chỉnh và nén JPEG
     */
    suspend fun renderLiveMapFrame(
        location: LatLng?,
        course: Float,
        activeRoute: OSMRoute?,
        alternativeRoutes: List<OSMRoute> = emptyList(),
        instruction: NavigationInstruction,
        speedKmh: Double,
        isRerouting: Boolean = false
    ): ByteArray? = renderMutex.withLock {
        this.lastRenderedLocation = location
        this.lastRenderedCourse = course
        this.lastRenderedRoute = activeRoute
        this.lastRenderedAlternativeRoutes = alternativeRoutes
        this.lastRenderedInstruction = instruction
        this.lastRenderedSpeedKmh = speedKmh
        this.lastRenderedIsRerouting = isRerouting

        val scaleFactor = (targetWidth / 480f).coerceAtLeast(1.0f)

        // Tự động tải trước ngói bản đồ dọc lộ trình
        if (activeRoute != null && activeRoute.coordinates.isNotEmpty()) {
            val key = "${activeRoute.coordinates.size}_${activeRoute.distanceMeters.toInt()}"
            if (key != lastPrefetchedRouteKey) {
                lastPrefetchedRouteKey = key
                tileEngine.prefetchRouteTiles(scope, activeRoute.coordinates, currentOsmZoom)
            }
        }

        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Nền tối chống chói ban đêm (#141A24)
        canvas.drawColor(Color.rgb(20, 26, 36))

        if (location == null) {
            val title = if (clusterMapSource == ClusterMapSource.GOOGLE_MAPS_TRAFFIC) "Google Maps Live Traffic HD" else "OpenStreetMap HD"
            drawFallbackScreen(canvas, title, "Đang lấy tín hiệu GPS...", speedKmh, scaleFactor)
            _lastFrameBitmap.value = bitmap
            return@withLock compressToJpeg(bitmap)
        }

        val zoom = currentOsmZoom
        val n = 2.0.pow(zoom)
        val lon = location.longitude
        val lat = location.latitude.coerceIn(-85.0511, 85.0511)

        val worldX = (lon + 180.0) / 360.0 * n * 256.0 * scaleFactor
        val latRad = Math.toRadians(lat)
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n * 256.0 * scaleFactor

        val centerTileX = ((lon + 180.0) / 360.0 * n).toInt()
        val centerTileY = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()

        val originWorldX = ((centerTileX - 1) * 256.0) * scaleFactor
        val originWorldY = ((centerTileY - 1) * 256.0) * scaleFactor

        val bikeCanvasX = (worldX - originWorldX).toFloat()
        val bikeCanvasY = (worldY - originWorldY).toFloat()

        val heading = if (course >= 0) course else 0f
        val mapRotation = if (isNorthUp) 0f else heading
        val centerPtX = targetWidth / 2f
        val centerPtY = targetHeight / 2f

        canvas.save()
        // Xoay bản đồ theo hướng di chuyển của xe (Heading Tracking)
        canvas.translate(centerPtX, centerPtY)
        canvas.rotate(-mapRotation)
        canvas.translate(-bikeCanvasX, -bikeCanvasY)

        val tileDim = 256f * scaleFactor
        val gridPaint = Paint().apply {
            color = Color.rgb(26, 31, 41)
            style = Paint.Style.FILL
        }
        val gridBorder = Paint().apply {
            color = Color.argb(76, 51, 51, 51)
            style = Paint.Style.STROKE
            strokeWidth = 1f * scaleFactor
        }
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

        // 2. Vẽ 9 ngói bản đồ HD (3x3 grid) xung quanh vị trí xe
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                val left = (dx + 1) * tileDim
                val top = (dy + 1) * tileDim
                val rect = RectF(left, top, left + tileDim, top + tileDim)

                val tile = tileEngine.getCachedTile(tx, ty, zoom)
                if (tile != null) {
                    canvas.drawBitmap(tile, null, rect, tilePaint)
                } else {
                    canvas.drawRect(rect, gridPaint)
                    canvas.drawRect(rect, gridBorder)
                    scope.launch(Dispatchers.IO) {
                        tileEngine.fetchTile(tx, ty, zoom)
                    }
                }
            }
        }

        // Tăng độ tương phản nhẹ cho nền bản đồ ngoài trời nắng
        val contrastPaint = Paint().apply {
            color = Color.argb(30, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, 3 * tileDim, 3 * tileDim, contrastPaint)

        // 3. Vẽ lộ trình OpenStreetMap (Polyline) với độ nét cao
        for (alt in alternativeRoutes) {
            if (alt.coordinates.size > 1) {
                drawRoutePolyline(canvas, alt, zoom, originWorldX, originWorldY, isAlternative = true, scaleFactor = scaleFactor)
            }
        }
        if (activeRoute != null && activeRoute.coordinates.size > 1) {
            drawRoutePolyline(canvas, activeRoute, zoom, originWorldX, originWorldY, isAlternative = false, scaleFactor = scaleFactor)
        }

        canvas.restore()

        // 4. Vẽ biểu tượng xe máy trung tâm màn hình (Chevron xanh Neon Cyan)
        drawMotorcycleMarker(canvas, centerPtX, centerPtY, heading, isNorthUp, scaleFactor)

        // 5. Thanh chỉ dẫn điều hướng Turn-by-Turn phía trên (khoảng cách + tên đường)
        drawTopNavigationBanner(canvas, instruction, isRerouting, scaleFactor)

        // 6. Nhãn bản quyền
        drawAttribution(canvas, scaleFactor)

        _lastFrameBitmap.value = bitmap
        compressToJpeg(bitmap)
    }

    /**
     * Vẽ lộ trình đa lớp tương phản cao chống chói ngoài trời
     */
    private fun drawRoutePolyline(
        canvas: Canvas,
        route: OSMRoute,
        zoom: Int,
        originX: Double,
        originY: Double,
        isAlternative: Boolean,
        scaleFactor: Float = 1.0f
    ) {
        val n = 2.0.pow(zoom)
        val path = Path()
        var started = false

        for (coord in route.coordinates) {
            val ptWorldX = (coord.longitude + 180.0) / 360.0 * n * 256.0 * scaleFactor
            val safeLat = coord.latitude.coerceIn(-85.0511, 85.0511)
            val ptLatRad = Math.toRadians(safeLat)
            val ptWorldY = (1.0 - ln(tan(ptLatRad) + 1.0 / cos(ptLatRad)) / Math.PI) / 2.0 * n * 256.0 * scaleFactor

            val px = (ptWorldX - originX).toFloat()
            val py = (ptWorldY - originY).toFloat()

            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }

        if (!started) return

        if (isAlternative) {
            // Tuyến thay thế: viền đen 12px + thân xám đá 7px
            val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(204, 13, 20, 31)
                style = Paint.Style.STROKE
                strokeWidth = 12f * scaleFactor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(100, 116, 139)
                style = Paint.Style.STROKE
                strokeWidth = 7f * scaleFactor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, outer)
            canvas.drawPath(path, inner)
        } else {
            // Tuyến chính: Viền đen cực dày + Thân Racing Neon Cyan + Lõi trắng
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 10, 31)
                style = Paint.Style.STROKE
                strokeWidth = 16f * scaleFactor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 224, 255) // Racing Neon Cyan
                style = Paint.Style.STROKE
                strokeWidth = 10f * scaleFactor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f * scaleFactor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, outline)
            canvas.drawPath(path, body)
            canvas.drawPath(path, core)
        }
    }

    /**
     * Vẽ biểu tượng xe máy trung tâm màn hình
     */
    private fun drawMotorcycleMarker(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        heading: Float,
        isNorthUp: Boolean,
        scaleFactor: Float = 1.0f
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        if (isNorthUp) {
            canvas.rotate(heading)
        }

        // Vòng hào quang phát sáng
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(89, 0, 153, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 22f * scaleFactor, haloPaint)

        // Viền đen tương phản
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 13, 31)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 13f * scaleFactor, borderPaint)

        // Vòng thân xe xanh Cyan
        val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 224, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(0f, 0f, 11f * scaleFactor, cyanPaint)

        // Mũi tên định hướng trắng
        val arrowPath = Path().apply {
            moveTo(0f, -15f * scaleFactor)
            lineTo(8f * scaleFactor, 9f * scaleFactor)
            lineTo(0f, 4.5f * scaleFactor)
            lineTo(-8f * scaleFactor, 9f * scaleFactor)
            close()
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, whitePaint)

        canvas.restore()
    }

    /**
     * Dải banner chỉ dẫn Turn-by-Turn phía trên (khoảng cách Neon Green + Tên đường trắng)
     * Tự động điều chỉnh kích thước theo cỡ chữ người dùng chọn và độ phân giải canvas
     */
    private fun drawTopNavigationBanner(
        canvas: Canvas,
        instruction: NavigationInstruction,
        isRerouting: Boolean,
        scaleFactor: Float = 1.0f
    ) {
        if (!instruction.isNavigating && !isRerouting && !instruction.hasArrived) return

        val baseFontSize = _headerFontSize.value.coerceIn(12f, 32f)
        val effectiveFontSize = baseFontSize * scaleFactor
        val bannerHeight = (baseFontSize * 2.0f).coerceAtLeast(32f) * scaleFactor
        val baselineY = bannerHeight * 0.70f
        val bannerRect = RectF(0f, 0f, targetWidth.toFloat(), bannerHeight)

        if (isRerouting) {
            val bgPaint = Paint().apply { color = Color.argb(250, 31, 20, 5) }
            val linePaint = Paint().apply {
                color = Color.YELLOW
                strokeWidth = 2f * scaleFactor
            }
            canvas.drawRect(bannerRect, bgPaint)
            canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, linePaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                textSize = effectiveFontSize * 0.9f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("⚠️ ĐANG TÍNH LẠI LỘ TRÌNH MỚI...", 12f * scaleFactor, baselineY, textPaint)
            return
        }

        if (instruction.hasArrived) {
            val bgPaint = Paint().apply { color = Color.argb(250, 8, 38, 15) }
            val linePaint = Paint().apply {
                color = Color.rgb(0, 255, 115)
                strokeWidth = 2f * scaleFactor
            }
            canvas.drawRect(bannerRect, bgPaint)
            canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, linePaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 255, 115)
                textSize = effectiveFontSize * 0.9f
                typeface = Typeface.DEFAULT_BOLD
            }
            val destText = if (instruction.destinationName.isEmpty()) "ĐIỂM ĐẾN" else instruction.destinationName
            canvas.drawText("🎯 ĐÃ ĐẾN NƠI: $destText", 12f * scaleFactor, baselineY, textPaint)
            return
        }

        // Banner dẫn đường bình thường
        val bgPaint = Paint().apply { color = Color.argb(250, 10, 15, 23) }
        val dividerPaint = Paint().apply {
            color = Color.rgb(0, 224, 255)
            strokeWidth = 2f * scaleFactor
        }
        canvas.drawRect(bannerRect, bgPaint)
        canvas.drawLine(0f, bannerHeight, targetWidth.toFloat(), bannerHeight, dividerPaint)

        val distText = "${instruction.formattedDistanceCompact} "
        val actionText = "${instruction.direction.actionPhraseEn} "
        val roadText = instruction.nextRoadName.trim()

        val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 115)
            textSize = effectiveFontSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = effectiveFontSize
            typeface = Typeface.DEFAULT_BOLD
        }

        var x = 12f * scaleFactor
        canvas.drawText(distText, x, baselineY, greenPaint)
        x += greenPaint.measureText(distText)

        canvas.drawText(actionText, x, baselineY, greenPaint)
        x += greenPaint.measureText(actionText)

        if (roadText.isNotEmpty()) {
            canvas.drawText(roadText, x, baselineY, whitePaint)
        }
    }

    private fun drawAttribution(canvas: Canvas, scaleFactor: Float = 1.0f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178, 153, 153, 153)
            textSize = 9f * scaleFactor
        }
        val text = clusterMapSource.attribution
        val textWidth = paint.measureText(text)
        canvas.drawText(text, targetWidth - textWidth - 8f * scaleFactor, targetHeight - 8f * scaleFactor, paint)
    }

    private fun drawFallbackScreen(
        canvas: Canvas,
        title: String,
        subtitle: String,
        speedKmh: Double,
        scaleFactor: Float = 1.0f
    ) {
        val gridPaint = Paint().apply {
            color = Color.argb(76, 51, 51, 51)
            strokeWidth = 1f * scaleFactor
        }
        val step = (40 * scaleFactor).toInt().coerceAtLeast(20)
        for (x in 0 until targetWidth step step) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), targetHeight.toFloat(), gridPaint)
        }
        for (y in 0 until targetHeight step step) {
            canvas.drawLine(0f, y.toFloat(), targetWidth.toFloat(), y.toFloat(), gridPaint)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f * scaleFactor
            typeface = Typeface.DEFAULT_BOLD
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 13f * scaleFactor
        }
        canvas.drawText(title, 24f * scaleFactor, 40f * scaleFactor, titlePaint)
        canvas.drawText(subtitle, 24f * scaleFactor, 68f * scaleFactor, subPaint)
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }
}
