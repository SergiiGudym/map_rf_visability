@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "FunctionName")

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.accept
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLInputElement
import kotlin.math.*

external object GeoTIFF {
    fun fromArrayBuffer(buffer: dynamic): dynamic
}

external object L {
    fun map(id: String): dynamic
    fun tileLayer(urlTemplate: String, options: dynamic = definedExternally): dynamic
    fun rectangle(bounds: dynamic, options: dynamic = definedExternally): dynamic
    fun circleMarker(latlng: dynamic, options: dynamic = definedExternally): dynamic
    fun canvasOverlay(): dynamic
}

private data class RasterData(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double
)

private data class Threshold(var limit: Double, var color: String, var opacity: Double, val label: String)

private data class LngLat(val lon: Double, val lat: Double)

@Composable
fun App() {
    var mapReady by remember { mutableStateOf(false) }
    var raster by remember { mutableStateOf<RasterData?>(null) }
    var center by remember { mutableStateOf<LngLat?>(null) }
    var radiusKm by remember { mutableStateOf(12.0) }
    var mastHeight by remember { mutableStateOf(30.0) }

    val thresholds = remember {
        mutableStateListOf(
            Threshold(5.0, "#000000", 0.0, "≤ 5m (прозоро)"),
            Threshold(50.0, "#00b050", 0.35, "≤ 50m (зелений)"),
            Threshold(200.0, "#1e74ff", 0.45, "≤ 200m (синій)"),
            Threshold(500.0, "#ff3b30", 0.55, "≤ 500m (червоний)"),
            Threshold(1500.0, "#000000", 0.70, "1500m+ (чорний)"),
        )
    }

    LaunchedEffect(Unit) {
        initMap { mapReady = true }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            width(100.vw)
            height(100.vh)
            fontFamily("Inter", "Arial", "sans-serif")
            backgroundColor(rgb(248, 249, 251))
        }
    }) {
        SettingsPanel(
            center = center,
            radiusKm = radiusKm,
            mastHeight = mastHeight,
            thresholds = thresholds,
            onRadiusChange = { radiusKm = it },
            onMastHeightChange = { mastHeight = it },
            onThresholdChange = { index, limit, opacity ->
                thresholds[index].limit = limit
                thresholds[index].opacity = opacity
            },
        )

        Div({
            style {
                flexGrow(1)
                position(Position.Relative)
            }
        }) {
            Div({
                id("map")
                style {
                    width(100.percent)
                    height(100.percent)
                }
            })

            if (!mapReady) {
                Div({
                    style {
                        position(Position.Absolute)
                        top(16.px)
                        right(16.px)
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color.white)
                    }
                }) { Text("Ініціалізація карти...") }
            }
        }
    }

    LaunchedEffect(raster) {
        raster?.let {
            drawRasterBounds(it)
            fitToRaster(it)
        }
    }

    LaunchedEffect(center, raster, radiusKm, mastHeight, thresholds.toList()) {
        val r = raster ?: return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        drawCoverage(r, c, radiusKm, mastHeight, thresholds)
        updateCenterMarker(c)
    }

    DisposableEffect(Unit) {
        setupMapClick { lat, lon ->
            val r = raster ?: return@setupMapClick
            if (lon in r.minLon..r.maxLon && lat in r.minLat..r.maxLat) {
                center = LngLat(lon, lat)
            }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        hookFileLoader { name, bytes ->
            if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return@hookFileLoader
            raster = parseGeoTiff(bytes)
        }
        onDispose { }
    }
}

