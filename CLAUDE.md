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
- 功能：单聊 / 群聊、互相 @（含 AI 之间接力，最多 N 轮）、身份认知（知道自己和别人是谁、背后什么模型）、联网搜索、看图、读 PDF/Word/Excel/PPT/文本、做 Word / Excel / PPT 文件、会审模式、画图、服务商预设（国内外三十多家，含九模型测评的准确模型名和测评名次）。
- 联网搜索：「自动」模式下智谱 / 千问用平台官方内置搜索；Kimi 的内置 $web_search 2026-10-20 下线，改成调我们的 web_search，但搜索发到 Kimi 官方搜索接口（WebSearch.kimi，POST {base}/tools/search，同一个 Key，出错退回设置里的引擎）；DeepSeek 成员同理走 DeepSeek 官方搜索（WebSearch.deepseek，POST https://api.deepseek.com/v1/tools/search，5 元 / 千次，2026-10-28 接）；其他模型调 web_search（默认免费必应，可换博查 / 智谱 / Tavily / Brave），设置里是免费必应时借用户已有的 DeepSeek / Kimi 官方搜索（Engine.officialSearchFor：免费必应搜中文只按第一个字搜，实测记录在 docs/参考资料.md「回答啰嗦」）；不会调工具的模型由引擎代搜。不需要单独的 AI。
- 画图协议（engine/ImageGen.kt）：OpenAI 风格、硅基流动、阿里百炼 / 千问AI平台（千问图像、新万相先同步，老 text2image 异步）、MiniMax、可灵（API Key 或 AK:SK 签 JWT）、魔搭（异步任务）。
  没在 设置→画图 指定时自动挑（model/ImagePick.kt，2026-10-08 用户定「默认千问」：画质优先，千问图像第一、免费 cogview-3-flash 垫底；按预设推断同一个 Key 能调的画图模型；Engine.paintAuto 失败换下一个，Key 失效 / 欠费整家跳过）。
