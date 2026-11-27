package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局节流：同一加载器里所有章节请求，开始时间至少间隔 [defaultGapMs]。
 * 跨协程共享一把锁，不是每个协程各算一份间隔。
 */
class CxRequestPacer(
    private val defaultGapMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextAllowedAt: Long? = null

    /** 阻塞到允许发起下一个请求；[gapMs] 是本次要求的最小间隔。 */
    suspend fun acquire(gapMs: Long = defaultGapMs) {
        mutex.withLock {
            val now = nowMs()
            val earliest = nextAllowedAt ?: now
            if (now < earliest) wait(earliest - now)
            nextAllowedAt = maxOf(now, earliest) + gapMs
        }
    }
}

/**
 * 章节任务点加载器：并发上限 [maxParallel]，每个请求前过 [pacer]（全局间隔）。
 *
 * 风控优先：任何一章返回 403（含验证码页）立刻把本轮降级为**串行**——之后每个请求
 * 都要经过同一把锁，且间隔拉长到 [serialGapMs]；本轮不再提升并发。降级不影响
 * 已经开始的请求，但不会有新的并行请求再发出去。
 *
 * 单章失败（网络重试耗尽 [CX_CODE_NETWORK]、mArg 解析失败一类的 502）只把这一章
 * 标错，其余章节继续；只有登录失效（[CX_CODE_AUTH]）才整页上抛。
 */
class CxChapterLoader(
    private val maxParallel: Int = MAX_PARALLEL,
    private val gapMs: Long = GAP_MS,
    private val serialGapMs: Long = SERIAL_GAP_MS,
    private val pacer: CxRequestPacer = CxRequestPacer(GAP_MS),
) {

    /**
     * @param fetch 单章请求。
     * @param onResult 每章完成即回调；顺序不保证，但每章恰好一次。
     * @return 本轮是否发生过风控降级。
     */
    suspend fun load(
        chapters: List<CxChapterChild>,
        fetch: suspend (CxChapterChild) -> List<CxTaskCard>,
        onResult: suspend (index: Int, content: CxChildContent) -> Unit = { _, _ -> },
    ): Boolean {
        val nextIndex = AtomicInteger(0)
        val serial = AtomicBoolean(false)
        val degraded = AtomicBoolean(false)

        suspend fun runOne(chapter: CxChapterChild): CxChildContent {
            pacer.acquire(if (serial.get()) serialGapMs else gapMs)
            return try {
                CxChildContent(chapter.name, fetch(chapter))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CxException) {
                when (e.code) {
                    RISK_CODE -> {
                        serial.set(true)
                        degraded.set(true)
                        CxChildContent(chapter.name, emptyList(), e.message ?: "学习通要求验证")
                    }
                    CX_CODE_NETWORK -> CxChildContent(
                        chapter.name,
                        emptyList(),
                        e.message ?: "网络请求失败",
                    )
                    // 登录失效必须上抛：Repository 已经单飞重登并重试过一次还失败，
                    // 再往下走只会让每一章都显示「读取失败」。
                    CX_CODE_AUTH -> throw e
                    // 其余（mArg 解析失败、结构异常等 502）只算这一章坏了，
                    // 一颗坏章节不能让整页打挂。
                    else -> CxChildContent(
                        chapter.name,
                        emptyList(),
                        e.message ?: "本章读取失败（${e.code}）",
                    )
                }
            }
        }

        // coroutineScope 只负责「等所有工作协程结束」；degraded 必须在它返回后再读。
        coroutineScope {
            val serialGate = Mutex()
            val workers = minOf(maxParallel, chapters.size).coerceAtLeast(1)
            repeat(workers) {
                launch {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= chapters.size) return@launch
                        val chapter = chapters[index]
                        val content = if (serial.get()) {
                            serialGate.withLock { runOne(chapter) }
                        } else {
                            runOne(chapter)
                        }
                        onResult(index, content)
                    }
                }
            }
        }
        return degraded.get()
    }

    companion object {
        /** 并发上限：再多就容易触发风控。 */
        const val MAX_PARALLEL = 3

        /** 正常情况下的全局请求间隔。 */
        const val GAP_MS = 250L

        /** 风控降级后的请求间隔（回到老项目的串行节奏）。 */
        const val SERIAL_GAP_MS = 500L

        /** 学习通要求验证/拦截时的错误码（202 与验证码页都归一到 403）。 */
        const val RISK_CODE = 403
    }
}