@Composable
private fun SettingsPanel(
    center: LngLat?,
    radiusKm: Double,
    mastHeight: Double,
    thresholds: List<Threshold>,
    onRadiusChange: (Double) -> Unit,
    onMastHeightChange: (Double) -> Unit,
    onThresholdChange: (index: Int, limit: Double, opacity: Double) -> Unit,
) {
    Aside({
        style {
            width(330.px)
            minWidth(300.px)
            padding(16.px)
            backgroundColor(Color.white)
            property("border-right", "1px solid rgb(230, 232, 235)")
            overflowY("auto")
        }
    }) {
        H3 { Text("Налаштування") }
        P { Text("1) Завантажте локальний output_hh.tif") }
        Input(type = InputType.File) {
            accept(".tif,.tiff")
            onInput {
                val target = it.target as? HTMLInputElement ?: return@onInput
                val file = target.files?.item(0) ?: return@onInput
                val reader = js("new FileReader()")
                reader.onload = { _: dynamic ->
                    val result = reader.result
                    window.asDynamic().__rf_file_name = file.name
                    window.asDynamic().__rf_file_bytes = result
                    window.dispatchEvent(js("new Event('rf-file-loaded')"))
                }
                reader.readAsArrayBuffer(file)
            }
        }

        Hr()
        H4 { Text("Центр") }
        P { Text(center?.let { "lat=${it.lat.format(6)}, lon=${it.lon.format(6)}" } ?: "Натисніть в межах контуру") }

        H4 { Text("Параметри") }
        Label { Text("Радіус, км") }
        Input(InputType.Number) {
            value(radiusKm.toString())
            onInput { event -> onRadiusChange(event.inputValueOrNull() ?: radiusKm) }
        }

        Br(); Br()
        Label { Text("Висота обладнання (центр), м") }
        Input(InputType.Number) {
            value(mastHeight.toString())
            onInput { event -> onMastHeightChange(event.inputValueOrNull() ?: mastHeight) }
        }

        Hr()
        H4 { Text("Легенда/схема") }
        thresholds.forEachIndexed { idx, t ->
            Div({ style { marginBottom(10.px) } }) {
                Small { Text(t.label) }
                Input(InputType.Number) {
                    value(t.limit.toString())
                    onInput { event -> onThresholdChange(idx, event.inputValueOrNull() ?: t.limit, t.opacity) }
                }
                Input(InputType.Number) {
                    value(t.opacity.toString())
                    onInput { event -> onThresholdChange(idx, t.limit, event.inputValueOrNull() ?: t.opacity) }
                    placeholder("opacity 0..1")
                }
                Div({
                    style {
                        width(100.percent)
                        height(10.px)
                        backgroundColor(Color(t.color))
                        opacity(t.opacity)
                    }
                })
            }
        }
    }
}

private fun parseGeoTiff(bytes: dynamic): RasterData {
    val tiff = GeoTIFF.fromArrayBuffer(bytes)
    val image = tiff.getImage(0)
    val width = image.getWidth() as Int
    val height = image.getHeight() as Int
    val bbox = image.getBoundingBox()
    val arr = image.readRasters(js("({ interleave: true })"))

    val values = FloatArray(width * height)
    for (i in values.indices) values[i] = (arr[i] as Number).toFloat()

    return RasterData(
        width = width,
        height = height,
        values = values,
        minLon = (bbox[0] as Number).toDouble(),
        minLat = (bbox[1] as Number).toDouble(),
        maxLon = (bbox[2] as Number).toDouble(),
        maxLat = (bbox[3] as Number).toDouble(),
    )
}

private fun valueAt(r: RasterData, lat: Double, lon: Double): Double {
    val x = ((lon - r.minLon) / (r.maxLon - r.minLon) * (r.width - 1)).roundToInt().coerceIn(0, r.width - 1)
    val y = ((r.maxLat - lat) / (r.maxLat - r.minLat) * (r.height - 1)).roundToInt().coerceIn(0, r.height - 1)
    return r.values[y * r.width + x].toDouble()
}

private fun colorFor(heightNeed: Double, thresholds: List<Threshold>): Pair<String, Double> {
    val sorted = thresholds.sortedBy { it.limit }
    val t = sorted.firstOrNull { heightNeed <= it.limit } ?: sorted.last()
    return t.color to t.opacity
}

