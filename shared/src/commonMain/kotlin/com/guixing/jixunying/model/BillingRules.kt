package com.guixing.jixunying.model

/**
 * 各家「先扣什么、后扣什么」：手动填余额时给用户看，估算也照这个顺序（先扣资源包 / 免费额度的 token，再扣钱）。
 * 2026-10-12 查的官方文档，原话摘在 docs/参考资料.md「手动余额：各家扣费顺序」。没查到的平台不写（返回 null），只按价格表估。
 * @param packFirst 这家有「资源包 / 免费额度先扣」的说法：对话框里给填「还剩多少 token」那一栏
 */
object BillingRules {
    class Rule(val text: String, val source: String, val packFirst: Boolean)

    /** platform 是 Thinking.platformOf 的结果。 */
    fun of(platform: String): Rule? = when (platform) {
        "zhipu" -> Rule(
            "智谱：调用时先扣满足这个模型适用场景的资源包（有好几个就先扣最快过期的），用完再扣现金余额。资源包按 token 数扣，命中缓存的 token 怎么折算官方没写，这里按全数扣。",
            "https://docs.bigmodel.cn/cn/faq/fee-issues", packFirst = true,
        )
        "qwen" -> Rule(
            "千问AI平台：先扣免费额度，再扣资源包，最后按量付费。免费额度是每个模型各算各的（通常 100 万 token，一般 90 天有效），这里只能填一个总数、按总数扣。按量的钱先冻结、月末统一结算，账单显示要晚一到几个小时，所以控制台的数字和这里的估算可能对不上。",
            "https://platform.qianwenai.com/docs/resources/free-quota", packFirst = true,
        )
        "mimo" -> Rule(
            "小米 MiMo：按量付费按实际 token 从账户余额扣；Token Plan 套餐是另外买的，不能用余额抵扣；余额变成负数就不能再调用。",
            "https://mimo.mi.com/docs/en-US/quick-start/faq/payment", packFirst = false,
        )
        else -> null
    }
}
