package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/*
 * 手机和电脑互相同步账本和微信对话（2026-10-27 用户：「手机一个记录，电脑一个记录」「两边会用同一个 key，怎么计算金额」
 * 「开机的时候它会自动同步」「如果太大……就要学会避让，小一点慢慢的更新」）。engine/DeviceSync.kt。
 *
 * 两条指令都是对称的：发过去自己这边新的一批（push），对方合进去，再回它那边 pullSince 以后自己的一批。
 * 每条按内容去重，重发、两边同时发都不会多出来；只发本机自己产生的（origin 空的），别处同步来的不再转发。
 */

/** 账本里一家服务商是谁：接口地址 + Key 指纹（Key 本身不发）。对方按这个对到自己那家，对不上按地址。 */
@Serializable
data class SyncProviderRef(val url: String, val keyFp: String = "", val name: String = "")

@Serializable
data class LedgerBatch(
    val records: List<UsageRecord> = emptyList(),
    /** records 里出现的 providerId → 是哪家。 */
    val providers: Map<String, SyncProviderRef> = emptyMap(),
    /** records 里出现的 memberId → 成员名字（两边成员编号可能不一样，按名字对）。 */
    val members: Map<String, String> = emptyMap(),
    /** 这批没发完，还有。 */
    val more: Boolean = false,
    /** 这批最后一条的时间（下次从这里接着要）。 */
    val until: Long = 0,
    /** 回复里用：对方发来的那批里有几条是新的（重复的不算）。 */
    val added: Int = 0,
)

@Serializable
data class ChannelBatch(
    /** 哪个渠道，比如 weixin:<微信用户编号>。 */
    val channel: String,
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val members: Map<String, String> = emptyMap(),
    val more: Boolean = false,
    val until: Long = 0,
    val added: Int = 0,
)

/** 同步的情况（手机 连接电脑 页显示）。 */
@Serializable
data class SyncInfo(
    val running: Boolean = false,
    val lastAt: Long = 0,
    /** 上一次同步过来 / 发过去多少条（给人看的一句话）。 */
    val summary: String = "",
    val error: String = "",
    /** 正在让路：别的应用在大量上网、或者用的是手机流量。 */
    val yielding: String = "",
)
