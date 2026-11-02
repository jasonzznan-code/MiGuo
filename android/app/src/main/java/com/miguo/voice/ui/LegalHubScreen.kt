package com.miguo.voice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

private enum class LegalPage { Hub, Licenses, Gpl, Privacy, Delete }

/**
 * 合规与关于总入口：GPL / 源码 / 隐私 / 注销。登录页与设置页共用。
 * @param model 注销流程需要；仅浏览 GPL/隐私/源码时可传 null（注销入口会提示需 model）。
 */
@Composable
fun LegalHubScreen(model: AppModel?, onDone: () -> Unit) {
    var page by remember { mutableStateOf(LegalPage.Hub) }
    val ctx = LocalContext.current
    when (page) {
        LegalPage.Licenses -> OpenSourceLicensesScreen(
            onDone = { page = LegalPage.Hub },
            onOpenGplFull = { page = LegalPage.Gpl },
        )
        LegalPage.Gpl -> GplFullTextScreen(onDone = { page = LegalPage.Hub })
        LegalPage.Privacy -> PrivacyPolicyScreen(onDone = { page = LegalPage.Hub })
        LegalPage.Delete -> {
            if (model != null) AccountDeletionScreen(model = model, onDone = { page = LegalPage.Hub })
            else {
                page = LegalPage.Hub
            }
        }
        LegalPage.Hub -> Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        MigoIcons.Back,
                        contentDescription = "返回",
                        tint = MigoTokens.Ink,
                        modifier = Modifier.size(40.dp).clickable(onClick = onDone).padding(8.dp),
                    )
                    Text("合规与关于", color = MigoTokens.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "运营主体：" + MiguoLegal.OPERATOR,
                    color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                LegalRow("开源许可 / GPL", MiguoLegal.GPL_SPDX) { page = LegalPage.Licenses }
                LegalRow("GPL 全文", "应用内 LICENSE") { page = LegalPage.Gpl }
                LegalRow("开源仓库", "GitHub") {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MiguoLegal.SOURCE_URL)))
                    }
                }
                LegalRow("隐私政策", if (MiguoLegal.PRIVACY_HOSTED) "外链" else "应用内说明") {
                    page = LegalPage.Privacy
                }
                LegalRow("注销账号", "可点流程") {
                    if (model != null) page = LegalPage.Delete
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "源码：" + MiguoLegal.SOURCE_URL + "\n联系：" + MiguoLegal.CONTACT_EMAIL +
                        "\n隐私托管：" + MiguoLegal.PRIVACY_URL,
                    color = MigoTokens.Muted, fontSize = 11.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun LegalRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$sub ›", color = MigoTokens.Muted, fontSize = 10.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
}
