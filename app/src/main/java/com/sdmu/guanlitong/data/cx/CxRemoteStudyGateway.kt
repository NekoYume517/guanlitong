package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.delay

/**
 * 刷课执行器的真实实现：所有请求都经 [CxRepository.withSession]，401 由 Repository
 * 单飞重登；这里只负责「扫描课程」与「刷单个任务点」。
 *
 * 每个任务点独立走一次 withSession，避免长事务内会话过期后整段重来。
 * 同一会话的 [CxStudyApi] 会被缓存，保留 setlog encode 缓存与挂起队列。
 *
 * 扫描时单章网络重试耗尽（[CX_CODE_NETWORK]）不中断其余章节，而是记入
 * [CxScanResult.missedChapters]——这一章可能是空的，也可能是恰好藏着未通过视频，
 * 因此该课程此后按「部分失败」处理，绝不报完成。403/401 仍然上抛。
 */
class CxRemoteStudyGateway(private val repo: CxRepository) : CxStudyGateway {

    private var cachedCookieKey: Int = 0
    private var cachedStudy: CxStudyApi? = null

    override suspend fun scan(course: CxCourse): CxScanResult =
        repo.withSession { session -> scan(course, studyFor(session)) }

    override suspend fun brush(
        task: CxStudyTask,
        onProgress: (CxStudyProgress) -> Unit,
    ): CxStudyOutcome = repo.withSession { session ->
        CxStudyRunner(studyFor(session), onProgress = onProgress).brush(task)
    }

    override suspend fun relogin() {
        repo.refreshSession()
    }

    private suspend fun scan(base: CxCourse, study: CxStudyApi): CxScanResult {
        val detail = study.courseDetail(base.courseId, base.clazzId, base.personId)
        val course = CxStudyCourse(
            courseId = base.courseId,
            clazzId = base.clazzId,
            personId = base.personId,
            name = base.name,
            cpi = detail.cpi.ifBlank { base.personId },
            enc = detail.enc,
        )
        val tree = study.chapterTree(course.courseId, course.clazzId, course.cpi, course.enc)
        val chapters = tree.flatMap { it.children }
        Log.i(
            TAG,
            "scan course=${base.name} groups=${tree.size} chapters=${chapters.size} " +
                "cpi=${course.cpi} encLen=${course.enc.length} fidLen=${detail.fid.length}",
        )

        val tasks = mutableListOf<CxStudyTask>()
        val missed = mutableListOf<String>()
        val typeCounts = mutableMapOf<String, Int>()
        for (chapter in chapters) {
            try {
                val page = study.chapterCards(
                    courseId = course.courseId,
                    clazzId = course.clazzId,
                    cpi = course.cpi,
                    chapterId = chapter.chapterId,
                )
                page.cards.forEach { typeCounts[it.type] = (typeCounts[it.type] ?: 0) + 1 }
                page.cards
                    .pendingBrushableTasks()
                    .forEach { tasks += CxStudyTask(course, chapter.chapterId, chapter.name, it) }
            } catch (e: CxException) {
                if (e.code != CX_CODE_NETWORK) throw e
                missed += chapter.name
                Log.w(TAG, "chapter missing chapter=${chapter.name} err=${e.message}")
            }
            delay(CHAPTER_GAP_MS)
        }

        val video = tasks.count { it.card.type == "video" }
        val audio = tasks.count { it.card.type == "audio" }
        Log.i(
            TAG,
            "scan done course=${base.name} tasks=${tasks.size} video=$video audio=$audio " +
                "missed=${missed.size} types=${typeCounts.toSortedMap()}",
        )
        return CxScanResult(tasks, missed)
    }

    private fun studyFor(session: CxSession): CxStudyApi {
        val key = session.cookies.hashCode()
        cachedStudy?.takeIf { cachedCookieKey == key }?.let { return it }
        val uid = session.uid.ifBlank { session.cookies["UID"].orEmpty() }
        return CxStudyApi(CxApi(session.cookies), uid, session.fid).also {
            cachedCookieKey = key
            cachedStudy = it
        }
    }

    private companion object {
        const val TAG = "CxStudyGateway"
        const val CHAPTER_GAP_MS = 500L
    }
}