/*
 * Copyright (c) 2022 Proton Technologies AG
 * This file is part of Proton AG and ProtonCore.
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

package me.proton.core.paymentiap.presentation.viewmodel

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import me.proton.core.observability.domain.ObservabilityManager
import me.proton.core.observability.domain.metrics.CheckoutGiapBillingProductQueryTotalV1
import me.proton.core.observability.domain.metrics.CheckoutGiapBillingPurchaseTotalV1
import me.proton.core.observability.domain.metrics.CheckoutGiapBillingUnredeemedTotalV1
import me.proton.core.observability.domain.metrics.common.GiapStatus
import me.proton.core.payment.domain.usecase.FindUnacknowledgedGooglePurchase
import me.proton.core.paymentiap.domain.repository.GoogleBillingRepository
import me.proton.core.test.kotlin.CoroutinesTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingIAPViewModelTest : CoroutinesTest by CoroutinesTest() {
    @MockK(relaxed = true)
    private lateinit var billingRepository: GoogleBillingRepository

    @MockK(relaxed = true)
    private lateinit var findUnacknowledgedGooglePurchase: FindUnacknowledgedGooglePurchase

    @MockK(relaxed = true)
    private lateinit var observabilityManager: ObservabilityManager

    private lateinit var tested: BillingIAPViewModel

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this)
        tested = BillingIAPViewModel(billingRepository, findUnacknowledgedGooglePurchase, observabilityManager)
    }

    @Test
    fun `observability data is recorded for product query`() = coroutinesTest {
        // GIVEN
        coEvery { billingRepository.getProductDetails(any()) } returns mockk()

        // WHEN
        tested.queryProductDetails("test-plan-name").join()

        // THEN
        val dataSlot = slot<CheckoutGiapBillingProductQueryTotalV1>()
        verify { observabilityManager.enqueue(capture(dataSlot), any()) }
        assertEquals(GiapStatus.success, dataSlot.captured.Labels.status)
    }

    @Test
    fun `observability data is recorded unredeemed purchase is returned`() = coroutinesTest {
        // GIVEN
        coEvery { findUnacknowledgedGooglePurchase.byProduct(any(), any()) } returns mockk()
        tested.queryProductDetails("test-plan-name").join()

        // WHEN
        tested.makePurchase(mockk(), "customer-id").join()

        // THEN
        verify { observabilityManager.enqueue(any<CheckoutGiapBillingUnredeemedTotalV1>(), any()) }
    }

    @Test
    fun `observability data is recorded on purchase result`() = coroutinesTest {
        // GIVEN
        every { billingRepository.purchaseUpdated } returns flowOf(
            BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                .build() to null
        )

        // WHEN
        tested = BillingIAPViewModel(billingRepository, findUnacknowledgedGooglePurchase, observabilityManager)
        // wait until `onPurchasesUpdated` is called:
        tested.billingIAPState.first { it is BillingIAPViewModel.State.Error.ProductPurchaseError.Message }

        // THEN
        val dataSlot = slot<CheckoutGiapBillingPurchaseTotalV1>()
        verify { observabilityManager.enqueue(capture(dataSlot), any()) }
        assertEquals(GiapStatus.developerError, dataSlot.captured.Labels.status)
    }
}