- 点名（engine/Stances.kt 的 Addressing）：群里没 @ 时，开头直接喊名字的程序认，其他出现成员名字的问记录员模型，只让被叫到的回答；开头喊了对话外的成员会拉进来。
- 立场档案（model/Stances.kt 数据和统计，engine/Stances.kt 的 StanceJudge 写给记录员的说明，Engine.judgeStances）：群聊每轮答完记录员后台判断——开议题、记首答、记改口和原因；存 stances.json，界面用 StanceList / StanceMark / StanceDelete 指令；侧栏「立场档案」页（ui/StancesScreen.kt）。成员的提示词里不提立场档案（不能让它们知道被记录）。
- 裁判和 AI 核实（2026-10-09 / 10，1.4.0）：记立场和核实都用「裁判」模型（model/JudgePick.kt：设置里指定了就用；没指定找 deepseek-flash，即用户定的 DeepSeek-V4.1-Flash；都没有退回记录员）。隐名开关（judgeAnonymous，默认开）：StanceJudge.Anon 把成员名字换成「成员甲 / 乙 / 丙」、成员回答里报的模型名换成「模型甲」；代号按对话里的成员顺序给，每轮一样；用户的问题和议题只换名字（问题里提到的模型名回答里也不换）；裁判写回来的名字和说明文字都换回真名。AI 核实开关（aiVerify，默认开）：开新议题后 Engine.verifyTopic 另开协程让裁判判一次谁对（能用 web_search），结论只认这道题的立场 key / none / open / unclear，存 StanceTopic.aiVerdict / aiReason / aiSources；同一题不同时跑两次；出错、看不懂都进后台出错提示（BgJob.AI_VERIFY）；重新核实不先清旧结论。统计用 StanceTopic.effectiveVerdict：用户标的优先，没标时按 AI（countAiVerdict 开关），「判断不了」和不存在的立场不算。
- 狼人杀（集训场，docs/狼人杀设计说明.md，2026-10-26 起）：用户定最终目标是测评。法官是程序（engine/wolf/WolfGame.kt），AI 每步先写私下想法再给动作，不合法重问一次、还不行记弃权（绝不随机代投）；私下判断、同时投票；每局存 <数据目录>/wolf/<局编号>.json；Engine.playWolf 打全 AI 的局。侧栏第三栏「集训场」（ui/WolfScreen.kt，10-27 照第一代 G:\ai-discussion-app\src\wolf 重做，细节见设计说明第 10 节）：两种玩法——「我当主持人」（全 AI、上帝视角、暂停 / 一步一步 / 插话 WolfControl、WolfSay）和「我上桌玩」（真人座位，同一台设备轮流挡屏）；8 套预设 + 14 种身份（model/Wolf.kt WolfRole、WolfPresets），身份随机或指定；对局页像群聊（座位头像条、AI 边出字 Event.WolfTyping、法官消息、投票卡）；AI 调不通问重试 / 跳过；打完每个 AI 说复盘感想、总结经验存 <数据>/wolf/exp.json 下局带上。测试 WolfGameTest、WolfLiveTest（假模型 WolfFakeLlm）。多手机扫码进房间、测评批量跑还没做。
- 会审模式（engine/Council.kt + Engine.runCouncil，1.5.0，构想.md 第二节）：对话上的 council 开关（不做成 ReplyMode 的新值：旧版手机解不开新枚举值，整个状态会读坏）。对大家提的问题：各自独立回答 → 匿名互评（Council.reviewPrompt，代号甲乙丙，顺序按问题编号打乱，每位成员各评一次、并行）→ 主席汇总（裁判模型，发言人 CHAIR_ID = tool:chair，Council.chairPrompt 要求写首轮分歧、依据、少数派、不确定的）；名次由程序算平均名次（Council.averageRanks）附在汇总后；代号最后换回真名（Council.reveal）。Message.stage = review / chair；只 @ 或喊了某几位时不会审；立场档案只记第一轮；会审消息不能单独重答；微信里只发回答和汇总；记账 UsageTag("council")。
- 设置版本（Settings.schema / Settings.SCHEMA，1.4.0 起）：旧版手机不认识新加的设置项，发来的 SaveSettings 里是默认值；电脑看到 schema 比自己小就保留那几项（Engine 的 SaveSettings）。以后再加旧版不认识的设置项：SCHEMA 加一，并在 SaveSettings 里保留。
- 思考（model/Thinking.kt，2026-10-08）：每位成员「默认 / 快速 / 深度」。各家切换参数不一样（DeepSeek thinking.type + reasoning_effort、MiniMax-M3 只认 adaptive / disabled、GLM-5.3 关不掉只能调强度、千问 enable_thinking……），出处在 docs/参考资料.md「切换思考的参数」；没查到的平台两样都灰掉、不传参数；模型报 400 不认就自动去掉重试（LlmClient 的 thinking）。
- 后台出错提示（engine/Background.kt，2026-10-08）：记录员的后台活（压缩聊天、挑记忆、点名判断、立场档案，1.4.0 加了 AI 核实）出错时记进 AppState.bgProblems，弹一次提示（同一样半小时内只弹一次），设置 → 记忆、立场档案页、侧栏「设置」上常驻显示，成功一次自动消失。
- 花费（2026-10-08）：每次调用模型、每张图记进账本 usage\年-月.jsonl（engine/Ledger.kt，UsageTag 标算谁的账）；价格表 model/Prices.kt（官方价，DeepSeek 分高峰 / 空闲，用户能改单价）；engine/CostReporter.kt 汇总；engine/Balances.kt 查余额；侧栏「花费」页 ui/CostsScreen.kt。
- 做 Word / Excel / PPT（engine/OfficeWriter.kt、engine/PptWriter.kt，1.5.0）：手写 OOXML（不用 Apache POI，手机也能跑；元素顺序照 ECMA-376 schema，Office 对顺序很严）。成员工具 create_word（Markdown 正文 + 可选 title / style 通用·正式·公文 / header / landscape / toc）、create_excel（Markdown 表格，每个 ## 一个工作表，数字等自动认类型，公式行号按「表头第 1 行」写）、create_ppt（# 封面、每个 ## 一页、「备注：」进讲稿，可选 title / subtitle / theme）；PPT 不用占位符，形状全自己画，只留一个空白版式，五套配色、按字数选字号、放不下平均拆页；Engine.makeOfficeFile(ext) 存成附件（Attachment.generated = true）并另存副本（savedPath：电脑默认 文档\AI集训营，手机 <数据>/made/）；Command.ExportMessage 是「存成 Word / 表格存成 Excel」；设置 Settings.office（设置 → Word / Excel / PPT）。
  - 改了生成器要验两样：① 用 G:\DevCache\tools\ooxml-validator\bin\OOXMLValidatorCLI.exe <文件夹> Office2016 校验（JXY_OFFICE_OUT=文件夹 跑 OfficeWriterTest 会把样例存下来），0 个错误才算过；② 用 LibreOffice 看排出来的样子：G:\DevCache\tools\lo\x\program\soffice.exe -env:UserInstallation=file:///G:/DevCache/tools/lo/profile --headless --convert-to pdf --outdir <文件夹> <文件>（安装包解压的，没装进系统；配置目录放 G 盘），再把 PDF 转图片看。LibreOffice 的编号列表不会自动往下数，所以 PPT 每条编号都写死。
