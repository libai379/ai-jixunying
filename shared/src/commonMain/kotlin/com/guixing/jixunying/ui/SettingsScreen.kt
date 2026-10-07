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
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
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
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemberTemplates
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SearchEngine
import com.guixing.jixunying.model.SearchMode
import kotlinx.coroutines.flow.first

private fun tabIcon(t: SettingsTab): ImageVector = when (t) {
    SettingsTab.PROVIDERS -> Icons.Rounded.Cloud
    SettingsTab.MEMBERS -> Icons.Rounded.Groups
    SettingsTab.PROFILE -> Icons.Rounded.Badge
    SettingsTab.MEMORY -> Icons.Rounded.Psychology
    SettingsTab.SEARCH -> Icons.Rounded.Language
    SettingsTab.IMAGE -> Icons.Rounded.Brush
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
                SettingsTab.entries.forEach { t ->
                    val sel = t == tab
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { ctl.settingsTab = t }.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tabIcon(t), null, Modifier.size(18.dp), tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(tabTitle(t, LocalPlatform.current.isDesktop), style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            VerticalDivider(color = Ext.c.border)
            Box(Modifier.weight(1f).fillMaxHeight()) { SettingsPage(ctl, state, tab) }
        }
    } else if (ctl.settingsHome) {
        // 手机：先是一张列表（和系统设置一样），每项带当前状态；以前是一排要横着滑的标签，找不到在哪
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                val desktop = LocalPlatform.current.isDesktop
                SettingsTab.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, Ext.c.border, RoundedCornerShape(14.dp))
                            .clickable { ctl.settingsTab = t; ctl.settingsHome = false }.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tabIcon(t), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(tabTitle(t, desktop), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(tabSummary(t, state, ctl), style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                        Text("  ›", color = Ext.c.subtle)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ctl.settingsHome = true }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置") }
                Text(tabTitle(tab, LocalPlatform.current.isDesktop), style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1f)) { SettingsPage(ctl, state, tab) }
        }
    }
}

/** 设置首页每一项右边的小字：一眼看出现在是什么状态。 */
private fun tabSummary(t: SettingsTab, s: AppState, ctl: AppController): String = when (t) {
    SettingsTab.PROVIDERS -> if (s.providers.isEmpty()) "还没添加" else "${s.providers.size} 个"
    SettingsTab.MEMBERS -> if (s.members.isEmpty()) "还没有" else "${s.members.size} 位"
    SettingsTab.PROFILE -> s.profile.name
    SettingsTab.MEMORY -> if (!s.settings.memory.enabled) "已关闭" else "${s.memories.size} 条"
    SettingsTab.SEARCH -> if (s.settings.search.mode == SearchMode.AUTO) "自动" else engineLabel(s.settings.search.engine)
    SettingsTab.IMAGE -> ImagePick.resolve(s).firstOrNull()?.let { (if (ImagePick.isAuto(s)) "自动：" else "") + it.modelId } ?: "没有可用的"
    SettingsTab.WEIXIN -> if (!s.weixinCapable) "在电脑上设置" else if (s.weixin.bound) "已绑定" else "未绑定"
    SettingsTab.DEVICES -> if (ctl.hub.remote.value != null) "已配对" else if (s.devices.isNotEmpty()) "${s.devices.size} 台" else "未配对"
    SettingsTab.APPEARANCE -> listOf("跟随系统", "浅色", "深色").getOrElse(s.settings.darkMode) { "" }
    SettingsTab.ABOUT -> com.guixing.jixunying.model.APP_VERSION
}

