package com.stripe.android.view

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import androidx.annotation.VisibleForTesting
import com.stripe.android.AnalyticsEvent
import com.stripe.android.PaymentConfiguration
import com.stripe.android.R
import com.stripe.android.cards.CardAccountRangeRepository
import com.stripe.android.cards.CardNumber
import com.stripe.android.cards.DefaultCardAccountRangeRepositoryFactory
import com.stripe.android.cards.DefaultStaticCardAccountRanges
import com.stripe.android.cards.StaticCardAccountRanges
import com.stripe.android.model.AccountRange
import com.stripe.android.model.CardBrand
import com.stripe.android.networking.AnalyticsDataFactory
import com.stripe.android.networking.AnalyticsRequest
import com.stripe.android.networking.AnalyticsRequestExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A [StripeEditText] that handles spacing out the digits of a credit card.
 */
class CardNumberEditText internal constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,

    // TODO(mshafrir-stripe): make immutable after `CardWidgetViewModel` is integrated in `CardWidget` subclasses
    internal var workContext: CoroutineContext,

    private val cardAccountRangeRepository: CardAccountRangeRepository,
    private val staticCardAccountRanges: StaticCardAccountRanges = DefaultStaticCardAccountRanges(),
    private val analyticsRequestExecutor: AnalyticsRequestExecutor,
    private val analyticsRequestFactory: AnalyticsRequest.Factory,
    private val analyticsDataFactory: AnalyticsDataFactory
) : StripeEditText(context, attrs, defStyleAttr) {

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
    ) : this(
        context,
        attrs,
        defStyleAttr,
        Dispatchers.IO,
        { PaymentConfiguration.getInstance(context).publishableKey }
    )

    private constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        workContext: CoroutineContext,
        publishableKeySupplier: () -> String
    ) : this(
        context,
        attrs,
        defStyleAttr,
        workContext,
        DefaultCardAccountRangeRepositoryFactory(context).create(),
        DefaultStaticCardAccountRanges(),
        AnalyticsRequestExecutor.Default(),
        AnalyticsRequest.Factory(),
        AnalyticsDataFactory(
            context,
            publishableKeySupplier = publishableKeySupplier
        )
    )

    @VisibleForTesting
    var cardBrand: CardBrand = CardBrand.Unknown
        internal set(value) {
            val prevBrand = field
            field = value
            if (value != prevBrand) {
                brandChangeCallback(cardBrand)
                updateLengthFilter()
            }
        }

    @JvmSynthetic
    internal var brandChangeCallback: (CardBrand) -> Unit = {}
        set(callback) {
            field = callback

            // Immediately display the brand if known, in case this method is invoked when
            // partial data already exists.
            callback(cardBrand)
        }

    // invoked when a valid card has been entered
    @JvmSynthetic
    internal var completionCallback: () -> Unit = {}

    @Deprecated("Will be removed in upcoming major release.")
    val lengthMax: Int
        get() {
            return cardBrand.getMaxLengthWithSpacesForCardNumber(fieldText)
        }

    private var accountRange: AccountRange? = null
        set(value) {
            field = value
            updateLengthFilter()
        }

    internal val panLength: Int
        get() = accountRange?.panLength
            ?: staticCardAccountRanges.first(unvalidatedCardNumber)?.panLength
            ?: CardNumber.DEFAULT_PAN_LENGTH

    private val formattedPanLength: Int
        get() = panLength + CardNumber.getSpacePositions(panLength).size

    /**
     * Check whether or not the card number is valid
     */
    var isCardNumberValid: Boolean = false
        private set

    /**
     * A normalized form of the card number. If the entered card number is "4242 4242 4242 4242",
     * this will be "4242424242424242". If the entered card number is invalid, this is `null`.
     */
    @Deprecated("Will be removed in next major release.")
    val cardNumber: String?
        get() = if (isCardNumberValid) {
            unvalidatedCardNumber.normalized
        } else {
            null
        }

    internal val validatedCardNumber: CardNumber.Validated?
        get() = unvalidatedCardNumber.validate(panLength)

    private val unvalidatedCardNumber: CardNumber.Unvalidated
        get() = CardNumber.Unvalidated(fieldText)

    private val isValid: Boolean
        get() = validatedCardNumber != null

    @VisibleForTesting
    internal var accountRangeRepositoryJob: Job? = null

    @JvmSynthetic
    internal var isLoadingCallback: (Boolean) -> Unit = {}

    private var loadingJob: Job? = null

    init {
        inputType = InputType.TYPE_CLASS_NUMBER
        setErrorMessage(resources.getString(R.string.invalid_card_number))
        addTextChangedListener(CardNumberTextWatcher())

        internalFocusChangeListeners.add { _, hasFocus ->
            if (!hasFocus && unvalidatedCardNumber.isPartialEntry(panLength)) {
                shouldShowError = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setAutofillHints(View.AUTOFILL_HINT_CREDIT_CARD_NUMBER)
        }

        updateLengthFilter()

        this.layoutDirection = LAYOUT_DIRECTION_LTR
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadingJob = CoroutineScope(workContext).launch {
            cardAccountRangeRepository.loading.collect {
                withContext(Dispatchers.Main) {
                    isLoadingCallback(it)
                }
            }
        }
    }

    override val accessibilityText: String?
        get() {
            return resources.getString(R.string.acc_label_card_number_node, text)
        }

    override fun onDetachedFromWindow() {
        loadingJob?.cancel()
        loadingJob = null

        cancelAccountRangeRepositoryJob()

        super.onDetachedFromWindow()
    }

    @JvmSynthetic
    internal fun updateLengthFilter(maxLength: Int = formattedPanLength) {
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
    }

    /**
     * Updates the selection index based on the current (pre-edit) index, and
     * the size change of the number being input.
     *
     * @param newFormattedLength the post-edit length of the string
     * @param start the position in the string at which the edit action starts
     * @param addedDigits the number of new characters going into the string (zero for
     * delete)
     * @param panLength the maximum normalized length of the PAN
     * @return an index within the string at which to put the cursor
     */
    @JvmSynthetic
    internal fun calculateCursorPosition(
        newFormattedLength: Int,
        start: Int,
        addedDigits: Int,
        panLength: Int = this.panLength
    ): Int {
        val gapSet = CardNumber.getSpacePositions(panLength)

        val gapsJumped = gapSet.count { gap ->
            start <= gap && start + addedDigits >= gap
        }

        val skipBack = gapSet.any { gap ->
            // addedDigits can only be 0 if we are deleting,
            // so we need to check whether or not to skip backwards one space
            addedDigits == 0 && start == gap + 1
        }

        var newPosition = start + addedDigits + gapsJumped
        if (skipBack && newPosition > 0) {
            newPosition--
        }

        return if (newPosition <= newFormattedLength) {
            newPosition
        } else {
            newFormattedLength
        }
    }

    @JvmSynthetic
    internal fun queryAccountRangeRepository(cardNumber: CardNumber.Unvalidated) {
        if (shouldQueryAccountRange(cardNumber)) {
            // cancel in-flight job
            cancelAccountRangeRepositoryJob()

            // invalidate accountRange before fetching
            accountRange = null

            accountRangeRepositoryJob = CoroutineScope(workContext).launch {
                val bin = cardNumber.bin
                val accountRange = if (bin != null) {
                    cardAccountRangeRepository.getAccountRange(cardNumber)
                } else {
                    null
                }

                withContext(Dispatchers.Main) {
                    onAccountRangeResult(accountRange)
                }
            }
        }
    }

    private fun cancelAccountRangeRepositoryJob() {
        accountRangeRepositoryJob?.cancel()
        accountRangeRepositoryJob = null
    }

    @JvmSynthetic
    internal fun onAccountRangeResult(
        newAccountRange: AccountRange?
    ) {
        accountRange = newAccountRange
        cardBrand = newAccountRange?.brand ?: CardBrand.Unknown
    }

    private fun shouldQueryAccountRange(cardNumber: CardNumber.Unvalidated): Boolean {
        return accountRange == null ||
            cardNumber.bin == null ||
            accountRange?.binRange?.matches(cardNumber) == false
    }

    @JvmSynthetic
    internal fun onCardMetadataLoadedTooSlow() {
        analyticsRequestExecutor.executeAsync(
            analyticsRequestFactory.create(
                analyticsDataFactory.createParams(AnalyticsEvent.CardMetadataLoadedTooSlow)
            )
        )
    }

    private inner class CardNumberTextWatcher : StripeTextWatcher() {
        private var ignoreChanges = false
        private var latestChangeStart: Int = 0
        private var latestInsertionSize: Int = 0

        private var newCursorPosition: Int? = null
        private var formattedNumber: String? = null

        private var beforeCardNumber = unvalidatedCardNumber

        private var isPastedPan = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!ignoreChanges) {
                isPastedPan = false
                beforeCardNumber = unvalidatedCardNumber

                latestChangeStart = start
                latestInsertionSize = after
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (ignoreChanges) {
                return
            }

            val cardNumber = CardNumber.Unvalidated(s?.toString().orEmpty())
            val staticAccountRange = staticCardAccountRanges.filter(cardNumber)
                .let { accountRanges ->
                    if (accountRanges.size == 1) {
                        accountRanges.first()
                    } else {
                        null
                    }
                }
            if (staticAccountRange == null || shouldQueryRepository(staticAccountRange)) {
                // query for AccountRange data
                queryAccountRangeRepository(cardNumber)
            } else {
                // use static AccountRange data
                onAccountRangeResult(staticAccountRange)
            }

            isPastedPan = isPastedPan(start, before, count, cardNumber)

            if (isPastedPan) {
                updateLengthFilter(cardNumber.getFormatted(cardNumber.length).length)
            }

            if (isPastedPan) {
                cardNumber.length
            } else {
                panLength
            }.let { maxPanLength ->
                val formattedNumber = cardNumber.getFormatted(maxPanLength)
                newCursorPosition = calculateCursorPosition(
                    formattedNumber.length,
                    latestChangeStart,
                    latestInsertionSize,
                    maxPanLength
                )
                this.formattedNumber = formattedNumber
            }
        }

        override fun afterTextChanged(s: Editable?) {
            if (ignoreChanges) {
                return
            }

            ignoreChanges = true

            if (shouldUpdateAfterChange) {
                setText(formattedNumber)
                newCursorPosition?.let {
                    setSelection(it.coerceIn(0, fieldText.length))
                }
            }

            formattedNumber = null
            newCursorPosition = null

            ignoreChanges = false

            if (unvalidatedCardNumber.length == panLength) {
                val wasCardNumberValid = isCardNumberValid
                isCardNumberValid = isValid
                shouldShowError = !isValid

                if (accountRange == null && unvalidatedCardNumber.isValidLuhn) {
                    // a complete PAN was inputted before the card service returned results
                    onCardMetadataLoadedTooSlow()
                }

                if (isComplete(wasCardNumberValid)) {
                    completionCallback()
                }
            } else if (!unvalidatedCardNumber.isPossibleCardBrand()) {
                // Partial card number entered and brand is not yet determine, but possible.
                isCardNumberValid = isValid
                shouldShowError = true
            } else {
                isCardNumberValid = isValid
                // Don't show errors if we aren't full-length and the brand is known.
                // TODO (michelleb-stripe) Should set error message to incomplete, then in focus change if it isn't complete it will update it.
                shouldShowError = false
            }
        }

        private val shouldUpdateAfterChange: Boolean
            get() = (digitsAdded || !isLastKeyDelete) && formattedNumber != null

        /**
         * Have digits been added in this text change.
         */
        private val digitsAdded: Boolean
            get() = unvalidatedCardNumber.length > beforeCardNumber.length

        /**
         * If `true`, [completionCallback] will be invoked.
         */
        private fun isComplete(
            wasCardNumberValid: Boolean
        ) = !wasCardNumberValid && (
            unvalidatedCardNumber.isMaxLength ||
                (isValid && accountRange != null)
            )

        /**
         * The [currentCount] characters beginning at [startPosition] have just replaced old text
         * that had length [previousCount]. If [currentCount] < [previousCount], digits were
         * deleted.
         */
        private fun isPastedPan(
            startPosition: Int,
            previousCount: Int,
            currentCount: Int,
            cardNumber: CardNumber.Unvalidated
        ): Boolean {
            return currentCount > previousCount && startPosition == 0 &&
                cardNumber.normalized.length >= CardNumber.MIN_PAN_LENGTH
        }

        private fun shouldQueryRepository(
            accountRange: AccountRange
        ) = when (accountRange.brand) {
            CardBrand.Unknown,
            CardBrand.UnionPay -> true
            else -> false
        }
    }
}
