package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮转限课：每轮最多 4 门，连续几轮覆盖全部课程；最近有开放活动的课程每轮必扫。
 */
class CxSignCourseRotationTest {

    private val courses = (1..13).map {
        CxCourse("c$it", "k$it", "p1", "课程$it", "", "", "")
    }

    private fun CxSignCourseRotation.ids(account: String = "acct-a"): List<String> =
        select(account, courses).map { it.courseId }

    @Test
    fun scansAtMostFourPerRound() {
        val rotation = CxSignCourseRotation()

        repeat(5) { round ->
            assertEquals("第 ${round + 1} 轮", 4, rotation.ids().size)
        }
    }

    @Test
    fun rotationCoversEveryCourse() {
        val rotation = CxSignCourseRotation()
        val seen = mutableSetOf<String>()

        repeat(4) { seen += rotation.ids() }

        assertEquals("13 门课 4 轮内必须全部覆盖", courses.map { it.courseId }.toSet(), seen)
    }

    @Test
    fun rotationOrderIsDeterministic() {
        val rotation = CxSignCourseRotation()

        assertEquals(listOf("c1", "c2", "c3", "c4"), rotation.ids())
        assertEquals(listOf("c5", "c6", "c7", "c8"), rotation.ids())
        assertEquals(listOf("c9", "c10", "c11", "c12"), rotation.ids())
        assertEquals(listOf("c13", "c1", "c2", "c3"), rotation.ids())
    }

    @Test
    fun openCourseIsScannedEveryRound() {
        val rotation = CxSignCourseRotation()
        rotation.recordRound("acct-a", mapOf("c9" to true))

        repeat(4) {
            val scanned = rotation.ids()
            assertTrue("有开放活动的课程每轮必扫", "c9" in scanned)
            assertEquals("优先课程排在本轮最前", "c9", scanned.first())
        }
    }

    @Test
    fun openFlagClearsWhenCourseHasNoOpenActivity() {
        val rotation = CxSignCourseRotation()
        rotation.recordRound("acct-a", mapOf("c9" to true))
        assertEquals(setOf("c9"), rotation.openCourseIds("acct-a"))

        // 下一轮真的扫到 c9 且没有开放活动：撤销优先。
        rotation.recordRound("acct-a", mapOf("c9" to false))

        assertTrue(rotation.openCourseIds("acct-a").isEmpty())
    }

    @Test
    fun unscannedCourseKeepsItsOpenFlag() {
        val rotation = CxSignCourseRotation()
        rotation.recordRound("acct-a", mapOf("c9" to true))

        // 只扫了别的课，c9 这轮不在窗口里，上一轮的认知要保留。
        rotation.recordRound("acct-a", mapOf("c1" to false))

        assertEquals(setOf("c9"), rotation.openCourseIds("acct-a"))
    }

    @Test
    fun accountsKeepSeparateCursors() {
        val rotation = CxSignCourseRotation()

        rotation.select("acct-a", courses)
        rotation.recordRound("acct-a", mapOf("c2" to true))

        assertEquals("新账号从 c1 开始", "c1", rotation.select("acct-b", courses).first().courseId)
        assertEquals("账号 a 的优先课不影响 b", setOf("c2"), rotation.openCourseIds("acct-a"))
        assertTrue(rotation.openCourseIds("acct-b").isEmpty())
    }

    @Test
    fun emptyCourseListIsSafe() {
        val rotation = CxSignCourseRotation()

        assertTrue(rotation.select("acct-a", emptyList()).isEmpty())
    }
}