package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Evals
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.VideoPick
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemberTemplates
import com.guixing.jixunying.model.Thinking
import com.guixing.jixunying.model.ThinkingMode
import com.guixing.jixunying.model.ThinkingRule
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SearchEngine
import com.guixing.jixunying.model.SearchMode
import com.guixing.jixunying.model.SpeechPick
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import com.guixing.jixunying.ui.glass.glass

private fun tabIcon(t: SettingsTab): ImageVector = when (t) {
    SettingsTab.ACCOUNT -> Icons.Rounded.AccountCircle
    SettingsTab.PROVIDERS -> Icons.Rounded.Cloud
    SettingsTab.MEMBERS -> Icons.Rounded.Groups
    SettingsTab.PROFILE -> Icons.Rounded.Badge
    SettingsTab.MEMORY -> Icons.Rounded.Psychology
    SettingsTab.SEMANTIC -> Icons.Rounded.ManageSearch
    SettingsTab.SEARCH -> Icons.Rounded.Language
    SettingsTab.IMAGE -> Icons.Rounded.Brush
    SettingsTab.SPEECH -> Icons.Rounded.Mic
    SettingsTab.OFFICE -> Icons.Rounded.Description
    SettingsTab.CODE -> Icons.Rounded.Terminal
    SettingsTab.WEIXIN -> Icons.Rounded.Forum
    SettingsTab.DEVICES -> Icons.Rounded.PhoneAndroid
    SettingsTab.APPEARANCE -> Icons.Rounded.Palette
    SettingsTab.ABOUT -> Icons.Rounded.Info
}

private fun tabTitle(t: SettingsTab, desktop: Boolean) = if (t == SettingsTab.DEVICES) (if (desktop) "手机联机" else "连接电脑") else t.title

@Composable
fun SettingsScreen(ctl: AppController, tab: SettingsTab, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(210.dp).fillMaxHeight().padding(12.dp)) {
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 14.dp))
                SettingsTab.entries.forEachIndexed { i, t ->
                    val sel = t == tab
                    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val selBg by androidx.compose.animation.animateColorAsState(
                        if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent, com.guixing.jixunying.ui.glass.Springs.snappy())
                    Row(
                        Modifier.fillMaxWidth().entrance(i).springPress(press, 0.97f).padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                            .background(selBg)
                            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { ctl.settingsTab = t }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tabIcon(t), null, Modifier.size(18.dp), tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(tabTitle(t, LocalPlatform.current.isDesktop), style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (tabAlert(t, state)) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                }
            }
            VerticalDivider(color = Ext.c.border)
            Box(Modifier.weight(1f).fillMaxHeight()) { SettingsPage(ctl, state, tab) }
        }
    } else if (ctl.settingsHome) {
        // 手机：先是一张列表（和系统设置一样），每项带当前状态；以前是一排要横着滑的标签，找不到在哪
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                val desktop = LocalPlatform.current.isDesktop
                SettingsTab.entries.forEachIndexed { i, t ->
                    // 液态玻璃开着：每项是一块玻璃，一条条弹出来、按下弹一下（照癸星科技启动页的胶囊卡片，10-24）
                    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth().entrance(i).springPress(press, 0.97f).padding(vertical = 3.dp)
                            .then(if (com.guixing.jixunying.ui.glass.LocalGlass.current.on)
                                Modifier.glass(shape, com.guixing.jixunying.ui.glass.GlassStyle.Panel, fallback = MaterialTheme.colorScheme.surface)
                            else Modifier.clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, Ext.c.border, shape))
                            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current) { ctl.settingsTab = t; ctl.settingsHome = false }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tabIcon(t), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(tabTitle(t, desktop), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val alert = tabAlert(t, state)
                        Text(if (alert) "后台出错" else tabSummary(t, state, ctl), style = MaterialTheme.typography.labelMedium,
                            color = if (alert) MaterialTheme.colorScheme.error else Ext.c.subtle, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                        Text("  ›", color = Ext.c.subtle)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ctl.settingsHome = true }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置") }
                Text(tabTitle(tab, LocalPlatform.current.isDesktop), style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1f)) { SettingsPage(ctl, state, tab) }
        }
    }
}

/** 这一项要不要标红：记录员后台出错的提示都显示在「记忆」页（BgProblemsCard），所以标在「记忆」上。 */
private fun tabAlert(t: SettingsTab, s: AppState): Boolean = t == SettingsTab.MEMORY && s.bgProblems.isNotEmpty()

/** 设置首页每一项右边的小字：一眼看出现在是什么状态。 */
private fun tabSummary(t: SettingsTab, s: AppState, ctl: AppController): String = when (t) {
    SettingsTab.ACCOUNT -> if (s.account.githubLogin.isNotBlank()) "@" + s.account.githubLogin else "没登录"
    SettingsTab.PROVIDERS -> if (s.providers.isEmpty()) "还没添加" else "${s.providers.size} 个"
    SettingsTab.MEMBERS -> if (s.members.isEmpty()) "还没有" else "${s.members.size} 位"
    SettingsTab.PROFILE -> s.profile.name
    SettingsTab.MEMORY -> if (!s.settings.memory.enabled) "已关闭" else "${s.memories.size} 条"
    // 按意思搜这一页读写的是「数据在哪台设备」（遥控电脑时是电脑上的），所以看 s 就行
    SettingsTab.SEMANTIC -> SemanticUi.summary(s.settings.semantic, s.semanticProgress)
    SettingsTab.SEARCH -> if (s.settings.search.mode == SearchMode.AUTO) "自动" else engineLabel(s.settings.search.engine)
    SettingsTab.IMAGE -> ImagePick.resolve(s).firstOrNull()?.let { (if (ImagePick.isAuto(s)) "自动：" else "") + it.modelId } ?: "没有可用的"
    // 语音这一页读写的是这台设备自己的设置（手机上遥控电脑时也是本机的），所以这里看本机
    SettingsTab.SPEECH -> ctl.hub.local.store.state.value.let { local ->
        SpeechPick.forTts(local).let { c -> if (c == null) "没有可用的" else (if (SpeechPick.isAuto(local, tts = true)) "自动：" else "") + c.modelId }
    }
    SettingsTab.OFFICE -> if (!s.settings.office.enabled) "已关闭" else when (s.settings.office.wordStyle) { "formal" -> "正式"; "official" -> "公文"; else -> "通用" }
    SettingsTab.CODE -> shellLabel(s.settings.code.shell) + if (s.settings.code.allowPhone) " · 手机能用" else " · 手机不能用"
    SettingsTab.WEIXIN -> if (!s.weixinCapable) "在电脑上设置" else if (s.weixin.bound) "已绑定" else "未绑定"
    SettingsTab.DEVICES -> if (ctl.hub.remote.value != null) "已配对" else if (s.devices.isNotEmpty()) "${s.devices.size} 台" else "未配对"
    SettingsTab.APPEARANCE -> listOf("跟随系统", "浅色", "深色").getOrElse(s.settings.darkMode) { "" } + if (s.settings.glass) " · 液态玻璃" else ""
    SettingsTab.ABOUT -> com.guixing.jixunying.model.APP_VERSION
}

@Composable
private fun SettingsPage(ctl: AppController, state: AppState, tab: SettingsTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            when (tab) {
                SettingsTab.ACCOUNT -> AccountPage(ctl, state)
                SettingsTab.PROVIDERS -> ProvidersPage(ctl, state)
                SettingsTab.MEMBERS -> MembersPage(ctl, state)
                SettingsTab.PROFILE -> ProfilePage(ctl, state)
                SettingsTab.MEMORY -> MemoryPage(ctl, state)
                SettingsTab.SEMANTIC -> SemanticPage(ctl)
                SettingsTab.SEARCH -> SearchPage(ctl, state)
                SettingsTab.IMAGE -> ImagePage(ctl, state)
                SettingsTab.SPEECH -> SpeechPage(ctl)
                SettingsTab.OFFICE -> OfficePage(ctl, state)
                SettingsTab.CODE -> CodePage(ctl, state)
                SettingsTab.WEIXIN -> WeixinPage(ctl, state)
                SettingsTab.DEVICES -> DevicesPage(ctl, state)
                SettingsTab.APPEARANCE -> AppearancePage(ctl, state)
                SettingsTab.ABOUT -> AboutPage(ctl)
            }
        }
    }
}

