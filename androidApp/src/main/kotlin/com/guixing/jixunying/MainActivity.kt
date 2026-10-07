package com.guixing.jixunying

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.AndroidEnv
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.PairedHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import com.guixing.jixunying.ui.App
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机端：本机自带完整引擎（不连电脑也能用，模型直接从手机调用，数据存在手机里）；
 * 配对过电脑的话，还能遥控电脑（走加密中转，隔多远都行）。
 */
class JxyApp : Application() {
    lateinit var hub: Hub
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidEnv.context = this
        val prefs = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        val engine = Engine(Storage(File(filesDir, "data")), isPhone = true)
        hub = Hub(
            local = engine,
            linkFactory = { RelayLink(it) },
            pairer = { code, name -> pairWithHost(code, name) },
            deviceName = deviceName(),
            persist = { host ->
                prefs.edit().apply {
                    if (host == null) remove("paired_host") else putString("paired_host", AppJson.encodeToString(PairedHost.serializer(), host))
                }.apply()
            },
        )
        prefs.getString("paired_host", null)
            ?.let { runCatching { AppJson.decodeFromString(PairedHost.serializer(), it) }.getOrNull() }
            ?.let { hub.attach(it) }
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "手机" }
}

class MainActivity : ComponentActivity() {
    private var pickResult: CompletableDeferred<List<Uri>>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private var scanResult: CompletableDeferred<String?>? = null

    private val pickAny = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { pickResult?.complete(it) }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveResult?.complete(it) }
    private val scan = registerForActivityResult(ScanContract()) { scanResult?.complete(it.contents) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val hub = (application as JxyApp).hub
        setContent { App(hub, platform) }
    }

    private val platform = object : Platform {
        override val isDesktop = false
        override val deviceName: String get() = (application as JxyApp).deviceName()

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

        override suspend fun scanQr(): String? {
            val d = CompletableDeferred<String?>()
            scanResult = d
            scan.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("扫描电脑上 AI集训营「手机联机」页的二维码")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
            return d.await()
        }

        private val prefs get() = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        override fun getPref(key: String): String? = prefs.getString(key, null)
        override fun setPref(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}
