# Code 栏设计说明

写于 2026-10-12，草案，**用户还没审**。写的人读过成熟的同类开源工具和 Claude 桌面 App 的官方文档；写代码的人没有读过，也不许去读。这份文档就是两边之间唯一的传话渠道。

## 0 使用规矩（写代码的 AI 必须先读这一节）

1. 只按这份文档和本仓库自己的代码做。不许去读或参考别的开源多 agent 工具的源码，也不许读本机上它们的文档：具体是 C 盘用户目录下 AppData\Local\codex-dual 整个目录，以及 D:\AI\ccb、D:\AI\docs 里文件名带 ccb 的文档。
2. 文档里没写清的地方，不要去翻上面那些，也不要猜成「大概别人是这样做的」。把问题记下来问用户。
3. 命名、提示词、目录结构、配置项名字都自己起，跟本仓库现有风格一致（见根目录 CLAUDE.md）。不许出现别的工具的名字、命令、提示词原文。
4. 不靠提示词里加限制词来保证安全（用户 2026-10-08 定的规矩）：路径越界、危险命令、权限这些，都在程序里卡住，不能指望模型自觉。
5. 第一期只做第 1 到 5 节。第 6 节手机触发、第 7 节多 agent 协作是后面的事，现在只为不挡路。

## 1 用户看到什么

仿 Claude 桌面 App 的 Code 栏。官方文档的转述在 docs/参考资料.md 末尾「Claude 桌面 App 的 Code 栏」。电脑端界面分两栏：对话栏（现有聊天）和 Code 栏，在顶部切换。

- 会话：Code 栏里每个会话 = 一个项目文件夹 + 一段独立的历史。会话之间互不影响，可以同时开几个，侧栏列出来，能看到哪些在跑、哪些做完了。
- 开会话前先选三样：项目文件夹、模型、权限模式。模型从用户在「设置 → 服务商」里已经加好的服务商和模型里选，Code 栏自己不单独收 Key。
- 做事过程：像聊天一样一条条显示，AI 说的话、它调用的工具（读了哪个文件、改了哪个文件、跑了什么命令）、命令的输出，都按顺序显示在会话里。工具调用默认折叠成一行，点开看细节。
- 改动审阅：AI 改了文件，会话上出现「改了 N 个文件，+X −Y」的标记，点开是差异查看器：左边文件列表，右边每个文件改了什么。每个文件（最好每一处）都能「接受」或「拒绝」。拒绝以后文件恢复成改之前的样子。第一期不做「在某一行写评论再让 AI 重改」，用户直接在输入框里说就行。
- 权限模式，第一期三档：
  - 手动：每次改文件、每次跑命令之前都停下来问，显示要改的差异或要跑的命令，用户点允许或拒绝。默认就是这一档。
  - 接受编辑：改文件不问（但事后能在差异里拒绝），跑命令仍然问。
  - 计划：只许读文件和做查找，不许改、不许跑会改东西的命令；AI 先给出计划，用户同意后再切到别的档去做。
- 不做「完全不问」这一档。想要的话以后用户明确提出再说。
- 中断：任何时候有「停止」按钮，点了立刻停掉当前的模型请求和正在跑的命令。
- 通知：会话做完、或停下来等用户批准时，在不在看那个会话都弹系统通知。

## 2 接什么模型

- 用户自己加服务商和 Key，我们不预设、不代付、不转卖。
- 支持两种接口：Anthropic 风格的消息接口，和 OpenAI 风格的聊天接口（国内大部分服务商用后一种）。本仓库已有的 LlmClient 和服务商预设能复用的就复用，缺的补。
- 只有支持「调用工具」的模型才能进 Code 栏的模型列表；不支持的灰掉并说明原因。
- 不做任何形式的「Claude 账号登录」，不读取、不保存、不转发用户的 Claude 订阅登录凭据。Anthropic 官方明确不允许第三方产品这样做（原文摘录在 D:\AI\docs\anthropic-claude-code-legal-20261012.md，这一份可以读，里面没有别人的代码）。用户想用订阅，以后由「外部 Claude Code」的方式解决，第一期不做，也不要给它留任何登录入口。（2026-10-18 做了，见第 15 节：设置里的「打开 Claude Code 登录」只是开一个 Claude Code 自己的窗口，登录在它的界面里完成，本程序照样不碰凭据。）

## 3 编码循环（程序怎么驱动模型）

一次「做事」的流程：用户发一条任务 → 程序把会话历史、工具说明发给模型 → 模型回答里如果带工具调用，程序执行工具、把结果回给模型 → 重复，直到模型不再调用工具，或用户中断，或达到上限。

工具，第一期这些就够：
- 读文件（可指定行范围）；
- 列目录；
- 在项目里按内容查找；
- 新建或整个重写一个文件；
- 对已有文件做局部修改（给出旧片段和新片段，旧片段在文件里必须正好出现一次，否则报错让模型重来）；
- 执行一条命令，返回退出码和输出。

要点：
- 工具结果太长要截断，并明确告诉模型「被截断了」；单次命令有超时（默认几分钟，可设），超时要真的把进程杀掉，包括它的子进程。
- 循环有最大步数，防止无限转；到了上限停下来告诉用户，不是悄悄结束。
- 历史太长时的压缩，复用现有「记录员」那套思路，但压缩后要保证最近几步的工具调用和结果原样保留。
- 模型报「不认这个参数」之类的 400，沿用现有的去参数重试办法。
- DeepSeek 带工具的请求要回传思考内容，现有成员的工具循环已经处理过，Code 栏同样要处理。
- 一个会话同一时间只跑一个循环；用户在它跑的时候又发消息，排队，不并发。

## 4 权限和安全（全部由程序保证）

1. **范围**：所有文件读写都限制在会话的项目文件夹里。路径要先规范化再判断（处理 ..、绝对路径、符号链接、Windows 的大小写和短文件名），指到文件夹外面的一律拒绝并告诉模型。
2. **命令**：命令的工作目录固定在项目文件夹。手动和接受编辑两档下，每条命令都要用户批准。另外内置一份「永远要问，哪怕以后加了自动档也要问」的危险命令清单（递归删除、格式化、改注册表、改系统环境变量、往项目文件夹外写、联网下载后直接执行等），判断在程序里做。
3. **环境**：执行命令时不要把用户的各家服务商 Key、代理设置等环境变量原样带给子进程；只给它运行所需的最小环境。
4. **撤销**：每次 AI 改文件之前，先保存改之前的内容。项目是 Git 仓库就用 Git 能还原的方式记录；不是仓库就在会话的数据目录里存副本。「拒绝」和「撤销这次任务的全部改动」都靠这个。
5. **日志**：会话记录里不要出现 Key。工具输出里如果出现了像 Key 的东西，显示时打码。
6. **计划档**：工具层面直接不提供写文件和执行会改东西的命令，不是靠告诉模型「别改」。

## 5 数据放哪

