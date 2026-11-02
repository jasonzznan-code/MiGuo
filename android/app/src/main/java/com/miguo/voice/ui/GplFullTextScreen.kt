package com.miguo.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.theme.MigoTokens

/** 应用内展示 GPL-3.0-or-later 全文（assets）。 */
@Composable
fun GplFullTextScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val body = remember {
        runCatching {
            ctx.assets.open(MiguoLegal.GPL_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            "未能加载本地 " + MiguoLegal.GPL_ASSET + "。工程根目录 LICENSE 为 " + MiguoLegal.GPL_SPDX +
                "。也可参阅 https://www.gnu.org/licenses/gpl-3.0.html"
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        ZenTopBar(title = "GPL 全文", onDone = onDone)
        Text(
            body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MigoTokens.Ink,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
        )
    }
}
