package com.sdmu.guanlitong.data.cx

/**
 * 签到页「进行中」区域此刻该显示什么。
 *
 * 优先级：未登录 > 有数据 > 正在扫描 > 空态。扫描失败但没有数据时落在 EMPTY，
 * 由页面把失败文案放进空态，而不是再叠一条错误行；有旧数据就继续展示旧数据。
 */
enum class CxSignListState { NEEDS_LOGIN, LOADING, EMPTY, LIST }

/**
 * 签到页的纯展示状态，由登录态、实时提醒开关、扫描进度和条数算出。
 *
 * 实时提醒开关不改变「进行中」的展示：关着只是不再自动轮询/提醒，手动进入页面
 * 仍可以扫描一次。所以它只影响状态条上的「开/关 + 去开启」。
 */
data class CxSignPageState(
    val loggedIn: Boolean,
    val monitorEnabled: Boolean,
    val scanning: Boolean,
    val openCount: Int,
    val scanFailed: Boolean,
    val historyCount: Int,
) {
    val listState: CxSignListState = when {
        !loggedIn -> CxSignListState.NEEDS_LOGIN
        openCount > 0 -> CxSignListState.LIST
        scanning -> CxSignListState.LOADING
        else -> CxSignListState.EMPTY
    }

    /** 未登录时整页只给登录提示，不显示空态与历史。 */
    val showLoginRequired: Boolean get() = listState == CxSignListState.NEEDS_LOGIN

    /** 状态条只在已登录时显示「签到实时提醒：开/关」。 */
    val showMonitorRow: Boolean get() = loggedIn

    /** 直播提醒关着时给「去开启」。 */
    val showMonitorOffAction: Boolean get() = loggedIn && !monitorEnabled

    /** 「进行中」的空态文案；有数据或还在扫描时为 null，避免和列表叠在一起。 */
    val openEmptyText: String?
        get() = if (listState == CxSignListState.EMPTY) {
            if (scanFailed) "签到扫描失败，下拉重试" else "暂无进行中的签到"
        } else {
            null
        }

    /** 「还没有签到记录」只在已登录且确实没有记录时显示。 */
    val historyEmpty: Boolean
        get() = loggedIn && historyCount == 0
}

fun cxSignPageState(
    loggedIn: Boolean,
    monitorEnabled: Boolean,
    scanning: Boolean,
    openCount: Int,
    scanFailed: Boolean,
    historyCount: Int,
): CxSignPageState = CxSignPageState(
    loggedIn = loggedIn,
    monitorEnabled = monitorEnabled,
    scanning = scanning,
    openCount = openCount,
    scanFailed = scanFailed,
    historyCount = historyCount,
)

/**
 * 进行中签到卡片右下角的动作文案。
 *
 * 手势活动后台永远不会自动提交（每次图案都不同），所以无论自动开关如何都提示
 * 「点击手划」；其余活动开着自动签到就交给后台，已经成功过的显示「已自动签到」，
 * 避免让用户重复手点。
 */
fun cxSignActionLabel(
    gesture: Boolean,
    autoEnabled: Boolean,
    recordStatus: CxSignRecordStatus?,
): String = when {
    gesture -> "点击手划签到"
    !autoEnabled -> "点击去处理"
    recordStatus == CxSignRecordStatus.SUCCESS -> "已自动签到"
    recordStatus == CxSignRecordStatus.GAVE_UP -> "自动失败，点击手动处理"
    else -> "自动处理中"
}