- 会话和历史放用户数据目录里（和对话分开的一个文件夹），每个会话一个文件，格式沿用现有的 JSON 风格，设置版本号（Settings.SCHEMA）有变化就按现有规矩加一并保留旧版不认识的项。
- 会话要记：项目文件夹、模型、权限模式、历史、改动记录（哪个文件、改之前的备份在哪、用户接受还是拒绝了）。
- 电脑关机重开后，会话能原样打开；正在跑的循环不恢复，标成「已中断」。

## 6 手机触发（第二期，现在只为不挡路）

**2026-10-28 被第 21 节推翻**：手机和电脑能做的一样，下面的「手机不能做的」和 10-13 细则只留作历史。

用户要求手机也能触发。原则：

- 走现有的加密中转，不新开通道。
- 电脑端设置里有总开关「允许手机使用 Code 栏」，**默认关**。
- 手机能做的：新开会话（只能选电脑上已经建过的项目文件夹，不能让手机随便指一个路径）、发任务、看过程、停止、批准或拒绝某一次具体的改动或命令。
- 手机不能做的：把权限模式改成比电脑上更宽松的档；「一键放行全部」；新增项目文件夹。
- 批准必须针对一次具体的改动或命令，在手机上能看到要改什么、要跑什么再点。
- 手机掉线或中转断了，等待批准的任务就停在那里等，**绝不能因为超时就自动放行**。
- 手机发起的会话，在电脑端有明显标记，电脑用户随时能中断。

2026-10-13 定的细则（上面没写清的三处由主会话定，用户可改；代码在 engine/code/PhoneGate.kt，安全核心）：
- 「比电脑上更宽松」：手动、计划两档手机随便换；「接受编辑」只有电脑上给这个会话选过（CodeSpec.pcMode）才能在手机上切过去。手机新建只能选手动或计划。
- 「已经建过的项目文件夹」= 电脑上已有 Code 会话用的文件夹（规范化后比，Windows 不分大小写），子文件夹、上一级都不算。
- 「撤销这次的全部改动」只在电脑上做；手机能一个文件一个文件地接受 / 拒绝。
- 不管开关开没开，手机发来的「保存设置」都保留电脑上的 Code 栏设置（不然手机能自己打开开关、换外壳）。

## 7 多个 AI 协作写代码（第三期，现在只为不挡路）

成熟的同类工具有几条通用做法，可以自己实现，用我们自己的话写下来。不要照搬它们的具体写法。

- **分工**：一个规划者，负责拆任务、验收；一个或几个执行者，负责按任务单干活、回报。规划者和执行者可以用不同的模型：规划者用强的，执行者用便宜的。
- **任务单**：规划者派给执行者的是一张明确的任务单（目标、范围、验收标准、不许做的事）。任务单有生命周期：已提交、排队、执行中、完成、失败、取消。状态只由一个调度者来改，不让几方各改各的；结果送回发起的一方。
- **怎么知道干完了**：靠执行者在一轮结束时明确发出的完成事件，不靠盯着界面输出猜。事件丢了要有超时兜底，并且明确告诉用户「没收到完成信号，状态不确定」，不是悄悄当成功。
- **分层记忆**：给每个 AI 的起始指令，由几层合成：全局规矩、项目共享、角色私有。空的一层跳过，但在界面上看得出这层是空的；同一件事不要在两层写两遍。
- **隔离**：每个执行者有自己的会话、自己的模型配置，不继承用户别的服务商的 Key 和代理设置。串线会花冤枉钱，也会泄露。
- **续接**：默认每次开始都是全新的上下文；用户明确选了「接着上次」才续接；续接失败要退化成全新并告诉用户。
- **判断进程还活着没有**：在 Windows 上，「能打开这个进程」不等于「它还活着」，要查它是否已经退出。后台任务要有心跳；重新连接要有超时和明确提示，不能无限空等一个早就死了的东西。
- **动手前要不要确认**：由用户设，默认要确认。

## 8 分期

- 第一期：第 1 到 5 节。电脑端 Code 栏，单个 AI，手动 / 接受编辑 / 计划三档，改动审阅，通知，会话保存。
- 第二期：第 6 节，手机触发。
- 第三期：第 7 节，多 AI 协作。
- 以后再说：外部 Claude Code 后端（要先问 Anthropic 销售）、完全不问的权限档、云端运行（本项目红线：只在本机跑）。

## 9 验收（第一期，能自动测的都要有测试）

1. 工具要读写的路径指到项目文件夹外面（含 ..、绝对路径、符号链接、短文件名），一律被拒，文件没有被碰。
2. 手动档下，AI 每次写文件、每次跑命令，都先停下来等用户；用户拒绝后，文件内容和改之前一模一样。
3. 计划档下，模型拿到的工具里没有写文件和会改东西的命令；即使模型硬要调用，也被拒绝。
4. 点「停止」后，正在跑的命令连同它的子进程都被杀掉，循环立刻结束。
5. 命令超时会真的杀进程；输出超长会被截断并明确告诉模型。
6. 局部修改时旧片段不存在或出现多次，报错并让模型重来，文件不变。
7. 「撤销这次任务的全部改动」后，项目文件夹回到任务开始前的样子（Git 仓库和非仓库各测一次）。
8. 关掉软件重开，会话和历史还在；跑到一半的循环显示为「已中断」，不会自己继续。
9. 子进程的环境变量里没有任何服务商 Key。
10. 用假模型服务器（和现有 EngineTest 一样的办法）把「读 → 改 → 问用户 → 接受 / 拒绝 → 停止」的流程跑一遍。真 Key 的检查放最后，花钱的先问用户。

## 10 已定的事（2026-10-12 用户把这五条交给我定，下面是我的决定和理由；用户觉得不对随时改）

1. 差异审阅的粒度：第一期做到「每个文件」可以接受或拒绝，不做「每一处」。理由：每个文件一份备份就够撤销，实现简单、不容易出错；等第一期稳了再做每一处。
2. 终端面板：第一期不做。AI 跑的命令和输出已经按顺序显示在会话里；用户自己要敲命令就用系统终端。
3. 命令外壳：Windows 默认用 PowerShell（Windows 10 自带，不用用户另装 Git），设置里可以改成 cmd，或者自己指定一个外壳程序（比如装了 Git Bash 的人）。给模型的工具说明里要写明「当前外壳是什么、工作目录是什么」，这是告诉它环境，不是加限制词。子进程的输出一律按 UTF-8 读，避免中文乱码。
4. 第一期的模型：服务商里所有支持工具调用的都能选，但只有实测跑通过的才打「已实测」标记，没测过的可选，选的时候提示一句「未实测」。第一期先拿 DeepSeek 的便宜模型跑通和调试，其余几家成员用到时再测。
5. 会话数据：放在用户数据目录下的 code 子文件夹，一个会话一个 JSON，旁边一个同名文件夹放改动备份。不自动删，用户手动删会话时连备份一起删；备份总量超过 1 GB 时在设置页提醒一次。
## 11 实现约定（2026-10-12 定，写代码按这个来；要改先问）

总体：Code 会话复用「对话 + 消息」的存储和推送（Conversation、Message、Event.MessageUpsert / MessageDelta，手机同步也跟着走），界面另做一套 CodeScreen。不新开一套存储。

