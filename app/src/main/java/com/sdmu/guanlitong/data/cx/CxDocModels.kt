package com.sdmu.guanlitong.data.cx

import com.sdmu.guanlitong.service.BrushTaskRegistry
import com.sdmu.guanlitong.service.BrushTaskRoutes
import com.sdmu.guanlitong.service.BrushTaskSnapshot
import com.sdmu.guanlitong.service.BrushTaskSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 资料刷时长的一整套模型，逐项对照老项目
 * `D:\code\chaoxing-tools\old\chaoxing-study-tool\main.py`
 * （`get_course_detail` / `class Browser` / `meta` / `hb` / `run`）。
 */

/** 课程中间页（stucoursemiddle）里的资料参数。 */
data class CxDocParams(
    val enc: String = "",
    val cpi: String = "",
    val openc: String = "",
)

/** 资料目录里的一个条目（`<ul class="dataBody_td" ...>`）。 */
data class CxDocItem(
    val name: String,
    val type: String,
    val id: String,
    val objectId: String = "",
    val size: String = "",
    val creator: String = "",
) {
    /** 老项目用 `type=afolder` 表示可进入的文件夹。 */
    val isFolder: Boolean get() = type == "afolder"
}

/**
 * 一次目录请求的结果。
 *
 * [enc] 是响应页面里 hidden input 带回来的文件系统 enc，进入子目录后可能变化；
 * 请求下一层时要带上最新的值，空时沿用调用方手里的。
 */
data class CxDocPage(
    val items: List<CxDocItem>,
    val enc: String = "",
)

/** `ananas/status` 的文件元信息；缺字段由调用方补默认值（页数 100 / 字数 1659）。 */
data class CxDocMeta(
    val objectId: String = "",
    val pageNum: Int = 0,
    val wordCount: Int = 0,
    val filename: String = "",
)

/** 资料所在的课程 + 已解析好的参数，一次刷时长全程不变。 */
data class CxDocCourse(
    val courseId: String,
    val clazzId: String,
    val personId: String,
    val cpi: String,
    val enc: String,
    val name: String = "",
)

/** 目录导航栈里的一层：文件夹名、id 与它的父目录 id（根目录为空）。 */
data class CxDocFolder(val name: String, val id: String, val parentId: String = "")

/**
 * 一次心跳要上报的内容，对应老项目 `hb()` 里的 `d`（不含 uid / enc）。
 *
 * [state] 1=开始、2=进行中；[height] 是阅读高度（页数×100 为满），
 * [timestampMs] 是伪造的阅读时间戳（按倍速推进）。
 */
data class CxDocBeat(
    val courseId: String,
    val clazzId: String,
    val dataId: String,
    val objectId: String,
    val wordCount: Int,
    val pageNum: Int,
    val state: Int,
    val height: Int,
    val timestampMs: Long,
    val fileEnc: String = "",
)

/** 一次心跳之后的进度快照，服务与 UI 共用。 */
data class CxDocProgress(
    val beat: Int,
    val reportedSeconds: Long,
    val height: Int,
    val maxHeight: Int,
    val pageNum: Int,
    val speed: Double,
)

/** 刷资料时长的实时状态，前台服务写、UI 读（不复用刷课/签到的 [CxTaskStatus]）。 */
data class CxDocTaskState(
    val running: Boolean = false,
    /** 当前任务的课程上下文；浮球点回资料页时用它还原路由。 */
    val course: CxDocCourse? = null,
    val fileName: String = "",
    val dataId: String = "",
    val objectId: String = "",
    val speed: Double = 1.0,
    val reportedSeconds: Long = 0L,
    val pageNum: Int = 0,
    val height: Int = 0,
    val maxHeight: Int = 0,
    val message: String = "",
    val fatalError: String? = null,
    val startedAt: Long = 0L,
) {
    val courseName: String get() = course?.name.orEmpty()

    val reportedMinutes: Double get() = reportedSeconds / 60.0

    val heightPercent: Int
        get() = if (maxHeight <= 0) 0 else (height * 100L / maxHeight).toInt().coerceIn(0, 100)

    /** 浮球进度：阅读高度 / 满高度，夹在 0..1；还没拿到页数时保持 0。 */
    val progress: Float
        get() = if (maxHeight <= 0) 0f else (height.toFloat() / maxHeight).coerceIn(0f, 1f)

    fun speedLabel(): String =
        if (speed == speed.toLong().toDouble()) "${speed.toLong()}x"
        else String.format(Locale.CHINA, "%.1fx", speed)
}

/**
 * 资料任务的状态流，同时把运行中的任务镜像到共享 [BrushTaskRegistry]：
 * 浮球据此显示「课程 · 资料」与进度，结束（完成/停止/失败）立即移除，徽标不残留。
 *
 * 一次只跑一个文件，注册 id 用 `cx.material.<objectId|dataId>`，同一文件重跑视为覆盖。
 */
object CxDocTaskStatus {

    private val _state = MutableStateFlow(CxDocTaskState())
    val state: StateFlow<CxDocTaskState> = _state.asStateFlow()

    private var registryId: String? = null

    internal fun start(
        course: CxDocCourse?,
        item: CxDocItem,
        speed: Double,
        pageNum: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        _state.value = CxDocTaskState(
            running = true,
            course = course,
            fileName = item.name,
            dataId = item.id,
            objectId = item.objectId,
            speed = speed,
            pageNum = pageNum,
            maxHeight = pageNum * 100,
            message = "准备中",
            startedAt = nowMs,
        )
        publish()
    }

    internal fun update(transform: (CxDocTaskState) -> CxDocTaskState) {
        _state.value = transform(_state.value)
        publish()
    }

    internal fun finish(message: String, fatalError: String? = null) {
        _state.value = _state.value.copy(
            running = false,
            message = message,
            fatalError = fatalError,
        )
        publish()
    }

    private fun publish() {
        val snapshotState = _state.value
        if (!snapshotState.running) {
            registryId?.let(BrushTaskRegistry::unregister)
            registryId = null
            return
        }
        val id = "cx.material." +
            snapshotState.objectId.ifBlank { snapshotState.dataId }.ifBlank { "unknown" }
        if (registryId != null && registryId != id) {
            registryId?.let(BrushTaskRegistry::unregister)
        }
        registryId = id
        BrushTaskRegistry.register(
            BrushTaskSnapshot(
                id = id,
                source = BrushTaskSource.CX_MATERIAL,
                title = listOf(snapshotState.courseName, snapshotState.fileName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "学习通资料" },
                subtitle = buildString {
                    append("已上报 ").append(formatMinutes(snapshotState.reportedSeconds))
                    append(" 分钟 · ").append(snapshotState.pageNum).append(" 页")
                    append(" · ").append(snapshotState.speedLabel())
                },
                progress = snapshotState.progress,
                statusText = snapshotState.fatalError
                    ?: snapshotState.message.ifBlank {
                        "已上报 ${formatMinutes(snapshotState.reportedSeconds)} 分钟"
                    },
                running = snapshotState.running,
                startedAt = snapshotState.startedAt,
                route = BrushTaskRoutes.CX_MATERIAL,
            ),
        )
    }

    private fun formatMinutes(seconds: Long): String =
        String.format(Locale.CHINA, "%.1f", seconds / 60.0)
}