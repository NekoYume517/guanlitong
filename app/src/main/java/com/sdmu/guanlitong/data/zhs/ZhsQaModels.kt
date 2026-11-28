package com.sdmu.guanlitong.data.zhs

/** 课程问答的一条问题。 */
data class ZhsQuestion(
    val id: String,
    val content: String,
    val answerCount: Int,
    val time: String,
    val asker: String = "",
)

/** 一条回答。 */
data class ZhsAnswer(
    val id: String,
    val content: String,
    val time: String,
    val author: String = "",
    val teacher: Boolean = false,
)

/** 问题列表页（附带可能刷新过 qa 密钥的会话）。 */
data class ZhsQaPage(
    val questions: List<ZhsQuestion>,
    val session: ZhsSession,
)

/** 某问题的回答页。纯解析产物的 [session] 为空，数据层补上实际使用的会话。 */
data class ZhsAnswerPage(
    val questionContent: String,
    val answers: List<ZhsAnswer>,
    val session: ZhsSession? = null,
)