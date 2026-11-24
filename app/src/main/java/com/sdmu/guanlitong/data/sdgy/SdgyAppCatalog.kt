package com.sdmu.guanlitong.data.sdgy

import com.sdmu.guanlitong.data.SdgyPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 山管 App「应用」总页的数据模型。
 *
 * 官方页面完全由服务端 `api/app/list` 驱动：返回若干分组（YYFL 为分组名），
 * 组内 DATA 是应用条目。这里做两件事：
 *  1. 宽松解析官方字段，服务端字段缺失/大小写变化也不至于白屏；
 *  2. 给官方条目补上我们自己的原生实现（课表、付款码、扫一扫……），
 *     官方的网页条目则原样保留，点开走内置 WebView。
 */
data class SdgyAppEntry(
    val id: String,
    val name: String,
    val lx: String,
    val url: String,
    val iconUrl: String,
    val description: String = "",
    val nativePage: SdgyPage? = null,
)

data class SdgyAppGroup(
    val name: String,
    val apps: List<SdgyAppEntry>,
    /** 官方分组 TYPE=0，一般是「常用应用」。 */
    val common: Boolean = false,
)

data class SdgyNativeTool(
    val page: SdgyPage,
    val name: String,
    val description: String,
)

object SdgyAppCatalog {

    /** 移到功能页「其他」二级标签，山管功能总表不再显示。 */
    private val EXCLUDED_NATIVE_PAGES = setOf(SdgyPage.Scan, SdgyPage.Xbc)

    /** 官方列表里没有、但我们做成了原生页面的工具。 */
    val nativeTools = listOf(
        SdgyNativeTool(SdgyPage.Timetable, "山管课表", "按周查看 · 课程详情"),
        SdgyNativeTool(SdgyPage.Pay, "一卡通付款码", "余额 · 付款码"),
        SdgyNativeTool(SdgyPage.Power, "宿舍用电", "剩余电量 · 近 14 天用量"),
        SdgyNativeTool(SdgyPage.Shot, "请销假", "生成申请截图"),
    )