@Composable
fun PageHeader(title: String, desc: String, action: @Composable (() -> Unit)? = null) {
    // 窄屏（手机）上按钮放到说明下面，不挤压文字
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        val narrow = maxWidth < 520.dp
        if (narrow) Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            if (action != null) { Spacer(Modifier.height(10.dp)); action() }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
            if (action != null) { Spacer(Modifier.width(12.dp)); action() }
        }
    }
}

// ———————————————— 模型服务 ————————————————

@Composable
private fun ProvidersPage(ctl: AppController, state: AppState) {
    // 开发截图用：-Djxy.start=settings:PROVIDERS#edit:服务商编号 直接打开那个服务商的编辑弹窗
    var editing by remember { mutableStateOf(ctl.debugDialog.takeIf { it.startsWith("edit:") }?.let { state.provider(it.removePrefix("edit:")) }) }
    // 从欢迎页第一步进来时直接弹出「添加服务商」；只弹一次，下次进这页不再自动弹
    var adding by remember { mutableStateOf(ctl.debugDialog == "add").also { if (ctl.debugDialog == "add") ctl.debugDialog = "" } }
    PageHeader("模型服务", "填 API Key 接入各家模型。Key 只存在这台设备上；手机遥控电脑时，电脑上的 Key 在手机上只显示打码后的样子。") {
        Button(onClick = { adding = true }, shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加服务商")
        }
    }
    if (state.providers.isEmpty()) {
        SectionCard {
            Text("还没有添加服务商", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("点右上角「添加服务商」，从 DeepSeek、智谱、Kimi、通义千问、豆包、腾讯、OpenAI、Claude、Gemini 等预设里选一个，填上 Key 就能用。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.providers.forEach { p ->
            val preset = Presets.byId(p.presetId)
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresetBadge(preset, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            if (Presets.isChatGpt(p)) {
                                if (state.chatgpt.signedIn) Pill("已登录", Ext.c.success) else Pill("没登录", MaterialTheme.colorScheme.error)
                            } else if (p.apiKey.isBlank()) Pill("未填 Key", MaterialTheme.colorScheme.error)
                            if (p.useProxy) { Spacer(Modifier.width(4.dp)); Pill("走代理", Ext.c.warning) }
                        }
                        Text(p.baseUrl, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.models.size} 个模型" + (p.models.take(4).joinToString("、", prefix = "：") { it.id }.takeIf { p.models.isNotEmpty() } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 开关旁边写明是什么意思（以前只有一个开关，看不出是干什么的）
                    Text(if (p.enabled) "启用" else "已停用", style = MaterialTheme.typography.labelSmall,
                        color = if (p.enabled) Ext.c.subtle else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    AppSwitch(p.enabled, { ctl.run(Command.SaveProvider(p.copy(enabled = it)), if (it) "已启用「${p.name}」" else "已停用「${p.name}」：它的成员先不回答") })
                    IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.Edit, "编辑", Modifier.size(18.dp)) }
                }
            }
        }
    }
    if (adding) ProviderDialog(ctl, null) { adding = false }
    editing?.let { e -> ProviderDialog(ctl, e) { editing = null } }
}

@Composable
fun SelectBox(label: String, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, if (open) MaterialTheme.colorScheme.primary else Ext.c.border, RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { open = true }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (open) "▴" else "▾", color = Ext.c.subtle)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 300.dp).heightIn(max = 420.dp)) {
            content { open = false }
        }
    }
}

