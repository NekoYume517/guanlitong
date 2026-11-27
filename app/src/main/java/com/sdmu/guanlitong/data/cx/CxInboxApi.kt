package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 收件箱接口（notice.chaoxing.com），对照老项目 `cx_api.py:1242 get_inbox_list`。
 *
 * 只拉最新首页（约 20 条）：`type=2`，不带 `lastValue`（那是「取更早一页」，
 * 当同步游标用会一直往历史翻）。风控/登录失效的判定
 * 复用 [CxApi.ensurePageOk]（202 与验证码页 → 403，302/401/登录页 → 401），
 * 与课程接口保持同一套错误码，Repository 的单飞重登才能接上。
 *
 * [CxApi.get] 自己不做调度切换，这里必须包 [Dispatchers.IO]：UI 层从主线程的
 * `rememberCoroutineScope` 调进来时，否则会撞 `NetworkOnMainThreadException`
 * （无 message，界面只会显示通用的「学习通请求失败」）。
 */
class CxInboxApi(
    private val api: CxApi,
    private val baseUrl: String = DEFAULT_BASE,
    private val referer: String = DEFAULT_REFERER,
) {

    suspend fun fetch(): CxInboxPage = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/mobile/notice/getNoticeList?type=2"
        val (status, body) = api.get(url, referer)
        api.ensurePageOk(status, body, "同步收件箱")
        CxInboxParser.parse(body)
    }

    companion object {
        const val DEFAULT_BASE = "https://notice.chaoxing.com"
        const val DEFAULT_REFERER = "https://notice.chaoxing.com/res/pc/mobileHtml/html/inbox.html"
    }
}