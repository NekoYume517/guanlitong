package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料目录 / 参数 / 元信息解析，以及心跳 JSON 与签名（对照老项目 main.py）。 */
class CxDocParserTest {

    @Test
    fun parsesHiddenParams() {
        val html = """
            <input type="hidden" id="enc" value="E1">
            <input id="cpi" value="C1" type="hidden">
            <input value="O1" id="openc">
        """.trimIndent()
        assertEquals(CxDocParams("E1", "C1", "O1"), CxDocParser.parseParams(html))
    }

    @Test
    fun parsesFolderAndFileEntries() {
        val html = """
            <input id="enc" value="ENC2" type="hidden">
            <ul class="dataBody_td" dataName="%E8%B5%84%E6%96%99" type="afolder" id="11" objectId="">
              <li class="dataBody_size_stu">12 MB</li>
              <li class="dataBody_creater_stu"><span class="name">老师A</span></li>
            </ul>
            <ul class="dataBody_td" dataName="a%2Fb%20c.pdf" type="doc" id="22" objectId="oid22">
              <li class="dataBody_size_stu">1.2 MB</li>
              <li class="dataBody_creater_stu"><span>老师B</span></li>
            </ul>
        """.trimIndent()

        val page = CxDocParser.parseList(html)

        assertEquals("ENC2", page.enc)
        assertEquals(2, page.items.size)
        val folder = page.items[0]
        assertEquals("资料", folder.name)
        assertTrue(folder.isFolder)
        assertEquals("11", folder.id)
        assertEquals("12 MB", folder.size)
        assertEquals("老师A", folder.creator)
        val file = page.items[1]
        assertEquals("a/b c.pdf", file.name)
        assertEquals("doc", file.type)
        assertEquals("oid22", file.objectId)
        assertEquals("1.2 MB", file.size)
        assertEquals("老师B", file.creator)
    }

    @Test
    fun emptyListIsNotAnError() {
        val page = CxDocParser.parseList("<html><body>这个文件夹是空的</body></html>")
        assertTrue(page.items.isEmpty())
        assertEquals("", page.enc)
    }

    @Test
    fun parsesMetaWithStringAndNumberFields() {
        val meta = CxDocParser.parseMeta(
            """{"objectid":"oid1","pagenum":"12","wordcount":34,"filename":"讲义.pdf"}""",
        )
        assertEquals(CxDocMeta("oid1", 12, 34, "讲义.pdf"), meta)
    }

    @Test
    fun invalidMetaIsNull() {
        assertNull(CxDocParser.parseMeta("not json"))
        assertNull(CxDocParser.parseMeta("<html>login</html>"))
    }

    @Test
    fun heartbeatJsonMatchesOldOrderAndEscaping() {
        val beat = CxDocBeat(
            courseId = "100",
            clazzId = "200",
            dataId = "300",
            objectId = "400",
            wordCount = 500,
            pageNum = 600,
            state = 1,
            height = 0,
            timestampMs = 1_700_000_000_000,
            fileEnc = "abc",
        )
        val expected = "{\"r\":\"400\",\"t\":\"doc\",\"l\":1,\"f\":4,\"p\":1,\"tp\":1," +
            "\"wc\":500,\"ic\":600,\"v\":2,\"s\":1,\"h\":0," +
            "\"ext\":\"{\\\"_from_\\\":\\\"100_200_12345_abc\\\"}\"}"
        assertEquals(expected, CxDocParser.heartbeatJson(beat, uid = "12345"))
    }

    @Test
    fun heartbeatExtFallsBackToDataId() {
        val beat = CxDocBeat("1", "2", "333", "oid", 10, 20, 2, 30, 40, fileEnc = "")
        assertTrue(
            CxDocParser.heartbeatJson(beat, uid = "9")
                .contains("_from_\\\":\\\"1_2_9_333"),
        )
    }

    @Test
    fun signatureMatchesReferenceImplementation() {
        val beat = CxDocBeat("100", "200", "300", "400", 500, 600, 1, 0, 1_700_000_000_000, "abc")
        val json = CxDocParser.heartbeatJson(beat, uid = "12345")
        assertEquals(
            "16a08307f76cd114ace6c6ccda8bb3cd",
            CxDocParser.signHeartbeat(json, 1_700_000_000_000, "12345"),
        )
    }

    @Test
    fun percentEncodingMatchesPythonQuote() {
        assertEquals("a%20b", CxDocParser.percentEncode("a b"))
        assertEquals("a%2Fb", CxDocParser.percentEncode("a/b"))
        assertEquals("a/b", CxDocParser.percentEncode("a/b", allowSlash = true))
        assertEquals(
            "%E4%B8%AD%E6%96%87%20%E8%B5%84%E6%96%99/a%20b",
            CxDocParser.percentEncode("中文 资料/a b", allowSlash = true),
        )
        assertEquals("A-Z_a.z~0", CxDocParser.percentEncode("A-Z_a.z~0"))
    }
}