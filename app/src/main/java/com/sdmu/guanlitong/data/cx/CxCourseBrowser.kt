package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 详情页一章的内容：父章节名 + 子章节任务点。 */
data class CxChapterContent(
    val groupName: String,
    val children: List<CxChildContent>,
)

/**
 * 一个子章节的任务点；[error] 非空表示这一章没取到，[cards] 为空不代表
 * 「这章没有任务点」；[loading] 为 true 表示还在读取，UI 显示骨架。
 */
data class CxChildContent(
    val name: String,
    val cards: List<CxTaskCard>,
    val error: String? = null,
    val loading: Boolean = false,
)

/**
 * 一次详情加载的实时快照：章节骨架先出，卡片逐章填进来。
 *
 * [loaded] 只是「已尝试读取的章数」，失败章节也算；[remaining] 是还没开始的章数。
 */
data class CxCourseSnapshot(
    val groups: List<CxChapterContent>,
    val loaded: Int,
    val total: Int,
    val loading: Boolean,
    val cached: Boolean = false,
) {
    val remaining: Int get() = (total - loaded).coerceAtLeast(0)
}

/**
 * 课程详情数据源：课程中间页 → 章节树 → 每章任务点。
 *
 * 流式渲染：章节树一到就先发一版骨架（章节名 + 读取中），随后每章任务点返回就
 * 再发一版快照，UI 不必等全量。二次进入同一门课命中 [CxCourseCache] 直接秒开，
 * [forceReload] 才重新扫描。整段走 Repository 的 401 单飞重登。
 */
class CxCourseBrowser(
    private val repo: CxRepository,
    private val cache: CxCourseCache = CxCourseCache.shared,
    private val loader: CxChapterLoader = CxChapterLoader(),
) {

    suspend fun load(
        base: CxCourse,
        forceReload: Boolean = false,
        onUpdate: (CxCourseSnapshot) -> Unit = {},
    ): List<CxChapterContent> = repo.withSession { session ->
        val cacheKey = CxCourseCache.keyOf(session, base)
        if (!forceReload) {
            cache.get(cacheKey)?.let { cached ->
                Log.i(
                    TAG,
                    "cache hit course=${base.name} chapters=${cached.sumOf { it.children.size }}",
                )
                emit(
                    onUpdate,
                    CxCourseSnapshot(
                        groups = cached,
                        loaded = cached.sumOf { it.children.size },
                        total = cached.sumOf { it.children.size },
                        loading = false,
                        cached = true,
                    ),
                )
                return@withSession cached
            }
        }

        val startedAt = System.currentTimeMillis()
        val api = CxApi(session.cookies)
        val uid = session.uid.ifBlank { session.cookies["UID"].orEmpty() }
        val study = CxStudyApi(api, uid, session.fid)

        val detail = study.courseDetail(base.courseId, base.clazzId, base.personId)
        Log.i(TAG, "detail ms=${System.currentTimeMillis() - startedAt}")
        val course = CxStudyCourse(
            courseId = base.courseId,
            clazzId = base.clazzId,
            personId = base.personId,
            name = base.name,
            cpi = detail.cpi.ifBlank { base.personId },
            enc = detail.enc,
        )
        val tree = study.chapterTree(course.courseId, course.clazzId, course.cpi, course.enc)
        Log.i(TAG, "tree ms=${System.currentTimeMillis() - startedAt} chapters=${tree.sumOf { it.children.size }}")
        val chapters = tree.flatMap { it.children }

        val stateLock = Mutex()
        val states = LinkedHashMap<String, CxChildContent>()
        chapters.forEach { states[it.chapterId] = CxChildContent(it.name, emptyList(), loading = true) }

        fun snapshot(loading: Boolean) = CxCourseSnapshot(
            groups = tree.map { group ->
                CxChapterContent(
                    group.name,
                    group.children.map { states.getValue(it.chapterId) },
                )
            },
            loaded = states.values.count { !it.loading },
            total = states.size,
            loading = loading,
        )

        // 第一步：骨架（通常 1 秒内就能出）。
        emit(onUpdate, snapshot(loading = true))
        Log.i(
            TAG,
            "skeleton course=${base.name} chapters=${chapters.size} " +
                "ms=${System.currentTimeMillis() - startedAt}",
        )

        // 第二步：边取边渲染。并发/节流/风控降级都在 CxChapterLoader 里。
        loader.load(
            chapters = chapters,
            fetch = { child ->
                study.chapterCards(
                    courseId = course.courseId,
                    clazzId = course.clazzId,
                    cpi = course.cpi,
                    chapterId = child.chapterId,
                ).cards
            },
            onResult = { index, content ->
                val snap = stateLock.withLock {
                    states[chapters[index].chapterId] = content
                    snapshot(loading = true)
                }
                emit(onUpdate, snap)
            },
        )

        val finalSnapshot = stateLock.withLock { snapshot(loading = false) }
        // 有章节没读到就不缓存：部分数据不能在二次进入时冒充完整结果。
        if (finalSnapshot.groups.none { group -> group.children.any { it.error != null } }) {
            cache.put(cacheKey, finalSnapshot.groups)
        }
        emit(onUpdate, finalSnapshot)
        Log.i(
            TAG,
            "loaded course=${base.name} chapters=${finalSnapshot.total} " +
                "failed=${finalSnapshot.groups.sumOf { g -> g.children.count { it.error != null } }} " +
                "ms=${System.currentTimeMillis() - startedAt}",
        )
        finalSnapshot.groups
    }

    private suspend fun emit(
        onUpdate: (CxCourseSnapshot) -> Unit,
        snapshot: CxCourseSnapshot,
    ) = withContext(Dispatchers.Main) { onUpdate(snapshot) }

    private companion object {
        const val TAG = "CxCourseBrowser"
    }
}