- 说话规矩写在 engine/Prompts.kt：不知道就说不知道、时效信息先搜再答并标出处（「说真话」）；最后一段「怎么回答」（2026-10-28 照 Claude 官方设定的思路写）：先想用户拿回答去做什么、第一句进正题、篇幅跟着问题走、排版越少越好、搜索过程不念、没问的不教、幽默点到为止；微信里的对话另说一句微信显示不了 Markdown。工具回给模型的话只交代怎么答，不叫它「告诉用户没搜到」。改这些之前先跑 LiveAnswerStyleTest（同一位成员裸调和本应用对比，JXY_LIVE_STYLE=数据文件夹）。
- 记忆（engine/Memory.kt，2026-10-08）：记录员（model/RecorderPick.kt，默认 mimo-v2.6-flash）把太长的聊天压成摘要，全群一份，存 convs\<id>.memo.json；长期记忆 AppState.memories 写进每位成员的设定，AI 有 remember 工具，攒够 4 句用户的话自动挑；search_history 工具和侧栏搜索能翻以前的聊天。
  - 记忆二期（2026-10-26 用户定「对，就这样做」）：MemoryItem.topic 空 = 关于用户本人（每个对话都带），有名字 = 话题记忆，只在 Conversation.memoryTopic 相同的对话里带（Recorder.forPrompt），别的话题只在设定里列名字、AI 用 recall_memory 查。对话的话题由记录员挑记忆时顺带认（extractPrompt 输出 conversationTopic），用户在对话设置里定了（SetConvTopic，memoryTopicByUser）就不再改；UpdateConversation 一律保留这两个字段。出处：convId / messageId / quote，记录员抄的原话要 Recorder.locateQuote 在用户消息里对得上才挂，对不上不挂（不让编的原话冒充出处）；设置 → 记忆 按话题分组，点「来自」跳回那条消息。
- 本地文档（engine/DocLibrary.kt）：两端各自给文档建索引（存 docindex\），AI 用 search_documents / read_document，只能读收录了的文件；侧栏「我的文档」页。手机要「所有文件访问」权限；手机上微信「用其他应用打开」和系统分享都能把文件交给 AI（MainActivity 的 VIEW / SEND 意图）。
- 微信助理（engine/WeixinBridge.kt）：腾讯官方 ClawBot 的 iLink 协议，照官方插件 @tencent-weixin/openclaw-weixin 源码写（细节在 docs/参考资料.md）。凭证存 <数据目录>\weixin\；每个微信联系人一个「微信对话」（Conversation.channel = weixin:<编号>），走 Engine.channelTurn。
  - 1.5.0 起手机也能接（JxyApp 里建 WeixinBridge + engine/WeixinHandover）。一个微信只能绑一台设备（社区实测：再绑会把前一个顶掉），所以配对过电脑的手机不扫码，经加密线路用电脑的同一个绑定（Command.WeixinShare → WeixinImport，自动同步，电脑换绑 / 解绑手机跟着变）。
  - 一台设备挂几个微信（2026-10-30 多微信方案甲，照官方插件 dist/src/auth/accounts.js）：凭证一个微信一个文件夹 <数据目录>\weixin\bots\<botId>\（旧的 weixin\account.json 启动时自动搬过去），同一个人重绑只换他自己那份。每个记谁绑的（WeixinAccount.boundBy：空 = 本机；手机遥控电脑时扫的 = 那台手机，Engine 从 RelayHost 放进协程上下文的 RemoteDevice 取）和能不能读电脑文件（readFiles：本机绑的默认能，手机绑的默认只聊天——Engine 里 wxChatOnly 不给文档和文件工具；开关 WeixinSetReadFiles 只能在电脑上改）。手机用 WeixinShareAll 只拿自己绑的 + 电脑自己绑的（老电脑退回 WeixinShare）；待命、接班、报进度（WeixinReport 带 botId）都按微信分开：电脑在接哪个，手机那一份待命；电脑关了，手机绑的那台接，电脑自己绑的归 weixinBackup。WeixinInfo 顶上几项是汇总，给老版本手机看，bots 是每一个的。
  - 电脑优先：电脑在线、开着微信助理、收得到消息（WeixinInfo.answering）时手机待命（WeixinBridge.standby），刚打开还不知道电脑状态时也先等着；电脑关机、断网或关掉开关，手机接着答。两边各一个开关（Settings.weixin.enabled 各存各的），手机省电时关掉。
  - 不重复回答：按微信消息的 create_time_ms 划界——手机只答最后一次听到电脑（RemoteBackend.lastHeardAt）以后发来的（WeixinCursor.answerAfter）；手机答过的经 Command.WeixinReport 告诉电脑；电脑把绑定给过手机的话，刚打开时积压的消息先等 30 秒（BACKLOG_HOLD_MS）等手机报告。只有正在接的那台发 notifystop。
  - 手机后台（JxyApp.watchLifecycle）：手机接微信时开前台服务 KeepAliveService（remoteMessaging，通知上「关掉」= 关开关），只在应用打开着时启动；没在接微信时切到后台 30 秒断开中转（RemoteBackend.pause / RelayLink.pause），回前台 resume。
  - 两端同步（engine/DeviceSync.kt，2026-10-27）：手机接过的微信对话、两边各自记的账本，手机连上电脑就分批对齐（按内容去重，只发本机自己的：Message.syncedFrom、UsageRecord.origin 空的）；账本服务商按地址 + Key 指纹对到本机那家，手填余额估算两台一起扣。批次跟着网忙不忙、是不是手机流量变。以后再加「手机也能自己干」的功能，先想好它留下的东西要不要回电脑（教训 106）。
  - 电脑只许开一个（desktopApp/SingleInstance.kt，数据目录 app.lock）；设置 → 手机联机「开机自动启动」（HKCU Run）。
  - 已知限制：中转全断但电脑和手机都能上网时，手机看不到电脑，两边可能都答；三星等手机电池没设「不限制」时，锁屏久了还可能被关掉。

