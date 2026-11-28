package com.sdmu.guanlitong.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sdmu.guanlitong.data.zhs.ZhsRepository

/**
 * 知到「登录过期」链路的调试入口，只随 debug 构建打包。
 *
 * `EXPIRE_ZHS_SESSION`：清掉本机知到会话但保留账号与记住的密码。下一次知到接口
 * 调用就会走真实的 `ZhsErrorKind.EXPIRED` 分支，用来在真机上复核自动重登弹窗，
 * 不需要等线上 401。release 构建里没有这个类，也没有清单注册。
 */
class ZhsReloginDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPIRE) return
        ZhsRepository(context.applicationContext).logout(clearPassword = false)
    }

    companion object {
        const val ACTION_EXPIRE = "com.sdmu.guanlitong.debug.EXPIRE_ZHS_SESSION"
    }
}