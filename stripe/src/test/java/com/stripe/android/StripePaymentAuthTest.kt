package com.stripe.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nhaarman.mockitokotlin2.KArgumentCaptor
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.PaymentIntentFixtures
import com.stripe.android.model.SetupIntentFixtures
import com.stripe.android.networking.ApiRequest
import com.stripe.android.networking.DefaultApiRequestExecutor
import com.stripe.android.networking.StripeApiRepository
import com.stripe.android.view.AuthActivityStarter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class StripePaymentAuthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testDispatcher = TestCoroutineDispatcher()

    private val activity: Activity = mock()
    private val paymentController: PaymentController = mock()
    private val paymentCallback: ApiResultCallback<PaymentIntentResult> = mock()
    private val setupCallback: ApiResultCallback<SetupIntentResult> = mock()
    private val hostArgumentCaptor: KArgumentCaptor<AuthActivityStarter.Host> = argumentCaptor()

    @Test
    fun confirmPayment_shouldConfirmAndAuth() {
        val stripe = createStripe()
        val confirmPaymentIntentParams = ConfirmPaymentIntentParams.createWithPaymentMethodId(
            "pm_card_threeDSecure2Required",
            "client_secret",
            "yourapp://post-authentication-return-url"
        )
        stripe.confirmPayment(activity, confirmPaymentIntentParams)
        verify(paymentController).startConfirmAndAuth(
            hostArgumentCaptor.capture(),
            eq(confirmPaymentIntentParams),
            eq(REQUEST_OPTIONS)
        )
        assertEquals(activity, hostArgumentCaptor.firstValue.activity)
    }

    @Test
    fun confirmSetupIntent_shouldConfirmAndAuth() {
        val stripe = createStripe()
        val confirmSetupIntentParams = ConfirmSetupIntentParams.create(
            "pm_card_threeDSecure2Required",
            "client_secret",
            "yourapp://post-authentication-return-url"
        )
        stripe.confirmSetupIntent(activity, confirmSetupIntentParams)
        verify(paymentController).startConfirmAndAuth(
            hostArgumentCaptor.capture(),
            eq(confirmSetupIntentParams),
            eq(REQUEST_OPTIONS)
        )
        assertEquals(activity, hostArgumentCaptor.firstValue.activity)
    }

    @Test
    fun authenticatePayment_shouldAuth() {
        val stripe = createStripe()
        val clientSecret =
            requireNotNull(PaymentIntentFixtures.PI_REQUIRES_MASTERCARD_3DS2.clientSecret)
        stripe.handleNextActionForPayment(activity, clientSecret)
        verify(paymentController).startAuth(
            hostArgumentCaptor.capture(),
            eq(clientSecret),
            eq(REQUEST_OPTIONS),
            eq(PaymentController.StripeIntentType.PaymentIntent)
        )
        assertEquals(activity, hostArgumentCaptor.firstValue.activity)
    }

    @Test
    fun handleNextActionForSetupIntent_shouldStartAuth() {
        val stripe = createStripe()
        val clientSecret =
            requireNotNull(SetupIntentFixtures.SI_NEXT_ACTION_REDIRECT.clientSecret)
        stripe.handleNextActionForSetupIntent(activity, clientSecret)
        verify(paymentController).startAuth(
            hostArgumentCaptor.capture(),
            eq(clientSecret),
            eq(REQUEST_OPTIONS),
            eq(PaymentController.StripeIntentType.SetupIntent)
        )
        assertEquals(activity, hostArgumentCaptor.firstValue.activity)
    }

    @Test
    fun onPaymentResult_whenShouldHandleResultIsTrue_shouldCallHandleResult() {
        val data = Intent()
        whenever(
            paymentController.shouldHandlePaymentResult(
                StripePaymentController.PAYMENT_REQUEST_CODE,
                data
            )
        ).thenReturn(true)

        val stripe = createStripe()
        stripe.onPaymentResult(
            StripePaymentController.PAYMENT_REQUEST_CODE,
            data,
            callback = paymentCallback
        )

        verify(paymentController).handlePaymentResult(data, paymentCallback)
    }

    @Test
    fun onSetupResult_whenShouldHandleResultIsTrue_shouldCallHandleResult() {
        val data = Intent()
        whenever(
            paymentController.shouldHandleSetupResult(
                StripePaymentController.SETUP_REQUEST_CODE,
                data
            )
        ).thenReturn(true)

        val stripe = createStripe()
        stripe.onSetupResult(
            StripePaymentController.SETUP_REQUEST_CODE,
            data,
            callback = setupCallback
        )

        verify(paymentController).handleSetupResult(data, setupCallback)
    }

    private fun createStripe(): Stripe {
        return Stripe(
            StripeApiRepository(
                context,
                ApiKeyFixtures.FAKE_PUBLISHABLE_KEY,
                stripeApiRequestExecutor = DefaultApiRequestExecutor(
                    workContext = testDispatcher
                ),
                analyticsRequestExecutor = {}
            ),
            paymentController,
            ApiKeyFixtures.FAKE_PUBLISHABLE_KEY,
            null
        )
    }

    private companion object {
        private val REQUEST_OPTIONS =
            ApiRequest.Options(ApiKeyFixtures.FAKE_PUBLISHABLE_KEY)
    }
}
