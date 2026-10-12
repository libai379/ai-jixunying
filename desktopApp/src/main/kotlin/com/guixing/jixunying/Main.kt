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
    // Code 栏跑命令的执行器（安全核心在 engine/code/CodeShell.kt）：只在电脑上有，手机上是空的（手机不在本机跑命令）
    engine.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
    // 联机服务：连公共中转，手机在哪儿都能连进来
    val relay = RelayHost(engine)
    engine.onRelaySettingsChanged = { Thread { relay.restart() }.start() }
    Thread { relay.restart() }.start()
    // 微信助理（官方 ClawBot）：绑定过就自动连上
    val weixin = com.guixing.jixunying.engine.WeixinBridge(engine, java.io.File(storage.root, "weixin"))
    engine.weixin = weixin
    weixin.start()

    var frame: Frame? = null
    val platform = DesktopPlatform { frame }
    val hub = Hub(engine)

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(
            onCloseRequest = { relay.stop(); weixin.stop(); exitApplication() },
            title = "AI集训营",
            // 窗口和任务栏图标：用户 2026-10-12 给的那张（和安装包、安卓、App 里左上角同一张）
            icon = platform.brandIcon?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
            state = state,
        ) {
            frame = window
            window.minimumSize = java.awt.Dimension(420, 560)
            App(hub, platform, System.getProperty("jxy.start"))
        }
    }
}
