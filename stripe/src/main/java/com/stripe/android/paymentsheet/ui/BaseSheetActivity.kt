package com.stripe.android.paymentsheet.ui

import android.animation.LayoutTransition
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.stripe.android.R
import com.stripe.android.paymentsheet.BottomSheetController
import com.stripe.android.paymentsheet.analytics.EventReporter
import com.stripe.android.paymentsheet.viewmodels.BaseSheetViewModel
import com.stripe.android.view.KeyboardController

internal abstract class BaseSheetActivity<ResultType> : AppCompatActivity() {
    abstract val viewModel: BaseSheetViewModel<*>
    abstract val bottomSheetController: BottomSheetController
    abstract val eventReporter: EventReporter

    abstract val rootView: ViewGroup
    abstract val bottomSheet: ViewGroup
    abstract val appbar: AppBarLayout
    abstract val scrollView: ScrollView
    abstract val toolbar: MaterialToolbar
    abstract val messageView: TextView
    abstract val fragmentContainerParent: ViewGroup

    abstract fun onUserCancel()
    abstract fun setActivityResult(result: ResultType)

    private val keyboardController: KeyboardController by lazy {
        KeyboardController(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = PaymentSheetFragmentFactory(eventReporter)

        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            // In Oreo, Activities where `android:windowIsTranslucent=true` can't request
            // orientation. See https://stackoverflow.com/a/50832408/11103900
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        supportFragmentManager.addOnBackStackChangedListener {
            updateToolbarButton(supportFragmentManager.backStackEntryCount == 0)
        }

        scrollView.viewTreeObserver.addOnScrollChangedListener {
            appbar.elevation = if (scrollView.scrollY > 0) {
                resources.getDimension(R.dimen.stripe_paymentsheet_toolbar_elevation)
            } else {
                0f
            }
        }

        bottomSheet.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        fragmentContainerParent.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        bottomSheetController.setup()

        bottomSheetController.shouldFinish.observe(this) { shouldFinish ->
            if (shouldFinish) {
                finish()
            }
        }

        viewModel.userMessage.observe(this) { userMessage ->
            messageView.isVisible = userMessage != null
            messageView.text = userMessage?.message
        }

        viewModel.processing.observe(this) { isProcessing ->
            updateRootViewClickHandling(isProcessing)
            toolbar.isEnabled = !isProcessing
        }

        toolbar.setNavigationOnClickListener {
            if (toolbar.isEnabled) {
                if (supportFragmentManager.backStackEntryCount == 0) {
                    onUserCancel()
                } else {
                    onUserBack()
                }
            }
        }

        updateToolbarButton(true)

        // Make `bottomSheet` clickable to prevent clicks on the bottom sheet from triggering
        // `rootView`'s click listener
        bottomSheet.isClickable = true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            viewModel.onBackPressed()
            super.onBackPressed()
        } else {
            onUserCancel()
        }
    }

    protected fun closeSheet(
        result: ResultType
    ) {
        setActivityResult(result)
        bottomSheetController.hide()
    }

    private fun updateToolbarButton(isStackEmpty: Boolean) {
        val toolbarResources = if (isStackEmpty) {
            ToolbarResources(
                R.drawable.stripe_paymentsheet_toolbar_close,
                R.string.stripe_paymentsheet_close
            )
        } else {
            ToolbarResources(
                R.drawable.stripe_paymentsheet_toolbar_back,
                R.string.stripe_paymentsheet_back
            )
        }

        toolbar.navigationIcon = ContextCompat.getDrawable(this, toolbarResources.icon)
        toolbar.navigationContentDescription = resources.getString(toolbarResources.description)
    }

    private fun updateRootViewClickHandling(isProcessing: Boolean) {
        if (!isProcessing) {
            // Handle taps outside of bottom sheet
            rootView.setOnClickListener {
                onUserCancel()
            }
        } else {
            rootView.setOnClickListener(null)
            rootView.isClickable = false
        }
    }

    protected fun onUserBack() {
        keyboardController.hide()
        onBackPressed()
    }

    internal companion object {
        const val EXTRA_FRAGMENT_CONFIG = "com.stripe.android.paymentsheet.extra_fragment_config"
        const val EXTRA_STARTER_ARGS = "com.stripe.android.paymentsheet.extra_starter_args"
    }

    internal data class ToolbarResources(
        @DrawableRes val icon: Int,
        @StringRes val description: Int
    )
}
