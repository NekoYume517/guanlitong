package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程列表 HTML 解析单测。
 *
 * 样本取自学习通课程列表页的真实结构（`li.course` + `course-name` + `p.line2`），
 * 额外覆盖「缺教师 / 缺封面」「缺关键 id」两种上游可能出现的残缺数据。
 */
class CxCourseParserTest {

    @Test
    fun parsesCourseFieldsInOrder() {
        val html = """
            <ul class="course-list">
              <li class="course clearfix" courseId="1001" clazzId="2002" personId="3003">
                <a href="https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=1001&amp;clazzid=2002" target="_blank">
                  <img src="https://p.ananas.chaoxing.com/star3/cover-a.jpg" />
                  <span class="course-name clearfix" title="高等数学">高等数学</span>
                  <p class="line2" title="张三">张三</p>
                </a>
              </li>
              <li class="course" courseId="1004" clazzId="2005" personId="3006">
                <a href="https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=1004" target="_blank">
                  <img src="https://p.ananas.chaoxing.com/star3/cover-b.jpg" />
                  <span class="course-name" title="大学英语">大学英语</span>
                  <p class="line2" title="李四">李四</p>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val courses = CxCourseParser.parse(html)

        assertEquals(2, courses.size)
        assertEquals("1001", courses[0].courseId)
        assertEquals("2002", courses[0].clazzId)
        assertEquals("3003", courses[0].personId)
        assertEquals("高等数学", courses[0].name)
        assertEquals("张三", courses[0].teacher)
        assertEquals("https://p.ananas.chaoxing.com/star3/cover-a.jpg", courses[0].coverUrl)
        assertEquals(
            "https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=1001&clazzid=2002",
            courses[0].link,
        )

        assertEquals("大学英语", courses[1].name)
        assertEquals("李四", courses[1].teacher)
    }

    @Test
    fun missingTeacherAndCoverFallBackPerBlock() {
        val html = """
            <li class="course" courseId="7" clazzId="8" personId="9">
              <span class="course-name" title="缺字段课程">缺字段课程</span>
            </li>
        """.trimIndent()

        val course = CxCourseParser.parse(html).single()

        assertEquals("缺字段课程", course.name)
        assertEquals("", course.teacher)
        assertEquals("", course.coverUrl)
        assertEquals("", course.link)
    }

    @Test
    fun nestedQuitCourseLiBeforeNameDoesNotTruncateBlock() {
        // 真实课程块里，hanlde-list 的「退课」li 出现在课程名之前。
        val html = """
            <li class="course clearfix" courseId="260052592" clazzId="144152660" personId="486368853">
              <div class="course-cover">
                <img src="https://p.ananas.chaoxing.com/star4/cover.jpg" />
                <ul class="hanlde-list">
                  <li class="move-to movetobtn">移动</li>
                  <li class="quit-course quitCourseBtn">退课</li>
                </ul>
              </div>
              <div class="course-info">
                <span class="course-name overHidden2" title="高等数学">高等数学</span>
                <p class="line2" title="王平">王平</p>
              </div>
            </li>
        """.trimIndent()

        val course = CxCourseParser.parse(html).single()

        assertEquals("260052592", course.courseId)
        assertEquals("144152660", course.clazzId)
        assertEquals("486368853", course.personId)
        assertEquals("高等数学", course.name)
        assertEquals("王平", course.teacher)
        assertEquals("https://p.ananas.chaoxing.com/star4/cover.jpg", course.coverUrl)
    }

    @Test
    fun nestedQuitCourseLiAfterNameStillParsesLaterFields() {
        // 名字在前、教师与封面在后时，也不能在「退课」li 处把块截断。
        val html = """
            <li class="course" courseId="38" clazzId="39" personId="40">
              <span class="course-name" title="大学物理">大学物理</span>
              <ul class="hanlde-list">
                <li class="quit-course quitCourseBtn">退课</li>
              </ul>
              <img src="https://p.ananas.chaoxing.com/star4/physics.jpg" />
              <p class="line2" title="李四">李四</p>
            </li>
        """.trimIndent()

        val course = CxCourseParser.parse(html).single()

        assertEquals("大学物理", course.name)
        assertEquals("李四", course.teacher)
        assertEquals("https://p.ananas.chaoxing.com/star4/physics.jpg", course.coverUrl)
    }

    @Test
    fun missingNameUsesPlaceholder() {
        val html = """
            <li class="course" courseId="11" clazzId="12" personId="13">
              <p class="line2" title="王五">王五</p>
            </li>
        """.trimIndent()

        assertEquals("未命名课程", CxCourseParser.parse(html).single().name)
    }

    @Test
    fun itemWithoutIdsIsSkipped() {
        val html = """
            <li class="course" courseId="21" clazzId="22" personId="23">
              <span class="course-name" title="正常课程">正常课程</span>
            </li>
            <li class="course" courseId="24" clazzId="25">
              <span class="course-name" title="缺 personId">缺 personId</span>
            </li>
        """.trimIndent()

        val courses = CxCourseParser.parse(html)

        assertEquals(1, courses.size)
        assertEquals("正常课程", courses.single().name)
    }

    @Test
    fun blankOrUnrelatedHtmlYieldsEmptyList() {
        assertTrue(CxCourseParser.parse("").isEmpty())
        assertTrue(CxCourseParser.parse("<html><body>登录</body></html>").isEmpty())
    }

    @Test
    fun phoneMaskKeepsHeadAndTail() {
        assertEquals("138****5678", maskPhone("13812345678"))
        assertEquals("****", maskPhone("1234"))
    }
}