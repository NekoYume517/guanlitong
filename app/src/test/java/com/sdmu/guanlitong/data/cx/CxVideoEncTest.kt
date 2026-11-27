package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * enc 测试向量由老项目 `_calc_video_enc` 同式独立算出（Python hashlib.md5），
 * 用于锁定 Kotlin 实现逐字节一致。
 */
class CxVideoEncTest {

    @Test
    fun encMatchesLegacyVectors() {
        assertEquals(
            "6898eaeec631093d3e03c3c374493527",
            CxVideoEnc.enc(
                clazzId = "130748384",
                uid = "405539101",
                jobid = "jobid_1001",
                objectId = "object_abc",
                playingTimeMs = 120_000,
                durationMs = 313_000,
                clipTime = "0_313",
            ),
        )
        assertEquals(
            "81a5ea4a732bc4313eef7f50f174cfa2",
            CxVideoEnc.enc(
                clazzId = "456",
                uid = "789",
                jobid = "jobid_audio",
                objectId = "obj_audio",
                playingTimeMs = 60_000,
                durationMs = 120_000,
                clipTime = "0_120",
            ),
        )
        assertEquals(
            "ff53767f2deeb0882b2280d4154b9190",
            CxVideoEnc.enc(
                clazzId = "456",
                uid = "789",
                jobid = "jobid_1001",
                objectId = "object_abc",
                playingTimeMs = 313_000,
                durationMs = 313_000,
                clipTime = "0_313",
            ),
        )
    }

    @Test
    fun clipTimeFallsBackToDurationWhenEndsMissing() {
        assertEquals("0_313", CxVideoEnc.clipTime(begins = 0, ends = 0, duration = 313))
        assertEquals("0_313", CxVideoEnc.clipTime(begins = 0, ends = 313, duration = 313))
        assertEquals("10_500", CxVideoEnc.clipTime(begins = 10, ends = 500, duration = 999))
    }

    @Test
    fun videoQueryKeepsOrderAndRawOtherInfo() {
        val query = CxStudyParams.query(
            clazzId = "130748384",
            playingTime = 120,
            duration = 313,
            clipTime = "0_313",
            objectId = "object_abc",
            otherInfo = "&courseId=123&clazzid=456",
            jobid = "jobid_1001",
            userId = "405539101",
            isDrag = CxStudyParams.ISDRAG_PLAYING,
            enc = "abc",
            dtype = "Video",
            attDuration = 313,
            attDurationEnc = "att_enc",
            timestampMs = 1_700_000_000_000,
        )

        assertEquals(
            "clazzId=130748384&playingTime=120&duration=313&clipTime=0_313" +
                "&objectId=object_abc&otherInfo=&courseId=123&clazzid=456" +
                "&jobid=jobid_1001&userid=405539101&isdrag=0&view=pc&enc=abc&rt=0.9" +
                "&videoFaceCaptureEnc=&dtype=Video&_t=1700000000000" +
                "&attDuration=313&attDurationEnc=att_enc&courseEngineInfo=false",
            query,
        )
        // otherInfo 自带的分隔符必须原样保留，不能被编码成 %26 / %3D。
        assertTrue(query.contains("otherInfo=&courseId=123&clazzid=456"))
        assertFalse(query.contains("%26"))
    }

    @Test
    fun audioQueryOmitsVideoOnlyFields() {
        val query = CxStudyParams.query(
            clazzId = "456",
            playingTime = 60,
            duration = 120,
            clipTime = "0_120",
            objectId = "obj_audio",
            otherInfo = "",
            jobid = "jobid_audio",
            userId = "789",
            isDrag = CxStudyParams.ISDRAG_ENDED,
            enc = "abc",
            dtype = "Audio",
            attDuration = 120,
            attDurationEnc = "ignored",
            timestampMs = 1,
        )

        assertTrue(query.contains("&dtype=Audio&_t=1&courseEngineInfo=false"))
        assertFalse(query.contains("videoFaceCaptureEnc"))
        assertFalse(query.contains("attDuration"))
    }

    @Test
    fun videoQueryOmitsInvalidAttDuration() {
        listOf(null, 0).forEach { invalid ->
            val query = CxStudyParams.query(
                clazzId = "1",
                playingTime = 0,
                duration = 60,
                clipTime = "0_60",
                objectId = "o",
                otherInfo = "",
                jobid = "j",
                userId = "u",
                isDrag = CxStudyParams.ISDRAG_PLAY,
                enc = "e",
                dtype = "Video",
                attDuration = invalid,
                attDurationEnc = "enc",
                timestampMs = 1,
            )
            assertFalse("attDuration=$invalid 不应上报", query.contains("attDuration"))
        }
    }

    @Test
    fun logUrlJoinsReportUrlAndDtoken() {
        assertEquals(
            "https://mooc1-1.chaoxing.com/mooc-ans/multimedia/log/a/789/dtoken_x?clazzId=1",
            CxStudyParams.url(
                "https://mooc1-1.chaoxing.com/mooc-ans/multimedia/log/a/789/",
                "dtoken_x",
                "clazzId=1",
            ),
        )
    }
}