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

    /**
     * 用户给的样板提炼出来的写法（2026-11-03，三份原文在 docs/视频提示词样板.md：真人手机自拍、暴雨古刹武侠、白墙自拍产品展示）。
     * 用户说这样写，千问、MiniMax 本来很 low 的视频会变得非常真实，所以排在官方写法前面，两家都照这个结构。
     */
    const val TEMPLATE = "照这个结构写成一大段中文（用户试过，这样写出来最真实，两家都照这个写）：" +
        "【主体】年龄（一律写成年人）、五官、发型、妆容、穿着，最后写「全程保持同一张脸、同一发型、同一套服装」；" +
        "【场景】在哪、背景是否固定、光从哪来、明暗差异，空间关系写清楚；" +
        "【时长与画幅】几秒、横竖屏；" +
        "【风格与色彩】（要质感时）比如 16 毫米胶片颗粒、柔化高光；" +
        "【音乐与声音】原创配乐的风格、BPM、乐器，哪些动作踩鼓点，保留哪些真实声音（衣料摩擦、脚步、底噪），有没有台词；" +
        "【镜头与剪辑】机位和拍法（前置手机自拍 / 一镜到底 / 硬切跳切）、运镜路线，说明不用稳定器、动作自然不像摆拍；" +
        "【画面段落】按秒分段（0—4 秒：…；4—7 秒：…），每段写清动作、景别、表情，动作之间有借力、停顿、落地过程；" +
        "【画质约束】要真实就写手机或胶片的瑕疵：细小随机颗粒和彩噪、动态拖影、短暂失焦和对焦呼吸、滚动快门形变、曝光白平衡轻微变化、平台压缩痕迹、不是每帧都锐利、720p 观感；" +
        "【连续性约束】（一镜到底时）不切机位、不瞬移、武器和道具不凭空消失、手和道具接触准确；" +
        "【禁止】字幕、水印、界面文字、品牌标志、人物变脸、服装变化、额外人物、肢体或手指畸形、产品变形、夸张磨皮、全程锐利、HDR、影棚精修、稳定器式平滑运镜。" +
        "用户只说一句话时，你按这个结构替他补全细节，不要把用户没说的写成和他意思相反的。"

    /** 万相（千问AI平台 / 百炼）。 */
    const val WAN = "写给万相（wan…、happyhorse）的提示词，用中文，照官方公式：" +
        "文生视频 = 主体（外观细节）+ 场景（环境细节）+ 运动（幅度、速度、效果，比如「缓缓」「剧烈」）+ 审美控制 + 风格；" +
        "审美控制写光源（日光、火光、阴天光）、光线（柔光、硬光、侧光）、时间段、景别（特写、近景、中景、全景）、拍摄角度、镜头焦距、运镜（镜头推进、拉远、左移、环绕、固定机位）、色调；" +
        "风格写具体的（实拍、纪录片质感、电影感、黏土、3D 卡通），别写「高清、精美」这种空词。" +
        "图生视频：图已经定了主体和场景，提示词只写运动 + 运镜，不想动镜头就写「固定机位」。" +
        "要声音就写清人声（台词 + 情绪 + 语气 + 语速 + 音色）、音效（什么东西、怎么响）、背景音乐（风格）。" +
        "多镜头写「整体描述 + 镜头 1 [0-3 秒] 内容 + 镜头 2 [4-6 秒] 内容……」。"

    /** MiniMax H3（海螺）。 */
    const val MINIMAX = "MiniMax-H3（海螺）官方写作指南的写法（参考：用户的中文结构在两家都管用，优先照上面的；想更贴官方时可以用英文分三段）：" +
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
        append(TEMPLATE).append(" ")
        if (VideoPick.DASHSCOPE in vendors) append(WAN).append(" ")
        if (VideoPick.MINIMAX in vendors) append(MINIMAX)
    }.trim()
}