## 红线

- **对话页和 Code 栏能力同步（用户 2026-10-17：「对话和 code 同步明白吗？只是专业不一样，一个专门开发，一个专门聊天工作」）**：读文件 / 文件夹、拖文件和贴图、看图、自定义指令、联网，两边口径一致，改一边就对照另一边一起改。只有「专业」不同：Code 栏改代码、跑命令、看差异和撤销；对话页群聊、会审、立场档案、画图、做 Word / Excel / PPT。

- **手机遥控电脑时和电脑上一样，不再加「手机不许」（用户 2026-10-28：「不要限制，要比 code 更开放……我一直点上百次允许吗？」）**：闸门只有配对和电脑上「允许手机用 Code 栏」。以后想给手机单独加限制，先问用户。细节见 docs/Code栏设计说明.md 第 21 节。
- 外观（主题、背景、液态玻璃）每台设备各管各的（用户 2026-10-28：手机遥控电脑时「我调的主题怎么这么卡……不应该跟随电脑的」）：App.kt 一直读本机 hub.local，外观页、玻璃调节页用 updateLocalSettings，背景图存本机。新加界面类设置照这个来。
- **免费的尽量不用（用户 2026-10-28：「但凡免费真的不好用，我这次算是吃了一个大亏……既然能用钱解决……我们就花钱」）**：默认方案先用用户已有的付费服务（各家 Key），免费接口、爬网页只当实测能用的退路；起因是免费必应搜中文只按第一个字搜。
- 不做网页版。苹果端暂不做（原生 iOS 必须有 Mac，用户 2026-10-07 说先不做）。
- 联机不许退回「只能局域网」：用户明确说过只能同一个 Wi-Fi 用的联机没有意义。
- 前身 G:\ai-discussion-app 只读参考，不改它的代码。它的 localStorage key `discussion_state_v3` 和 appId `com.taiclear.taic` 永远不许改。
- 安卓签名：专用密钥 G:\DevCache\signing\ai-jixunying.jks，密码在仓库根目录 keystore.properties（不进 git，另有一份备份在 G:\DevCache\signing\）。和前身 App 的调试签名不同，包名也不同，绝不会互相覆盖。这把钥匙丢了以后就没法覆盖升级，不许删、不许换。
- 癸星（F:\guixing）的源码包名 `com.guixing` 不许动。本项目包名和安卓 applicationId 都是 `com.guixing.jixunying`，不许再改。
- 工程目录用 ASCII 名 `ai-jixunying`，不要改成中文路径。
- 所有显示名、包名、文件名按 docs/命名规范.md，不许再出现 AI他妈 / aichatroom / taic 等旧名。
- 电脑发给手机的状态必须打码（Engine.remoteView）；手机传回打码的 Key 时保留电脑上的原值。只有「导入配置」（ExportConfig）会经加密线路把真 Key 给已配对的手机。
- 微信只走官方渠道：ClawBot 助理、用户转发、「用其他应用打开」、电脑上微信自己存成普通文件的收件文件夹（用户手动打开开关）。不读微信本地加密的聊天数据库，不从微信进程里取密钥。
- 微信助理：**扫码绑定的本人才能让 AI 读本机文档**（WeixinBridge.handle 里的 owner 检查），这条不许去掉——AI 能读本机文档，放开就等于把文档给陌生人看。2026-10-23 用户定：「用户谁扫码，谁能够调取文档，群内其他用户只可聊天，也可以爬虫其他平台公开内容」——以后进群了，群里别人只能聊天、看公开链接的内容，不能碰文档、不能让电脑干活（还没做；ClawBot 能不能进群没核实）。
- 对话页 AI 读文件（2026-10-17 用户定「和 Code 一样放开」，推翻了原来的「文档工具只能读收录了的文件」）：电脑上 list_folder / read_document 能读任意路径；「我的文档」收录范围和这个对话「不用再问」的清单（Conversation.grantedPaths，拖进来的、批准时选了不再问的）里的直接读，外面的在聊天里停下来问用户（Engine.askToRead）。**微信对话、手机发起的这一轮（PhoneTurn）一律不许读收录范围外的，不许去掉**——微信、手机丢了就等于整台电脑的文件被人读。网络路径一律不读。
- 不靠在提示词里加限制词修毛病（用户 2026-10-08：「限制提示词我觉得最笨的方法，他们模型不能自己去理解吗？」）。先用真模型测是哪一环坏了；判断类的活（点名、立场）交给模型理解，程序只做对号入座。
- 成员：现有四家（阿德 DeepSeek、阿麦 MiniMax、阿智 智谱、阿米 小米 MiMo），千问只画图、做视频（2026-10-20 接上，见 docs/参考资料.md「视频生成」）。**2026-10-23 用户撤掉了「成员固定四家」**（原话：「现在可以把这条禁令改掉，可以继续加其他模型了」；这条是 10-08 用户说「目前就四家固定的」时我写死的）。加新成员照常：模型名、参数从官方原文核对，价格进 model/Prices.kt。
- 千问 Key 余额很少（2026-10-08 剩约 0.64 元，每张图 0.18 元），测试别用它画图，除非用户同意。
- 只在本机跑（用户 2026-10-10）：开发、测试、打包都在这台电脑上做，不开云端会话、不用远程 agent 或云端隔离环境；推 GitHub、发 Release 不算。

