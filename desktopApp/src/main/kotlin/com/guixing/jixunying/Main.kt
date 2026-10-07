package com.guixing.jixunying

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.ui.App
import java.awt.Frame

fun main() {
    // Windows 上默认用 DirectX 渲染，部分显卡 / 驱动下每次重画（打字、鼠标悬停、打开弹窗）文字会闪。
    // 官方建议改用 OpenGL（https://github.com/JetBrains/compose-multiplatform/issues/553）。
    // 想换回来可以设环境变量 SKIKO_RENDER_API 或启动参数 -Dskiko.renderApi=DIRECT3D。
    if (System.getProperty("skiko.renderApi") == null && System.getenv("SKIKO_RENDER_API") == null) {
        System.setProperty("skiko.renderApi", "OPENGL")
    }
    val storage = Storage(Storage.defaultRoot())
    val engine = Engine(storage)
    // 联机服务：连公共中转，手机在哪儿都能连进来
    val relay = RelayHost(engine)
    engine.onRelaySettingsChanged = { Thread { relay.restart() }.start() }
    Thread { relay.restart() }.start()

    var frame: Frame? = null
    val platform = DesktopPlatform { frame }
    val hub = Hub(engine)

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(
            onCloseRequest = { relay.stop(); exitApplication() },
            title = "AI集训营",
            icon = AppIcon,
            state = state,
        ) {
            frame = window
            window.minimumSize = java.awt.Dimension(420, 560)
            App(hub, platform, System.getProperty("jxy.start"))
        }
    }
}

/** 窗口和任务栏图标：渐变圆角方块 + 白色「工」字形笔画（和安卓图标一致）。 */
private object AppIcon : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(256f, 256f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val s = size.minDimension
        drawRoundRect(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF6A5CFF), androidx.compose.ui.graphics.Color(0xFF2EC5CE))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f),
        )
        val w = androidx.compose.ui.graphics.Color.White
        fun bar(x: Float, y: Float, bw: Float, bh: Float) =
            drawRect(w, androidx.compose.ui.geometry.Offset(s * x, s * y), androidx.compose.ui.geometry.Size(s * bw, s * bh))
        bar(0.22f, 0.24f, 0.56f, 0.09f)
        bar(0.28f, 0.44f, 0.44f, 0.08f)
        bar(0.22f, 0.66f, 0.56f, 0.09f)
        bar(0.455f, 0.24f, 0.09f, 0.51f)
    }
}
