package dev.bakrlabs.flux

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Send, Receive, Progress }

class Picked(val uri: Uri, val name: String)

class Item(val name: String) {
    var status by mutableStateOf("Queued")
}

class FluxState(private val context: Context, private val scope: CoroutineScope) {
    var screen by mutableStateOf(Screen.Home)
    var target by mutableStateOf("")
    var addresses by mutableStateOf<List<String>>(emptyList())
    var speed by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val picked = mutableStateListOf<Picked>()
    val transfers = mutableStateListOf<Item>()
    val recent = mutableStateListOf<String>()
    val devices = mutableStateListOf<Device>()
    private var receiving: Job? = null

    fun addPicked(uris: List<Uri>) {
        uris.forEach { picked.add(Picked(it, displayName(it))) }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "file"
    }

    fun send() {
        val files = picked.toList()
        val addr = target.trim()
        picked.clear()
        transfers.clear()
        files.forEach { transfers.add(Item(it.name)) }
        speed = ""
        error = null
        screen = Screen.Progress
        scope.launch(Dispatchers.IO) {
            files.forEachIndexed { index, file ->
                val item = transfers[index]
                item.status = "Sending"
                try {
                    val local = File(context.cacheDir, file.name)
                    context.contentResolver.openInputStream(file.uri)!!.use { input ->
                        local.outputStream().use { input.copyTo(it) }
                    }
                    val started = System.nanoTime()
                    val bytes = FluxCore.sendFile(addr, local.path)
                    val seconds = (System.nanoTime() - started) / 1e9
                    speed = "%.1f MB/s".format(bytes / seconds / 1e6)
                    item.status = "Done"
                    recent.add(0, "Sent ${file.name}")
                    local.delete()
                } catch (e: Exception) {
                    item.status = "Failed"
                    error = e.message
                }
            }
        }
    }

    suspend fun discover() {
        devices.clear()
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("flux")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            coroutineScope {
                launch {
                    try {
                        listen { found -> withContext(Dispatchers.Main) { upsert(found) } }
                    } catch (e: java.io.IOException) {
                        error = e.message
                    }
                }
                while (isActive) {
                    delay(1000)
                    devices.removeAll { System.currentTimeMillis() - it.seen > 4000 }
                }
            }
        } finally {
            lock?.release()
        }
    }

    private fun upsert(found: Device) {
        val index = devices.indexOfFirst { it.host == found.host && it.port == found.port }
        if (index >= 0) devices[index] = found else devices.add(found)
    }

    fun startReceiving() {
        screen = Screen.Receive
        addresses = emptyList()
        error = null
        receiving?.cancel()
        receiving = scope.launch(Dispatchers.IO) {
            val port = try {
                FluxCore.bindReceiver()
            } catch (e: Exception) {
                error = e.message
                return@launch
            }
            val ips = lanAddresses().ifEmpty { listOf(FluxCore.localIp()).filter { it.isNotEmpty() } }
            addresses = ips.map { "$it:$port" }.ifEmpty { listOf("No network, port $port") }
            launch { announce(Build.MODEL, port) }
            val dir = (context.getExternalFilesDir("Flux") ?: context.filesDir).path
            while (isActive) {
                try {
                    val saved = FluxCore.receiveOne(dir)
                    recent.add(0, "Received ${File(saved).name}")
                } catch (e: Exception) {
                    error = e.message
                    delay(500)
                }
            }
        }
    }

    fun stopReceiving() {
        receiving?.cancel()
        receiving = null
        screen = Screen.Home
    }
}
