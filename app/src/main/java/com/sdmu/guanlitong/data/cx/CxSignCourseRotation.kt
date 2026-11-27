package com.sdmu.guanlitong.data.cx

/**
 * 签到轮询的轮转限课：每轮最多扫 [maxPerRound] 门课，跨轮次把 13 门课轮完。
 *
 * 学习通 13 门课全扫一轮就是 13 次 activelist；固定整轮扫描是风控最敏感的特征。
 * 这里用「每轮 4 门 + 轮转指针」把单轮请求压到 4 次，同时保证：
 * - 最近扫到开放活动的课程每轮必扫（优先保证签到时效）；
 * - 其余课程按游标顺序覆盖，ceil(n/max) 轮内每门至少被扫到一次。
 *
 * 状态必须跨 [CxSignRunner] 实例保留（服务循环每轮都会 new 一个 runner），
 * 因此按账号指纹存在进程级单例 [shared] 里；进程重启后从指针 0 重新轮转。
 */
class CxSignCourseRotation(
    private val maxPerRound: Int = CX_SIGN_SCAN_COURSES_PER_ROUND,
) {

    /** 每个账号的下一轮起始位置。 */
    private val cursors = mutableMapOf<String, Int>()

    /** 每个账号「上次扫到时有开放活动」的课程 id，只有重新扫过才会更新。 */
    private val openCourses = mutableMapOf<String, MutableSet<String>>()

    /**
     * 选出本轮要扫的课程：先放最近有开放活动的课程（按游标顺序），
     * 剩余名额再从游标开始顺序补齐。返回顺序即扫描顺序。
     */
    @Synchronized
    fun select(accountKey: String, courses: List<CxCourse>): List<CxCourse> {
        if (courses.isEmpty() || maxPerRound <= 0) return emptyList()
        val n = courses.size
        val cursor = cursors[accountKey]?.mod(n) ?: 0
        val open = openCourses[accountKey].orEmpty()

        val picked = LinkedHashMap<String, CxCourse>()
        // 1) 有开放活动的课程每轮必扫，最多 maxPerRound 门。
        for (i in 0 until n) {
            if (picked.size >= maxPerRound) break
            val course = courses[(cursor + i) % n]
            if (course.courseId in open) picked[course.courseId] = course
        }
        // 2) 剩余名额按轮转窗口补齐；窗口每轮前进 maxPerRound。
        for (i in 0 until n) {
            if (picked.size >= maxPerRound) break
            val course = courses[(cursor + i) % n]
            picked.putIfAbsent(course.courseId, course)
        }

        cursors[accountKey] = (cursor + maxPerRound) % n
        return picked.values.toList()
    }

    /**
     * 记录本轮每门课是否出现开放活动；只更新真的扫过的课程，
     * 没扫到的课程保留上次的认知，下一轮仍会进入优先集合。
     */
    @Synchronized
    fun recordRound(accountKey: String, openByCourse: Map<String, Boolean>) {
        if (openByCourse.isEmpty()) return
        val set = openCourses.getOrPut(accountKey) { mutableSetOf() }
        openByCourse.forEach { (courseId, hasOpen) ->
            if (hasOpen) set += courseId else set -= courseId
        }
        if (set.isEmpty()) openCourses.remove(accountKey)
    }

    /** 只读快照，供日志与单测检查。 */
    @Synchronized
    fun openCourseIds(accountKey: String): Set<String> =
        openCourses[accountKey].orEmpty().toSet()

    companion object {
        /** 进程级共享实例：让轮转指针跨 runner 实例延续。 */
        val shared = CxSignCourseRotation()
    }
}