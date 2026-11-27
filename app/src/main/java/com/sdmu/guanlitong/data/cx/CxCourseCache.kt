package com.sdmu.guanlitong.data.cx

/**
 * 课程详情缓存：二次进入同一门课直接秒开，下拉刷新（forceReload）才重新扫描。
 *
 * 缓存键带上会话 Cookie 的指纹：切换账号或重新登录后 Cookie 变了，旧条目自然
 * 不会被命中，不需要手动清理。只缓存在内存里，进程结束即失效。
 *
 * 有章节读取失败的结果**不缓存**——那种「部分数据」不该在二次进入时冒充完整结果。
 */
class CxCourseCache(private val maxEntries: Int = MAX_ENTRIES) {

    /** accessOrder=true：按访问顺序淘汰最久未用的条目。 */
    private val entries = LinkedHashMap<String, List<CxChapterContent>>(8, 0.75f, true)

    @Synchronized
    fun get(key: String): List<CxChapterContent>? = entries[key]

    @Synchronized
    fun put(key: String, groups: List<CxChapterContent>) {
        entries[key] = groups
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val MAX_ENTRIES = 5

        /** 进程内共享的缓存实例。 */
        val shared = CxCourseCache()

        /** 缓存键：会话指纹 + courseId + clazzId。指纹只用 hashCode，不保留 Cookie 明文。 */
        fun keyOf(session: CxSession, course: CxCourse): String {
            val sessionKey = session.cookies.entries
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
                .hashCode()
            return "$sessionKey:${course.courseId}:${course.clazzId}"
        }
    }
}