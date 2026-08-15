package com.xieguiawu.apicheckers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguiawu.apicheckers.AppViewModel
import com.xieguiawu.apicheckers.data.Account
import com.xieguiawu.apicheckers.data.SecureSettings
import com.xieguiawu.apicheckers.ui.theme.Accent
import com.xieguiawu.apicheckers.ui.theme.Bg
import com.xieguiawu.apicheckers.ui.theme.Card
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.Divider
import com.xieguiawu.apicheckers.ui.theme.Ok
import com.xieguiawu.apicheckers.ui.theme.TextMain
import com.xieguiawu.apicheckers.ui.theme.TextSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 设置页 ─────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    // DeepSeek 账号（多 key）
    var dsAccounts by remember { mutableStateOf(SecureSettings.getDeepSeekAccounts()) }
    var dsEditing by remember { mutableStateOf(false) }
    var dsName by remember { mutableStateOf("") }
    var dsApiKey by remember { mutableStateOf("") }
    var dsToken by remember { mutableStateOf("") }
    var showDsKey by remember { mutableStateOf(false) }
    var showDsToken by remember { mutableStateOf(false) }
    var renamingDs by remember { mutableStateOf<com.xieguiawu.apicheckers.data.DeepSeekAccount?>(null) }
    // OpenCode 账号
    var accounts by remember { mutableStateOf(SecureSettings.getAccounts()) }
    var renaming by remember { mutableStateOf<com.xieguiawu.apicheckers.data.Account?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var accName by remember { mutableStateOf("") }
    var accKey by remember { mutableStateOf("") }
    var accWorkspace by remember { mutableStateOf("") }
    var accCookie by remember { mutableStateOf("") }
    var showAccKey by remember { mutableStateOf(false) }
    var showAccCookie by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    // 操作反馈
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun flashHint(msg: String) {
        hint = msg
        scope.launch {
            delay(2500)
            if (hint == msg) hint = null
        }
    }

    // Material 默认输入框颜色会泄漏非色板色，显式覆写全部关键槽位
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Divider,
        focusedTextColor = TextMain,
        unfocusedTextColor = TextMain,
        cursorColor = Accent,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextSub,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        // 安全警告条（Keystore 加密失败/解密失败时显示）
        SecureSettings.securityWarning?.let { warning ->
            Text(
                warning,
                color = Danger,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
            }
            Text(
                "设置",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            hint?.let { item(key = "hint") { Text(it, color = Ok, fontSize = 13.sp) } }

            // ── DeepSeek 分区 ──
            item(key = "deepseek") {
                SectionCard("DeepSeek 账号") {
                    if (dsAccounts.isEmpty()) {
                        Text("暂无 DeepSeek 账号", color = TextSub, fontSize = 13.sp)
                    }
                    dsAccounts.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.name, color = TextMain, fontSize = 14.sp)
                                Text(
                                    keyTail(acc.apiKey) + if (acc.hasToken) " · 消费" else "",
                                    color = TextSub,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = {
                                renamingDs = acc
                                renameText = acc.name
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = "重命名 DeepSeek 账号", tint = TextSub)
                            }
                            IconButton(onClick = {
                                SecureSettings.deleteDeepSeekAccount(acc.id)
                                dsAccounts = SecureSettings.getDeepSeekAccounts()
                                vm.refreshDeepSeek()
                                flashHint("已删除「${acc.name}」")
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除 DeepSeek 账号", tint = Danger)
                            }
                        }
                    }

                    if (dsEditing) {
                        HorizontalDivider(color = Divider, modifier = Modifier.padding(vertical = 4.dp))
                        OutlinedTextField(
                            value = dsName,
                            onValueChange = { dsName = it },
                            label = { Text("名称") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = dsApiKey,
                            onValueChange = { dsApiKey = it },
                            label = { Text("DeepSeek API Key") },
                            singleLine = true,
                            colors = fieldColors,
                            visualTransformation = if (showDsKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showDsKey = !showDsKey }) {
                                    Icon(
                                        if (showDsKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showDsKey) "隐藏" else "显示",
                                        tint = TextSub,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = dsToken,
                            onValueChange = { dsToken = it },
                            label = { Text("平台 Token（可选，看消费明细）") },
                            singleLine = true,
                            colors = fieldColors,
                            visualTransformation = if (showDsToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showDsToken = !showDsToken }) {
                                    Icon(
                                        if (showDsToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showDsToken) "隐藏" else "显示",
                                        tint = TextSub,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Token 获取：登录 platform.deepseek.com → DevTools → Network → 任意 api/v0 请求的 Authorization 头。几天到几周后过期。",
                            color = TextSub,
                            fontSize = 12.sp,
                        )
                        Row {
                            Button(onClick = {
                                val key = dsApiKey.trim()
                                if (key.isBlank()) {
                                    flashHint("API Key 不能为空")
                                } else {
                                    val name = dsName.trim().ifEmpty { "DeepSeek ${dsAccounts.size + 1}" }
                                    SecureSettings.saveDeepSeekAccount(
                                        com.xieguiawu.apicheckers.data.DeepSeekAccount(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = name,
                                            apiKey = key,
                                            platformToken = dsToken.trim(),
                                        ),
                                    )
                                    dsAccounts = SecureSettings.getDeepSeekAccounts()
                                    vm.refreshDeepSeek()
                                    dsEditing = false
                                    dsName = ""; dsApiKey = ""; dsToken = ""
                                    flashHint("已添加「$name」")
                                }
                            }) { Text("保存") }
                            TextButton(onClick = { dsEditing = false }) {
                                Text("取消", color = TextSub)
                            }
                        }
                    } else {
                        TextButton(onClick = {
                            dsEditing = true
                            dsName = "DeepSeek ${dsAccounts.size + 1}"
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(4.dp))
                            Text("添加 DeepSeek 账号", color = Accent, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ── OpenCode 账号分区 ──
            item(key = "accounts") {
                SectionCard("OpenCode 账号") {
                    if (accounts.isEmpty()) {
                        Text("暂无账号", color = TextSub, fontSize = 13.sp)
                    }
                    accounts.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.name, color = TextMain, fontSize = 14.sp)
                                Text(
                                    keyTail(acc.goApiKey) + if (acc.hasZen) " · Zen" else "",
                                    color = TextSub,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = {
                                renaming = acc
                                renameText = acc.name
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = "重命名账号", tint = TextSub)
                            }
                            IconButton(onClick = {
                                SecureSettings.deleteAccount(acc.id)
                                accounts = SecureSettings.getAccounts()
                                vm.refreshAll()
                                flashHint("已删除账号「${acc.name}」")
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除账号", tint = Danger)
                            }
                        }
                    }

                    if (editing) {
                        HorizontalDivider(color = Divider, modifier = Modifier.padding(vertical = 4.dp))
                        OutlinedTextField(
                            value = accName,
                            onValueChange = { accName = it },
                            label = { Text("名称") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = accKey,
                            onValueChange = { accKey = it },
                            label = { Text("Go API Key（必填）") },
                            singleLine = true,
                            colors = fieldColors,
                            visualTransformation = if (showAccKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAccKey = !showAccKey }) {
                                    Icon(
                                        if (showAccKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showAccKey) "隐藏" else "显示",
                                        tint = TextSub,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = accWorkspace,
                            onValueChange = { accWorkspace = it },
                            label = { Text("Workspace ID（可选）") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = accCookie,
                            onValueChange = { accCookie = it },
                            label = { Text("Auth Cookie（可选）") },
                            singleLine = true,
                            colors = fieldColors,
                            visualTransformation = if (showAccCookie) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAccCookie = !showAccCookie }) {
                                    Icon(
                                        if (showAccCookie) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showAccCookie) "隐藏" else "显示",
                                        tint = TextSub,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "浏览器 DevTools → Application → Cookies → opencode.ai → auth，值以 Fe26.2 开头。" +
                                "Workspace ID 见控制台 URL（wrk_ 开头）。",
                            color = TextSub,
                            fontSize = 12.sp,
                        )
                        formError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                val key = accKey.trim()
                                if (key.isBlank()) {
                                    formError = "Go API Key 不能为空"
                                } else {
                                    val name = accName.trim().ifEmpty { "账号 ${accounts.size + 1}" }
                                    SecureSettings.saveAccount(
                                        Account(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = name,
                                            goApiKey = key,
                                            workspaceId = accWorkspace.trim(),
                                            authCookie = accCookie.trim(),
                                        ),
                                    )
                                    accounts = SecureSettings.getAccounts()
                                    editing = false
                                    accName = ""
                                    accKey = ""
                                    accWorkspace = ""
                                    accCookie = ""
                                    formError = null
                                    vm.refreshAll()
                                    flashHint("账号「$name」已保存，正在刷新数据")
                                }
                            }) {
                                Text("保存账号")
                            }
                            TextButton(onClick = {
                                editing = false
                                accName = ""
                                accKey = ""
                                accWorkspace = ""
                                accCookie = ""
                                formError = null
                            }) {
                                Text("取消", color = TextSub)
                            }
                        }
                    } else {
                        TextButton(onClick = {
                            editing = true
                            accName = "账号 ${accounts.size + 1}"
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(4.dp))
                            Text("添加账号", color = Accent, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 账号重命名对话框
        renaming?.let { acc ->
            AlertDialog(
                onDismissRequest = { renaming = null },
                containerColor = Card,
                titleContentColor = TextMain,
                textContentColor = TextMain,
                title = { Text("重命名账号") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            cursorColor = Accent,
                            focusedLabelColor = Accent,
                            unfocusedLabelColor = TextSub,
                        ),
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val newName = renameText.trim()
                        if (newName.isNotBlank()) {
                            SecureSettings.saveAccount(acc.copy(name = newName))
                            accounts = SecureSettings.getAccounts()
                            flashHint("已重命名为「${newName}」")
                        }
                        renaming = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renaming = null }) { Text("取消", color = TextSub) }
                },
            )
        }

        // DeepSeek 账号重命名对话框
        renamingDs?.let { acc ->
            AlertDialog(
                onDismissRequest = { renamingDs = null },
                containerColor = Card,
                titleContentColor = TextMain,
                textContentColor = TextMain,
                title = { Text("重命名 DeepSeek 账号") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        colors = fieldColors,
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val newName = renameText.trim()
                        if (newName.isNotBlank()) {
                            SecureSettings.saveDeepSeekAccount(acc.copy(name = newName))
                            dsAccounts = SecureSettings.getDeepSeekAccounts()
                            flashHint("已重命名为「${newName}」")
                        }
                        renamingDs = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renamingDs = null }) { Text("取消", color = TextSub) }
                },
            )
        }
    }
}

/** 分区卡片：标题 + 内容 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** API key 尾号脱敏显示：sk-…4f3a；短 key（≤8 字符）整体掩码 */
private fun keyTail(key: String): String {
    if (key.length <= 8) return "••••"
    val tail = key.takeLast(4)
    return if (key.startsWith("sk-")) "sk-…$tail" else "…$tail"
}
