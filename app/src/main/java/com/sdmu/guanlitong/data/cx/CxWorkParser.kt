package com.sdmu.guanlitong.data.cx

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * 作业做题页（doWork）解析，对照老项目 `cx_api.py: fetch_work_detail`。
 *
 * 页面结构（真实页面样本尚未落到单测以外，字段容错按老项目正则/BeautifulSoup 逻辑）：
 * - 第一个 `<form action="...">` 是提交接口；
 * - 所有 `<input type="hidden">` 是提交固定参数（enc、workId、courseId 等）；
 * - 每道题是 `<div id="question{qid}" typename="单选题">`，题干在
 *   `<h3 class="mark_name">`，选项在 `<div class="answerBg">` 内
 *   `<span class="num_option" data="A">` + `<div class="answer_p">`；
 * - 填空题空数取 `input[name=tiankongsize{qid}]`，已填内容在
 *   `textarea[id^=answerEditor{qid}]`；其他题已答值在 `input/textarea#answer{qid}`。
 *
 * 解析不到题目时返回空列表，不抛异常（交给人机判断是「没有题」还是页面变了）。
 */
object CxWorkParser {

    private const val TAG = "CxWorkParser"

    /**
     * 真实页面的题干容器探测项：只用于诊断日志，不影响解析结果。
     * 2026-11-27 真机出现「题干为空但界面有正文」，先确认正文落在哪个容器里。
     */
    private val RICH_CONTAINER_PROBES = listOf(
        "div[class~=stem_answer]" to "stem_answer",
        "div[class~=mark_content]" to "mark_content",
        "div[class~=richText]" to "richText",
        "div[class~=rich_text]" to "rich_text",
        "div[class~=questionContent]" to "questionContent",
        "div[class~=qtContent]" to "qtContent",
        "iframe" to "iframe",
    )

    private val QUESTION_ID_RE = Regex("""^question\d+$""")
    private val ANSWER_FIELD_RE = Regex("""^answer\d+$""")
    private val TITLE_PREFIX_RE = Regex("""^\d+\.\s*""")
    private val BRACKET_PREFIX_RE = Regex("""^\(.*?\)\s*""")
    private val MSPACE_RE = Regex("""\\mspace\{[^}]*\}\s*""")