## 工程结构

- shared/：Kotlin 多平台模块（desktop JVM + android）
  - commonMain/model：数据模型、指令和事件协议（Protocol.kt）、配对码（Pairing.kt）、服务商预设和测评成绩（Presets.kt）
  - commonMain/client：Backend 接口、ClientStore、RemoteBackend（遥控电脑，含心跳和掉线重连）、Hub（本机 + 电脑两个后端）
  - commonMain/ui：全部界面，桌面和手机共用；宽屏侧栏布局，窄屏抽屉布局；Devices.kt 是联机相关界面，DocsScreen「我的文档」，StancesScreen「立场档案」（还有聊天里的立场标签和弹窗），MemoryPage、WeixinPage 是设置里的记忆、微信页。设置改动统一走 AppController.updateSettings（改了就生效、排队、拿最新状态），不要再加「保存」按钮
  - jvmShared/engine：Engine（调度、上下文、工具循环、内置搜索、记忆、文档、外部渠道）、Prompts、LlmClient、WebSearch、ImageGen、DocExtract、Storage、ConfigMerge、Memory（记录员）、Stances（点名、立场档案的记录员）、DocLibrary、WeixinBridge；PlatformBits 是 PDF、图片压缩、默认文档文件夹、文件权限的平台接口
  - jvmShared/relay：Crypto（AES-GCM）、MqttMulti（多中转 + 探测自检 + 切片信封）、RelayHost（电脑端）、RelayLink 和 pairWithHost（手机端）
  - desktopMain / androidMain：平台实现（PDFBox / pdfbox-android，ImageIO / BitmapFactory，二维码生成）
  - desktopTest：EngineTest（假模型服务器 + 假 iLink / CDN + 进程内 MQTT 服务器 Moquette，把全流程跑一遍）；ConfigMergeTest（同步不重复、启动合并重复）；ShotsTest（离屏截图，见下）；LiveRelayTest、LiveSearchTest、LiveSnoopTest 是真联网检查，设 JXY_LIVE=1（Snoop 用 JXY_SNOOP=配对码）才跑；LiveRecorderTest 设 JXY_LIVE_RECORDER=数据文件夹 才跑（用那份数据里的真 Key 试记录员）；LiveDrawPromptTest（JXY_LIVE_DRAW=数据文件夹，看四家写的画图描述，不画）；LiveDrawTest（JXY_LIVE_DRAW_REAL + JXY_DRAW_OUT + JXY_DRAW_MODELS，真画，花钱）；LiveStanceTest（JXY_LIVE_STANCE=数据文件夹，点名 10 句 + 四位真答两轮看立场档案，复制一份数据再用；JXY_LIVE_STANCE_ROUNDS=0 只测点名；一轮可能要四分钟）；LiveInstructionTest（JXY_LIVE_INSTR=数据文件夹，旧对话 / 新对话照不照新写的自定义指令，JXY_LIVE_INSTR_ONLY=成员名、JXY_LIVE_INSTR_RUNS=轮数）；LiveBrowserTest（JXY_LIVE_BROWSER=网址,…，真开 Edge 读平台页面；自动测试里 -Djxy.browser=off 不开真浏览器）
  - 自动测试不扫开发机的真实文档（Gradle 给测试设了 -Djxy.docs.autoscan=false）
