package com.guixing.jixunying

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 手机端：本机自带完整引擎（不连电脑也能用，模型直接从手机调用，数据存在手机里）；
 * 配对过电脑的话，还能遥控电脑（走加密中转，隔多远都行）。
 */
class JxyApp : Application() {
    lateinit var hub: Hub
        private set
    lateinit var engine: Engine
        private set
    lateinit var weixin: com.guixing.jixunying.engine.WeixinBridge
        private set

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    /** 现在有几个界面在前台（0 = 应用在后台）。 */
    private var visible = 0
    private var bgJob: kotlinx.coroutines.Job? = null

    /** 手机在接微信：绑定了、「这台手机接微信」开着。这时要开前台服务，不然系统会把应用关掉。 */
    fun phoneAnswersWeixin() = weixin.isBound && engine.state.settings.weixin.enabled

    /** 应用现在在不在前台（Code 栏只在不在前台时弹通知，第二期 2026-10-13）。 */
    val inForeground: Boolean get() = visible > 0

    fun weixinStatus(): String = engine.state.weixin.status

    /** 通知上点「关掉」：把「这台手机接微信」关掉（和设置里关一样）。 */
    fun turnOffPhoneWeixin() {
        appScope.launch { engine.call(com.guixing.jixunying.model.Command.SaveSettings(engine.state.settings.let { it.copy(weixin = it.weixin.copy(enabled = false)) })) }
    }

