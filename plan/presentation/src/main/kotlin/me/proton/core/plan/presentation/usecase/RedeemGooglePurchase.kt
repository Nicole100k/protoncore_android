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

package me.proton.core.plan.presentation.usecase

import me.proton.core.domain.entity.AppStore
import me.proton.core.domain.entity.UserId
import me.proton.core.observability.domain.metrics.CheckoutGiapBillingValidatePlanTotalV1
import me.proton.core.observability.domain.metrics.ObservabilityData
import me.proton.core.observability.domain.metrics.common.toHttpApiStatus
import me.proton.core.payment.domain.entity.GooglePurchase
import me.proton.core.payment.domain.entity.PaymentTokenStatus
import me.proton.core.payment.domain.entity.PaymentType
import me.proton.core.payment.domain.entity.Subscription
import me.proton.core.payment.domain.entity.SubscriptionManagement
import me.proton.core.payment.domain.usecase.AcknowledgeGooglePlayPurchase
import me.proton.core.payment.domain.usecase.CreatePaymentTokenWithGoogleIAP
import me.proton.core.payment.domain.usecase.PerformSubscribe
import me.proton.core.payment.domain.usecase.ValidateSubscriptionPlan
import me.proton.core.plan.domain.entity.Plan
import me.proton.core.plan.presentation.entity.PlanCurrency
import me.proton.core.plan.presentation.entity.PlanCycle
import me.proton.core.plan.presentation.entity.UnredeemedGooglePurchaseStatus
import java.util.Optional
import javax.inject.Inject

internal class RedeemGooglePurchase @Inject constructor(
    private val acknowledgeGooglePlayPurchaseOptional: Optional<AcknowledgeGooglePlayPurchase>,
    private val createPaymentTokenWithGoogleIAP: CreatePaymentTokenWithGoogleIAP,
    private val performSubscribe: PerformSubscribe,
    private val validateSubscriptionPlan: ValidateSubscriptionPlan
) {
    suspend operator fun invoke(
        googlePurchase: GooglePurchase,
        purchasedPlan: Plan,
        purchaseStatus: UnredeemedGooglePurchaseStatus,
        userId: UserId,
        subscribeMetricData: ((Result<Subscription>, SubscriptionManagement) -> ObservabilityData)? = null
    ) {
        when (purchaseStatus) {
            UnredeemedGooglePurchaseStatus.NotSubscribed ->
                createSubscriptionAndAcknowledge(googlePurchase, purchasedPlan, userId, subscribeMetricData)
            UnredeemedGooglePurchaseStatus.SubscribedButNotAcknowledged ->
                acknowledgeGooglePlayPurchaseOptional.getOrNull()?.invoke(googlePurchase.purchaseToken)
        }
    }

    private suspend fun createSubscriptionAndAcknowledge(
        googlePurchase: GooglePurchase,
        purchasedPlan: Plan,
        userId: UserId,
        subscribeMetricData: ((Result<Subscription>, SubscriptionManagement) -> ObservabilityData)?
    ) {
        val currency = PlanCurrency.valueOf(purchasedPlan.currency!!)
        val planCycle = getPlanCycleForPurchase(googlePurchase, purchasedPlan)
        val planNames = listOf(purchasedPlan.name)
        val subscriptionStatus = validateSubscriptionPlan(
            userId,
            codes = null,
            plans = planNames,
            currency.toSubscriptionCurrency(),
            planCycle.toSubscriptionCycle(),
            metricData  = { CheckoutGiapBillingValidatePlanTotalV1(it.toHttpApiStatus()) }
        )
        val tokenResult = createPaymentTokenWithGoogleIAP(
            userId,
            subscriptionStatus.amountDue,
            subscriptionStatus.currency,
            PaymentType.GoogleIAP(
                productId = googlePurchase.productIds.first(),
                purchaseToken = googlePurchase.purchaseToken,
                orderId = googlePurchase.orderId,
                packageName = googlePurchase.packageName,
                customerId = requireNotNull(googlePurchase.customerId)
            )
        )
        check(tokenResult.status == PaymentTokenStatus.CHARGEABLE)

        // performSubscribe also acknowledges Google purchase:
        performSubscribe(
            userId,
            subscriptionStatus.amountDue,
            subscriptionStatus.currency,
            subscriptionStatus.cycle,
            planNames,
            codes = null,
            tokenResult.token,
            SubscriptionManagement.GOOGLE_MANAGED,
            subscribeMetricData = subscribeMetricData
        )
    }

    private fun getPlanCycleForPurchase(googlePurchase: GooglePurchase, purchasedPlan: Plan): PlanCycle {
        val planVendorData = requireNotNull(purchasedPlan.vendors[AppStore.GooglePlay])
        val (planDuration, _) = planVendorData.names.entries.first { it.value in googlePurchase.productIds }
        return requireNotNull(PlanCycle.map[planDuration.months])
    }

    private fun <T : Any> Optional<T>.getOrNull(): T? = if (isPresent) get() else null
}
