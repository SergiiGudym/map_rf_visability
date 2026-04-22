@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "FunctionName")

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.accept
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.math.*

external object GeoTIFF {
    fun fromArrayBuffer(buffer: dynamic): dynamic
}

external object L {
    fun map(id: String): dynamic
    fun tileLayer(urlTemplate: String, options: dynamic = definedExternally): dynamic
    fun rectangle(bounds: dynamic, options: dynamic = definedExternally): dynamic
    fun circleMarker(latlng: dynamic, options: dynamic = definedExternally): dynamic
    fun circle(latlng: dynamic, options: dynamic = definedExternally): dynamic
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

private data class LngLat(val lon: Double, val lat: Double)

private data class RecalcResult(
    val visibleHeights: FloatArray,
    val deltaHeights: FloatArray,
    val radiusMask: BooleanArray,
    val maxDelta: Double
)

private data class CachedRasters(
    val key: String,
    val visibleTif: ByteArray?,
    val deltaTif: ByteArray?
)

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var mapReady by remember { mutableStateOf(false) }
    var raster by remember { mutableStateOf<RasterData?>(null) }
    var center by remember { mutableStateOf<LngLat?>(null) }
    var radiusKm by remember { mutableStateOf(12.0) }
    var mastHeight by remember { mutableStateOf(30.0) }
    var loadedFileName by remember { mutableStateOf("output_hh.tif") }
    var isBusy by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("Очікування завантаження GeoTIFF.") }
    var calcDurationSec by remember { mutableStateOf<Double?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var mapClickBound by remember { mutableStateOf(false) }

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
            statusMessage = statusMessage,
            errorMessage = errorMessage,
            isBusy = isBusy,
            progressPercent = progressPercent,
            calcDurationSec = calcDurationSec,
            onRadiusChange = { radiusKm = it },
            onMastHeightChange = { mastHeight = it },
            onRecalculate = {
                val r = raster ?: return@SettingsPanel
                val c = center ?: return@SettingsPanel
                scope.launch {
                    isBusy = true
                    progressPercent = 0
                    calcDurationSec = null
                    statusMessage = "Розрахунок зони покриття..."
                    val startedAt = window.performance.now()
                    drawCoverage(
                        r = r,
                        center = c,
                        radiusKm = radiusKm,
                        mastHeight = mastHeight,
                        fileName = loadedFileName,
                        onProgress = { progressPercent = it }
                    )
                    calcDurationSec = ((window.performance.now() - startedAt) / 1000.0)
                    updateCenterMarker(c)
                    statusMessage = "Розрахунок завершено за ${calcDurationSec?.format(2) ?: "?"} с."
                    isBusy = false
                }
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

            if (isBusy) {
                Div({
                    style {
                        position(Position.Absolute)
                        top(16.px)
                        left(16.px)
                        padding(12.px)
                        borderRadius(8.px)
                        backgroundColor(Color.white)
                        property("box-shadow", "0 2px 10px rgba(0,0,0,0.12)")
                        maxWidth(320.px)
                    }
                }) {
                    Text(statusMessage)
                    if (progressPercent in 0..99) {
                        Div({
                            style {
                                marginTop(8.px)
                                width(100.percent)
                                height(10.px)
                                backgroundColor(rgb(232, 236, 240))
                                borderRadius(8.px)
                                overflow("hidden")
                            }
                        }) {
                            Div({
                                style {
                                    width(progressPercent.percent)
                                    height(100.percent)
                                    backgroundColor(rgb(30, 116, 255))
                                }
                            })
                        }
                        Small { Text("${progressPercent}%") }
                    }
                }
            }
        }
    }

    LaunchedEffect(raster) {
        raster?.let {
            drawRasterBounds(it)
            fitToRaster(it)
        }
    }

    LaunchedEffect(center, radiusKm) {
        val c = center ?: return@LaunchedEffect
        updateCenterMarker(c)
        drawRadiusCircle(c, radiusKm)
    }

    LaunchedEffect(mapReady, raster, mapClickBound) {
        if (!mapReady || mapClickBound) return@LaunchedEffect
        setupMapClick { lat, lon ->
            val r = raster ?: return@setupMapClick
            if (lon in r.minLon..r.maxLon && lat in r.minLat..r.maxLat) {
                center = LngLat(lon, lat)
                statusMessage = "Центр обрано. Натисніть «Перерахувати»."
            }
        }
        mapClickBound = true
    }

    DisposableEffect(Unit) {
        hookFileLoader { name, bytes ->
            if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return@hookFileLoader
            scope.launch {
                isBusy = true
                progressPercent = 0
                errorMessage = null
                loadedFileName = name
                statusMessage = "Завантаження файлу $name..."
                try {
                    statusMessage = "Парсинг GeoTIFF..."
                    raster = parseGeoTiff(bytes)
                    statusMessage = "GeoTIFF успішно завантажено."
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val msg = t.message ?: "Невідома помилка."
                    errorMessage = msg
                    statusMessage = "Помилка: $msg"
                } finally {
                    isBusy = false
                }
            }
        }
        onDispose { }
    }
}