    /**
     * 手机后台省电（1.5.0）：
     * - 回到前台：中转接上，手机接微信的话开前台服务；
     * - 切到后台 30 秒（回个微信、切个应用不算）：手机没在接微信就断开中转、停心跳；在接微信就保持连着（前台服务已经开着）。
     */
    private fun watchLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                if (visible++ == 0) {
                    bgJob?.cancel()
                    hub.remote.value?.resume()
                    if (phoneAnswersWeixin()) KeepAliveService.start(this@JxyApp)
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (--visible == 0) {
                    bgJob?.cancel()
                    bgJob = appScope.launch {
                        kotlinx.coroutines.delay(30_000)
                        if (!phoneAnswersWeixin()) hub.remote.value?.pause()
                    }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // 开关、绑定变了：在前台时开 / 关前台服务；通知上的字跟着状态变
        appScope.launch {
            engine.store.state.collect { st ->
                val want = weixin.isBound && st.settings.weixin.enabled
                when {
                    !want -> KeepAliveService.stop(this@JxyApp)
                    visible > 0 && !KeepAliveService.running -> KeepAliveService.start(this@JxyApp)
                    else -> KeepAliveService.update(this@JxyApp, st.weixin.status)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AndroidEnv.context = this
        val prefs = getSharedPreferences("jxy", Context.MODE_PRIVATE)
        val engine = Engine(Storage(File(filesDir, "data")), isPhone = true)
        this.engine = engine
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
        this.weixin = weixin
        engine.weixin = weixin
        com.guixing.jixunying.engine.WeixinHandover(hub, engine, weixin, backupFile = File(filesDir, "data/weixin/backup-phone.txt")).start()
        // 长期记忆：连着电脑时两边互相同步（2026-10-24）
        com.guixing.jixunying.engine.MemorySyncer(hub, engine).start()
        // 账本、微信对话：连上电脑那一下、之后每 10 分钟、点「现在同步」时对一遍，别的应用大量上网时让路（2026-10-27）
        com.guixing.jixunying.engine.DeviceSyncer(hub, engine, "手机 " + deviceName(), File(filesDir, "data")).start()
        // GitHub：连着电脑就用电脑上登录的；手机自己登录时换令牌也交给电脑（2026-10-24）
        com.guixing.jixunying.engine.GitHubViaHost(hub, engine).start()
        com.guixing.jixunying.engine.HostModels(hub, engine).start()
        weixin.start()
        watchLifecycle()
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "手机" }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** 通知 / 托盘带过来的「要点开哪个 Code 会话」（第二期 2026-10-13）。 */
        const val EXTRA_CONV = "jxy.code.conv"
        /** Code 栏的通知渠道（Android 8 起必须先建渠道，通知才出得来）。 */
        private const val CHANNEL_CODE = "code"
    }

    /** 点了 Code 栏的通知要打开哪个会话（界面收掉以后调 clearOpenConvRequest）。 */
    private val openConv = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private var pickResult: CompletableDeferred<List<Uri>>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private var scanResult: CompletableDeferred<String?>? = null
    private var folderResult: CompletableDeferred<Uri?>? = null

    private val pickAny = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { pickResult?.complete(it) }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveResult?.complete(it) }
    private val scan = registerForActivityResult(ScanContract()) { scanResult?.complete(it.contents) }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderResult?.complete(it) }
    // 手机接微信时通知栏要常驻一条（前台服务）；Android 13 起显示通知要用户同意。没同意服务照样跑，只是通知栏看不到
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var askedNotify = false
    // 录音权限：第一次点输入框旁边的话筒时问
    private var recordResult: CompletableDeferred<Boolean>? = null
    private val askRecord = registerForActivityResult(ActivityResultContracts.RequestPermission()) { recordResult?.complete(it) }

    /** 问录音权限，同意返回 true。已经给过就直接 true。 */
    private suspend fun askRecordPermission(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        val d = CompletableDeferred<Boolean>()
        recordResult = d
        withContext(Dispatchers.Main) { askRecord.launch(android.Manifest.permission.RECORD_AUDIO) }
        return d.await()
    }

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
        val app = application as JxyApp
        if (Build.VERSION.SDK_INT >= 33 && !askedNotify && app.phoneAnswersWeixin() &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotify = true
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // 从系统设置给了「所有文件访问」权限回来：马上扫一遍文档
        (application as JxyApp).hub.local.let { local ->
            if (local is com.guixing.jixunying.engine.Engine && local.state.docs.needPermission && !com.guixing.jixunying.engine.docAccessMissing()) local.rescanDocs()
        }
    }

    // ---- Code 栏的通知（第二期 2026-10-13）：某一步开始等批准、或者一条回答做完了，不在前台时弹一条。
    //      Android 13 起要用户同意才能显示通知：第一次用到时问一下（没同意就不弹，不打扰）。

    private fun showCodeNotify(key: String, title: String, text: String, convId: String) {
        // 没权限就不弹。权限在用户手机上动手（发任务、新建会话）时问（askCodeNotifyPermission）：
        // 那时应用一定在前台；在这里问的话应用多半在后台，弹窗出不来（主会话审查 10-13）
        if (!canPostNotifications()) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(android.app.NotificationChannel(CHANNEL_CODE, "Code 栏", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Code 栏做完一件事、或者停下来等你批准"
                })
            // 请求码按会话分开、并且 UPDATE_CURRENT：附加内容不参与 PendingIntent 的比较，
            // 都用 0 的话系统会复用第一条的，点后来的通知打开的还是第一个会话（主会话审查 10-13）
            val open = android.app.PendingIntent.getActivity(
                this, convId.hashCode(),
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CONV, convId),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = android.app.Notification.Builder(this, CHANNEL_CODE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            // 同一件事（同一个 key）盖掉旧的，不同的事各占一条
            getSystemService(android.app.NotificationManager::class.java).notify(key.hashCode(), n)
        }
    }

    private fun canPostNotifications() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 问一次通知权限（Code 栏用；和接微信那条各问各的，那边被拒了这边还能问一次）。 */
    private var askedCodeNotify = false
    private fun askCodeNotifyPermission() {
        if (canPostNotifications() || askedCodeNotify) return
        askedCodeNotify = true
        runCatching { askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        // 从 Code 栏的通知点进来：界面打开以后切到那个会话（第二期 2026-10-13）
        intent.getStringExtra(EXTRA_CONV)?.takeIf { it.isNotBlank() }?.let { openConv.value = it }
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

        // ---- Code 栏的通知（第二期 2026-10-13）
        override val inForeground: Boolean get() = (application as JxyApp).inForeground
        override val openConvRequest get() = openConv
        override fun clearOpenConvRequest() { openConv.value = null }

        override fun askNotifyPermission() {
            runOnUiThread { if (!isFinishing) askCodeNotifyPermission() }
        }

        override fun notify(id: String, title: String, text: String, convId: String) {
            // 通知要在主线程发（请求权限的 launcher 也只能在主线程用）
            runOnUiThread { if (!isFinishing) runCatching { showCodeNotify(id, title, text, convId) } }
        }

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

        override val canOpenAppSettings = true
        override fun openAppSettings() {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }

        override fun requestFileAccess() {
            if (Build.VERSION.SDK_INT < 30) return
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }

        /** 系统文件夹选择器返回的是 content:// 地址，换成文件路径（主存储是 /storage/emulated/0）。 */
        override suspend fun pickFolder(title: String): String? {
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

        // res/drawable-nodpi/jxy_app_icon.png（tools/make_icons.py 生成，和启动图标同一张）
        override val brandIcon: ImageBitmap? by lazy {
            runCatching { BitmapFactory.decodeResource(resources, R.drawable.jxy_app_icon)?.asImageBitmap() }.getOrNull()
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

        // ---- 读出来（放音）和听写（录音）。手机上放用 MediaPlayer（先写成临时文件），录 用 AudioRecord，
        //      格式照设计说明第 5 节：wav、16000 Hz、单声道、16 位。

        override val canPlayAudio = true

        override fun log(tag: String, text: String) {
            android.util.Log.i(tag, text)
        }

        override suspend fun playWav(bytes: ByteArray) {
            val file = withContext(Dispatchers.IO) {
                File(cacheDir, "speak-${System.currentTimeMillis()}.wav").also { it.writeBytes(bytes) }
            }
            var mp: MediaPlayer? = null
            try {
                withContext(Dispatchers.Main) {
                    val p = MediaPlayer()
                    mp = p
                    p.setDataSource(file.absolutePath)
                    p.prepare()
                    val dur = p.duration
                    log("JxySpeech", "放音开始 ${bytes.size} 字节，时长 $dur 毫秒")
                    // 兜底：「放完」回调万一不来（用户 10-13 报「只读第一句就卡住」，模拟器上没复现），
                    // 过了这段的时长再多等 5 秒就当放完，接着放下一段，不让整条卡死
                    val limit = if (dur > 0) dur + 5_000L else 10 * 60_000L
                    val finished = kotlinx.coroutines.withTimeoutOrNull(limit) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                            var done = false
                            p.setOnCompletionListener { if (!done) { done = true; cont.resume(Unit) } }
                            p.setOnErrorListener { _, what, extra ->
                                if (!done) { done = true; cont.resumeWithException(IllegalStateException("这段音频放不出来（错误 $what/$extra）")) }
                                true
                            }
                            cont.invokeOnCancellation { runCatching { p.stop() } } // 点了「停止」或者超时
                            p.start()
                        }
                    }
                    if (finished == null) log("JxySpeech", "放完的回调没来，$limit 毫秒后当放完（播放位置 ${runCatching { p.currentPosition }.getOrDefault(-1)}）")
                    else log("JxySpeech", "放音结束")
                }
            } catch (e: Throwable) {
                log("JxySpeech", "放音出错或停止：${e::class.simpleName} ${e.message.orEmpty()}")
                throw e
            } finally {
                // 取消的时候也要 release，所以套 NonCancellable
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { runCatching { mp?.release() } }
                withContext(Dispatchers.IO) { file.delete() }
            }
        }

        override val canRecord = true
        private var rec: AudioRecord? = null
        private var recOut: java.io.ByteArrayOutputStream? = null
        private var recThread: Thread? = null
        @Volatile private var recStopped = false

        override suspend fun startRecording(): String? {
            if (rec != null) return null // 已经在录了
            if (!askRecordPermission()) return com.guixing.jixunying.ui.NO_RECORD_PERMISSION
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return "这台手机不支持 16 kHz 录音"
            val ar = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096) * 2)
            }.getOrElse { return "打不开麦克风：${it.message ?: it::class.simpleName}" }
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { ar.release() }
                return "麦克风打不开（可能被别的应用占着）"
            }
            val out = java.io.ByteArrayOutputStream()
            runCatching { ar.startRecording() }.onFailure {
                runCatching { ar.release() }
                return "打不开麦克风：${it.message ?: it::class.simpleName}"
            }
            rec = ar
            recOut = out
            recStopped = false
            recThread = Thread({
                val buf = ByteArray(4096)
                while (!recStopped) {
                    val n = runCatching { ar.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (n > 0) out.write(buf, 0, n) else if (n < 0) break // release 之后 read 会报错，收工
                }
            }, "jxy-record").also {
                it.isDaemon = true
                it.start()
            }
            return null
        }

        override suspend fun stopRecording(): ByteArray? = withContext(Dispatchers.IO) {
            val ar = rec ?: return@withContext null
            rec = null
            recStopped = true
            // 先 stop 让读线程的 read 返回、等它收工，再 release：读线程还卡在 read 里时 release，
            // 有的机器会原生崩溃（主会话审查 10-12 改的顺序）
            runCatching { ar.stop() }
            recThread?.join(2000)
            runCatching { ar.release() }
            val data = recOut?.toByteArray() ?: ByteArray(0) // 线程已经收工，这会儿读是安全的
            recOut = null
            recThread = null
            if (data.isEmpty()) null else com.guixing.jixunying.engine.SpeechText.wavOf(16000, 1, 16, data)
        }

        override fun cancelRecording() {
            val ar = rec ?: return
            val t = recThread
            rec = null
            recStopped = true
            runCatching { ar.stop() }
            recOut = null
            recThread = null
            // 和 stopRecording 一样：等读线程收工再 release（这里不能挂起，放到后台线程里等）
            Thread({ t?.join(2000); runCatching { ar.release() } }, "jxy-record-release").apply { isDaemon = true; start() }
        }
    }
}
