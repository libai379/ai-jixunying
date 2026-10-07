package com.guixing.jixunying

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.DocSettings
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchSource
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.model.USER_ID
import com.guixing.jixunying.model.Usage
import com.guixing.jixunying.ui.App
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Base64
import kotlin.test.Test

/**
 * 界面截图：不开窗口、不动鼠标，离屏把各个页面画出来存成 PNG，用来逐页检查界面。
 * 只在设了 JXY_SHOTS=输出文件夹 时跑：JXY_SHOTS=G:/DevCache/shots ./gradlew :shared:desktopTest --tests "*ShotsTest*" --rerun
 */
class ShotsTest {
    private val out = System.getenv("JXY_SHOTS")?.let { File(it) }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

    /** 手机尺寸用的平台：不是电脑，不能显示二维码。 */
    private class PhonePlatform : Platform {
        override val isDesktop = false
        override val deviceName = "SM-S938U"
        override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = emptyList()
        override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
        override suspend fun saveFile(name: String, bytes: ByteArray) = false
        override fun openUrl(url: String) {}
    }

    private fun seed(root: File, docsDir: File): Engine {
        val providers = listOf(
            ProviderConfig("pd", "deepseek", "DeepSeek 4.1 Flash", "https://api.deepseek.com", "sk-1234567890abcdef", listOf(ModelInfo("deepseek-flash"))),
            ProviderConfig("pm", "minimax", "MiniMax 中国版", "https://api.minimaxi.com/v1", "eyJhbGciOi.xxxxx", listOf(ModelInfo("MiniMax-M3", vision = true))),
            ProviderConfig("pz", "zhipu", "智谱开放平台", "https://open.bigmodel.cn/api/paas/v4", "abcd.efgh",
                listOf(ModelInfo("GLM-5.3-Flash"), ModelInfo("cogview-3-flash", tools = false, imageGen = true))),
            ProviderConfig("pi", "mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1", "sk-mimo-xxxx", listOf(ModelInfo("mimo-v2.6-flash"))),
        )
        val members = listOf(
            Member("ma", "阿德", "🧠", 0xFF5B6CFF, "pd", "deepseek-flash", "全能助手，回答准确、条理清楚，遇到事实问题先查证再说。"),
            Member("mb", "阿麦", "🤖", 0xFFEC4899, "pm", "MiniMax-M3", "写作和总结"),
            Member("mc", "阿智", "📊", 0xFF0EA5E9, "pz", "GLM-5.3-Flash"),
            Member("md", "阿米", "🍵", 0xFF10B981, "pi", "mimo-v2.6-flash"),
        )
        val now = System.currentTimeMillis()
        val convs = listOf(
            Conversation("c1", "租房合同要注意什么", listOf("ma", "mb"), ReplyMode.INDEPENDENT, createdAt = now - 3_600_000, updatedAt = now - 60_000),
            Conversation("c2", "微信对话", listOf("ma"), createdAt = now - 7_200_000, updatedAt = now - 3_000_000, channel = "weixin:u1@im.wechat"),
            Conversation("c3", "画一只在月球上喝茶的橘猫", listOf("md"), createdAt = now - 90_000_000, updatedAt = now - 86_000_000),
            Conversation("c4", "新对话", listOf("mc"), createdAt = now - 900_000_000, updatedAt = now - 800_000_000),
        )
        val st = Storage(root)
        st.saveState(AppState(
            providers = providers, members = members, conversations = convs,
            memories = listOf(
                MemoryItem("m1", "在北京做产品经理", "关于我", "手动添加", now, now, pinned = true),
                MemoryItem("m2", "希望回答先给结论，少用术语", "偏好", "租房合同要注意什么", now, now),
                MemoryItem("m3", "正在找房，预算每月 5000 以内", "事实", "租房合同要注意什么", now, now),
            ),
            settings = Settings(proxy = "127.0.0.1:10809", docs = DocSettings(folders = listOf(docsDir.path))),
            hostId = "h1", pairingSecret = Base64.getEncoder().encodeToString(ByteArray(16) { 1 }),
        ))
        val doc = Attachment("a1", "租房合同.docx", "application/octet-stream", 946, AttachmentKind.DOCUMENT, textChars = 56)
        st.putFile(doc, ByteArray(10), "房屋租赁合同 月租金 4800 元")
        val cat = Attachment("a2", "图片_1.png", "image/png", png.size.toLong(), AttachmentKind.GENERATED_IMAGE, note = "月球上喝茶的橘猫")
        st.putFile(cat, png, null)
        st.saveMessages("c1", listOf(
            Message("u1", "c1", Role.USER, USER_ID, "帮我看看这份合同有什么坑", attachments = listOf(doc), createdAt = now - 120_000),
            Message("r1", "c1", Role.AI, "ma",
                "先说结论：**这不是一份能签的合同**，只是两条备忘。\n\n### 主要问题\n1. **每月 5 日前支付**：没写逾期怎么办。\n2. **押金两个月**：退还期限从哪天起算没写清[1]。\n\n| 条款 | 风险 |\n|---|---|\n| 押金 | 扣款条件没写 |\n| 租金 | 逾期责任没写 |\n\n建议补上维修责任、提前退租、违约金这几条。",
                reasoning = "用户想知道合同的风险……",
                tools = listOf(
                    ToolStep("doc", "读《租房合同.docx》"),
                    ToolStep("search", "租房押金 规定", listOf(SearchSource("住房租赁条例解读", "https://www.gov.cn/zhengce/a", "押金不得超过……"), SearchSource("押金退还纠纷", "https://news.example.com/b"))),
                ),
                createdAt = now - 110_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）", usage = Usage(1345, 420, 128, 4800)),
            Message("r2", "c1", Role.AI, "mb", "补充一点：**签之前拍照留存房屋现状**，退押金时少扯皮。@阿德 说的维修责任也要写进去。",
                createdAt = now - 100_000, modelLabel = "MiniMax-M3（MiniMax 中国版）", usage = Usage(980, 60, 0, 2100)),
        ))
        st.saveMessages("c3", listOf(
            Message("u3", "c3", Role.USER, USER_ID, "画一只在月球上喝茶的橘猫", createdAt = now - 86_100_000),
            Message("r3", "c3", Role.AI, "md", "画好了。", attachments = listOf(cat), tools = listOf(ToolStep("image", "月球上喝茶的橘猫，水彩风格")), createdAt = now - 86_000_000,
                modelLabel = "mimo-v2.6-flash（小米 MiMo）"),
        ))
        val e = Engine(Storage(root))
        e.weixin = com.guixing.jixunying.engine.WeixinBridge(e, File(root, "weixin"))
        e.rescanDocs()
        repeat(100) { if (e.state.docs.count < 3 || e.state.docs.scanning) Thread.sleep(50) }
        return e
    }

    private fun shoot(name: String, w: Int, h: Int, density: Float, hub: Hub, platform: Platform, start: String?) {
        val scene = ImageComposeScene(w, h, Density(density)) { App(hub, platform, start) }
        var img: org.jetbrains.skia.Image? = null
        for (i in 0 until 14) {
            img = scene.render(i * 120_000_000L)
            Thread.sleep(60)
        }
        File(out, "$name.png").writeBytes(img!!.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun shots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = kotlin.io.path.createTempDirectory("jxy-shots").toFile()
        val docs = File(tmp, "文档").apply { mkdirs() }
        File(docs, "周报.txt").writeText("项目周报：本周完成登录页改版，下周灰度测试，预算 3.5 万元。", Charsets.UTF_8)
        File(docs, "租房合同.md").writeText("# 房屋租赁合同\n月租金 4800 元，押金两个月。", Charsets.UTF_8)
        File(docs, "会议纪要.txt").writeText("周一例会：确定上线时间为 10 月 20 日。", Charsets.UTF_8)
        val e = seed(File(tmp, "data"), docs)
        val hub = Hub(e)
        val desktop = DesktopPlatform { null }
        val phone = PhonePlatform()

        val wide = listOf(
            "chat" to "conv:c1", "chat-image" to "conv:c3", "newchat" to "newchat", "docs" to "docs",
        ) + com.guixing.jixunying.ui.SettingsTab.entries.map { "settings-" + it.name.lowercase() to "settings:${it.name}" } +
            listOf("provider-add" to "settings:PROVIDERS#add")
        for ((name, start) in wide) shoot("desktop-$name", 1280, 820, 1f, hub, desktop, start)
        val narrow = listOf("chat" to "conv:c1", "docs" to "docs", "settings-home" to "settingshome", "settings-image" to "settings:IMAGE",
            "settings-memory" to "settings:MEMORY", "settings-weixin" to "settings:WEIXIN", "settings-providers" to "settings:PROVIDERS",
            "settings-search" to "settings:SEARCH", "settings-profile" to "settings:PROFILE")
        for ((name, start) in narrow) shoot("phone-$name", 824, 1784, 2f, hub, phone, start)

        // 弹窗
        shoot("desktop-convsettings", 1280, 820, 1f, hub, desktop, "conv:c1#convsettings")
        shoot("desktop-member-edit", 1280, 820, 1f, hub, desktop, "settings:MEMBERS#edit")
        shoot("phone-convsettings", 824, 1784, 2f, hub, phone, "conv:c1#convsettings")
        shoot("phone-devices", 824, 1784, 2f, hub, phone, "settings:DEVICES")
        // 深色
        kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.SaveSettings(e.state.settings.copy(darkMode = 2))) }
        shoot("desktop-dark-chat", 1280, 820, 1f, hub, desktop, "conv:c1")
        shoot("desktop-dark-docs", 1280, 820, 1f, hub, desktop, "docs")
        shoot("phone-dark-settings-home", 824, 1784, 2f, hub, phone, "settingshome")

        // 空白状态：第一次打开
        val empty = Engine(Storage(File(tmp, "empty")))
        shoot("desktop-welcome", 1280, 820, 1f, Hub(empty), desktop, null)
        shoot("phone-welcome", 824, 1784, 2f, Hub(empty), phone, null)
        tmp.deleteRecursively()
    }
}
