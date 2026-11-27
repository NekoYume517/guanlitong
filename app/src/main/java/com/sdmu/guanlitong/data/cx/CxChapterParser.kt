package com.sdmu.guanlitong.data.cx

import org.json.JSONObject

/**
 * 学习通章节页与任务点页解析。
 *
 * 对照实现：D:\code\chaoxing-fenbushi\server\app\cx_api.py 的
 * `get_chapter_list`（章节树）与 `get_knowledge_cards`（mArg 任务点）。
 */
object CxChapterParser {

    /** 老项目锚点：cards 页里真实赋值语句前有三个空格。 */
    private const val MARG_ANCHOR = "   mArg = "

    /** 锚点缺失时的退化匹配；多个就取最后一个，避免被前面的声明/注释骗到。 */
    private val MARG_FALLBACK = Regex("""\bmArg\s*=\s*\{""")

    // 页面可能给 div 加额外属性或换引号，例如 <div class="chapter_unit" data-id="..">；
// 只要求 class 值精确等于 chapter_unit，避免误切 chapter_unit_xxx。
    private val UNIT_SPLIT = Regex("""<div\s+class=["']chapter_unit["'](?=[\s>])""")
    private val PARENT_TITLE = Regex("""<span\s+title="([^"]+)"""")
    private val CHILD_ONCLICK =
        Regex("""onclick="toOld\('([^']*)',\s*'(\d+)',\s*'([^']*)'[^"]*"[^>]*title="([^"]+)"""")
    private val CHILD_ONCLICK_TITLE_FIRST =
        Regex("""title="([^"]+)"[^>]*onclick="toOld\('([^']*)',\s*'(\d+)',\s*'([^']*)'""")
    private val STUDY_ENC = Regex("""_from = '\d+_\d+_\d+_([0-9a-f]{32})'""")

    /** 解析 `studentcourse` 页面的章节树：父章节 + 子节点。 */
    fun parseTree(html: String): List<CxChapterGroup> {
        val units = html.split(UNIT_SPLIT)
        if (units.size <= 1) return emptyList()

        return units.drop(1).mapNotNull { unit ->
            val parentName = PARENT_TITLE.find(unit)?.groupValues?.get(1)?.trim().orEmpty()
            val children = LinkedHashMap<String, String>()
            CHILD_ONCLICK.findAll(unit).forEach { m ->
                children.putIfAbsent(m.groupValues[2], m.groupValues[4].trim())
            }
            CHILD_ONCLICK_TITLE_FIRST.findAll(unit).forEach { m ->
                children.putIfAbsent(m.groupValues[3], m.groupValues[1].trim())
            }
            if (children.isEmpty() && parentName.isEmpty()) return@mapNotNull null
            CxChapterGroup(
                name = parentName.ifEmpty { "未命名章节" },
                children = children.map { (id, name) -> CxChapterChild(id, name) },
            )
        }
    }

    /**
     * 解析一页 `knowledge/cards`：任务点卡片 + 章节学习 enc。
     *
     * 页面有 `mArg` 却解析失败时抛 502，绝不能返回空列表 —— 上层会把空列表当成
     * 「本章没有任务点」，这正是老项目「刷课假完成」假象的来源。真正没有任务点的
     * 页面（无 `mArg` 标记）才是合法的空结果。
     */
    fun parseCards(html: String, chapterId: String = ""): CxChapterPage {
        val studyEnc = STUDY_ENC.find(html)?.groupValues?.get(1).orEmpty()
        val start = margStart(html) ?: return CxChapterPage(emptyList(), studyEnc)
        val marg = parseMargAt(html, start)
            ?: throw CxException("章节任务点解析失败", 502)

        val defaults = marg.optJSONObject("defaults")
        val reportUrl = defaults?.optString("reportUrl").orEmpty()
        val attachments = marg.optJSONArray("attachments")
        if (attachments == null || attachments.length() == 0) {
            return CxChapterPage(emptyList(), studyEnc)
        }

        val cards = buildList {
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val jobid = att.optString("jobid")
                if (jobid.isEmpty()) continue
                val property = att.optJSONObject("property")
                add(
                    CxTaskCard(
                        chapterId = chapterId,
                        jobid = jobid,
                        objectId = property?.optString("objectid").orEmpty(),
                        mid = att.optString("mid"),
                        type = att.optString("type").lowercase(),
                        name = property?.optString("name").orEmpty(),
                        isPassed = att.optBoolean("isPassed", false),
                        duration = intOf(property, "duration"),
                        // 服务端签发值只有 > 0 才可用；0 / null 绝不能当作凭证回传，
                        // 否则等于伪造 attDuration。
                        attDuration = intOf(att, "attDuration").takeIf { it > 0 },
                        attDurationEnc = att.optString("attDurationEnc"),
                        begins = intOf(att, "begins"),
                        ends = intOf(att, "ends"),
                        otherInfo = att.optString("otherInfo"),
                        reportUrl = reportUrl,
                    ),
                )
            }
        }
        return CxChapterPage(cards, studyEnc)
    }

    /**
     * 从 cards 页中取出 `mArg = {...};` 的 JSON。
     * 页面结构：`   mArg = {...};\n}catch(e){`，结束位置以 `}catch` 兜底。
     */
    fun extractMarg(html: String): JSONObject? {
        val start = margStart(html) ?: return null
        return parseMargAt(html, start)
    }

    /**
     * 定位 `mArg` 赋值语句。
     * 优先老项目的精确锚点「三个空格 + mArg = 」；没有时退化到词边界匹配并取
     * **最后一个**，防止命中前面的声明、注释或别的脚本块。
     */
    private fun margStart(html: String): Int? {
        val anchor = html.indexOf(MARG_ANCHOR)
        if (anchor >= 0) return anchor
        return MARG_FALLBACK.findAll(html).lastOrNull()?.range?.first
    }

    private fun parseMargAt(html: String, start: Int): JSONObject? {
        val eq = html.indexOf('=', start)
        if (eq < 0) return null
        val objStart = html.indexOf('{', eq)
        if (objStart < 0) return null

        val catchIdx = html.indexOf("}catch", objStart)
        val end = if (catchIdx > 0) {
            html.lastIndexOf('}', catchIdx - 1)
        } else {
            html.lastIndexOf('}')
        }
        if (end <= objStart) return null
        return runCatching { JSONObject(html.substring(objStart, end + 1)) }.getOrNull()
    }

    private fun intOf(obj: JSONObject?, key: String): Int {
        if (obj == null) return 0
        return when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }
}