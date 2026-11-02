package com.miguo.voice.support

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * fix1：崩溃报告。
 * - 进程级：Thread.setDefaultUncaughtExceptionHandler，把堆栈写到 filesDir/crash.txt，再交给系统默认处理（进程照常退出）；
 * - 协程级：AppModel 的 CoroutineExceptionHandler 调 [record]（不退出，只记录 + 界面提示）。
 * 下次启动主界面顶部显示“上次异常退出”，可一键复制发给我们。
 */
object CrashReporter {
    private const val TAG = "miguo"
    private const val FILE = "crash.txt"
    @Volatile private var appContext: Context? = null
    @Volatile private var versionInfo: String = ""

    fun install(context: Context, version: String) {
        appContext = context.applicationContext
        versionInfo = version
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { record(e, "FATAL thread=${t.name}") } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(t, e) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
        Log.i(TAG, "[miguo] CrashReporter 已安装，报告文件：${file(context).absolutePath}")
    }

    fun file(context: Context): File = File(context.filesDir, FILE)

    /** 追加一条报告（保留最近 64 KB） */
    fun record(e: Throwable, kind: String) {
        val ctx = appContext ?: return
        val text = buildString {
            append("==== 米果崩溃报告 ").append(kind).append(" ====\n")
            append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("App：com.miguo.voice ").append(versionInfo).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append(Log.getStackTraceString(e).ifEmpty { e.stackTraceToString() }).append("\n\n")
        }
        Log.e(TAG, "[miguo] $kind: $e\n$text")
        try {
            val f = file(ctx)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + text).takeLast(64 * 1024))
        } catch (w: Throwable) {
            Log.e(TAG, "[miguo] 写 crash.txt 失败：$w")
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() && it.length() > 0 }?.readText()
    } catch (_: Throwable) { null }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Throwable) {}
    }
}
