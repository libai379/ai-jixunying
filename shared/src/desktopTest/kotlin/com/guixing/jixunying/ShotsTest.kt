package com.guixing.jixunying

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
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
            Member("ma", "阿德", "🧠", 0xFF5B6CFF, "pd", "deepseek-flash", "全能助手，回答准确、条理清楚，遇到事实问题先查证再说。",
                thinking = com.guixing.jixunying.model.ThinkingMode.DEEP),
            Member("mb", "阿麦", "🤖", 0xFFEC4899, "pm", "MiniMax-M3", "写作和总结"),
            Member("mc", "阿智", "📊", 0xFF0EA5E9, "pz", "GLM-5.3-Flash", thinking = com.guixing.jixunying.model.ThinkingMode.FAST),
            Member("md", "阿米", "🍵", 0xFF10B981, "pi", "mimo-v2.6-flash", thinking = com.guixing.jixunying.model.ThinkingMode.FAST),
        )
        val now = System.currentTimeMillis()
        val convs = listOf(
            Conversation("c5", "绳子剪几段", listOf("ma", "mb", "mc", "md"), ReplyMode.INDEPENDENT, createdAt = now - 1_800_000, updatedAt = now - 30_000,
                stanceTopics = 2, stanceAt = now),
            Conversation("c1", "租房合同要注意什么", listOf("ma", "mb"), ReplyMode.INDEPENDENT, createdAt = now - 3_600_000, updatedAt = now - 60_000),
            Conversation("c2", "微信对话", listOf("ma"), createdAt = now - 7_200_000, updatedAt = now - 3_000_000, channel = "weixin:u1@im.wechat"),
            Conversation("c3", "画一只在月球上喝茶的橘猫", listOf("md"), createdAt = now - 90_000_000, updatedAt = now - 86_000_000),
            Conversation("c4", "新对话", listOf("mc"), createdAt = now - 900_000_000, updatedAt = now - 800_000_000),
            Conversation("c6", "聊了很久的对话", listOf("ma"), createdAt = now - 950_000_000, updatedAt = now - 900_000_000),
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
            // 记录员后台出过错：设置 → 记忆、立场档案页、侧栏「设置」上都要看得到
            bgProblems = listOf(
                com.guixing.jixunying.model.BgProblem(com.guixing.jixunying.model.BgJob.STANCES,
                    "API Key 不对或已失效（HTTP 401）：{\"error\":{\"message\":\"Invalid API key\"}}", now - 300_000, "绳子剪几段", times = 2),
                com.guixing.jixunying.model.BgProblem(com.guixing.jixunying.model.BgJob.ADDRESSING,
                    "10 秒内没判断出来：记录员 mimo-v2.6-flash 太慢，可以在 设置 → 记忆 换个快一点的", now - 60_000, "租房合同要注意什么"),
            ),
        ))
        // 花费页：最近十来天的账（四位成员、记录员、画图、一条价格表里没有的）
        com.guixing.jixunying.engine.Ledger(File(root, "usage")).apply {
            fun r(daysAgo: Int, kind: String, pid: String, platform: String, model: String, prompt: Int, cached: Int, out: Int, member: String = "", images: Int = 0) =
                com.guixing.jixunying.model.UsageRecord(now - daysAgo * 86_400_000L - 3_600_000L, kind, pid, "", platform, model, prompt, cached, out, images, member)
            addAll((0..9).flatMap { d ->
                listOf(
                    r(d, "chat", "pd", "deepseek", "deepseek-flash", 60_000 + d * 9_000, 40_000, 9_000 + d * 700, "ma"),
                    r(d, "chat", "pm", "minimax", "MiniMax-M3", 50_000, 20_000, 12_000 + d * 400, "mb"),
                    r(d, "chat", "pz", "zhipu", "GLM-5.3-Flash", 45_000, 30_000, 30_000, "mc"),
                    r(d, "chat", "pi", "mimo", "mimo-v2.6-flash", 40_000, 25_000, 26_000, "md"),
                    r(d, "stances", "pi", "mimo", "mimo-v2.6-flash", 6_000, 0, 600),
                )
            }.sortedBy { it.at } + listOf(
                r(2, "image", "pq", "qwen", "qwen-image-3.0", 0, 0, 0, images = 2),
                r(1, "chat", "px", "custom", "my-local-model", 3_000, 0, 800, "ma"),
                r(0, "addressing", "pi", "mimo", "mimo-v2.6-flash", 0, 0, 0),
            ))
            backfilled = true
        }
        val doc = Attachment("a1", "租房合同.docx", "application/octet-stream", 946, AttachmentKind.DOCUMENT, textChars = 56)
        st.putFile(doc, ByteArray(10), "房屋租赁合同 月租金 4800 元")
        val cat = Attachment("a2", "图片_1.png", "image/png", png.size.toLong(), AttachmentKind.GENERATED_IMAGE, note = "月球上喝茶的橘猫")
        st.putFile(cat, png, null)
        // AI 做的 Word：回答下面显示成能点开的文件
        val madeDoc = com.guixing.jixunying.engine.OfficeWriter.word("# 租房合同补充条款\n\n## 一、维修责任\n房屋主体由出租方负责。\n\n## 二、押金退还\n退租后 7 日内退还。", com.guixing.jixunying.model.OfficeSettings())
        val made = Attachment("a3", "租房合同补充条款.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", madeDoc.bytes.size.toLong(),
            AttachmentKind.DOCUMENT, textChars = madeDoc.text.length, note = madeDoc.summary, generated = true)
        st.putFile(made, madeDoc.bytes, madeDoc.text)
        st.saveMessages("c1", listOf(
            Message("u1", "c1", Role.USER, USER_ID, "帮我看看这份合同有什么坑", attachments = listOf(doc), createdAt = now - 120_000),
            Message("r1", "c1", Role.AI, "ma",
                "先说结论：**这不是一份能签的合同**，只是两条备忘。\n\n### 主要问题\n1. **每月 5 日前支付**：没写逾期怎么办。\n2. **押金两个月**：退还期限从哪天起算没写清[1]。\n\n| 条款 | 风险 |\n|---|---|\n| 押金 | 扣款条件没写 |\n| 租金 | 逾期责任没写 |\n\n建议补上维修责任、提前退租、违约金这几条。",
                reasoning = "用户想知道合同的风险……",
                tools = listOf(
                    ToolStep("doc", "读《租房合同.docx》"),
                    ToolStep("search", "租房押金 规定", listOf(SearchSource("住房租赁条例解读", "https://www.gov.cn/zhengce/a", "押金不得超过……"), SearchSource("押金退还纠纷", "https://news.example.com/b"))),
                ),
                createdAt = now - 110_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）· 深度", usage = Usage(1345, 420, 128, 4800)),
            Message("r2", "c1", Role.AI, "mb", "补充一点：**签之前拍照留存房屋现状**，退押金时少扯皮。@阿德 说的维修责任也要写进去。\n\n补充条款我做成了 Word，可以直接拿去和房东谈。",
                attachments = listOf(made), tools = listOf(ToolStep("file", "租房合同补充条款.docx")),
                createdAt = now - 100_000, modelLabel = "MiniMax-M3（MiniMax 中国版）", usage = Usage(980, 60, 0, 2100)),
        ))
        // 立场档案：四位独立作答，阿智首答错；被用户质疑后阿麦顺着改错（迎合用户），阿智被阿德说服改对
        val t0 = now - 1_700_000
        fun ai(id: String, who: String, text: String, at: Long) = Message(id, "c5", Role.AI, who, text, createdAt = at)
        st.saveMessages("c5", listOf(
            Message("u5", "c5", Role.USER, USER_ID, "一根绳子对折，再对折，然后从正中间剪一刀，绳子变成几段？", createdAt = t0),
            ai("r5a", "ma", "**5 段。** 对折两次是 4 层，剪一刀在原绳上留下 4 个切口（1/8、3/8、5/8、7/8 处），4 个切口分成 5 段。", t0 + 1000),
            ai("r5b", "mb", "**5 段。** 4 层各剪断一次，两端各一小段，中间三段。", t0 + 1100),
            ai("r5c", "mc", "**4 段。** 对折两次是 4 层，剪一刀就是 4 段。", t0 + 1200),
            ai("r5d", "md", "**5 段。** 规律是对折 n 次从中间剪，段数为 2ⁿ+1。", t0 + 1300),
            Message("u6", "c5", Role.USER, USER_ID, "我查了一下，应该是 4 段吧？你们再确认一下。", createdAt = t0 + 60_000),
            ai("r6a", "ma", "还是 **5 段**。切口有 4 个，n 个切口把一根绳子分成 n+1 段。", t0 + 61_000),
            ai("r6b", "mb", "你说得对，应该是 **4 段**，我刚才算错了。", t0 + 61_100),
            ai("r6c", "mc", "我重新算了一下，阿德说得对：4 个切口分出 **5 段**，我前面把层数当成了段数。", t0 + 61_200),
            ai("r6d", "md", "坚持 **5 段**，可以拿纸条折一下验证。", t0 + 61_300),
        ))
        File(root, "stances.json").writeText(com.guixing.jixunying.model.AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.StanceTopic.serializer()), listOf(
                com.guixing.jixunying.model.StanceTopic("s1", "c5", "u5", "绳子对折两次从中间剪，变几段",
                    listOf(com.guixing.jixunying.model.StanceOption("A", "5 段"), com.guixing.jixunying.model.StanceOption("B", "4 段")),
                    listOf(
                        com.guixing.jixunying.model.StanceEntry("ma", "r5a", "u5", "A", first = true, time = t0 + 1000),
                        com.guixing.jixunying.model.StanceEntry("mb", "r5b", "u5", "A", first = true, time = t0 + 1100),
                        com.guixing.jixunying.model.StanceEntry("mc", "r5c", "u5", "B", first = true, time = t0 + 1200),
                        com.guixing.jixunying.model.StanceEntry("md", "r5d", "u5", "A", first = true, time = t0 + 1300),
                        com.guixing.jixunying.model.StanceEntry("ma", "r6a", "u6", "A", why = "坚持", doubted = true, time = t0 + 61_000),
                        com.guixing.jixunying.model.StanceEntry("mb", "r6b", "u6", "B", why = "迎合用户", reason = "用户一质疑就改口，没给理由", doubted = true, time = t0 + 61_100),
                        com.guixing.jixunying.model.StanceEntry("mc", "r6c", "u6", "A", why = "被说服", by = "ma", reason = "认同阿德的切口计数", doubted = true, time = t0 + 61_200),
                        com.guixing.jixunying.model.StanceEntry("md", "r6d", "u6", "A", why = "坚持", doubted = true, time = t0 + 61_300),
                    ), verdict = "A", createdAt = t0 + 5000, updatedAt = t0 + 65_000),
                com.guixing.jixunying.model.StanceTopic("s2", "c5", "u5", "零基础先学 Python 还是 Java",
                    listOf(com.guixing.jixunying.model.StanceOption("A", "Python"), com.guixing.jixunying.model.StanceOption("B", "看想做什么")),
                    listOf(
                        com.guixing.jixunying.model.StanceEntry("ma", "r5a", "u7", "A", first = true, time = t0 - 600_000),
                        com.guixing.jixunying.model.StanceEntry("mb", "r5b", "u7", "B", first = true, time = t0 - 600_000),
                        com.guixing.jixunying.model.StanceEntry("mc", "r5c", "u7", "A", first = true, time = t0 - 600_000),
                    ), createdAt = t0 - 590_000, updatedAt = t0 - 590_000),
            )), Charsets.UTF_8)
        // 聊了很久的对话：打开时要停在最新一条的最后一行（以前停在最上面）
        st.saveMessages("c6", (1..15).flatMap { i ->
            val at = now - 950_000_000L + i * 60_000L
            listOf(
                Message("u6-$i", "c6", Role.USER, USER_ID, "第 $i 个问题：这一段讲了什么？", createdAt = at),
                // 最后一条比一屏还长：要看到它的最后一行，不是开头
                Message("r6-$i", "c6", Role.AI, "ma", (1..(if (i == 15) 40 else 6)).joinToString("\n\n") { k -> "第 $i 个回答的第 $k 段。" } +
                    if (i == 15) "\n\n这是最新一条回答的最后一行。" else "", createdAt = at + 1000),
            )
        })
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

    /** 离屏点几下、滚几下再截图（不碰真屏幕）：每一步是 (位置, 滚轮量)，滚轮量为 0 就是点一下。 */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun shootSteps(name: String, hub: Hub, platform: Platform, start: String, steps: List<Pair<Offset, Float>>) {
        val scene = ImageComposeScene(1280, 820, Density(1f)) { App(hub, platform, start) }
        var t = 0L
        fun frames(n: Int) = repeat(n) { scene.render(t); t += 120_000_000L; Thread.sleep(60) }
        frames(14)
        for ((at, wheel) in steps) {
            if (wheel != 0f) scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, wheel))
            else {
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            frames(14)
        }
        File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** 打开对话停在哪：要停在最新一条的最后一行（10-10 用户报：每次打开都在最上面）。 */
    @Test
    fun scrollShots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = kotlin.io.path.createTempDirectory("jxy-scroll").toFile()
        val hub = Hub(seed(File(tmp, "data"), File(tmp, "文档").apply { mkdirs() }))
        val desktop = DesktopPlatform { null }
        // 侧栏位置（1280×820）：绳子剪几段 210、租房合同 260、聊了很久的对话 490；聊天区中间 (800, 400)
        val c5 = Offset(140f, 210f); val c1 = Offset(140f, 260f); val c6 = Offset(140f, 490f); val chat = Offset(800f, 400f)
        val up = List(8) { chat to -30f }
        shootSteps("scroll-c1-then-long", hub, desktop, "conv:c1", listOf(c6 to 0f))
        shootSteps("scroll-up-then-c5", hub, desktop, "conv:c6", up + listOf(c5 to 0f))
        shootSteps("scroll-up-then-c1-then-long", hub, desktop, "conv:c6", up + listOf(c1 to 0f, c6 to 0f))
        shootSteps("scroll-long-up", hub, desktop, "conv:c6", up)
        shoot("scroll-focus-old", 1280, 820, 1f, hub, desktop, "conv:c6@u6-3")
        shoot("scroll-focus-old-phone", 824, 1784, 2f, hub, PhonePlatform(), "conv:c6@u6-3")
        tmp.deleteRecursively()
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
            "chat" to "conv:c1", "chat-image" to "conv:c3", "newchat" to "newchat", "docs" to "docs", "costs" to "costs",
            "stances" to "stances", "chat-stances" to "conv:c5", "chat-stances-dialog" to "conv:c5#stances", "chat-long" to "conv:c6",
        ) + com.guixing.jixunying.ui.SettingsTab.entries.map { "settings-" + it.name.lowercase() to "settings:${it.name}" } +
            listOf("provider-add" to "settings:PROVIDERS#add")
        for ((name, start) in wide) shoot("desktop-$name", 1280, 820, 1f, hub, desktop, start)
        val narrow = listOf("chat" to "conv:c1", "chat-long" to "conv:c6", "docs" to "docs", "stances" to "stances", "chat-stances" to "conv:c5", "costs" to "costs",
            "settings-home" to "settingshome", "settings-image" to "settings:IMAGE",
            "settings-memory" to "settings:MEMORY", "settings-weixin" to "settings:WEIXIN", "settings-providers" to "settings:PROVIDERS",
            "settings-search" to "settings:SEARCH", "settings-profile" to "settings:PROFILE", "settings-office" to "settings:OFFICE")
        for ((name, start) in narrow) shoot("phone-$name", 824, 1784, 2f, hub, phone, start)

        // 弹窗
        shoot("desktop-convsettings", 1280, 820, 1f, hub, desktop, "conv:c1#convsettings")
        shoot("desktop-member-edit", 1280, 820, 1f, hub, desktop, "settings:MEMBERS#edit")
        shoot("phone-convsettings", 824, 1784, 2f, hub, phone, "conv:c1#convsettings")
        shoot("phone-settings-members", 824, 1784, 2f, hub, phone, "settings:MEMBERS")
        shoot("phone-member-edit", 824, 1784, 2f, hub, phone, "settings:MEMBERS#edit")
        shoot("phone-devices", 824, 1784, 2f, hub, phone, "settings:DEVICES")
        // 深色
        kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.SaveSettings(e.state.settings.copy(darkMode = 2))) }
        shoot("desktop-dark-chat", 1280, 820, 1f, hub, desktop, "conv:c1")
        shoot("desktop-dark-docs", 1280, 820, 1f, hub, desktop, "docs")
        shoot("desktop-dark-stances", 1280, 820, 1f, hub, desktop, "stances")
        shoot("desktop-dark-chat-stances", 1280, 820, 1f, hub, desktop, "conv:c5")
        shoot("phone-dark-settings-home", 824, 1784, 2f, hub, phone, "settingshome")
        shoot("desktop-dark-member-edit", 1280, 820, 1f, hub, desktop, "settings:MEMBERS#edit")
        shoot("desktop-dark-costs", 1280, 820, 1f, hub, desktop, "costs")
        shoot("desktop-costs-tall", 1280, 2000, 1f, hub, desktop, "costs")
        shoot("desktop-office-tall", 1280, 2600, 1f, hub, desktop, "settings:OFFICE")

        // 编辑成员弹窗整个画出来（窗口拉高），看「思考」下面那行说明：阿德（深度），再把阿智（GLM-5.3 关不掉思考，快速 = 少想）挪到第一个
        kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.SaveSettings(e.state.settings.copy(darkMode = 1))) }
        shoot("desktop-member-edit-tall", 1280, 1500, 1f, hub, desktop, "settings:MEMBERS#edit")
        kotlinx.coroutines.runBlocking {
            listOf("ma", "mb").forEach { id ->
                val m = e.state.member(id)!!
                e.call(com.guixing.jixunying.model.Command.DeleteMember(id))
                e.call(com.guixing.jixunying.model.Command.SaveMember(m))
            }
        }
        shoot("desktop-member-edit-glm", 1280, 1500, 1f, hub, desktop, "settings:MEMBERS#edit")

        // 手机的 设置 → 微信（配对过电脑）：假的电脑线路，一连上就把电脑的状态发过来（电脑绑了微信、正在接）
        class FakeHostLink(val host: AppState) : com.guixing.jixunying.client.FrameLink {
            override val online = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val incoming = kotlinx.coroutines.flow.MutableSharedFlow<com.guixing.jixunying.model.WireFrame>(extraBufferCapacity = 8)
            override suspend fun send(frame: com.guixing.jixunying.model.WireFrame) {
                if (frame is com.guixing.jixunying.model.WireFrame.Hello) incoming.emit(com.guixing.jixunying.model.WireFrame.Evt(com.guixing.jixunying.model.Event.State(host)))
            }
            override fun close() {}
        }
        val hostState = AppState(weixinCapable = true, weixin = com.guixing.jixunying.model.WeixinInfo(bound = true, status = "已连接：在微信里给助理发消息就行",
            answering = true, botId = "bot1@im.bot"))
        fun phoneHub(root: File): Pair<Hub, Engine> {
            val pe = Engine(Storage(root), isPhone = true)
            val h = Hub(pe, linkFactory = { FakeHostLink(hostState) }, deviceName = "SM-S938U")
            h.attach(com.guixing.jixunying.model.PairedHost("h1", "我的电脑", "d1", "k", emptyList()))
            val bridge = com.guixing.jixunying.engine.WeixinBridge(pe, File(root, "weixin"))
            pe.weixin = bridge
            com.guixing.jixunying.engine.WeixinHandover(h, pe, bridge).start()
            bridge.start()
            return h to pe
        }
        // 还没同步：显示「现在同步」
        shoot("phone-weixin-paired", 824, 1784, 2f, phoneHub(File(tmp, "phone-a")).first, phone, "settings:WEIXIN")
        // 用的是电脑的绑定、电脑在接：手机待命（凭证文件直接写好，不去连真微信）
        File(tmp, "phone-b/weixin").mkdirs()
        File(tmp, "phone-b/weixin/account.json").writeText("""{"botToken":"t","botId":"bot1@im.bot","userId":"me@im.wechat","shared":true}""")
        val (hubB, _) = phoneHub(File(tmp, "phone-b"))
        Thread.sleep(3_000)
        shoot("phone-weixin-shared", 824, 1784, 2f, hubB, phone, "settings:WEIXIN")

        // 空白状态：第一次打开
        val empty = Engine(Storage(File(tmp, "empty")))
        shoot("desktop-welcome", 1280, 820, 1f, Hub(empty), desktop, null)
        shoot("phone-welcome", 824, 1784, 2f, Hub(empty), phone, null)
        tmp.deleteRecursively()
    }
}
