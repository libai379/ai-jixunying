package com.guixing.jixunying

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.LanServer
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.ui.App
import java.awt.Frame

fun main() {
    val storage = Storage(Storage.defaultRoot())
    val engine = Engine(storage)
    val server = LanServer(engine)
    engine.onServerSettingsChanged = { server.restart() }
    Thread { server.restart() }.start()

    var frame: Frame? = null
    val platform = DesktopPlatform { frame }

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(
            onCloseRequest = { server.stop(); exitApplication() },
            title = "AI集训营",
            icon = AppIcon,
            state = state,
        ) {
            frame = window
            window.minimumSize = java.awt.Dimension(420, 560)
            App(engine, platform, System.getProperty("jxy.start"))
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
