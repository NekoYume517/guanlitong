package com.sdmu.guanlitong.data.cx

/**
 * 作业题型。
 *
 * 编码对照老项目 `cx_api.py` 的 `_QTYPE_MAP`（answertype 0~8）；页面上的
 * `typename` 属性（如「单选题」）优先于编码。
 */
enum class CxWorkQType(val label: String) {
    SINGLE("单选题"),
    MULTI("多选题"),
    JUDGE("判断题"),
    FILL("填空题"),
    ESSAY("简答题"),
    CALC("计算题"),
    CONNECT("连线题"),
    SORT("排序题"),
    INNER_CALC("内嵌计算");

    /** 是否为可点选的题目（单选 / 多选 / 判断）；其余题型用输入框。 */
    val isChoice: Boolean get() = this == SINGLE || this == MULTI || this == JUDGE
}

/** 一个选项：key 是 A/B/C/D（判断题是 true/false），[images] 为题干内图片的绝对地址。 */
data class CxWorkOption(
    val key: String,
    val text: String,
    val images: List<String> = emptyList(),
)

/**
 * 一道题。
 *
 * - [answer] 是页面上回显的原始答案：选择题为字母（多选如 `AB`）、判断题为
 *   `true`/`false`、填空题为各空用 `|` 拼接、简答题为原文。
 * - 填空题额外保留 [fillCount] 与 [fillAnswers]，界面按空数渲染输入框。
 * - LaTeX（`img[data]`）已解码并内联进 [title]/选项文本，[titleImages] 只存
 *   `img[src]` 的绝对地址。
 */
data class CxWorkQuestion(
    val id: String,
    val index: Int,
    val qtype: CxWorkQType,
    val title: String,
    val titleImages: List<String> = emptyList(),
    val options: List<CxWorkOption> = emptyList(),
    val answer: String = "",
    val fillCount: Int = 1,
    val fillAnswers: List<String> = emptyList(),
)

/** 做题页解析结果。提交时把 [submitParams] 与 answer* 字段一起 POST 到 [submitUrl]。 */
data class CxWorkDetail(
    val submitUrl: String,
    val submitParams: Map<String, String>,
    val doworkUrl: String,
    val questions: List<CxWorkQuestion>,
    val courseName: String = "",
    val title: String = "",
)

/** 提交结果：成功与否 + 给用户看的一句话。 */
data class CxWorkResult(
    val success: Boolean,
    val message: String,
)