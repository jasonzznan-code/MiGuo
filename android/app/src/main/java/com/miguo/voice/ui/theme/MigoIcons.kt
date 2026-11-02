package com.miguo.voice.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 定稿线图标（自绘 Path，禁止用问号字符冒充）。 */
object MigoIcons {
    val Home: ImageVector by lazy {
        lineIcon("Home") {
            moveTo(4.5f, 10.5f)
            lineTo(12f, 4f)
            lineTo(19.5f, 10.5f)
            moveTo(6.5f, 9.8f)
            verticalLineTo(18.5f)
            horizontalLineTo(10.2f)
            verticalLineTo(13.5f)
            horizontalLineTo(13.8f)
            verticalLineTo(18.5f)
            horizontalLineTo(17.5f)
            verticalLineTo(9.8f)
        }
    }

    val Plan: ImageVector by lazy {
        lineIcon("Plan") {
            moveTo(7f, 4.5f)
            verticalLineTo(6.5f)
            moveTo(17f, 4.5f)
            verticalLineTo(6.5f)
            moveTo(5.5f, 8.5f)
            horizontalLineTo(18.5f)
            moveTo(6.5f, 4.5f)
            horizontalLineTo(17.5f)
            curveTo(18.6f, 4.5f, 19.5f, 5.4f, 19.5f, 6.5f)
            verticalLineTo(18f)
            curveTo(19.5f, 19.1f, 18.6f, 20f, 17.5f, 20f)
            horizontalLineTo(6.5f)
            curveTo(5.4f, 20f, 4.5f, 19.1f, 4.5f, 18f)
            verticalLineTo(6.5f)
            curveTo(4.5f, 5.4f, 5.4f, 4.5f, 6.5f, 4.5f)
            close()
            moveTo(8f, 12f)
            horizontalLineTo(11f)
            moveTo(8f, 15.5f)
            horizontalLineTo(16f)
        }
    }

    val Leaf: ImageVector by lazy {
        lineIcon("Leaf") {
            moveTo(12f, 20f)
            curveTo(12f, 20f, 5.5f, 16.5f, 5.5f, 10.5f)
            curveTo(5.5f, 7.5f, 7.8f, 5f, 12f, 4f)
            curveTo(16.2f, 5f, 18.5f, 7.5f, 18.5f, 10.5f)
            curveTo(18.5f, 16.5f, 12f, 20f, 12f, 20f)
            close()
            moveTo(12f, 4.2f)
            verticalLineTo(20f)
        }
    }

    val Menu: ImageVector by lazy {
        lineIcon("Menu") {
            moveTo(5f, 7.5f)
            horizontalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
            moveTo(5f, 16.5f)
            horizontalLineTo(19f)
        }
    }

