package com.boykohub.looppractice

import android.app.Application
import android.util.Log
import java.io.File

class LoopApp : Application() {
    val engine: PracticeEngine by lazy { PracticeEngine(this) }

    override fun onCreate() {
        super.onCreate()
        // Save any crash so the app can show it (with a Copy button) the next time it opens.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(
                    "Crash in thread ${t.name} at ${java.util.Date()}\n" + Log.getStackTraceString(e)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}
