package com.guixing.jixunying

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.DISCOVERY_PING
import com.guixing.jixunying.model.DISCOVERY_PONG
import com.guixing.jixunying.model.DISCOVERY_PORT
import com.guixing.jixunying.ui.App
import com.guixing.jixunying.ui.FoundHost
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class JxyApp : Application() {
    val backend by lazy { RemoteBackend() }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        val host = prefs.getString("host", null)
        val token = prefs.getString("token", null)
        if (host != null && token != null) backend.connect(host, token)
    }
}

class MainActivity : ComponentActivity() {
    private var pickResult: CompletableDeferred<List<Uri>>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null

    private val pickAny = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { pickResult?.complete(it) }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveResult?.complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val backend = (application as JxyApp).backend
        setContent { App(backend, platform) }
    }

    private val platform = object : Platform {
        override val isDesktop = false
        override val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "手机" }

        override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> {
            val d = CompletableDeferred<List<Uri>>()
            pickResult = d
            pickAny.launch(if (imagesOnly) "image/*" else "*/*")
            val uris = d.await()
            return withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment ?: "file"
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                        PickedFile(name, mime, bytes)
                    }.getOrNull()
                }
            }
        }

        override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()

        override suspend fun saveFile(name: String, bytes: ByteArray): Boolean {
            val d = CompletableDeferred<Uri?>()
            saveResult = d
            saveDoc.launch(name)
            val uri = d.await() ?: return false
            return withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
        }

        override fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        override suspend fun discoverHosts(): List<FoundHost> = withContext(Dispatchers.IO) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("jxy-discover").apply { setReferenceCounted(false); acquire() }
            val found = LinkedHashMap<String, FoundHost>()
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 600
                    val ping = DISCOVERY_PING.toByteArray()
                    val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                    repeat(3) {
                        targets.forEach { addr -> runCatching { sock.send(DatagramPacket(ping, ping.size, addr, DISCOVERY_PORT)) } }
                        val until = System.currentTimeMillis() + 700
                        while (System.currentTimeMillis() < until) {
                            val buf = ByteArray(512)
                            val pkt = DatagramPacket(buf, buf.size)
                            try {
                                sock.receive(pkt)
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                            val parts = String(pkt.data, 0, pkt.length).split('|')
                            if (parts.size >= 3 && parts[0] == DISCOVERY_PONG) {
                                val addr = "${pkt.address.hostAddress}:${parts[2]}"
                                found[addr] = FoundHost(parts[1], addr)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                lock.release()
            }
            found.values.toList()
        }

        private fun broadcastAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())

        private val prefs get() = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        override fun getPref(key: String): String? = prefs.getString(key, null)
        override fun setPref(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}