@Composable
private fun ProviderDialog(ctl: AppController, existing: ProviderConfig?, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val state by ctl.backend.store.state.collectAsState()
    var presetId by remember { mutableStateOf(existing?.presetId ?: "deepseek") }
    val preset = Presets.byId(presetId)
    var name by remember { mutableStateOf(existing?.name ?: preset.name) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: preset.baseUrl) }
    var key by remember { mutableStateOf(existing?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var useProxy by remember { mutableStateOf(existing?.useProxy ?: preset.useProxy) }
    var models by remember { mutableStateOf(existing?.models ?: preset.models) }
    var newModel by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    /** 测试连通 / 拉取模型的结果，直接显示在弹窗里（不再用会被弹窗挡住的提示条）。 */
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: ("p" + nowMillis().toString(36)) }

    fun draft() = ProviderConfig(id, presetId, name.trim().ifEmpty { preset.name }, baseUrl.trim().trimEnd('/'), key.trim(), models, useProxy, existing?.enabled ?: true)

    AppDialog(if (existing == null) "添加服务商" else "编辑服务商", onClose, width = 620.dp, actions = {
        if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(enabled = !busy && models.isNotEmpty(), onClick = {
            val chat = models.firstOrNull { !it.imageGen }
            if (chat == null) {
                val looksChat = models.filter { !Presets.guessImageGen(it.id) }.map { it.id }
                result = false to (if (looksChat.isNotEmpty())
                    "现在没有「聊天」模型可以测：${looksChat.joinToString("、")} 被设成了「画图」。它是聊天模型，把它改回「聊天」再测。"
                else "这个服务商只有画图模型，测试连通要用聊天模型。画图模型到 设置 → 画图 点「试画」测试。")
                return@OutlinedButton
            }
            busy = true
            result = null
            ctl.run(Command.SaveProvider(draft()), quiet = true) { saved ->
                if (!saved.ok) { busy = false; result = false to saved.message; return@run }
                ctl.run(Command.TestProvider(id, chat.id), quiet = true) { r ->
                    busy = false
                    result = r.ok to r.message.ifBlank { if (r.ok) "连通了" else "失败了" }
                }
            }
        }) { Text(if (busy) "正在测试…" else "测试连通") }
        TextButton(onClose) { Text("取消") }
        Button(onClick = { ctl.run(Command.SaveProvider(draft()), "已保存"); onClose() }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FieldLabel("服务商", "都按 OpenAI 兼容协议接入")
            SelectBox(preset.name, leading = { PresetBadge(preset, 20.dp) }) { close ->
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { TapToSearchField(search, { search = it }, "搜索服务商") }
                val list = Presets.all.filter { search.isBlank() || it.name.contains(search, true) || it.id.contains(search, true) }
                list.groupBy { it.group }.forEach { (group, items) ->
                    Text(group, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                    items.forEach { p ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                    if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                                }
                            },
                            leadingIcon = { PresetBadge(p, 22.dp) },
                            onClick = {
                                presetId = p.id
                                if (existing == null || name == Presets.byId(existing.presetId).name) name = p.name
                                baseUrl = p.baseUrl; useProxy = p.useProxy
                                if (existing == null) models = p.models
                                search = ""
                                close()
                            },
                        )
                    }
                }
            }
            if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall, color = Ext.c.warning, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            FieldLabel("显示名称")
            AppTextField(name, { name = it })
            Spacer(Modifier.height(10.dp))
            FieldLabel("接口地址（Base URL）", "到 /v1 这一级，不要带 /chat/completions")
            AppTextField(baseUrl, { baseUrl = it }, placeholder = "https://…/v1")
            Spacer(Modifier.height(10.dp))
            if (presetId == Presets.CHATGPT) ChatGptLogin(ctl, state) { ctl.run(Command.SaveProvider(draft()), quiet = true) { r -> it(r.ok) } }
            else Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("API Key")
                Spacer(Modifier.weight(1f))
                if (preset.keyUrl.isNotBlank()) Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { platform.openUrl(preset.keyUrl) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("去获取 Key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (presetId != Presets.CHATGPT) AppTextField(key, { key = it }, placeholder = "sk-…", password = !showKey, trailing = {
                IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp)) }
            })
            // ChatGPT 登录好了引擎会自己拉一次模型列表，弹窗里跟着更新
            val engineModels = state.provider(id)?.models
            androidx.compose.runtime.LaunchedEffect(engineModels) { if (presetId == Presets.CHATGPT && !engineModels.isNullOrEmpty()) models = engineModels }
            Spacer(Modifier.height(4.dp))
            SwitchRow("走代理", "海外服务商一般要开 · 代理 ${state.settings.proxy.ifBlank { "未设置" }}（在 外观 里改）", useProxy) { useProxy = it }
            HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { FieldLabel("模型", "每个模型选「聊天」还是「画图」") }
                TextButton(enabled = !busy && (key.isNotBlank() || (presetId == Presets.CHATGPT && state.chatgpt.signedIn)), onClick = {
                    busy = true
                    result = null
                    ctl.run(Command.SaveProvider(draft()), quiet = true) {
                        ctl.run(Command.FetchModels(id), quiet = true) { r ->
                            busy = false
                            if (r.ok) ctl.backend.store.state.value.provider(id)?.let { models = it.models }
                            result = r.ok to r.message.ifBlank { if (r.ok) "已更新模型列表" else "拉取失败" }
                        }
                    }
                }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拉取模型列表", maxLines = 1, softWrap = false)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))) {
                if (models.isEmpty()) Text("还没有模型。点「拉取模型列表」，或在下面手动添加。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(12.dp))
                models.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = Ext.c.border)
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.id, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Evals.label(m.id)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Ext.c.success) }
                        }
                        if (!m.imageGen) MiniCheck("能看图", m.vision) { v -> models = models.map { if (it.id == m.id) it.copy(vision = v) else it } }
                        TypeToggle(m.imageGen) { img -> models = models.map { if (it.id == m.id) it.copy(imageGen = img, tools = !img, vision = if (img) false else it.vision) else it } }
                        IconButton(onClick = { models = models.filterNot { it.id == m.id } }, Modifier.size(30.dp)) { Icon(Icons.Rounded.Delete, "移除", Modifier.size(15.dp), tint = Ext.c.subtle) }
                    }
                }
            }
            Text(
                "聊天模型用来对话，当 AI 成员；画图模型（比如智谱的 cogview-3-flash、MiniMax 的 image-01）只用来出图，" +
                    "聊天里说「画一张……」会自动用上，不用另外设置。想指定用哪个画图模型、或者试画一张，到 设置 → 画图。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTextField(newModel, { newModel = it }, Modifier.weight(1f), placeholder = "手动添加模型名，例如 deepseek-v4-flash")
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = newModel.isNotBlank(), onClick = {
                    val idm = newModel.trim()
                    if (models.none { it.id == idm }) models = models + com.guixing.jixunying.model.ModelInfo(idm, vision = Presets.guessVision(idm), imageGen = Presets.guessImageGen(idm), tools = !Presets.guessImageGen(idm))
                    newModel = ""
                }) { Text("添加") }
            }
        }
        result?.let { (ok, text) -> TestResultBox(ok, text) { result = null } }
    }
    if (confirmDelete && existing != null) {
        AppDialog("删除服务商", { confirmDelete = false }, width = 420.dp, actions = {
            TextButton({ confirmDelete = false }) { Text("取消") }
            Button({ confirmDelete = false; onClose(); ctl.run(Command.DeleteProvider(existing.id)) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }) { Text("用这个服务商的成员会变成「未配置模型」。", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun MiniCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(1.dp, if (checked) MaterialTheme.colorScheme.primary else Ext.c.subtle, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", color = Color.White, fontSize = 10.sp) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ———————————————— AI 成员 ————————————————

@Composable
private fun MembersPage(ctl: AppController, state: AppState) {
    var editing by remember { mutableStateOf(if (ctl.debugDialog == "edit") state.members.firstOrNull() else null) }
    PageHeader("AI 成员", "每位成员 = 一个模型 + 名字和定位。它知道自己是谁、背后是什么模型，也知道群里还有谁。") {
        Button(onClick = { editing = newMember(state, null) }, shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加成员")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.members.forEach { m ->
            val p = state.provider(m.providerId)
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(m, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    // 名字一行，模型和服务商一行，测评成绩一行：窄屏上也不会挤成「…」
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        if (p == null || m.modelId.isBlank()) Text("未配置模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${m.modelId} · ${p.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            // 选了快速 / 深度、并且这个模型真能切换时才标
                            if (m.thinking != ThinkingMode.AUTO && Thinking.available(Thinking.rule(p, m.modelId), m.thinking)) {
                                Spacer(Modifier.width(6.dp))
                                Pill(Thinking.label(m.thinking), if (m.thinking == ThinkingMode.FAST) Ext.c.success else MaterialTheme.colorScheme.primary)
                            }
                        }
                        Evals.label(m.modelId)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Ext.c.success) }
                        if (m.bio.isNotBlank()) Text(m.bio, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Rounded.Edit, "编辑", Modifier.size(18.dp)) }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("从模板快速添加", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FlowRowCompat {
        MemberTemplates.all.forEach { t ->
            Row(
                Modifier.padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(12.dp))
                    .clickable { editing = newMember(state, t) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(null, 26.dp, emoji = t.avatar, color = t.color)
                Spacer(Modifier.width(8.dp))
                Text(t.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    editing?.let { m -> MemberDialog(ctl, state, m, isNew = state.member(m.id) == null) { editing = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow { content() }
}

private fun newMember(state: AppState, t: MemberTemplates.Template?): Member {
    val p = state.providers.firstOrNull { it.enabled && it.models.any { m -> !m.imageGen } }
    val model = p?.models?.firstOrNull { !it.imageGen }?.id.orEmpty()
    return Member(
        id = "m" + nowMillis().toString(36),
        name = t?.name ?: "新成员${state.members.size + 1}",
        avatar = t?.avatar ?: MemberEmojis[state.members.size % MemberEmojis.size],
        color = t?.color ?: MemberColors[state.members.size % MemberColors.size],
        providerId = p?.id.orEmpty(),
        modelId = model,
        bio = t?.bio.orEmpty(),
    )
}

@Composable
private fun MemberDialog(ctl: AppController, state: AppState, initial: Member, isNew: Boolean, onClose: () -> Unit) {
    var m by remember { mutableStateOf(initial) }
    var temp by remember { mutableStateOf(initial.temperature?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val provider = state.provider(m.providerId)
    AppDialog(if (isNew) "添加成员" else "编辑成员", onClose, width = 600.dp, actions = {
        if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        TextButton(onClose) { Text("取消") }
        Button(enabled = m.name.isNotBlank(), onClick = {
            ctl.run(Command.SaveMember(m.copy(name = m.name.trim().replace("@", "").take(16), temperature = temp.trim().toDoubleOrNull())), "已保存")
            onClose()
        }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(m, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("名字", "群里用 @名字 叫它")
                    AppTextField(m.name, { m = m.copy(name = it) })
                }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("头像")
            FlowRowCompat {
                MemberEmojis.forEach { e ->
                    Box(
                        Modifier.padding(end = 6.dp, bottom = 6.dp).size(34.dp).clip(CircleShape)
                            .background(if (m.avatar == e) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { m = m.copy(avatar = e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 18.sp) }
                }
            }
            FlowRowCompat {
                MemberColors.forEach { c ->
                    Box(
                        Modifier.padding(end = 8.dp, bottom = 6.dp).size(24.dp).clip(CircleShape).background(Color(c))
                            .border(2.dp, if (m.color == c) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .clickable { m = m.copy(color = c) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("定位 / 人设", "会写进它自己的设定，也会介绍给群里其他成员")
            AppTextField(m.bio, { m = m.copy(bio = it) }, singleLine = false, minLines = 3, placeholder = "例如：资深律师，回答严谨，会说明法律依据和风险")
            Spacer(Modifier.height(10.dp))
            FieldLabel("服务商")
            if (state.providers.isEmpty()) Text("先到「模型服务」添加服务商。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else SelectBox(provider?.name ?: "选择服务商", leading = provider?.let { { PresetBadge(Presets.byId(it.presetId), 20.dp) } }) { close ->
                state.providers.forEach { p ->
                    androidx.compose.material3.DropdownMenuItem({ Text(p.name) }, leadingIcon = { PresetBadge(Presets.byId(p.presetId), 20.dp) }, onClick = {
                        m = m.copy(providerId = p.id, modelId = p.models.firstOrNull { !it.imageGen }?.id.orEmpty()); close()
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("模型", "可以直接输入，也可以点右边的箭头选")
            if (provider != null) {
                ModelPicker(m.modelId, { m = m.copy(modelId = it.trim()) }, provider.models.filter { !it.imageGen }, "例如 deepseek-flash")
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("思考", "快速 = 不先想直接答；深度 = 想透再答")
            ThinkingPicker(m.thinking, Thinking.rule(provider, m.modelId)) { m = m.copy(thinking = it) }
            Spacer(Modifier.height(10.dp))
            FieldLabel("温度（可选）", "留空用模型默认值；有的模型只接受固定值（如 kimi-k3 只能 1）")
            AppTextField(temp, { temp = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, placeholder = "留空", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    if (confirmDelete) {
        AppDialog("删除成员", { confirmDelete = false }, width = 420.dp, actions = {
            TextButton({ confirmDelete = false }) { Text("取消") }
            Button({ confirmDelete = false; onClose(); ctl.run(Command.DeleteMember(initial.id)) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }) { Text("「${initial.name}」会从所有对话里移除，以前的发言保留。", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** 默认 / 快速 / 深度 三选一；这个模型用不了的那项灰掉，下面一行说清楚选了会怎样。 */
@Composable
private fun ThinkingPicker(mode: ThinkingMode, rule: ThinkingRule, onPick: (ThinkingMode) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, Ext.c.border, shape)) {
        ThinkingMode.entries.forEachIndexed { i, opt ->
            val ok = Thinking.available(rule, opt)
            val sel = mode == opt
            if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(Ext.c.border))
            Column(
                Modifier.weight(1f).height(44.dp).background(if (sel) primary.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = ok || sel) { onPick(opt) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(Thinking.label(opt), style = MaterialTheme.typography.labelLarge,
                    color = when { sel -> primary; ok -> MaterialTheme.colorScheme.onSurface; else -> Ext.c.subtle.copy(alpha = 0.5f) })
                Text(when {
                    opt == ThinkingMode.AUTO -> "模型自己定"
                    !ok -> "不支持"
                    opt == ThinkingMode.DEEP -> "想透再答"
                    rule.fastOnlyLess -> "少想一点"
                    rule.fast?.isEmpty() == true -> "本来就不想"
                    else -> "不先想"
                },
                    style = MaterialTheme.typography.labelSmall, color = if (ok || sel) Ext.c.subtle else Ext.c.subtle.copy(alpha = 0.5f))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(Thinking.explain(rule, mode), style = MaterialTheme.typography.bodySmall,
        color = if (Thinking.available(rule, mode)) Ext.c.subtle else MaterialTheme.colorScheme.error)
}

// ———————————————— 个性化（我的资料 + 自定义指令） ————————————————

@Composable
private fun ProfilePage(ctl: AppController, state: AppState) {
    // 文字框自己记着正在打的内容（不跟着保存回来的状态重置，免得打字时被冲掉），停下来自动保存
    var name by remember { mutableStateOf(state.profile.name) }
    var about by remember { mutableStateOf(state.profile.about) }
    AutoSave(name, state.profile.name) { v -> if (v.isNotBlank()) ctl.updateProfile { it.copy(name = v.trim().take(16)) } }
    AutoSave(about, state.profile.about) { v -> ctl.updateProfile { it.copy(about = v) } }
    // 自定义指令（2026-10-17，学 WorkBuddy 的 个性化 → 自定义指令）
    val personal = state.settings.personal
    var instructions by remember { mutableStateOf(personal.instructions) }
    var codeInstructions by remember { mutableStateOf(personal.codeInstructions) }
    AutoSave(instructions, personal.instructions) { v -> ctl.updateSettings { it.copy(personal = it.personal.copy(instructions = v)) } }
    AutoSave(codeInstructions, personal.codeInstructions) { v -> ctl.updateSettings { it.copy(personal = it.personal.copy(codeInstructions = v)) } }
    PageHeader("个性化", "AI 会知道你是谁、该怎么做。写下你的身份、偏好和要它一直照着做的规矩，回答会更对路。改了会自动保存。")
    SectionCard {
        FieldLabel("自定义指令", "每次回答都照着做：聊天里的每位成员、Code 栏都用")
        AppTextField(instructions, { instructions = it }, singleLine = false, minLines = 5,
            placeholder = "例如：回答先给结论再讲理由；事实要带出处，说不出出处就写「未确认」；不要表格；用中文")
        Spacer(Modifier.height(10.dp))
        FieldLabel("Code 栏另外的指令", "只给 Code 栏，管所有项目（每个项目自己的规矩写在项目里的 CLAUDE.md 或 AGENTS.md，Code 栏会自己读）")
        AppTextField(codeInstructions, { codeInstructions = it }, singleLine = false, minLines = 4,
            placeholder = "例如：改完先跑测试再说做完了；注释写中文；不要动 .git 和签名文件")
        AutoSaveHint(if (instructions != personal.instructions || codeInstructions != personal.codeInstructions) "正在保存…" else "已保存")
    }
    Spacer(Modifier.height(12.dp))
    // 自己写的斜杠命令（2026-10-18，开发补齐第 12 样）：输入框开头打「/名字」展开成这段话；两栏都能用
    SectionCard {
        FieldLabel("我的斜杠命令", "输入框开头打「/」就能选；内容里写 \$ARGUMENTS 的地方换成命令后面跟的字。项目里的 .claude/commands 文件夹里的 .md 也会自动出现")
        personal.commands.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("/" + c.name + if (c.description.isNotBlank()) "　" + c.description else "", style = MaterialTheme.typography.bodyMedium)
                    Text(c.text.take(80), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1)
                }
                TextButton({ ctl.updateSettings { it.copy(personal = it.personal.copy(commands = it.personal.commands - c)) } }) {
                    Text("删掉", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        var cName by remember { mutableStateOf("") }
        var cDesc by remember { mutableStateOf("") }
        var cText by remember { mutableStateOf("") }
        Spacer(Modifier.height(6.dp))
        AppTextField(cName, { cName = it.replace(" ", "").removePrefix("/") }, placeholder = "命令名，比如 周报")
        Spacer(Modifier.height(4.dp))
        AppTextField(cDesc, { cDesc = it }, placeholder = "一句说明（可以不写）")
        Spacer(Modifier.height(4.dp))
        AppTextField(cText, { cText = it }, singleLine = false, minLines = 3, placeholder = "展开成什么，比如：把这周改过的代码整理成周报，重点写 \$ARGUMENTS")
        TextButton({
            if (cName.isNotBlank() && cText.isNotBlank()) {
                val n = com.guixing.jixunying.model.SlashCommand(cName.trim(), cDesc.trim(), cText.trim())
                ctl.updateSettings { it.copy(personal = it.personal.copy(commands = it.personal.commands.filter { x -> x.name != n.name } + n)) }
                cName = ""; cDesc = ""; cText = ""
            }
        }, enabled = cName.isNotBlank() && cText.isNotBlank()) { Text("加上这个命令") }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(null, 52.dp, emoji = state.profile.avatar, color = 0xFF4F5BEB)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("称呼", "AI 怎么称呼你")
                AppTextField(name, { name = it })
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("头像")
        FlowRowCompat {
            listOf("🙂", "😎", "🧑‍💻", "👩‍💼", "👨‍🏫", "🐯", "🦁", "🌙", "☀️", "🍀").forEach { e ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp).size(34.dp).clip(CircleShape)
                    .background(if (state.profile.avatar == e) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { ctl.updateProfile { it.copy(avatar = e) } }, contentAlignment = Alignment.Center) { Text(e, fontSize = 18.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("关于我", "职业、所在城市、关心什么、希望怎么被回答")
        AppTextField(about, { about = it }, singleLine = false, minLines = 4, placeholder = "例如：在北京做产品经理，回答请先给结论，少用术语")
        AutoSaveHint(if (name != state.profile.name || about != state.profile.about) "正在保存…" else "已保存")
    }
}

// ———————————————— 联网搜索 ————————————————

@Composable
private fun SearchPage(ctl: AppController, state: AppState) {
    val s = state.settings.search
    fun set(f: (com.guixing.jixunying.model.SearchSettings) -> com.guixing.jixunying.model.SearchSettings) = ctl.updateSettings { it.copy(search = f(it.search)) }
    PageHeader("联网搜索", "对话里打开「联网」后，AI 遇到时效性问题会先搜再答，并标出处。不需要单独的 AI：回答问题的那个模型自己决定什么时候搜、搜什么。改了马上生效。")
    SectionCard {
        FieldLabel("联网方式")
        RadioRow(s.mode == SearchMode.AUTO, "自动（推荐）", "智谱、千问用平台自带的官方搜索；Kimi 用 Kimi 官方搜索接口（同一个 Key，每次 1 分钱，出错自动换下面的引擎）；其他模型（DeepSeek、MiniMax、MiMo 等）用下面选的搜索引擎") {
            set { it.copy(mode = SearchMode.AUTO) }
        }
        RadioRow(s.mode == SearchMode.ENGINE_ONLY, "全部用下面的搜索引擎", "所有模型统一用同一个搜索引擎，出处显示最一致") {
            set { it.copy(mode = SearchMode.ENGINE_ONLY) }
        }
        HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 10.dp))
        FieldLabel("搜索引擎")
        listOf(
            SearchEngine.BING_FREE to "免费，不用 Key（必应 + DuckDuckGo 一起搜，对不上题的扔掉）。中文题经常搜不到，要稳用下面有 Key 的",
            SearchEngine.BOCHA to "推荐：国内正规搜索 API，中文结果质量好、稳定，按次计费很便宜（open.bochaai.com）",
            SearchEngine.ZHIPU to "智谱的搜索 API（基础版 search_std，官方价每次 1 分钱）。服务商里加了智谱开放平台的就不用再填 Key",
            SearchEngine.TAVILY to "海外 AI 搜索 API，每月有免费额度（tavily.com），一般要走代理",
            SearchEngine.BRAVE to "海外搜索 API，有免费额度（brave.com/search/api），要走代理",
        ).forEach { (e, desc) -> RadioRow(s.engine == e, engineLabel(e), desc) { set { it.copy(engine = e) } } }
        if (s.engine != SearchEngine.BING_FREE) {
            Spacer(Modifier.height(8.dp))
            // 换了引擎就换一个输入框（各自的 Key 分开记）
            androidx.compose.runtime.key(s.engine) {
                var key by remember { mutableStateOf(s.apiKeys[s.engine.name].orEmpty()) }
                AutoSave(key, s.apiKeys[s.engine.name].orEmpty()) { v -> set { it.copy(apiKeys = it.apiKeys + (s.engine.name to v.trim())) } }
                FieldLabel("${engineLabel(s.engine)} 的 Key", if (s.engine == SearchEngine.ZHIPU) "不填就用服务商里智谱开放平台的 Key" else "填好自动保存")
                AppTextField(key, { key = it }, password = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("搜索走代理", "用 Tavily、Brave 时打开", s.useProxy) { v -> set { it.copy(useProxy = v) } }
        // 滑块拖的时候只改显示，松手才保存
        var n by remember(s.maxResults) { mutableStateOf(s.maxResults.toFloat()) }
        FieldLabel("每次搜索取几条结果：${n.toInt()}")
        Slider(n, { n = it }, valueRange = 3f..10f, steps = 6, onValueChangeFinished = { set { it.copy(maxResults = n.toInt()) } })
    }
    Spacer(Modifier.height(12.dp))
    PlatformLinksCard(ctl)
}

/**
 * 读平台链接（2026-10-23，docs/平台链接设计说明.md）：抖音、小红书、快手、微博、B站的链接用电脑上一个专用的 Edge 读。
 * 各平台登没登录（看浏览器里的登录 cookie）、「去登录」把浏览器挪到屏幕上让用户扫码。
 */
@Composable
private fun PlatformLinksCard(ctl: AppController) {
    var info by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var tick by remember { mutableStateOf(0) }
    var showing by remember { mutableStateOf(false) }
    var launch by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        ctl.run(Command.PlatformStatus(launch), quiet = true) { r ->
            info = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.data) as kotlinx.serialization.json.JsonObject }.getOrNull()
        }
    }
    val available = (info?.get("available") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    val started = (info?.get("started") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    val st = info?.get("platforms") as? kotlinx.serialization.json.JsonObject
    SectionCard {
        Text("读平台链接", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("你发来抖音、小红书、快手、微博、B站的链接（微信里转发的也行），AI 会用这台电脑上一个专用的 Edge 打开来读：文案、作者、点赞、评论、图片和视频地址。" +
            "这个 Edge 和你平时用的分开，窗口平时放在屏幕外面（任务栏上会有它的图标），AI集训营 关掉时它也关掉。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        Spacer(Modifier.height(8.dp))
        when {
            info == null -> Text("正在查…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            !available -> Text(if (ctl.remoteMode) "要在电脑上设置（浏览器在电脑上）。" else "这台设备上没有能用的浏览器（电脑上要有 Edge 或 Chrome；手机上不支持，手机遥控电脑时由电脑去读）。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            else -> {
                listOf("douyin" to "抖音", "xhs" to "小红书", "kuaishou" to "快手", "weibo" to "微博", "bilibili" to "B站").forEach { (id, name) ->
                    val s = (st?.get(id) as? kotlinx.serialization.json.JsonPrimitive)?.content
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                        Text(when (s) { "in" -> "已登录"; "out" -> "没登录"; "notstarted" -> "—"; else -> "看不出登没登录" },
                            style = MaterialTheme.typography.bodySmall, color = if (s == "in") Ext.c.success else Ext.c.subtle, modifier = Modifier.weight(1f))
                        if (!ctl.remoteMode) TextButton({
                            ctl.run(Command.PlatformLogin(id), quiet = true) { r -> showing = r.ok; ctl.toast(r.message) }
                        }) { Text("去登录") }
                    }
                }
                if (!started) Text("浏览器还没开：AI 第一次读平台链接、或者你点「去登录」「检查登录状态」时才开。",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
                Text("不登录也能读一部分（抖音作品、小红书笔记实测能读）；遇到要登录或验证的，点「去登录」用手机扫码。" +
                    "B站对国外出口 IP 会直接拦（你电脑现在全局走代理的话就会这样）。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                    modifier = Modifier.padding(top = 6.dp))
                if (!ctl.remoteMode) Row(Modifier.padding(top = 6.dp)) {
                    if (showing) Button({ ctl.run(Command.PlatformHide, quiet = true) { showing = false; tick++ } }, shape = RoundedCornerShape(10.dp)) { Text("登好了，收起浏览器") }
                    else OutlinedButton({ launch = true; tick++ }) { Text(if (started) "刷新登录状态" else "检查登录状态（会开浏览器）") }
                }
            }
        }
    }
}

private fun engineLabel(e: SearchEngine) = when (e) {
    SearchEngine.BING_FREE -> "必应（免费）"
    SearchEngine.BOCHA -> "博查 Bocha"
    SearchEngine.TAVILY -> "Tavily"
    SearchEngine.ZHIPU -> "智谱搜索"
    SearchEngine.BRAVE -> "Brave Search"
}

// ———————————————— 画图 ————————————————

/**
 * 画图页。以前要先选服务商、再填模型名、再点保存，用户看不懂。现在：
 * 已配好的服务商里能画图的模型直接列出来（标明谁家官方、要不要钱），点一下就生效；默认「自动」，不选也能画。
 */
@Composable
private fun ImagePage(ctl: AppController, state: AppState) {
    val candidates = remember(state.providers) { ImagePick.candidates(state.providers) }
    val auto = ImagePick.isAuto(state)
    val current = ImagePick.resolve(state).firstOrNull()
    val ig = state.settings.imageGen
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testImage by remember { mutableStateOf<com.guixing.jixunying.model.Attachment?>(null) }
    fun save(f: (com.guixing.jixunying.model.ImageGenSettings) -> com.guixing.jixunying.model.ImageGenSettings) = ctl.updateSettings { it.copy(imageGen = f(it.imageGen)) }

    PageHeader("画图和视频", "不用专门设置：在任何对话里说「画一张……」「做一段……的视频」，AI 会自己调用画图 / 视频模型。输入框上的「直接画图」则不经过 AI，直接把你写的话当画面描述。")
    if (candidates.isEmpty()) {
        SectionCard {
            Text("还没有能画图的服务商", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("画图用的是各家平台的官方画图模型，和聊天用同一个 Key。到「模型服务」添加下面任意一家并填 Key，这里就会自动用上：",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(6.dp))
            listOf(
                "千问AI平台 / 阿里百炼：千问图像（推荐，画里的中文字写得准）、通义万相",
                "火山方舟：豆包 Seedream（即梦同款）",
                "MiniMax：image-01",
                "硅基流动：可图 Kolors、Qwen-Image",
                "魔搭 ModelScope：每天有免费额度",
                "智谱开放平台：cogview-3-flash 免费，但写不好字",
            ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { ctl.openSettings(SettingsTab.PROVIDERS) }) { Text("去添加服务商") }
        }
        Spacer(Modifier.height(12.dp))
        VideoSection(ctl, state)
        return
    }
    SectionCard {
        FieldLabel("用哪个画图模型", "点一下就换，马上生效")
        val first = candidates.first()
        val qwenFirst = "qwen-image" in first.modelId.lowercase()
        RadioRow(auto, "自动（推荐）",
            (if (qwenFirst) "画质优先，千问图像排第一。" else "画质优先（配了千问AI平台或阿里百炼，就先用画字最准的千问图像）。") +
                "现在会用 ${first.modelId}（${first.providerName}，${first.note}）。画失败了（比如余额用完）会自动换下一家。") {
            save { it.copy(providerId = "", modelId = "") }
        }
        candidates.forEach { c ->
            val sel = !auto && current?.providerId == c.providerId && current.modelId == c.modelId
            RadioRow(sel, c.modelId, "${c.providerName} · ${c.note}" + if (c.inferred) " · 用这家的 Key 直接调" else "") {
                save { it.copy(providerId = c.providerId, modelId = c.modelId) }
            }
        }
        Text("上面都是各家平台自己的官方画图模型，用的就是你在「模型服务」里填的那个 Key，不用另外申请。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        FieldLabel("默认尺寸", "AI 也可以按你说的比例改")
        FlowRowCompat {
            listOf("1024x1024", "1024x1536", "1536x1024", "768x1344", "1344x768", "2048x2048").forEach { sz ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
                    ToggleChip(sz, Icons.Rounded.Brush, ig.size == sz) { save { it.copy(size = sz) } }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("试画一张", style = MaterialTheme.typography.titleSmall)
                Text("画一只水彩风格的橘猫，看看能不能用" + if (current?.free == true) "（这个模型免费）。" else "（会按张扣一点费用）。",
                    style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
            Spacer(Modifier.width(10.dp))
            Button(enabled = !testing, onClick = {
                testing = true; result = null; testImage = null
                ctl.run(Command.TestImage(if (auto) "" else current?.providerId.orEmpty(), if (auto) "" else current?.modelId.orEmpty()), quiet = true) { r ->
                    testing = false
                    result = r.ok to r.message.ifBlank { if (r.ok) "画好了" else "没画成" }
                    if (r.ok) testImage = runCatching { com.guixing.jixunying.model.AppJson.decodeFromString(com.guixing.jixunying.model.Attachment.serializer(), r.data) }.getOrNull()
                }
            }) { Text(if (testing) "正在画…" else "试画") }
        }
        result?.let { (ok, text) -> TestResultBox(ok, text) { result = null } }
        testImage?.let { a ->
            val img = rememberImage(ctl, a.id)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (img != null) androidx.compose.foundation.Image(img, a.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    VideoSection(ctl, state)
}

/**
 * 做视频（2026-10-20 千问AI平台 / 阿里百炼，10-21 加 MiniMax 海螺 H3）：按秒收费，所以默认最便宜的一档（wan3.0-video、480P、5 秒），
 * 每个选项旁边写清这样一段大约多少钱。模型只列有 Key 的那几家的；选了别家的模型，「用哪家」跟着换。
 */
@Composable
private fun VideoSection(ctl: AppController, state: AppState) {
    val v = state.settings.videoGen
    fun save(f: (com.guixing.jixunying.model.VideoGenSettings) -> com.guixing.jixunying.model.VideoGenSettings) = ctl.updateSettings { it.copy(videoGen = f(it.videoGen)) }
    val providers = remember(state.providers) { VideoPick.candidates(state.providers) }
    val models = remember(state.providers) { VideoPick.modelsFor(state.providers) }
    // 现在实际用的：和引擎一样按 VideoPick.resolve 算（设置里的模型和服务商不是一家时用那家的默认模型）
    val choice = VideoPick.resolve(state.copy(settings = state.settings.copy(videoGen = v.copy(enabled = true))))
    val m = choice?.model ?: VideoPick.model(v.modelId)
    val res = VideoPick.resolutionFor(m, v.resolution)
    val secs = VideoPick.durationFor(m, v.duration)
    fun perSec(vm: com.guixing.jixunying.model.VideoModel, r: String, extra: String = "") =
        Prices.of(VideoPick.pricePlatform(vm), VideoPick.billingModel(vm.id, r, extra), state.settings.costs.overrides)?.perSecond
    SectionCard {
        SwitchRow("让 AI 做视频", "在对话里说「做一段……的视频」，AI 会调用视频模型，做好了点开就能播放。也能拿对话里的图、视频、文件，或者电脑上的文件当素材" +
            "（让图片动起来、照着参考做、改一段视频、把视频接长、按文档做视频）。按秒收费，做一段要一到几分钟。", v.enabled) { on ->
            save { it.copy(enabled = on) }
        }
        if (providers.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("要先在「模型服务」里添加千问AI平台、阿里百炼或 MiniMax 中国版并填 Key（和聊天、画图用同一个 Key）。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            return@SectionCard
        }
        if (!v.enabled) return@SectionCard
        if (providers.size > 1) {
            Spacer(Modifier.height(8.dp))
            FieldLabel("用哪家")
            val cur = providers.firstOrNull { it.id == choice?.providerId } ?: providers.first()
            providers.forEach { p ->
                RadioRow(p.id == cur.id, p.name, null) {
                    val vendor = VideoPick.vendorOf(p)
                    save { it.copy(providerId = p.id, modelId = if (VideoPick.model(it.modelId).vendor == vendor) it.modelId else VideoPick.defaultFor(vendor!!).id) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FieldLabel("视频模型", "千问 / 百炼的价格是阿里云百炼官方价（每秒），千问AI平台按同样的价算；MiniMax 是它官网价格页的刊例价")
        models.forEach { vm ->
            val r = VideoPick.resolutionFor(vm, v.resolution)
            val price = perSec(vm, r)?.let { " · $r 每秒 ${it} 元" }.orEmpty()
            RadioRow(vm.id == m.id, vm.id, vm.label + price + if (vm.minSec < vm.maxSec) " · ${vm.minSec}–${vm.maxSec} 秒" else "") {
                val p = providers.firstOrNull { it.id == choice?.providerId }?.takeIf { VideoPick.vendorOf(it) == vm.vendor }
                    ?: providers.first { VideoPick.vendorOf(it) == vm.vendor }
                save { it.copy(modelId = vm.id, providerId = p.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        FieldLabel("清晰度")
        FlowRowCompat {
            m.resolutions.forEach { r ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) { ToggleChip(r, Icons.Rounded.Brush, res == r) { save { it.copy(resolution = r) } } }
            }
        }
        FieldLabel("默认时长", "AI 也可以按你说的改；带视频素材（编辑、接长）时万相默认智能时长")
        FlowRowCompat {
            (listOf(2, 3, 4, 5, 8, 10, 15, 30).filter { it in m.minSec..m.maxSec }.ifEmpty { listOf(m.minSec) }).forEach { s ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) { ToggleChip("$s 秒", Icons.Rounded.Brush, secs == s) { save { it.copy(duration = s) } } }
            }
        }
        if (!m.bySize) {
            FieldLabel("默认画面比例", "AI 也可以按你说的改；带素材时默认跟素材一样")
            FlowRowCompat {
                m.ratios.forEach { r ->
                    Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) { ToggleChip(r, Icons.Rounded.Brush, VideoPick.ratioFor(m, v.ratio) == r) { save { it.copy(ratio = r) } } }
                }
            }
        }
        if (m.audioSwitch) SwitchRow("带声音", "关掉就是无声视频，价格一样", v.audio) { on -> save { it.copy(audio = on) } }
        val each = perSec(m, res)?.let { it * secs }
        Text((if (each != null) "按现在的设置，一段 $secs 秒大约 ${yuan(each)}" else "这个模型的价格没查到") +
            "（成功了才收费，失败不收）。花了多少在「花费」页能看到。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
        Text(when {
            m.media.isEmpty() -> "这个模型只能用文字做视频。AI 带素材（图片、视频、文档）时会自动改用能收素材的模型（${models.filter { it.media.isNotEmpty() }.joinToString("、") { it.id }.ifEmpty { "现在没有" }}），并告诉你。"
            m.vendor == VideoPick.MINIMAX -> "素材：首帧 / 尾帧，或者参考图（最多 9 张）、参考视频（最多 3 段，共 15 秒）、参考音频（最多 3 段，共 15 秒）。" +
                "收费 = 输出秒数 × 每秒价 + 输入视频秒数 × 每秒 ${perSec(m, res, "输入视频") ?: "?"} 元；图片 ${VideoPick.freeImages(m.id)} 张以内免费，多的每张 " +
                "${Prices.of("minimax", VideoPick.billingModel(m.id, res, "图片"), state.settings.costs.overrides)?.perImage ?: "?"} 元；音频免费。" +
                "MiniMax 不能按文档 / 网页做视频，要这个时自动改用万相 3.0（有千问 / 百炼的 Key 才行）。"
            else -> "素材：首帧 / 首尾帧，或者参考图（最多 10 张）、参考视频（最多 5 段，共 15 秒）、参考音频（最多 5 段，共 15 秒），文档或网页（各 1 个）。" +
                "带视频的（编辑、接长、参考视频）按「输入视频秒数 + 输出秒数」收费，单价一样，所以比直接做贵；输入 + 输出最长 30 秒。" +
                "电脑上的素材先传到千问的免费临时存储（48 小时后自动删）再交给模型。"
        }, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
        Text(if (m.vendor == VideoPick.MINIMAX)
                "实际扣多少以 MiniMax 后台的账单为准。点停止时，还在排队的任务会取消、不收费；已经开始生成的取消不了，做完照样收费，视频还是会放回那条回答下面。"
            else "实际扣多少以千问AI平台后台的账单为准：这里和「花费」页的价格是阿里云百炼官方的原价（千问AI平台自己的价格页读不到），" +
                "百炼写的限时折扣、新用户免费秒数在千问AI平台上算不算查不到，所以这里算出来的可能比实际扣的多。" +
                "点停止时，还在排队的任务会取消、不收费；已经开始生成的取消不了（千问只许取消排队中的），做完照样收费，视频还是会放回那条回答下面。" +
                "实测提交后不到 3 秒就开始生成了，所以多数时候点停止已经来不及取消。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 单选行：圆点 + 标题 + 灰色说明，整行可点。 */
@Composable
fun RadioRow(selected: Boolean, title: String, desc: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (!desc.isNullOrBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
    }
}

// ———————————————— 手机连接 ————————————————

@Composable
private fun DevicesPage(ctl: AppController, state: AppState) {
    if (LocalPlatform.current.isDesktop) HostDevicesPage(ctl, state) else PhoneDevicesPage(ctl)
}

// ———————————————— 外观与其他 ————————————————

@Composable
private fun AppearancePage(ctl: AppController, state: AppState) {
    val s = state.settings
    PageHeader("外观与通用", "主题、代理、群聊规则。改了马上生效。")
    SectionCard {
        FieldLabel("主题")
        FlowRowCompat {
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, t) ->
                Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { ctl.updateSettings { it.copy(darkMode = v) } }.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.darkMode == v, { ctl.updateSettings { it.copy(darkMode = v) } }); Text(t)
                }
            }
        }
        // 液态玻璃（2026-10-22，docs/界面换装说明.md）
        SwitchRow("液态玻璃", "侧栏、输入框、弹窗、开关、发送键用癸星科技那套玻璃和弹簧动画；关掉就是原来的纯色界面",
            s.glass) { v -> ctl.updateSettings { it.copy(glass = v) } }
        Spacer(Modifier.height(10.dp))
        var proxy by remember { mutableStateOf(s.proxy) }
        AutoSave(proxy, s.proxy) { v -> ctl.updateSettings { it.copy(proxy = v.trim()) } }
        FieldLabel("代理地址", "给海外服务商和海外搜索用，比如 v2rayN 默认 127.0.0.1:10809；填好自动保存")
        AppTextField(proxy, { proxy = it }, placeholder = "127.0.0.1:10809")
        Spacer(Modifier.height(10.dp))
        var chain by remember(s.maxMentionChain) { mutableStateOf(s.maxMentionChain.toFloat()) }
        FieldLabel("AI 互相 @ 最多接力几轮：${chain.toInt()}", "防止 AI 之间没完没了地互相点名")
        Slider(chain, { chain = it }, valueRange = 0f..6f, steps = 5, onValueChangeFinished = { ctl.updateSettings { it.copy(maxMentionChain = chain.toInt()) } })
    }
}

@Composable
private fun AboutPage(ctl: AppController) {
    val platform = LocalPlatform.current
    PageHeader("关于", "AI集训营 ${com.guixing.jixunying.model.APP_VERSION}")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(44)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("AI集训营", style = MaterialTheme.typography.titleMedium)
                Text("多模型助手 · 群聊 · 立场档案 · 联网 · 画图 · 读文档 · 记忆 · 微信助理", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "· 不需要自己的服务器：电脑和手机各自都能单独用，模型从本机直接调用。\n" +
                "· 手机配对电脑后，在哪儿都能遥控电脑（走加密的公共中转，不用同一个 Wi-Fi）。\n" +
                "· 聊天记录、附件、记忆、API Key 都只存在自己的设备上：" +
                (if (platform.isDesktop) "电脑上在 %APPDATA%\\ai-jixunying 文件夹，备份复制这个文件夹就行。" else "手机上在 App 自己的存储里。") + "\n" +
                "· 手机遥控电脑时，电脑上的 Key 在手机上只显示打码后的样子。\n" +
                "· 所有模型都按 OpenAI 兼容协议接入；微信助理用的是腾讯官方的 ClawBot 接口。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    // 开发者署名（用户 2026-10-14 定：用「癸星科技」这个名字）
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开发", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(com.guixing.jixunying.model.APP_DEVELOPER, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("版本", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(com.guixing.jixunying.model.APP_VERSION, style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle)
        }
        Spacer(Modifier.height(12.dp))
        Text("© 2026 ${com.guixing.jixunying.model.APP_DEVELOPER}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

/** 模型名输入框：能直接打字，右边箭头点开是这个服务商已知的模型（带看图、测评标记）。 */
@Composable
private fun ModelPicker(value: String, onChange: (String) -> Unit, options: List<com.guixing.jixunying.model.ModelInfo>, placeholder: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppTextField(value, onChange, placeholder = placeholder, trailing = if (options.isEmpty()) null else {
            {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Rounded.ExpandMore, "选择模型", Modifier.size(20.dp))
                }
            }
        })
        androidx.compose.material3.DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 320.dp).heightIn(max = 420.dp)) {
            options.forEach { md ->
                androidx.compose.material3.DropdownMenuItem({
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(md.id, style = MaterialTheme.typography.bodyMedium)
                        if (md.vision) { Spacer(Modifier.width(6.dp)); Pill("看图") }
                        Evals.label(md.id)?.let { Spacer(Modifier.width(6.dp)); Pill(it, Ext.c.success) }
                    }
                }, onClick = { onChange(md.id); open = false })
            }
        }
    }
}

/** 弹窗里的结果条：绿色成功、红色失败，文字可以选中复制。 */
@Composable
fun TestResultBox(ok: Boolean, text: String, onClose: () -> Unit) {
    val color = if (ok) Ext.c.success else MaterialTheme.colorScheme.error
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(if (ok) "✓" else "!", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 1.dp, end = 8.dp))
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onClose, Modifier.size(26.dp)) { Icon(Icons.Rounded.Close, "关闭", Modifier.size(14.dp), tint = Ext.c.subtle) }
    }
}

/** 模型类型：聊天 / 画图，二选一。 */
@Composable
private fun TypeToggle(image: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(8.dp)),
    ) {
        listOf(false to "聊天", true to "画图").forEach { (v, label) ->
            val sel = image == v
            Box(
                Modifier.background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.primary else Ext.c.subtle)
            }
        }
    }
}


/**
 * 「用 ChatGPT 登录」（2026-10-23，docs/ChatGPT订阅设计说明.md）：替代 Key 输入框。
 * 点登录先保存这个服务商，再让引擎开本机回调、把 OpenAI 的页面在浏览器里打开；用户在网页上点同意，状态里的 chatgpt 跟着变。
 * 回调只能回到登录的那台设备，所以手机遥控电脑时不能在手机上登。
 */
@Composable
private fun ChatGptLogin(ctl: AppController, state: AppState, saveFirst: (done: (Boolean) -> Unit) -> Unit) {
    val platform = LocalPlatform.current
    val info = state.chatgpt
    FieldLabel("登录", "不用 Key，用你自己的 ChatGPT 订阅额度")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(
            when {
                info.signingIn -> "正在等你在浏览器里的 OpenAI 页面上点同意…（5 分钟内有效）"
                info.signedIn && info.planOk -> "已登录" + (info.email.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "") + "。成员用这个服务商的模型时，花的是你 ChatGPT 订阅的额度。"
                info.signedIn -> "已登录，但没给「用订阅额度」的权限，调不了模型。点「重新登录」，在 OpenAI 页面上把权限都勾上。"
                else -> "还没登录。点下面的按钮，会在浏览器打开 OpenAI 的页面，登录你的 ChatGPT 账号并同意，就能用。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (info.error.isNotBlank()) Text(info.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        if (ctl.remoteMode) {
            Text("要在电脑上点登录（OpenAI 登录完跳回的是登录那台设备）。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                saveFirst { ok ->
                    if (ok) ctl.run(Command.ChatGptSignIn, quiet = true) { r ->
                        if (r.ok && r.data.isNotBlank()) platform.openUrl(r.data) else ctl.toast(r.message.ifBlank { "开不了登录页" })
                    }
                }
            }, shape = RoundedCornerShape(10.dp)) { Text(if (info.signedIn) "重新登录" else if (info.signingIn) "重新打开登录页" else "用 ChatGPT 登录") }
            Spacer(Modifier.width(8.dp))
            if (info.signedIn) TextButton({ ctl.run(Command.ChatGptSignOut, "已退出 ChatGPT 登录") }) { Text("退出登录") }
        }
        Text("OpenAI 官方给开源、本机运行的程序开的通道；在 ChatGPT 网页 设置 里能看每个程序用了多少、设上限。" +
            "登录只存在这台设备上，不会同步到别的设备。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * 账号（2026-10-24，docs/账号设计说明.md）：没有服务器——账号就是这台设备上的一对密钥（编号由公钥算），可以绑 GitHub；
 * 换设备用备份码（口令加密）。手机遥控电脑时这里只看不改。
 */
@Composable
private fun AccountPage(ctl: AppController, state: AppState) {
    PageHeader("账号", "用 GitHub 登录；不用服务器，账号就在这台设备上，换设备用备份码带过去。")
    AccountCard(ctl, state)
}

@Composable
private fun AccountCard(ctl: AppController, state: AppState) {
    val platform = LocalPlatform.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val a = state.account
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    SectionCard {
        Text("账号", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("不用注册、没有服务器：这台设备第一次打开时生成了一对密钥，它就是你的账号（只存在这台设备上）。可以再绑上 GitHub，别人就能认出是你。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("账号编号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            SelectionContainer(Modifier.weight(1f)) { Text(a.id.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            TextButton({ clipboard.setText(AnnotatedString(a.id)); ctl.toast("已复制账号编号") }, enabled = a.id.isNotBlank()) { Text("复制") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            Text(
                when {
                    a.githubLogin.isNotBlank() -> "@${a.githubLogin}" + (a.githubName.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                    a.githubSigningIn -> "等你在 GitHub 页面上点同意…"
                    else -> "没绑"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (a.githubLogin.isNotBlank()) Ext.c.success else if (a.githubSigningIn) MaterialTheme.colorScheme.primary else Ext.c.subtle,
                modifier = Modifier.weight(1f),
            )
            if (!ctl.remoteMode) {
                if (a.githubLogin.isNotBlank()) TextButton({ ctl.run(Command.AccountGitHubUnbind, "已解绑 GitHub") }) { Text("解绑", color = MaterialTheme.colorScheme.error) }
                else TextButton({
                    ctl.run(Command.AccountGitHubSignIn, quiet = true) { r ->
                        if (r.ok && r.data.isNotBlank()) platform.openUrl(r.data) else ctl.toast(r.message.ifBlank { "开不了 GitHub 登录页" })
                    }
                }) { Text(if (a.githubSigningIn) "重新打开" else "用 GitHub 登录") }
            }
        }
        if (a.githubSigningIn) Text("浏览器里打开了 GitHub，点同意后会自动跳回来。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 72.dp))
        if (a.githubError.isNotBlank()) Text(a.githubError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 72.dp))
        Spacer(Modifier.height(6.dp))
        if (ctl.remoteMode) Text("这是电脑的账号，只能在电脑上改。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        else Row {
            OutlinedButton({ exporting = true }) { Text("导出备份码") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ importing = true }) { Text("导入备份码") }
        }
        Text("换手机、换电脑，或者想让手机和电脑用同一个账号：在这台导出备份码，到那台导入。备份码要设口令，口令忘了就导不进去；设备丢了又没备份，这个账号就找不回来了。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
    }
    if (exporting) AccountExportDialog(ctl) { exporting = false }
    if (importing) AccountImportDialog(ctl) { importing = false }
}

@Composable
private fun AccountExportDialog(ctl: AppController, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AppDialog("导出账号备份码", onClose, width = 520.dp, actions = {
        TextButton(onClose) { Text(if (code.isBlank()) "取消" else "关闭") }
        if (code.isBlank()) Button({
            error = when { p1.length < 6 -> "口令至少 6 个字"; p1 != p2 -> "两次口令不一样"; else -> "" }
            if (error.isEmpty()) ctl.run(Command.AccountExport(p1), quiet = true) { r -> if (r.ok) code = r.data else error = r.message }
        }) { Text("导出") } else Button({ clipboard.setText(AnnotatedString(code)); ctl.toast("备份码已复制") }) { Text("复制备份码") }
    }) {
        Column {
            if (code.isBlank()) {
                Text("备份码里有这个账号的密钥，用你设的口令加密。别发给别人；口令另外记好。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                Spacer(Modifier.height(8.dp))
                FieldLabel("口令")
                AppTextField(p1, { p1 = it }, password = true)
                Spacer(Modifier.height(6.dp))
                FieldLabel("再输一次")
                AppTextField(p2, { p2 = it }, password = true)
            } else {
                Text("备份码（存到安全的地方，导入时要用刚才的口令）：", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AccountImportDialog(ctl: AppController, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AppDialog("导入账号备份码", onClose, width = 520.dp, actions = {
        TextButton(onClose) { Text("取消") }
        Button({ ctl.run(Command.AccountImport(code, pass), quiet = true) { r -> if (r.ok) { ctl.toast(r.message); onClose() } else error = r.message } },
            enabled = code.isNotBlank() && pass.isNotBlank()) { Text("导入") }
    }) {
        Column {
            Text("导入以后这台设备就换成备份码里的账号，原来的账号会被替换掉（要留着就先导出一份）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            FieldLabel("备份码", "JXYID1: 开头")
            AppTextField(code, { code = it }, singleLine = false, minLines = 3)
            Spacer(Modifier.height(6.dp))
            FieldLabel("口令")
            AppTextField(pass, { pass = it }, password = true)
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