- desktopApp/：桌面入口 Main.kt（启动 RelayHost）和打包配置
- androidApp/：安卓入口 MainActivity.kt（本机引擎、扫码配对、选文件）

## 常用命令（仓库根目录，Git Bash）

- 测试：./gradlew :shared:desktopTest
- 真走公共中转的联机检查：JXY_LIVE=1 ./gradlew :shared:desktopTest --tests "*LiveRelayTest*" --rerun -i
- 跑桌面版（用测试数据，不碰正式数据）：JAVA_TOOL_OPTIONS="-Djxy.data=G:/ai-jixunying/data-dev" ./gradlew :desktopApp:run
  - 再加 -Djxy.start=settings:DEVICES 或 settings:PROVIDERS#add 可以直接打开某个设置页或弹窗，方便截图
- 安卓模拟器：pixel6_api34（启动前 unset 掉 HTTP(S)_PROXY）；adb input text 只能输英文，配对码可以用它粘贴
- 真机测试（用户授权过，手机插线时）：adb 在 F:\android_sdk\platform-tools（不在 PATH 上）。tools/phone_ui.py 按文字点界面、截图；tools/inspect_state.py 看数据里的服务商和成员（Key 只显示指纹）。手机数据用 adb exec-out run-as com.guixing.jixunying tar cf - files shared_prefs databases no_backup 导出，装新版、改数据之前先备份到 G:\DevCache\phone-backup\；命令一律带 -s R5GL136KP1H（用户 2026-10-20 换的新手机三星 SM-S9480，旧手机 SM-S938U / R5CY2195QED 已卖；常有别的模拟器连着）。run-as 只对可调试包有用：2026-10-11 起用户手机装的是正式包，run-as 会报「package not debuggable」；要导出数据得先覆盖装一个同版本的调试包（调试包也用专用钥匙签，见 androidApp/build.gradle.kts 的 debug；同 versionCode 覆盖应能装、数据不丢，未试）。Python 用 -I 时要加 -X utf8，否则中文乱码。**装完启动用 adb shell am start -n com.guixing.jixunying/.MainActivity，不许用 monkey；启动前先只读查 dumpsys window 的 mCurrentFocus，用户在用别的应用（10-23 在打游戏被我拽出来）就不启动，只告诉用户装好了**（2026-10-21：monkey 退出时会把用户的竖屏锁定改成自动旋转）；不要随手 adb tcpip（会重启 adbd，把用户的 Shizuku 断掉，GKD 跟着失效）；无线连接用 F:\Tools\adb-wifi.cmd（只做 connect，连不上请用户自己连），无线时序列号是 192.168.1.15:5555。用户 10-21：「以后所有项目无线安装都不要碰」——对手机只许装、启动、只读查询，Shizuku 也不许替用户启动；规矩在全局 ~/.claude/CLAUDE.md，有钩子 ~/.claude/hooks/adb_guard.py 拦
- 检查更新用的电脑整包：./gradlew :desktopApp:packageUpdateZip（先 createDistributable，再把整个程序文件夹打成 desktopApp/build/compose/binaries/main/update/ai-jixunying-windows-update.zip），发版时和安装程序一起传（见下面「发到 GitHub」）
- 打包桌面：./gradlew :desktopApp:packageExe（产物在 desktopApp/build/compose/binaries/main/exe/）。需要带 jpackage 的 JDK，路径在 gradle.properties 的 packageJdk；版本号在 desktopApp/build.gradle.kts，每次发版要加，否则覆盖安装会提示已安装
- 打包安卓：./gradlew :androidApp:assembleDebug（用专用签名）
- 检查更新（2026-10-30，engine/Updater.kt + DesktopPlatform 里的 UpdateSwap + engine/UpdateViaHost.kt + ui/UpdateCard.kt）：查 api.github.com/repos/libai379/ai-jixunying/releases/latest，tag 比 APP_VERSION 新就算有新版；下完拿 GitHub 给的 digest（sha256）核对，对不上删掉。先走设置里的代理再直连（null = 直连，别用 listOfNotNull 滤掉，教训 123）。引擎每天自动查一次只标红点（-Djxy.update.auto=false 关，测试里关着）。
  - 电脑：程序文件夹能写就下整包原地换——解开到旁边的 <程序文件夹>.update，PowerShell 脚本（SWAP_SCRIPT，只用 ASCII，中文路径从参数传）等程序的两个进程都退出，旧的改名 .old、新的改成原名，再打开；换不成改回去开旧的。新版开起来两分钟后删 .old 和 .update（exe 是只读的，先去掉只读再删，教训 124）。写不进去才退回打开安装程序。用户这台电脑跑的是复制过去的 G:\DevCache\ai-jixunying-app，不是安装程序装的，所以不能跑安装程序（会另装一份，桌面图标、开机自启还指着旧的）。
  - jpackage 启动器（OpenJDK jdk-25+36 WinLauncher.cpp）把程序本体放进 JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK 的作业：程序再开的进程不在作业里，程序退了脚本照跑（10-30 打包实测）。程序自己再打开的新版继承了 PATH（里面有程序文件夹），启动器不再分出子进程，只有一个进程，正常。
  - 手机：下 apk，交给系统安装界面（ACTION_VIEW + FileProvider，要 REQUEST_INSTALL_PACKAGES，第一次跳「安装未知应用」）；不用 PackageInstaller 静默装（装完系统直接关掉程序、没有「打开」）。直连 GitHub 下不动、配对的电脑开着（state.appVersion 不空 = 电脑认得新命令）就 HostFetchUpdate 请电脑代下（只认本项目发行版地址），再 GetFilePart 分段取回。
  - 演练：-Djxy.start=settings:ABOUT#autoupdate 自己走一遍「检查 → 下载 → 更新」；-Djxy.update.api 换假 GitHub、-Djxy.version 假装旧版。10-30 在打包好的测试副本（G:\DevCache\jxy-uptest，测试数据）上真换过两次、在模拟器上从真 GitHub 装过 1.12.1。
