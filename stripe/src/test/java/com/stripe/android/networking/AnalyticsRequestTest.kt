package com.stripe.android.networking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.stripe.android.ApiKeyFixtures
import com.stripe.android.Logger
import com.stripe.android.Stripe
import com.stripe.android.model.PaymentMethodCreateParams
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class AnalyticsRequestTest {

    private val logger: Logger = mock()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = AnalyticsRequest.Factory(logger)

    @Test
    fun factoryCreate_createsExpectedObject() {
        val sdkVersion = Stripe.VERSION_NAME
        val analyticsRequest = factory.create(
            params = AnalyticsDataFactory(context, ApiKeyFixtures.FAKE_PUBLISHABLE_KEY)
                .createPaymentMethodCreationParams(
                    PaymentMethodCreateParams.Type.Card,
                    emptySet(),
                    REQUEST_ID
                )
        )
        assertThat(analyticsRequest.headers)
            .isEqualTo(
                mapOf(
                    "User-Agent" to "Stripe/v1 AndroidBindings/$sdkVersion",
                    "Accept-Charset" to "UTF-8"
                )
            )
        val requestUrl = analyticsRequest.url

        assertThat(requestUrl)
            .isEqualTo("https://q.stripe.com?publishable_key=pk_test_123&app_version=0&bindings_version=$sdkVersion&os_version=30&os_release=11&device_type=robolectric_robolectric_robolectric&source_type=card&app_name=com.stripe.android.test&analytics_ua=analytics.stripe_android-1.0&os_name=REL&event=stripe_android.payment_method_creation&request_id=req_123")
    }

    @Test
    fun factoryCreate_shouldLogRequest() {
        factory.create(
            AnalyticsDataFactory(context, ApiKeyFixtures.FAKE_PUBLISHABLE_KEY)
                .createPaymentMethodCreationParams(
                    PaymentMethodCreateParams.Type.Card,
                    emptySet(),
                    REQUEST_ID
                )
        )

        verify(logger).info(
            "Event: stripe_android.payment_method_creation"
        )
    }

    private companion object {
        val REQUEST_ID = RequestId("req_123")
    }
}