@Composable
private fun SettingsPanel(
    center: LngLat?,
    radiusKm: Double,
    mastHeight: Double,
    statusMessage: String,
    errorMessage: String?,
    isBusy: Boolean,
    progressPercent: Int,
    calcDurationSec: Double?,
    onRadiusChange: (Double) -> Unit,
    onMastHeightChange: (Double) -> Unit,
    onRecalculate: () -> Unit,
) {
    var radiusInput by remember(radiusKm) { mutableStateOf(radiusKm.toString()) }
    var mastHeightInput by remember(mastHeight) { mutableStateOf(mastHeight.toString()) }

    LaunchedEffect(radiusKm) {
        val normalized = radiusKm.toString()
        if (radiusInput != normalized) radiusInput = normalized
    }
    LaunchedEffect(mastHeight) {
        val normalized = mastHeight.toString()
        if (mastHeightInput != normalized) mastHeightInput = normalized
    }

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
        if (errorMessage != null) {
            P({
                style {
                    color(Color.red)
                    fontWeight("600")
                }
            }) { Text("Помилка: $errorMessage") }
        } else {
            P({
                style { color(rgb(90, 95, 104)) }
            }) {
                Text(statusMessage)
            }
        }

        Hr()
        H4 { Text("Центр") }
        if (center != null) {
            P { Text("lat=${center.lat.format(6)}") }
            P { Text("lon=${center.lon.format(6)}") }
            Button(attrs = {
                onClick { onRecalculate() }
                if (isBusy) attr("disabled", "true")
            }) {
                Text(if (isBusy) "Розрахунок..." else "Перерахувати")
            }
            if (isBusy) {
                P { Text("Прогрес: $progressPercent%") }
            } else if (calcDurationSec != null) {
                P { Text("Останній перерахунок: ${calcDurationSec!!.format(2)} с") }
            }
        } else {
            P { Text("Натисніть в межах контуру") }
        }

        H4 { Text("Параметри") }
        Label { Text("Радіус, км") }
        Input(InputType.Text) {
            value(radiusInput)
            onInput { event ->
                val target = event.target as? HTMLInputElement ?: return@onInput
                radiusInput = target.value
                target.value.toDoubleOrNull()?.let(onRadiusChange)
            }
        }

        Br(); Br()
        Label { Text("Висота обладнання (центр), м") }
        Input(InputType.Text) {
            value(mastHeightInput)
            onInput { event ->
                val target = event.target as? HTMLInputElement ?: return@onInput
                mastHeightInput = target.value
                target.value.toDoubleOrNull()?.let(onMastHeightChange)
            }
        }

        Hr()
        H4 { Text("Легенда/схема") }
        P { Text("Дельта висот: зелений → синій → червоний, вища дельта = більш насичений шар.") }
    }
}

