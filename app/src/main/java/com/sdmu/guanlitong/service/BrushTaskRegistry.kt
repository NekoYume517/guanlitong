package com.sdmu.guanlitong.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 刷课任务来源；标签直接用在浮球展开列表里。新模块加来源即可，注册表不认具体字段。 */
enum class BrushTaskSource(val label: String) {
    /** 学习通章节刷课（视频/音频）。 */
    CX_STUDY("学习通"),

    /** 学习通资料刷时长（另一会话接入）。 */
    CX_MATERIAL("学习通"),

    /** 知到/智慧树定时刷课。 */
    ZHS_STUDY("知到"),
}

/**
 * 进度页路由 id。注册表只存字符串，由 AppRoot 解析成具体页面；
 * 不认识的 id 只展示、不跳转，方便别的模块先注册再补路由。
 */
object BrushTaskRoutes {
    const val CX_STUDY = "cx.study"
    const val CX_MATERIAL = "cx.material"
    const val ZHS_STUDY = "zhs.study"
}

/**
 * 一个正在跑的刷课任务的对外快照（浮球/列表/通知共用）。
 *
 * - [id] 全局唯一，重复 register 视为覆盖；
 * - [progress] 0..1，浮球进度条与列表都用它；
 * - [running] false 的任务保留到业务方 unregister 为止（例如正在收尾）；
 * - [startedAt] 用于展开列表排序（运行中优先，其次先开始的在上）。
 */
data class BrushTaskSnapshot(
    val id: String,
    val source: BrushTaskSource,
    val title: String,
    val subtitle: String = "",
    val progress: Float = 0f,
    val statusText: String = "",
    val running: Boolean = true,
    val startedAt: Long = 0L,
    val route: String = "",
)

/**
 * 多刷课任务的共享注册表：知到、学习通章节、学习通资料各自注册自己的任务，
 * 互不取消、互不覆盖状态。浮球只订阅这一份快照列表。
 *
 * 约定：任务结束（含达标/停止/异常）后，业务方必须 [unregister]，
 * 球和徽标不残留；终态系统通知由各业务方自己发，与注册表无关。
 */
object BrushTaskRegistry {

    private val _tasks = MutableStateFlow<List<BrushTaskSnapshot>>(emptyList())

    /** 已排序：运行中优先，其次按开始时间升序。 */
    val tasks: StateFlow<List<BrushTaskSnapshot>> = _tasks.asStateFlow()

    /** 球右上角徽标数字：仍在跑或有进度的任务数。 */
    fun badgeCount(tasks: List<BrushTaskSnapshot>): Int =
        tasks.count { it.running || it.progress > 0f }

    /** 只有一个任务时点球直达它；多个任务走展开列表。 */
    fun directTarget(tasks: List<BrushTaskSnapshot>): BrushTaskSnapshot? =
        tasks.singleOrNull()

    fun register(snapshot: BrushTaskSnapshot) {
        _tasks.update { list ->
            (list.filterNot { it.id == snapshot.id } + snapshot).sorted()
        }
    }

    fun update(snapshot: BrushTaskSnapshot) {
        _tasks.update { list ->
            if (list.none { it.id == snapshot.id }) return@update list
            list.map { if (it.id == snapshot.id) snapshot else it }.sorted()
        }
    }

    fun unregister(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() {
        _tasks.value = emptyList()
    }

    private fun List<BrushTaskSnapshot>.sorted(): List<BrushTaskSnapshot> =
        sortedWith(
            compareByDescending<BrushTaskSnapshot> { it.running }
                .thenBy { it.startedAt },
        )
}