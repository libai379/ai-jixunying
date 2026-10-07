# AI集训营 — 给 AI 助手的入口

开会话先读：docs/当前任务.md → docs/教训库.md → 需要时再读 docs/构想.md。
做完一件事：更新 docs/当前任务.md；有决定或里程碑写进 docs/编年史.md（带日期）；踩了坑写进 docs/教训库.md。

## 现在是什么

多模型 AI 助手 + AI 群聊（用户要求对标豆包 / WorkBuddy），Windows 桌面端 + 安卓端。
- 桌面端就是「服务器」：数据、API Key、模型调用全在电脑上（%APPDATA%\ai-jixunying）。不需要云服务器。
- 安卓端是「遥控器」：同一 Wi-Fi 下经局域网连电脑（UDP 18766 发现，HTTP/WebSocket 18765），6 位配对码换长期令牌。
- 功能：单聊 / 群聊、互相 @（含 AI 之间接力，最多 N 轮）、身份认知（知道自己和别人是谁、背后什么模型）、联网搜索（模型自己调 web_search / fetch_url，不会调工具的模型由引擎代搜）、看图、读 PDF/Word/Excel/PPT/文本、画图（输入框开关，或 AI 调 generate_image）、服务商预设（国内外三十家）。
- 说话规矩写在 shared/src/desktopMain/.../engine/Prompts.kt：正经回答为主、幽默点到为止、不知道就说不知道、时效信息先搜再答并标出处。

## 红线

- 不做网页版。苹果端暂不做（原生 iOS 必须有 Mac，用户 2026-10-07 说先不做）。
- 前身 G:\ai-discussion-app 只读参考，不改它的代码。它的 localStorage key `discussion_state_v3` 和 appId `com.taiclear.taic` 永远不许改（改了用户手机上的 Key 和配置全丢）。
- 癸星（F:\guixing）的源码包名 `com.guixing` 不许动，原因见癸星仓库 sync-to-public.sh 开头注释。本项目包名和安卓 applicationId 都是 `com.guixing.jixunying`，已经定了，不许再改（改了等于换一个 App）。
- 工程目录用 ASCII 名 `ai-jixunying`，不要改成中文路径（Gradle / Android 工具链对非 ASCII 路径有问题）。
- 所有显示名、包名、文件名按 docs/命名规范.md，不许再出现 AI他妈 / aichatroom / taic 等旧名。
- API Key 只存电脑。发给手机的状态必须打码（Engine.remoteView）；手机传回打码的 Key 时保留电脑上的原值。

## 工程结构

- shared/：Kotlin 多平台模块（desktop JVM + android）
  - commonMain/model：数据模型、指令和事件协议（Protocol.kt）、服务商预设（Presets.kt）
  - commonMain/client：Backend 接口、ClientStore、RemoteBackend（手机连电脑）
  - commonMain/ui：全部界面，桌面和手机共用一套 Compose 代码；宽屏是侧栏布局，窄屏是抽屉布局
  - desktopMain/engine：Engine（调度、上下文、工具循环）、Prompts（身份和规矩）、LlmClient、WebSearch、ImageGen、DocExtract、Storage、LanServer
  - desktopTest：EngineTest（假模型服务器把全流程跑一遍）、LiveSearchTest（设 JXY_LIVE=1 才真联网）
- desktopApp/：桌面入口 Main.kt 和打包配置
- androidApp/：安卓入口 MainActivity.kt（选文件、局域网发现、配对信息存 SharedPreferences）

## 常用命令（仓库根目录，Git Bash）

- 测试：./gradlew :shared:desktopTest
- 跑桌面版（用测试数据，不碰正式数据）：JAVA_TOOL_OPTIONS="-Djxy.data=G:/ai-jixunying/data-dev" ./gradlew :desktopApp:run
  - 再加 -Djxy.start=settings:PROVIDERS#add 可以直接打开某个设置页或弹窗，方便截图
  - data-dev 里的 state.json 把局域网服务关了，免得开发时弹 Windows 防火墙
- 打包桌面：./gradlew :desktopApp:packageExe（产物在 desktopApp/build/compose/binaries/main/exe/）。需要带 jpackage 的 JDK，路径在 gradle.properties 的 packageJdk
- 打包安卓：./gradlew :androidApp:assembleDebug（产物在 androidApp/build/outputs/apk/debug/）
- 交付：复制到 F:\apk-out\，文件名 AI集训营.exe / AI集训营.apk，不带版本号和日期，汇报时报文件时间

## 用户偏好

- 汇报用中文，不要表格，不要代码块（用户复制不了）。
- 卡住先搜网络和 GitHub，别硬编。
- 联网需要代理 127.0.0.1:10809（Gradle 的代理已配在 G:\DevCache\gradle-home\gradle.properties）。
- 用 Bash 工具写含反斜杠的内容会出错（双反斜杠被折成一个，\b、\1 变成控制字符），含转义的代码一律用 Write / Edit 写。