private suspend fun parseGeoTiff(bytes: dynamic): RasterData {
    val tiff = awaitIfPromise(GeoTIFF.fromArrayBuffer(bytes))
    val image = awaitIfPromise(tiff.getImage(0))
    val width = image.getWidth() as Int
    val height = image.getHeight() as Int
    val bbox = image.getBoundingBox()
    val arr = awaitIfPromise(image.readRasters(js("({ interleave: true })")))

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

private suspend fun awaitIfPromise(value: dynamic): dynamic {
    if (value == null || value == undefined) return value
    val then = value.then
    return if (jsTypeOf(then) == "function") {
        (value as Promise<dynamic>).await()
    } else {
        value
    }
}

private fun valueAt(r: RasterData, lat: Double, lon: Double): Double {
    val x = ((lon - r.minLon) / (r.maxLon - r.minLon) * (r.width - 1)).roundToInt().coerceIn(0, r.width - 1)
    val y = ((r.maxLat - lat) / (r.maxLat - r.minLat) * (r.height - 1)).roundToInt().coerceIn(0, r.height - 1)
    return r.values[y * r.width + x].toDouble()
}

private val recalculationCache = mutableMapOf<String, CachedRasters>()

private suspend fun drawCoverage(
    r: RasterData,
    center: LngLat,
    radiusKm: Double,
    mastHeight: Double,
    fileName: String,
    onProgress: (Int) -> Unit
) {
    val map = window.asDynamic().__rf_map
    if (map == undefined || map == null) return

    window.asDynamic().__rf_coverage_layer?.remove()

    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = r.width
    canvas.height = r.height
    val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return

    val cacheKey = buildCacheKey(fileName, center, radiusKm, mastHeight)
    val result = buildVisibilityRasters(r, center, radiusKm, mastHeight, onProgress)

    val visibleTif = encodeGeoTiffOrNull(r, result.visibleHeights)
    val deltaTif = encodeGeoTiffOrNull(r, result.deltaHeights)
    recalculationCache[cacheKey] = CachedRasters(
        key = cacheKey,
        visibleTif = visibleTif,
        deltaTif = deltaTif
    )
    saveCachedRastersNearSource(fileName, recalculationCache[cacheKey]!!)

    val totalRows = r.height
    var processedRows = 0
    for (yy in 0 until r.height) {
        for (xx in 0 until r.width) {
            val index = yy * r.width + xx
            if (!result.radiusMask[index]) continue
            val delta = result.deltaHeights[index].toDouble()
            if (delta <= 0.0) continue
            val (color, alpha) = gradientColorForDelta(delta, result.maxDelta)
            ctx.fillStyle = color
            ctx.globalAlpha = alpha
            ctx.fillRect(xx.toDouble(), yy.toDouble(), 1.0, 1.0)
        }
        processedRows += 1
        onProgress(50 + ((processedRows.toDouble() / totalRows) * 50.0).roundToInt().coerceIn(0, 50))
        if (processedRows % 10 == 0) yield()
    }
    ctx.globalAlpha = 1.0
    onProgress(100)

    val dataUrl = canvas.toDataURL("image/png")
    val bounds = arrayOf(arrayOf(r.minLat, r.minLon), arrayOf(r.maxLat, r.maxLon))
    val coverageLayer = js("L.imageOverlay(dataUrl, bounds, {opacity: 0.95, interactive: false, pane: 'overlayPane'})")
    coverageLayer.addTo(map)
    window.asDynamic().__rf_coverage_layer = coverageLayer
}

private fun buildVisibilityRasters(
    r: RasterData,
    center: LngLat,
    radiusKm: Double,
    mastHeight: Double,
    onProgress: (Int) -> Unit
): RecalcResult {
    val centerX = ((center.lon - r.minLon) / (r.maxLon - r.minLon) * (r.width - 1)).roundToInt().coerceIn(0, r.width - 1)
    val centerY = ((r.maxLat - center.lat) / (r.maxLat - r.minLat) * (r.height - 1)).roundToInt().coerceIn(0, r.height - 1)
    val centerHeight = r.values[centerY * r.width + centerX].toDouble() + mastHeight
    val kmPerPixel = haversineKm(center.lat, center.lon, center.lat, center.lon + (r.maxLon - r.minLon) / max(2, r.width))
        .coerceAtLeast(0.001)
    val radiusPx = max(1, (radiusKm / kmPerPixel).roundToInt())
    val visible = r.values.copyOf()
    val delta = FloatArray(r.values.size)
    val mask = BooleanArray(r.values.size)
    val maxAllowedHeight = 1500.0

    val minX = max(0, centerX - radiusPx)
    val maxX = min(r.width - 1, centerX + radiusPx)
    val minY = max(0, centerY - radiusPx)
    val maxY = min(r.height - 1, centerY + radiusPx)
    val totalRows = max(1, maxY - minY + 1)
    var processedRows = 0

    for (y in minY..maxY) {
        for (x in minX..maxX) {
            val dx = x - centerX
            val dy = y - centerY
            val distPx = sqrt((dx * dx + dy * dy).toDouble())
            if (distPx > radiusPx) continue
            val index = y * r.width + x
            val lon = r.minLon + x.toDouble() / (r.width - 1) * (r.maxLon - r.minLon)
            val lat = r.maxLat - y.toDouble() / (r.height - 1) * (r.maxLat - r.minLat)
            if (haversineKm(center.lat, center.lon, lat, lon) > radiusKm) continue

            mask[index] = true
            val targetHeight = min(maxAllowedHeight, r.values[index].toDouble())
            if (distPx < 1.0) {
                visible[index] = targetHeight.toFloat()
                continue
            }

            var maxBlockingSlope = Double.NEGATIVE_INFINITY
            val steps = max(1, floor(distPx).toInt())
            for (step in 1 until steps) {
                val t = step.toDouble() / distPx
                val sampleX = centerX + dx * t
                val sampleY = centerY + dy * t
                val terrain = min(maxAllowedHeight, bilinearSample(r, sampleX, sampleY))
                val slope = (terrain - centerHeight) / step.toDouble()
                if (slope > maxBlockingSlope) maxBlockingSlope = slope
            }

            val minVisibleHeight = if (maxBlockingSlope == Double.NEGATIVE_INFINITY) {
                targetHeight
            } else {
                centerHeight + maxBlockingSlope * distPx
            }
            visible[index] = max(targetHeight, min(minVisibleHeight, maxAllowedHeight)).toFloat()
        }
        processedRows += 1
        val progress = ((processedRows.toDouble() / totalRows) * 50.0).roundToInt().coerceIn(0, 50)
        onProgress(progress)
    }

    var maxDelta = 0.0
    for (i in visible.indices) {
        if (!mask[i]) continue
        val original = min(maxAllowedHeight, r.values[i].toDouble())
        val adjusted = min(maxAllowedHeight, visible[i].toDouble())
        val d = max(0.0, adjusted - original)
        delta[i] = d.toFloat()
        if (d > maxDelta) maxDelta = d
    }
    return RecalcResult(visible, delta, mask, maxDelta)
}

private fun gradientColorForDelta(delta: Double, maxDelta: Double): Pair<String, Double> {
    if (maxDelta <= 0.0) return "#2ecc71" to 0.45
    val t = (delta / maxDelta).coerceIn(0.0, 1.0)
    val (r, g, b) = when {
        t < 0.5 -> lerpColor(0x2e, 0xcc, 0x71, 0x34, 0x98, 0xdb, t * 2.0) // green -> blue
        else -> lerpColor(0x34, 0x98, 0xdb, 0xe7, 0x4c, 0x3c, (t - 0.5) * 2.0) // blue -> red
    }
    val color = "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}"
    val alpha = 0.35 + 0.5 * t
    return color to alpha
}

private fun bilinearSample(r: RasterData, x: Double, y: Double): Double {
    val x0 = floor(x).toInt().coerceIn(0, r.width - 1)
    val y0 = floor(y).toInt().coerceIn(0, r.height - 1)
    val x1 = (x0 + 1).coerceIn(0, r.width - 1)
    val y1 = (y0 + 1).coerceIn(0, r.height - 1)
    val tx = x - x0
    val ty = y - y0
    val q11 = r.values[y0 * r.width + x0].toDouble()
    val q21 = r.values[y0 * r.width + x1].toDouble()
    val q12 = r.values[y1 * r.width + x0].toDouble()
    val q22 = r.values[y1 * r.width + x1].toDouble()
    val top = q11 * (1 - tx) + q21 * tx
    val bottom = q12 * (1 - tx) + q22 * tx
    return top * (1 - ty) + bottom * ty
}

private fun lerpColor(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int, t: Double): Triple<Int, Int, Int> {
    val clamped = t.coerceIn(0.0, 1.0)
    return Triple(
        (r1 + (r2 - r1) * clamped).roundToInt().coerceIn(0, 255),
        (g1 + (g2 - g1) * clamped).roundToInt().coerceIn(0, 255),
        (b1 + (b2 - b1) * clamped).roundToInt().coerceIn(0, 255),
    )
}

private fun buildCacheKey(fileName: String, center: LngLat, radiusKm: Double, mastHeight: Double): String =
    "${fileName.lowercase()}::${center.lat.format(6)}::${center.lon.format(6)}::${radiusKm.format(3)}::${mastHeight.format(3)}"

private fun encodeGeoTiffOrNull(r: RasterData, values: FloatArray): ByteArray? {
    val writeFunction = GeoTIFF.asDynamic().writeArrayBuffer
    if (jsTypeOf(writeFunction) != "function") return null

    val metadata = js("({})")
    metadata.width = r.width
    metadata.height = r.height
    metadata.ImageWidth = r.width
    metadata.ImageLength = r.height
    metadata.BitsPerSample = arrayOf(32)
    metadata.SampleFormat = arrayOf(3) // floating point
    metadata.SamplesPerPixel = 1
    metadata.Compression = 1 // no compression
    metadata.PhotometricInterpretation = 1 // BlackIsZero
    metadata.GeoKeyDirectory = js("({ GTModelTypeGeoKey: 2, GTRasterTypeGeoKey: 1 })")
    metadata.ModelTiepoint = arrayOf(0, 0, 0, r.minLon, r.maxLat, 0)
    metadata.ModelPixelScale = arrayOf(
        (r.maxLon - r.minLon) / max(1, r.width - 1).toDouble(),
        (r.maxLat - r.minLat) / max(1, r.height - 1).toDouble(),
        0
    )

    val floatArray = js("new Float32Array(values.length)")
    for (i in values.indices) floatArray[i] = values[i]

    val buffer = writeFunction(floatArray, metadata)
    val uint8 = js("new Uint8Array(buffer)")
    val output = ByteArray(uint8.length as Int)
    for (i in output.indices) output[i] = (uint8[i] as Number).toInt().toByte()
    return output
}

private fun saveCachedRastersNearSource(fileName: String, cached: CachedRasters) {
    saveBlob("${fileName.substringBeforeLast('.')}_visible.tif", cached.visibleTif)
    saveBlob("${fileName.substringBeforeLast('.')}_delta.tif", cached.deltaTif)
}

private fun saveBlob(name: String, bytes: ByteArray?) {
    if (bytes == null) return
    val blob = js("new Blob([bytes], {type:'image/tiff'})")
    val url = window.asDynamic().URL.createObjectURL(blob)
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = name
    a.style.display = "none"
    document.body?.appendChild(a)
    a.click()
    a.remove()
    window.asDynamic().URL.revokeObjectURL(url)
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

private fun drawRadiusCircle(c: LngLat, radiusKm: Double) {
    val map = window.asDynamic().__rf_map ?: return
    window.asDynamic().__rf_radius_circle?.remove()
    val options = js("({})")
    options.radius = radiusKm * 1000.0
    options.color = "#1e74ff"
    options.weight = 2
    options.fill = false
    options.dashArray = "8 6"
    val circle = L.circle(
        arrayOf(c.lat, c.lon),
        options
    )
    circle.addTo(map)
    window.asDynamic().__rf_radius_circle = circle
}

private fun setupMapClick(onClick: (lat: Double, lon: Double) -> Unit) {
    val map = window.asDynamic().__rf_map ?: return
    map.on("click") { e ->
        val dynamicEvent = e.unsafeCast<dynamic>()
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

private fun Double.format(digits: Int): String = unsafeCast<dynamic>().toFixed(digits) as String

private fun Double.toRadians(): Double = this * PI / 180.0

fun main() {
    renderComposable(rootElementId = "root") {
        App()
    }
}
