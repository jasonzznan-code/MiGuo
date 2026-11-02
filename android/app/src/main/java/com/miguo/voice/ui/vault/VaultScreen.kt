package com.miguo.voice.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.home.MigoAccountSheet
import com.miguo.voice.ui.home.MigoAccountSheetHost
import com.miguo.voice.ui.theme.MigoTokens

data class VaultFile(val name: String, val sizeLabel: String)

/** 个人仓库：仅内存记录文件名与大小，不读取内容、不上传；详情走主稿 vaultdetail sheet。 */
@Composable
fun VaultScreen(onBack: () -> Unit, onNotice: (String) -> Unit) {
    val files = remember { mutableStateListOf<VaultFile>() }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<MigoAccountSheet.VaultDetail?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"
        files.add(0, VaultFile(name = name, sizeLabel = "大小未知 · 仅记录名称"))
        onNotice("已记录文件名（不读取内容、不上传、不持久化）")
    }
    val filtered = files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = MigoTokens.Primary, modifier = Modifier.semantics { contentDescription = "返回" })
            }
            Text("个人仓库", color = MigoTokens.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { contentDescription = "个人仓库" })
        }
        Text("收藏与本地文件名索引 · 安全存储待接入", color = MigoTokens.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索标题及文件名" },
            placeholder = { Text("搜索标题及文件名") },
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Text(
                "选择文件",
                color = MigoTokens.White,
                modifier = Modifier
                    .background(MigoTokens.Primary, MigoTokens.ButtonShape)
                    .clickable { picker.launch(arrayOf("*/*")) }
                    .semantics { contentDescription = "选择文件" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "收藏入口",
                color = MigoTokens.Ink,
                modifier = Modifier
                    .border(1.dp, MigoTokens.Line, MigoTokens.ButtonShape)
                    .clickable { onNotice("收藏内容待接入") }
                    .semantics { contentDescription = "收藏入口" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (filtered.isEmpty()) {
            Text("还没有文件记录。选择文件仅保存名称与大小提示。", color = MigoTokens.Muted, fontSize = 13.sp)
        } else {
            filtered.forEach { f ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(MigoTokens.CardMint.copy(alpha = 0.5f), MigoTokens.CardShape)
                        .clickable {
                            detail = MigoAccountSheet.VaultDetail(
                                name = f.name,
                                kind = "本地文件",
                                meta = f.sizeLabel,
                            )
                        }
                        .padding(14.dp),
                ) {
                    Text(f.name, color = MigoTokens.Ink, fontWeight = FontWeight.Medium)
                    Text(f.sizeLabel, color = MigoTokens.Muted, fontSize = 12.sp)
                }
            }
        }
    }
        detail?.let { d ->
            MigoAccountSheetHost(
                sheet = d,
                demoAccountName = "",
                onDismiss = { detail = null },
                onSaveDemoAccount = {},
            )
        }
    }
}
