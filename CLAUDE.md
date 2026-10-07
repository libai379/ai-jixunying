# AI集训营 — 给 AI 助手的入口

开会话先读：docs/当前任务.md → docs/教训库.md → 需要时再读 docs/构想.md。
做完一件事：更新 docs/当前任务.md；有决定或里程碑写进 docs/编年史.md（带日期）；踩了坑写进 docs/教训库.md。

## 现在是什么

多模型 AI 助手 + AI 群聊，用户要求对标豆包和 WorkBuddy（用户自己在用 WorkBuddy，九模型测评就是在 WorkBuddy 里跑的）。Windows 桌面端 + 安卓端。
- 两端都能单独用：电脑和手机各带一个完整引擎（shared/src/jvmShared），模型直接从本机调用，数据和 Key 存本机。
- 联机：手机配对电脑后，在任何地方（不用同一个 Wi-Fi，隔半个地球也行）都能遥控电脑上的 AI集训营。
  - 两边都主动连公共 MQTT 中转（默认 EMQX 两个入口 + HiveMQ，同时连），内容用配对时交换的密钥 AES-256-GCM 端到端加密。不需要自己的服务器、公网 IP、端口映射。
  - 配对：电脑「设置→手机联机」显示二维码（也能复制成文字配对码），手机扫码或粘贴。二维码一次性。
  - 手机侧栏顶上可以在「本机」和「电脑」之间切换；「连接电脑」页能一键把电脑上的服务商（含 Key）和成员导入手机。
- 功能：单聊 / 群聊、互相 @（含 AI 之间接力，最多 N 轮）、身份认知（知道自己和别人是谁、背后什么模型）、联网搜索、看图、读 PDF/Word/Excel/PPT/文本、画图、服务商预设（国内外三十多家，含九模型测评的准确模型名和测评名次）。
- 联网搜索：「自动」模式下 Kimi / 智谱 / 千问用平台官方内置搜索，其他模型调我们自己的 web_search（默认免费必应，可换博查 / 智谱 / Tavily / Brave）；不会调工具的模型由引擎代搜。不需要单独的 AI。
- 画图协议（engine/ImageGen.kt）：OpenAI 风格、硅基流动、阿里百炼 / 千问AI平台（异步任务）、MiniMax、可灵（API Key 或 AK:SK 签 JWT）、魔搭（异步任务）。
- 说话规矩写在 engine/Prompts.kt：正经回答为主、幽默点到为止、不知道就说不知道、时效信息先搜再答并标出处。

## 红线

- 不做网页版。苹果端暂不做（原生 iOS 必须有 Mac，用户 2026-10-07 说先不做）。
- 联机不许退回「只能局域网」：用户明确说过只能同一个 Wi-Fi 用的联机没有意义。
- 前身 G:\ai-discussion-app 只读参考，不改它的代码。它的 localStorage key `discussion_state_v3` 和 appId `com.taiclear.taic` 永远不许改。
- 安卓签名：专用密钥 G:\DevCache\signing\ai-jixunying.jks，密码在仓库根目录 keystore.properties（不进 git，另有一份备份在 G:\DevCache\signing\）。和前身 App 的调试签名不同，包名也不同，绝不会互相覆盖。这把钥匙丢了以后就没法覆盖升级，不许删、不许换。
- 癸星（F:\guixing）的源码包名 `com.guixing` 不许动。本项目包名和安卓 applicationId 都是 `com.guixing.jixunying`，不许再改。
- 工程目录用 ASCII 名 `ai-jixunying`，不要改成中文路径。
- 所有显示名、包名、文件名按 docs/命名规范.md，不许再出现 AI他妈 / aichatroom / taic 等旧名。
- 电脑发给手机的状态必须打码（Engine.remoteView）；手机传回打码的 Key 时保留电脑上的原值。只有「导入配置」（ExportConfig）会经加密线路把真 Key 给已配对的手机。

## 工程结构

- shared/：Kotlin 多平台模块（desktop JVM + android）
  - commonMain/model：数据模型、指令和事件协议（Protocol.kt）、配对码（Pairing.kt）、服务商预设和测评成绩（Presets.kt）
  - commonMain/client：Backend 接口、ClientStore、RemoteBackend（遥控电脑，含心跳和掉线重连）、Hub（本机 + 电脑两个后端）
  - commonMain/ui：全部界面，桌面和手机共用；宽屏侧栏布局，窄屏抽屉布局；Devices.kt 是联机相关界面
  - jvmShared/engine：Engine（调度、上下文、工具循环、内置搜索）、Prompts、LlmClient、WebSearch、ImageGen、DocExtract、Storage；PlatformBits 是 PDF 和图片压缩的平台接口
  - jvmShared/relay：Crypto（AES-GCM）、MqttMulti（多中转 + 探测自检 + 切片信封）、RelayHost（电脑端）、RelayLink 和 pairWithHost（手机端）
  - desktopMain / androidMain：平台实现（PDFBox / pdfbox-android，ImageIO / BitmapFactory，二维码生成）
  - desktopTest：EngineTest（假模型服务器 + 进程内 MQTT 服务器 Moquette，把全流程跑一遍）；LiveRelayTest、LiveSearchTest、LiveSnoopTest 是真联网检查，设 JXY_LIVE=1（Snoop 用 JXY_SNOOP=配对码）才跑
- desktopApp/：桌面入口 Main.kt（启动 RelayHost）和打包配置
- androidApp/：安卓入口 MainActivity.kt（本机引擎、扫码配对、选文件）

## 常用命令（仓库根目录，Git Bash）

- 测试：./gradlew :shared:desktopTest
- 真走公共中转的联机检查：JXY_LIVE=1 ./gradlew :shared:desktopTest --tests "*LiveRelayTest*" --rerun -i
- 跑桌面版（用测试数据，不碰正式数据）：JAVA_TOOL_OPTIONS="-Djxy.data=G:/ai-jixunying/data-dev" ./gradlew :desktopApp:run
  - 再加 -Djxy.start=settings:DEVICES 或 settings:PROVIDERS#add 可以直接打开某个设置页或弹窗，方便截图
- 安卓模拟器：pixel6_api34（启动前 unset 掉 HTTP(S)_PROXY）；adb input text 只能输英文，配对码可以用它粘贴
- 打包桌面：./gradlew :desktopApp:packageExe（产物在 desktopApp/build/compose/binaries/main/exe/）。需要带 jpackage 的 JDK，路径在 gradle.properties 的 packageJdk；版本号在 desktopApp/build.gradle.kts，每次发版要加，否则覆盖安装会提示已安装
- 打包安卓：./gradlew :androidApp:assembleDebug（用专用签名）
- 交付：复制到 F:\apk-out\，文件名 AI集训营.exe / AI集训营.apk，不带版本号和日期，汇报时报文件时间

## 用户偏好

- 汇报用中文，不要表格，不要代码块（用户复制不了）。
- 卡住先搜网络和 GitHub，别硬编。
- 联网需要代理 127.0.0.1:10809（Gradle 的代理已配在 G:\DevCache\gradle-home\gradle.properties）。
- 用 Bash 工具写含反斜杠的内容会出错（双反斜杠被折成一个，\b、\1 变成控制字符），含转义的代码一律用 Write / Edit 写。
- WorkBuddy 的配置在 C:\Users\WHITEKING\.workbuddy，里面有 Key；系统不允许我翻它的设置文件，别去碰。
