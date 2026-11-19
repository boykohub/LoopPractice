package com.boykohub.looppractice

import android.app.Application

class LoopApp : Application() {
    val engine: PracticeEngine by lazy { PracticeEngine(this) }
}
