package com.sdmu.guanlitong.data.sdgy

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * 宿舍用电查询 —— 「能源易支付」财务系统（ASP.NET ASMX，返回 XML）。
 *
 * 逆向结论（2026-10-26 实测）：
 *  - 基址 `http://wxyy.cwc.sdmu.edu.cn/EEPWeiXinPay/PurchaseWebService.asmx`
 *  - 全部 GET，返回 XML；`<resultInfo><result>1</result>` 表示成功
 *  - 查询链路：getAreaInfo → getArchitectureInfo(Area_ID)
 *              → getRoomInfo(Architecture_ID, Floor) → getMeterInfo(Room_ID)
 *              → getReserveHKAM(AmMeter_ID)   ← 剩余金额/总示数/状态/闸位
 *              → getMeterDayValue(AmMeter_ID, startDate, endDate)  ← 每日用量
 *  - 查询类接口无需登录凭据，只要选对楼栋/楼层/房间即可
 */
object SdgyPowerApi {

    private const val BASE =
        "http://wxyy.cwc.sdmu.edu.cn/EEPWeiXinPay/PurchaseWebService.asmx"

    data class Area(val id: String, val name: String)
    data class Building(val id: String, val name: String, val floorFrom: Int, val floorTo: Int)
    data class Room(val id: String, val name: String)
    data class Meter(val id: String, val name: String, val type: String)

    /** 实时用量 */
    data class Usage(
        val remain: String,      // remainPower 剩余
        val unit: String,        // remainName 单位（元/度）
        val total: String,       // ZVlaue 总示数
        val state: String,       // 电表在线状态
        val valve: String,       // 合闸/拉闸
        val readTime: String,
    )

    /** 某天用量 */
    data class DayUsage(val date: String, val value: String, val money: String, val unit: String)

    @Volatile var lastErr: String = ""

    // ---------- XML 解析（轻量，够用） ----------

    private fun tags(xml: String, name: String): List<String> {
        val out = mutableListOf<String>()
        val p = Pattern.compile("<$name>(.*?)</$name>", Pattern.DOTALL)
        val m = p.matcher(xml)
        while (m.find()) out.add(m.group(1)?.trim().orEmpty())
        return out
    }

    private fun one(xml: String, name: String): String = tags(xml, name).firstOrNull().orEmpty()

    private fun ok(xml: String): Boolean = one(xml, "result") == "1"

    private fun msg(xml: String): String = one(xml, "msg").ifBlank { "查询失败" }

    // ---------- HTTP ----------

    private fun get(path: String, params: Map<String, String> = emptyMap()): String? {
        val q = if (params.isEmpty()) "" else
            "?" + params.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
        return try {
            val conn = URL(BASE + path + q).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (!ok(body)) {
                lastErr = msg(body)
                android.util.Log.w("PowerApi", "$path -> ${msg(body)}")
                null
            } else {
                lastErr = ""
                body
            }
        } catch (e: Exception) {
            lastErr = "${e.javaClass.simpleName}: ${e.message?.take(60)}"
            android.util.Log.w("PowerApi", "$path failed: $lastErr")
            null
        }
    }

    // ---------- 业务 ----------

    /** 区域（通常只有一个：山东管理学院） */
    fun areas(): List<Area> {
        val xml = get("/getAreaInfo") ?: return emptyList()
        val ids = tags(xml, "AreaID")
        val names = tags(xml, "AreaName")
        return ids.mapIndexed { i, id -> Area(id, names.getOrNull(i) ?: id) }
    }

    /** 楼栋列表 */
    fun buildings(areaId: String): List<Building> {
        val xml = get("/getArchitectureInfo", mapOf("Area_ID" to areaId)) ?: return emptyList()
        val ids = tags(xml, "ArchitectureID")
        val names = tags(xml, "ArchitectureName")
        val tos = tags(xml, "ArchitectureStorys")
        val froms = tags(xml, "ArchitectureBegin")
        return ids.mapIndexed { i, id ->
            Building(
                id,
                names.getOrNull(i) ?: id,
                froms.getOrNull(i)?.toIntOrNull() ?: 1,
                tos.getOrNull(i)?.toIntOrNull() ?: 5,
            )
        }
    }

    /** 某层房间列表 */
    fun rooms(buildingId: String, floor: Int): List<Room> {
        val xml = get("/getRoomInfo",
            mapOf("Architecture_ID" to buildingId, "Floor" to floor.toString())) ?: return emptyList()
        val ids = tags(xml, "RoomNo")
        val names = tags(xml, "RoomName")
        return ids.mapIndexed { i, id -> Room(id, names.getOrNull(i) ?: id) }
    }

    /** 房间下的表具（电表/水表） */
    fun meters(roomId: String): List<Meter> {
        val xml = get("/getMeterInfo", mapOf("Room_ID" to roomId)) ?: return emptyList()
        // meterList 里每个 MeterInfo 的字段
        val blocks = tags(xml, "MeterInfo").ifEmpty { listOf(xml) }
        return blocks.mapNotNull { b ->
            val id = one(b, "meterId")
            if (id.isBlank()) null
            else Meter(id, one(b, "conType"), one(b, "meterType"))
        }
    }

    /** 实时用量（剩余/总示数/状态/闸位） */
    fun usage(meterId: String): Usage? {
        val xml = get("/getReserveHKAM", mapOf("AmMeter_ID" to meterId)) ?: return null
        return Usage(
            remain = one(xml, "remainPower"),
            unit = one(xml, "remainName").ifBlank { one(xml, "unit") },
            total = one(xml, "ZVlaue"),
            state = one(xml, "state"),
            valve = one(xml, "valve"),
            readTime = one(xml, "readTime"),
        )
    }

    /** 最近 N 天每日用量（按日期倒序返回） */
    fun dailyUsage(meterId: String, days: Int = 14): List<DayUsage> {
        val cal = Calendar.getInstance()
        val end = cal.clone() as Calendar
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val f = { c: Calendar -> "${c.get(Calendar.YEAR)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}" }
        val xml = get("/getMeterDayValue", mapOf(
            "AmMeter_ID" to meterId,
            "startDate" to f(cal),
            "endDate" to f(end),
        )) ?: return emptyList()
        val blocks = tags(xml, "DayValueInfo")
        return blocks.mapNotNull { b ->
            val d = one(b, "curDayTime")
            if (d.isBlank()) null
            else DayUsage(d, one(b, "dayValue"), one(b, "dayUseMeony"), one(b, "dw"))
        }
    }
}
