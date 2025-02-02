package com.stripe.android.paymentsheet.repositories

import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.paymentsheet.PaymentSheet

internal interface PaymentMethodsRepository {
    suspend fun get(
        customerConfig: PaymentSheet.CustomerConfiguration,
        type: PaymentMethod.Type
    ): List<PaymentMethod>

    suspend fun save(
        customerConfig: PaymentSheet.CustomerConfiguration,
        paymentMethodCreateParams: PaymentMethodCreateParams,
    ): PaymentMethod
}
