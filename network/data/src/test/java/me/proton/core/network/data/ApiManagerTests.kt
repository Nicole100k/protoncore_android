/*
 * Copyright (c) 2020 Proton Technologies AG
 * This file is part of Proton Technologies AG and ProtonCore.
 *
 * ProtonCore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonCore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonCore.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.proton.core.network.data

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import me.proton.core.network.data.di.ApiFactory
import me.proton.core.network.data.util.MockApiClient
import me.proton.core.network.data.util.MockNetworkManager
import me.proton.core.network.data.util.MockNetworkPrefs
import me.proton.core.network.data.util.MockUserData
import me.proton.core.network.data.util.TestResult
import me.proton.core.network.data.util.TestRetrofitApi
import me.proton.core.network.domain.ApiBackend
import me.proton.core.network.domain.ApiManager
import me.proton.core.network.domain.ApiManagerImpl
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.DohApiHandler
import me.proton.core.network.domain.DohProvider
import me.proton.core.network.domain.DohService
import me.proton.core.network.domain.NetworkPrefs
import me.proton.core.network.domain.NetworkStatus
import me.proton.core.network.domain.ProtonForceUpdateHandler
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
internal class ApiManagerTests {

    private val baseUrl = "https://primary.com/"
    private val proxy1url = "https://proxy1.com/"
    private val success5foo = ApiResult.Success(TestResult(5, "foo"))

    private lateinit var apiFactory: ApiFactory

    private lateinit var apiClient: MockApiClient
    private lateinit var user: MockUserData
    private lateinit var networkManager: MockNetworkManager
    private lateinit var apiManager: ApiManager<TestRetrofitApi>
    private lateinit var dohApiHandler: DohApiHandler<TestRetrofitApi>

    @MockK private lateinit var backend: ProtonApiBackend<TestRetrofitApi>
    @MockK private lateinit var altBackend1: ProtonApiBackend<TestRetrofitApi>
    @MockK private lateinit var dohService: DohService

    private var time = 0L
    private var wallTime = 0L

    private lateinit var prefs: NetworkPrefs

    @BeforeTest
    fun before() {
        MockKAnnotations.init(this)
        time = 0L

        prefs = MockNetworkPrefs()
        apiClient = MockApiClient()
        networkManager = MockNetworkManager()
        networkManager.networkStatus = NetworkStatus.Unmetered

        val scope = CoroutineScope(TestCoroutineDispatcher())
        apiFactory = ApiFactory(baseUrl, apiClient, networkManager, prefs, scope)

        user = MockUserData()

        coEvery { dohService.getAlternativeBaseUrls(any()) } returns listOf(proxy1url)
        val dohProvider = DohProvider(baseUrl, apiClient, listOf(dohService), scope, prefs, ::time)
        dohApiHandler = DohApiHandler(apiClient, backend, dohProvider, prefs, ::wallTime) {
            altBackend1
        }
        ApiManagerImpl.failRequestBeforeTimeMs = Long.MIN_VALUE
        apiManager = ApiManagerImpl(apiClient, backend, dohApiHandler, networkManager,
            apiFactory.createBaseErrorHandlers(user, ::time, scope), ::time)

        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Success(TestResult(5, "foo"))
        every { altBackend1.baseUrl } returns proxy1url
    }

    @Test
    fun `test basic call`() = runBlockingTest {
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Success)
        assertEquals(5, result.value.number)
        assertEquals("foo", result.value.string)
    }

    @Test
    fun `test no internet`() = runBlockingTest {
        networkManager.networkStatus = NetworkStatus.Disconnected
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error.NoInternet)
    }

    @Test
    fun `test retry`() = runBlockingTest {
        apiClient.shouldUseDoh = false
        apiClient.backoffRetryCount = 2
        coEvery { backend.invoke<TestResult>(any()) } returnsMany listOf(
            ApiResult.Error.Timeout(true),
            ApiResult.Error.Timeout(true),
            ApiResult.Success(TestResult(5, "foo")))
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Success)
        assertEquals(5, result.value.number)
    }

    @Test
    fun `test too many retries`() = runBlockingTest {
        apiClient.shouldUseDoh = false
        apiClient.backoffRetryCount = 1
        coEvery { backend.invoke<TestResult>(any()) } returnsMany listOf(
            ApiResult.Error.Timeout(true),
            ApiResult.Error.Timeout(true),
            ApiResult.Success(TestResult(5, "foo")))
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error.Timeout)
    }

    @Test
    fun `test force no retry`() = runBlockingTest {
        apiClient.shouldUseDoh = false
        coEvery { backend.invoke<TestResult>(any()) } returnsMany listOf(
            ApiResult.Error.Timeout(true),
            ApiResult.Success(TestResult(5, "foo")))
        val result = apiManager.invoke(forceNoRetryOnConnectionErrors = true) { test() }
        assertTrue(result is ApiResult.Error.Timeout)
    }

    @Test
    fun `test token refresh`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } answers {
            if (user.accessToken == "new_access_token" && user.refreshToken == "new_refresh_token")
                ApiResult.Success(TestResult(5, "foo"))
            else
                ApiResult.Error.Http(401, "Unauthorized")
        }
        coEvery { backend.refreshTokens() } returns
                ApiResult.Success(ApiBackend.Tokens(refresh = "new_refresh_token", access = "new_access_token"))
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `test token refresh on concurrent calls`() = runBlockingTest {
        var count401 = 0
        coEvery { backend.invoke<TestResult>(any()) } coAnswers {
            if (user.accessToken == "new_access_token" && user.refreshToken == "new_refresh_token")
                ApiResult.Success(TestResult(5, "foo"))
            else {
                count401++
                delay(100)
                ApiResult.Error.Http(401, "Unauthorized")
            }
        }
        coEvery { backend.refreshTokens() } coAnswers {
            ApiResult.Success(ApiBackend.Tokens(refresh = "new_refresh_token", access = "new_access_token"))
        }

        // concurrent execution of 2 calls, both will receive 401 and eventually succeed but only
        // one should refresh token
        val result1 = async { apiManager.invoke { test() } }
        val result2 = async { apiManager.invoke { test() } }

        assertTrue(result1.await() is ApiResult.Success)
        assertTrue(result2.await() is ApiResult.Success)
        assertEquals(2, count401)
        coVerify(exactly = 1) {
            backend.refreshTokens()
        }
    }

    @Test
    fun `test failed token refresh`() = runBlockingTest {
        val oldAccessToken = user.accessToken
        coEvery { backend.invoke<TestResult>(any()) } answers {
            if (user.accessToken == oldAccessToken)
                ApiResult.Error.Http(401, "Unauthorized")
            else
                ApiResult.Success(TestResult(5, "foo"))
        }
        coEvery { backend.refreshTokens() } returns
                ApiResult.Error.Http(400, "")
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error)
        assertEquals(true, user.loggedOut)
    }

    @Test
    fun `test old request token refresh`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } answers {
            if (user.accessToken == "new_access_token" && user.refreshToken == "new_refresh_token")
                ApiResult.Success(TestResult(5, "foo"))
            else
                ApiResult.Error.Http(401, "Unauthorized")
        }
        coEvery { backend.refreshTokens() } returns
                ApiResult.Success(ApiBackend.Tokens(refresh = "new_refresh_token", access = "new_access_token"))

        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Success)

        // Simulate request that started before first and gets 401 after token refresh finished
        time = -1
        coEvery { backend.invoke<TestResult>(any()) } returnsMany listOf(
            ApiResult.Error.Http(401, "Unauthorized"),
            ApiResult.Success(TestResult(5, "foo"))
        )
        val result2 = apiManager.invoke { test() }
        assertTrue(result2 is ApiResult.Success)

        // Token should be refreshed only for the first request
        coVerify(exactly = 1) {
            backend.refreshTokens()
        }
    }

    @Test
    fun `test force update`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns
                ApiResult.Error.Http(400, "",
                    ApiResult.Error.ProtonData(ProtonForceUpdateHandler.ERROR_CODE_FORCE_UPDATE, ""))
        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error)
        assertEquals(true, apiClient.forceUpdated)
    }

    @Test
    fun `test too many requests`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returnsMany listOf(
                ApiResult.Error.TooManyRequest(5),
                ApiResult.Success(TestResult(5, "foo"))
        )

        time = 0

        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error.TooManyRequest)

        val result2 = apiManager.invoke { test() }
        assertTrue(result2 is ApiResult.Error.TooManyRequest)

        time = 5001

        val result3 = apiManager.invoke { test() }
        assertTrue(result3 is ApiResult.Success)
    }

    @Test
    fun `basic doh scenario`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Timeout(true)
        coEvery { backend.isPotentiallyBlocked() } returns true
        coEvery { altBackend1.invoke<TestResult>(any()) } returns success5foo

        val result1 = apiManager.invoke { test() }
        assertTrue(result1 is ApiResult.Success)
        assertEquals(altBackend1, dohApiHandler.activeAltBackend)

        val result2 = apiManager.invoke { test() }
        assertTrue(result2 is ApiResult.Success)
        // There was no call to primary backend as altBackend1 is active
        coVerify(exactly = 1) {
            backend.invoke<TestResult>(any())
        }

        // After proxy is no longer valid, attempt primary backend again
        wallTime += apiClient.proxyValidityPeriodMs
        assertNull(dohApiHandler.activeAltBackend)
        apiManager.invoke { test() }
        coVerify(exactly = 2) {
            backend.invoke<TestResult>(any())
        }
    }

    @Test
    fun `test doh ping ok`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Connection(true)
        // when isPotentiallyBlocked == false DoH logic won't be applied
        coEvery { backend.isPotentiallyBlocked() } returns false
        coEvery { altBackend1.invoke<TestResult>(any()) } returns success5foo

        val result = apiManager.invoke { test() }

        // Accept the error when pinging primary api succeeds
        assertTrue(result is ApiResult.Error.Connection)
    }

    @Test
    fun `test doh off`() = runBlockingTest {
        apiClient.shouldUseDoh = false
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Connection(true)
        coEvery { backend.isPotentiallyBlocked() } returns true
        coEvery { altBackend1.invoke<TestResult>(any()) } returns success5foo

        val result = apiManager.invoke { test() }

        // Doh is off, no proxy should be called
        assertTrue(result is ApiResult.Error.Connection)
        coVerify(exactly = 0) { altBackend1.invoke<TestResult>(any()) }
    }


    @Test
    fun `test no DoH on client error`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Http(400, "")
        coEvery { backend.isPotentiallyBlocked() } returns true
        coEvery { altBackend1.invoke<TestResult>(any()) } returns success5foo

        val result = apiManager.invoke { test() }

        // HTTP 400 shouldn't trigger DoH
        assertTrue(result is ApiResult.Error.Http)
        coVerify(exactly = 0) { altBackend1.invoke<TestResult>(any()) }
    }

    @Test
    fun `test DoH timeout`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Connection(true)
        coEvery { backend.isPotentiallyBlocked() } returns true
        coEvery { altBackend1.invoke<TestResult>(any()) } coAnswers {
            delay(apiClient.dohTimeoutMs)
            success5foo
        }

        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error.Timeout)
    }

    @Test
    fun `test doh proxy refresh throttling`() = runBlockingTest {
        coEvery { backend.invoke<TestResult>(any()) } returns ApiResult.Error.Connection(true)
        coEvery { backend.isPotentiallyBlocked() } returns true
        coEvery { altBackend1.invoke<TestResult>(any()) } returns ApiResult.Error.Connection(true)

        val result = apiManager.invoke { test() }
        assertTrue(result is ApiResult.Error.Connection)

        val result2 = apiManager.invoke { test() }
        assertTrue(result2 is ApiResult.Error.Connection)

        time += DohProvider.MIN_REFRESH_INTERVAL_MS
        val result3 = apiManager.invoke { test() }
        assertTrue(result3 is ApiResult.Error.Connection)

        coVerify(exactly = 2) {
            dohService.getAlternativeBaseUrls(any())
        }
    }
}
