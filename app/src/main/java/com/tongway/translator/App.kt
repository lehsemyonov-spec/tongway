package com.tongway.translator

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        engine = TranslationEngine(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("Сбой приложения", error)
            previous?.uncaughtException(thread, error)
        }
        AppLog.i("Запуск, версия ${BuildConfig.VERSION_NAME}")
    }

    companion object {
        /** Один переводчик на всё приложение: его используют и главный экран, и кружок. */
        lateinit var engine: TranslationEngine
            private set
    }
}
