package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节树 / 任务点解析单测。
 *
 * 样本结构对照老项目 `cx_api.py` 实际抓取的 `studentcourse` 与 `knowledge/cards`：
 * 父章节 `<span title>`、子节点 `onclick="toOld(...)"`、任务点 `mArg = {...}`。
 */
class CxChapterParserTest {

    @Test
    fun parsesParentChaptersAndChildren() {
        val html = """
            <div class="chapter_unit">
              <div class="chapter_item">
                <span title="第一章 函数与极限">第一章 函数与极限</span>
              </div>
              <div class="chapter_item" id="cur104" onclick="toOld('123', '104', '456', '0')" title="1.1 映射与函数">
                <a>1.1 映射与函数</a>
              </div>
              <div class="chapter_item" id="cur105" title="1.2 数列的极限" onclick="toOld('123', '105', '456', '0')">
                <a>1.2 数列的极限</a>
              </div>
            </div>
            <div class="chapter_unit">
              <div class="chapter_item"><span title="第二章 导数与微分">第二章 导数与微分</span></div>
              <div class="chapter_item" onclick="toOld('123', '201', '456', '0')" title="2.1 导数概念"></div>
            </div>
        """.trimIndent()

        val tree = CxChapterParser.parseTree(html)

        assertEquals(2, tree.size)
        assertEquals("第一章 函数与极限", tree[0].name)
        assertEquals(listOf("104", "105"), tree[0].children.map { it.chapterId })
        assertEquals(listOf("1.1 映射与函数", "1.2 数列的极限"), tree[0].children.map { it.name })
        assertEquals("第二章 导数与微分", tree[1].name)
        assertEquals("201", tree[1].children.single().chapterId)
    }

    @Test
    fun chapterUnitWithExtraAttributesStillParses() {
        val html = """
            <div class="chapter_unit" data-index="1">
              <span title="第三章 积分"></span>
              <div class="chapter_item" onclick="toOld('1', '301', '2', '0')" title="3.1 不定积分"></div>
            </div>
            <div class='chapter_unit'>
              <span title="第四章 级数"></span>
            </div>
        """.trimIndent()

        val tree = CxChapterParser.parseTree(html)

        assertEquals(2, tree.size)
        assertEquals("第三章 积分", tree[0].name)
        assertEquals("301", tree[0].children.single().chapterId)
        assertEquals("第四章 级数", tree[1].name)
    }

    @Test
    fun cardPageParsesAttachmentsAndReportUrl() {
        val html = """
            <script>
                var _from = '123_456_789_0123456789abcdef0123456789abcdef';
                  if (true) {
                   mArg = {"attachments":[{"type":"video","jobid":"jobid_1001","mid":"mid_1","property":{"objectid":"object_abc","name":"1.1 映射与函数","duration":313},"otherInfo":"&courseId=123&clazzid=456","isPassed":false,"attDuration":313,"attDurationEnc":"att_enc_value","begins":0,"ends":0}],"defaults":{"reportUrl":"https://mooc1-1.chaoxing.com/mooc-ans/multimedia/log/a/789"}};
                }catch(e){}
            </script>
        """.trimIndent()

        val page = CxChapterParser.parseCards(html, chapterId = "104")

        assertEquals("0123456789abcdef0123456789abcdef", page.studyEnc)
        val card = page.cards.single()
        assertEquals("104", card.chapterId)
        assertEquals("jobid_1001", card.jobid)
        assertEquals("object_abc", card.objectId)
        assertEquals("mid_1", card.mid)
        assertEquals("video", card.type)
        assertEquals("1.1 映射与函数", card.name)
        assertFalse(card.isPassed)
        assertEquals(313, card.duration)
        assertEquals(313, card.attDuration)
        assertEquals("att_enc_value", card.attDurationEnc)
        assertEquals(0, card.begins)
        assertEquals(0, card.ends)
        assertEquals("&courseId=123&clazzid=456", card.otherInfo)
        assertEquals(
            "https://mooc1-1.chaoxing.com/mooc-ans/multimedia/log/a/789",
            card.reportUrl,
        )
    }

