# ChatGPT 订阅设计说明（用 ChatGPT 登录）

2026-10-23 用户：「我现在还有订阅，我在想 GPT 订阅能不能加进聊天里，走终端转过去」；同一天撤掉了「成员固定四家」（CLAUDE.md）。

## 1 走哪条路

查了两条（出处在 docs/参考资料.md「用 ChatGPT 登录」）：

- 甲：在对话页里启动用户电脑上的 Codex 命令行（它用 ChatGPT 登录），把聊天转给它。Codex 是写代码的，每条都要起进程、带工作区，当聊天成员很别扭；Code 栏已经这么接了（Code栏设计说明第 17 节）。
- 乙：OpenAI 2026-09-29 开发者大会公布的官方通道「Sign in with ChatGPT」：开源和本机运行的程序，让用户在 OpenAI 页面上同意，就能用他自己的订阅额度调 Responses 接口。官方文档原文：plan usage for "open-source and locally hosted apps"；收费或放在云上的程序要先填申请表（"If you're interested in offering it in a paid or remotely hosted app, complete the interest form"）。

选乙：官方明说允许，和别的成员一样走引擎、工具、记忆、立场档案，不用起进程。仓库是公开的、程序在用户本机跑；**以后要收费，先填 OpenAI 的申请表**。不碰 ChatGPT 的 backend-api（官方："do not point it at ChatGPT's backend-api endpoints"）。

## 2 用法

设置 → 模型服务 → 添加服务商 → 「ChatGPT 订阅（用 ChatGPT 登录）」（presetId = chatgpt，海外组，默认走代理）。不填 Key，点「用 ChatGPT 登录」：浏览器打开 OpenAI 页面，用户登录、同意；跳回本机后程序自己拉一次模型列表。然后像别的服务商一样建成员。花费页按 0 元记（订阅额度，Prices 里 chatgpt 一律 0）。

## 3 登录（engine/ChatGptAuth.kt，照官方 sign-in 页）

- 授权：https://auth.openai.com/api/accounts/authorize；令牌：https://auth.openai.com/api/accounts/oauth/token（表单 POST，不要密钥）。
- 第一次 client_id = dynamic_agent_client 并带 agent_name_hint（「AI集训营」）；回调里给正式的 client_id（oaiapp_…），存下来以后都用它（官方：dynamic_agent_client 只是第一次注册的入口）。
- ext_agent_host_id：每台设备一个，urn:uuid:随机，第一次登录前定好。
- scope：openid profile email offline_access resource.invoke chatgpt.tokens.use.direct；resource = https://api.openai.com/v1；PKCE S256；state、nonce 每次新的。
- 回调：http://127.0.0.1:端口/auth/callback，端口先试 1455、占了换一个（官方：只有端口能变，不许写 localhost、路径不许改）；先开好端口再开浏览器；等 5 分钟。
- 校验 ID 令牌：签名对 openid-configuration 里 jwks_uri 的公钥（RS256），iss、aud（正式 client_id）、exp、nonce；用 sub 当账号身份。没给 chatgpt.tokens.use.direct 就显示「已登录但不能用订阅额度」。
- 令牌：访问令牌 1 小时，剩不到 2 分钟就先刷新（grant_type=refresh_token、client_id、refresh_token）；刷新令牌每次换新的。刷新报 invalid_grant / refresh_token_reused 等（官方「Refresh errors」）就清掉令牌、留着 client_id 和设备编号，让用户再登。退出也一样。
- 存放：<数据>/chatgpt/auth.json，只给本人读写；**不进 state.json、不同步到手机**（刷新令牌一用就换，两台设备共用会互相顶掉）。state 里只有 ChatGptInfo（登没登录、邮箱、能不能用额度、正在登录、报错）。
- 只能在登录的那台设备上点：手机遥控电脑时界面写明「要在电脑上点登录」。手机本机也能自己登（Android 上一样开 127.0.0.1 端口），没在真机试过（未确认）。

## 4 调模型（engine/ResponsesApi.kt，照官方 Models and inference、Preview limitations）

- POST {base}/responses，Authorization: Bearer 访问令牌；每次 store=false、stream=true；不传 temperature 等官方列了不支持的字段（background、conversation、max_output_tokens、max_tool_calls、metadata、moderation、multi_agent、prompt、prompt_cache_retention、safety_identifier、temperature、top_logprobs、top_p、truncation、user）；不用 previous_response_id，历史全放 input。
- 引擎里是 chat/completions 格式，这里转：开头的 system → instructions（这条路不收 role=system 的消息）；中间的 system → developer 消息；用户文字 / 图片 → input_text / input_image；AI 说过的 → output_text；tool_calls → function_call；工具结果 → function_call_output。
- 函数工具放进一个命名空间（type namespace、name jxy；官方：「Group function/custom tools in namespaces or supply them through additional_tools」）；平台内置工具（智谱 web_search 那种）不传，联网走我们自己的 web_search。模型回的 function_call 带了 namespace 就记下，回放历史时原样带回（按 call_id）。命名空间里的调用实际回来长什么样，官方没写，假服务器按「带 namespace」测的（未确认，真登录后看）。
- reasoning: {summary: auto}，推理摘要进「推理过程」；服务端报 subscription_sharing_unsupported_capability 且 param 是 tools / reasoning 就去掉重试并记住。401 先强制刷新一次。
- 只认 response.completed 为成功；response.failed / error 按官方错误码归类：usage_limit_exceeded（429，额度用完，给人话、不重试）、usage_unavailable / user_unavailable（503）、invalid_user（401，重新登录）、user_not_eligible（403，这个账号不能用）。
- 模型列表：GET {base}/models，读 models[]，只留 visibility = "list" 的，模型名用 slug。

## 5 测试

ChatGptTest：假登录服务（openid-configuration、jwks、token）+ 假 /v1/models、/v1/responses。走一遍：没登录调模型报「登录」→ 登录地址参数逐个核对 → 模拟浏览器回调 → 校验签名 → 拉模型（藏起来的不要）→ 令牌快过期先刷新、刷新令牌换新 → 请求 store/stream/没有 temperature → 额度用完给人话 → 退出 → 再登录用存下的 client_id；另一条测格式转换和工具来回（namespace 带回、function_call_output、developer）。

## 6 没做 / 未确认

- 真登录没试（要用户本人在 OpenAI 页面上点）；从国内走代理 OpenAI 会不会按地区拒（官方 403「permitted serving region」）未确认。
- 思考强度（reasoning.effort）没接，Thinking.kt 里 chatgpt 是「没查到」，两档都灰。
- 联网：官方说网页搜索「受模型和账号策略管」，我们不传它的内置搜索。
- 手机本机登录、手机遥控时手机上的成员用电脑上的登录（遥控时本来就是电脑在调，应该可以）都没真机试。
