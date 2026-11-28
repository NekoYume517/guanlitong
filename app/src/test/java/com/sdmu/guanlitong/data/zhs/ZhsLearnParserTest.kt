package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节树与进度合并：两级/三级课都要正确识别，进度按 id 并回章节。
 */
class ZhsLearnParserTest {

    private val chaptersBody = """
        {"chapterOrLesson":{"clList":[
          {"chapterId":11,"chapterName":"第一章 绪论","lessonTempDtos":[
            {"lessonId":101,"lessonName":"1.1 三级节","lessonVideoTempDtos":[
              {"lessonVideoName":"视频 A","lessonVideoId":1001,"lessonVideoSec":300,"watchStatus":1},
              {"lessonVideoName":"视频 B","lessonVideoId":1002,"lessonVideoSec":600,"watchStatus":0}
            ]},
            {"lessonId":102,"lessonName":"1.2 两级视频","videoId":555,"lessonSec":900,"watchStatus":0},
            {"lessonId":103,"lessonName":"1.3 讨论","videoId":0}
          ]},
          {"chapterId":12,"chapterName":"第二章","lessonTempDtos":[]}
        ]}}
    """.trimIndent()

    private val progressBody = """
        {"data":{
          "lesson":{"102":{"studyTotalTime":120,"watchState":1}},
          "lv":{"1001":{"studyTotalTime":280,"watchState":1},
                "1002":{"studyTotalTime":100,"watchState":0}}
        }}
    """.trimIndent()

    @Test
    fun parsesTwoAndThreeLevelLessons() {
        val chapters = ZhsLearnParser.parseChapters(chaptersBody)

        assertEquals(2, chapters.size)
        assertEquals("第一章 绪论", chapters[0].name)
        assertEquals(11L, chapters[0].id)
        assertTrue(chapters[1].lessons.isEmpty())

        val lessons = chapters[0].lessons
        assertEquals(3, lessons.size)

        val three = lessons[0]
        assertEquals(3, three.depth)
        assertEquals(101L, three.lessonId)
        assertEquals(2, three.videos.size)
        assertEquals("视频 A", three.videos[0].name)
        assertTrue(three.videos[0].watched)
        assertEquals(600, three.videos[1].sec)
        assertFalse(three.videos[1].watched)

        val two = lessons[1]
        assertEquals(2, two.depth)
        assertEquals(555L, two.videoId)
        assertEquals(900, two.sec)
        assertFalse(two.noVideo)

        val text = lessons[2]
        assertTrue(text.noVideo)
        assertEquals(103L, text.lessonId)
    }

    @Test
    fun mergesProgressIntoLessonsAndVideos() {
        val merged = ZhsLearnParser.merge(
            ZhsLearnParser.parseChapters(chaptersBody),
            ZhsLearnParser.parseProgress(progressBody),
        )

        val lessons = merged[0].lessons
        assertEquals(280, lessons[0].videos[0].studyTime)
        assertTrue(lessons[0].videos[0].watched)
        assertEquals(100, lessons[0].videos[1].studyTime)
        assertFalse(lessons[0].videos[1].watched)
        assertEquals(120, lessons[1].studyTime)
        assertTrue(lessons[1].watched)
    }

    @Test
    fun mergeClampsStudyTimeToVideoLength() {
        val progress = ZhsLearnParser.Progress(
            lessons = mapOf(102L to ZhsLearnParser.ProgressItem(studyTotalTime = 5000, watchState = 1)),
        )
        val merged = ZhsLearnParser.merge(ZhsLearnParser.parseChapters(chaptersBody), progress)
        assertEquals(900, merged[0].lessons[1].studyTime)
    }

    @Test
    fun summaryCountsVideosOnly() {
        val merged = ZhsLearnParser.merge(
            ZhsLearnParser.parseChapters(chaptersBody),
            ZhsLearnParser.parseProgress(progressBody),
        )
        val summary = ZhsLearnParser.summary(merged)

        assertEquals(3, summary.totalVideos)
        assertEquals(2, summary.watchedVideos)
        assertEquals(1800, summary.totalSec)
        assertEquals(500, summary.watchedSec)
        assertEquals(27, summary.percent)
    }

    @Test
    fun missingProgressDataMergesNothing() {
        val merged = ZhsLearnParser.merge(
            ZhsLearnParser.parseChapters(chaptersBody),
            ZhsLearnParser.parseProgress("{\"data\":{}}"),
        )
        assertEquals(0, merged[0].lessons[1].studyTime)
        assertEquals(0, merged[0].lessons[0].videos[0].studyTime)
        assertTrue(merged[0].lessons[0].videos[0].watched)
    }

    @Test
    fun missingChapterObjectIsEmptyList() {
        assertEquals(0, ZhsLearnParser.parseChapters("{\"msg\":\"ok\"}").size)
    }

    @Test
    fun errorMessageWithoutChapterObjectIsThrown() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLearnParser.parseChapters("{\"msg\":\"没有权限\"}")
        }
        assertEquals("没有权限", error.message)
        assertEquals(ZhsErrorKind.PARSE, error.kind)
    }

    @Test
    fun invalidJsonThrowsParseError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLearnParser.parseChapters("<html>login</html>")
        }
        assertEquals(ZhsErrorKind.PARSE, error.kind)
        assertThrows(ZhsException::class.java) { ZhsLearnParser.parseProgress("not json") }
    }

    @Test
    fun idsAreDistinctAndPositive() {
        val chapters = ZhsLearnParser.parseChapters(chaptersBody)
        assertEquals(listOf(101L, 102L, 103L), ZhsLearnParser.lessonIds(chapters))
        assertEquals(listOf(1001L, 1002L), ZhsLearnParser.videoIds(chapters))
    }
}