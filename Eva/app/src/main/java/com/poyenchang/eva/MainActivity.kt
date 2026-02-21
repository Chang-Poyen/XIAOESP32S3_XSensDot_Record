package com.poyenchang.eva

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poyenchang.eva.ui.theme.EvaTheme
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.FileInputStream
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureRuntimePermissions()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            EvaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    MainScreen(Modifier.padding(inner), this)
                }
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }
}

private class MovellaDotController(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onSample: (String) -> Unit,
    private val onDeviceCount: (Int) -> Unit,
) {
    private var scanner: Any? = null
    private var scannerCallbackProxy: Any? = null
    private var deviceCallbackProxy: Any? = null
    private var dotDeviceClass: Class<*>? = null
    private val devices = ConcurrentHashMap<String, Any>()
    private val writers = ConcurrentHashMap<String, FileWriter>()
    private val flushCounter = ConcurrentHashMap<String, Int>()
    @Volatile private var started = false
    @Volatile private var recording = false
    @Volatile private var maxDevices = 2
    @Volatile private var sessionSensorDir: File? = null

    fun setMaxDevices(count: Int) {
        maxDevices = count.coerceIn(1, 8)
        onStatus("DOT: max devices = $maxDevices")
    }

    fun start() {
        if (started) return
        started = true
        if (!hasBleRuntimePermissions(activity)) {
            onStatus("DOT: 藍牙權限未授權")
            started = false
            return
        }
        runCatching {
            initScanner()
            startScan()
            onStatus("DOT: 掃描中...")
        }.onFailure {
            started = false
            onStatus("DOT 初始化失敗: ${it.message}")
        }
    }

    fun startRecording(sensorDir: File) {
        sensorDir.mkdirs()
        sessionSensorDir = sensorDir
        recording = true
        devices.forEach { (_, device) ->
            runCatching { callNoArg(device, "startMeasuring") }
        }
        onStatus("DOT: recording start (${devices.size} devices)")
    }

    fun stopRecording() {
        recording = false
        devices.forEach { (_, device) ->
            runCatching { callNoArg(device, "stopMeasuring") }
        }
        closeAllWriters()
        onStatus("DOT: recording stop, data saved")
    }

    fun shutdown() {
        stopRecording()
        stopScan()
        disconnectAll()
        started = false
        onStatus("DOT: stopped")
    }

    private fun initScanner() {
        Class.forName("com.xsens.dot.android.sdk.DotSdk").also { sdkClass ->
            callStaticNoArg(sdkClass, "setReconnectEnabled", true)
            callStaticNoArg(sdkClass, "setDebugEnabled", true)
        }
        val scannerClass = classForNames(
            "com.xsens.dot.android.sdk.DotScanner",
            "com.xsens.dot.android.sdk.utils.DotScanner"
        )
        val scannerCallbackClass = Class.forName("com.xsens.dot.android.sdk.interfaces.DotScannerCallback")
        dotDeviceClass = classForNames(
            "com.xsens.dot.android.sdk.DotDevice",
            "com.xsens.dot.android.sdk.models.DotDevice"
        )

        scannerCallbackProxy = Proxy.newProxyInstance(
            scannerCallbackClass.classLoader,
            arrayOf(scannerCallbackClass)
        ) { _, method, args ->
            if (method.name == "onDotScanned") {
                val bt = args?.getOrNull(0) as? BluetoothDevice
                if (bt != null) handleScanned(bt)
            }
            null
        }
        val ctor = scannerClass.getConstructor(Context::class.java, scannerCallbackClass)
        scanner = ctor.newInstance(activity, scannerCallbackProxy)
        deviceCallbackProxy = newDeviceCallbackProxy()
    }

    private fun classForNames(vararg names: String): Class<*> {
        var last: Throwable? = null
        for (n in names) {
            try {
                return Class.forName(n)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw ClassNotFoundException("none found: ${names.joinToString()}", last)
    }

    private fun newDeviceCallbackProxy(): Any {
        val callbackClass = Class.forName("com.xsens.dot.android.sdk.interfaces.DotDeviceCallback")
        return Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
                "onDotConnectionChanged" -> {
                    val address = args?.getOrNull(0) as? String ?: return@newProxyInstance null
                    onStatus("DOT connected: $address")
                    onDeviceCount(devices.size)
                }
                "onDotInitDone" -> {
                    val address = args?.getOrNull(0) as? String ?: return@newProxyInstance null
                    onStatus("DOT init done: $address")
                    devices[address]?.let { d ->
                        // Use a payload mode that always contains inertial data.
                        configureMeasurementMode(d)
                    }
                    if (recording) {
                        devices[address]?.let { runCatching { callNoArg(it, "startMeasuring") } }
                    }
                }
                "onDotDataChanged" -> {
                    val address = args?.getOrNull(0) as? String ?: return@newProxyInstance null
                    val dotData = args.getOrNull(1) ?: return@newProxyInstance null
                    if (recording) appendDotSample(address, dotData)
                }
            }
            null
        }
    }

    private fun handleScanned(bt: BluetoothDevice) {
        val address = bt.address ?: return
        if (devices.containsKey(address)) return
        if (devices.size >= maxDevices) return
        runCatching {
            val cls = dotDeviceClass ?: return
            val callbackClass = Class.forName("com.xsens.dot.android.sdk.interfaces.DotDeviceCallback")
            val ctor = cls.getConstructor(Context::class.java, BluetoothDevice::class.java, callbackClass)
            val device = ctor.newInstance(activity, bt, deviceCallbackProxy)
            devices[address] = device
            onDeviceCount(devices.size)
            callNoArg(device, "connect")
            onStatus("DOT connecting: $address")
        }.onFailure {
            onStatus("DOT connect error: ${it.message}")
        }
    }

    private fun appendDotSample(address: String, dotData: Any) {
        val writer = openWriter(address) ?: return
        val packetCounter = callMaybe(dotData, "getPacketCounter")?.toString() ?: ""
        val quatObj = callMaybe(dotData, "getQuat")
        var quat = joinValues(quatObj)
        var acc = joinValues(callMaybe(dotData, "getAcc"))
        var gyr = joinValues(callMaybe(dotData, "getGyr"))
        val freeAcc = joinValues(callMaybe(dotData, "getFreeAcc"))
        val dq = joinValues(callMaybe(dotData, "getDq"))
        val dv = joinValues(callMaybe(dotData, "getDv"))
        var euler = joinValues(callMaybe(dotData, "getEuler"))
        // Some payload modes expose freeAcc/dq/dv instead of acc/gyr.
        if (acc.isBlank()) acc = freeAcc
        if (gyr.isBlank()) gyr = dq
        // Some payloads provide quaternion only; derive Euler in app for unified output.
        if (euler.isBlank() && quatObj is FloatArray && quatObj.size >= 4) {
            euler = convertQuatToEuler(quatObj)
        }
        // Some payloads may provide only one orientation representation.
        if (quat.isBlank() && euler.isNotBlank()) quat = "from_euler:$euler"
        if (euler.isBlank() && quat.isNotBlank()) euler = "from_quat:$quat"
        writer.append("${System.currentTimeMillis()},$packetCounter,$quat,$euler,$acc,$gyr,$freeAcc,$dq,$dv\n")

        val next = (flushCounter[address] ?: 0) + 1
        if (next >= 20) {
            writer.flush()
            flushCounter[address] = 0
        } else {
            flushCounter[address] = next
        }
        onSample(address)
    }

    private fun openWriter(address: String): FileWriter? {
        val existing = writers[address]
        if (existing != null) return existing
        val base = sessionSensorDir ?: return null
        val file = File(base, "dot_$address.csv")
        val writer = FileWriter(file, true)
        if (file.length() == 0L) {
            writer.append("phone_ts_ms,packet_counter,quat,euler,acc,gyr,freeAcc,dq,dv\n")
            writer.flush()
        }
        writers[address] = writer
        return writer
    }

    private fun closeAllWriters() {
        writers.forEach { (_, w) ->
            runCatching {
                w.flush()
                w.close()
            }
        }
        writers.clear()
        flushCounter.clear()
    }

    private fun startScan() {
        val s = scanner ?: return
        if (!callTry(s, "startScan")) {
            callTry(s, "startScanning")
        }
    }

    private fun stopScan() {
        val s = scanner ?: return
        if (!callTry(s, "stopScan")) {
            callTry(s, "stopScanning")
        }
    }

    private fun disconnectAll() {
        devices.forEach { (_, d) ->
            callTry(d, "disconnect")
        }
        devices.clear()
        onDeviceCount(0)
    }

    private fun configureMeasurementMode(device: Any) {
        runCatching {
            val payloadCls = Class.forName("com.xsens.dot.android.sdk.models.DotPayload")
            // Explicitly prefer modes that contain orientation + acceleration + angular velocity.
            val mode = resolveOrientationWithAccGyrMode(payloadCls)
            device.javaClass.methods.firstOrNull {
                it.name == "setMeasurementMode" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(device, mode)
            device.javaClass.methods.firstOrNull {
                it.name == "setOutputRate" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(device, 60)
            onStatus("DOT: mode=$mode (orientation + acc/gyr), rate=60Hz")
        }
    }

    private fun resolveOrientationWithAccGyrMode(payloadCls: Class<*>): Int {
        // Per SDK appendix:
        // CUSTOM_MODE_5 = quat + acceleration + angular velocity
        // CUSTOM_MODE_4 = quat + dq + dv + angular velocity + acceleration + mag + status
        // COMPLETE_QUATERNION = quat + free acceleration (no angular velocity)
        val tryFields = listOf(
            "PAYLOAD_TYPE_CUSTOM_MODE_5",
            "PAYLOAD_TYPE_CUSTOM_MODE_4",
            "PAYLOAD_TYPE_HIGH_FIDELITY_WITH_MAG"
        )
        for (name in tryFields) {
            val value = runCatching { payloadCls.getField(name).getInt(null) }.getOrNull()
            if (value != null) return value
        }
        return payloadCls.getField("PAYLOAD_TYPE_COMPLETE_QUATERNION").getInt(null)
    }

    private fun convertQuatToEuler(quat: FloatArray): String {
        return runCatching {
            val parserCls = Class.forName("com.xsens.dot.android.sdk.utils.DotParser")
            val m = parserCls.methods.firstOrNull {
                it.name == "quaternion2Euler" && it.parameterTypes.size == 1
            } ?: return ""
            val euler = m.invoke(null, quat) as? DoubleArray ?: return ""
            euler.joinToString("|")
        }.getOrDefault("")
    }

    private fun callStaticNoArg(cls: Class<*>, method: String, arg: Boolean) {
        cls.methods.firstOrNull { m ->
            m.name == method && m.parameterTypes.size == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.invoke(null, arg)
    }

    private fun callNoArg(target: Any, method: String) {
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }?.invoke(target)
    }

    private fun callTry(target: Any, method: String): Boolean {
        return runCatching {
            callNoArg(target, method)
            true
        }.getOrDefault(false)
    }

    private fun callMaybe(target: Any, method: String): Any? {
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }?.invoke(target)
        }.getOrNull()
    }

    private fun joinValues(v: Any?): String {
        return when (v) {
            is FloatArray -> v.joinToString("|")
            is DoubleArray -> v.joinToString("|")
            is IntArray -> v.joinToString("|")
            is Array<*> -> v.joinToString("|")
            else -> ""
        }
    }
}

