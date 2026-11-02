package com.miguo.voice

import android.app.Application
import com.miguo.voice.support.CrashReporter

/** fix1：尽早安装崩溃记录器（早于 Activity / ViewModel 创建） */
class MiguoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
