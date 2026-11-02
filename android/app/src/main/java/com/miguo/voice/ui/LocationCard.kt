package com.miguo.voice.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.location.LocationPolicy

/** fix2：“我的位置”卡片：设备定位（WGS-84）→ 服务器 /api/v1/map/reverse → 地址。 */
@Composable
fun LocationCard(model: AppModel) {
    val ui by model.location.collectAsState()
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9E7DC)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📍 我的位置", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF302F2B), modifier = Modifier.weight(1f))
            if (ui.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            TextButton(onClick = { model.locateMe() }, enabled = !ui.busy) {
                Text(if (ui.fix == null) "定位" else "重新定位", fontSize = 13.sp)
            }
        }
        ui.step?.let { Text(it, fontSize = 12.sp, color = Color.DarkGray) }
        ui.address?.let { Text(it.display, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        ui.address?.takeIf { it.widened }?.let { Text("（附近 1 km 内没有地点，已放宽到 ${it.radiusKmUsed?.toInt()} km 搜索，距离 ${it.distanceM?.toInt()} m）", fontSize = 11.sp, color = Color.DarkGray) }
        ui.fix?.let { f ->
            Text("${LocationPolicy.formatCoord(f.lat)}, ${LocationPolicy.formatCoord(f.lon)}（WGS-84）", fontSize = 11.sp, color = Color.DarkGray)
            Text(LocationPolicy.describe(f), fontSize = 11.sp, color = Color.DarkGray)
        }
        ui.warnings.forEach { Text("⚠️ $it", fontSize = 11.sp, color = Color(0xFFEF6C00)) }
        ui.error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC62828)) }
        ui.action?.let { a ->
            TextButton(onClick = {
                runCatching {
                    val i = when (a) {
                        LocationAction.APP_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        LocationAction.LOCATION_SETTINGS -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    }
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(if (a == LocationAction.APP_SETTINGS) "去设置定位权限" else "打开定位服务", fontSize = 12.sp) }
        }
    }
}
