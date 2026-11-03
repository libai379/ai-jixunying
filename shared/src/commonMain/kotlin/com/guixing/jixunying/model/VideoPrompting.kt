package com.guixing.jixunying.model

/**
 * 做视频的提示词怎么写（2026-11-03 用户：「MiniMax、千问……本身能力不强，生成的视频很 low，但是根据提示词会变得非常真实。
 * 那怎么加入这里面呢，本身的程序可以直接写进去吗」）。按模型给 AI 一份写法，写进 generate_video 的工具说明。
 * 都照官方原文，不凭感觉编（docs/参考资料.md「做视频的提示词写法」）：
 * - 万相：千问AI平台「视频生成微调」developer-guides/accuracy-tuning/video-generation.md（提示词公式、图生视频公式、声音公式、多镜头公式、提示词词典）
 * - MiniMax H3：官方模型仓库 huggingface.co/MiniMaxAI/MiniMax-H3 docs/VIDEO_PROMPT_WRITING_GUIDE_base_en.md（三段结构、[Shot N]、运镜词）
 * 用户自己总结的写法放在 VideoGenSettings.promptGuide，排在最前、优先照它。
 */
object VideoPrompting {

    /** 万相（千问AI平台 / 百炼）。 */
    const val WAN = "写给万相（wan…、happyhorse）的提示词，用中文，照官方公式：" +
        "文生视频 = 主体（外观细节）+ 场景（环境细节）+ 运动（幅度、速度、效果，比如「缓缓」「剧烈」）+ 审美控制 + 风格；" +
        "审美控制写光源（日光、火光、阴天光）、光线（柔光、硬光、侧光）、时间段、景别（特写、近景、中景、全景）、拍摄角度、镜头焦距、运镜（镜头推进、拉远、左移、环绕、固定机位）、色调；" +
        "风格写具体的（实拍、纪录片质感、电影感、黏土、3D 卡通），别写「高清、精美」这种空词。" +
        "图生视频：图已经定了主体和场景，提示词只写运动 + 运镜，不想动镜头就写「固定机位」。" +
        "要声音就写清人声（台词 + 情绪 + 语气 + 语速 + 音色）、音效（什么东西、怎么响）、背景音乐（风格）。" +
        "多镜头写「整体描述 + 镜头 1 [0-3 秒] 内容 + 镜头 2 [4-6 秒] 内容……」。"

    /** MiniMax H3（海螺）。 */
    const val MINIMAX = "写给 MiniMax-H3（海螺）的提示词，照官方写作指南用英文、分三段：" +
        "「integrated_multimodal_description: [Shot 1] Live-action, cinematic, a medium-wide shot frames …」（第一个镜头开头先写风格和构图，按时间往下写看得见、听得见的：人物外观和位置、场景、动作和反应；" +
        "换镜头写「[Shot 2] At 00:03.500, the camera cuts to …」）、" +
        "「overall_soundscape: …」（1～4 句英文写环境声、动作声、呼吸笑声）、「non_diegetic_music: …」（1～3 句写配乐的乐器和节奏，没有就写 N/A）。" +
        "运镜写成句子里的动作：Push In / Pull Out、Pan Left / Right、Truck Left / Right、Tilt Up / Down、Pedestal Up / Down、Arc Shot、Tracking Shot、Static Shot、Shake Slightly，" +
        "需要时加 with small / large amplitude、at slow / fast speed，比如「The camera pushes in with small amplitude at slow speed toward …」。" +
        "台词：说话的人标 (S1)，台词原文放 <d>[Chinese] 原话</d> 里，一个字不改。画面里的字放英文双引号里，原文不翻译。" +
        "用首帧图时第一行写「For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.」，空一行再写三段。"

    /** 这些模型的写法，按要用的模型挑（都可能用到就都给）。 */
    fun guides(vendors: Set<String>, userGuide: String): String = buildString {
        if (userGuide.isNotBlank()) append("用户自己总结的写法（优先照这个）：").append(userGuide.trim().take(3000)).append(" ")
        if (VideoPick.DASHSCOPE in vendors) append(WAN).append(" ")
        if (VideoPick.MINIMAX in vendors) append(MINIMAX)
    }.trim()
}