数据（commonMain/model）：
- Conversation 加 `code: CodeSpec? = null`。不为空就是 Code 会话。CodeSpec：folder（项目文件夹绝对路径）、providerId、modelId、mode（CodeMode：MANUAL 手动 / ACCEPT_EDITS 接受编辑 / PLAN 计划）、fromPhone（第二期用，先留着，默认 false）。Code 会话的 memberIds 为空，channel 为空。
- ToolStep 加：id（每一步唯一）、output（工具输出，截断后的）、approval（"" 不用批准 / "waiting" 等用户 / "allowed" / "denied"）、change（FileChange?，这一步改了文件才有）。kind 用：read / list / grep / write / edit / run。
- FileChange：id、path（相对项目文件夹）、added、removed（行数）、created（原来没有这个文件）、review（"" 没处理 / "accepted" / "rejected"）。
- Settings 加 `code: CodeSettings`：shell（"powershell" / "cmd" / 自定义路径，默认 powershell）、commandTimeoutSec（默认 300）、maxSteps（默认 40）、allowPhone（默认 false，第二期用）。Settings.SCHEMA 加到 3，SaveSettings 里 schema < 3 的保留电脑上的 code 设置（照 office 那样）。
- 旧版手机不认识这些新字段会直接忽略（AppJson.ignoreUnknownKeys），不许改动已有字段的含义。

指令（Protocol.kt 的 Command，结果都是 CommandResult）：
- CodeCreate(folder, providerId, modelId, mode)：建会话，data 是新对话 id。文件夹必须存在。
- CodeSend(convId, text)：发任务，开始跑循环（同一会话已经在跑就排队）。
- CodeStop(convId)：停：取消模型请求、杀掉正在跑的命令，消息状态 STOPPED。
- CodeDecide(convId, stepId, allow)：批准或拒绝一步（写文件 / 改文件 / 跑命令）。
- CodeSetMode(convId, mode)：换权限档（只影响之后的步骤）。
- CodeDiff(convId, changeId)：data 是这一处改动的统一差异文本（unified diff）。
- CodeReview(convId, changeId, accept)：接受或拒绝一处改动；拒绝 = 把文件恢复成改之前的样子（原来没有的文件就删掉）。
- CodeRevertTask(convId, messageId)：撤销这一条回答里的全部改动。
- 删除会话用已有的 DeleteConversation，同时删掉它的备份文件夹。

引擎（jvmShared/engine）：
- 安全核心由主会话（读过别人源码的那位）写，放在 engine/code/ 下，写代码的 AI 只调用、不改：CodeGuard（把模型给的路径规范化、限制在项目文件夹里）、CodeShell（按设置的外壳在项目文件夹里跑命令：超时杀整棵进程树、输出按 UTF-8 读并截断、只给最小环境变量）、CodeBackup（改文件之前存原样，按 changeId 还原）。接口以主会话写好的代码为准。
- 编码循环写在 engine/CodeAgent.kt：用现有的 LlmClient.chat（带 tools）；工具结果超长截断并告诉模型；每一步先按 mode 判断要不要等批准（PLAN 档根本不给写文件和改文件工具，跑命令也一律要批准）；等批准时消息状态保持 STREAMING、对应 ToolStep.approval = "waiting"，用 CompletableDeferred 等 CodeDecide；拒绝时把「用户拒绝了」作为工具结果回给模型。到 maxSteps 停下并在消息里写明。
- Code 会话不触发记录员的后台活（压缩聊天、挑记忆、点名、立场档案、AI 核实），也不进联网搜索。
- 记账：UsageTag("code", convId = …)。
- 备份放在 <数据目录>/code/<convId>/。

界面（commonMain/ui）：
- 侧栏顶上加「对话 | Code」切换。对话那边的列表不显示 Code 会话；Code 那边列出 Code 会话（标题、文件夹名、在跑 / 等批准 / 做完的状态）和「新建 Code 会话」按钮。
- 新建对话框：选文件夹（Platform.pickFolder）、选模型（只列支持工具调用的；没实测过的标「未实测」）、选权限档（默认手动）。
- CodeScreen：上面是会话名、文件夹、权限档切换、「停止」；中间按顺序显示用户的话、AI 的话、每一步工具（折叠成一行，点开看输入和输出）；等批准的那一步显示要改的差异或要跑的命令，带「允许」「拒绝」；改了文件的回答下面显示「改了 N 个文件 +X −Y」，点开是差异查看器（左边文件列表，右边差异，每个文件「接受」「拒绝」）；下面是输入框。
- 手机上：第一期电脑端能用就行；手机上看到的 Code 会话先只读（第二期再做手机下令）。

测试：照 EngineTest 用假模型服务器；ShotsTest 加 Code 栏的几个场景（会话列表、等批准、差异查看器、新建对话框）。
## 12 照 Claude Code 补齐（2026-10-17 用户要求，推翻第 4 节第 1 条「范围」）

用户原话：「电脑 code 限制那么死，我怎么用来做开发，你是 Claude Code，你的 agent 是什么样子，你可以加进去，这才符合开发用的场景，而不是限制某个文件夹」「code 栏，你看看他们所有的 code 最基本的操作吧，都加上」「也可以学 WorkBuddy，在个性化里面自定义指令」。
起因：用户给 Code 栏 G 盘别处的路径，AI 读不到（CodeGuard 一律拒绝项目文件夹外面）；Code 栏也不能传图片、拖文件。

对照的官方原文（2026-10-17 读）：
- 权限：https://code.claude.com/docs/en/permissions —— 读文件、Grep「No, within the working directory and additional directories」不用批准；工作区外面的要批准；改文件要批准；Bash 要批准，「Yes, and don't ask again」对命令是按仓库和命令永久记住；`--add-dir` / `/add-dir` 加的目录「follow the same permission rules as the original working directory」；网络路径（\server\share）不能当工作目录，「looking one up can contact the host it names」。
- 接受编辑档只放行工作区里的写：同一页「the write isn't auto-approved in acceptEdits mode」（指向工作区外面时）。
- 工具：https://code.claude.com/docs/en/tools-reference —— Read 能读图片（「returned as visual content that Claude can see」）、PDF、notebook；还有 Glob、Grep、Edit、Write、Bash、WebFetch、WebSearch、TodoWrite / Task 等。
- 项目说明文件：https://code.claude.com/docs/en/memory —— 项目的 ./CLAUDE.md 或 ./.claude/CLAUDE.md、本人的 ./CLAUDE.local.md，没有 CLAUDE.md 时读 AGENTS.md；每次开会话都读进去。
- WorkBuddy：设置中心 → 个性化 → 自定义指令（腾讯云开发者社区多篇教程，例如 https://cloud.tencent.com/developer/article/2754715 ；官方文档 https://www.workbuddy.ai/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Setting 只写了语言、字号、显示模式，没写个性化页，未确认）；个性化页还有回复风格、称呼与身份（同上，第三方教程，未确认）。

