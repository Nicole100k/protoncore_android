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

package me.proton.core.auth.presentation.viewmodel

import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.auth.domain.AccountWorkflowHandler
import me.proton.core.auth.domain.usecase.PostLoginAccountSetup
import me.proton.core.auth.domain.usecase.CreateLoginSession
import me.proton.core.auth.presentation.entity.signup.SubscriptionDetails
import me.proton.core.crypto.common.keystore.EncryptedString
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.domain.entity.UserId
import me.proton.core.humanverification.domain.HumanVerificationManager
import me.proton.core.humanverification.presentation.HumanVerificationOrchestrator
import me.proton.core.payment.domain.usecase.PerformSubscribe
import javax.inject.Inject

@HiltViewModel
internal class LoginViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val accountWorkflow: AccountWorkflowHandler,
    private val createLoginSession: CreateLoginSession,
    private val keyStoreCrypto: KeyStoreCrypto,
    private val performSubscribe: PerformSubscribe,
    private val postLoginAccountSetup: PostLoginAccountSetup,
    humanVerificationManager: HumanVerificationManager,
    humanVerificationOrchestrator: HumanVerificationOrchestrator,
) : AuthViewModel(humanVerificationManager, humanVerificationOrchestrator) {

    private val _state = MutableSharedFlow<State>(replay = 1, extraBufferCapacity = 3)

    val state = _state.asSharedFlow()

    sealed class State {
        object Idle : State()
        object Processing : State()
        data class AccountSetupResult(val result: PostLoginAccountSetup.Result) : State()
        data class ErrorMessage(val message: String?) : State()
    }

    override val recoveryEmailAddress: String?
        get() = null

    fun stopLoginWorkflow(): Job = viewModelScope.launch {
        savedStateHandle.get<String>(STATE_USER_ID)?.let { accountWorkflow.handleAccountDisabled(UserId(it)) }
    }

    fun observeHumanVerification(context: ComponentActivity) = handleHumanVerificationState(context)

    fun startLoginWorkflow(
        username: String,
        password: String,
        requiredAccountType: AccountType,
        subscriptionDetails: SubscriptionDetails? = null
    ): Job = startLoginWorkflowWithEncryptedPassword(
        username = username,
        encryptedPassword = password.encrypt(keyStoreCrypto),
        requiredAccountType = requiredAccountType,
        subscriptionDetails = subscriptionDetails
    )

    fun startLoginWorkflowWithEncryptedPassword(
        username: String,
        encryptedPassword: EncryptedString,
        requiredAccountType: AccountType,
        subscriptionDetails: SubscriptionDetails? = null
    ) = flow<State> {
        emit(State.Processing)

        val sessionInfo = createLoginSession(username, encryptedPassword, requiredAccountType)
        val userId = sessionInfo.userId

        savedStateHandle.set(STATE_USER_ID, userId.id)

        // Subscribe to any pending subscription/billing.
        if (subscriptionDetails?.billingResult != null) {
            val billing = subscriptionDetails.billingResult
            runCatching {
                performSubscribe(
                    userId = userId,
                    amount = billing.amount,
                    currency = billing.currency,
                    cycle = billing.cycle,
                    planNames = listOf(subscriptionDetails.planName),
                    paymentToken = billing.token
                )
            }
        }

        val result = postLoginAccountSetup(sessionInfo, encryptedPassword, requiredAccountType)
        emit(State.AccountSetupResult(result))
    }.catch { error ->
        emit(State.ErrorMessage(error.message))
    }.onEach { state ->
        _state.tryEmit(state)
    }.launchIn(viewModelScope)

    companion object {
        const val STATE_USER_ID = "userId"
    }
}
