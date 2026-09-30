# AI集训营 — 给 AI 助手的入口

开会话先读：docs/当前任务.md → docs/教训库.md → 需要时再读 docs/构想.md。
做完一件事：更新 docs/当前任务.md；有决定或里程碑写进 docs/编年史.md（带日期）；踩了坑写进 docs/教训库.md。

## 红线

- 不做网页版，只做 Windows 桌面版（将来并入安卓「癸星」）。
- 前身 G:\ai-discussion-app 只读参考，不改它的代码。它的 localStorage key `discussion_state_v3` 和 appId `com.taiclear.taic` 永远不许改（改了用户手机上的 Key 和配置全丢）。
- 癸星（F:\guixing）的源码包名 `com.guixing` 不许动，原因见癸星仓库 sync-to-public.sh 开头注释。并入癸星前先读癸星的 CLAUDE.md。
- 工程目录用 ASCII 名 `ai-jixunying`，不要改成中文路径（Gradle / Android 工具链对非 ASCII 路径有问题）。
- 所有显示名、包名、文件名按 docs/命名规范.md，不许再出现 AI他妈 / aichatroom / taic 等旧名。

## 用户偏好

- 汇报用中文，不要表格，不要代码块（用户复制不了）。
- 卡住先搜网络和 GitHub，别硬编。
- 联网需要代理 127.0.0.1:10809。
