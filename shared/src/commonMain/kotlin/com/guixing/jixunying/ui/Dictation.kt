package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 输入框的听写：点话筒开始录，再点一下停下，交给听写模型，认出来的字插到输入框光标处（不直接发，用户看一眼再发）。
 * 10-12 先在对话栏做的；2026-10-29 用户：「Code 为什么没有语音输入？」——抽出来，对话栏、Code 栏共用这一份。
 * 一次最多录 5 分钟（MiniMax 一段最长 500 秒，留点余量），到点自动停下并听写；离开这个会话就丢掉这段录音。
 * 听写用本机的服务商：手机遥控电脑时，录音不往中转上传（见 SpeechUi.kt 顶上那段）。
 */
class Dictation internal constructor(
    private val ctl: AppController,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
) {
    var recording by mutableStateOf(false)
        private set
    var recSec by mutableStateOf(0)
        internal set
    var recognizing by mutableStateOf(false)
        private set

    /** 开始录音；没有麦克风 / 没给权限就把原因弹出来（权限被拒时提示条上多一个「去设置」）。 */
    fun start() {
        if (recording || recognizing) return
        scope.launch {
            val err = platform.startRecording()
            if (err == null) {
                recSec = 0
                recording = true
                return@launch
            }
            if (err == NO_RECORD_PERMISSION && platform.canOpenAppSettings) {
                val res = ctl.snackbar.showSnackbar(err, actionLabel = "去设置", withDismissAction = true, duration = SnackbarDuration.Long)
                if (res == SnackbarResult.ActionPerformed) platform.openAppSettings()
            } else ctl.toast(err)
        }
    }

    fun stop() {
        if (!recording) return
        recording = false
        scope.launch {
            val wav = platform.stopRecording()
            if (wav == null) { ctl.toast("没有录到声音，再试一次"); return@launch }
            recognizing = true
            val r = ctl.hub.local.call(Command.Transcribe(kotlin.io.encoding.Base64.encode(wav)))
            recognizing = false
            if (!r.ok) { ctl.toast(r.message.ifBlank { "没听清，再说一次" }); return@launch }
            val t = r.data.trim()
            if (t.isBlank()) ctl.toast("没听出内容，再说一次") else onText(t)
        }
    }

    internal fun cancel() {
        if (recording) { recording = false; platform.cancelRecording() }
    }
}

/** 一个输入框一份听写；key 换了（换了会话）就是新的一份，旧的那段录音丢掉。 */
@Composable
fun rememberDictation(ctl: AppController, key: Any?, onText: (String) -> Unit): Dictation {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onText)
    val d = remember(key) { Dictation(ctl, platform, scope) { latest.value(it) } }
    LaunchedEffect(d, d.recording) {
        if (!d.recording) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            d.recSec++
            if (d.recSec >= 300) {
                ctl.toast("一次最多录 5 分钟，先认这一段")
                d.stop()
                return@LaunchedEffect
            }
        }
    }
    DisposableEffect(d) { onDispose { d.cancel() } }
    return d
}

/** 输入框那一排的话筒：平时是圆按钮（ComposerIcon）；录着是红色胶囊，显示几分几秒，点一下停；认字时转圈。 */
@Composable
fun DictationButton(d: Dictation, desc: String) {
    when {
        d.recognizing -> Row(Modifier.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(5.dp))
            Text("识别中…", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
        }
        d.recording -> Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), RoundedCornerShape(50))
                .clickable { d.stop() }.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Mic, "停下并变成文字", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text("${d.recSec / 60}:${(d.recSec % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        else -> ComposerIcon(Icons.Rounded.Mic, desc) { d.start() }
    }
}

/** 认出来的字插到光标处（输入框里原来有字就接在旁边，不覆盖）。 */
fun TextFieldValue.withSpoken(t: String): TextFieldValue {
    val at = selection.start.coerceIn(0, text.length)
    val before = text.substring(0, at)
    val add = if (before.isEmpty() || before.last().isWhitespace()) t else " $t"
    return TextFieldValue(before + add + text.substring(at), TextRange(at + add.length))
}