@Composable
private fun SettingsPage(ctl: AppController, state: AppState, tab: SettingsTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            when (tab) {
                SettingsTab.PROVIDERS -> ProvidersPage(ctl, state)
                SettingsTab.MEMBERS -> MembersPage(ctl, state)
                SettingsTab.PROFILE -> ProfilePage(ctl, state)
                SettingsTab.MEMORY -> MemoryPage(ctl, state)
                SettingsTab.SEARCH -> SearchPage(ctl, state)
                SettingsTab.IMAGE -> ImagePage(ctl, state)
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
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
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
                            if (p.apiKey.isBlank()) Pill("未填 Key", MaterialTheme.colorScheme.error)
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
                    Switch(p.enabled, { ctl.run(Command.SaveProvider(p.copy(enabled = it)), if (it) "已启用「${p.name}」" else "已停用「${p.name}」：它的成员先不回答") })
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("API Key")
                Spacer(Modifier.weight(1f))
                if (preset.keyUrl.isNotBlank()) Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { platform.openUrl(preset.keyUrl) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("去获取 Key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            AppTextField(key, { key = it }, placeholder = "sk-…", password = !showKey, trailing = {
                IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp)) }
            })
            Spacer(Modifier.height(4.dp))
            SwitchRow("走代理", "海外服务商一般要开 · 代理 ${state.settings.proxy.ifBlank { "未设置" }}（在 外观 里改）", useProxy) { useProxy = it }
            HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { FieldLabel("模型", "每个模型选「聊天」还是「画图」") }
                TextButton(enabled = !busy && key.isNotBlank(), onClick = {
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
                        else Text("${m.modelId} · ${p.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// ———————————————— 我的资料 ————————————————

@Composable
private fun ProfilePage(ctl: AppController, state: AppState) {
    // 文字框自己记着正在打的内容（不跟着保存回来的状态重置，免得打字时被冲掉），停下来自动保存
    var name by remember { mutableStateOf(state.profile.name) }
    var about by remember { mutableStateOf(state.profile.about) }
    AutoSave(name, state.profile.name) { v -> if (v.isNotBlank()) ctl.updateProfile { it.copy(name = v.trim().take(16)) } }
    AutoSave(about, state.profile.about) { v -> ctl.updateProfile { it.copy(about = v) } }
    PageHeader("我的资料", "AI 会知道你是谁。写下你的身份和偏好，回答会更对路。改了会自动保存。")
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
        RadioRow(s.mode == SearchMode.AUTO, "自动（推荐）", "Kimi、智谱、千问这几家平台自带官方搜索，用它们的；其他模型（DeepSeek、MiniMax、MiMo 等）用下面选的搜索引擎") {
            set { it.copy(mode = SearchMode.AUTO) }
        }
        RadioRow(s.mode == SearchMode.ENGINE_ONLY, "全部用下面的搜索引擎", "所有模型统一用同一个搜索引擎，出处显示最一致") {
            set { it.copy(mode = SearchMode.ENGINE_ONLY) }
        }
        HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 10.dp))
        FieldLabel("搜索引擎")
        listOf(
            SearchEngine.BING_FREE to "免费，不用 Key，国内直连。偶尔会被限流，结果质量一般",
            SearchEngine.BOCHA to "推荐：国内正规搜索 API，中文结果质量好、稳定，按次计费很便宜（open.bochaai.com）",
            SearchEngine.ZHIPU to "智谱的搜索 API，用智谱开放平台的 Key",
            SearchEngine.TAVILY to "海外 AI 搜索 API，每月有免费额度（tavily.com），一般要走代理",
            SearchEngine.BRAVE to "海外搜索 API，有免费额度（brave.com/search/api），要走代理",
        ).forEach { (e, desc) -> RadioRow(s.engine == e, engineLabel(e), desc) { set { it.copy(engine = e) } } }
        if (s.engine != SearchEngine.BING_FREE) {
            Spacer(Modifier.height(8.dp))
            // 换了引擎就换一个输入框（各自的 Key 分开记）
            androidx.compose.runtime.key(s.engine) {
                var key by remember { mutableStateOf(s.apiKeys[s.engine.name].orEmpty()) }
                AutoSave(key, s.apiKeys[s.engine.name].orEmpty()) { v -> set { it.copy(apiKeys = it.apiKeys + (s.engine.name to v.trim())) } }
                FieldLabel("${engineLabel(s.engine)} 的 Key", "填好自动保存")
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

    PageHeader("画图", "不用专门设置：在任何对话里说「画一张……」，AI 会自己调用画图模型。输入框上的「直接画图」则不经过 AI，直接把你写的话当画面描述。")
    if (candidates.isEmpty()) {
        SectionCard {
            Text("还没有能画图的服务商", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("画图用的是各家平台的官方画图模型，和聊天用同一个 Key。到「模型服务」添加下面任意一家并填 Key，这里就会自动用上：",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(6.dp))
            listOf(
                "智谱开放平台：cogview-3-flash 免费",
                "MiniMax：image-01",
                "火山方舟：豆包 Seedream（即梦同款）",
                "阿里百炼 / 千问AI平台：千问图像、通义万相",
                "硅基流动：可图 Kolors、Qwen-Image",
                "魔搭 ModelScope：每天有免费额度",
            ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { ctl.openSettings(SettingsTab.PROVIDERS) }) { Text("去添加服务商") }
        }
        return
    }
    SectionCard {
        FieldLabel("用哪个画图模型", "点一下就换，马上生效")
        val first = candidates.first()
        RadioRow(auto, "自动（推荐）",
            "现在会用 ${first.modelId}（${first.providerName}，${first.note}）。画失败了会自动换下一个。") {
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
                Text("多模型助手 · 群聊 · 联网 · 画图 · 读文档 · 记忆 · 微信助理", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
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