    private val META_REFRESH_RE = Regex(
        """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?[^>]*?url\s*=\s*([^"'>\s]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val JS_LOCATION_RE = Regex(
        """(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE,
    )
    private val JS_REPLACE_RE = Regex(
        """(?:window\.)?location\.(?:replace|assign)\(\s*['"]([^'"]+)['"]\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    private val QTYPE_BY_CODE = mapOf(
        "0" to CxWorkQType.SINGLE,
        "1" to CxWorkQType.MULTI,
        "2" to CxWorkQType.JUDGE,
        "3" to CxWorkQType.FILL,
        "4" to CxWorkQType.ESSAY,
        "5" to CxWorkQType.CALC,
        "6" to CxWorkQType.CONNECT,
        "7" to CxWorkQType.SORT,
        "8" to CxWorkQType.INNER_CALC,
    )

    /**
     * @param html doWork 页 HTML。
     * @param doworkUrl 最终做题页 URL，用于把 form action / 图片相对路径补成绝对地址；
     *   传空串时保留原样。
     */
    fun parse(html: String, doworkUrl: String = ""): CxWorkDetail {
        if (html.isBlank()) {
            return CxWorkDetail("", emptyMap(), doworkUrl, emptyList())
        }
        val doc = Jsoup.parse(html, doworkUrl)

        val submitParams = LinkedHashMap<String, String>()
        doc.select("input")
            .filter { it.attr("type").equals("hidden", ignoreCase = true) }
            .forEach { input ->
                val name = input.attr("name").ifBlank { input.attr("id") }
                if (name.isNotBlank()) submitParams[name] = input.attr("value")
            }

        val submitUrl = resolveUrl(doc.selectFirst("form")?.attr("action").orEmpty(), doworkUrl)

        val questions = doc.select("div[id^=question]")
            .filter { QUESTION_ID_RE.matches(it.id()) }
            .mapIndexed { index, div -> parseQuestion(div, index + 1, doworkUrl) }

        val courseName = doc.selectFirst(".courseName, .course-name")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: submitParams["courseName"].orEmpty().trim()

        val title = doc.title().trim().ifBlank {
            doc.selectFirst("h1, .mark_title, .workTitle")?.text()?.trim().orEmpty()
        }

        return CxWorkDetail(
            submitUrl = submitUrl,
            submitParams = submitParams,
            doworkUrl = doworkUrl,
            questions = questions,
            courseName = courseName,
            title = title,
        )
    }

    /** 页面没有题目但有 meta refresh / JS 跳转时，返回跳转目标（已补绝对地址）。 */
    fun findClientRedirect(html: String, baseUrl: String = ""): String? {
        if (html.isBlank()) return null
        val raw = META_REFRESH_RE.find(html)?.groupValues?.getOrNull(1)
            ?: JS_REPLACE_RE.find(html)?.groupValues?.getOrNull(1)
            ?: JS_LOCATION_RE.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val target = raw.replace("&amp;", "&").trim()
        if (target.isBlank()) return null
        return resolveUrl(target, baseUrl)
    }

    /** 把相对地址按 [baseUrl] 补全；无法解析时原样返回。 */
    fun resolveUrl(target: String, baseUrl: String): String {
        val value = target.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            return "$scheme:$value"
        }
        if (baseUrl.isBlank()) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
    }

    private fun parseQuestion(div: Element, index: Int, baseUrl: String): CxWorkQuestion {
        val qid = div.id().removePrefix("question")
        val answerType = div.getElementById("answertype$qid")?.attr("value").orEmpty()
        val typeName = div.attr("typename")
        val qtype = detectQType(typeName, answerType)

        val titleElement = div.selectFirst("h3[class~=mark_name]")
        val titleRich = if (titleElement != null) extractRichText(titleElement, baseUrl) else RichText("")

        val options = div.select("div[class~=answerBg]").mapNotNull { optionDiv ->
            val keySpan = optionDiv.selectFirst("span[class~=num_option]")
            val key = keySpan?.attr("data")?.trim()?.takeIf { it.isNotBlank() }
                ?: keySpan?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: "?"
            val textElement = optionDiv.selectFirst("div.answer_p")
                ?: optionDiv.selectFirst("div[class~=fl]")
            val rich = if (textElement != null) extractRichText(textElement, baseUrl) else RichText("")
            CxWorkOption(key = key, text = rich.text, images = rich.images)
        }

        var fillCount = 1
        var fillAnswers = emptyList<String>()
        val existingAnswer = when (qtype) {
            CxWorkQType.FILL -> {
                val declared = div.selectFirst("input[name=tiankongsize$qid]")
                    ?.attr("value")?.toIntOrNull() ?: 1
                val editors = div.select("textarea")
                    .filter { it.id().startsWith("answerEditor$qid") }
                fillCount = maxOf(declared.coerceAtLeast(1), editors.size)
                fillAnswers = if (editors.isNotEmpty()) {
                    editors.map { it.text().trim() }
                } else {
                    List(fillCount) { "" }
                }
                fillAnswers.joinToString("|")
            }

            else -> {
                val element = div.getElementById("answer$qid")
                when {
                    element == null -> ""
                    element.tagName().equals("textarea", ignoreCase = true) -> element.text().trim()
                    else -> element.attr("value")
                }
            }
        }

        val question = CxWorkQuestion(
            id = qid,
            index = index,
            qtype = qtype,
            title = titleRich.text,
            titleImages = titleRich.images,
            options = options,
            answer = existingAnswer,
            fillCount = fillCount,
            fillAnswers = fillAnswers,
        )

        logDiagnostics(div, question, hasStemElement = titleElement != null)
        return question
    }

    /**
     * 每题一条 debug 日志：方向是「题干为空时正文到底在哪」。
     * 字段固定为 qid / 题型 / 题干长度 / 选项数 / 图片数 / iframe / 各候选富文本容器
     * 的文本长度 / question div 的直接子节点（标签[class]:文本长度）。
     */
    private fun logDiagnostics(div: Element, question: CxWorkQuestion, hasStemElement: Boolean) {
        val rich = RICH_CONTAINER_PROBES.joinToString(",") { (selector, name) ->
            val element = div.selectFirst(selector)
            val detail = when {
                element == null -> "-"
                element.tagName().equals("iframe", ignoreCase = true) -> "1"
                else -> element.text().length.toString()
            }
            "$name=$detail"
        }
        val children = div.children().take(8).joinToString("; ") { child ->
            "${child.tagName()}[${child.className()}]:${child.text().length}"
        }
        Log.d(
            TAG,
            "qid=${question.id} type=${question.qtype.name} stemLen=${question.title.length} " +
                "hasH3=$hasStemElement options=${question.options.size} " +
                "images=${question.titleImages.size} iframes=${div.select("iframe").size} " +
                "wholeText=${div.text().length} rich=$rich children=$children",
        )
    }

    private fun detectQType(typeName: String, answerType: String): CxWorkQType {
        var qtype = QTYPE_BY_CODE[answerType] ?: CxWorkQType.ESSAY
        if (typeName.isNotBlank()) {
            qtype = when {
                "判断" in typeName -> CxWorkQType.JUDGE
                "多选" in typeName -> CxWorkQType.MULTI
                "单选" in typeName -> CxWorkQType.SINGLE
                "填空" in typeName -> CxWorkQType.FILL
                "简答" in typeName || "论述" in typeName || "问答" in typeName -> CxWorkQType.ESSAY
                "计算" in typeName -> CxWorkQType.CALC
                else -> qtype
            }
        }
        return qtype
    }

    private class RichText(val text: String, val images: List<String> = emptyList())

    /**
     * 取富文本：`img[data]` 是 LaTeX，解码后作为文本内联；`img[src]` 收集绝对地址并从
     * 文本里移除。最后去掉题目编号前缀（`1. ` / `(1) `）。
     */
    private fun extractRichText(element: Element, baseUrl: String): RichText {
        val copy = element.clone()
        val images = mutableListOf<String>()
        copy.select("img").forEach { img ->
            val latex = img.attr("data")
            val src = img.attr("src")
            when {
                latex.isNotBlank() -> img.replaceWith(TextNode(decodeLatex(latex)))
                src.isNotBlank() -> {
                    val absolute = resolveUrl(src, baseUrl)
                    if (absolute.isNotBlank()) images += absolute
                    img.remove()
                }

                else -> img.remove()
            }
        }
        var text = copy.text()
        text = TITLE_PREFIX_RE.replace(text, "")
        text = BRACKET_PREFIX_RE.replace(text, "")
        return RichText(text.trim(), images)
    }

    /**
     * 超星 `img[data]` 解码链：URL decode → 去掉外层引号 → 还原 JSON 里的双反斜杠
     * → 去掉排版用的 `\mspace{…}`。`%XX` 只按字节解码，不把 `+` 当空格（与
     * Python `urllib.parse.unquote` 一致）。
     */
    private fun decodeLatex(raw: String): String {
        var value = percentDecode(raw).trim().trim('"')
        value = value.replace("\\\"", "\"")
        value = value.replace("\\\\", "\\")
        value = MSPACE_RE.replace(value, "")
        return value.trim()
    }

    private fun percentDecode(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '%' && i + 2 < input.length) {
                val bytes = ByteArrayOutputStream()
                var j = i
                while (j + 2 < input.length && input[j] == '%') {
                    val byte = input.substring(j + 1, j + 3).toIntOrNull(16) ?: break
                    bytes.write(byte)
                    j += 3
                }
                if (j > i) {
                    out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                    i = j
                    continue
                }
            }
            out.append(input[i])
            i++
        }
        return out.toString()
    }

    /** 供提交时判断参数名（测试也可直接用）。 */
    internal fun isAnswerField(name: String): Boolean = ANSWER_FIELD_RE.matches(name)
}