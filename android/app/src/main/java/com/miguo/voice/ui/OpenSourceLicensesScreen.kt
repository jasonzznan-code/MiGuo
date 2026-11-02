package com.miguo.voice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OpenSourceLicensesScreen(
    onDone: () -> Unit,
    onOpenGplFull: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val thirdParty = remember {
        runCatching {
            ctx.assets.open("opensource_licenses.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "" }
    }
    val projectBody = listOf(
        "· 软件名称：米果 Android 客户端",
        "· 包名：com.miguo.voice",
        "· 运营主体：" + MiguoLegal.OPERATOR,
        "· 联系邮箱：" + MiguoLegal.CONTACT_EMAIL,
        "· 许可证：" + MiguoLegal.GPL_SPDX + "（见工程 LICENSE / 应用内全文）",
        "· 适用范围：仅公开 Android 客户端源码",
        "· 源码获取：" + MiguoLegal.SOURCE_URL,
    ).joinToString("\n")
    Column(
        Modifier
            .fillMaxSize()
            .background(ZenPalette.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        ZenTopBar(title = "开源许可", onDone = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "米果 Android 客户端（包名 com.miguo.voice）的开源声明与第三方许可证信息。",
                fontSize = 13.sp, color = ZenPalette.Ink, lineHeight = 20.sp,
            )
            SectionTitle("本项目")
            Text(projectBody, fontSize = 13.sp, color = ZenPalette.Ink, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth())
            Text(
                "打开开源仓库（GitHub）",
                fontSize = 13.sp, color = ZenPalette.MossDeep,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MiguoLegal.SOURCE_URL)))
                    }
                }.padding(vertical = 4.dp),
            )
            Text(
                "查看 GPL 全文（应用内）",
                fontSize = 13.sp, color = ZenPalette.MossDeep,
                modifier = Modifier.fillMaxWidth().clickable {
                    onOpenGplFull?.invoke()
                }.padding(vertical = 4.dp),
            )
            Text(
                "隐私政策：" + MiguoLegal.PRIVACY_URL,
                fontSize = 12.sp, color = ZenPalette.Seal, lineHeight = 18.sp,
            )
            SectionTitle("第三方组件")
            Text(
                "本应用包含下列开源组件（随 App 分发）。完整 NOTICE 以仓库为准。",
                fontSize = 13.sp, color = ZenPalette.Ink, lineHeight = 20.sp,
            )
            Text(
                thirdParty.ifBlank { "第三方清单加载失败；请见工程 NOTICE.template。" },
                fontSize = 12.sp, color = ZenPalette.Ink, fontFamily = FontFamily.SansSerif, lineHeight = 18.sp,
            )
            SectionTitle("许可证全文入口")
            Text(
                "· Apache License 2.0：https://www.apache.org/licenses/LICENSE-2.0\n" +
                    "· 本项目 " + MiguoLegal.GPL_SPDX + "：应用内「GPL 全文」或 https://www.gnu.org/licenses/gpl-3.0.html",
                fontSize = 13.sp, color = ZenPalette.Ink, lineHeight = 20.sp,
            )
            SectionTitle("美术素材")
            Text("米果形象及启动器图标为自有素材，不对外授权。", fontSize = 13.sp, color = ZenPalette.Ink, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ZenPalette.Ink, modifier = Modifier.padding(top = 4.dp))
}
