package com.stripe.android.view

import android.content.Context
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ApplicationProvider
import com.nhaarman.mockitokotlin2.mock
import com.stripe.android.ApiKeyFixtures
import com.stripe.android.CustomerSession
import com.stripe.android.EphemeralKeyProvider
import com.stripe.android.PaymentConfiguration
import com.stripe.android.PaymentSessionData
import com.stripe.android.PaymentSessionFixtures
import com.stripe.android.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test class for [CountryTextInputLayout]
 */
@RunWith(RobolectricTestRunner::class)
class CountryTextInputLayoutTest {
    private lateinit var countryTextInputLayout: CountryTextInputLayout
    private lateinit var autoCompleteTextView: AutoCompleteTextView

    private val ephemeralKeyProvider: EphemeralKeyProvider = mock()

    private val activityScenarioFactory = ActivityScenarioFactory(
        ApplicationProvider.getApplicationContext()
    )

    @BeforeTest
    fun setup() {
        Locale.setDefault(Locale.US)

        val context: Context = ApplicationProvider.getApplicationContext()
        PaymentConfiguration.init(context, ApiKeyFixtures.FAKE_PUBLISHABLE_KEY)
        CustomerSession.initCustomerSession(context, ephemeralKeyProvider)

        val config = PaymentSessionFixtures.CONFIG.copy(
            prepopulatedShippingInfo = null,
            allowedShippingCountryCodes = emptySet()
        )
        activityScenarioFactory.create<PaymentFlowActivity>(
            PaymentFlowActivityStarter.Args(
                paymentSessionConfig = config,
                paymentSessionData = PaymentSessionData(config)
            )
        ).use { activityScenario ->
            activityScenario.onActivity {
                countryTextInputLayout = it
                    .findViewById(R.id.country_autocomplete_aaw)
                autoCompleteTextView = countryTextInputLayout.countryAutocomplete
            }
        }
    }

    @Test
    fun countryAutoCompleteTextView_whenInitialized_displaysDefaultLocaleDisplayName() {
        assertEquals(Locale.US.country, countryTextInputLayout.selectedCountry?.code)
        assertEquals(Locale.US.displayCountry, autoCompleteTextView.text.toString())
    }

    @Test
    fun updateUIForCountryEntered_whenInvalidCountry_revertsToLastCountry() {
        val previousValidCountryCode =
            countryTextInputLayout.selectedCountry?.code.orEmpty()
        countryTextInputLayout.setCountrySelected("FAKE COUNTRY CODE")
        assertNull(autoCompleteTextView.error)
        assertEquals(
            autoCompleteTextView.text.toString(),
            Locale("", previousValidCountryCode).displayCountry
        )
        countryTextInputLayout.setCountrySelected(Locale.UK.country)
        assertNotEquals(
            autoCompleteTextView.text.toString(),
            Locale("", previousValidCountryCode).displayCountry
        )
        assertEquals(autoCompleteTextView.text.toString(), Locale.UK.displayCountry)
    }

    @Test
    fun updateUIForCountryEntered_whenValidCountry_UIUpdates() {
        assertEquals(Locale.US.country, countryTextInputLayout.selectedCountry?.code)
        countryTextInputLayout.setCountrySelected(Locale.UK.country)
        assertEquals(Locale.UK.country, countryTextInputLayout.selectedCountry?.code)
    }

    @Test
    fun countryAutoCompleteTextView_onInputFocus_displayDropDown() {
        autoCompleteTextView.clearFocus()
        assertFalse(autoCompleteTextView.isPopupShowing)
        autoCompleteTextView.requestFocus()
        assertTrue(autoCompleteTextView.isPopupShowing)
    }

    @Test
    fun setAllowedCountryCodes_withPopulatedSet_shouldUpdateSelectedCountry() {
        countryTextInputLayout.setAllowedCountryCodes(setOf("fr", "de"))
        assertEquals(
            "FR",
            countryTextInputLayout.selectedCountry?.code
        )
    }

    @Test
    fun validateCountry_withInvalidCountry_setsSelectedCountryToNull() {
        assertNotNull(countryTextInputLayout.selectedCountry)
        countryTextInputLayout.countryAutocomplete.setText("invalid country")
        countryTextInputLayout.validateCountry()
        assertNull(countryTextInputLayout.selectedCountry)
    }

    @Test
    fun validateCountry_withValidCountry_setsSelectedCountry() {
        assertNotNull(countryTextInputLayout.selectedCountry)
        countryTextInputLayout.countryAutocomplete.setText("Canada")
        countryTextInputLayout.validateCountry()
        assertEquals("Canada", countryTextInputLayout.selectedCountry?.name)
    }

    @AfterTest
    fun teardown() {
        Locale.setDefault(Locale.US)
    }
}