- 用测试数据跑的电脑端（-Djxy.data）窗口和托盘都叫「AI集训营（测试数据）」，别和用户正在用的那个混了（10-30 用户说开过两个桌面端，查下来锁没问题，这是能看着像两个的原因之一）。
- 换电脑端前要真退出：点叉 / 发关窗口消息现在只藏到托盘（2026-10-30）。正常退出是往 <数据目录>\app.port 里那个 127.0.0.1 端口发一行「quit」（停中转、微信、后台命令再退）；用户自己退是右下角托盘图标右键「退出」
- 给用户打开桌面版：先 :desktopApp:createDistributable，把 desktopApp/build/compose/binaries/main/app/ai-jixunying 复制到 G:\DevCache\ai-jixunying-app 再运行（直接运行 build 目录里的会占住文件，下次打包失败）
- 渲染：Main.kt 默认 skiko.renderApi=OPENGL（DirectX 在用户电脑上会让字闪，见教训库 26）。用户正在用的窗口只截图，不要模拟鼠标键盘
- 检查界面（不碰用户屏幕）：JXY_SHOTS=G:/DevCache/shots ./gradlew :shared:desktopTest --tests "*ShotsTest*" --rerun，电脑和手机尺寸、各页面、弹窗、深色模式都画成 PNG。要加场景就在 ShotsTest 里加一行，App 的 debugStart 支持 settings:标签名#弹窗、conv:对话编号#convsettings、conv:对话编号#stances、conv:对话编号#plus（输入框「+」菜单打开）、conv:对话编号#draw（直接画图开着）、conv:对话编号#longdraft（输入框里一大段草稿；# 后面能用 + 叠几个，比如 #longdraft+draw）、docs、stances、settingshome、newchat
- 开发者署名（用户 2026-10-14 定）：「癸星」「癸星科技」两个都能用，现在关于页和电脑安装包的发布者 / 版权用的是「癸星科技」（model/Presets.kt 的 APP_DEVELOPER、desktopApp 的 vendor / copyright），不用再改。安卓签名证书里的 O=guixing、包名 com.guixing.jixunying 不动（改了手机没法覆盖安装）。
- 版本号规则（2026-10-12 定，用户说「1.5.0 会规范三个数字的含义」，按我的提议没反对）：主.次.修。
  - 主：旧版和新版不再互通（联机协议、数据格式不兼容），或者整体改版，才加；次、修归零。
  - 次：加了新功能就加（比如 Code 栏是 1.6.0）；修归零。
  - 修：只修问题、不加功能（1.5.1）。
  - 联机只保证「主.次」相同的电脑和手机互通，修订号不同不影响。安卓 versionCode 照旧每发一版加一，和三个数字无关。
  - 补充（2026-10-14，用户嫌「更新迭代得有点快」：10-10 到 10-13 四天从 1.3.0 发到 1.8.0；侧栏改版时我还把它说成 1.9.0，按上面的规则本该是 1.8.1）：
    - 什么时候发版：只在用户说「发」时发，或者攒够一批值得装的改动、先问用户同意再发。不许做完一批就发一版。没发的改动照常提交推送，在 当前任务.md 里列一个「已做、未发版」清单。
    - 什么算新功能（加次版本号）：用户能用上以前没有的能力（比如 Code 栏、读出来、手机遥控 Code 栏）。界面换排法、改样式、改文案、挪位置都不算新功能，算修订号（侧栏改版 = 修）。主版本号的「整体改版」只指整个应用的界面和交互全部重做，改一个区域（侧栏、设置页）不算。
    - 一次发版不管里面有几个新功能，次版本号只加一。修订号同理。
    - 汇报时不提前报版本号：要发版时先对照这几条写出理由（「有新功能 X，所以次版本号加一」），再报号。
