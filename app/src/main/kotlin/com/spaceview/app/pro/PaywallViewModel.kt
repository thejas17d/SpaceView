package com.spaceview.app.pro

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.billing.BillingCode
import com.spaceview.app.billing.OffersResult
import com.spaceview.app.billing.ProductOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the paywall can't sell right now. */
enum class PaywallProblem {
    /** No connection to Google Play. Retry. */
    OFFLINE,

    /** Play Store missing, blocked or without billing on this device. */
    UNAVAILABLE,

    /** Pro isn't on sale for this account / build yet (no products, or no licence key in this build). */
    NOT_ON_SALE,

    GENERIC,
}

/** A one-shot message after an action. */
enum class PaywallMessage {
    PURCHASE_PENDING,
    PURCHASE_FAILED,
    PURCHASE_OFFLINE,
    PURCHASE_UNAVAILABLE,
    RESTORED,
    NOTHING_TO_RESTORE,
    RESTORE_PENDING,
    RESTORE_FAILED,
}

sealed interface OffersUi {
    data object Loading : OffersUi

    data class Ready(val offers: List<ProductOffer>) : OffersUi

    data class Failed(val problem: PaywallProblem) : OffersUi
}

data class PaywallUiState(
    val feature: ProFeature,
    val entitlement: Entitlement = Entitlement.Unknown,
    val offers: OffersUi = OffersUi.Loading,
    val selectedBasePlan: String? = null,
    val busy: Boolean = false,
    val message: PaywallMessage? = null,
) {
    val selectedOffer: ProductOffer?
        get() = (offers as? OffersUi.Ready)?.offers?.firstOrNull { it.basePlanId == selectedBasePlan }
}

/**
 * The paywall (S-Paywall). A non-AR destination; it never opens over the camera and never holds a
 * result: closing it always returns to where the user was, with their data intact.
 *
 * Process death: the feature comes from the route, the selected plan from [SavedStateHandle]; a purchase
 * in flight is recovered by the repository's next query (and the entitlement flow updates this screen).
 */
class PaywallViewModel(
    feature: ProFeature,
    private val savedState: SavedStateHandle,
    private val repository: EntitlementRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PaywallUiState(feature, selectedBasePlan = savedState[KEY_SELECTED]))
    val state: StateFlow<PaywallUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.entitlement.collect { e -> _state.update { it.copy(entitlement = e) } } }
        load()
    }

    /** Loads the offers (and refreshes the entitlement so a Pro user sees "You're Pro", not a price). */
    fun load() {
        _state.update { it.copy(offers = OffersUi.Loading) }
        viewModelScope.launch {
            repository.refresh(RefreshReason.PAYWALL)
            if (!repository.purchasesEnabled) {
                _state.update { it.copy(offers = OffersUi.Failed(PaywallProblem.NOT_ON_SALE)) }
                return@launch
            }
            val offers = when (val r = repository.offers()) {
                is OffersResult.Failed -> OffersUi.Failed(problemFor(r.code))
                is OffersResult.Ok -> if (r.offers.isEmpty()) OffersUi.Failed(PaywallProblem.NOT_ON_SALE) else OffersUi.Ready(r.offers)
            }
            _state.update { s ->
                val selected = (offers as? OffersUi.Ready)?.let { OfferSelector.defaultSelection(it.offers, s.selectedBasePlan) }
                s.copy(offers = offers, selectedBasePlan = selected ?: s.selectedBasePlan)
            }
        }
    }

    fun select(basePlanId: String) {
        savedState[KEY_SELECTED] = basePlanId
        _state.update { it.copy(selectedBasePlan = basePlanId) }
    }

    fun buy(activity: Activity) {
        val s = _state.value
        val offer = s.selectedOffer ?: return
        // A second purchase while one is pending could charge twice (the UI disables Buy too).
        if (s.busy || s.entitlement is Entitlement.Pro || s.entitlement is Entitlement.Pending) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (val outcome = repository.purchase(activity, offer)) {
                PurchaseOutcome.Purchased, PurchaseOutcome.Cancelled -> null
                PurchaseOutcome.Pending -> PaywallMessage.PURCHASE_PENDING
                is PurchaseOutcome.AlreadyOwned -> if (outcome.restored) PaywallMessage.RESTORED else PaywallMessage.PURCHASE_FAILED
                PurchaseOutcome.NotConfigured -> PaywallMessage.PURCHASE_UNAVAILABLE
                is PurchaseOutcome.Failed -> when (problemFor(outcome.code)) {
                    PaywallProblem.OFFLINE -> PaywallMessage.PURCHASE_OFFLINE
                    PaywallProblem.UNAVAILABLE, PaywallProblem.NOT_ON_SALE -> PaywallMessage.PURCHASE_UNAVAILABLE
                    PaywallProblem.GENERIC -> PaywallMessage.PURCHASE_FAILED
                }
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun restore() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = restoreMessage(repository.refresh(RefreshReason.RESTORE_BUTTON))
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        private const val KEY_SELECTED = "paywall_selected_base_plan"

        fun problemFor(code: BillingCode): PaywallProblem = when (code) {
            BillingCode.NETWORK_ERROR, BillingCode.SERVICE_UNAVAILABLE -> PaywallProblem.OFFLINE
            BillingCode.BILLING_UNAVAILABLE, BillingCode.FEATURE_NOT_SUPPORTED -> PaywallProblem.UNAVAILABLE
            BillingCode.ITEM_UNAVAILABLE -> PaywallProblem.NOT_ON_SALE
            else -> PaywallProblem.GENERIC
        }

        fun restoreMessage(outcome: RefreshOutcome): PaywallMessage = when (outcome) {
            RefreshOutcome.ProActive -> PaywallMessage.RESTORED
            RefreshOutcome.Pending -> PaywallMessage.RESTORE_PENDING
            RefreshOutcome.NothingFound -> PaywallMessage.NOTHING_TO_RESTORE
            is RefreshOutcome.Failed, RefreshOutcome.Throttled -> PaywallMessage.RESTORE_FAILED
        }
    }
}
