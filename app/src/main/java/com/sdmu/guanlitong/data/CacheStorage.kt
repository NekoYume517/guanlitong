package com.sdmu.guanlitong.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 本地缓存落盘的键值抽象。
 *
 * 线上实现落 SharedPreferences；单测用内存 Map 实现，就能在 JVM 上验证
 * 「按 origin 隔离」「保存后版本号递增」，不必依赖 Android 运行时。
 */
internal interface CacheShelf {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun clear()
}

internal class SharedPrefsShelf(context: Context, name: String) : CacheShelf {

    private val prefs = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * 进程内的缓存版本号。
 *
 * 保存成功就 +1；概览卡片把它当 `remember` 的 key，读完新缓存后立刻重组，
 * 不需要轮询，也不让卡片自己发请求。
 */
internal class CacheVersion {

    private val _value = MutableStateFlow(0)
    val value: StateFlow<Int> = _value.asStateFlow()

    fun bump() {
        // 成绩页与桌面预热可能在不同线程同时保存，用 update 保证 +1 不丢。
        _value.update { it + 1 }
    }
}