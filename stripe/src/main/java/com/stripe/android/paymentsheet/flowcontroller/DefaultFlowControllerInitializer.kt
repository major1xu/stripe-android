package com.stripe.android.paymentsheet.flowcontroller

import com.stripe.android.model.PaymentIntent
import com.stripe.android.model.PaymentMethod
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PrefsRepository
import com.stripe.android.paymentsheet.model.PaymentIntentValidator
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.model.SavedSelection
import com.stripe.android.paymentsheet.repositories.PaymentIntentRepository
import com.stripe.android.paymentsheet.repositories.PaymentMethodsRepository
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class DefaultFlowControllerInitializer(
    private val paymentIntentRepository: PaymentIntentRepository,
    private val paymentMethodsRepository: PaymentMethodsRepository,
    private val prefsRepositoryFactory: (String, Boolean) -> PrefsRepository,
    private val isGooglePayReadySupplier: suspend (PaymentSheet.GooglePayConfiguration.Environment?) -> Boolean,
    private val workContext: CoroutineContext
) : FlowControllerInitializer {
    private val paymentIntentValidator = PaymentIntentValidator()

    override suspend fun init(
        paymentIntentClientSecret: String,
        configuration: PaymentSheet.Configuration
    ) = withContext(workContext) {
        val isGooglePayReady = isGooglePayReadySupplier(configuration.googlePay?.environment)
        configuration.customer?.let { customerConfig ->
            createWithCustomer(
                paymentIntentClientSecret,
                customerConfig,
                configuration,
                isGooglePayReady
            )
        } ?: createWithoutCustomer(
            paymentIntentClientSecret,
            configuration,
            isGooglePayReady
        )
    }

    override suspend fun init(
        paymentIntentClientSecret: String
    ) = withContext(workContext) {
        createWithoutCustomer(
            paymentIntentClientSecret,
            config = null,
            isGooglePayReady = false
        )
    }

    private suspend fun createWithCustomer(
        clientSecret: String,
        customerConfig: PaymentSheet.CustomerConfiguration,
        config: PaymentSheet.Configuration?,
        isGooglePayReady: Boolean
    ): FlowControllerInitializer.InitResult {
        val prefsRepository = prefsRepositoryFactory(
            customerConfig.id,
            isGooglePayReady
        )

        return runCatching {
            retrievePaymentIntent(clientSecret)
        }.fold(
            onSuccess = { paymentIntent ->
                val paymentMethodTypes = paymentIntent.paymentMethodTypes.mapNotNull {
                    PaymentMethod.Type.fromCode(it)
                }
                retrieveAllPaymentMethods(
                    types = paymentMethodTypes,
                    customerConfig
                ).let { paymentMethods ->

                    setLastSavedPaymentMethod(prefsRepository, isGooglePayReady, paymentMethods)

                    FlowControllerInitializer.InitResult.Success(
                        InitData(
                            config = config,
                            paymentIntent = paymentIntent,
                            paymentMethodTypes = paymentMethodTypes,
                            paymentMethods = paymentMethods,
                            savedSelection = prefsRepository.getSavedSelection(),
                            isGooglePayReady = isGooglePayReady
                        )
                    )
                }
            },
            onFailure = {
                FlowControllerInitializer.InitResult.Failure(it)
            }
        )
    }

    private suspend fun createWithoutCustomer(
        clientSecret: String,
        config: PaymentSheet.Configuration?,
        isGooglePayReady: Boolean
    ): FlowControllerInitializer.InitResult {
        return runCatching {
            retrievePaymentIntent(clientSecret)
        }.fold(
            onSuccess = { paymentIntent ->
                val paymentMethodTypes = paymentIntent.paymentMethodTypes
                    .mapNotNull {
                        PaymentMethod.Type.fromCode(it)
                    }

                val savedSelection = if (isGooglePayReady) {
                    SavedSelection.GooglePay
                } else {
                    SavedSelection.None
                }

                FlowControllerInitializer.InitResult.Success(
                    InitData(
                        config = config,
                        paymentIntent = paymentIntent,
                        paymentMethodTypes = paymentMethodTypes,
                        paymentMethods = emptyList(),
                        savedSelection = savedSelection,
                        isGooglePayReady = isGooglePayReady
                    )
                )
            },
            onFailure = {
                FlowControllerInitializer.InitResult.Failure(it)
            }
        )
    }

    private suspend fun setLastSavedPaymentMethod(
        prefsRepository: PrefsRepository,
        isGooglePayReady: Boolean,
        paymentMethods: List<PaymentMethod>
    ) {
        if (prefsRepository.getSavedSelection() == SavedSelection.None) {
            when {
                paymentMethods.isNotEmpty() -> {
                    PaymentSelection.Saved(paymentMethods.first())
                }
                isGooglePayReady -> {
                    PaymentSelection.GooglePay
                }
                else -> {
                    null
                }
            }?.let {
                prefsRepository.savePaymentSelection(it)
            }
        }
    }

    private suspend fun retrieveAllPaymentMethods(
        types: List<PaymentMethod.Type>,
        customerConfig: PaymentSheet.CustomerConfiguration
    ): List<PaymentMethod> {
        return types.flatMap { type ->
            paymentMethodsRepository.get(customerConfig, type)
        }
    }

    private suspend fun retrievePaymentIntent(
        clientSecret: String
    ): PaymentIntent {
        return paymentIntentValidator.requireValid(
            paymentIntentRepository.get(clientSecret)
        )
    }
}