private fun hasBleRuntimePermissions(ctx: Context): Boolean {
    val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

private class DualWsServer(
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onBinary: (String, ByteArray) -> Unit,
    private val onBitmap: (String, Bitmap) -> Unit,
) {
    private var engine: ApplicationEngine? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<DefaultWebSocketServerSession>())

    fun start(port: Int, leftPath: String, rightPath: String) {
        stop()
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                webSocket(leftPath) { handleSocket("L") }
                webSocket(rightPath) { handleSocket("R") }
            }
        }.start(wait = false)
        onStatus("WS listening on $port$leftPath & $rightPath")
    }

    private suspend fun DefaultWebSocketServerSession.handleSocket(label: String) {
        onStatus("$label connected")
        clients.add(this)
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        scope.launch {
                            onBinary(label, bytes)
                            val bmp = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            bmp?.let { onBitmap(label, it) }
                        }
                    }
                    is Frame.Text -> onStatus("$label text: ${frame.readText()}")
                    else -> {}
                }
            }
        } catch (e: Exception) {
            onStatus("$label socket error: ${e.message}")
        } finally {
            clients.remove(this)
            onStatus("$label disconnected")
        }
    }

    fun sendText(msg: String) {
        scope.launch {
            val dead = mutableListOf<DefaultWebSocketServerSession>()
            clients.forEach { c ->
                try { c.send(Frame.Text(msg)) } catch (_: Exception) { dead.add(c) }
            }
            clients.removeAll(dead.toSet())
        }
    }

    fun stop() {
        engine?.stop()
        engine = null
        clients.clear()
        onStatus("WS stopped")
    }
}

