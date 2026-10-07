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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemberTemplates
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SearchEngine

private fun tabIcon(t: SettingsTab): ImageVector = when (t) {
    SettingsTab.PROVIDERS -> Icons.Rounded.Cloud
    SettingsTab.MEMBERS -> Icons.Rounded.Groups
    SettingsTab.PROFILE -> Icons.Rounded.Badge
    SettingsTab.SEARCH -> Icons.Rounded.Language
    SettingsTab.IMAGE -> Icons.Rounded.Brush
    SettingsTab.DEVICES -> Icons.Rounded.PhoneAndroid
    SettingsTab.APPEARANCE -> Icons.Rounded.Palette
    SettingsTab.ABOUT -> Icons.Rounded.Info
}

private fun tabTitle(t: SettingsTab, host: Boolean) = if (t == SettingsTab.DEVICES && !host) "连接电脑" else t.title

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
                        Text(tabTitle(t, ctl.backend.isHost), style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            VerticalDivider(color = Ext.c.border)
            Box(Modifier.weight(1f).fillMaxHeight()) { SettingsPage(ctl, state, tab) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsTab.entries.forEach { t ->
                    ToggleChip(tabTitle(t, ctl.backend.isHost), tabIcon(t), t == tab) { ctl.settingsTab = t }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f)) { SettingsPage(ctl, state, tab) }
        }
    }
}

@Composable
private fun SettingsPage(ctl: AppController, state: AppState, tab: SettingsTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            when (tab) {
                SettingsTab.PROVIDERS -> ProvidersPage(ctl, state)
                SettingsTab.MEMBERS -> MembersPage(ctl, state)
                SettingsTab.PROFILE -> ProfilePage(ctl, state)
                SettingsTab.SEARCH -> SearchPage(ctl, state)
                SettingsTab.IMAGE -> ImagePage(ctl, state)
                SettingsTab.DEVICES -> DevicesPage(ctl, state)
                SettingsTab.APPEARANCE -> AppearancePage(ctl, state)
                SettingsTab.ABOUT -> AboutPage(ctl)
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, desc: String, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        action?.invoke()
    }
}

// ———————————————— 模型服务 ————————————————

