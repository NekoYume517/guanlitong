package com.sdmu.guanlitong.data.cx

/**
 * 收件箱数据源；单测用假实现。
 *
 * 固定拉最新首页，不接收翻页游标：`lastValue` 在超星接口里是「取更早一页」的语义，
 * 拿它当同步游标会让每一轮刷新都往历史翻，新通知反而永远拉不到。
 */
interface CxNoticeGateway {
    suspend fun inbox(): CxInboxPage
}

/** 线上实现：走 [CxRepository.withSession]，401 单飞重登后仍失败才上抛。 */
class CxRemoteNoticeGateway(
    private val repo: CxRepository,
) : CxNoticeGateway {

    override suspend fun inbox(): CxInboxPage =
        repo.withSession { CxInboxApi(CxApi(it.cookies)).fetch() }
}