# AI集训营 — 给 AI 助手的入口

> **每次会话第一件事：读完本文件，再读根目录的 当前任务.md、教训库.md，跑一次 git status，从当前任务接着干，不要重新勘察。需要时再读 docs/构想.md。**
> **每做完一件事（每个检查点）：覆盖 当前任务.md；有决定或里程碑追加到 编年史.md（带日期）；踩了坑追加到 教训库.md；然后提交。**
> 三份文档在仓库根目录（2026-10-08 用户要求「跟其他项目一样」，照星阙 horosa-app 的做法从 docs/ 挪上来）。
> 提交钩子 tools/git-hooks/commit-msg 把关：改了代码的提交必须带上 当前任务.md；第一行含「收尾」的提交还必须带上 编年史.md。不许 --no-verify 绕过。
> 新克隆或换机器后执行一次：git config core.hooksPath tools/git-hooks

## 现在是什么

多模型 AI 助手 + AI 群聊，用户要求对标豆包和 WorkBuddy（用户自己在用 WorkBuddy，九模型测评就是在 WorkBuddy 里跑的）。Windows 桌面端 + 安卓端。
- 两端都能单独用：电脑和手机各带一个完整引擎（shared/src/jvmShared），模型直接从本机调用，数据和 Key 存本机。
- 联机：手机配对电脑后，在任何地方（不用同一个 Wi-Fi，隔半个地球也行）都能遥控电脑上的 AI集训营。
  - 两边都主动连公共 MQTT 中转（默认 EMQX 两个入口 + HiveMQ，同时连），内容用配对时交换的密钥 AES-256-GCM 端到端加密。不需要自己的服务器、公网 IP、端口映射。
  - 配对：电脑「设置→手机联机」显示二维码（也能复制成文字配对码），手机扫码或粘贴。二维码一次性。
  - 手机侧栏顶上可以在「本机」和「电脑」之间切换；「连接电脑」页能一键把电脑上的服务商（含 Key）和成员同步到手机。按内容比对（engine/ConfigMerge.kt）：同一个账号、同一位成员不重复加，手机上已有的不覆盖只补空缺；启动时也会合并重复项。
- 功能：单聊 / 群聊、互相 @（含 AI 之间接力，最多 N 轮）、身份认知（知道自己和别人是谁、背后什么模型）、联网搜索、看图、读 PDF/Word/Excel/PPT/文本、画图、服务商预设（国内外三十多家，含九模型测评的准确模型名和测评名次）。
- 联网搜索：「自动」模式下 Kimi / 智谱 / 千问用平台官方内置搜索，其他模型调我们自己的 web_search（默认免费必应，可换博查 / 智谱 / Tavily / Brave）；不会调工具的模型由引擎代搜。不需要单独的 AI。
- 画图协议（engine/ImageGen.kt）：OpenAI 风格、硅基流动、阿里百炼 / 千问AI平台（千问图像、新万相先同步，老 text2image 异步）、MiniMax、可灵（API Key 或 AK:SK 签 JWT）、魔搭（异步任务）。
  没在 设置→画图 指定时自动挑（model/ImagePick.kt，2026-10-08 用户定「默认千问」：画质优先，千问图像第一、免费 cogview-3-flash 垫底；按预设推断同一个 Key 能调的画图模型；Engine.paintAuto 失败换下一个，Key 失效 / 欠费整家跳过）。
- 点名（engine/Stances.kt 的 Addressing）：群里没 @ 时，开头直接喊名字的程序认，其他出现成员名字的问记录员模型，只让被叫到的回答；开头喊了对话外的成员会拉进来。
- 立场档案（model/Stances.kt 数据和统计，engine/Stances.kt 的 StanceJudge 写给记录员的说明，Engine.judgeStances）：群聊每轮答完记录员后台判断——开议题、记首答、记改口和原因；存 stances.json，界面用 StanceList / StanceMark / StanceDelete 指令；侧栏「立场档案」页（ui/StancesScreen.kt）。成员的提示词里不提立场档案（不能让它们知道被记录）。
- 说话规矩写在 engine/Prompts.kt：正经回答为主、幽默点到为止、不知道就说不知道、时效信息先搜再答并标出处。
- 记忆（engine/Memory.kt，2026-10-08）：记录员（model/RecorderPick.kt，默认 mimo-v2.6-flash）把太长的聊天压成摘要，全群一份，存 convs\<id>.memo.json；长期记忆 AppState.memories 写进每位成员的设定，AI 有 remember 工具，攒够 4 句用户的话自动挑；search_history 工具和侧栏搜索能翻以前的聊天。
- 本地文档（engine/DocLibrary.kt）：两端各自给文档建索引（存 docindex\），AI 用 search_documents / read_document，只能读收录了的文件；侧栏「我的文档」页。手机要「所有文件访问」权限；手机上微信「用其他应用打开」和系统分享都能把文件交给 AI（MainActivity 的 VIEW / SEND 意图）。
- 微信助理（engine/WeixinBridge.kt）：腾讯官方 ClawBot 的 iLink 协议，照官方插件 @tencent-weixin/openclaw-weixin 源码写（细节在 docs/参考资料.md）。只接在电脑上，凭证存 %APPDATA%\ai-jixunying\weixin\；每个微信联系人一个「微信对话」（Conversation.channel = weixin:<编号>），走 Engine.channelTurn。

