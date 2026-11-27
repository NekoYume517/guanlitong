package com.sdmu.guanlitong.data.cx

import java.security.MessageDigest

/**
 * 超星视频上报的 enc 与 clipTime。
 *
 * 对照实现：D:\code\chaoxing-fenbushi\server\app\cx_api.py 的 `_calc_video_enc`，
 * 以及逆向出的官方 videojs-ext：
 * `MD5([clazzId][userid][jobid][objectId][playingTimeMS][d_yHJ!$pdA~5][durationMS][clipTime])`
 */
object CxVideoEnc {

    /** 官方播放器硬编码的盐值，逐字节一致。 */
    const val SALT = "d_yHJ!\$pdA~5"

    /** clipTime = `{begins||0}_{ends||duration}`；ends 为 0 时用总时长兜底。 */
    fun clipTime(begins: Int, ends: Int, duration: Int): String =
        "${begins}_${if (ends != 0) ends else duration}"

    /**
     * 计算上报 enc。
     * @param playingTimeMs 当前播放位置（毫秒）
     * @param durationMs    视频总时长（毫秒）
     */
    fun enc(
        clazzId: String,
        uid: String,
        jobid: String,
        objectId: String,
        playingTimeMs: Long,
        durationMs: Long,
        clipTime: String,
    ): String {
        val raw = "[$clazzId][$uid][$jobid][$objectId][$playingTimeMs]" +
            "[$SALT][$durationMs][$clipTime]"
        return md5Hex(raw)
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}