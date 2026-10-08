package dev.bakrlabs.flux

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Screen { Home, Send, Receive, Progress }

class AppEntry(val label: String, val packageName: String, val apks: List<File>) {
    val fileName: String
        get() = label.replace(Regex("""[\\/:*?"<>|]"""), "_") + if (apks.size == 1) ".apk" else ".apks"
}

class Picked(val name: String, val uri: Uri? = null, val app: AppEntry? = null)

private class Source(val pfd: ParcelFileDescriptor, val name: String, val size: Long)

class Received(val name: String, val uri: Uri, val mime: String)

class Item(val name: String) {
    var status by mutableStateOf("Queued")
}

class FluxState(private val context: Context, private val scope: CoroutineScope) {
    var screen by mutableStateOf(Screen.Home)
    var target by mutableStateOf("")
    var addresses by mutableStateOf<List<String>>(emptyList())
    var speed by mutableStateOf("")
    var eta by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var sending by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var incomingName by mutableStateOf("")
    var incomingFraction by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)
    val picked = mutableStateListOf<Picked>()
    val transfers = mutableStateListOf<Item>()
    val recent = mutableStateListOf<String>()
    val devices = mutableStateListOf<Device>()
    val received = mutableStateListOf<Received>()
    val apps = mutableStateListOf<AppEntry>()
    var tab by mutableStateOf(0)
    private var receiving: Job? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var currentFile = 0

    fun addPicked(uris: List<Uri>) {
        uris.forEach { picked.add(Picked(displayName(it), uri = it)) }
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
        eta = ""
        error = null
        progress = 0f
        paused = false
        sending = true
        cancelRequested = false
        currentFile = 0
        FluxCore.reset(0)
        screen = Screen.Progress
        scope.launch(Dispatchers.IO) {
            val ticker = launch { trackSend(files.size) }
            try {
                files.forEachIndexed { index, file ->
                    val item = transfers[index]
                    if (cancelRequested) {
                        item.status = "Cancelled"
                        return@forEachIndexed
                    }
                    currentFile = index
                    item.status = "Preparing"
                    val scratch = mutableListOf<File>()
                    try {
                        val source = prepare(file, scratch)
                        item.status = "Sending"
                        val started = System.nanoTime()
                        val bytes = FluxCore.sendFd(addr, source.name, source.size, source.pfd.detachFd())
                        val seconds = (System.nanoTime() - started) / 1e9
                        speed = "%.1f MB/s".format(bytes / seconds / 1e6)
                        item.status = "Done"
                        recent.add(0, "Sent ${file.name}")
                    } catch (e: Exception) {
                        if (cancelRequested) {
                            item.status = "Cancelled"
                        } else {
                            item.status = "Failed"
                            error = e.message
                        }
                    } finally {
                        scratch.forEach { it.delete() }
                    }
                }
            } finally {
                ticker.cancel()
                sending = false
                paused = false
                eta = ""
                if (transfers.isNotEmpty() && transfers.all { it.status == "Done" }) progress = 1f
            }
        }
    }

    private fun prepare(file: Picked, scratch: MutableList<File>): Source {
        file.app?.let { app ->
            val apk = if (app.apks.size == 1) app.apks[0] else bundle(app).also { scratch.add(it) }
            return Source(ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY), file.name, apk.length())
        }
        val uri = file.uri!!
        val direct = context.contentResolver.openFileDescriptor(uri, "r")
        if (direct != null && direct.statSize >= 0) {
            return Source(direct, file.name, direct.statSize)
        }
        direct?.close()
        val local = File(context.cacheDir, file.name).also { scratch.add(it) }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            local.outputStream().use { input.copyTo(it) }
        }
        return Source(ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY), file.name, local.length())
    }

    private fun bundle(app: AppEntry): File {
        val out = File(context.cacheDir, app.fileName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            app.apks.forEach { apk ->
                zip.putNextEntry(ZipEntry(apk.name))
                apk.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    suspend fun loadApps() {
        val found = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter {
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
                .map { info ->
                    val parts = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
                    AppEntry(info.loadLabel(pm).toString(), info.packageName, parts.map { File(it) })
                }
                .sortedBy { it.label.lowercase() }
        }
        apps.clear()
        apps.addAll(found)
    }

    fun isSelected(app: AppEntry) = picked.any { it.app?.packageName == app.packageName }

    fun toggleApp(app: AppEntry) {
        val index = picked.indexOfFirst { it.app?.packageName == app.packageName }
        if (index >= 0) picked.removeAt(index) else picked.add(Picked(app.fileName, app = app))
    }

    private suspend fun trackSend(count: Int) {
        var lastDone = 0L
        var lastTime = System.nanoTime()
        var smooth = 0.0
        while (true) {
            delay(200)
            val done = FluxCore.progressDone(0)
            val total = FluxCore.progressTotal(0)
            val now = System.nanoTime()
            val seconds = (now - lastTime) / 1e9
            val delta = done - lastDone
            lastDone = done
            lastTime = now
            val active = transfers.getOrNull(currentFile)?.status == "Sending"
            val within = if (active && total > 0) done.toFloat() / total else 0f
            progress = ((currentFile + within) / count).coerceIn(0f, 1f)
            if (paused) {
                speed = "Paused"
                eta = ""
            } else if (active && delta > 0) {
                val instant = delta / seconds
                smooth = if (smooth == 0.0) instant else smooth * 0.7 + instant * 0.3
                speed = "%.1f MB/s".format(smooth / 1e6)
                eta = clock(((total - done) / smooth).toLong())
            }
        }
    }

    private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

    fun togglePause() {
        paused = !paused
        FluxCore.setPaused(0, paused)
    }

    fun cancelSend() {
        cancelRequested = true
        FluxCore.cancel(0)
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
                    } catch (e: IOException) {
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

    private fun publish(source: File): Received {
        val extension = source.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Flux")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create the file in Downloads")
        resolver.openOutputStream(uri)!!.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        source.delete()
        return Received(source.name, uri, mime)
    }

    private fun installBundle(item: Received) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            error = "Allow installs from Flux, then tap Install again"
            return
        }
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        try {
            installer.openSession(id).use { session ->
                var parts = 0
                ZipInputStream(context.contentResolver.openInputStream(item.uri)!!.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.name.endsWith(".apk", ignoreCase = true)) continue
                        session.openWrite(entry.name.substringAfterLast('/'), 0, -1).use { out ->
                            zip.copyTo(out)
                            session.fsync(out)
                        }
                        parts++
                    }
                }
                if (parts == 0) throw IOException("no APK files inside ${item.name}")
                val callback = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            error = e.message
        }
    }

    fun open(item: Received) {
        if (item.name.endsWith(".apks", ignoreCase = true)) {
            scope.launch(Dispatchers.IO) { installBundle(item) }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            error = "No app can open ${item.name}"
        }
    }

    fun startReceiving() {
        screen = Screen.Receive
        addresses = emptyList()
        error = null
        incomingName = ""
        val previous = receiving
        previous?.cancel()
        FluxCore.cancel(1)
        receiving = scope.launch(Dispatchers.IO) {
            previous?.join()
            FluxCore.reset(1)
            val port = try {
                FluxCore.bindReceiver()
            } catch (e: Exception) {
                error = e.message
                return@launch
            }
            val ips = lanAddresses().ifEmpty { listOf(FluxCore.localIp()).filter { it.isNotEmpty() } }
            addresses = ips.map { "$it:$port" }.ifEmpty { listOf("No network, port $port") }
            launch { announce(Build.MODEL, port) }
            launch { trackReceive() }
            val dir = File(context.cacheDir, "incoming").apply { mkdirs() }.path
            while (isActive) {
                try {
                    val saved = File(FluxCore.receiveOne(dir))
                    val item = publish(saved)
                    received.add(0, item)
                    recent.add(0, "Received ${item.name}")
                } catch (e: Exception) {
                    if (isActive) {
                        error = e.message
                        delay(500)
                    }
                }
            }
        }
    }

    private suspend fun trackReceive() {
        while (true) {
            delay(200)
            val total = FluxCore.progressTotal(1)
            val done = FluxCore.progressDone(1)
            if (total > 0 && done < total) {
                incomingName = FluxCore.progressName(1)
                incomingFraction = done.toFloat() / total
            } else {
                incomingName = ""
            }
        }
    }

    fun stopReceiving() {
        receiving?.cancel()
        receiving = null
        FluxCore.cancel(1)
        incomingName = ""
        screen = Screen.Home
    }
}