private fun drawCoverage(r: RasterData, center: LngLat, radiusKm: Double, mastHeight: Double, thresholds: List<Threshold>) {
    val map = window.asDynamic().__rf_map
    if (map == undefined || map == null) return

    val canvas = document.getElementById("coverageCanvas") as? HTMLCanvasElement ?: run {
        val c = document.createElement("canvas") as HTMLCanvasElement
        c.id = "coverageCanvas"
        c.style.position = "absolute"
        c.style.top = "0"
        c.style.left = "0"
        c.style.setProperty("pointer-events", "none")
        c.width = 1400
        c.height = 1000
        document.getElementById("map")!!.appendChild(c)
        c
    }

    val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return
    ctx.clearRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())

    val step = max(1, min(r.width, r.height) / 150)
    for (yy in 0 until r.height step step) {
        for (xx in 0 until r.width step step) {
            val lon = r.minLon + xx.toDouble() / (r.width - 1) * (r.maxLon - r.minLon)
            val lat = r.maxLat - yy.toDouble() / (r.height - 1) * (r.maxLat - r.minLat)
            val distKm = haversineKm(center.lat, center.lon, lat, lon)
            if (distKm > radiusKm) continue

            val needed = max(0.0, valueAt(r, lat, lon) - mastHeight)
            val (color, alpha) = colorFor(needed, thresholds)

            val point = map.latLngToContainerPoint(arrayOf(lat, lon))
            ctx.fillStyle = color
            ctx.globalAlpha = alpha
            ctx.fillRect((point.x as Number).toDouble(), (point.y as Number).toDouble(), 5.0, 5.0)
        }
    }
    ctx.globalAlpha = 1.0
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a = sin(dLat / 2).pow(2) + cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
    return 2 * 6371 * asin(sqrt(a))
}

private fun initMap(onReady: () -> Unit) {
    val map = L.map("map")
    window.asDynamic().__rf_map = map

    L.tileLayer(
        "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
        js("({ maxZoom: 19, attribution: '&copy; OpenStreetMap contributors' })")
    ).addTo(map)

    map.setView(arrayOf(50.4501, 30.5234), 8)
    onReady()
}

private fun drawRasterBounds(r: RasterData) {
    val map = window.asDynamic().__rf_map ?: return
    window.asDynamic().__rf_bounds_layer?.remove()
    val layer = L.rectangle(
        arrayOf(arrayOf(r.minLat, r.minLon), arrayOf(r.maxLat, r.maxLon)),
        js("({ color: '#ff8800', weight: 2, fill: false })")
    )
    layer.addTo(map)
    window.asDynamic().__rf_bounds_layer = layer
}

private fun fitToRaster(r: RasterData) {
    val map = window.asDynamic().__rf_map ?: return
    map.fitBounds(arrayOf(arrayOf(r.minLat, r.minLon), arrayOf(r.maxLat, r.maxLon)))
}

private fun updateCenterMarker(c: LngLat) {
    val map = window.asDynamic().__rf_map ?: return
    window.asDynamic().__rf_center?.remove()
    val marker = L.circleMarker(arrayOf(c.lat, c.lon), js("({radius: 7, color: '#000', fillColor:'#ffd400', fillOpacity:0.9})"))
    marker.addTo(map)
    window.asDynamic().__rf_center = marker
}

private fun setupMapClick(onClick: (lat: Double, lon: Double) -> Unit) {
    val map = window.asDynamic().__rf_map ?: return
    map.on("click") { e ->
        val dynamicEvent = e.asDynamic()
        onClick((dynamicEvent.latlng.lat as Number).toDouble(), (dynamicEvent.latlng.lng as Number).toDouble())
    }
}

private fun hookFileLoader(onLoaded: (name: String, bytes: dynamic) -> Unit) {
    window.addEventListener("rf-file-loaded", {
        onLoaded(
            window.asDynamic().__rf_file_name as String,
            window.asDynamic().__rf_file_bytes
        )
    })
}

private fun Double.format(digits: Int): String = asDynamic().toFixed(digits) as String

private fun Double.toRadians(): Double = this * PI / 180.0

private fun Any.inputValueOrNull(): Double? =
    (asDynamic().target as? HTMLInputElement)?.value?.toDoubleOrNull()

fun main() {
    renderComposable(rootElementId = "root") {
        App()
    }
}
