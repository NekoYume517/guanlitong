package com.sdmu.guanlitong.service

/**
 * 开机 / 应用更新后恢复签到监控的结果。
 */
enum class CxSignBootOutcome {
    /** 未登录，或「签到实时提醒」总开关关着：什么都不做。 */
    SKIPPED,

    /** 已请求启动前台服务。 */
    STARTED,

    /**
     * 系统或 ROM 不允许后台启动前台服务（Android 12+ 的
     * `ForegroundServiceStartNotAllowedException`，以及部分 ROM 抛出的同族
     * `IllegalStateException`）。调用方可退化为一次性任务再试一次。
     */
    START_DENIED,

    /** 其它异常（例如权限、初始化问题）：只记日志，不再重试。 */
    FAILED,
}

/**
 * 开机 / 应用更新后是否拉起签到监控的纯逻辑，不依赖 Android 运行时。
 *
 * 判断顺序与 App 内一致：先看 [monitorEnabled]，再看 [loggedIn]，都满足才调用
 * [startService]。启动被系统拒绝时返回 [CxSignBootOutcome.START_DENIED]，让接收器
 * 决定是否交给 WorkManager 再试一次；一次失败绝不在这里循环重试。
 *
 * @param monitorEnabled 实时提醒总开关（默认开）。
 * @param loggedIn 本机是否有可用的学习通会话。
 * @param startService 实际拉起 [CxTaskService]。
 * @param isStartDenied 识别「后台启动前台服务被拒」；默认把
 *   `IllegalStateException` 全族都算进来（Android 12+ 的拒绝、Android 14 的缺失
 *   FGS 类型、部分 ROM 的同类抛错都在这个族里）。
 */
class CxSignBootRestore(
    private val monitorEnabled: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val startService: () -> Unit,
    private val isStartDenied: (Throwable) -> Boolean = { it is IllegalStateException },
) {

    fun run(): CxSignBootOutcome {
        // 读 SharedPreferences / 会话本身也可能抛（存储损坏、Keystore 异常），
        // 接收器不能因此崩溃，也不能误判成「被拒」去排兜底任务。
        val eligible = try {
            monitorEnabled() && loggedIn()
        } catch (e: Throwable) {
            return CxSignBootOutcome.FAILED
        }
        if (!eligible) return CxSignBootOutcome.SKIPPED

        return try {
            startService()
            CxSignBootOutcome.STARTED
        } catch (e: Throwable) {
            if (isStartDenied(e)) CxSignBootOutcome.START_DENIED else CxSignBootOutcome.FAILED
        }
    }
}

/**
 * 接收器只认这两个系统广播：开机完成、应用被新版本替换。
 *
 * 不接 `QUICKBOOT_POWERON` 等厂商私有重启广播：口径不统一，且会带来重复启动。
 */
object CxBootActions {
    const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    fun handles(action: String?): Boolean =
        action == BOOT_COMPLETED || action == MY_PACKAGE_REPLACED
}