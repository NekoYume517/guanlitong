package com.sdmu.guanlitong.data.cx

/**
 * 课程列表页 HTML 解析（纯字符串处理，可在 JVM 单测里直接跑）。
 *
 * 页面结构（参考 D:\code\chaoxing-fenbushi\server\app\cx_api.py 的 `get_course_list`）：
 * ```
 * <li class="course ..." courseId="..." clazzId="..." personId="...">
 *   <a href="...stucoursemiddle?...">
 *     <img src="https://p.ananas.chaoxing.com/...">
 *     <span class="course-name ..." title="课程名">
 *     <p class="line2" title="教师">
 *   </a>
 * </li>
 * ```
 * 上游用「四个全局列表按下标配对」，课程缺封面或缺教师时会导致错位；
 * 这里按 `li` 分块解析，每个字段只在本块内找，缺什么就留空。
 */
internal object CxCourseParser {

    // 必须以 class="course…" 开头，并且同一个 li 标签里带 courseId。
    // 不能用 \bcourse\b：课程块内的 <li class="quit-course quitCourseBtn">退课</li>
    // 也满足词边界，会把块从名字之前截断（真实页面已复现）。
    private val LI_RE = Regex(
        """<li\b(?=[^>]*\bclass="course[^"]*")(?=[^>]*\bcourseid="\d+")[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val COURSE_ID_RE = Regex("""\bcourseid="(\d+)"""", RegexOption.IGNORE_CASE)
    private val CLAZZ_ID_RE = Regex("""\bclazzid="(\d+)"""", RegexOption.IGNORE_CASE)
    private val PERSON_ID_RE = Regex("""\bpersonid="(\d+)"""", RegexOption.IGNORE_CASE)
    private val NAME_RE = Regex(
        """class="course-name[^"]*"[^>]*title="([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val TEACHER_RE = Regex(
        """<p[^>]*class="line2"[^>]*title="([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val COVER_RE = Regex(
        """<img[^>]*src="(https://p\.ananas\.chaoxing\.com/[^"]+)"""",
        RegexOption.IGNORE_CASE,
    )
    private val LINK_RE = Regex(
        """href="(https://mooc1-1\.chaoxing\.com/mooc-ans/visit/stucoursemiddle\?[^"]*)"""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(html: String): List<CxCourse> {
        if (html.isBlank()) return emptyList()
        val tags = LI_RE.findAll(html).toList()
        if (tags.isEmpty()) return emptyList()

        return tags.mapIndexedNotNull { index, tag ->
            val end = if (index + 1 < tags.size) tags[index + 1].range.first else html.length
            val block = html.substring(tag.range.first, end)

            val courseId = COURSE_ID_RE.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val clazzId = CLAZZ_ID_RE.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val personId = PERSON_ID_RE.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null

            CxCourse(
                courseId = courseId,
                clazzId = clazzId,
                personId = personId,
                name = NAME_RE.find(block)?.groupValues?.get(1)?.htmlUnescape()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "未命名课程",
                teacher = TEACHER_RE.find(block)?.groupValues?.get(1)?.htmlUnescape()?.trim().orEmpty(),
                coverUrl = COVER_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
                link = LINK_RE.find(block)?.groupValues?.get(1)?.htmlUnescape()?.trim().orEmpty(),
            )
        }
    }

    /** 只处理标题里常见的几种实体，避免把课程名显示成 `&amp;`。 */
    private fun String.htmlUnescape(): String = this
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}