private data class SpeechStep(val text: String, val delayMs: Long)

private class JsonTtsPlayer(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onReady: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var ready = false

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(false) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { waiters.remove(it)?.complete(false) }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val lang = chooseBestLanguage()
            ready = lang != null
            onReady(ready)
            if (ready) {
                onStatus("TTS ready: ${lang!!.toLanguageTag()} (本機引擎)")
            } else {
                onStatus("TTS 語言不支持，請安裝中文語音引擎(如訊飛TTS)")
            }
        } else {
            ready = false
            onReady(false)
            onStatus("TTS 初始化失敗")
        }
    }

    private fun chooseBestLanguage(): Locale? {
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINESE,
            Locale.TAIWAN,
            Locale("zh", "HK"),
            Locale.US
        )
        for (loc in candidates) {
            val ret = tts.setLanguage(loc)
            if (ret != TextToSpeech.LANG_MISSING_DATA && ret != TextToSpeech.LANG_NOT_SUPPORTED) {
                return loc
            }
        }
        return null
    }

    suspend fun playScript(steps: List<SpeechStep>) {
        if (!ready) {
            onStatus("TTS 未就緒")
            return
        }
        for (step in steps) {
            if (step.text.isBlank()) continue
            speakAndWait(step.text.trim())
            if (step.delayMs > 0) delay(step.delayMs)
        }
    }

    fun stop() {
        tts.stop()
        waiters.forEach { (_, d) -> d.complete(false) }
        waiters.clear()
    }

    fun release() {
        stop()
        tts.shutdown()
    }

    private suspend fun speakAndWait(text: String) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        waiters[id] = done
        val ret = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (ret == TextToSpeech.ERROR) {
            waiters.remove(id)
            onStatus("TTS 播報失敗")
            return
        }
        withTimeoutOrNull(30_000) { done.await() }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "cannot open input stream" }
        return input.bufferedReader().readText()
    }
}

