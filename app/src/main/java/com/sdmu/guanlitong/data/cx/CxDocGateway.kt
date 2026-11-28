package com.sdmu.guanlitong.data.cx

import android.util.Log

/**
 * 资料刷时长执行器依赖的全部网络能力；单测用假实现，UI 与服务走 [CxDocApi]。
 *
 * 每个方法独立走 [CxRepository.withSession]：长跑到一半会话过期时由 Repository
 * 单飞重登，不需要在这里攒一个大事务。
 */
interface CxDocGateway {
    suspend fun params(courseId: String, clazzId: String, personId: String): CxDocParams
    suspend fun list(course: CxDocCourse, folder: CxDocFolder?): CxDocPage
    suspend fun meta(objectId: String): CxDocMeta
    suspend fun updateReadCount(course: CxDocCourse, dataId: String)
    suspend fun beat(course: CxDocCourse, beat: CxDocBeat): Int
}

class CxRemoteDocGateway(private val repo: CxRepository) : CxDocGateway {

    override suspend fun params(courseId: String, clazzId: String, personId: String): CxDocParams =
        repo.withSession { session ->
            CxDocApi(CxApi(session.cookies)).params(courseId, clazzId, personId)
        }

    override suspend fun list(course: CxDocCourse, folder: CxDocFolder?): CxDocPage =
        repo.withSession { session ->
            CxDocApi(CxApi(session.cookies))
                .list(course.courseId, course.clazzId, course.cpi, course.enc, folder)
        }

    override suspend fun meta(objectId: String): CxDocMeta =
        repo.withSession { session -> CxDocApi(CxApi(session.cookies)).meta(objectId) }

    override suspend fun updateReadCount(course: CxDocCourse, dataId: String) {
        repo.withSession { session ->
            CxDocApi(CxApi(session.cookies))
                .updateReadCount(course.courseId, course.clazzId, course.cpi, dataId)
        }
    }

    override suspend fun beat(course: CxDocCourse, beat: CxDocBeat): Int =
        repo.withSession { session ->
            val api = CxDocApi(CxApi(session.cookies))
            val uid = session.uid.ifBlank { api.uid }
            if (uid.isBlank()) {
                Log.w(TAG, "缺少 uid，无法签名心跳")
                throw CxException("学习通会话缺少 UID，请重新登录", CX_CODE_AUTH)
            }
            api.beat(beat, uid)
        }

    private companion object {
        const val TAG = "CxRemoteDocGateway"
    }
}