    /** 解析 `api/app/list` 解密后的 JSON；兼容正文直接是数组或对象包一层 DATA。 */
    fun parse(raw: String): List<SdgyAppGroup> {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            trimmed.startsWith("{") -> runCatching {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("DATA") ?: obj.optJSONArray("data") ?: obj.optJSONArray("apps")
            }.getOrNull()
            else -> null
        } ?: return emptyList()
        return parse(array)
    }

    fun parse(array: JSONArray): List<SdgyAppGroup> {
        val groups = mutableListOf<SdgyAppGroup>()
        val loose = mutableListOf<SdgyAppEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // 少数版本直接返回扁平应用数组，没有分组外壳。
            if (item.field("YYID", "yyid", "id", "APPID").isNotBlank() &&
                item.optJSONArray("DATA") == null && item.optJSONArray("data") == null
            ) {
                entryOf(item)?.let { loose += it }
                continue
            }
            val type = item.field("TYPE", "type")
            // 官方首页的「常用应用」在后台可以整组隐藏。
            if (type == "0" && item.field("ISSHOWCOMMONAPP", "isshowcommonapp") == "0") {
                continue
            }
            val name = item.field("YYFL", "YYFLNAME", "NAME", "name")
                .ifBlank { if (type == "0") "常用应用" else "应用" }
            val data = item.optJSONArray("DATA")
                ?: item.optJSONArray("data")
                ?: item.optJSONArray("APPS")
                ?: item.optJSONArray("apps")
                ?: JSONArray()
            var apps = buildList {
                for (j in 0 until data.length()) {
                    data.optJSONObject(j)?.let { entryOf(it) }?.let { add(it) }
                }
            }
            val shownNumber = item.optInt("SHOWNUMBER", item.optInt("shownumber", 0))
            if (type == "0" && shownNumber > 0) apps = apps.take(shownNumber)
            if (apps.isNotEmpty()) {
                val existing = groups.indexOfFirst { it.name == name }
                if (existing >= 0) {
                    groups[existing] = groups[existing].copy(apps = groups[existing].apps + apps)
                } else {
                    groups += SdgyAppGroup(name = name, apps = apps, common = type == "0")
                }
            } else if (item.optJSONObject("APP") != null) {
                entryOf(item.optJSONObject("APP")!!)?.let { loose += it }
            }
        }
        if (loose.isNotEmpty()) groups += SdgyAppGroup("应用", loose.distinctBy { it.id })
        return groups
    }

    /** 服务端暂时不可用时显示的官方条目（字段来自对官方 App 的抓取记录）。 */
    fun fallback(): List<SdgyAppGroup> = withNativeTools(
        listOf(
            SdgyAppGroup(
                name = "常用应用",
                common = true,
                apps = listOf(
                    web("common_freeclassroom", "空闲教室", "0dcbbb65-051a-453f-9eb6-984e5455e8be"),
                ),
            ),
            SdgyAppGroup(
                name = "官方应用",
                apps = listOf(
                    web("yzsbs", "网上办事大厅", "ebb2201d-3463-425b-b698-3209d5b2c572"),
                    web("common_graduate_report", "毕业报告", "09f3b3ee-a0f5-458f-affc-3ad68a360485"),
                    web("common_wechat_exam", "我的考试", "73d509f8-6f93-4c2a-aa5a-95f780f87247"),
                    web("jwxt", "教务系统", "8aecba3a-8d02-4b02-b62b-f722b3e31222"),
                    web("dpxt", "督评系统", "61196481-bde0-4ac8-9e3c-afcfec92d1e4"),
                    web("GYXT", "公寓系统", "f6ad7759-8da8-481d-86da-1cb71910bd1b"),
                ),
            ),
            SdgyAppGroup(
                name = "一卡通",
                apps = listOf(
                    web("yktsy", "一卡通首页", "342ba885-7717-4e58-a2f3-1c8ad1406b4c"),
                    web("common_ybg_onecard", "我的余额", "e0c879a0-e8c1-4a87-b503-eedc2762af18"),
                    web("schoolCard", "我的账单", "d598de18-c746-40ce-bc2b-d9829d07b95c"),
                    web("gsjg", "挂失解挂", "72663d04-b8b4-4667-871e-23933d783d90"),
                    web("XFBG", "消费报告", "5870920c-ea6d-4c6f-9e74-efb72bf38448"),
                ),
            ),
            SdgyAppGroup(
                name = "轻松校园",
                apps = listOf(
                    SdgyAppEntry(
                        id = "schoolNews",
                        name = "校园新闻",
                        lx = "4",
                        url = "https://www.sdmu.edu.cn/xwzx/xszx_.htm",
                        iconUrl = icon("38dea26a-375d-4de7-978d-8b734fd55d53"),
                    ),
                    web("common_lost_found", "失物招领", "8eaea5f2-744d-4841-bcff-24fac883d94c"),
                    web("schoolCalendar", "校历", "9a942545-f0f3-4389-bdb2-94d79a54ffcb"),
                    web("address_book", "通讯录", "9a53cdda-f233-473a-9c8c-66dbf60cdf47"),
                ),
            ),
        ),
    )

    /** 官方列表里已有的原生页面不再重复，缺的补进「原生工具」分组。 */
    fun withNativeTools(groups: List<SdgyAppGroup>): List<SdgyAppGroup> {
        val present = groups.flatMap { it.apps }.mapNotNull { it.nativePage }.toSet()
        val missing = nativeTools.filter { it.page !in present }
        if (missing.isEmpty()) return groups
        val apps = missing.map { tool ->
            SdgyAppEntry(
                id = "native_page_${tool.page.name}",
                name = tool.name,
                lx = "1",
                url = "",
                iconUrl = "",
                description = tool.description,
                nativePage = tool.page,
            )
        }
        val nativeGroup = SdgyAppGroup("原生工具", apps)
        val result = groups.toMutableList()
        val insertAt = if (result.firstOrNull()?.common == true) 1 else 0
        result.add(insertAt.coerceAtMost(result.size), nativeGroup)
        return result
    }

    private fun web(id: String, name: String, iconId: String): SdgyAppEntry =
        SdgyAppEntry(
            id = id,
            name = name,
            lx = "2",
            url = "",
            iconUrl = icon(iconId),
        )

    private fun icon(id: String): String = when {
        id.isBlank() -> ""
        id.startsWith("http://") || id.startsWith("https://") -> id
        id.startsWith("/") -> SdgyConst.SERVER + id
        else -> "${SdgyConst.SERVER}${SdgyConst.PATH_IMAGE}/$id"
    }

    private fun entryOf(obj: JSONObject): SdgyAppEntry? {
        val id = obj.field("YYID", "yyid", "id", "APPID")
        val name = obj.field("YYMC", "yymc", "name", "NAME")
        if (id.isBlank() && name.isBlank()) return null
        val url = obj.field("URL", "url")
        val rawLx = obj.field("LX", "lx")
        val lx = rawLx.ifBlank {
            when {
                url.startsWith("native_") -> "1"
                url.isBlank() -> "2"
                else -> "2"
            }
        }
        val iconId = obj.field("YYTB", "yytb", "ICON", "icon")
        val entry = SdgyAppEntry(
            id = id.ifBlank { name },
            name = name.ifBlank { id },
            lx = lx,
            url = url,
            iconUrl = icon(iconId),
            description = obj.field("MS", "ms", "DESC", "desc"),
        )
        return entry.copy(nativePage = nativePageOf(entry).takeUnless { it in EXCLUDED_NATIVE_PAGES })
    }

    private fun nativePageOf(entry: SdgyAppEntry): SdgyPage? {
        val id = entry.id.lowercase()
        val name = entry.name
        return when {
            id == "timetable" || id == "native_timetable" || name.contains("课表") ->
                SdgyPage.Timetable
            id == "ewm" || id == "native_pay" || id == "pay_native" || name.contains("付款码") ->
                SdgyPage.Pay
            id == "scan" || id == "native_scan" || name == "扫一扫" -> SdgyPage.Scan
            id == "native_shot" || name.contains("请销假生成器") -> SdgyPage.Shot
            id == "native_xbc" || name.contains("小白超人") -> SdgyPage.Xbc
            id == "native_power" || name.contains("宿舍用电") -> SdgyPage.Power
            else -> null
        }
    }

    private fun JSONObject.field(vararg keys: String): String {
        for (key in keys) {
            val value = opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            val text = value.toString().trim()
            if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) return text
        }
        return ""
    }
}