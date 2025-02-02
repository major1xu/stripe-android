package com.stripe.android.view

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stripe.android.ApiKeyFixtures
import com.stripe.android.StripeIntentResult
import com.stripe.android.auth.PaymentAuthWebViewContract
import com.stripe.android.networking.AnalyticsDataFactory
import com.stripe.android.networking.AnalyticsRequest
import com.stripe.android.networking.AnalyticsRequestExecutor
import com.stripe.android.payments.PaymentFlowResult
import com.stripe.android.stripe3ds2.init.ui.StripeToolbarCustomization
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class PaymentAuthWebViewActivityViewModelTest {
    private val analyticsRequestExecutor = FakeAnalyticsRequestExecutor()
    private val analyticsDataFactory = AnalyticsDataFactory(
        ApplicationProvider.getApplicationContext(),
        ApiKeyFixtures.FAKE_PUBLISHABLE_KEY
    )
    private val analyticsRequestFactory = AnalyticsRequest.Factory()

    @Test
    fun cancellationResult() {
        val viewModel = createViewModel(
            ARGS.copy(
                shouldCancelSource = true
            )
        )

        val intent = viewModel.cancellationResult
        val resultIntent = PaymentFlowResult.Unvalidated.fromIntent(intent)
        assertThat(resultIntent.flowOutcome)
            .isEqualTo(StripeIntentResult.Outcome.CANCELED)
        assertThat(resultIntent.shouldCancelSource)
            .isTrue()
    }

    @Test
    fun `cancellationResult should set correct outcome when user nav is allowed`() {
        val viewModel = createViewModel(
            ARGS.copy(
                shouldCancelSource = true,
                shouldCancelIntentOnUserNavigation = false
            )
        )

        val intent = viewModel.cancellationResult
        val resultIntent = PaymentFlowResult.Unvalidated.fromIntent(intent)
        assertThat(resultIntent.flowOutcome)
            .isEqualTo(StripeIntentResult.Outcome.SUCCEEDED)
        assertThat(resultIntent.shouldCancelSource)
            .isTrue()
    }

    @Test
    fun toolbarBackgroundColor_returnsCorrectValue() {
        val viewModel = createViewModel(
            ARGS.copy(
                toolbarCustomization = StripeToolbarCustomization().apply {
                    setBackgroundColor("#ffffff")
                }
            )
        )
        assertThat(viewModel.toolbarBackgroundColor)
            .isEqualTo("#ffffff")
    }

    @Test
    fun buttonText_returnsCorrectValue() {
        val viewModel = createViewModel(
            ARGS.copy(
                toolbarCustomization = StripeToolbarCustomization().apply {
                    setButtonText("close")
                }
            )
        )
        assertThat(viewModel.buttonText)
            .isEqualTo("close")
    }

    @Test
    fun toolbarTitle_returnsCorrectValue() {
        val toolbarCustomization = StripeToolbarCustomization().apply {
            setHeaderText("auth webview")
        }
        val viewModel = createViewModel(
            ARGS.copy(
                toolbarCustomization = toolbarCustomization
            )
        )
        assertThat(
            viewModel.toolbarTitle
        ).isEqualTo(
            PaymentAuthWebViewActivityViewModel.ToolbarTitleData(
                "auth webview",
                toolbarCustomization
            )
        )
    }

    @Test
    fun `logError() should fire expected event`() {
        val viewModel = createViewModel(ARGS)

        viewModel.logError(
            Uri.parse("https://example.com/path?secret=password"),
            ActivityNotFoundException("Failed to find activity")
        )

        val analyticsRequests = analyticsRequestExecutor.requests
        val params = analyticsRequests.first().params
        assertThat(params["event"])
            .isEqualTo("stripe_android.3ds1_challenge_error")
        assertThat(params["error_message"])
            .isEqualTo("Failed to find activity")
        assertThat(params["error_stacktrace"].toString())
            .startsWith("android.content.ActivityNotFoundException: Failed to find activity\n")
        assertThat(params["challenge_uri"])
            .isEqualTo("https://example.com")
    }

    @Test
    fun `logComplete() should fire expected event`() {
        val viewModel = createViewModel(ARGS)

        viewModel.logComplete(
            Uri.parse("https://example.com/path?secret=password")
        )

        val analyticsRequests = analyticsRequestExecutor.requests
        val params = analyticsRequests.first().params
        assertThat(params["event"])
            .isEqualTo("stripe_android.3ds1_challenge_complete")
        assertThat(params["challenge_uri"])
            .isEqualTo("https://example.com")
    }

    @Test
    fun `logComplete() with uri=null should fire expected event`() {
        val viewModel = createViewModel(ARGS)

        viewModel.logComplete(
            uri = null
        )

        val analyticsRequests = analyticsRequestExecutor.requests
        val params = analyticsRequests.first().params
        assertThat(params["event"])
            .isEqualTo("stripe_android.3ds1_challenge_complete")
        assertThat(params["challenge_uri"])
            .isEqualTo("")
    }

    private fun createViewModel(
        args: PaymentAuthWebViewContract.Args
    ): PaymentAuthWebViewActivityViewModel {
        return PaymentAuthWebViewActivityViewModel(
            args,
            analyticsRequestExecutor,
            analyticsRequestFactory,
            analyticsDataFactory
        )
    }

    private class FakeAnalyticsRequestExecutor : AnalyticsRequestExecutor {
        val requests = mutableListOf<AnalyticsRequest>()

        override fun executeAsync(request: AnalyticsRequest) {
            requests.add(request)
        }
    }

    private companion object {
        val ARGS = PaymentAuthWebViewContract.Args(
            objectId = "pi_1EceMnCRMbs6FrXfCXdF8dnx",
            requestCode = 100,
            clientSecret = "client_secret",
            url = "https://example.com"
        )
    }
}
