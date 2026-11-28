package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 智慧树（知到）账号登录，对照 D:\code\zhihuishu\zhs_server.py 的 `ZhiHuiShuAPI.login`：
 *
 * 1. `loginEncrypt({account,password,validate})` → POST `validateAccountAndPassword`
 *    （body 只有 `secretStr=`），返回 `pwd` / `uuid`；
 * 2. 用 `pwd` 打开 `passport.zhihuishu.com/login?pwd=…&service=…` 完成 CAS，
 *    拿到 CASTGC，再依次认证 onlineservice-api / creditqa-api / stuonline；
 * 3. 预热 studyvideoh5（失败不影响本次登录）；
 * 4. 判据：CASTGC 在 Cookie 里。
 *
 * 滑块由真人完成，[validate] 原样透传，App 不做任何识别或绕过。
 * 密码只出现在这一次请求的表单里，不打印、不落盘。
 */
object ZhsLoginApi {

    private const val PASSPORT_LOGIN = "https://passport.zhihuishu.com/user/validateAccountAndPassword"
    private const val PASSPORT_CAS = "https://passport.zhihuishu.com/login"
    private const val ONLINE_WEB_SERVICE = "https://onlineweb.zhihuishu.com/onlinestuh5"

    suspend fun login(account: String, password: String, validate: String): ZhsSession =
        withContext(Dispatchers.IO) {
            val http = ZhsHttp()
            val encrypted = ZhsCrypto.loginEncrypt(account, password, validate)
            val response = runCatching {
                http.postForm(
                    PASSPORT_LOGIN,
                    mapOf("secretStr" to encrypted),
                    mapOf(
                        "Accept" to "application/json",
                        "Origin" to "https://passport.zhihuishu.com",
                        "Referer" to "https://passport.zhihuishu.com/login",
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                )
            }.getOrElse { throw ioError(it, "连不上智慧树登录服务，请检查网络") }

            val ticket = parseLoginResponse(response.code, response.body)

            // 2a: 消耗 pwd 拿 CASTGC，并跟随到 onlineweb。
            try {
                http.get(
                    "$PASSPORT_CAS?pwd=${ZhsHttp.urlEncode(ticket.pwd)}" +
                        "&service=${ZhsHttp.urlEncode(ONLINE_WEB_SERVICE)}",
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw ioError(e, "登录跳转失败，请重试")
            }

            if (!http.cookies["CASTGC"].orEmpty().isNotBlank()) {
                throw ZhsException("账号验证通过，但登录状态没有建立成功，请重试", ZhsErrorKind.AUTH)
            }

            // 2b-2d: 各子系统的 CAS 认证，失败不影响课程列表主流程。
            bestEffort {
                http.get(
                    "https://onlineservice-api.zhihuishu.com/gateway/f/v1/login/gologin?" +
                        "fromurl=${ZhsHttp.urlEncode(ONLINE_WEB_SERVICE)}",
                )
            }
            bestEffort {
                http.get(
                    "https://creditqa-api.zhihuishu.com/creditqa/gateway/f/v1/web/login/gologin" +
                        "?fromurl=https://qah5.zhihuishu.com/qa.html",
                )
            }
            bestEffort {
                http.get("$PASSPORT_CAS?service=${ZhsHttp.urlEncode(STUONLINE_SERVICE)}")
            }

            // 预热学习页会话（studyvideoh5），失败忽略。
            bestEffort {
                repeat(2) {
                    http.get("$PASSPORT_CAS?service=${ZhsHttp.urlEncode(STUDY_SERVICE)}")
                }
            }

            ZhsSession(
                account = account.trim(),
                uuid = ticket.uuid,
                cookies = LinkedHashMap(http.cookies),
                loggedAt = System.currentTimeMillis(),
            )
        }

    private const val STUONLINE_SERVICE =
        "https://stuonline.zhihuishu.com/stuonline/stuLearnReportNew/index"
    private const val STUDY_SERVICE = "https://studyvideoh5.zhihuishu.com/stuStudy"

    /** CAS 子系统的认证是可选的：取消要照常向上抛，其余异常忽略。 */
    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 子系统失败不影响 CASTGC 已建立的登录态。
        }
    }

    /**
     * 纯解析：`{"status":1,"pwd":"…","uuid":"…"}` 才算成功。
     * 失败时把上游 `msg` 原样带出来（账号密码错、滑块过期等都在这里）。
     */
    internal fun parseLoginResponse(code: Int, body: String): ZhsLoginTicket {
        if (code == 401 || code == 403) {
            throw ZhsException("账号验证被拒绝，请重新完成滑块验证", ZhsErrorKind.AUTH)
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException(
                if (code >= 500) "智慧树登录服务暂时不可用，请稍后重试" else "登录响应格式异常，请重试",
                if (code >= 500) ZhsErrorKind.SERVER else ZhsErrorKind.PARSE,
            )
        val status = json.opt("status")
        val ok = (status as? Number)?.toInt() == 1 || status?.toString() == "1" ||
            status == true || status?.toString() == "true"
        if (!ok) {
            val msg = json.optString("msg").ifBlank { json.optString("message") }
            throw ZhsException(
                msg.ifBlank { "账号或密码不正确，或滑块验证已失效" },
                ZhsErrorKind.AUTH,
            )
        }
        val pwd = json.optString("pwd")
        if (pwd.isBlank()) {
            throw ZhsException("登录响应缺少会话票据，请重试", ZhsErrorKind.PARSE)
        }
        return ZhsLoginTicket(pwd = pwd, uuid = json.optString("uuid"))
    }
}