    val Back: ImageVector by lazy {
        lineIcon("Back") {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }

    val Search: ImageVector by lazy {
        lineIcon("Search") {
            moveTo(15.8f, 15.8f)
            lineTo(18.5f, 18.5f)
            moveTo(10.8f, 5.5f)
            curveTo(13.7f, 5.5f, 16f, 7.8f, 16f, 10.7f)
            curveTo(16f, 13.6f, 13.7f, 15.9f, 10.8f, 15.9f)
            curveTo(7.9f, 15.9f, 5.6f, 13.6f, 5.6f, 10.7f)
            curveTo(5.6f, 7.8f, 7.9f, 5.5f, 10.8f, 5.5f)
            close()
        }
    }

    val Add: ImageVector by lazy {
        lineIcon("Add") {
            moveTo(12f, 6f)
            verticalLineTo(18f)
            moveTo(6f, 12f)
            horizontalLineTo(18f)
        }
    }

    val Camera: ImageVector by lazy {
        lineIcon("Camera") {
            moveTo(8f, 8.5f)
            lineTo(9.2f, 6.5f)
            horizontalLineTo(14.8f)
            lineTo(16f, 8.5f)
            horizontalLineTo(18f)
            curveTo(18.8f, 8.5f, 19.5f, 9.2f, 19.5f, 10f)
            verticalLineTo(16.5f)
            curveTo(19.5f, 17.3f, 18.8f, 18f, 18f, 18f)
            horizontalLineTo(6f)
            curveTo(5.2f, 18f, 4.5f, 17.3f, 4.5f, 16.5f)
            verticalLineTo(10f)
            curveTo(4.5f, 9.2f, 5.2f, 8.5f, 6f, 8.5f)
            close()
            moveTo(12f, 11f)
            curveTo(13.4f, 11f, 14.5f, 12.1f, 14.5f, 13.5f)
            curveTo(14.5f, 14.9f, 13.4f, 16f, 12f, 16f)
            curveTo(10.6f, 16f, 9.5f, 14.9f, 9.5f, 13.5f)
            curveTo(9.5f, 12.1f, 10.6f, 11f, 12f, 11f)
            close()
        }
    }


    val MicOff: ImageVector by lazy {
        lineIcon("MicOff") {
            moveTo(12f, 4.5f)
            curveTo(13.4f, 4.5f, 14.5f, 5.6f, 14.5f, 7f)
            verticalLineTo(12f)
            curveTo(14.5f, 13.4f, 13.4f, 14.5f, 12f, 14.5f)
            curveTo(10.6f, 14.5f, 9.5f, 13.4f, 9.5f, 12f)
            verticalLineTo(7f)
            curveTo(9.5f, 5.6f, 10.6f, 4.5f, 12f, 4.5f)
            close()
            moveTo(7f, 11.5f)
            verticalLineTo(12f)
            curveTo(7f, 14.8f, 9.2f, 17f, 12f, 17f)
            curveTo(14.8f, 17f, 17f, 14.8f, 17f, 12f)
            verticalLineTo(11.5f)
            moveTo(12f, 17f)
            verticalLineTo(19.5f)
            moveTo(5.5f, 5.5f)
            lineTo(18.5f, 18.5f)
        }
    }

    val Mic: ImageVector by lazy {
        lineIcon("Mic") {
            moveTo(12f, 4.5f)
            curveTo(13.4f, 4.5f, 14.5f, 5.6f, 14.5f, 7f)
            verticalLineTo(12f)
            curveTo(14.5f, 13.4f, 13.4f, 14.5f, 12f, 14.5f)
            curveTo(10.6f, 14.5f, 9.5f, 13.4f, 9.5f, 12f)
            verticalLineTo(7f)
            curveTo(9.5f, 5.6f, 10.6f, 4.5f, 12f, 4.5f)
            close()
            moveTo(7f, 11.5f)
            verticalLineTo(12f)
            curveTo(7f, 14.8f, 9.2f, 17f, 12f, 17f)
            curveTo(14.8f, 17f, 17f, 14.8f, 17f, 12f)
            verticalLineTo(11.5f)
            moveTo(12f, 17f)
            verticalLineTo(19.5f)
        }
    }

    val Wave: ImageVector by lazy {
        lineIcon("Wave") {
            moveTo(4f, 12f)
            verticalLineTo(12.01f)
            moveTo(7f, 9f)
            verticalLineTo(15f)
            moveTo(10f, 7f)
            verticalLineTo(17f)
            moveTo(13f, 9.5f)
            verticalLineTo(14.5f)
            moveTo(16f, 8f)
            verticalLineTo(16f)
            moveTo(19f, 11f)
            verticalLineTo(13f)
        }
    }

    val Bell: ImageVector by lazy {
        lineIcon("Bell") {
            moveTo(12f, 4.5f)
            curveTo(14.5f, 4.5f, 16.5f, 6.5f, 16.5f, 9f)
            verticalLineTo(13.5f)
            lineTo(18f, 15.5f)
            horizontalLineTo(6f)
            lineTo(7.5f, 13.5f)
            verticalLineTo(9f)
            curveTo(7.5f, 6.5f, 9.5f, 4.5f, 12f, 4.5f)
            close()
            moveTo(10.2f, 17.5f)
            curveTo(10.5f, 18.4f, 11.2f, 19f, 12f, 19f)
            curveTo(12.8f, 19f, 13.5f, 18.4f, 13.8f, 17.5f)
        }
    }

    val Settings: ImageVector by lazy {
        lineIcon("Settings") {
            moveTo(12f, 9f)
            curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f)
            curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
            curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f)
            curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
            close()
            moveTo(12f, 4.5f)
            lineTo(13.2f, 6.2f)
            lineTo(15.2f, 5.6f)
            lineTo(16.4f, 7.4f)
            lineTo(18.3f, 7.8f)
            lineTo(18.3f, 10f)
            lineTo(19.5f, 11.5f)
            lineTo(18.3f, 13f)
            lineTo(18.3f, 15.2f)
            lineTo(16.4f, 15.6f)
            lineTo(15.2f, 17.4f)
            lineTo(13.2f, 16.8f)
            lineTo(12f, 18.5f)
            lineTo(10.8f, 16.8f)
            lineTo(8.8f, 17.4f)
            lineTo(7.6f, 15.6f)
            lineTo(5.7f, 15.2f)
            lineTo(5.7f, 13f)
            lineTo(4.5f, 11.5f)
            lineTo(5.7f, 10f)
            lineTo(5.7f, 7.8f)
            lineTo(7.6f, 7.4f)
            lineTo(8.8f, 5.6f)
            lineTo(10.8f, 6.2f)
            close()
        }
    }

    val Scan: ImageVector by lazy {
        lineIcon("Scan") {
            moveTo(7f, 5.5f)
            horizontalLineTo(5.5f)
            verticalLineTo(7f)
            moveTo(5.5f, 17f)
            verticalLineTo(18.5f)
            horizontalLineTo(7f)
            moveTo(17f, 18.5f)
            horizontalLineTo(18.5f)
            verticalLineTo(17f)
            moveTo(18.5f, 7f)
            verticalLineTo(5.5f)
            horizontalLineTo(17f)
            moveTo(8.5f, 12f)
            horizontalLineTo(15.5f)
        }
    }

    val Chat: ImageVector by lazy {
        lineIcon("Chat") {
            moveTo(6f, 6.5f)
            horizontalLineTo(18f)
            curveTo(18.8f, 6.5f, 19.5f, 7.2f, 19.5f, 8f)
            verticalLineTo(14.5f)
            curveTo(19.5f, 15.3f, 18.8f, 16f, 18f, 16f)
            horizontalLineTo(10f)
            lineTo(6f, 19f)
            verticalLineTo(8f)
            curveTo(6f, 7.2f, 6.2f, 6.5f, 6f, 6.5f)
            close()
        }
    }

    val Vault: ImageVector by lazy {
        lineIcon("Vault") {
            moveTo(7f, 6f)
            horizontalLineTo(17f)
            verticalLineTo(19f)
            horizontalLineTo(7f)
            close()
            moveTo(9.5f, 6f)
            verticalLineTo(19f)
            moveTo(14.5f, 6f)
            verticalLineTo(19f)
            moveTo(7f, 10.5f)
            horizontalLineTo(17f)
            moveTo(7f, 14.5f)
            horizontalLineTo(17f)
        }
    }

    val Device: ImageVector by lazy {
        lineIcon("Device") {
            moveTo(9f, 4.5f)
            horizontalLineTo(15f)
            curveTo(15.8f, 4.5f, 16.5f, 5.2f, 16.5f, 6f)
            verticalLineTo(18f)
            curveTo(16.5f, 18.8f, 15.8f, 19.5f, 15f, 19.5f)
            horizontalLineTo(9f)
            curveTo(8.2f, 19.5f, 7.5f, 18.8f, 7.5f, 18f)
            verticalLineTo(6f)
            curveTo(7.5f, 5.2f, 8.2f, 4.5f, 9f, 4.5f)
            close()
            moveTo(10.5f, 17f)
            horizontalLineTo(13.5f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        lineIcon("ChevronRight") {
            moveTo(9.5f, 6f)
            lineTo(15.5f, 12f)
            lineTo(9.5f, 18f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        lineIcon("ChevronDown") {
            moveTo(6f, 9.5f)
            lineTo(12f, 15.5f)
            lineTo(18f, 9.5f)
        }
    }

    val Volume: ImageVector by lazy {
        lineIcon("Volume") {
            moveTo(5.5f, 9.5f)
            horizontalLineTo(8f)
            lineTo(11.5f, 6.5f)
            verticalLineTo(17.5f)
            lineTo(8f, 14.5f)
            horizontalLineTo(5.5f)
            close()
            moveTo(14f, 9.5f)
            curveTo(15f, 10.3f, 15.5f, 11.3f, 15.5f, 12.5f)
            curveTo(15.5f, 13.7f, 15f, 14.7f, 14f, 15.5f)
        }
    }

    val Copy: ImageVector by lazy {
        lineIcon("Copy") {
            moveTo(9f, 8f)
            horizontalLineTo(17.5f)
            verticalLineTo(18.5f)
            horizontalLineTo(9f)
            close()
            moveTo(6.5f, 5.5f)
            horizontalLineTo(14.5f)
            verticalLineTo(8f)
            moveTo(6.5f, 5.5f)
            verticalLineTo(15.5f)
            horizontalLineTo(9f)
        }
    }

    val Share: ImageVector by lazy {
        lineIcon("Share") {
            moveTo(8f, 12f)
            lineTo(16f, 7f)
            moveTo(8f, 12f)
            lineTo(16f, 17f)
            moveTo(7f, 12f)
            curveTo(7f, 13.1f, 6.1f, 14f, 5f, 14f)
            curveTo(3.9f, 14f, 3f, 13.1f, 3f, 12f)
            curveTo(3f, 10.9f, 3.9f, 10f, 5f, 10f)
            curveTo(6.1f, 10f, 7f, 10.9f, 7f, 12f)
            close()
            moveTo(19f, 7f)
            curveTo(19f, 8.1f, 18.1f, 9f, 17f, 9f)
            curveTo(15.9f, 9f, 15f, 8.1f, 15f, 7f)
            curveTo(15f, 5.9f, 15.9f, 5f, 17f, 5f)
            curveTo(18.1f, 5f, 19f, 5.9f, 19f, 7f)
            close()
            moveTo(19f, 17f)
            curveTo(19f, 18.1f, 18.1f, 19f, 17f, 19f)
            curveTo(15.9f, 19f, 15f, 18.1f, 15f, 17f)
            curveTo(15f, 15.9f, 15.9f, 15f, 17f, 15f)
            curveTo(18.1f, 15f, 19f, 15.9f, 19f, 17f)
            close()
        }
    }

    val Star: ImageVector by lazy {
        lineIcon("Star") {
            moveTo(12f, 4.5f)
            lineTo(13.9f, 9.2f)
            lineTo(19f, 9.6f)
            lineTo(15.2f, 12.9f)
            lineTo(16.4f, 18f)
            lineTo(12f, 15.4f)
            lineTo(7.6f, 18f)
            lineTo(8.8f, 12.9f)
            lineTo(5f, 9.6f)
            lineTo(10.1f, 9.2f)
            close()
        }
    }

    val Refresh: ImageVector by lazy {
        lineIcon("Refresh") {
            moveTo(18.5f, 9.5f)
            curveTo(17.4f, 6.8f, 14.9f, 5f, 12f, 5f)
            curveTo(8.1f, 5f, 5f, 8.1f, 5f, 12f)
            curveTo(5f, 15.9f, 8.1f, 19f, 12f, 19f)
            curveTo(15.2f, 19f, 17.8f, 16.9f, 18.7f, 14f)
            moveTo(18.5f, 5.5f)
            verticalLineTo(9.5f)
            horizontalLineTo(14.5f)
        }
    }

    val Send: ImageVector by lazy {
        lineIcon("Send") {
            moveTo(5f, 12f)
            lineTo(19f, 5f)
            lineTo(12.5f, 12f)
            lineTo(19f, 19f)
            close()
            moveTo(5f, 12f)
            horizontalLineTo(12.5f)
        }
    }

    val EndCall: ImageVector by lazy {
        lineIcon("EndCall") {
            moveTo(6f, 10.5f)
            curveTo(9.5f, 8.5f, 14.5f, 8.5f, 18f, 10.5f)
            lineTo(16.5f, 13f)
            curveTo(15.2f, 12.2f, 13.6f, 11.8f, 12f, 11.8f)
            curveTo(10.4f, 11.8f, 8.8f, 12.2f, 7.5f, 13f)
            close()
        }
    }

    val Video: ImageVector by lazy {
        lineIcon("Video") {
            moveTo(5.5f, 8f)
            horizontalLineTo(14f)
            curveTo(14.8f, 8f, 15.5f, 8.7f, 15.5f, 9.5f)
            verticalLineTo(14.5f)
            curveTo(15.5f, 15.3f, 14.8f, 16f, 14f, 16f)
            horizontalLineTo(5.5f)
            curveTo(4.7f, 16f, 4f, 15.3f, 4f, 14.5f)
            verticalLineTo(9.5f)
            curveTo(4f, 8.7f, 4.7f, 8f, 5.5f, 8f)
            close()
            moveTo(15.5f, 10.5f)
            lineTo(20f, 8f)
            verticalLineTo(16f)
            lineTo(15.5f, 13.5f)
        }
    }

    val Keyboard: ImageVector by lazy {
        lineIcon("Keyboard") {
            moveTo(4.5f, 7.5f)
            horizontalLineTo(19.5f)
            verticalLineTo(16.5f)
            horizontalLineTo(4.5f)
            close()
            moveTo(7f, 10f)
            horizontalLineTo(8.2f)
            moveTo(10.5f, 10f)
            horizontalLineTo(11.7f)
            moveTo(14f, 10f)
            horizontalLineTo(15.2f)
            moveTo(17f, 10f)
            horizontalLineTo(17.5f)
            moveTo(7f, 13.5f)
            horizontalLineTo(17f)
        }
    }

    val More: ImageVector by lazy {
        lineIcon("More") {
            moveTo(5.5f, 12f)
            horizontalLineTo(6.5f)
            moveTo(11.5f, 12f)
            horizontalLineTo(12.5f)
            moveTo(17.5f, 12f)
            horizontalLineTo(18.5f)
            moveTo(6f, 10.8f)
            verticalLineTo(13.2f)
            moveTo(12f, 10.8f)
            verticalLineTo(13.2f)
            moveTo(18f, 10.8f)
            verticalLineTo(13.2f)
        }
    }

    val Clock: ImageVector by lazy {
        lineIcon("Clock") {
            moveTo(12f, 5f)
            curveTo(15.9f, 5f, 19f, 8.1f, 19f, 12f)
            curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f)
            curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f)
            curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f)
            close()
            moveTo(12f, 8f)
            verticalLineTo(12.5f)
            lineTo(15f, 14f)
        }
    }

    val Image: ImageVector by lazy {
        lineIcon("Image") {
            moveTo(4.5f, 5.5f)
            horizontalLineTo(19.5f)
            verticalLineTo(18.5f)
            horizontalLineTo(4.5f)
            close()
            moveTo(8f, 9.5f)
            curveTo(8.8f, 9.5f, 9.5f, 10.2f, 9.5f, 11f)
            curveTo(9.5f, 11.8f, 8.8f, 12.5f, 8f, 12.5f)
            curveTo(7.2f, 12.5f, 6.5f, 11.8f, 6.5f, 11f)
            curveTo(6.5f, 10.2f, 7.2f, 9.5f, 8f, 9.5f)
            close()
            moveTo(6f, 17f)
            lineTo(10f, 13f)
            lineTo(12.5f, 15.5f)
            lineTo(15.5f, 12f)
            lineTo(18.5f, 16.5f)
        }
    }

    val File: ImageVector by lazy {
        lineIcon("File") {
            moveTo(7f, 3.5f)
            horizontalLineTo(14f)
            lineTo(18.5f, 8f)
            verticalLineTo(20.5f)
            horizontalLineTo(7f)
            close()
            moveTo(14f, 3.5f)
            verticalLineTo(8f)
            horizontalLineTo(18.5f)
            moveTo(9.5f, 12.5f)
            horizontalLineTo(16f)
            moveTo(9.5f, 16f)
            horizontalLineTo(14f)
        }
    }

    val Phone: ImageVector by lazy {
        lineIcon("Phone") {
            moveTo(7.5f, 4f)
            lineTo(5f, 5.8f)
            curveTo(5f, 12.5f, 11.5f, 19f, 18.2f, 19f)
            lineTo(20f, 16.5f)
            lineTo(16f, 13.5f)
            lineTo(13.8f, 15.2f)
            curveTo(11.5f, 14f, 10f, 12.5f, 8.8f, 10.2f)
            lineTo(10.5f, 8f)
            close()
        }
    }

    val Check: ImageVector by lazy {
        lineIcon("Check") {
            moveTo(5.5f, 12f)
            lineTo(9.5f, 16f)
            lineTo(18.5f, 7f)
        }
    }

    val Pin: ImageVector by lazy {
        lineIcon("Pin") {
            moveTo(10f, 4f)
            lineTo(16.5f, 6.5f)
            lineTo(14.8f, 9.8f)
            lineTo(16.5f, 13f)
            lineTo(11.5f, 11.5f)
            lineTo(7.5f, 15.5f)
            lineTo(9f, 10f)
            lineTo(6.5f, 7.5f)
            close()
            moveTo(8.5f, 15f)
            lineTo(5.5f, 19.5f)
        }
    }

    val Close: ImageVector by lazy {
        lineIcon("Close") {
            moveTo(6.5f, 6.5f)
            lineTo(17.5f, 17.5f)
            moveTo(6.5f, 17.5f)
            lineTo(17.5f, 6.5f)
        }
    }



    val Food: ImageVector by lazy {
        lineIcon("Food") {
            // fork
            moveTo(4f, 3f)
            verticalLineTo(10f)
            curveTo(4f, 11.7f, 5.3f, 13f, 7f, 13f)
            curveTo(8.7f, 13f, 10f, 11.7f, 10f, 10f)
            verticalLineTo(3f)
            moveTo(7f, 3f)
            verticalLineTo(21f)
            // spoon
            moveTo(19f, 3f)
            curveTo(15f, 6f, 15f, 11f, 19f, 12f)
            verticalLineTo(21f)
            moveTo(19f, 3f)
            verticalLineTo(12f)
        }
    }

    val Train: ImageVector by lazy {
        lineIcon("Train") {
            moveTo(10f, 3f)
            horizontalLineTo(14f)
            curveTo(16.2f, 3f, 18f, 4.8f, 18f, 7f)
            verticalLineTo(14f)
            curveTo(18f, 16.2f, 16.2f, 18f, 14f, 18f)
            horizontalLineTo(10f)
            curveTo(7.8f, 18f, 6f, 16.2f, 6f, 14f)
            verticalLineTo(7f)
            curveTo(6f, 4.8f, 7.8f, 3f, 10f, 3f)
            close()
            moveTo(6f, 10f)
            horizontalLineTo(18f)
            moveTo(9f, 21f)
            lineTo(11f, 18f)
            moveTo(15f, 21f)
            lineTo(13f, 18f)
            moveTo(10f, 14f)
            horizontalLineTo(10.1f)
            moveTo(14f, 14f)
            horizontalLineTo(14.1f)
        }
    }

    private fun lineIcon(
        name: String,
        fill: Boolean = false,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = if (fill) SolidColor(Color.Black) else SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()
}
