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
        // 微信助理（1.5.0 起手机也能接）：配对过电脑就用电脑的同一个绑定，电脑在接时手机待命，见 WeixinHandover
        val weixin = com.guixing.jixunying.engine.WeixinBridge(engine, File(filesDir, "data/weixin"))
        engine.weixin = weixin
        com.guixing.jixunying.engine.WeixinHandover(hub, engine, weixin).start()
        weixin.start()
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "手机" }
}

class MainActivity : ComponentActivity() {
    private var pickResult: CompletableDeferred<List<Uri>>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private var scanResult: CompletableDeferred<String?>? = null
    private var folderResult: CompletableDeferred<Uri?>? = null

    private val pickAny = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { pickResult?.complete(it) }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveResult?.complete(it) }
    private val scan = registerForActivityResult(ScanContract()) { scanResult?.complete(it.contents) }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderResult?.complete(it) }

    /** 别的 App 发来的文件（微信里点文件 → 用其他应用打开，或者分享）。 */
    private val incoming = kotlinx.coroutines.flow.MutableStateFlow<List<PickedFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val hub = (application as JxyApp).hub
        setContent { App(hub, platform) }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置给了「所有文件访问」权限回来：马上扫一遍文档
        (application as JxyApp).hub.local.let { local ->
            if (local is com.guixing.jixunying.engine.Engine && local.state.docs.needPermission && !com.guixing.jixunying.engine.docAccessMissing()) local.rescanDocs()
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        val sharedText = if (intent.action == Intent.ACTION_SEND && uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (uris.isEmpty() && sharedText.isNullOrBlank()) return
        Thread {
            val files = uris.mapNotNull(::readUri) +
                listOfNotNull(sharedText?.let { PickedFile("分享的文字.txt", "text/plain", it.toByteArray()) })
            if (files.isNotEmpty()) incoming.value = files
        }.start()
    }

    private fun readUri(uri: Uri): PickedFile? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        PickedFile(name, mime, bytes)
    }.getOrNull()

    private val platform = object : Platform {
        override val isDesktop = false
        override val deviceName: String get() = (application as JxyApp).deviceName()

        override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> {
            val d = CompletableDeferred<List<Uri>>()
            pickResult = d
            pickAny.launch(if (imagesOnly) "image/*" else "*/*")
            val uris = d.await()
            return withContext(Dispatchers.IO) { uris.mapNotNull(::readUri) }
        }

        override val incomingFiles get() = incoming
        override fun clearIncoming() { incoming.value = emptyList() }

        override fun openFile(path: String): Boolean = runCatching {
            val f = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        }.getOrDefault(false)

        override suspend fun openBytes(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                // 存在应用自己的目录里（FileProvider 的 files-path 覆盖得到），再交给 WPS / Office 打开
                val dir = File(filesDir, "open").apply { mkdirs() }
                val f = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                f.writeBytes(bytes)
                f.absolutePath
            }.getOrNull()
        }?.let { withContext(Dispatchers.Main) { openFile(it) } } == true

        override fun requestFileAccess() {
            if (Build.VERSION.SDK_INT < 30) return
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }

        /** 系统文件夹选择器返回的是 content:// 地址，换成文件路径（主存储是 /storage/emulated/0）。 */
        override suspend fun pickFolder(): String? {
            val d = CompletableDeferred<Uri?>()
            folderResult = d
            pickTree.launch(null)
            val uri = d.await() ?: return null
            val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
            val vol = docId.substringBefore(':')
            val rel = docId.substringAfter(':', "")
            @Suppress("DEPRECATION")
            val base = if (vol == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
            return if (rel.isEmpty()) base else "$base/$rel"
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
                setOrientationLocked(true)
            })
            return d.await()
        }

        @androidx.compose.runtime.Composable
        override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

        private val prefs get() = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        override fun getPref(key: String): String? = prefs.getString(key, null)
        override fun setPref(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}