@Composable
private fun ProvidersPage(ctl: AppController, state: AppState) {
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(ctl.debugDialog == "add") }
    PageHeader("模型服务", "填 API Key 接入各家模型。Key 只存在电脑上，手机端只看得到打码后的样子。") {
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
                    Switch(p.enabled, { ctl.run(Command.SaveProvider(p.copy(enabled = it))) })
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
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: ("p" + nowMillis().toString(36)) }

    fun draft() = ProviderConfig(id, presetId, name.trim().ifEmpty { preset.name }, baseUrl.trim().trimEnd('/'), key.trim(), models, useProxy, existing?.enabled ?: true)

    AppDialog(if (existing == null) "添加服务商" else "编辑服务商", onClose, width = 620.dp, actions = {
        if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(enabled = !busy && models.isNotEmpty(), onClick = {
            busy = true
            ctl.run(Command.SaveProvider(draft())) {
                ctl.run(Command.TestProvider(id, models.first().id)) { busy = false }
            }
        }) { Text(if (busy) "请稍候…" else "测试连通") }
        TextButton(onClose) { Text("取消") }
        Button(onClick = { ctl.run(Command.SaveProvider(draft()), "已保存"); onClose() }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FieldLabel("服务商", "都按 OpenAI 兼容协议接入")
            SelectBox(preset.name, leading = { PresetBadge(preset, 20.dp) }) { close ->
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { AppTextField(search, { search = it }, placeholder = "搜索") }
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
                FieldLabel("模型", "勾「看图」的模型能看图片，勾「画图」的出现在画图设置里")
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    ctl.run(Command.SaveProvider(draft())) {
                        ctl.run(Command.FetchModels(id)) { r ->
                            busy = false
                            if (r.ok) ctl.backend.store.state.value.provider(id)?.let { models = it.models }
                        }
                    }
                }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拉取模型列表")
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))) {
                if (models.isEmpty()) Text("还没有模型。点「拉取模型列表」，或在下面手动添加。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(12.dp))
                models.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = Ext.c.border)
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MiniCheck("看图", m.vision) { v -> models = models.map { if (it.id == m.id) it.copy(vision = v) else it } }
                        MiniCheck("画图", m.imageGen) { v -> models = models.map { if (it.id == m.id) it.copy(imageGen = v, tools = !v) else it } }
                        IconButton(onClick = { models = models.filterNot { it.id == m.id } }, Modifier.size(30.dp)) { Icon(Icons.Rounded.Delete, "移除", Modifier.size(15.dp), tint = Ext.c.subtle) }
                    }
                }
            }
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
    var editing by remember { mutableStateOf<Member?>(null) }
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
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            if (p == null || m.modelId.isBlank()) Pill("未配置模型", MaterialTheme.colorScheme.error)
                            else Pill("${m.modelId} · ${p.name}", MaterialTheme.colorScheme.primary)
                        }
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
            ctl.run(Command.SaveMember(m.copy(name = m.name.trim().replace("@", ""), temperature = temp.trim().toDoubleOrNull())), "已保存")
            onClose()
        }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(m, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("名字", "群里用 @名字 叫它")
                    AppTextField(m.name, { m = m.copy(name = it.take(12)) })
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
            FieldLabel("模型")
            if (provider != null) {
                SelectBox(m.modelId.ifBlank { "选择模型" }) { close ->
                    provider.models.filter { !it.imageGen }.forEach { md ->
                        androidx.compose.material3.DropdownMenuItem({
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(md.id, style = MaterialTheme.typography.bodyMedium)
                                if (md.vision) { Spacer(Modifier.width(6.dp)); Pill("看图") }
                            }
                        }, onClick = { m = m.copy(modelId = md.id); close() })
                    }
                }
                Spacer(Modifier.height(6.dp))
                AppTextField(m.modelId, { m = m.copy(modelId = it.trim()) }, placeholder = "也可以直接填模型名")
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
    var p by remember(state.profile) { mutableStateOf(state.profile) }
    PageHeader("我的资料", "AI 会知道你是谁。写下你的身份和偏好，回答会更对路。")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(null, 52.dp, emoji = p.avatar, color = 0xFF4F5BEB)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("称呼")
                AppTextField(p.name, { p = p.copy(name = it.take(16)) })
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("头像")
        FlowRowCompat {
            listOf("🙂", "😎", "🧑‍💻", "👩‍💼", "👨‍🏫", "🐯", "🦁", "🌙", "☀️", "🍀").forEach { e ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp).size(34.dp).clip(CircleShape)
                    .background(if (p.avatar == e) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { p = p.copy(avatar = e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 18.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("关于我", "职业、所在城市、关心什么、希望怎么被回答")
        AppTextField(p.about, { p = p.copy(about = it) }, singleLine = false, minLines = 4, placeholder = "例如：在北京做产品经理，回答请先给结论，少用术语")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { ctl.run(Command.SaveProfile(p), "已保存") }) { Text("保存") }
    }
}

// ———————————————— 联网搜索 ————————————————

@Composable
private fun SearchPage(ctl: AppController, state: AppState) {
    var s by remember(state.settings.search) { mutableStateOf(state.settings.search) }
    PageHeader("联网搜索", "对话里打开「联网」后，AI 遇到时效性问题会先搜再答，并标出处。")
    SectionCard {
        FieldLabel("搜索引擎")
        val engines = listOf(
            SearchEngine.BING_FREE to "免费，不用 Key，国内直连。偶尔会被限流",
            SearchEngine.BOCHA to "国内正规搜索 API，中文结果好，按次计费（open.bochaai.com）",
            SearchEngine.ZHIPU to "智谱的搜索 API，用智谱开放平台的 Key",
            SearchEngine.TAVILY to "海外 AI 搜索 API，每月有免费额度（tavily.com），一般要走代理",
            SearchEngine.BRAVE to "海外搜索 API，有免费额度（brave.com/search/api），要走代理",
        )
        engines.forEach { (e, desc) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { s = s.copy(engine = e) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.engine == e, { s = s.copy(engine = e) })
                Column(Modifier.weight(1f)) {
                    Text(engineLabel(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                }
            }
        }
        if (s.engine != SearchEngine.BING_FREE) {
            Spacer(Modifier.height(8.dp))
            FieldLabel("${engineLabel(s.engine)} 的 Key")
            AppTextField(s.apiKeys[s.engine.name].orEmpty(), { s = s.copy(apiKeys = s.apiKeys + (s.engine.name to it)) }, password = true)
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("搜索走代理", "用 Tavily、Brave 时打开", s.useProxy) { s = s.copy(useProxy = it) }
        FieldLabel("每次搜索取几条结果：${s.maxResults}")
        Slider(s.maxResults.toFloat(), { s = s.copy(maxResults = it.toInt()) }, valueRange = 3f..10f, steps = 6)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { ctl.run(Command.SaveSettings(state.settings.copy(search = s)), "已保存") }) { Text("保存") }
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

@Composable
private fun ImagePage(ctl: AppController, state: AppState) {
    var ig by remember(state.settings.imageGen) { mutableStateOf(state.settings.imageGen) }
    val provider = state.provider(ig.providerId)
    PageHeader("画图", "选一个画图模型。输入框打开「画图」直接出图；聊天时 AI 也能自己调用它画图。")
    SectionCard {
        FieldLabel("服务商", "推荐：智谱 cogview-4、火山方舟 seedream、硅基流动 Kolors、OpenAI gpt-image-1")
        SelectBox(provider?.name ?: "选择服务商", leading = provider?.let { { PresetBadge(Presets.byId(it.presetId), 20.dp) } }) { close ->
            state.providers.forEach { p ->
                androidx.compose.material3.DropdownMenuItem({
                    Column {
                        Text(p.name)
                        val n = p.models.count { it.imageGen }
                        Text(if (n > 0) "$n 个画图模型" else "没有标记为画图的模型", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                }, leadingIcon = { PresetBadge(Presets.byId(p.presetId), 20.dp) }, onClick = {
                    ig = ig.copy(providerId = p.id, modelId = p.models.firstOrNull { it.imageGen }?.id.orEmpty()); close()
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("模型")
        if (provider != null) {
            val list = provider.models.filter { it.imageGen }
            if (list.isNotEmpty()) SelectBox(ig.modelId.ifBlank { "选择模型" }) { close ->
                list.forEach { md -> androidx.compose.material3.DropdownMenuItem({ Text(md.id) }, onClick = { ig = ig.copy(modelId = md.id); close() }) }
            }
            Spacer(Modifier.height(6.dp))
            AppTextField(ig.modelId, { ig = ig.copy(modelId = it.trim()) }, placeholder = "或者直接填模型名，如 cogview-4")
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("默认尺寸")
        FlowRowCompat {
            listOf("1024x1024", "1024x1536", "1536x1024", "768x1344", "1344x768", "2048x2048").forEach { sz ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
                    ToggleChip(sz, Icons.Rounded.Brush, ig.size == sz) { ig = ig.copy(size = sz) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { ctl.run(Command.SaveSettings(state.settings.copy(imageGen = ig)), "已保存") }) { Text("保存") }
    }
}

// ———————————————— 手机连接 ————————————————

@Composable
private fun DevicesPage(ctl: AppController, state: AppState) {
    if (!ctl.backend.isHost) {
        RemoteDevicePage(ctl)
        return
    }
    var srv by remember(state.settings.server) { mutableStateOf(state.settings.server) }
    PageHeader("手机连接", "电脑就是手机的服务器：不用云端，手机和电脑连同一个 Wi-Fi 就能用。聊天记录和 Key 都只在这台电脑上。")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("配对码", style = MaterialTheme.typography.labelLarge, color = Ext.c.subtle)
                Text(state.pairingCode.chunked(3).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = { ctl.run(Command.NewPairingCode) }) { Text("换一个") }
        }
        Spacer(Modifier.height(8.dp))
        Text("电脑地址", style = MaterialTheme.typography.labelLarge, color = Ext.c.subtle)
        if (state.serverAddresses.isEmpty()) Text(if (srv.enabled) "服务没起来，或者电脑没连局域网" else "服务已关闭", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        state.serverAddresses.forEach { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(10.dp))
        Text(
            "手机上：打开「AI集训营」→ 自动找到这台电脑（或手动填上面的地址）→ 输入配对码。\n" +
                "第一次运行时 Windows 会弹「是否允许访问网络」，请勾选「专用网络」并允许，否则手机连不上。\n" +
                "每个配对码只能用一次，配对成功后会自动换新的。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("已配对的设备", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (state.devices.isEmpty()) Text("还没有", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        state.devices.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp), tint = Ext.c.subtle)
                Spacer(Modifier.width(8.dp))
                Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { ctl.run(Command.RemoveDevice(d.token)) }) { Text("取消配对", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        SwitchRow("允许手机连接", "关掉后局域网服务停止", srv.enabled) { srv = srv.copy(enabled = it) }
        FieldLabel("电脑名称", "手机上显示的名字")
        AppTextField(srv.deviceName, { srv = srv.copy(deviceName = it.take(20)) })
        Spacer(Modifier.height(8.dp))
        FieldLabel("端口")
        AppTextField(srv.port.toString(), { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { srv = srv.copy(port = it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { ctl.run(Command.SaveSettings(state.settings.copy(server = srv)), "已保存，服务已重启") }) { Text("保存") }
    }
}

@Composable
private fun RemoteDevicePage(ctl: AppController) {
    val remote = ctl.backend as RemoteBackend
    val conn by remote.conn.collectAsState()
    val platform = LocalPlatform.current
    PageHeader("连接电脑", "手机是遥控器：模型调用、聊天记录、Key 都在电脑上。")
    SectionCard {
        Text(when (val c = conn) {
            is ConnState.Connected -> "已连接：${c.host}"
            ConnState.Connecting -> "正在连接 ${remote.hostAddress}…"
            is ConnState.Failed -> c.reason
            else -> "未连接"
        }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            platform.setPref("host", null); platform.setPref("token", null)
            remote.disconnect()
        }) { Text("断开并重新配对") }
    }
}

// ———————————————— 外观与其他 ————————————————

@Composable
private fun AppearancePage(ctl: AppController, state: AppState) {
    var s by remember(state.settings) { mutableStateOf(state.settings) }
    PageHeader("外观与通用", "主题、代理、群聊规则。")
    SectionCard {
        FieldLabel("主题")
        Row {
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, t) ->
                Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { s = s.copy(darkMode = v) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.darkMode == v, { s = s.copy(darkMode = v) }); Text(t)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("代理地址", "给海外服务商和海外搜索用，比如 v2rayN 默认 127.0.0.1:10809")
        AppTextField(s.proxy, { s = s.copy(proxy = it.trim()) }, placeholder = "127.0.0.1:10809")
        Spacer(Modifier.height(10.dp))
        FieldLabel("AI 互相 @ 最多接力几轮：${s.maxMentionChain}", "防止 AI 之间没完没了地互相点名")
        Slider(s.maxMentionChain.toFloat(), { s = s.copy(maxMentionChain = it.toInt()) }, valueRange = 0f..6f, steps = 5)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { ctl.run(Command.SaveSettings(s), "已保存") }) { Text("保存") }
    }
}

@Composable
private fun AboutPage(ctl: AppController) {
    PageHeader("关于", "AI集训营 1.0.0")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(44)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("AI集训营", style = MaterialTheme.typography.titleMedium)
                Text("多模型助手 · 群聊 · 联网 · 画图 · 读文档", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "· 不需要服务器：电脑端存数据、调模型，手机经局域网连电脑使用。\n" +
                "· 数据位置：电脑的 %APPDATA%\\ai-jixunying 文件夹，聊天记录、附件、设置都在里面，备份复制这个文件夹即可。\n" +
                "· API Key 只保存在电脑上，手机端只能看到打码后的样子。\n" +
                "· 所有模型都按 OpenAI 兼容协议接入。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