做法（安全仍然全在程序里，不靠提示词）：
1. 工作区 = 项目文件夹 + 会话里加进来的文件夹（CodeSpec.extraPaths）。工作区里读不问；工作区外面的读、列目录、查找、写、改每次都先问，哪一档都问（接受编辑档也只放行工作区里的改动）。批准时可选「只这一次」或「允许，并把这个文件夹加进工作区」。网络路径、设备名、数据流照旧直接拒绝（engine/code/CodeGuard）。
2. 命令：批准时可选「同样开头这个会话里不再问」（CodeSpec.allowedCommands）。拼起来的命令（; & | ` > < $( 换行）和危险命令（递归删除、格式化、改注册表、改系统环境变量、iex、强推、硬重置……）永远要问、不给这个选项（engine/code/CodeCommands）。比 Claude Code 保守：记在会话上，不是整个仓库永久。
3. 会话上面加「工作区」按钮：看项目文件夹、加进来的文件夹（能加、能移出）、不再问的命令（能删）。
4. 手机：批准时的两个「顺便放宽」一律当成只这一次；改工作区（CodeSetAccess）直接拒（engine/code/PhoneGate）。手机只能批准具体一步这条不变。
5. 附件：Code 输入框和聊天一样能带附件（回形针、图片、拖进来、Ctrl+V）。图片只把最新一条任务里的发给能看图的模型；文档抽出文字接在任务后面；电脑上的文件把原路径写进任务、加进工作区（手机发来的路径清空）。read_file 照 Claude Code 的 Read：图片给能看图的模型看（工具结果放不了图片，所以这一轮工具结果回完后补一条 user 消息带上图片），PDF / Word / Excel / PPT 用 DocExtract 抽文字。
6. 个性化：设置里「我的资料」改名「个性化」，加「自定义指令」（Settings.personal.instructions，聊天每位成员的系统说明末尾和 Code 都用）和「Code 栏另外的指令」（codeInstructions，相当于 Claude Code 的个人 CLAUDE.md）。Settings.SCHEMA 升到 6，旧版手机发来的保存设置保留电脑上的。
7. 项目说明：Code 每次任务开头读项目文件夹里的 CLAUDE.md、.claude/CLAUDE.md、CLAUDE.local.md，这三个都没有才读 AGENTS.md；每个最多 3 万字；读了哪些记一步给用户看。没做往上级目录找。
8. 新工具：find_files（按文件名通配找，照 Glob；新改的在前）；web_search（和聊天同一套搜索引擎，不改本机，不问）；fetch_url（照 WebFetch 先问——读过本机文件的 AI 能把内容拼进网址发出去；可选「这个网站这个会话里不再问」，记成 web:网站 放在 allowedCommands）。
9. 命令「不再问」补充：python / node 等解释器带 -c / -e、再开一层外壳（powershell、cmd、bash…）的不给；python -m 记到模块名。
10. 仍然不做「完全不问」（绕过权限）档：第 1 节的决定没被推翻，用户要的话再加。

## 13 两栏定型、比 Claude 更开放（2026-10-17 用户定）

用户原话：「我们分两栏一栏 chat+cowork，一栏是 code」「我需要的不仅要 claude 那一套，而且比 claude 更开放」「对话和 code 同步……只是专业不一样」。
参照：Claude 桌面 App 官方三栏 Chat / Cowork / Code（https://code.claude.com/docs/en/desktop）；Cowork「read, edit, and create files in folders you specify」「uses the same agentic approach as Claude Code」（https://claude.com/product/cowork）。

1. 读：电脑上发起的，两栏都不问（推翻第 12 节「工作区外的读先问」）。手机发起的 Code 任务读工作区外照旧问（CodeSend.fromPhone → PhoneTask）；对话栏手机 / 微信发起的不许读收录范围外的。
2. 改：Code 栏手动 / 接受编辑照旧，新加「全部放开」（CodeSpec.openAll）：写文件、跑命令不问，危险命令（CodeCommands.dangerous）照样问。对话栏：拖进来的 / 选了「不再问」的文件夹（grantedPaths）里直接改，别处的先问（卡片带差异），「全部放开」（Conversation.fileOpen）都不问；手机 / 微信发起的不许改。两栏改之前都备份（<数据目录>/code/<对话 id>/），都能撤销。
3. 读网页：两栏都不问。
4. 手机：全部放开只能在电脑上打开；改对话设置保留电脑上的 fileOpen / grantedPaths / code。

## 14 开发一套补齐（2026-10-18 用户：「更新文档，全做」）

对照 https://code.claude.com/docs/en/desktop 的功能目录和 https://code.claude.com/docs/en/tools-reference 的工具清单排查出 13 样缺的。
规矩照旧：两栏能力同步（对话栏用得上的一起做），比 Claude 更开放，安全仍在程序里卡；手机端等电脑测过再跟。

1. 后台命令：run_command 加 background，开起来就返回编号；read_output 看输出、stop_process 停掉。会话上列出在跑的后台进程，能停。删会话、退出程序时全部杀掉。
2. 内置终端：Code 会话下面能打开终端面板，自己敲命令（在项目文件夹里跑，cd 记得住），和 AI 用同一个执行器。
3. 预览：preview 工具——用 Edge 无界面模式给网址截图，图片给能看图的模型看（改完自己看页面对不对）；会话上「预览」按钮用系统浏览器打开后台进程输出里出现的网址。
4. 待办清单：update_todos 工具——多步任务列成清单，做到哪一步在会话顶上看得见。两栏都有。
5. 输入框「@」补全文件名：Code 栏列项目里的文件，对话栏列拖进来的文件夹里的文件。
6. 长会话自动压缩：Code 会话历史太长时，早先的部分让记录员压成摘要（和对话栏一样），最近几轮原样保留。
7. 差异上逐行写评论：差异查看器里点一行写评论，攒几条后「按评论再改」发给 AI。
8. 回到这里（检查点）：Code 会话里用户的每条任务上有「回到这里」——撤销这之后的全部改动、删掉之后的消息，任务文字放回输入框。
9. PR 的检查状态：开过 PR 的会话顶上显示 CI 状态（gh pr checks）；失败了一键「让 AI 修」（把失败日志交给 AI）；可开「全过了自动合并」（gh pr merge --auto --squash）。
10. 独立副本（worktree）：新建 Code 会话可选「独立副本」——git worktree add 到项目的 .claude/worktrees/，在自己的分支上改，互不干扰；删会话时一起清掉。
11. 子代理：spawn_agent 工具——把一块独立的活交给一个新的 AI（自己一份上下文、同样的工具和权限），做完把结果交回。
12. 斜杠命令 / 技能 / MCP / 钩子：
    - 斜杠命令：项目 .claude/commands/*.md 和设置里自己写的，输入框打「/」补全，选了展开成任务；
    - 技能：项目 .claude/skills/*/SKILL.md，开头列给 AI（名字 + 说明），要用时它自己读；
    - MCP：设置里加 MCP 服务器（命令 + 参数，stdio），会话开始时连上，它的工具直接给 AI 用（权限照跑命令）；
    - 钩子：设置里「改完文件后跑」「任务做完后跑」两种命令（比如格式化、跑测试）。
13. 代码审查：内置斜杠命令 /审查（/review）——把这次未提交的改动（git diff）交给 AI 审，只看不改，按严重程度列问题。

## 15 外部 Claude Code（2026-10-18 用户：「这个 code 能不能接入终端，比如 Claude Code 终端，跟各种 AI 一起，claude 可以用订阅了」「先做外部 Claude Code」）

这是「多 AI 协作开发」的第一步：先让一个 Code 会话能交给用户电脑上的 Claude Code 去做；下一步是多角色派活（主控拆活、开发 / 审查 / 测试各用一个脑子），再接 Codex、Gemini。

1. 用法：Code 会话标题下面的「谁来干活」按钮（10-23 起；以前在「工作区 → 模型」）里选「Claude Code · 默认 / Opus / Sonnet / Haiku」（CodeSpec.providerId = claude-code，modelId 是给它的 --model 别名，空 = 它自己的默认）。一个模型都没加、但装了 Claude Code 的，新建 Code 会话直接用它。设置 → Code 栏「Claude Code（用你的 Claude 订阅）」：检查装没装、登没登录（跑它自己的 --version 和 auth status），没登录点「打开 Claude Code 登录」开一个它自己的终端窗口去登；claude.exe 不在默认位置的可以填路径。
2. 订阅的规矩（Anthropic 官方页面，原文摘录 D:\AI\docs\anthropic-claude-code-legal-20261012.md）：只许用户自己登录未改动的 Claude Code，我们只启动它；本程序不做 Claude 登录、不读不存它的凭据。将来发行营收前要问 Anthropic 销售「从我们的程序里启动它」算不算要签商业条款（未确认）。
3. 怎么跑（engine/code/ClaudeCli.kt，本机 Claude Code 2.1.302 实测的协议）：每条任务起一个进程 claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio --disallowedTools AskUserQuestion --permission-mode manual|plan [--model] [--resume 会话编号] [--add-dir 加进工作区的文件夹] [--append-system-prompt-file 自定义指令]。任务、附件（文档文字、图片）走标准输入，不放在命令行里（经 cmd.exe 会被引号弄坏）。环境变量去掉 CLAUDECODE、CLAUDE_CODE_SESSION_ID 这类（CCB 踩过：漏进去它以为自己是子会话），没设代理就带上设置里的代理。
4. 权限：除了计划档交给它自己的 plan，一律让它跑 manual——要改文件、跑它认为不只读的命令、读工作区外面的都来问（control_request can_use_tool）。我们按这个会话的档答，和自己的循环同一套：shouldAsk、命令规则（危险的永远问、「同样开头不再问」）、自动档的检查员、全部放开；要问用户的照样停在等批准的卡片上。改文件放行之前先 CodeBackup 存原样，它改完算差异挂上 FileChange——回答里照样看差异、拒绝、撤销整条、回到这里。手机发起的任务读工作区外面照样要问（它来问时我们停下等）。它的计划（ExitPlanMode）显示成「计划」一步，回它「不要动手」。
5. 显示：它的文字流进回答正文、思考进推理过程；读文件、查找、它自己判断只读直接跑的命令（比如 Get-Content）在结果回来时补记一步；子代理（Task）马上记一步；它的 TodoWrite 画成待办卡片；订阅额度到上限时记一步写清几点恢复。
6. 会话：它的 session_id 存在 CodeSpec.externalSession，下一条任务 --resume 接着说（它自己记得前面做过什么）；接不上（它那边清掉了）就开新的，把这个会话前面的对话（最多 2 万字）带过去。点停止 = 杀掉它连同它开的子进程。任务钩子没过时往它的标准输入再发一条，让它改一轮（只一轮）。
7. 和自己的循环不一样的地方：它判断为只读的命令（Get-Content、git status 之类）在手动档也不问——这是它自己的规则，我们收不到请求。它用的是它自己的工具和系统提示词，我们的预览、后台命令、MCP 设置不给它（它有自己的一套）。
8. 测试：ClaudeCodeTest 用 Python 写的假 claude（照真协议）走一遍手动 / 接受编辑 / 拒绝 / 计划 / 接不上会话 / 没登录 / 停止，还有从真输出摘的几行解析；LiveClaudeCodeTest（设 JXY_LIVE_CLAUDE=1）用本机真的 Claude Code（haiku）改一个文件：读 → 改（等批准）→ 跑命令确认 → 撤销，10-18 通过。


## 16 多角色派活（2026-10-19 用户：「直接做，不用问我」，接第 15 节的第二步）

第 7 节写的通用做法落到实处：一个主控拆活、几个角色按任务单干活，各用各的脑子。

1. 用法：Code 会话「谁来干活 → 团队」（10-23 以前在「工作区 → 团队」）给开发 / 审查 / 测试各选一个脑子：「不用」、Claude Code（默认 / Opus / Sonnet / Haiku），或者自己加的能调工具的模型。上面选的模型就是主控。一个都不选 = 不组队，和以前一样。存在 CodeSpec.team（TeamSeat：role、providerId、modelId），Command.CodeSetTeam 改，只能在电脑上改（PhoneGate）。
2. 主控：组了队的主控多一个工具 assign_task（role 只能填组了的角色，task 是任务单，name 可选），系统说明里列出手下有谁、各用什么脑子、干什么；一轮里派几张单子同时做。主控是 Claude Code 时不能组队（它调不了我们的工具），界面上写明。
3. 角色怎么跑（engine/CodeAgent.assign）：自己加的模型走 subAgent——一份新的上下文、同样的工具和权限，不能再派活；审查只给只读的工具（照计划档：没有 write_file / edit_file，系统说明也不提）。Claude Code 走 claudeWorker——每张任务单开一个新的 claude 进程（不 --resume，不记进会话编号），审查用它的 plan 档；它的步子、要批准的都记在主控这条回答上，它说的话不进回答正文，收起来当 assign_task 的结果交回主控。
4. 开场话（CodeAgent.roleIntro）：只讲角色是谁、交回什么，不加限制词（用户 10-08 的规矩）。
5. 怎么知道做完了：自己的模型不再调工具就是做完；Claude Code 是它发的 result 事件。步数到上限、出错都写进交回的结果里，不悄悄当成功。
6. 隔离：每个角色用自己那个服务商的 Key；Claude Code 用用户自己登录的那份。改文件照样改前备份，回答里能看差异、拒绝、撤销整条。
7. 测试：TeamTest 三条（自己的模型当开发 / 审查；Claude Code 当开发 / 审查，用 ClaudeCodeTest 的假 claude；选角色的规矩和手机把关）。LiveTeamTest（设 JXY_LIVE_TEAM=数据文件夹）：主控和审查 deepseek-flash、开发真 Claude Code haiku，修一个写错的 add 函数，10-19 通过（70 秒，主控派了开发和审查、自己核对、汇报里写清了开发贴的验证命令其实跑不通）。
8. 没做：测试角色没有专门的工具（和开发一样）；手机端界面只能看不能改团队。接 Codex、Gemini 见第 17 节。

## 17 接 Codex、Gemini CLI（2026-10-20 用户：「按甲做，直接做」，第 15 节说的第三步）

两家的无界面模式都不能一步步问权限，改文件之前也不通知（本机实测，docs/参考资料.md「Codex / Gemini 命令行无界面模式」）。10-19 我给了甲乙两个方案，用户选甲：

1. 用法：Code 会话「谁来干活」的模型列表和「团队」的下拉里多了「Codex · 默认」「Gemini CLI · 默认」（CodeSpec.providerId = codex / gemini-cli，modelId 空 = 它们自己配的默认；模型名不凭记忆写，以后要选再从它们自己的配置里查）。设置 → Code 栏「Codex 和 Gemini CLI」：各自装没装、版本（跑 --version），Codex 再跑 login status 看登没登录；Gemini 没有不进界面查登录的命令，只报版本；位置可以自己填（CodeSettings.codexPath / geminiPath），空着就在 PATH 和 %APPDATA%\npm 里找。主控是它们时不能组队（和 Claude Code 一样，它们调不了 assign_task）。
2. 怎么跑（engine/code/AgentCli.kt）：每条任务起一个新进程，任务走标准输入（codex exec --json --skip-git-repo-check -s <沙箱> [-m] [--add-dir] - ；gemini -p . --output-format stream-json --skip-trust --approval-mode <档> [-m] [--include-directories]），写完就关输入。10-20 实测两家都认标准输入（Gemini 把 -p 的字接在后面，所以只给一个「.」）。不接它们自己的会话，把这个会话前面的对话（最多 2 万字）带进去；图片不带（附件只带文档文字）。用户的自定义指令放在任务前面；它们自己会读项目里的 AGENTS.md / GEMINI.md。
3. 权限（方案甲）：手动档（没开自动、全部放开）交出去之前弹一张批准卡片，整张单子问一次，不同意就不启动；接受编辑、自动不弹。计划档和审查角色只读：Codex -s read-only、Gemini --approval-mode plan，并在任务前写一句「这次是只读的」。其余 Codex 用 workspace-write 沙箱、Gemini 用 auto_edit（要批准的命令它会跳过）；全部放开时 Codex danger-full-access、Gemini yolo。
4. 撤销靠快照（engine/code/Snapshot.kt）：不是只读时，交出去之前把工作区（项目文件夹 + 加进来的）里的文件原样记一份，跳过 .git、node_modules、build、.gradle 这类目录；单个文件超过 1MB 只记大小和修改时间；最多 2 万个文件、64MB。跑完（停下、出错也算）再扫一遍比对：改了的、新建的、删掉的各记一步，原样交给 CodeBackup.saveBytes，挂上 FileChange——回答里照样看差异、拒绝、撤销整条、回到这里。超过 1MB 的只说「改了、撤销不了」。没扫完时只报改了和删了的，不报新建（后扫到的可能是当时没扫到的老文件，拒绝新建会删文件）；界面上写明只比对了多少个。同一个会话里会改文件的 Codex / Gemini 一次只跑一个（快照按会话算，两个同时改分不清）。
5. 手机：手机发起的任务不交给它们（它们自己读文件，管不住「不许读收录范围外」这条），主会话直接报错说明，团队里派给它们的单子交回「你自己做」。
6. 显示：它们说的话进回答正文（团队角色收起来交回主控）；Codex 的 reasoning 进推理过程；跑的命令、读写文件、搜索各记一步（Codex 的命令去掉外面那层 powershell -Command）；Gemini 自己记思路的 update_topic 不显示；Codex 不致命的 error 条目记成一步「提示」。报错取出里面那层 message（Codex 的报错是一整段 JSON），没登录、额度用完各给一句怎么办。
7. 和 Claude Code 不一样的地方：中间不能拦——手动档也只能整张单子问一次；改工作区外面的文件我们比对不到（Codex 的沙箱只许写工作区，Gemini auto_edit 能不能写外面没实测，未确认）；同一时间别的角色（自己的模型）也在改文件时，它的改动会同时记在两边（都能撤销，撤销到同一个原样）。
8. 测试：AgentCliTest 五条（两家的输出解析和启动参数、快照比对、Codex 接受编辑改建删并撤销、Gemini 手动档先问、计划档只读 + 报错 + 手机不交），假 codex / gemini 是 python 脚本，照实测格式输出；TeamTest.cliAgentsAsRoles（开发 Gemini、审查 Codex 只读）。LiveAgentTest（设 JXY_LIVE_AGENT=codex,gemini）用本机真的 Codex、Gemini 修一个写错的 add：10-20 两家都通过（Codex 56 秒、Gemini 34 秒），改动比对出来、撤销回原样。

## 18 Claude Code 当总指挥（2026-10-22 用户：「能不能一个 Claude Code 统领所有AI 所有 agent，完成自动化工作流」「我看了你说的，你来觉得怎么做，全权交给你处理」）

方向（10-22 定，长期记忆和当前任务里也记了）：两套并存。Claude Code 当总指挥和主力；自己的 agent 当工人（给 DeepSeek 等便宜模型干体力活）和备胎（Claude 额度满、断网、在手机上）；不再追平 Claude Code 已有的功能，力气放在它没有的：多家 AI 协作、看得清每个 AI 在干嘛、权限和撤销、花费、手机遥控、自动化流程。

1. 用法：Code 会话的模型选 Claude Code，再在「谁来干活 → 团队」给开发 / 审查 / 测试选脑子（自己加的模型、Codex、Gemini、另一个 Claude Code）。组了队的 Claude Code 会话就是总指挥；不组队和以前一样。以前界面上写「主控是 Claude Code 时不能派活」（CodeScreen.kt 团队那一节），改掉；Codex / Gemini 当主控仍不能组队（它们也能接 MCP，没核对）。
2. 怎么接：MCP 的 Streamable HTTP（规范 https://modelcontextprotocol.io/specification/2025-06-18/basic/transports ；Claude Code 的接法 https://code.claude.com/docs/en/mcp ）。
   - 电脑端在本机 127.0.0.1 随便一个空端口起一个 HTTP 服务（JDK 自带的 com.sun.net.httpserver，引擎在两端共用，所以服务放在 desktopMain，引擎经接口拿端口；打包的运行时加 jdk.httpserver 模块）。路径 /mcp：POST 收 JSON-RPC；通知回 202 不带内容；请求回 application/json；GET 回 405（不开推送流）；DELETE 回 200。
   - 每次把任务交给组了队的 Claude Code：生成一个随机口令，写一份临时的 MCP 配置（mcpServers.jxy：type http、url、headers 里 Authorization: Bearer 口令、timeout 1800000），用 --mcp-config 交给 claude；这一轮做完口令作废、配置删掉。
   - 安全（规范原文要求）：只认口令对的请求（不对回 401）；有 Origin 头且不是本机的回 403（防网页借浏览器打本机端口）。
3. 给它的工具（Claude Code 里叫 mcp__jxy__…）：
   - assign_task(role, task, name?)：把一张任务单派给团队里的一个角色，马上返回编号（T1、T2…），角色在后台干；一次可以派几张同时干。
   - wait_task(ids?, seconds?)：等这些编号做完（不写就等全部），最多等 600 秒；做完的交回角色的结果，没做完的写还在做、做了多久，可以接着等。
   - list_tasks：这一轮派过的单子和状态。
   官方原文：服务器条目的 timeout 至少 1000 时，Claude Code 判空闲超时不会早于它（HTTP 默认空闲 5 分钟就断）；所以 timeout 设 30 分钟，wait_task 一次最多等 10 分钟。
4. 角色怎么跑：和第 16 节一样走 assign——自己的模型走 subAgent、Claude Code 走 claudeWorker（新开会话）、Codex / Gemini 走 agentWorker；步子都记在总指挥这条回答上，要批准的照样弹，权限照这个会话的档。总指挥调这几个工具不用批准（mcp__jxy__ 开头的直接放行）；角色干活该问的照问。
5. 给总指挥的说明（附加说明文件 --append-system-prompt-file 里加一段）：团队里有谁、各用什么脑子；用 assign_task 派、wait_task 收；角色的每一步用户都看得见。只讲清楚怎么用，不加限制词（用户 10-08 的规矩）。
6. 结束和停止：总指挥这一轮结束时还有没收的单子，停掉，回答里写明哪几张没等结果；用户点停止，总指挥和所有角色一起停（角色在总指挥这一轮的协程里跑）。
7. 手机：手机发起的任务不给这几个工具（Claude Code 只在电脑上，角色要批准的也在电脑上）。
8. 测试：CommanderTest——MCP 服务（口令错 401、Origin 不对 403、GET 405、通知 202、initialize / tools/list / tools/call）；假 claude（python）照 --mcp-config 调我们的服务：派开发（假模型改文件）→ 等结果 → 汇报。LiveCommanderTest（JXY_LIVE_COMMANDER=数据文件夹）：真 Claude Code 当总指挥、开发 deepseek-flash，修一个写错的 add。
9. 下一步：流程模板（开发 → 审查 → 测试 → 没过就修 → 提交，一条龙自己转）；手机上看总指挥和各角色的进度。
10. 改动摘要（10-22 做了，第 9 条原来的第 2 样）：角色改了哪些文件由程序记，不靠它自己说。角色干活的协程上下文里放一个 RoleChanges（engine/RoleChanges.kt），写文件、改文件、Claude Code 改完、Codex / Gemini 比对出来的改动都记到它上面；同一个文件改几次合成一行。交回结果时接一段「【程序记的改动】改了 N 个文件，+X −Y」，每个文件一行（新建、删掉、大文件没行数分开写）；没改文件也写「这张单子没有改文件」。自己的主控（assign_task）和总指挥（MCP）都走 assignRole，所以两边都有。几张单子同时跑各记各的；Codex / Gemini 是整个工作区比对，同时有别的角色在改时会把别人的也算进来（第 17 节第 7 条）。测试：TeamTest 两条加了断言。

## 19 流程模板：一条龙（2026-10-22，第 18 节第 9 条第 1 样；用户「全权交给你处理」「继续其他任务」）

1. 用法：「谁来干活 → 团队」组了开发、又组了审查或测试（至少一个），下面多一个开关「一条龙」。打开后，这个会话发的每条任务由程序按流程转：开发 → 审查和测试同时看 → 有没过的，把意见交回开发再改 → 都过了就提交。最多转 3 轮（FLOW_ROUNDS），第 3 轮还没过就停下，回答里写清卡在哪。存在 CodeSpec.flow（字符串，"" = 不用、"devflow" = 一条龙；不做成枚举，旧版手机读不坏）；Command.CodeSetFlow 改，只能在电脑上改（PhoneGate）。
2. 为什么由程序转，不交给总指挥自己转：第 7 节的通用做法「状态只由一个调度者来改、靠明确的完成信号判断」；程序转每次都一样、看得清走到第几步；Claude 额度满、断网时角色换成自己的模型照样能转（备胎）。开着一条龙时，上面选的「模型」不参与（不花它的钱），角色用各自的脑子；关掉就回到原来的做法（总指挥 / 主控自己派活）。
3. 每一步给角色的话：开发拿用户的任务原话（加上这个会话前面最近几条对话，最多 6000 字，让它知道上下文）；第 2 轮起再接上「上一轮审查 / 测试没过，意见如下」。审查和测试拿用户的任务原话 + 开发交回的话（带程序记的改动摘要，第 18 节第 10 条）。审查、测试的开场话多一句：最后单独一行写「结论：通过」或「结论：没过」（这是交回的格式，不是限制）。
4. 怎么判过没过：认交回的话里最后一个「结论：通过 / 没过」。哪个角色没写结论，流程停下，回答里写明「没给结论，状态不确定」，不悄悄当成过了（第 7 节）。开发出错（模型用不了、Claude Code 报错）也停下。开发这一轮没改任何文件，照样交给审查 / 测试看（可能本来就没问题）。
5. 提交：都过了，并且这个会话是独立副本（CodeSpec.worktreeOf 不为空，副本里的改动都是这个会话的）才在本地提交（GitHub.commit，git add -A；不推送），提交信息是任务第一行 +「（一条龙第 N 轮通过）」。不是独立副本时不提交，回答里说「改动留在工作区没提交：不是独立副本，怕把你别的改动一起提交」。手动档提交前照样弹批准（和跑命令一样）。
6. 显示：每一步就是原来的角色步骤（「开发：第 1 轮」「审查：第 1 轮」……），回答正文由程序写：每轮谁说了什么结论、最后过没过、提交了没有、提交编号。点停止，正在干的角色一起停。
7. 手机：手机发起的任务照样能走一条龙（角色的手机规矩照旧：Codex / Gemini 不接手机发起的单子，第 17 节第 5 条）；开关只能在电脑上改。
8. 测试：FlowTest——假模型当开发 / 审查 / 测试：第 1 轮审查没过、第 2 轮都过、独立副本里提交；没写结论就停；不是独立副本不提交。

## 20 Claude Code 的思考强度（2026-10-22 用户：「用 --effort 传给它，档位照官方：low、medium、high、xhigh、max。还有一个ultracode，都加上。可以自己选。」）

起因：用户觉得 Code 栏里的 Claude Code「跟原生不像一个模型」。查了：模型一样（会话记录 237 条都是 claude-opus-5-5），我们没传 --effort（官方：Opus 5.5 默认 medium）；那次显得笨主要是会话里带着「别碰手机」的旧指令和全局红线（编年史 10-22）。

1. 「谁来干活」里选了 Claude Code 时多一行「思考强度」：默认（不传，官方 medium）、low、medium、high、xhigh、max、ultracode。存在 CodeSpec.effort，Command.CodeSetEffort 改（手机遥控时和其它 Code 写操作一样要电脑开了「允许手机用」）；会话标题下面的那行带上档位。
2. 原样交给 --effort（ClaudeCli.args）。官方原文（https://code.claude.com/docs/en/model-config ）：Opus 5.5 / Sonnet 5.5 / Haiku 5.5 都支持 low～max；「If you set a level the active model does not support, Claude Code falls back to the highest supported level at or below the one you set」；ultracode：「launch with `claude --effort ultracode`, which starts the session at `xhigh` effort with ultracode on」，要 2.1.203 以上。本机 claude 2.1.308 实测 -p --effort low、--effort ultracode 都正常回答。
3. 团队里的 Claude Code 角色也用这个会话的档（它们走同一个 claudeRound）。Codex / Gemini 不管这一项。
4. 测试：ClaudeCodeTest.acceptEditsWritesWithoutAskingAndResumes 加了：没选不带 --effort；选 ultracode 带 --effort ultracode；不认识的档不收。

## 21 手机和电脑一样、做事中途能插话（2026-10-28，推翻第 6 节的手机限制和第 3 节「排队」那条）

起因：10-27 晚上（北京时间 10-28 上午）用户在手机上遥控电脑，让 Code 会话处理服务器断线的事。会话只能挂在电脑上建过的文件夹上（记账本的独立副本），资料在 F 盘全算「工作区外面」；手机发起的任务读工作区外面也要问；手机上只能切手动、计划，自动档一切走就回不来。第一个会话 101 步里 51 步要用户点允许或拒绝，用户在里面说「别问我直接通过」「停，别再问我了」，它在排队听不到。用户：「我之前说过了，不要限制，要比 code 更开放……更开放难道我得一秒钟不待，我一直点上百次允许吗？」「我跟他说话他不听……我也不知道从哪暂停他」。

1. 手机和电脑一样（engine/code/PhoneGate.kt）：电脑上打开「允许手机用 Code 栏」后，新建会话用电脑上任意存在的文件夹（手机上能直接输入路径，也能从建过的里挑）、五档权限都能选（新建时就能选全部放开 / 自动，Command.CodeCreate 多了 openAll）、换档、改工作区和「不再问」、批准时顺便记住、换项目文件夹和模型、团队、一条龙，都放行。手机发起的任务读工作区外面不再问，也能交给外部 Claude Code / Codex / Gemini、Claude Code 照样当总指挥（CodeAgent 里的 PhoneTask 整个拿掉）。理由：配对过的手机本来就能一步步点允许，这些限制挡不住拿着手机的人，只让主人多点几十次；真正的闸门是配对和电脑上那个开关，这两样不变。还是只在电脑上做的：撤销整条回答的全部改动、「回到这里」（一次动一大片，手机屏幕小容易点错）、终端、提交和上传、各种登录、账号备份码。
2. 对话页对齐（Engine.phoneLocked）：手机发起的这一轮，电脑上开了那个开关就和电脑上一样（读、改、跑命令按这个对话的档来问或不问）；没开照旧不许。微信发来的照旧不许。
3. 做事中途插话（CodeAgent.submit / takeInterjections / afterToolsMsg）：自己的模型在跑时，用户新发的话不再排到这一件做完——下一步就交给模型；正在等批准的那一步按「先放下」处理（步骤上写「你插了话，这一步先放下了」），模型同时看到「那一步先放下了」和用户的原话。回答在插话处分成前后两段，用户的话夹在中间，界面上顺序就是说话的顺序。外部 Claude Code（2026-10-28 同一天补上，用户：「为什么 Claude Code 用终端的时候……我可以随时打断你呢？为什么在那个软件就做不到呢？」）：先往标准输入发 control_request（subtype interrupt）喊停这一轮，它回 control_response、出一条 error_during_execution 的 result，进程不退；收到这条 result 时把插的话在同一个进程里当新的 user 发过去（它记得前面），回答在这里分段；被喊停的那段不算出错。依据：官方 streaming input 模式写着能中途喊停、接着发消息（https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode），本机 2.1.312 实测（写长文中途喊停，接着问「刚才在写什么」，答「《茶的历史》」）。Codex / Gemini、一条龙：进程里插不进去，照旧排队，输入框下面写清「要马上改方向先点停」。
4. 停止键：在做的时候，输入框发送键旁边一直有停止键（以前只在顶栏，手机上会被提示条挡住）。
5. 新建会话的权限档：五档都列，默认照最近建的那个会话的档（用户上次选了全部放开，下次就默认全部放开）。
6. 测试：PhoneGateTest 改成手机任意文件夹、五档、改工作区都放行，开关关着照样都拦；CodeAgentTest.interjectionReachesModelMidway（等批准时插话：那一步放下、模型收到原话、回答分两段、做完不再另起一轮）；ChatFilesTest 加了开关开着时手机发起的命令照跑；AgentCliTest 手机发来的也交给 Codex；ClaudeCodeTest.interjectionInterruptsAndContinuesInSameProcess（假 claude：喊停、同一个进程接着说、回答两段）；LiveClaudeCodeTest.realClaudeHearsInterjection（真 claude，插话后 8 秒答完、记得题目）。

## 22 无人值守（2026-10-28，用户：「现在能不能做到我给他一系列的任务让他自己去干，我全程一天不带管的，我不需要点任何一个允许，能不能做到？开额度重置后自己自动起跑」）

权限档多一档「无人值守」= 全部放开 + 下面四样（CodeSpec.unattended，和 openAll 一样不做成 CodeMode 新值）。新建会话时就能选，手机上也能选。
1. 危险命令（CodeCommands.dangerous：递归删除、格式化、改注册表……）不等人：步骤上记「无人值守：危险命令没跑」，回给模型「换个不危险的办法；绕不开就跳过，在最后的汇报里写明要用户回来做什么」。自己的模型（runCommand）和 Claude Code（claudePermission）都这样。不选「自动放行」：没人看着的时候删错了救不回来。
2. 自己的模型一轮走满 maxSteps 步不停：这一轮剩下的调用各回一句「先没做」（接口要求每个调用都有结果），再补一句「这一轮走满了，接着做」进下一轮；最多 UNATTENDED_ROUNDS（10）轮，免得原地打转烧钱。
3. 模型一时调不通（429 限流——不含智谱那种「余额不足」也报 429 的、408、5xx、网络）：记一步「模型暂时调不通」，30 秒起翻倍、最长等半小时，最多 8 次。Key 不对、没钱照旧直接报错。
4. Claude Code 订阅额度用完（rate_limit_event 不是 allowed，或者报错里有 usage limit / session limit / hit your … limit）：回答里写「北京时间几点前后恢复，到点自动接着做」，会话上记 resumeAt，等到点用 --resume 在同一个会话里发「额度恢复了，接着刚才没做完的事做完」；没说几点就半小时后再试，最多等 6 回。等的时候点停止照样停（清掉 resumeAt）。程序重开时还记着 resumeAt 的，电脑端接上执行器后（Engine.codeRunner 的 set）CodeAgent.resumePending 到点照样接着做。排队的下一件任务在这一件做完以后接着跑。
测试：CodeAgentTest.unattendedNeverWaitsAndKeepsGoing（危险命令不等、一直读走满 11 轮、切回手动又会等）；ClaudeCodeTest.unattendedWaitsForQuotaThenResumes（假 claude 报额度用完，2 秒后到点、第二个进程带 --resume 接着做完、resumeAt 清掉）。
没做：Codex / Gemini CLI 的额度等待；手机上看不到「几点接着做」以外的倒计时。