## 红线

- 不做网页版。苹果端暂不做（原生 iOS 必须有 Mac，用户 2026-10-07 说先不做）。
- 联机不许退回「只能局域网」：用户明确说过只能同一个 Wi-Fi 用的联机没有意义。
- 前身 G:\ai-discussion-app 只读参考，不改它的代码。它的 localStorage key `discussion_state_v3` 和 appId `com.taiclear.taic` 永远不许改。
- 安卓签名：专用密钥 G:\DevCache\signing\ai-jixunying.jks，密码在仓库根目录 keystore.properties（不进 git，另有一份备份在 G:\DevCache\signing\）。和前身 App 的调试签名不同，包名也不同，绝不会互相覆盖。这把钥匙丢了以后就没法覆盖升级，不许删、不许换。
- 癸星（F:\guixing）的源码包名 `com.guixing` 不许动。本项目包名和安卓 applicationId 都是 `com.guixing.jixunying`，不许再改。
- 工程目录用 ASCII 名 `ai-jixunying`，不要改成中文路径。
- 所有显示名、包名、文件名按 docs/命名规范.md，不许再出现 AI他妈 / aichatroom / taic 等旧名。
- 电脑发给手机的状态必须打码（Engine.remoteView）；手机传回打码的 Key 时保留电脑上的原值。只有「导入配置」（ExportConfig）会经加密线路把真 Key 给已配对的手机。
- 微信只走官方渠道：ClawBot 助理、用户转发、「用其他应用打开」、电脑上微信自己存成普通文件的收件文件夹（用户手动打开开关）。不读微信本地加密的聊天数据库，不从微信进程里取密钥。
- 微信助理只回答扫码绑定的本人（WeixinBridge.handle 里的 owner 检查），不许去掉：AI 能读本机文档，放开就等于把文档给陌生人看。
- 文档工具只能读收录了的文件（DocLibrary.find），不许改成能读任意路径。
- 不靠在提示词里加限制词修毛病（用户 2026-10-08：「限制提示词我觉得最笨的方法，他们模型不能自己去理解吗？」）。先用真模型测是哪一环坏了；判断类的活（点名、立场）交给模型理解，程序只做对号入座。
- 成员固定四家（阿德 DeepSeek、阿麦 MiniMax、阿智 智谱、阿米 小米 MiMo）；国内最多再加一家画图 / 做视频的（千问，只画图不当成员），国外以后再说。
- 千问 Key 余额很少（2026-10-08 剩约 0.64 元，每张图 0.18 元），测试别用它画图，除非用户同意。

## 工程结构

- shared/：Kotlin 多平台模块（desktop JVM + android）
  - commonMain/model：数据模型、指令和事件协议（Protocol.kt）、配对码（Pairing.kt）、服务商预设和测评成绩（Presets.kt）
  - commonMain/client：Backend 接口、ClientStore、RemoteBackend（遥控电脑，含心跳和掉线重连）、Hub（本机 + 电脑两个后端）
  - commonMain/ui：全部界面，桌面和手机共用；宽屏侧栏布局，窄屏抽屉布局；Devices.kt 是联机相关界面，DocsScreen「我的文档」，StancesScreen「立场档案」（还有聊天里的立场标签和弹窗），MemoryPage、WeixinPage 是设置里的记忆、微信页。设置改动统一走 AppController.updateSettings（改了就生效、排队、拿最新状态），不要再加「保存」按钮
  - jvmShared/engine：Engine（调度、上下文、工具循环、内置搜索、记忆、文档、外部渠道）、Prompts、LlmClient、WebSearch、ImageGen、DocExtract、Storage、ConfigMerge、Memory（记录员）、Stances（点名、立场档案的记录员）、DocLibrary、WeixinBridge；PlatformBits 是 PDF、图片压缩、默认文档文件夹、文件权限的平台接口
  - jvmShared/relay：Crypto（AES-GCM）、MqttMulti（多中转 + 探测自检 + 切片信封）、RelayHost（电脑端）、RelayLink 和 pairWithHost（手机端）
  - desktopMain / androidMain：平台实现（PDFBox / pdfbox-android，ImageIO / BitmapFactory，二维码生成）
  - desktopTest：EngineTest（假模型服务器 + 假 iLink / CDN + 进程内 MQTT 服务器 Moquette，把全流程跑一遍）；ConfigMergeTest（同步不重复、启动合并重复）；ShotsTest（离屏截图，见下）；LiveRelayTest、LiveSearchTest、LiveSnoopTest 是真联网检查，设 JXY_LIVE=1（Snoop 用 JXY_SNOOP=配对码）才跑；LiveRecorderTest 设 JXY_LIVE_RECORDER=数据文件夹 才跑（用那份数据里的真 Key 试记录员）；LiveDrawPromptTest（JXY_LIVE_DRAW=数据文件夹，看四家写的画图描述，不画）；LiveDrawTest（JXY_LIVE_DRAW_REAL + JXY_DRAW_OUT + JXY_DRAW_MODELS，真画，花钱）；LiveStanceTest（JXY_LIVE_STANCE=数据文件夹，点名 10 句 + 四位真答两轮看立场档案，复制一份数据再用；JXY_LIVE_STANCE_ROUNDS=0 只测点名；一轮可能要四分钟）
  - 自动测试不扫开发机的真实文档（Gradle 给测试设了 -Djxy.docs.autoscan=false）