private fun parseSpeechSteps(json: String): List<SpeechStep> {
    val source = json.trim()
    val arr = when {
        source.startsWith("[") -> JSONArray(source)
        source.startsWith("{") -> {
            val obj = JSONObject(source)
            when {
                obj.has("items") -> obj.getJSONArray("items")
                obj.has("steps") -> obj.getJSONArray("steps")
                else -> JSONArray().put(obj)
            }
        }
        else -> JSONArray()
    }
    val out = mutableListOf<SpeechStep>()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val text = item.optString("text", "").trim()
        val delayMs = item.optLong("delay", 0L).coerceAtLeast(0L)
        if (text.isNotBlank()) out += SpeechStep(text, delayMs)
    }
    return out
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    var wsPort by remember { mutableStateOf("9002") }
    val leftPath = "/ws/cameraL"
    val rightPath = "/ws/cameraR"
    var status by remember { mutableStateOf("Idle") }
    var recording by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportingZip by remember { mutableStateOf(false) }
    var sessionDir by remember { mutableStateOf<String?>(null) }
    var countL by remember { mutableStateOf(0) }
    var countR by remember { mutableStateOf(0) }
    var dotStatus by remember { mutableStateOf("DOT: idle") }
    var dotSampleCount by remember { mutableStateOf(0) }
    var dotDeviceCount by remember { mutableStateOf(0) }
    var dotTargetCount by remember { mutableStateOf("2") }
    var ttsReady by remember { mutableStateOf(false) }
    var scriptName by remember { mutableStateOf("未導入") }
    var speechSteps by remember { mutableStateOf<List<SpeechStep>>(emptyList()) }
    var speechJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingZipSessionDir by remember { mutableStateOf<String?>(null) }
    var bmpL by remember { mutableStateOf<Bitmap?>(null) }
    var bmpR by remember { mutableStateOf<Bitmap?>(null) }
    val lastPreviewMs = remember { mutableStateMapOf("L" to 0L, "R" to 0L) }
    val dotController = remember {
        MovellaDotController(
            activity = activity,
            scope = scope,
            onStatus = { dotStatus = it },
            onSample = { dotSampleCount++ },
            onDeviceCount = { dotDeviceCount = it }
        )
    }
    val ttsPlayer = remember {
        JsonTtsPlayer(
            context = activity,
            onStatus = { status = it },
            onReady = { ttsReady = it }
        )
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val raw = readTextFromUri(activity, uri)
            val parsed = parseSpeechSteps(raw)
            speechSteps = parsed
            scriptName = uri.lastPathSegment ?: "script.json"
            status = "JSON 導入成功: ${parsed.size} 條"
        }.onFailure {
            status = "JSON 導入失敗: ${it.message}"
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val dir = pendingZipSessionDir
        if (uri == null || dir == null) return@rememberLauncherForActivityResult
        exportingZip = true
        scope.launch(Dispatchers.IO) {
            val leftDir = java.io.File(dir, "L").absolutePath
            val rightDir = java.io.File(dir, "R").absolutePath
            val okL = exportMp4FromJpegs(leftDir, activity, "output_L.mp4")
            val okR = exportMp4FromJpegs(rightDir, activity, "output_R.mp4")
            val zipOk = exportSessionToZip(activity, java.io.File(dir), uri)
            withContext(Dispatchers.Main) {
                exportingZip = false
                status = "ZIP導出: mp4(L:$okL R:$okR), zip=$zipOk"
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            speechJob?.cancel()
            ttsPlayer.release()
            dotController.shutdown()
        }
    }

    val wsServer = remember {
        DualWsServer(
            scope = scope,
            onStatus = { status = it },
            onBinary = { label, bytes ->
                if (recording) {
                    val dir = sessionDir
                    if (dir != null) {
                        val subDir = java.io.File(dir, label)
                        subDir.mkdirs()
                        val tsNs = SystemClock.elapsedRealtimeNanos()
                        val file = java.io.File(subDir, "${label}_${tsNs}.jpg")
                        scope.launch(Dispatchers.IO) {
                            try {
                                file.writeBytes(bytes)
                                withContext(Dispatchers.Main) {
                                    if (label == "L") countL++ else countR++
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    status = "寫檔失敗: ${e.message}"
                                }
                            }
                        }
                    }
                }
            },
            onBitmap = { label, bmp ->
                val now = System.currentTimeMillis()
                val last = lastPreviewMs[label] ?: 0L
                if (now - last >= 150) {
                    lastPreviewMs[label] = now
                    if (label == "L") bmpL = bmp else bmpR = bmp
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("雙路 WS 錄製/導出", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = wsPort, onValueChange = { wsPort = it },
            label = { Text("WS Port") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { wsServer.start(wsPort.toIntOrNull() ?: 9002, leftPath, rightPath) }) { Text("啟動 WS") }
            Button(onClick = { wsServer.stop() }) { Text("停止 WS") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { jsonPicker.launch(arrayOf("application/json", "text/plain")) }) { Text("導入測試 JSON") }
            Text("TTS: ${if (ttsReady) "ready" else "not ready"}")
        }
        Text("腳本: $scriptName (${speechSteps.size} 條)")

        OutlinedTextField(
            value = dotTargetCount,
            onValueChange = { dotTargetCount = it.filter { ch -> ch.isDigit() } },
            label = { Text("DOT 裝置數(1-8)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val target = dotTargetCount.toIntOrNull() ?: 2
                dotController.setMaxDevices(target)
                dotController.start()
            }) { Text("連接 DOT") }
            Button(onClick = { dotController.shutdown() }) { Text("斷開 DOT") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (!recording) {
                    val extDir = activity.getExternalFilesDir("recordings")
                    val dir = java.io.File(extDir, "rec_${System.currentTimeMillis()}")
                    dir.mkdirs()
                    sessionDir = dir.absolutePath
                    countL = 0; countR = 0
                    dotSampleCount = 0
                    recording = true
                    status = "錄製中… ${dir.absolutePath}"
                    dotController.startRecording(File(dir, "sensors"))
                    wsServer.sendText("START")
                    speechJob?.cancel()
                    if (speechSteps.isNotEmpty()) {
                        speechJob = scope.launch {
                            ttsPlayer.playScript(speechSteps)
                        }
                    }
                }
            }) { Text(if (recording) "錄製中" else "開始錄製") }

            Button(onClick = {
                if (recording) {
                    recording = false
                    wsServer.sendText("STOP")
                    dotController.stopRecording()
                    speechJob?.cancel()
                    ttsPlayer.stop()
                    status = "錄製結束，L:$countL R:$countR，路徑: $sessionDir"
                }
            }) { Text("停止並保存") }
        }

        Button(
            enabled = !exporting && sessionDir != null && (countL + countR) > 0,
            onClick = {
                val dir = sessionDir
                if (dir != null) {
                    exporting = true
                    scope.launch(Dispatchers.IO) {
                        val okL = exportMp4FromJpegs(java.io.File(dir, "L").absolutePath, activity, "output_L.mp4")
                        val okR = exportMp4FromJpegs(java.io.File(dir, "R").absolutePath, activity, "output_R.mp4")
                        exporting = false
                        status = "導出完成 L:${okL} R:${okR}"
                    }
                }
            }
        ) { Text(if (exporting) "導出中..." else "導出 L/R MP4") }

        Button(
            enabled = !exportingZip && sessionDir != null,
            onClick = {
                val dir = sessionDir ?: return@Button
                pendingZipSessionDir = dir
                val name = "eva_${java.io.File(dir).name}.zip"
                zipPicker.launch(name)
            }
        ) { Text(if (exportingZip) "ZIP導出中..." else "導出 ZIP(含L/R MP4+傳感器)") }

        Text("狀態：$status")
        Text("傳感器：$dotStatus")
        Text("DOT 已連接：$dotDeviceCount, 樣本數：$dotSampleCount")
        Text("L 幀數：$countL    R 幀數：$countR")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StreamPreview("L", bmpL)
            StreamPreview("R", bmpR)
        }
    }
}

@Composable
private fun RowScope.StreamPreview(label: String, bmp: Bitmap?) {
    Column(modifier = Modifier.weight(1f)) {
        Text("$label 預覽")
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "$label camera",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        } else {
            Text("無影像")
        }
    }
}

