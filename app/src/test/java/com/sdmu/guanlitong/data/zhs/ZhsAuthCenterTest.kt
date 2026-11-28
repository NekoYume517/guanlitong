package com.sdmu.guanlitong.data.zhs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 过期重登协调器：去重、状态流转、代数递增与密码可用性。 */
class ZhsAuthCenterTest {

    @Before
    fun setUp() {
        ZhsAuthCenter.cancel()
        ZhsAuthCenter.bindProvider { ZhsAuthAccount("13800000000", "test-pass") }
    }

    @After
    fun tearDown() = ZhsAuthCenter.cancel()

    @Test
    fun requestReloginFillsAccountAndDeduplicates() {
        ZhsAuthCenter.requestRelogin()
        ZhsAuthCenter.requestRelogin()

        val state = ZhsAuthCenter.state.value
        assertTrue(state.requested)
        assertEquals("13800000000", state.username)
        assertTrue(state.hasStoredPassword)
        assertEquals("13800000000", ZhsAuthCenter.currentUsername())
    }

    @Test
    fun requestWhileBusyKeepsSinglePendingRequest() {
        ZhsAuthCenter.requestRelogin()
        ZhsAuthCenter.begin()
        ZhsAuthCenter.requestRelogin()

        assertTrue(ZhsAuthCenter.state.value.busy)
        assertTrue(ZhsAuthCenter.state.value.requested)
    }

    @Test
    fun completeClearsStateAndIncrementsGeneration() {
        val start = ZhsAuthCenter.generation.value
        ZhsAuthCenter.requestRelogin()
        ZhsAuthCenter.begin()
        ZhsAuthCenter.complete()

        val state = ZhsAuthCenter.state.value
        assertFalse(state.requested)
        assertFalse(state.busy)
        assertNull(state.error)
        assertEquals(start + 1, ZhsAuthCenter.generation.value)
    }

    @Test
    fun failKeepsDialogForRetry() {
        ZhsAuthCenter.requestRelogin()
        ZhsAuthCenter.begin()
        ZhsAuthCenter.fail("密码错误")

        val state = ZhsAuthCenter.state.value
        assertTrue(state.requested)
        assertFalse(state.busy)
        assertEquals("密码错误", state.error)
    }

    @Test
    fun cancelClosesWithoutTouchingGeneration() {
        val start = ZhsAuthCenter.generation.value
        ZhsAuthCenter.requestRelogin()
        ZhsAuthCenter.cancel()

        assertFalse(ZhsAuthCenter.state.value.requested)
        assertEquals(start, ZhsAuthCenter.generation.value)
    }

    @Test
    fun noStoredPasswordFallsBackToManualInput() {
        ZhsAuthCenter.bindProvider { ZhsAuthAccount("13800000000", null) }
        ZhsAuthCenter.requestRelogin()

        assertTrue(ZhsAuthCenter.state.value.requested)
        assertFalse(ZhsAuthCenter.state.value.hasStoredPassword)
        assertNull(ZhsAuthCenter.storedPassword())
    }

    @Test
    fun providerFailureDoesNotBlockRequest() {
        ZhsAuthCenter.bindProvider { throw IllegalStateException("no account") }
        ZhsAuthCenter.requestRelogin()

        assertTrue(ZhsAuthCenter.state.value.requested)
        assertEquals("", ZhsAuthCenter.state.value.username)
        assertFalse(ZhsAuthCenter.state.value.hasStoredPassword)
    }

    @Test
    fun rememberedUsernameReadsProviderWithoutOpeningDialog() {
        ZhsAuthCenter.bindProvider { ZhsAuthAccount("13800000000", null) }

        assertEquals("13800000000", ZhsAuthCenter.rememberedUsername())
        assertEquals("13800000000", ZhsAuthCenter.rememberedAccount().username)
        assertFalse(ZhsAuthCenter.state.value.requested)
    }

    @Test
    fun rememberedUsernameIsEmptyWhenProviderFails() {
        ZhsAuthCenter.bindProvider { throw IllegalStateException("no account") }

        assertEquals("", ZhsAuthCenter.rememberedUsername())
        assertEquals("", ZhsAuthCenter.rememberedAccount().username)
    }
}