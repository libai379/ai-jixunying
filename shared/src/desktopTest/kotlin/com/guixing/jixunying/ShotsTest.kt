package com.guixing.jixunying

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
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
import com.guixing.jixunying.model.FileChange
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // 手机上用的是安卓资源里同一张图，截图时从电脑的资源里读
        override val brandIcon: ImageBitmap? = DesktopPlatform.loadBrandIcon()
    }

    /**
     * 截图用的台面：能放音、能录音，但不碰真设备（截图时不真的出声、不真的开麦克风）。
     * 手机能不能放音、录音由真机决定，这里的 PhonePlatform 还是「不能」——
     * 要看手机上语音那一页的样子，就套一层这个。
     */
    private class FakeAvPlatform(private val base: Platform) : Platform by base {
        override val canPlayAudio = true
        override val canRecord = true
        override suspend fun playWav(bytes: ByteArray) {}
        override suspend fun startRecording(): String? = null
    }

    /**
     * 截图用：读出来永远停在「合成中…」——SpeakPlan 立刻回一个两段的计划，Speak 一直不返回。
     * 用来看「停止」和「合成中…」长什么样（真跑要联网、还要花钱）。
     */
    private class HangingSpeech(private val inner: Engine) : com.guixing.jixunying.client.Backend by inner {
        override suspend fun call(command: com.guixing.jixunying.model.Command): com.guixing.jixunying.model.CommandResult = when (command) {
            is com.guixing.jixunying.model.Command.SpeakPlan -> com.guixing.jixunying.model.CommandResult(data =
                com.guixing.jixunying.model.AppJson.encodeToString(com.guixing.jixunying.model.SpeechPlan.serializer(),
                    com.guixing.jixunying.model.SpeechPlan("pm", "mimo-v2.5-tts", "mimo-v2.5-tts（小米 MiMo）",
                        listOf("先说结论：这不是一份能签的合同，只是两条备忘。", "押金退还期限从哪天起算没写清，逾期也没写怎么办。"))))
            is com.guixing.jixunying.model.Command.Speak -> kotlinx.coroutines.awaitCancellation()
            else -> inner.call(command)
        }
    }

    /**
     * 截图用的数据。按意思搜那几张要另开数据目录：switches 开着的时候引擎真的会去算向量
     * （真算要联网、花钱），所以给它一个假向量服务器（semanticSwitches + zhipuUrl）。
     */
    /**
     * 截图用的假向量服务器（真算一遍要联网、要花钱、还要等）：
     * mode = hang 永远不回（看「正在算…」）、bad 回一句 Key 不对（看「出错了」）、ok 正常算（看算完的样子）。
     * base 给服务商的 baseUrl 用，引擎会往 {base}/embeddings 发。
     */
    private class FakeEmbed(private val mode: String) {
        private val port = java.net.ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port/v1"
        private val srv: EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/embeddings") {
                    when (mode) {
                        "hang" -> awaitCancellation()
                        "bad" -> call.respondText("""{"error":{"message":"Invalid API key"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        else -> {
                            val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                            val input = req["input"]!!.jsonArray.map { it.jsonPrimitive.content }
                            val dim = (req["dimensions"] as? JsonPrimitive)?.intOrNull ?: 8
                            val data = JsonArray(input.mapIndexed { i, _ ->
                                JsonObject(mapOf(
                                    "index" to JsonPrimitive(i),
                                    "embedding" to JsonArray(MutableList(dim) { 0.5f }.map { JsonPrimitive(it) }),
                                ))
                            })
                            val out = JsonObject(mapOf(
                                "data" to data,
                                "usage" to JsonObject(mapOf("prompt_tokens" to JsonPrimitive(input.sumOf { it.length }))),
                            ))
                            call.respondText(out.toString(), ContentType.Application.Json)
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
        fun stop() = srv.stop(100, 200)
    }

    /** 等引擎算到某个样子（等不到就报错：截图错了要看见，不能悄悄拍一张看不出问题的图）。 */
    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        error("等不到：$what（等了 ${ms / 1000} 秒）")
    }

    private fun seed(
        root: File,
        docsDir: File,
        semantic: com.guixing.jixunying.model.SemanticSettings = com.guixing.jixunying.model.SemanticSettings(),
        zhipuUrl: String = "https://open.bigmodel.cn/api/paas/v4",
        extraProviders: List<ProviderConfig> = emptyList(),
    ): Engine {
        val providers = listOf(
            ProviderConfig("pd", "deepseek", "DeepSeek 4.1 Flash", "https://api.deepseek.com", "sk-1234567890abcdef", listOf(ModelInfo("deepseek-flash"))),
            ProviderConfig("pm", "minimax", "MiniMax 中国版", "https://api.minimaxi.com/v1", "eyJhbGciOi.xxxxx", listOf(ModelInfo("MiniMax-M3", vision = true))),
            ProviderConfig("pz", "zhipu", "智谱开放平台", zhipuUrl, "abcd.efgh",
                listOf(ModelInfo("GLM-5.3-Flash"), ModelInfo("cogview-3-flash", tools = false, imageGen = true))),
            ProviderConfig("pi", "mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1", "sk-mimo-xxxx", listOf(ModelInfo("mimo-v2.6-flash"))),
            // 做视频（2026-10-20）只有千问AI平台 / 百炼能做：设置页的「做视频」那块要有东西可选
            ProviderConfig("pqv", "qianwen", "千问AI平台（阿里）", "https://maas.qianwenaiapi.com/compatible-mode/v1", "sk-qw-xxxx", listOf(ModelInfo("qwen3.8-max"))),
            // 2026-10-23 用 ChatGPT 登录：没登录的样子（不用 Key）
            ProviderConfig("pg", "chatgpt", "ChatGPT 订阅（用 ChatGPT 登录）", "https://api.openai.com/v1", useProxy = true),
        ) + extraProviders
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
            Conversation("c1", "租房合同要注意什么", listOf("ma", "mb"), ReplyMode.INDEPENDENT, createdAt = now - 3_600_000, updatedAt = now - 60_000, memoryTopic = "找房"),
            Conversation("c2", "微信对话", listOf("ma"), createdAt = now - 7_200_000, updatedAt = now - 3_000_000, channel = "weixin:u1@im.wechat"),
            // 2026-10-17 对话栏 = Chat + Cowork：改文件在等批准（带差异）、改过一个文件（能撤销），拖进来过一个文件夹
            Conversation("c14", "整理八字项目的资料", listOf("ma"), createdAt = now - 100_000, updatedAt = now - 1_000,
                grantedPaths = listOf("G:\\八字五行人格分析app开发")),
            Conversation("c3", "画一只在月球上喝茶的橘猫", listOf("md"), createdAt = now - 90_000_000, updatedAt = now - 86_000_000),
            Conversation("c4", "新对话", listOf("mc"), createdAt = now - 900_000_000, updatedAt = now - 800_000_000),
            Conversation("c6", "聊了很久的对话", listOf("ma"), createdAt = now - 950_000_000, updatedAt = now - 900_000_000),
            Conversation("c7", "会审：先学 Python 还是 Java", listOf("ma", "mb"), ReplyMode.INDEPENDENT, createdAt = now - 960_000_000, updatedAt = now - 950_000_000,
                council = true),
            // Code 栏：对着一个项目文件夹写代码的会话（成员为空，code 不为空）
            Conversation("c8", "我的项目", emptyList(), createdAt = now - 900_000, updatedAt = now - 60_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, "pd", "deepseek-flash")),
            // 用户按了停止的那条：侧栏要写「停下了」（灰的），不是「做完」
            Conversation("c9", "旧项目", emptyList(), createdAt = now - 600_000, updatedAt = now - 20_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "旧项目").absolutePath, "pd", "deepseek-flash")),
            // 手机发起建的（第二期 2026-10-13）：电脑上侧栏和会话上面都要标「手机发起」
            Conversation("c10", "手机建的会话", emptyList(), createdAt = now - 300_000, updatedAt = now - 10_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, "pd", "deepseek-flash", fromPhone = true)),
            // 2026-10-17：读工作区外面的文件在等批准（带「加进工作区」）、命令在等批准（带「同样开头不再问」）
            Conversation("c11", "读别处的资料", emptyList(), createdAt = now - 200_000, updatedAt = now - 5_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, "pd", "deepseek-flash",
                    extraPaths = listOf("G:\\资料\\接口文档"), allowedCommands = listOf("git status", "web:docs.python.org"),
                    team = listOf(com.guixing.jixunying.model.TeamSeat("dev", com.guixing.jixunying.model.CodeSpec.CLAUDE_CODE, "opus"),
                        com.guixing.jixunying.model.TeamSeat("review", "pd", "deepseek-flash")))),
            Conversation("c12", "跑测试", emptyList(), createdAt = now - 150_000, updatedAt = now - 4_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, "pd", "deepseek-flash")),
            // 2026-10-22：做了两步、正在想下一步（没在等批准）：最下面要有「还在做…（已经 N 秒）」
            Conversation("c16", "还在做的会话", emptyList(), createdAt = now - 100_000, updatedAt = now - 2_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, com.guixing.jixunying.model.CodeSpec.CLAUDE_CODE, "")),
            // 2026-10-18 外部 Claude Code：交给用户自己的 Claude Code（订阅）做，改文件在等批准
            Conversation("c15", "交给 Claude Code", emptyList(), createdAt = now - 120_000, updatedAt = now - 3_000,
                code = com.guixing.jixunying.model.CodeSpec(File(root, "我的项目").absolutePath, com.guixing.jixunying.model.CodeSpec.CLAUDE_CODE, "opus",
                    externalSession = "s-1")),
        )
        val st = Storage(root)
        st.saveState(AppState(
            providers = providers, members = members, conversations = convs,
            memories = listOf(
                MemoryItem("m1", "在北京做产品经理", "关于我", "手动添加", now, now, pinned = true),
                MemoryItem("m2", "希望回答先给结论，少用术语", "偏好", "租房合同要注意什么", now, now),
                MemoryItem("m3", "正在找房，预算每月 5000 以内", "事实", "租房合同要注意什么", now, now, topic = "找房",
                    convId = "c1", messageId = "x", quote = "我最近在找房，每月预算 5000 以内"),
                MemoryItem("m4", "成员不再限定四家，可以加别的模型", "事实", "整理八字项目的资料", now - 1000, now - 1000, topic = "AI集训营",
                    convId = "c-gone", quote = "成员固定四家这条禁令撤掉吧"),
            ),
            settings = Settings(proxy = "127.0.0.1:10809", docs = DocSettings(folders = listOf(docsDir.path)), semantic = semantic),            hostId = "h1", pairingSecret = Base64.getEncoder().encodeToString(ByteArray(16) { 1 }),
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
                // 语音：读出来的字数、听写的秒数要按模型累加显示（1.7.0）
                com.guixing.jixunying.model.UsageRecord(now - 5_000_000L, com.guixing.jixunying.model.UsageKinds.TTS, "pi", "小米 MiMo", "mimo", "mimo-v2.5-tts",
                    units = 1_240.0, unit = com.guixing.jixunying.model.SpeechUnits.CHAR),
                com.guixing.jixunying.model.UsageRecord(now - 4_400_000L, com.guixing.jixunying.model.UsageKinds.TTS, "pi", "小米 MiMo", "mimo", "mimo-v2.5-tts",
                    units = 860.0, unit = com.guixing.jixunying.model.SpeechUnits.CHAR),
                com.guixing.jixunying.model.UsageRecord(now - 3_000_000L, com.guixing.jixunying.model.UsageKinds.ASR, "pi", "小米 MiMo", "mimo", "mimo-v2.5-asr",
                    units = 95.0, unit = com.guixing.jixunying.model.SpeechUnits.SECOND),
                // MiniMax 按字符算钱（一个汉字 2 个字符），这行要写清「汉字算 2 个」
                com.guixing.jixunying.model.UsageRecord(now - 2_500_000L, com.guixing.jixunying.model.UsageKinds.TTS, "pm", "MiniMax 中国版", "minimax", "speech-2.8-turbo",
                    units = 640.0, unit = com.guixing.jixunying.model.SpeechUnits.CHAR),
                // 超过一分钟：显示成「共 M 分 S 秒」
                com.guixing.jixunying.model.UsageRecord(now - 2_000_000L, com.guixing.jixunying.model.UsageKinds.ASR, "pm", "MiniMax 中国版", "minimax", "asr-1.0",
                    units = 305.0, unit = com.guixing.jixunying.model.SpeechUnits.SECOND),
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
        // 2026-10-22：Code 里 AI 看过的手机截图（竖图），画几块色块看比例对不对
        val shotImg = java.awt.image.BufferedImage(540, 1170, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.color = java.awt.Color(0xF4, 0xF1, 0xEA); g.fillRect(0, 0, 540, 1170)
            g.color = java.awt.Color(0x1F, 0x3B, 0x2D); g.fillRoundRect(30, 120, 480, 300, 40, 40)
            g.color = java.awt.Color.WHITE; g.fillRoundRect(30, 470, 480, 140, 30, 30); g.fillRoundRect(30, 640, 480, 140, 30, 30)
            g.dispose()
        }
        val shotBytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(shotImg, "png", it) }.toByteArray()
        st.putFile(Attachment("a5", "01-home.png", "image/png", shotBytes.size.toLong(), AttachmentKind.IMAGE, note = "Code 栏里 AI 看过的图"), shotBytes, null)
        st.saveMessages("c14", listOf(
            Message("u14", "c14", Role.USER, USER_ID, "把这个文件夹里的需求整理成一份清单，放到 G:\\资料\\清单.md\n\n附上的文件 / 文件夹（在这台电脑上，可以直接用 list_folder / read_document 读）：\n- G:\\八字五行人格分析app开发", createdAt = now - 60_000),
            Message("r14", "c14", Role.AI, "ma", "先看了文件夹里的需求文档，又把 README 里的错字改了。清单要写到 G 盘资料文件夹，那里不在这个对话的文件夹里，等你点一下。",
                createdAt = now - 30_000, status = MsgStatus.STREAMING, modelLabel = "deepseek-flash",
                tools = listOf(
                    ToolStep("doc", "看了文件夹 八字五行人格分析app开发 · 12 项"),
                    ToolStep("doc", "读《需求说明.docx》"),
                    ToolStep("edit", "G:\\八字五行人格分析app开发\\README.md", id = "w1", output = "--- a/README.md\n+++ b/README.md\n@@ -1,2 +1,2 @@\n-# 八字五行人格分析app\n+# 八字五行人格分析 App\n 需求见 需求说明.docx", note = "替换 README.md 里的一段",
                        change = FileChange("w1", "G:\\八字五行人格分析app开发\\README.md", added = 1, removed = 1)),
                    ToolStep("write", "G:\\资料\\清单.md", id = "w2", output = "--- /dev/null\n+++ b/清单.md\n@@ -0,0 +1,4 @@\n+# 需求清单\n+1. 输入生辰，排出八字\n+2. 五行强弱分析\n+3. 人格解读报告", note = "新建这个文件",
                        approval = "waiting", scopeDir = "G:\\资料"),
                )),
        ))
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
        // 会审：两位各答 → 两份互评（默认折叠）→ 主席汇总
        val tc = now - 955_000_000L
        st.saveMessages("c7", listOf(
            Message("u7", "c7", Role.USER, USER_ID, "零基础想转行做数据分析，先学 Python 还是 Java？", createdAt = tc),
            Message("r7a", "c7", Role.AI, "ma", "**先学 Python。** 数据分析的主流工具（pandas、Jupyter）都是 Python 的，入门快。", createdAt = tc + 1000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）"),
            Message("r7b", "c7", Role.AI, "mb", "**看目标岗位。** 去大厂做数据开发要会 Java / Scala；只做分析报表，Python 加 SQL 就够。", createdAt = tc + 1100, modelLabel = "MiniMax-M3（MiniMax 中国版）"),
            Message("v7a", "c7", Role.AI, "ma", "阿麦（回答乙）提到 SQL，这点很重要，我（回答甲）漏了。\n\n名次：乙 > 甲", createdAt = tc + 2000, modelLabel = "deepseek-flash · 互评", stage = "review"),
            Message("v7b", "c7", Role.AI, "mb", "阿德（回答甲）的结论对，但没说 SQL。\n\n名次：甲 > 乙", createdAt = tc + 2100, modelLabel = "MiniMax-M3 · 互评", stage = "review"),
            Message("h7", "c7", Role.AI, "tool:chair", "**结论：先学 Python 和 SQL。** 只做数据分析不需要 Java。\n\n- **首轮分歧**：两种说法——阿德（回答甲）说先学 Python；阿麦（回答乙）说看岗位。\n" +
                "- **依据**：分析岗的日常工具是 pandas、SQL、Excel；Java 主要在数据开发岗。\n- **少数派意见**：阿麦提醒「看岗位」有道理——想进大厂数据开发，后面要补 Java。\n- **还不确定**：你的目标岗位。\n\n---\n**互评名次**（2 份互评的平均名次，越小越好）：阿德 1.5；阿麦 1.5",
                createdAt = tc + 3000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）· 主席", stage = "chair"),
        ))
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
        val clip = Attachment("a4", "视频_123456.mp4", "video/mp4", 1_225_913, AttachmentKind.DOCUMENT, note = "2 秒 · 480P · wan3.0-video", generated = true)
        st.putFile(clip, ByteArray(16), null)
        st.saveMessages("c3", listOf(
            Message("u3", "c3", Role.USER, USER_ID, "画一只在月球上喝茶的橘猫", createdAt = now - 86_100_000),
            Message("r3", "c3", Role.AI, "md", "画好了。", attachments = listOf(cat), tools = listOf(ToolStep("image", "月球上喝茶的橘猫，水彩风格")), createdAt = now - 86_000_000,
                modelLabel = "mimo-v2.6-flash（小米 MiMo）"),
            Message("u3b", "c3", Role.USER, USER_ID, "再做一段它伸懒腰的视频", createdAt = now - 85_900_000),
            Message("r3b", "c3", Role.AI, "md", "做好了，点一下就能播放。", attachments = listOf(clip),
                tools = listOf(ToolStep("video", "橘猫在窗台上伸懒腰，午后阳光，镜头缓慢推近", note = "2 秒 · 480P · wan3.0-video")), createdAt = now - 85_800_000,
                modelLabel = "mimo-v2.6-flash（小米 MiMo）"),
        ))
        // Code 会话（c8）：一条做完了、带一处改动；一条正等着批准改 README
        File(root, "我的项目").mkdirs()
        val diffMain = """
            |--- a/src/main.py
            |+++ b/src/main.py
            |@@ -1,7 +1,10 @@
            | import os
            |+import sys
            |
            |-def main():
            |-    print("hello")
            |+def main(argv):
            |+    print("hello", argv)
            |
            | if __name__ == "__main__":
            |-    main()
            |+    main(sys.argv[1:])
        """.trimMargin()
        val diffReadme = """
            |--- a/README.md
            |+++ b/README.md
            |@@ -1,3 +1,3 @@
            | # 我的项目
            |
            |-跑法：python src/main.py
            |+跑法：python src/main.py 参数1 参数2
        """.trimMargin()
        // 项目文件夹里真实的文件，外加 s83 的备份：差异查看器里要有真差异可看
        File(root, "我的项目/src").mkdirs()
        File(root, "我的项目/src/main.py").writeText("import os\nimport sys\n\ndef main(argv):\n    print(\"hello\", argv)\n\nif __name__ == \"__main__\":\n    main(sys.argv[1:])\n", Charsets.UTF_8)
        File(root, "我的项目/README.md").writeText("# 我的项目\n\n跑法：python src/main.py\n", Charsets.UTF_8)
        File(root, "code/c8").mkdirs()
        File(root, "code/c8/s83.orig").writeText("import os\n\ndef main():\n    print(\"hello\")\n\nif __name__ == \"__main__\":\n    main()\n", Charsets.UTF_8)
        // 2026-10-23：边做边说的话按 textAt 插在步骤之间
        val said8 = listOf("先看看项目里有什么。", "\n\n入口只有一个 main()，把参数传进去就行，改这一段。", "\n\n改完跑一下。",
            "\n\n改好了：**main.py 现在能接收命令行参数**。\n\n- 加了 `sys` 导入；\n- `main()` 改成 `main(argv)`，把参数打进输出；\n- 原来的调用改成 `main(sys.argv[1:])`。\n\n跑了一次 `python src/main.py A B`，退出码 0，输出 `hello ['A', 'B']`。")
        fun at8(n: Int) = said8.take(n).joinToString("").length
        val r8bSaid = "README 里的用法也得跟着改，我先看一眼再改。"
        st.saveMessages("c8", listOf(
            Message("u8", "c8", Role.USER, USER_ID, "把 src/main.py 改成能接收命令行参数，改完跑一下看看。", createdAt = now - 900_000),
            Message("r8", "c8", Role.AI, "tool:code", said8.joinToString(""),
                createdAt = now - 880_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）",
                tools = listOf(
                    ToolStep("list", ".", output = "src/\nREADME.md\nrequirements.txt", id = "s81", textAt = at8(1)),
                    ToolStep("read", "src/main.py", id = "s82", textAt = at8(1),
                        output = "import os\n\ndef main():\n    print(\"hello\")\n\nif __name__ == \"__main__\":\n    main()"),
                    ToolStep("edit", "src/main.py", id = "s83", output = diffMain, note = "替换 src/main.py 里的一段", textAt = at8(2),
                        change = FileChange("s83", "src/main.py", added = 6, removed = 3)),
                    ToolStep("run", "python src/main.py A B", id = "s84", output = "hello ['A', 'B']", note = "跑完了（退出码 0）", textAt = at8(3)),
                ), usage = Usage(3200, 800, 900, 12_000)),
            Message("r8b", "c8", Role.AI, "tool:code", r8bSaid,
                createdAt = now - 60_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）", status = MsgStatus.STREAMING,
                tools = listOf(
                    ToolStep("read", "README.md", id = "s85", textAt = r8bSaid.length, output = "# 我的项目\n\n跑法：python src/main.py"),
                    ToolStep("edit", "README.md", id = "s86", textAt = r8bSaid.length, output = diffReadme, note = "替换 README.md 里的一段", approval = "waiting"),
                )),
        ))
        // 用户按了停止的那条（c9）：最后一条回答是 STOPPED，侧栏的状态要写「停下了」
        File(root, "旧项目").mkdirs()
        File(root, "旧项目/README.md").writeText("# 旧项目\n", Charsets.UTF_8)
        // 2026-10-22「提交」弹窗：旧项目是没有远端的仓库，有一处没提交的改动（我的项目不是仓库）
        fun git(vararg a: String) = ProcessBuilder(listOf("git") + a).directory(File(root, "旧项目")).redirectErrorStream(true).start().waitFor()
        git("init", "-b", "main"); git("config", "user.email", "t@t"); git("config", "user.name", "t")
        git("add", "-A"); git("commit", "-m", "第一次")
        File(root, "旧项目/README.md").appendText("跑法：python main.py\n", Charsets.UTF_8)
        File(root, "code/c9").mkdirs()
        File(root, "code/c9/s91.orig").writeText("旧的 README 内容\n", Charsets.UTF_8)
        st.saveMessages("c9", listOf(
            Message("u9", "c9", Role.USER, USER_ID, "把 README 里的用法补一下。", createdAt = now - 40_000),
            Message("r9", "c9", Role.AI, "tool:code", "先看一眼 README 现在写了什么。",
                createdAt = now - 30_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）", status = MsgStatus.STOPPED,
                tools = listOf(ToolStep("read", "README.md", id = "s91", output = "# 旧项目"))),
        ))
        // 手机发起的那个会话（c10）：一条做完了的记录，侧栏的状态才不是「新」
        st.saveMessages("c10", listOf(
            Message("u10", "c10", Role.USER, USER_ID, "手机上让它把 README 补一下。", createdAt = now - 300_000),
            Message("r10", "c10", Role.AI, "tool:code", "补好了，README 里加上了安装和运行两节。",
                createdAt = now - 290_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）",
                tools = listOf(ToolStep("edit", "README.md", id = "s101", output = diffReadme, change = FileChange("s101", "README.md", added = 4, removed = 1)))),
        ))
        st.saveMessages("c11", listOf(
            Message("u11", "c11", Role.USER, USER_ID, "照 G:\\资料\\设计稿\\登录页说明.md 把登录页改一下", createdAt = now - 20_000),
            Message("r11", "c11", Role.AI, "tool:code", "先读一下那份说明。", status = MsgStatus.STREAMING,
                createdAt = now - 10_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）",
                tools = listOf(ToolStep("read", "G:\\资料\\设计稿\\登录页说明.md", id = "s111", note = "读工作区外面的文件",
                    approval = "waiting", scopeDir = "G:\\资料\\设计稿"))),
        ))
        st.saveMessages("c12", listOf(
            Message("u12", "c12", Role.USER, USER_ID, "跑一下测试", createdAt = now - 20_000),
            Message("r12", "c12", Role.AI, "tool:code", "", status = MsgStatus.STREAMING,
                createdAt = now - 10_000, modelLabel = "deepseek-flash（DeepSeek 4.1 Flash）",
                tools = listOf(ToolStep("todo", "待办", id = "s120", output = "[x] 找到测试命令\n[>] 跑测试\n[ ] 看失败的用例"),
                    // 2026-10-22：长命令（像试用时起模拟器那条）整条换行显示，不再截成一行
                    ToolStep("run", "python -m pytest -q tests/test_parser.py tests/test_ledger.py --maxfail=1 --durations=10 -p no:cacheprovider " +
                        "--basetemp=C:\\Users\\me\\AppData\\Local\\Temp\\pytest-of-me\\run-42 -k \"parse and not slow\"", id = "s121", approval = "waiting",
                        prefix = "python -m pytest", note = "自动检查拦下：要写到工作区外面的临时目录，先问一下"))),
        ))
        st.saveMessages("c16", listOf(
            Message("u16", "c16", Role.USER, USER_ID, "把单元测试跑一遍，看看有没有坏的", createdAt = now - 80_000),
            Message("r16", "c16", Role.AI, "tool:code", "", status = MsgStatus.STREAMING, createdAt = now - 75_000, modelLabel = "Claude Code",
                tools = listOf(ToolStep("read", "README.md", id = "s160", output = "# 我的项目"),
                    ToolStep("run", "python -m pytest -q", id = "s161", output = "12 passed in 3.2s"))),
        ))
        st.saveMessages("c15", listOf(
            Message("u15", "c15", Role.USER, USER_ID, "把首页标题改成「欢迎」", createdAt = now - 20_000),
            // 2026-10-22：带长文字列的表格（像试用记账本时的 bug 清单），看列宽是不是按内容分的
            Message("r15", "c15", Role.AI, "tool:code", "先看了首页文件，标题在第 3 行。顺带列一下看到的问题：\n\n" +
                "| # | 是什么、怎么触发 | 影响 | 测试 |\n|---|---|---|---|\n" +
                "| 1 | 固定费用按模板里的日子入账（比如 1 号），银行几天后（比如 5 号）才扣款。自动记账只查「同一天同额」，两笔对不上，房租就记了两遍。 | **重复记**，而且是大额 | `b1_recurringRent` |\n" +
                "| 2 | 一条通知里有好几个金额时只取第一个：「原价¥25，实付¥18」会记成 25。 | 记错金额 | `b6_firstAmount` |",
                status = MsgStatus.STREAMING,
                createdAt = now - 10_000, modelLabel = "Claude Code（Opus）",
                tools = listOf(ToolStep("read", "src/index.html", id = "s150", output = "<h1>Hello</h1>"),
                    ToolStep("run", "git status", id = "s151", output = "On branch main"),
                    // 2026-10-22：它看过的截图（步骤上挂一份副本，默认展开）
                    ToolStep("read", "C:\\Users\\me\\AppData\\Local\\Temp\\shots\\01-home.png", id = "s153", image = "a5"),
                    ToolStep("edit", "src/index.html", id = "s152", approval = "waiting", note = "改这个文件",
                        output = "--- a/src/index.html\n+++ b/src/index.html\n@@ -3,1 +3,1 @@\n-<h1>Hello</h1>\n+<h1>欢迎</h1>"))),
        ))
        // 备份攒过头（1 GB 以上，设置 → Code 栏 顶上要提醒）：写一个稀疏文件占位，不真占盘。
        // 2026-10-22：以前用 RandomAccessFile.setLength，NTFS 上不是稀疏文件，每次真占 1 GB 的 C 盘；要 CREATE_NEW + SPARSE 才是
        val big = File(root, "code/c8/big.orig").toPath()
        java.nio.file.Files.deleteIfExists(big)
        java.nio.file.Files.newByteChannel(big, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.SPARSE).use { it.position(1_100_000_000L - 1).write(java.nio.ByteBuffer.wrap(byteArrayOf(0))) }
        val e = Engine(Storage(root))
        // 「提交」弹窗要真查 git 状态（2026-10-22）
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.weixin = com.guixing.jixunying.engine.WeixinBridge(e, File(root, "weixin"))
        e.rescanDocs()
        repeat(100) { if (e.state.docs.count < 3 || e.state.docs.scanning) Thread.sleep(50) }
        return e
    }

    /** 测试用的临时文件夹：登记下来，测试结束（过没过都算）由 cleanTmp 删掉。以前只在最后一行删，断言失败就留在 C 盘（10-22 攒了 9.2 GB）。 */
    private val tmps = mutableListOf<File>()
    private fun tempDir(prefix: String) = kotlin.io.path.createTempDirectory(prefix).toFile().also { tmps += it }

    @kotlin.test.AfterTest
    fun cleanTmp() {
        tmps.forEach { it.deleteRecursively() }
        tmps.clear()
    }

    /** frames：要等后台查东西的（比如提交弹窗查 git 状态）多渲染几帧，每帧约 60 毫秒。 */
    private fun shoot(name: String, w: Int, h: Int, density: Float, hub: Hub, platform: Platform, start: String?, frames: Int = 14) {
        val scene = ImageComposeScene(w, h, Density(density)) { App(hub, platform, start) }
        var img: org.jetbrains.skia.Image? = null
        for (i in 0 until frames) {
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

    /**
     * 输入框在各种手机屏幕上都放得下（2026-10-22 用户：「需要兼容大多数手机的屏幕比例才行」）。
     * 范围按安卓规范定，资料见 docs/参考资料.md「手机屏幕尺寸和系统字体」：
     * - 宽：全屏最窄 320dp（CDD 7.1.1.3），分屏窗口最窄 220dp（CDD 3.8.14），大屏手机调小显示大小到 480dp 左右，折叠屏内屏 840dp；
     * - 高：竖屏 20:9；键盘弹出后剩 280～390dp；横屏 296dp；横屏又开着键盘剩 150～190dp；分屏又开着键盘 200dp；
     * - 字体：系统字体最大 200%（这里线性放大，比安卓的非线性更大，是最坏情况）。
     * 看的是：发送键整个在窗口里、没被压小；那一排有停止键时它也整个在、在发送键左边；「+」完整；写字的地方没被压没（至少 16dp，空着时一行在 100% 字体下是 19dp）；
     * 左边的按钮不盖到停止和发送上（横着滑出去、被裁掉的部分不算）。
     * 平时就跑（不用 JXY_SHOTS）；设了 JXY_SHOTS 时把最极端的几种画成 fit-*.png。
     */
    @Test
    fun phoneComposerFits() {
        val tmp = tempDir("jxy-fit")
        val hub = Hub(seed(File(tmp, "data"), File(tmp, "文档").apply { mkdirs() }))
        // 真手机能录音，话筒也在那一排
        val phone = FakeAvPlatform(PhonePlatform())
        val desktop = DesktopPlatform(fileLog = false) { null }
        val fonts = listOf(1f, 1.5f, 2f)
        val problems = mutableListOf<String>()
        var cases = 0
        // 竖屏：c1 群聊；c14 有一条正在回答（出「停止」）；#draw 直接画图开着；#record 正在录音
        val tall = listOf("conv:c1", "conv:c1#draw", "conv:c14", "conv:c14#draw", "conv:c14#record")
        for (wDp in listOf(220, 320, 360, 412, 480, 840)) for (font in fonts) for (start in tall) {
            if (fitCase(hub, phone, wDp, if (wDp >= 600) wDp * 6 / 5 else wDp * 20 / 9, font, start, problems)) cases++
        }
        // 矮窗口：键盘弹出、横屏、横屏又开着键盘（915 宽是大屏手机横过来，带侧栏）、分屏又开着键盘；输入框里写了很长的草稿
        val short = listOf(320 to 280, 360 to 330, 412 to 390, 569 to 296, 640 to 150, 915 to 190, 220 to 200)
        for ((wDp, hDp) in short) for (font in fonts) for (start in listOf("conv:c1#longdraft", "conv:c14#longdraft+draw", "conv:c14#record")) {
            if (fitCase(hub, phone, wDp, hDp, font, start, problems)) cases++
        }
        // Code 栏的输入框（手机遥控时也是这一个，这里用电脑的台面）：c16 正在跑，出「上一条还在做」那行字
        for ((wDp, hDp) in listOf(320 to 711, 360 to 800, 412 to 915, 360 to 330, 640 to 150)) for (font in fonts) for (start in listOf("code:c16", "code:c8")) {
            if (fitCase(hub, desktop, wDp, hDp, font, start, problems, plus = false)) cases++
        }
        kotlin.test.assertTrue(problems.isEmpty(), "量了 $cases 种，${problems.size} 处不对：\n" + problems.joinToString("\n"))
        kotlin.test.assertEquals(6 * 3 * 5 + 7 * 3 * 3 + 5 * 3 * 2, cases)
    }

    /**
     * 设置首页、每个设置页、侧栏在窄屏和大字体下不出界（2026-10-24，当前任务「大字体、窄屏下的其它页面」）：
     * 220dp（分屏最窄）、320dp（全屏最窄）、360dp × 字体 100% / 200%。看的是：文字、按钮的右边不超出窗口（在横着滑的区域里的不算），
     * 能点的东西不被压成 0 宽。平时就跑。
     */
    @Test
    fun phoneSettingsFit() {
        val tmp = tempDir("jxy-fit-settings")
        val hub = Hub(seed(File(tmp, "data"), File(tmp, "文档").apply { mkdirs() }))
        val phone = PhonePlatform()
        val pages = listOf("settingshome", "conv:c1#drawer") + com.guixing.jixunying.ui.SettingsTab.entries.map { "settings:${it.name}" } +
            listOf("settings:APPEARANCE#tune")
        val problems = mutableListOf<String>()
        var cases = 0
        for (wDp in listOf(220, 320, 360)) for (font in listOf(1f, 2f)) for (start in pages) {
            if (outCase(hub, phone, wDp, wDp * 20 / 9, font, start, problems)) cases++
        }
        kotlin.test.assertEquals(3 * 2 * pages.size, cases)
        kotlin.test.assertTrue(problems.isEmpty(), "量了 $cases 种，${problems.size} 处不对：\n" + problems.distinct().take(80).joinToString("\n"))
    }

    private fun outCase(hub: Hub, platform: Platform, wDp: Int, hDp: Int, font: Float, start: String, problems: MutableList<String>): Boolean {
        val d = 2f
        val w = wDp * d
        val scene = ImageComposeScene(w.toInt(), (hDp * d).toInt(), Density(d, font)) { App(hub, platform, start) }
        try {
            repeat(10) { i -> scene.render(i * 120_000_000L); Thread.sleep(15) }
            val all = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { all += n; n.children.forEach { walk(it) } }
            scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
            fun inHScroll(n: androidx.compose.ui.semantics.SemanticsNode) = generateSequence(n.parent) { it.parent }
                .any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange) }
            fun label(n: androidx.compose.ui.semantics.SemanticsNode) = (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty() +
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }).joinToString("/").take(24)
            val name = "${wDp}dp、字体 ${(font * 100).toInt()}%、$start"
            for (n in all) {
                val hasText = n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
                val clickable = n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                if (!hasText && !clickable) continue
                // 不用 boundsInRoot：它被上级裁过，挤出去又被裁掉的量不出来；用没裁过的位置和大小
                val left = n.positionInRoot.x
                val right = left + n.size.width
                if (n.size.height <= 0 || inHScroll(n)) continue
                if (right > w + 1f) problems += "$name：「${label(n)}」右边出了窗口 ${((right - w) / d).toInt()}dp"
                if (clickable && n.size.width < 1) problems += "$name：「${label(n)}」被压成 0 宽"
            }
            return true
        } finally { scene.close() }
    }

    /** 量一种尺寸：离屏画出来，从界面的语义树里找发送键、停止、「+」、写字的地方，不对的写进 problems。量完返回 true。 */
    private fun fitCase(hub: Hub, platform: Platform, wDp: Int, hDp: Int, font: Float, start: String, problems: MutableList<String>, plus: Boolean = true): Boolean {
        val d = 2f
        val w = wDp * d
        val h = hDp * d
        val name = "${wDp}×${hDp}dp、字体 ${(font * 100).toInt()}%、$start"
        val sendNames = setOf("发送", "画图", "做视频")
        val scene = ImageComposeScene(w.toInt(), h.toInt(), Density(d, font)) { App(hub, platform, start) }
        try {
            fun nodes(): List<androidx.compose.ui.semantics.SemanticsNode> {
                val all = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
                fun walk(n: androidx.compose.ui.semantics.SemanticsNode) { all += n; n.children.forEach { walk(it) } }
                scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
                return all
            }
            fun descs(n: androidx.compose.ui.semantics.SemanticsNode) =
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty()
            fun texts(n: androidx.compose.ui.semantics.SemanticsNode) =
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }
            fun label(n: androidx.compose.ui.semantics.SemanticsNode) = (descs(n) + texts(n)).joinToString("/").take(30)
            // 等对话打开、输入框出来，再多画几帧让草稿、录音、画图这些状态落定
            var frame = 0L
            var img = scene.render(0)
            while (frame < 60 && nodes().none { n -> descs(n).any { it in sendNames } }) { frame++; img = scene.render(frame * 120_000_000L); Thread.sleep(20) }
            repeat(4) { frame++; img = scene.render(frame * 120_000_000L); Thread.sleep(20) }
            val all = nodes()
            val clickable = all.filter { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
            val sends = all.filter { n -> descs(n).any { it in sendNames } }
            if (sends.size != 1) { problems += "$name：找到 ${sends.size} 个发送键"; return false }
            val send = sends[0].boundsInRoot
            if (send.width < 36 * d - 1 || send.height < 36 * d - 1) problems += "$name：发送键被压小（${send.width / d}×${send.height / d}dp）"
            if (send.left < 0 || send.right > w || send.top < 0 || send.bottom > h) problems += "$name：发送键出了窗口（${send.left / d},${send.top / d}～${send.right / d},${send.bottom / d}dp）"
            // 只认和发送键在同一排的停止（Code 栏顶上也有停止，不算）
            val stops = clickable.filter { ("停止" in texts(it) || "停止" in descs(it)) && it.boundsInRoot.center.y in send.top..send.bottom }
            if (start.startsWith("conv:c14") != (stops.size == 1)) problems += "$name：那一排找到 ${stops.size} 个停止键"
            stops.forEach { s ->
                val b = s.boundsInRoot
                if (b.left < 0 || b.right > send.left + 0.5f) problems += "$name：停止键不在发送键左边、或出了窗口（${b.left / d}～${b.right / d}dp）"
                if (b.height < 24 * d) problems += "$name：停止键被压扁（高 ${b.height / d}dp）"
            }
            if (plus) {
                val p = all.filter { n -> descs(n).any { it.startsWith("图片、文件") } }
                if (p.size != 1 || p[0].boundsInRoot.width < 32 * d - 1 || p[0].boundsInRoot.left < 0) problems += "$name：「+」看不全"
            }
            val fields = all.filter { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) }
            if (fields.none { it.boundsInRoot.height >= 16 * d && it.boundsInRoot.bottom <= send.top + 1 }) problems += "$name：写字的地方被压没了（${fields.map { it.boundsInRoot.height / d }}dp）"
            // 左边的按钮不能盖到停止、发送上（它们的上级容器不算）
            val guarded = sends + stops
            val ancestors = guarded.flatMap { g -> generateSequence(g.parent) { it.parent }.map { it.id }.toList() }.toSet()
            for (n in clickable) {
                if (n.id in ancestors || guarded.any { it.id == n.id }) continue
                for (g in guarded) {
                    val i = n.boundsInRoot.intersect(g.boundsInRoot)
                    if (i.width > 1 && i.height > 1) problems += "$name：${label(n)} 盖到了 ${label(g)} 上"
                }
            }
            if (out != null && font == 2f && (wDp <= 320 || hDp <= 200)) {
                out.mkdirs()
                File(out, "fit-${wDp}x$hDp-${start.replace(':', '-').replace('#', '-').replace('+', '-')}.png")
                    .writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            return true
        } finally {
            scene.close()
        }
    }

    /**
     * Code 任务在跑时（2026-10-23，修补清单第 9 条）：往上翻着看某一步，后面再来几步也不把它挤走、不拽回底下；
     * 停在最底下时新的一步自己露出来。平时就跑（不用 JXY_SHOTS）。
     */
    @Test
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun codeListHoldsWhileRunning() {
        val tmp = tempDir("jxy-code-scroll")
        val engine = seed(File(tmp, "data"), File(tmp, "文档").apply { mkdirs() })
        val hub = Hub(engine)
        val desktop = DesktopPlatform(fileLog = false) { null }
        // 引擎改消息的入口是私有的，测试里借反射用一下（和真跑时 CodeAgent 走的是同一个）
        val update = Engine::class.java.declaredMethods.first {
            it.name == "updateMessage" && it.parameterCount == 4 && it.parameterTypes[3] == kotlin.jvm.functions.Function1::class.java
        }.apply { isAccessible = true }
        var n = 0
        fun moreSteps() {
            repeat(3) {
                n++
                val f: (Message) -> Message = { m -> m.copy(content = m.content + "\n\n第 $n 步。",
                    tools = m.tools + ToolStep("read", "src/new$n.py", id = "x$n", output = "x", textAt = m.content.trimStart().length)) }
                update.invoke(engine, "c8", "r8b", true, f)
            }
        }
        fun scene() = ImageComposeScene(1280, 820, Density(1f)) { App(hub, desktop, "code:c8") }
        fun nodes(scene: ImageComposeScene): List<androidx.compose.ui.semantics.SemanticsNode> {
            val all = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
            fun walk(x: androidx.compose.ui.semantics.SemanticsNode) { all += x; x.children.forEach { walk(it) } }
            scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
            return all
        }
        fun top(scene: ImageComposeScene, text: String) = nodes(scene).firstOrNull { x ->
            x.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().any { text in it.text }
        }?.boundsInRoot?.top
        var t = 0L
        fun frames(scene: ImageComposeScene, k: Int = 10) = repeat(k) { scene.render(t); t += 120_000_000L; Thread.sleep(40) }

        // 一、往上翻到上一条回答的「改了 src/main.py」，再来三步，它的位置不动
        val a = scene()
        try {
            frames(a, 14)
            var guard = 0
            while ((top(a, "改了 src/main.py")?.let { it < 150f || it > 600f } ?: true) && guard++ < 40) {
                a.sendPointerEvent(PointerEventType.Scroll, Offset(800f, 400f), scrollDelta = Offset(0f, -2f))
                frames(a, 3)
            }
            val before = kotlin.test.assertNotNull(top(a, "改了 src/main.py"), "翻不到那一步")
            moreSteps()
            frames(a)
            val after = kotlin.test.assertNotNull(top(a, "改了 src/main.py"), "来了新的几步以后那一步不见了（被拽回底下了）")
            kotlin.test.assertTrue(kotlin.math.abs(after - before) < 2f, "那一步从 $before 挪到了 $after")
        } finally { a.close() }

        // 二、停在最底下：新来的一步看得见
        val b = scene()
        try {
            frames(b, 14)
            moreSteps()
            frames(b)
            val y = kotlin.test.assertNotNull(top(b, "读了 src/new$n.py"), "最新的一步没露出来")
            kotlin.test.assertTrue(y in 0f..820f, "最新的一步在 $y，不在窗口里")
        } finally { b.close() }
    }

    /** 打开对话停在哪：要停在最新一条的最后一行（10-10 用户报：每次打开都在最上面）。 */
    @Test
    fun scrollShots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = tempDir("jxy-scroll")
        val hub = Hub(seed(File(tmp, "data"), File(tmp, "文档").apply { mkdirs() }))
        val desktop = DesktopPlatform(fileLog = false) { null }
        // 侧栏位置（1280×820）：绳子剪几段 299、租房合同 349、聊了很久的对话 579；聊天区中间 (800, 400)
        // 10-14 侧栏改版后列表上面多了三张卡片，原来的 210 / 260 / 490 会点到卡片上（打开「我的文档」，后台扫描从别的线程改状态，截图偶发报错）
        val c5 = Offset(140f, 299f); val c1 = Offset(140f, 349f); val c6 = Offset(140f, 579f); val chat = Offset(800f, 400f)
        val up = List(8) { chat to -30f }
        shootSteps("scroll-c1-then-long", hub, desktop, "conv:c1", listOf(c6 to 0f))
        shootSteps("scroll-up-then-c5", hub, desktop, "conv:c6", up + listOf(c5 to 0f))
        shootSteps("scroll-up-then-c1-then-long", hub, desktop, "conv:c6", up + listOf(c1 to 0f, c6 to 0f))
        shootSteps("scroll-long-up", hub, desktop, "conv:c6", up)
        shoot("scroll-focus-old", 1280, 820, 1f, hub, desktop, "conv:c6@u6-3")
        shoot("scroll-focus-old-phone", 824, 1784, 2f, hub, PhonePlatform(), "conv:c6@u6-3")
        tmp.deleteRecursively()
    }

    /**
     * 2026-10-28：新开软件的新对话首页（logo + 问候 + 选人 + 输入框）、Code 会话的权限档菜单（多了「无人值守」）、
     * 外观页（手机遥控时读本机）。
     */
    @Test
    fun homeShots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = tempDir("jxy-homeshots")
        val docs = File(tmp, "文档").apply { mkdirs() }
        val e = seed(File(tmp, "data"), docs)
        val hub = Hub(e)
        val desktop = DesktopPlatform(fileLog = false) { null }
        val phone = PhonePlatform()
        shoot("desktop-home", 1280, 820, 1f, hub, desktop, "home")
        shoot("phone-home", 824, 1784, 2f, hub, phone, "home")
        shoot("phone-home-360", 720, 1560, 2f, hub, phone, "home")
        shoot("desktop-code-modes", 1280, 820, 1f, hub, desktop, "code:c8#modes")
        shoot("phone-settings-appearance", 824, 1784, 2f, hub, phone, "settings:APPEARANCE")
    }

    /** 集训场：开局页、主持人看 AI 打（暂停在第一天发言中）、真人轮到发言、打完的复盘（AI 用 WolfFakeLlm，不联网）。 */
    @Test
    fun wolfShots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = tempDir("jxy-wolfshots")
        val fake = WolfFakeLlm()
        try {
            val names = listOf("阿德", "阿麦", "阿智", "阿米", "阿千", "阿珈", "阿苗")
            WolfFakeLlm.seed(File(tmp, "data"), fake.base, names)
            val e = Engine(Storage(File(tmp, "data")))
            val hub = Hub(e)
            val desktop = DesktopPlatform(fileLog = false) { null }
            val phone = PhonePlatform()
            shoot("desktop-wolf-lobby", 1280, 1400, 1f, hub, desktop, "wolf")
            shoot("phone-wolf-lobby", 824, 1784, 2f, hub, phone, "wolf")
            fun live() = kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.WolfCurrent).data }
                .let { com.guixing.jixunying.model.AppJson.decodeFromString(com.guixing.jixunying.model.WolfLive.serializer(), it) }
            // 主持人：7 个 AI，第一天说了三个人就暂停
            fake.delayMs = 30
            kotlinx.coroutines.runBlocking {
                val roles = com.guixing.jixunying.model.WolfPresets.all.first { it.name == "经典标配" }.list.take(7)
                e.call(com.guixing.jixunying.model.Command.WolfStart(roles, names.indices.map { com.guixing.jixunying.model.WolfSeatPick(memberId = "m$it") }, "经典标配"))
                kotlinx.coroutines.withTimeout(30_000) { while (live().record.events.count { it.kind == "speech" } < 3) kotlinx.coroutines.delay(10) }
                e.call(com.guixing.jixunying.model.Command.WolfControl("pause"))
                e.call(com.guixing.jixunying.model.Command.WolfSay("3号，你昨晚为什么那么安静？"))
                kotlinx.coroutines.delay(500)
            }
            shoot("desktop-wolf-host", 1280, 900, 1f, hub, desktop, "wolf")
            shoot("phone-wolf-host", 824, 1784, 2f, hub, phone, "wolf")
            // 打完：复盘
            kotlinx.coroutines.runBlocking {
                e.call(com.guixing.jixunying.model.Command.WolfControl("resume"))
                kotlinx.coroutines.withTimeout(60_000) { while (live().let { !it.over || it.reviewing }) kotlinx.coroutines.delay(10) }
            }
            shoot("desktop-wolf-over", 1280, 900, 1f, hub, desktop, "wolf")
            // 真人 + AI：轮到我发言
            kotlinx.coroutines.runBlocking {
                val picks = listOf(com.guixing.jixunying.model.WolfSeatPick(humanName = "我")) + (0..4).map { com.guixing.jixunying.model.WolfSeatPick(memberId = "m$it") }
                e.call(com.guixing.jixunying.model.Command.WolfStart(com.guixing.jixunying.model.WolfBoard.SIX.roles, picks, judge = false))
                kotlinx.coroutines.withTimeout(30_000) {
                    while (true) {
                        val a = live().asks.firstOrNull { it.seat == 1 } ?: run { kotlinx.coroutines.delay(10); null } ?: continue
                        if (a.kind == "speech") break
                        e.call(com.guixing.jixunying.model.Command.WolfAnswer(a.id, target = a.allowed.firstOrNull() ?: 0))
                        kotlinx.coroutines.delay(20)
                    }
                }
            }
            shoot("desktop-wolf-play", 1280, 900, 1f, hub, desktop, "wolf")
            shoot("phone-wolf-play", 824, 1784, 2f, hub, phone, "wolf")
            kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.WolfStop) }
            e.close()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun shots() {
        val dir = out ?: return
        dir.mkdirs()
        val tmp = tempDir("jxy-shots")
        val docs = File(tmp, "文档").apply { mkdirs() }
        File(docs, "周报.txt").writeText("项目周报：本周完成登录页改版，下周灰度测试，预算 3.5 万元。", Charsets.UTF_8)
        File(docs, "租房合同.md").writeText("# 房屋租赁合同\n月租金 4800 元，押金两个月。", Charsets.UTF_8)
        File(docs, "会议纪要.txt").writeText("周一例会：确定上线时间为 10 月 20 日。", Charsets.UTF_8)
        val e = seed(File(tmp, "data"), docs)
        val hub = Hub(e)
        val desktop = DesktopPlatform(fileLog = false) { null }
        val phone = PhonePlatform()

        val wide = listOf(
            "chat" to "conv:c1", "chat-image" to "conv:c3", "newchat" to "newchat", "docs" to "docs", "costs" to "costs",
            "stances" to "stances", "chat-stances" to "conv:c5", "chat-stances-dialog" to "conv:c5#stances", "chat-long" to "conv:c6", "chat-council" to "conv:c7",
        ) + com.guixing.jixunying.ui.SettingsTab.entries.map { "settings-" + it.name.lowercase() to "settings:${it.name}" } +
            listOf("provider-add" to "settings:PROVIDERS#add", "provider-chatgpt" to "settings:PROVIDERS#edit:pg")
        for ((name, start) in wide) shoot("desktop-$name", 1280, 820, 1f, hub, desktop, start)
        val narrow = listOf("chat" to "conv:c1", "chat-long" to "conv:c6", "chat-council" to "conv:c7", "docs" to "docs", "stances" to "stances", "chat-stances" to "conv:c5", "costs" to "costs",
            "settings-home" to "settingshome", "settings-image" to "settings:IMAGE",
            "settings-memory" to "settings:MEMORY", "settings-semantic" to "settings:SEMANTIC",
            "settings-weixin" to "settings:WEIXIN", "settings-providers" to "settings:PROVIDERS", "provider-chatgpt" to "settings:PROVIDERS#edit:pg",
            "settings-search" to "settings:SEARCH", "settings-profile" to "settings:PROFILE", "settings-office" to "settings:OFFICE",
            // 手机上这一页是只读的（Code 栏只能在电脑上跑）
            "settings-code" to "settings:CODE")
        for ((name, start) in narrow) shoot("phone-$name", 824, 1784, 2f, hub, phone, start)
        // 2026-10-22 用户手机截图：输入框下面那一排按钮太多，发送键被挤到边上。收进「+」以后：「+」菜单打开、画图模式开着（手机 412 和 360 宽、电脑）
        shoot("phone-chat-plus", 824, 1784, 2f, hub, phone, "conv:c1#plus")
        shoot("phone-chat-draw", 824, 1784, 2f, hub, phone, "conv:c1#draw")
        shoot("phone-chat-draw-360", 720, 1560, 2f, hub, phone, "conv:c1#draw")
        shoot("desktop-chat-plus", 1280, 820, 1f, hub, desktop, "conv:c1#plus")
        shoot("desktop-chat-draw", 1280, 820, 1f, hub, desktop, "conv:c1#draw")
        // 2026-10-24 液态玻璃调节（设置 → 外观）
        shoot("desktop-glass-tuning", 1280, 1400, 1f, hub, desktop, "settings:APPEARANCE#tune")
        shoot("phone-glass-tuning", 824, 3600, 2f, hub, phone, "settings:APPEARANCE#tune")

        // 语音（1.7.0）：读出来的按钮、正在读、「合成中…」、输入框正在录音、手机上那一页
        val av = FakeAvPlatform(desktop)
        shoot("desktop-speaking", 1280, 820, 1f, Hub(HangingSpeech(e)), av, "conv:c1#speak")
        shoot("desktop-recording", 1280, 820, 1f, hub, av, "conv:c1#record")
        shoot("desktop-speaking-tall", 1280, 1300, 1f, Hub(HangingSpeech(e)), av, "conv:c1#speak")
        // 手机上：听写那一页（能放音、能录音的手机）和正在录音的输入框
        shoot("phone-settings-speech", 824, 1784, 2f, hub, FakeAvPlatform(phone), "settings:SPEECH")
        shoot("phone-recording", 824, 1784, 2f, hub, FakeAvPlatform(phone), "conv:c1#record")
        shoot("phone-speaking", 824, 1784, 2f, Hub(HangingSpeech(e)), FakeAvPlatform(phone), "conv:c1#speak")
        // 语音那一页很长：整个画出来才看得到「试听」和「听写」两节
        shoot("desktop-settings-speech-tall", 1280, 1700, 1f, hub, av, "settings:SPEECH")
        // 画图和视频那一页很长：整个画出来才看得到「做视频」那块
        shoot("desktop-settings-image-tall", 1280, 2200, 1f, hub, desktop, "settings:IMAGE")
        shoot("phone-settings-speech-tall", 824, 2600, 2f, hub, FakeAvPlatform(phone), "settings:SPEECH")

        // Code 栏：侧栏那一栏（没开会话）、会话本身（含等批准那一步）、差异查看器、新建对话框
        shoot("desktop-code-tab", 1280, 820, 1f, hub, desktop, "code")
        shoot("desktop-code", 1280, 820, 1f, hub, desktop, "code:c8")
        shoot("desktop-code-tall", 1280, 1300, 1f, hub, desktop, "code:c8")
        shoot("desktop-code-outside", 1280, 820, 1f, hub, desktop, "code:c11")
        shoot("desktop-chat-cowork", 1280, 900, 1f, hub, desktop, "conv:c14")
        shoot("desktop-toast", 1280, 820, 1f, hub, desktop, "conv:c14#toast")
        shoot("phone-toast", 824, 1784, 2f, hub, phone, "conv:c14#toast")
        shoot("desktop-code-remember-cmd", 1280, 820, 1f, hub, desktop, "code:c12")
        shoot("desktop-code-workspace", 1280, 820, 1f, hub, desktop, "code:c11#path")
        // 2026-10-19 多角色派活：工作区里的「团队」一节（窗口拉高看全）
        shoot("desktop-code-team", 1280, 1500, 1f, hub, desktop, "code:c11#brain")
        shoot("desktop-code-claude", 1280, 820, 1f, hub, desktop, "code:c15")
        shoot("desktop-code-claude-tall", 1280, 1400, 1f, hub, desktop, "code:c15")
        shoot("desktop-code-git-local", 1280, 900, 1f, hub, desktop, "code:c9#git", frames = 120)
        shoot("desktop-code-git-norepo", 1280, 900, 1f, hub, desktop, "code:c8#git", frames = 120)
        shoot("desktop-code-working", 1280, 820, 1f, hub, desktop, "code:c16")
        shoot("desktop-code-claude-workspace", 1280, 900, 1f, hub, desktop, "code:c15#path")
        // 2026-10-22：主控是 Claude Code 也能组队（总指挥，第 18 节）：团队那一节要能选
        shoot("desktop-code-claude-team", 1280, 1700, 1f, hub, desktop, "code:c15#brain")
        // 2026-10-23：「谁来干活」从「工作区」里拆出来，标题下面一个按钮点开
        shoot("phone-code-brain", 824, 1784, 2f, hub, phone, "code:c15#brain")
        shoot("phone-code-claude", 824, 1784, 2f, hub, phone, "code:c15")
        shoot("desktop-settings-code-tall", 1280, 1900, 1f, hub, desktop, "settings:CODE")
        shoot("desktop-code-diff", 1280, 820, 1f, hub, desktop, "code:c8#diff")
        shoot("desktop-code-new", 1280, 820, 1f, hub, desktop, "newcode")
        // 窗口矮（用户 10-14 截图：「建好」被挤到窗口外）：按钮要一直在对话框底部看得见
        shoot("desktop-code-new-short", 1280, 600, 1f, hub, desktop, "newcode")
        shoot("phone-code", 824, 1784, 2f, hub, phone, "code:c8")
        shoot("phone-code-tab", 824, 1784, 2f, hub, phone, "code")
        shoot("phone-code-new", 824, 1784, 2f, hub, phone, "newcode")
        // 手机上顶栏只放得下文件夹最后一节：点一下弹完整路径
        shoot("phone-code-path", 824, 1784, 2f, hub, phone, "code:c8#path")
        // 侧栏改版（2026-10-14 用户给的图）：手机上把侧栏拉出来看
        shoot("phone-sidebar", 824, 1784, 2f, hub, phone, "conv:c1#drawer")
        shoot("phone-sidebar-code", 824, 1784, 2f, hub, phone, "code:#drawer")
        // 侧栏里那条「停下了」（用户按了停止，不是做完）
        shoot("desktop-code-stopped", 1280, 820, 1f, hub, desktop, "code")
        shoot("desktop-code-stopped-conv", 1280, 820, 1f, hub, desktop, "code:c9")
        // 手机发起的会话（c10）：侧栏那一行和会话上面都要标「手机发起」（第二期）
        shoot("desktop-code-fromphone", 1280, 820, 1f, hub, desktop, "code")
        shoot("desktop-code-fromphone-conv", 1280, 820, 1f, hub, desktop, "code:c10")

        // 弹窗
        shoot("desktop-convsettings", 1280, 820, 1f, hub, desktop, "conv:c1#convsettings")
        shoot("desktop-convsettings-tall", 1280, 1300, 1f, hub, desktop, "conv:c7#convsettings")
        // 记忆二期：按话题分组、出处能点（对话删了的只显示名字）
        shoot("desktop-settings-memory-tall", 1280, 2400, 1f, hub, desktop, "settings:MEMORY")
        shoot("desktop-convsettings-c1-tall", 1280, 1300, 1f, hub, desktop, "conv:c1#convsettings")
        shoot("desktop-member-edit", 1280, 820, 1f, hub, desktop, "settings:MEMBERS#edit")
        shoot("phone-convsettings", 824, 1784, 2f, hub, phone, "conv:c1#convsettings")
        shoot("phone-settings-members", 824, 1784, 2f, hub, phone, "settings:MEMBERS")
        shoot("phone-member-edit", 824, 1784, 2f, hub, phone, "settings:MEMBERS#edit")
        shoot("phone-devices", 824, 1784, 2f, hub, phone, "settings:DEVICES")
        // 2026-10-23：联机页也有「允许手机用 Code 栏」；Code 顶上的权限档菜单、「更多」、「这一页怎么用」
        shoot("desktop-settings-devices", 1280, 1100, 1f, hub, desktop, "settings:DEVICES")
        shoot("desktop-code-modes", 1280, 820, 1f, hub, desktop, "code:c8#modes")
        shoot("desktop-code-more", 1280, 820, 1f, hub, desktop, "code:c8#more")
        shoot("desktop-code-help", 1280, 820, 1f, hub, desktop, "code:c8#codehelp")
        shoot("phone-code-help", 824, 1784, 2f, hub, desktop, "code:c8#codehelp")

        // ———— 按意思搜（1.7.0）————
        // 这一页（都没开、自动挑中智谱）在上面那一圈里已经出了：desktop-settings-semantic / phone-settings-semantic。
        // 这里补：估算弹窗、进度、正在算、出错、要重算、换服务商前的确认。
        shoot("desktop-settings-semantic-estimate", 1280, 1300, 1f, hub, desktop, "settings:SEMANTIC#estimate")
        shoot("phone-settings-semantic-estimate", 824, 1784, 2f, hub, phone, "settings:SEMANTIC#estimate")
        shoot("desktop-settings-semantic-rebuild", 1280, 1300, 1f, hub, desktop, "settings:SEMANTIC#rebuild")

        // 另开一台引擎真的算一遍（假向量服务器：真算要联网、花钱、还要等）：进度、算完、换一家要重算
        val embOk = FakeEmbed("ok")
        val qwen = ProviderConfig("pq", "qwen", "千问 AI 平台", embOk.base, "sk-qwen-xxxx", listOf(ModelInfo("qwen3.8-max")))
        val semiOn = com.guixing.jixunying.model.SemanticSettings(historyOn = true)
        val doneEngine = seed(File(tmp, "sem-done"), docs, semantic = semiOn, zhipuUrl = embOk.base, extraProviders = listOf(qwen))
        waitFor("聊天记录算完") {
            val h = doneEngine.state.semanticProgress.history
            h.total > 0 && !h.running && h.error.isBlank() && h.done >= h.total
        }
        val doneHub = Hub(doneEngine)
        shoot("desktop-settings-semantic-done", 1280, 820, 1f, doneHub, desktop, "settings:SEMANTIC")
        shoot("phone-settings-semantic-done", 824, 1784, 2f, doneHub, phone, "settings:SEMANTIC")
        // 换到千问：以前用智谱算的不能用了 → 进度里标「要重新算一遍」，旁边一个「重新算一遍」按钮
        kotlinx.coroutines.runBlocking {
            doneEngine.call(com.guixing.jixunying.model.Command.SaveSettings(
                doneEngine.state.settings.copy(semantic = semiOn.copy(providerId = "pq"))))
        }
        waitFor("标成要重新算") { doneEngine.state.semanticProgress.history.stale }
        shoot("desktop-settings-semantic-stale", 1280, 820, 1f, doneHub, desktop, "settings:SEMANTIC")
        shoot("phone-settings-semantic-stale", 824, 1784, 2f, doneHub, phone, "settings:SEMANTIC")
        // 换了服务商 / 模型要重算：确认弹窗里按新那家的价格估（现在是千问，点回自动 = 智谱，弹窗显示智谱的价）
        shoot("desktop-settings-semantic-change", 1280, 1300, 1f, doneHub, desktop, "settings:SEMANTIC#change")
        // 深色下这一页也要看得清（进度条、那句「要重新算一遍」、重新算一遍按钮）
        kotlinx.coroutines.runBlocking {
            doneEngine.call(com.guixing.jixunying.model.Command.SaveSettings(doneEngine.state.settings.copy(darkMode = 2)))
        }
        shoot("desktop-dark-settings-semantic-stale", 1280, 820, 1f, doneHub, desktop, "settings:SEMANTIC")
        shoot("phone-dark-settings-semantic-stale", 824, 1784, 2f, doneHub, phone, "settings:SEMANTIC")

        // 正在算：假服务器一直不回
        val embHang = FakeEmbed("hang")
        val runEngine = seed(File(tmp, "sem-run"), docs, semantic = com.guixing.jixunying.model.SemanticSettings(docsOn = true), zhipuUrl = embHang.base)
        waitFor("进度上写着正在算") { runEngine.state.semanticProgress.docs.running }
        shoot("desktop-settings-semantic-running", 1280, 820, 1f, Hub(runEngine), desktop, "settings:SEMANTIC")

        // 出错：假服务器回 401（进度里写引擎的原话，同时照「后台出错提示」那一套记一笔）
        val embBad = FakeEmbed("bad")
        val errEngine = seed(File(tmp, "sem-bad"), docs, semantic = com.guixing.jixunying.model.SemanticSettings(docsOn = true), zhipuUrl = embBad.base)
        waitFor("进度上报出错") { errEngine.state.semanticProgress.docs.error.isNotBlank() }
        shoot("desktop-settings-semantic-error", 1280, 820, 1f, Hub(errEngine), desktop, "settings:SEMANTIC")

        // 一家能算的都没有（把智谱删了）：整页灰掉，顶上写清楚去哪儿加
        val noEmb = seed(File(tmp, "sem-none"), docs)
        kotlinx.coroutines.runBlocking { noEmb.call(com.guixing.jixunying.model.Command.DeleteProvider("pz")) }
        shoot("desktop-settings-semantic-noprovider", 1280, 820, 1f, Hub(noEmb), desktop, "settings:SEMANTIC")
        shoot("phone-settings-semantic-noprovider", 824, 1784, 2f, Hub(noEmb), phone, "settings:SEMANTIC")
        // 假服务器收工（正在挂着的那个请求会失败，等引擎把状态写完再删临时目录）
        listOf(embOk, embHang, embBad).forEach { it.stop() }
        Thread.sleep(500)
        // 深色
        kotlinx.coroutines.runBlocking { e.call(com.guixing.jixunying.model.Command.SaveSettings(e.state.settings.copy(darkMode = 2))) }
        shoot("desktop-dark-chat", 1280, 820, 1f, hub, desktop, "conv:c1")
        shoot("desktop-dark-glass-tuning", 1280, 1400, 1f, hub, desktop, "settings:APPEARANCE#tune")
        shoot("desktop-dark-docs", 1280, 820, 1f, hub, desktop, "docs")
        shoot("desktop-dark-stances", 1280, 820, 1f, hub, desktop, "stances")
        shoot("desktop-dark-chat-stances", 1280, 820, 1f, hub, desktop, "conv:c5")
        shoot("phone-dark-settings-home", 824, 1784, 2f, hub, phone, "settingshome")
        shoot("desktop-dark-member-edit", 1280, 820, 1f, hub, desktop, "settings:MEMBERS#edit")
        shoot("desktop-dark-costs", 1280, 820, 1f, hub, desktop, "costs")
        shoot("desktop-dark-code", 1280, 820, 1f, hub, desktop, "code:c8")
        shoot("desktop-dark-code-diff", 1280, 820, 1f, hub, desktop, "code:c8#diff")
        shoot("desktop-dark-code-new", 1280, 820, 1f, hub, desktop, "newcode")
        // 深色：设置 → 语音（这一页有单选框、滑块、开关、按钮，都要看得清）
        shoot("desktop-dark-settings-speech", 1280, 820, 1f, hub, av, "settings:SPEECH")
        // 深色：设置 → 按意思搜（都没开的样子）
        shoot("desktop-dark-settings-semantic", 1280, 820, 1f, hub, desktop, "settings:SEMANTIC")
        shoot("desktop-dark-speaking", 1280, 820, 1f, Hub(HangingSpeech(e)), av, "conv:c1#speak")
        shoot("phone-dark-code", 824, 1784, 2f, hub, phone, "code:c8")
        // 设置 → Code 栏（深色）：顶上的 1 GB 提醒、备份大小、错误提示在深色下都要看得清
        shoot("desktop-dark-settings-code", 1280, 820, 1f, hub, desktop, "settings:CODE")
        shoot("phone-dark-settings-code", 824, 1784, 2f, hub, phone, "settings:CODE")
        shoot("desktop-costs-tall", 1280, 3400, 1f, hub, desktop, "costs")
        shoot("desktop-office-tall", 1280, 2600, 1f, hub, desktop, "settings:OFFICE")
        // Code 设置页整页画出来：备份大小那一行要能看到
        shoot("desktop-settings-code-tall", 1280, 1300, 1f, hub, desktop, "settings:CODE")
        // 外壳填成一个不存在的路径：输入框和下面那句「这个路径上没有文件」的提示
        kotlinx.coroutines.runBlocking {
            val s = e.state.settings
            e.call(com.guixing.jixunying.model.Command.SaveSettings(s.copy(code = s.code.copy(shell = "F:\\Git\\bin\\没有这个.exe"))))
        }
        shoot("desktop-settings-code-custom", 1280, 1300, 1f, hub, desktop, "settings:CODE")
        kotlinx.coroutines.runBlocking {
            val s = e.state.settings
            e.call(com.guixing.jixunying.model.Command.SaveSettings(s.copy(code = s.code.copy(shell = "powershell"))))
        }

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

        // ———— 手机下令（第二期 2026-10-13）：手机遥控电脑上的 Code 栏 ————
        // 假的电脑线路：一连上就把电脑的状态发过来，要记录的时候（LoadMessages）回一份。
        // 真的走中转要开 MQTT、要配对，截图不该联网（全流程在 CodeAgentTest.phoneThroughRelay 里测）。
        class CodeHostLink(val host: AppState, val msgs: Map<String, List<Message>>) : com.guixing.jixunying.client.FrameLink {
            override val online = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val incoming = kotlinx.coroutines.flow.MutableSharedFlow<com.guixing.jixunying.model.WireFrame>(extraBufferCapacity = 64)
            override suspend fun send(frame: com.guixing.jixunying.model.WireFrame) {
                when (frame) {
                    is com.guixing.jixunying.model.WireFrame.Hello -> {
                        incoming.emit(com.guixing.jixunying.model.WireFrame.Evt(com.guixing.jixunying.model.Event.State(host)))
                        msgs.forEach { (cid, list) -> incoming.emit(com.guixing.jixunying.model.WireFrame.Evt(com.guixing.jixunying.model.Event.Messages(cid, list))) }
                    }
                    is com.guixing.jixunying.model.WireFrame.Req -> {
                        val cmd = frame.command
                        val res = when (cmd) {
                            is com.guixing.jixunying.model.Command.LoadMessages -> {
                                incoming.emit(com.guixing.jixunying.model.WireFrame.Evt(com.guixing.jixunying.model.Event.Messages(cmd.convId, msgs[cmd.convId].orEmpty())))
                                com.guixing.jixunying.model.CommandResult()
                            }
                            // 看差异只是读文件、不改东西，直接拿真的引擎算一份出来（差异查看器里要有真差异可看）
                            is com.guixing.jixunying.model.Command.CodeDiff -> kotlinx.coroutines.runBlocking { e.call(cmd) }
                            else -> com.guixing.jixunying.model.CommandResult()
                        }
                        incoming.emit(com.guixing.jixunying.model.WireFrame.Res(frame.reqId, res))
                    }
                    else -> {}
                }
            }
            override fun close() {}
        }
        val seeded = Storage(File(tmp, "data"))
        val codeMsgs: Map<String, List<Message>> = mapOf(
            "c8" to seeded.loadMessages("c8"), "c9" to seeded.loadMessages("c9"), "c10" to seeded.loadMessages("c10"),
        )
        /** 手机，遥控着「电脑」（host 就是电脑那份状态，只有开关和文件夹列表按场景改）。 */
        fun codePhoneHub(root: File, allowPhone: Boolean, withCode: Boolean, msgs: Map<String, List<Message>> = codeMsgs): Hub {
            val host = e.state.copy(
                settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = allowPhone)),
                conversations = if (withCode) e.state.conversations else e.state.conversations.filterNot { it.isCode },
                // 电脑上支持调用工具的模型（新建对话框的模型列表要照电脑上的列），画图那个不算
                providers = e.state.providers.map { p -> p.copy(models = p.models.map { m -> if (m.imageGen) m else m.copy(tools = true) }) },
            )
            val pe = Engine(Storage(root), isPhone = true)
            val h = Hub(pe, linkFactory = { CodeHostLink(host, msgs) }, deviceName = "SM-S938U")
            h.attach(com.guixing.jixunying.model.PairedHost("h1", "我的电脑", "d1", "k", emptyList()))
            h.useRemote.value = true // 侧栏顶上切到「电脑」（attach 只连上，不切）
            return h
        }
        // 电脑上开着「允许手机用 Code 栏」：能发任务、能停止、等批准那一步能允许 / 拒绝，权限档只列手动 / 计划
        val codePhone = codePhoneHub(File(tmp, "code-phone"), allowPhone = true, withCode = true)
        shoot("phone-code-can-act", 824, 1784, 2f, codePhone, phone, "code:c8")
        // 手机上新建 Code 会话：项目文件夹只能从电脑上已有的会话里选（不弹系统选择器）
        shoot("phone-code-new-folders", 824, 1784, 2f, codePhone, phone, "newcode")
        // 差异查看器：手机上能逐个文件接受 / 拒绝，「全部撤销」不显示、换成一行小字
        shoot("phone-code-diff", 824, 1784, 2f, codePhone, phone, "code:c8#diff")
        // 电脑上一个 Code 会话都没有：说清楚，建不了
        val codePhoneNone = codePhoneHub(File(tmp, "code-phone-none"), allowPhone = true, withCode = false)
        shoot("phone-code-new-nofolder", 824, 1784, 2f, codePhoneNone, phone, "newcode")
        // 电脑上没打开那个开关：只能看，底下写清楚去哪儿打开
        val codePhoneLocked = codePhoneHub(File(tmp, "code-phone-locked"), allowPhone = false, withCode = true)
        shoot("phone-code-locked", 824, 1784, 2f, codePhoneLocked, phone, "code:c8")
        shoot("phone-code-locked-tab", 824, 1784, 2f, codePhoneLocked, phone, "code")
        // 2026-10-23：手机切到电脑时侧栏有 Code 标签，设备按钮是「点一下切换」的样子
        shoot("phone-remote-drawer", 824, 1784, 2f, codePhone, phone, "code:c8#drawer")

        // 空白状态：第一次打开
        val empty = Engine(Storage(File(tmp, "empty")))
        shoot("desktop-welcome", 1280, 820, 1f, Hub(empty), desktop, null)
        shoot("phone-welcome", 824, 1784, 2f, Hub(empty), phone, null)
        tmp.deleteRecursively()
    }
}