// --------- MP4 導出 ---------
private fun bitmapToI420(src: Bitmap): ByteArray {
    val width = if (src.width % 2 == 0) src.width else src.width - 1
    val height = if (src.height % 2 == 0) src.height else src.height - 1
    val argb = IntArray(width * height)
    src.copy(Bitmap.Config.ARGB_8888, false).getPixels(argb, 0, width, 0, 0, width, height)
    val ySize = width * height
    val uvSize = ySize / 4
    val yuv = ByteArray(ySize + uvSize * 2)
    var yIdx = 0
    var uIdx = ySize
    var vIdx = ySize + uvSize
    for (j in 0 until height) {
        for (i in 0 until width) {
            val c = argb[j * width + i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
            yuv[yIdx++] = y.coerceIn(0, 255).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                yuv[uIdx++] = u.coerceIn(0, 255).toByte()
                yuv[vIdx++] = v.coerceIn(0, 255).toByte()
            }
        }
    }
    return yuv
}

private fun exportMp4FromJpegs(dirPath: String, ctx: ComponentActivity, fileName: String = "output.mp4", fps: Int = 15): Boolean {
    return runCatching {
        val dir = java.io.File(dirPath)
        val frames = dir.listFiles { f -> f.extension.lowercase() in listOf("jpg", "jpeg") }
            ?.mapNotNull { f ->
                val ts = extractTimestampFromName(f.name) ?: return@mapNotNull null
                ts to f
            }
            ?.sortedBy { it.first } ?: return false
        if (frames.isEmpty()) return false

        val firstBmp = BitmapFactory.decodeFile(frames.first().second.absolutePath) ?: return false
        val width = firstBmp.width
        val height = firstBmp.height
        firstBmp.recycle()

        val outFile = java.io.File(dir.parentFile ?: dir, fileName)
        if (outFile.exists()) outFile.delete()

        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createVideoFormat("video/avc", width, height)
        ) ?: return false

        val codec = MediaCodec.createByCodecName(codecName)
        val caps = codec.codecInfo.getCapabilitiesForType("video/avc")
        val candidates = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        val chosen = candidates.firstOrNull { caps.colorFormats.contains(it) }
            ?: caps.colorFormats.firstOrNull()
            ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, chosen)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var presentationTimeUs = 0L
        val frameDurationUs = 1_000_000L / fps
        val info = MediaCodec.BufferInfo()
        var started = false

        fun drain(end: Boolean) {
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (started) throw RuntimeException("format changed twice")
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start(); started = true
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0 && started) {
                            val outBuf = codec.getOutputBuffer(outIdx) ?: return
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        val startNs = frames.first().first
        val endNs = frames.last().first
        var srcIdx = 0
        var currentYuv: ByteArray? = null
        var currentFile: java.io.File? = null
        val totalOutFrames = (((endNs - startNs) / (frameDurationUs * 1000L)).toInt() + 1).coerceAtLeast(1)

        repeat(totalOutFrames) { outIdx ->
            val targetNs = startNs + outIdx * frameDurationUs * 1000L
            while (srcIdx + 1 < frames.size && frames[srcIdx + 1].first <= targetNs) {
                srcIdx++
            }
            val srcFile = frames[srcIdx].second
            if (currentFile?.absolutePath != srcFile.absolutePath) {
                val bmp = BitmapFactory.decodeFile(srcFile.absolutePath)
                if (bmp != null) {
                    currentYuv = bitmapToI420(bmp)
                    bmp.recycle()
                    currentFile = srcFile
                }
            }
            val yuv = currentYuv ?: return@repeat
            val inIdx = codec.dequeueInputBuffer(5000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)!!
                inBuf.clear()
                inBuf.put(yuv)
                codec.queueInputBuffer(inIdx, 0, yuv.size, presentationTimeUs, 0)
                presentationTimeUs += frameDurationUs
            }
            drain(false)
        }
        val eosIdx = codec.dequeueInputBuffer(5000)
        if (eosIdx >= 0) {
            codec.queueInputBuffer(eosIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)

        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        MediaScannerConnection.scanFile(ctx, arrayOf(outFile.absolutePath), arrayOf("video/mp4"), null)
        true
    }.getOrElse { false }
}

