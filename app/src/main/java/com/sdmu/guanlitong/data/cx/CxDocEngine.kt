package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 阅读高度与时间戳的计算，逐行对照老项目 `run()`（342–396）：
 *
 * - 时间戳：`startTs + i × speed × 5s`（加速只改时间，不改真实等待）；
 * - 高度：`pages×100` 封顶；未满时按 `pages×100 × i×speed×5 / 3600` 线性增长，
 *   满后每次在当前值上随机抖动 ±50，模拟反复阅读。
 */
internal object CxDocHeight {

    const val SECONDS_PER_BEAT = 5

    fun timestampMs(startTs: Long, beat: Int, speed: Double): Long =
        startTs + (beat * speed * SECONDS_PER_BEAT * 1000).toLong()

    fun height(
        beat: Int,
        speed: Double,
        maxHeight: Int,
        lastHeight: Int,
        jitter: () -> Int,
    ): Int {
        if (maxHeight <= 0) return 0
        val base = (maxHeight * (beat * speed * SECONDS_PER_BEAT / 3600.0)).toInt()
        return if (base >= maxHeight) {
            (lastHeight + jitter()).coerceIn(0, maxHeight)
        } else {
            base.coerceAtMost(maxHeight)
        }
    }
}

/**
 * 资料刷时长的执行器：更新阅读次数 → 起始心跳 → 每 5 秒一次心跳。
 *
 * 循环是开放式的（老项目用 Ctrl+C 结束），由调用方取消协程停止；取消时会补发
 * 一次 `h=满分` 的收尾心跳（老项目 `finally` 语义），异常退出则不补，避免把失败
 * 伪装成阅读完成。
 *
 * 心跳连续失败（非 200）3 次才中止；302/401 交回登录失效，202/403 交回风控，
 * 与其它学习通接口一致，不上报任何正文或签名。
 */
class CxDocEngine(
    private val gateway: CxDocGateway,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Int = { Random.nextInt(-50, 51) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(
        course: CxDocCourse,
        item: CxDocItem,
        speed: Double,
        onProgress: (CxDocProgress) -> Unit = {},
    ) {
        if (item.isFolder) throw CxException("文件夹不能刷时长", 400)
        if (speed < MIN_SPEED) throw CxException("倍速不能小于 $MIN_SPEED", 400)

        val fallbackId = item.objectId.ifBlank { item.id }
        val meta = try {
            gateway.meta(fallbackId)
        } catch (e: CxException) {
            if (e.code == CX_CODE_AUTH || e.code == CX_CODE_RISK) throw e
            Log.w(TAG, "meta 读取失败，按默认页数继续：${e.javaClass.simpleName} code=${e.code}")
            CxDocMeta()
        }
        val objectId = meta.objectId.ifBlank { fallbackId }
        val pages = meta.pageNum.takeIf { it > 0 } ?: DEFAULT_PAGES
        val wordCount = meta.wordCount.takeIf { it > 0 } ?: DEFAULT_WORD_COUNT
        val maxHeight = pages * 100
        val fileName = meta.filename.ifBlank { item.name }
        Log.i(
            TAG,
            "start course=${course.name} file=$fileName pages=$pages words=$wordCount " +
                "speed=$speed dataId=${item.id} objectId=$objectId",
        )

        runCatching { gateway.updateReadCount(course, item.id) }
            .onFailure { Log.w(TAG, "update-read-count 失败：${it.javaClass.simpleName}") }

        val startTs = nowMs()
        var lastBeat = 0
        var lastHeight = 0

        fun beatOf(state: Int, height: Int, beatIndex: Int) = CxDocBeat(
            courseId = course.courseId,
            clazzId = course.clazzId,
            dataId = item.id,
            objectId = objectId,
            wordCount = wordCount,
            pageNum = pages,
            state = state,
            height = height,
            timestampMs = CxDocHeight.timestampMs(startTs, beatIndex, speed),
            fileEnc = course.enc,
        )

        try {
            // 起始心跳：s=1、h=0，时间戳就是开始时间。
            checkBeat(gateway.beat(course, beatOf(1, 0, 0)))
            var beatIndex = 0
            var failures = 0
            while (true) {
                beatIndex++
                val height = CxDocHeight.height(beatIndex, speed, maxHeight, lastHeight, jitter)
                val status = gateway.beat(course, beatOf(2, height, beatIndex))
                lastBeat = beatIndex
                lastHeight = height
                failures = if (status == 200) {
                    0
                } else {
                    Log.w(TAG, "读取心跳 HTTP $status beat=$beatIndex")
                    failures + 1
                }
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    throw CxException("阅读心跳连续失败（最后 HTTP $status）", 502)
                }
                onProgress(
                    CxDocProgress(
                        beat = beatIndex,
                        reportedSeconds = (beatIndex * speed * CxDocHeight.SECONDS_PER_BEAT).toLong(),
                        height = height,
                        maxHeight = maxHeight,
                        pageNum = pages,
                        speed = speed,
                    ),
                )
                pause(CxDocHeight.SECONDS_PER_BEAT * 1000L)
            }
        } catch (e: CancellationException) {
            // 用户停止/服务销毁：照老项目补一次满进度收尾心跳。取消中的挂起请求
            // 必须放在 NonCancellable 里，否则会立刻再抛 CancellationException。
            withContext(NonCancellable) {
                runCatching { gateway.beat(course, beatOf(2, maxHeight, lastBeat)) }
                    .onFailure { Log.w(TAG, "收尾心跳失败：${it.javaClass.simpleName}") }
            }
            Log.i(TAG, "stopped file=$fileName beats=$lastBeat reported=${lastBeat * speed * 5}s")
            throw e
        }
    }

    private fun checkBeat(status: Int) {
        when (status) {
            200 -> Unit
            301, 302, 303, 307, 308, 401 ->
                throw CxException("登录已失效，请重新登录", CX_CODE_AUTH)
            202, 403 -> throw CxException("学习通要求验证，请稍后再试", CX_CODE_RISK)
            else -> Log.w(TAG, "起始心跳 HTTP $status")
        }
    }

    companion object {
        private const val TAG = "CxDocEngine"
        private const val MIN_SPEED = 0.1
        private const val DEFAULT_PAGES = 100
        private const val DEFAULT_WORD_COUNT = 1659

        /** 老项目不对单次心跳失败做处理；这里连错 3 次才停，避免网络抖动误杀长任务。 */
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}