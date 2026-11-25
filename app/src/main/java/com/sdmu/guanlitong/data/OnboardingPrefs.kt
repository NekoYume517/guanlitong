package com.sdmu.guanlitong.data

import android.content.Context

/** 首次启动引导只出现一次；跳过或看完都算看过。 */
object OnboardingPrefs {
    private const val FILE = "guanlitong_onboarding"
    private const val KEY_DONE = "done_v1"

    fun shouldShow(context: Context): Boolean =
        !context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }
}
