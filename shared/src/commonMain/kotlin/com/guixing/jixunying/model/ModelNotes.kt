package com.guixing.jixunying.model

/**
 * 各模型自带的毛病（2026-11-06 第一轮找毛病 LiveQuirksTest 的结果，全文 G:\DevCache\quirks-r1；用户：「把这五个各有的毛病都带上，用个小字儿做一个提示；智谱是最干净的，也带上」
 * 「以后我们聊天模型的话，首选智谱」）。界面上在成员下面用小字显示。
 * 每位 13 道题 × 本应用 / 裸调各 3 次；「带设定」= 在本应用里聊，「不带设定」= 直接调接口。只写三次里反复出现的。
 */
object ModelNotes {
    class Note(val text: String, val preferred: Boolean = false)

    fun of(modelId: String): Note? {
        val m = modelId.trim().lowercase()
        return when {
            m.startsWith("glm-5.3-flash") -> Note("聊天首选。五家 Flash 里最干净：在本应用里几乎不讲思路、不挂免责、不追问", preferred = true)
            m.startsWith("deepseek-flash") || m.startsWith("deepseek-v4-flash") -> Note("便宜、快；在本应用里偶尔结尾追问「需要我……吗」。不带设定时爱加标题加粗、挨骂先检讨")
            m.startsWith("minimax-m3.1-flash") -> Note("回答偏长（平均六七百字），爱单列注意事项、结尾追问")
            m.startsWith("mimo-v2.6-flash") -> Note("爱用「--」分隔；挨骂时会照抄设定里的示例原话")
            m.startsWith("qwen3.8-omni-flash") -> Note("回答偏长（平均五百字，不带设定时一千七百多字），爱用「--」分隔")
            else -> null
        }
    }

    /** 新建对话时谁排前面、默认选谁：标了首选的在前。 */
    fun rank(modelId: String): Int = if (of(modelId)?.preferred == true) 0 else 1
}