private fun extractTimestampFromName(name: String): Long? {
    val base = name.substringBeforeLast('.')
    val token = base.substringAfterLast('_')
    return token.toLongOrNull()
}

private fun exportSessionToZip(ctx: Context, sessionDir: File, targetUri: Uri): Boolean {
    return runCatching {
        val out = ctx.contentResolver.openOutputStream(targetUri) ?: return false
        ZipOutputStream(out.buffered()).use { zos ->
            val mp4L = File(sessionDir, "output_L.mp4")
            val mp4R = File(sessionDir, "output_R.mp4")
            addFileToZip(zos, mp4L, "output_L.mp4")
            addFileToZip(zos, mp4R, "output_R.mp4")

            val sensorDir = File(sessionDir, "sensors")
            if (sensorDir.exists()) {
                sensorDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() == "csv" }
                    ?.sortedBy { it.name }
                    ?.forEach { addFileToZip(zos, it, "sensors/${it.name}") }
            }
        }
        true
    }.getOrElse { false }
}

private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
    if (!file.exists() || !file.isFile) return
    val entry = ZipEntry(entryName).apply { time = file.lastModified() }
    zos.putNextEntry(entry)
    FileInputStream(file).use { it.copyTo(zos, 8 * 1024) }
    zos.closeEntry()
}
