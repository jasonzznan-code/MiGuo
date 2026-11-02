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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.theme.MigoTokens

/** 应用内静态隐私说明。不伪造已上线托管站。 */
@Composable
fun PrivacyPolicyScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val hosted = MiguoLegal.PRIVACY_URL.trim().let {
        it.isNotEmpty() && !it.equals("PENDING", ignoreCase = true) && it.startsWith("http")
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        ZenTopBar(title = "隐私政策", onDone = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("运营主体", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(MiguoLegal.OPERATOR, color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp)
            Text("联系方式", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(
                "邮箱：" + MiguoLegal.CONTACT_EMAIL,
                color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text("我们收集的信息", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(
                "· 账号标识：邮箱或手机号（用于登录与验证码）\n" +
                    "· 登录凭证：服务端下发的可吊销会话令牌（仅存本机加密存储）\n" +
                    "· 对话内容：你主动发送的文字及相关会话元数据（用于提供对话服务）\n" +
                    "· 设备与诊断：应用版本、基础崩溃日志（用于稳定性）\n" +
                    "· （本版本不采集位置）：仅在你授权并主动使用相关功能时采集",
                color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text("用途", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(
                "用于账号登录、提供米果对话与相关功能、保障安全与排查故障。不会把登录凭证写进对外分发的商店包。",
                color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text("第三方与开源", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(
                "客户端以 " + MiguoLegal.GPL_SPDX + " 许可公开源码意向地址：" + MiguoLegal.SOURCE_URL +
                    "。第三方组件见「开源许可」页。",
                color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text("你的权利", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            Text(
                "可在设置中申请注销账号；登录后将调用鉴权侧注销接口。未登录时可提交本机「注销申请」记录并通过联系方式跟进。",
                color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text("托管外链状态", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
            if (hosted) {
                Text(
                    "打开外部隐私页：" + MiguoLegal.PRIVACY_URL,
                    color = MigoTokens.Primary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MiguoLegal.PRIVACY_URL)))
                            }
                        }
                        .padding(vertical = 4.dp),
                )
            } else {
                Text(
                    "外部隐私政策：https://ai.njzyb.com:3010/privacy",
                    color = MigoTokens.Muted, fontSize = 13.sp, lineHeight = 20.sp,
                )
            }
            Text(
                "本文为产品内静态说明，随版本更新；最终法律文本以主体确认后的托管页为准（若有）。",
                color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
    }
}