    @Test
    fun passedCardAndAudioWithoutAttDuration() {
        val html = """
            <script>
              mArg = {"attachments":[{"type":"Audio","jobid":"jobid_audio","mid":"mid_2","property":{"objectid":"obj_audio","name":"听力","duration":"120"},"otherInfo":"","isPassed":true}],"defaults":{"reportUrl":"https://mooc1-1.chaoxing.com/log"}};
            </script>
        """.trimIndent()

        val card = CxChapterParser.parseCards(html).cards.single()

        assertTrue(card.isPassed)
        assertEquals("audio", card.type)
        assertEquals(120, card.duration)
        assertNull(card.attDuration)
        assertEquals("", card.attDurationEnc)
    }

    @Test
    fun workCardNamedWorkidFallsBackToTypeLabel() {
        val html = """
            <script>
              mArg = {"attachments":[{"type":"workid","jobid":"jobid_work","mid":"mid_w","property":{"objectid":"obj_work","name":"workid"},"otherInfo":"","isPassed":false}],"defaults":{"reportUrl":""}};
            </script>
        """.trimIndent()

        val card = CxChapterParser.parseCards(html).cards.single()

        assertEquals("workid", card.type)
        assertFalse(card.isBrushable)
        // property.name 是内部标识，不是真标题；标题与类型标签都必须显示「作业」。
        assertEquals("作业", card.displayName)
        assertEquals("作业", cxTypeLabel(card.type))
    }

    @Test
    fun attachmentWithoutJobidIsSkipped() {
        val html = """
            <script>
              mArg = {"attachments":[{"type":"video","property":{"objectid":"x"}}],"defaults":{}};
            </script>
        """.trimIndent()

        assertTrue(CxChapterParser.parseCards(html).cards.isEmpty())
    }

    @Test
    fun emptyPageWithoutMargYieldsNoCards() {
        // 没有 mArg 标记 = 真空页，是合法的「没有任务点」。
        assertTrue(CxChapterParser.parseCards("<html></html>").cards.isEmpty())
        assertNull(CxChapterParser.extractMarg("<html></html>"))
    }

    @Test
    fun brokenMargThrowsInsteadOfReportingEmpty() {
        // 有 mArg 却解析失败，必须冒错，不能当成「本章已完成」。
        val e = assertThrows(CxException::class.java) {
            CxChapterParser.parseCards("mArg = {broken")
        }
        assertEquals(502, e.code)
        assertEquals("章节任务点解析失败", e.message)
    }

    @Test
    fun earlierMargMentionDoesNotStealTheAnchor() {
        // 前面脚本里出现 mArg 字样（声明/注释），真正的赋值语句是带三空格锚点的那行。
        val html = "<script>var mArg = null; // mArg = {\"fake\":1}\n</script>\n" +
            "<script>\n   mArg = {\"attachments\":[{\"type\":\"video\",\"jobid\":\"jobid_x\"," +
            "\"property\":{\"objectid\":\"obj_x\",\"name\":\"真卡片\",\"duration\":60}}]," +
            "\"defaults\":{\"reportUrl\":\"https://mooc1-1.chaoxing.com/log\"}};\n}catch(e){}\n</script>"

        val card = CxChapterParser.parseCards(html).cards.single()

        assertEquals("jobid_x", card.jobid)
        assertEquals("真卡片", card.name)
    }

    @Test
    fun zeroOrNullAttDurationBecomesNull() {
        // 0 / null 不是有效凭证，上报时必须整对省略。
        val html = """
            <script>
               mArg = {"attachments":[
                 {"type":"video","jobid":"j0","property":{"objectid":"o0","duration":60},"attDuration":0,"attDurationEnc":"enc0"},
                 {"type":"video","jobid":"jn","property":{"objectid":"on","duration":60},"attDuration":null,"attDurationEnc":"encn"}
               ],"defaults":{}};
            </script>
        """.trimIndent()

        val cards = CxChapterParser.parseCards(html).cards

        assertNull(cards[0].attDuration)
        assertNull(cards[1].attDuration)
    }

    @Test
    fun blankChapterPageYieldsEmptyTree() {
        assertTrue(CxChapterParser.parseTree("<html></html>").isEmpty())
    }
}