- 交付：复制到 F:\apk-out\，文件名 AI集训营.exe / AI集训营.apk，不带版本号和日期，汇报时报文件时间。exe 是只读的，复制前后都用 PowerShell 的 Set-ItemProperty IsReadOnly false 去掉目标的只读属性（教训 50）。复制前先把版本号加上去（四处：androidApp 的 versionCode / versionName、desktopApp 的 packageVersion、model/Presets.kt 的 APP_VERSION、WeixinBridge 的 BOT_AGENT）
- 发到 GitHub（2026-10-08 起用户要从 GitHub 下载安装）：收尾提交推上去以后，用 gh（F:\Tools\gh\bin，环境变量设代理 127.0.0.1:10809）建 Release：标签 v版本号、--target 收尾提交的完整 SHA；**文件名必须带版本号和日期，而且只用英文字母数字**（GitHub 会把文件名里的中文删掉，2026-10-10 传成了「AI.-1.3.0-…」，教训 55）：AI-Jixunying-1.4.0-20261010-windows.exe / AI-Jixunying-1.4.0-20261010-android.apk，**从 1.12.2 起还要传第三个 AI-Jixunying-<版本>-<日期>-windows-update.zip**（packageUpdateZip 打的整包；电脑「检查更新」靠它原地换新版，没有它电脑只能打开发行版网页。名字里要有 windows、以 .zip 结尾，engine/Updater.kt 按这个挑）；中文写在显示名里（gh release upload 的「文件路径#显示名」）；每次发版都是新版本号、新标签，不许往已有的 Release 里换别的版本的安装包；**每一版都留着，不许删、不许覆盖上一版**（用户 2026-10-13 改的，推翻了 10-10 的「只留最新一版」）：新版标 Latest，旧版的 Release 原样留着；每版的说明都要具体写清这一版加了什么、改了什么、修了什么（不能只写「见上一版」）。10-13 用 G:\DevCache\release-版本号 里留的原文件和说明把删掉的 1.5.0、1.6.0、1.7.0 补了回去（--verify-tag --latest=false，线上 SHA-256 和当时的记录一致）；1.3.0、1.4.0 的安装包本机没留，只有标签。每版的上传副本和 notes.md 都留在 G:\DevCache\release-版本号，不要删；说明开头一句写清版本号、日期、比上一版多了什么；安卓要求 Android 8.0 以上（minSdk 26）；**Release 说明必须包含**：## What's New（新增功能清单）、## Improvements（改进项）、## Bug Fixes（修复的问题）、## Download（下载说明 + 每个文件的 SHA-256）、## 安卓签名指纹（方便用户验证）。上传前用 build-tools 的 apksigner verify --print-certs 确认安卓包是专用签名（CN=AI Jixunying），不然手机覆盖安装会失败

## 用户偏好

- 汇报用中文，不要中英混杂，不要表格，不要代码块（用户复制不了）。
- **每次完成任务后必须详细汇报**：执行了什么操作、结果是什么、产物在哪里（文件路径、时间戳、大小、SHA-256）、有没有推送到 GitHub / 上传到 Release。不要只回「完成了吗？」或「需要帮忙吗？」，用户看不到工具执行过程，必须靠汇报知道干了什么。
- 卡住先搜网络和 GitHub，别硬编；资料记进 docs/参考资料.md。
- 用户说「做成跟 XX 一样」，先查清 XX 具体怎么做再设计（曾把 WorkBuddy 式远程误做成只能局域网）。
- 用户报的现象我没复现也当真，问清是什么场景再修。
- 每次做完更新三文档（当前任务覆盖、编年史追加、教训库追加），提交并推 GitHub；用户会提醒「做完记得更新档案」。
- 在云端窗口里干活时，上面这些偏好照样要守（本机的记忆云端看不到，所以写在这里）。
- 用户电脑上联网需要代理 127.0.0.1:10809（Gradle 的代理已配在 G:\DevCache\gradle-home\gradle.properties；git 已配 http.proxy）；云端环境不用。
- 用 Bash 工具写含反斜杠的内容会出错（双反斜杠被折成一个，\b、\1 变成控制字符），含转义的代码一律用 Write / Edit 写。
- WorkBuddy 的配置在 C:\Users\WHITEKING\.workbuddy，里面有 Key；系统不允许我翻它的设置文件，别去碰。