- desktopApp/：桌面入口 Main.kt（启动 RelayHost）和打包配置
- androidApp/：安卓入口 MainActivity.kt（本机引擎、扫码配对、选文件）

## 常用命令（仓库根目录，Git Bash）

- 测试：./gradlew :shared:desktopTest
- 真走公共中转的联机检查：JXY_LIVE=1 ./gradlew :shared:desktopTest --tests "*LiveRelayTest*" --rerun -i
- 跑桌面版（用测试数据，不碰正式数据）：JAVA_TOOL_OPTIONS="-Djxy.data=G:/ai-jixunying/data-dev" ./gradlew :desktopApp:run
  - 再加 -Djxy.start=settings:DEVICES 或 settings:PROVIDERS#add 可以直接打开某个设置页或弹窗，方便截图
- 安卓模拟器：pixel6_api34（启动前 unset 掉 HTTP(S)_PROXY）；adb input text 只能输英文，配对码可以用它粘贴
- 真机测试（用户授权过，手机插线时）：adb 在 F:\android_sdk\platform-tools（不在 PATH 上）。tools/phone_ui.py 按文字点界面、截图；tools/inspect_state.py 看数据里的服务商和成员（Key 只显示指纹）。手机数据用 adb exec-out run-as com.guixing.jixunying tar cf - files/data 导出，改数据之前先备份到 G:\DevCache\phone-backup\。Python 用 -I 时要加 -X utf8，否则中文乱码
- 打包桌面：./gradlew :desktopApp:packageExe（产物在 desktopApp/build/compose/binaries/main/exe/）。需要带 jpackage 的 JDK，路径在 gradle.properties 的 packageJdk；版本号在 desktopApp/build.gradle.kts，每次发版要加，否则覆盖安装会提示已安装
- 打包安卓：./gradlew :androidApp:assembleDebug（用专用签名）
- 给用户打开桌面版：先 :desktopApp:createDistributable，把 desktopApp/build/compose/binaries/main/app/ai-jixunying 复制到 G:\DevCache\ai-jixunying-app 再运行（直接运行 build 目录里的会占住文件，下次打包失败）
- 渲染：Main.kt 默认 skiko.renderApi=OPENGL（DirectX 在用户电脑上会让字闪，见教训库 26）。用户正在用的窗口只截图，不要模拟鼠标键盘
- 检查界面（不碰用户屏幕）：JXY_SHOTS=G:/DevCache/shots ./gradlew :shared:desktopTest --tests "*ShotsTest*" --rerun，电脑和手机尺寸、各页面、弹窗、深色模式都画成 PNG。要加场景就在 ShotsTest 里加一行，App 的 debugStart 支持 settings:标签名#弹窗、conv:对话编号#convsettings、conv:对话编号#stances、docs、stances、settingshome、newchat
- 交付：复制到 F:\apk-out\，文件名 AI集训营.exe / AI集训营.apk，不带版本号和日期，汇报时报文件时间。exe 是只读的，复制前后都用 PowerShell 的 Set-ItemProperty IsReadOnly false 去掉目标的只读属性（教训 50）。复制前先把版本号加上去（四处：androidApp 的 versionCode / versionName、desktopApp 的 packageVersion、model/Presets.kt 的 APP_VERSION、WeixinBridge 的 BOT_AGENT）
- 发到 GitHub（2026-10-08 起用户要从 GitHub 下载安装）：收尾提交推上去以后，用 gh（F:\Tools\gh\bin，环境变量设代理 127.0.0.1:10809）建 Release：标签 v版本号、--target 收尾提交的完整 SHA；文件名用英文（AI-Jixunying-版本-windows.exe / AI-Jixunying-版本-android.apk），中文写在「文件#显示名」的显示名里；说明里写新功能、安装提示、SHA-256 和安卓签名证书指纹。上传前用 build-tools 的 apksigner verify --print-certs 确认安卓包是专用签名（CN=AI Jixunying），不然手机覆盖安装会失败

## 用户偏好

- 汇报用中文，不要表格，不要代码块（用户复制不了）。
- 卡住先搜网络和 GitHub，别硬编。
- 联网需要代理 127.0.0.1:10809（Gradle 的代理已配在 G:\DevCache\gradle-home\gradle.properties）。
- 用 Bash 工具写含反斜杠的内容会出错（双反斜杠被折成一个，\b、\1 变成控制字符），含转义的代码一律用 Write / Edit 写。
- WorkBuddy 的配置在 C:\Users\WHITEKING\.workbuddy，里面有 Key；系统不允许我翻它的设置文件，别去碰。
