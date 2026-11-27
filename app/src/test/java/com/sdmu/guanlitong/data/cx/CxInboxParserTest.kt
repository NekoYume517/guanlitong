package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收件箱解析：置顶合并、类型映射、已读镜像、attachment 两种形态、时间字段。 */
class CxInboxParserTest {

    @Test
    fun mergesTopNoticesAndDedupesByIdCode() {
        val page = CxInboxParser.parse(CxFixtureData.inboxJson)
        assertEquals(3, page.items.size)
        // 置顶在前，且 idCode 相同的列表项不再重复出现。
        assertEquals("top-1", page.items[0].idCode)
        assertEquals("期中作业", page.items[0].title)
        assertEquals("abc123", page.lastGetId)
        assertTrue(page.lastPage)
    }

    @Test
    fun mapsSourceTypeToInboxType() {
        val page = CxInboxParser.parse(CxFixtureData.inboxJson)
        assertEquals(CxInboxType.WORK, page.items[0].type)
        assertEquals(CxInboxType.EXAM, page.items[1].type)
        assertEquals(CxInboxType.NOTICE, page.items[2].type)
        assertEquals("📝 期中作业", page.items[0].displayTitle)
    }

    @Test
    fun mirrorsRemoteRead() {
        val page = CxInboxParser.parse(CxFixtureData.inboxJson)
        assertFalse(page.items[0].remoteRead) // isread = 0
        assertTrue(page.items[1].remoteRead) // isread = 1
    }

    @Test
    fun readsAttachmentFromJsonString() {
        val top = CxInboxParser.parse(CxFixtureData.inboxJson).items[0]
        // content 为空时用 att_web.content 补。
        assertEquals("提交一份报告", top.content)
        assertEquals("https://mooc1.chaoxing.com/mooc2/work/list?x=1", top.actionUrl)
        assertEquals(1_766_209_200_000L, top.insertTimeMs!!)
    }

    @Test
    fun readsAttachmentFromJsonArrayObject() {
        val page = CxInboxParser.parse(CxFixtureData.inboxJsonWithObjectAttachment)
        val item = page.items.first()
        assertEquals(CxInboxType.CHECKIN, item.type)
        assertEquals("快去签到", item.content)
        assertEquals("https://mobilelearn.chaoxing.com/page/active/show?id=1", item.actionUrl)
    }

    @Test
    fun booleanIsreadIsMirrored() {
        // JSON 布尔 false 不能被 optInt 误判成已读。
        val item = CxInboxParser.parse(CxFixtureData.inboxJsonWithObjectAttachment).items[1]
        assertFalse(item.remoteRead)
    }

    @Test
    fun falsyStatusWithNoListsIsEmptyPageWithWarnSignal() {
        val page = CxInboxParser.parse(CxFixtureData.emptyFalsyInboxJson)
        assertTrue(page.items.isEmpty())
        assertFalse(page.statusOk)
        assertTrue(page.rawSnippet.contains("暂无数据"))
    }

    @Test
    fun parsesInsertTimeStringAndKeepsMissingAsNull() {
        val page = CxInboxParser.parse(CxFixtureData.inboxJson)
        assertTrue(page.items[1].insertTimeMs!! > 0)
        assertNull(page.items[2].insertTimeMs)
    }

    @Test(expected = CxException::class)
    fun failedStatusIsRejected() {
        CxInboxParser.parse(CxFixtureData.failedInboxJson)
    }

    @Test(expected = CxException::class)
    fun malformedBodyIsRejected() {
        CxInboxParser.parse(CxFixtureData.malformedJson)
    }
}