package com.sdmu.guanlitong.data.cx

/**
 * `multimedia/log` 上报参数拼接，顺序与官方 videojs-ext 逐项一致。
 *
 * 两个不能动的点：
 * - `otherInfo` 必须**裸拼接**，它尾部自带 `&courseId=...`，服务端靠它解析课程；
 * - `attDuration` / `attDurationEnc` 只对 Video 且服务端签发值有效时才带，
 *   Audio 一律不带。
 */
object CxStudyParams {

    // 官方 isdrag 语义（videojs-ext sendLog 事件映射）
    const val ISDRAG_PLAYING = 0
    const val ISDRAG_DRAG = 1
    const val ISDRAG_PAUSE = 2
    const val ISDRAG_PLAY = 3
    const val ISDRAG_ENDED = 4

    fun query(
        clazzId: String,
        playingTime: Int,
        duration: Int,
        clipTime: String,
        objectId: String,
        otherInfo: String,
        jobid: String,
        userId: String,
        isDrag: Int,
        enc: String,
        dtype: String,
        attDuration: Int?,
        attDurationEnc: String?,
        timestampMs: Long,
    ): String {
        val sb = StringBuilder(384)
        fun add(key: String, value: Any) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(value)
        }

        add("clazzId", clazzId)
        add("playingTime", playingTime)
        add("duration", duration)
        add("clipTime", clipTime)
        add("objectId", objectId)
        add("otherInfo", otherInfo) // 裸拼接，不编码
        add("jobid", jobid)
        add("userid", userId)
        add("isdrag", isDrag)
        add("view", "pc")
        add("enc", enc)
        add("rt", "0.9")

        val isVideo = dtype == "Video"
        if (isVideo) {
            add("videoFaceCaptureEnc", "")
            add("dtype", "Video")
        } else {
            add("dtype", "Audio")
        }
        add("_t", timestampMs)

        if (isVideo && attDuration != null && attDuration > 0 && !attDurationEnc.isNullOrEmpty()) {
            add("attDuration", attDuration)
            add("attDurationEnc", attDurationEnc)
        }
        add("courseEngineInfo", "false")
        return sb.toString()
    }

    fun url(reportUrl: String, dtoken: String, query: String): String =
        "${reportUrl.trimEnd('/')}/$dtoken?$query"
}