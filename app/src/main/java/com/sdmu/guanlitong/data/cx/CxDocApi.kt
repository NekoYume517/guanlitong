package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资料浏览与刷时长的协议层，逐项对照老项目 main.py：
 * `get_course_detail`（199）、`Browser._fetch_root/_fetch_sub`（225/232）、
 * `meta`（323）、`update_count`（328）、`hb`（334）。
 *
 * 与其它学习通接口一样，这里不做调度切换之外的错误处理：目录/元信息用
 * [CxApi.ensurePageOk] 归一 401/403/502，让 [CxRepository.withSession] 的单飞重登
 * 接上；心跳的 HTTP 状态交回引擎判断。
 */
class CxDocApi(
    private val api: CxApi,
    private val listUrl: String = DEFAULT_LIST_URL,
) {

    /** 心跳 URL 里的 `u` 与签名共用 uid；会话里没有时退回 Cookie。 */
    val uid: String get() = api.cookie("_uid").ifBlank { api.cookie("UID") }

    /** 课程中间页参数；新版页面可能没有 enc，返回空串由调用方退到 [DEFAULT_FILE_ENC]。 */
    suspend fun params(courseId: String, clazzId: String, personId: String): CxDocParams =
        withContext(Dispatchers.IO) {
            val url = "https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle" +
                "?courseid=$courseId&clazzid=$clazzId&vc=1&cpi=$personId&ismooc2=1&v=2"
            val (status, body) = api.getFollowing(url, MOOC_REF)
            api.ensurePageOk(status, body, "获取课程资料参数")
            CxDocParser.parseParams(body)
        }

    /**
     * 目录列表。根目录用 `type=1&enc=...`（老项目 firstPage 格式），子目录再加
     * `dataName / dataId / parent / cpi`；文件夹名按 `quote` 规则编码（保留 `/`）。
     */
    suspend fun list(
        courseId: String,
        clazzId: String,
        cpi: String,
        enc: String,
        folder: CxDocFolder? = null,
    ): CxDocPage = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val effectiveEnc = enc.ifBlank { DEFAULT_FILE_ENC }
        val url = if (folder == null) {
            "$listUrl?courseid=$courseId&clazzid=$clazzId" +
                "&type=1&enc=$effectiveEnc&ut=s&t=$timestamp" +
                "&microTopicId=0&isAjax=isAjax"
        } else {
            "$listUrl?courseid=$courseId" +
                "&dataName=${CxDocParser.percentEncode(folder.name, allowSlash = true)}" +
                "&dataId=${folder.id}&type=1&parent=${folder.parentId}&flag=0" +
                "&clazzid=$clazzId&enc=$effectiveEnc&ut=s&cpi=$cpi&t=$timestamp" +
                "&microTopicId=0&isAjax=isAjax"
        }
        val (status, body) = api.getFollowing(url, MOOC2_REF)
        api.ensurePageOk(status, body, "读取资料目录")
        CxDocParser.parseList(body)
    }

    /**
     * 文件元信息。老项目 meta 解析失败时返回 `{}`，由 `run()` 补默认值；这里保留
     * 同一语义（[CxDocMeta] 空对象），但 401/403 仍要抛给重登/风控，不能伪装成
     * 「文件没信息」继续刷。
     */
    suspend fun meta(objectId: String): CxDocMeta = withContext(Dispatchers.IO) {
        if (objectId.isBlank()) return@withContext CxDocMeta()
        val url = "https://mooc1-1.chaoxing.com/ananas/status/$objectId" +
            "?k=9790&flag=normal&_dc=${System.currentTimeMillis()}"
        val (status, body) = api.getFollowing(url, MOOC_REF)
        api.ensurePageOk(status, body, "获取资料信息")
        CxDocParser.parseMeta(body) ?: CxDocMeta().also {
            Log.w(TAG, "meta 不是 JSON：objectId=$objectId len=${body.length}")
        }
    }

    /** 阅读次数 +1；老项目不看结果，这里也只在非 200 时留一行日志。 */
    suspend fun updateReadCount(
        courseId: String,
        clazzId: String,
        cpi: String,
        dataId: String,
    ) = withContext(Dispatchers.IO) {
        val url = "https://mooc2-ans.chaoxing.com/mooc2-ans/coursedata/update/update-read-count" +
            "?courseId=$courseId&clazzId=$clazzId&dataId=$dataId&cpi=$cpi&ut=s"
        val (status, _) = api.getFollowing(url, MOOC2_REF)
        if (status != 200) Log.w(TAG, "update-read-count HTTP $status dataId=$dataId")
    }

    /**
     * 一次阅读心跳。返回 HTTP 状态；签名与 URL 编码都按老项目 `compute_enc` / `hb`，
     * 这里绝不打印 `d` 或 enc 之外的正文。
     */
    suspend fun beat(beat: CxDocBeat, uid: String): Int = withContext(Dispatchers.IO) {
        val json = CxDocParser.heartbeatJson(beat, uid)
        val enc = CxDocParser.signHeartbeat(json, beat.timestampMs, uid)
        val url = "https://data-xxt.aichaoxing.com/analysis/ac_mark" +
            "?&f=readPoint&u=$uid" +
            "&d=${CxDocParser.percentEncode(json)}" +
            "&t=${beat.timestampMs}&enc=$enc"
        api.getFollowing(url, PAN_REF).first
    }

    companion object {
        private const val TAG = "CxDocApi"

        const val DEFAULT_LIST_URL =
            "https://mooc2-ans.chaoxing.com/mooc2-ans/coursedata/stu-datalist"

        /** 老项目 `Browser` 在拿不到课程 enc 时的兜底值（根目录先用它）。 */
        const val DEFAULT_FILE_ENC = "360182fb3a145a6c260ed351d636fca9"

        private const val MOOC_REF = "https://mooc1-1.chaoxing.com/"
        private const val MOOC2_REF = "https://mooc2-ans.chaoxing.com/"
        private const val PAN_REF = "https://pan-yz.chaoxing.com/"
    }
}