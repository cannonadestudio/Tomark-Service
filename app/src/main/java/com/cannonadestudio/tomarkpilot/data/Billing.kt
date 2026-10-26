package com.cannonadestudio.tomarkpilot.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-app purchase "AeroHangar Pro" (Google Play, one-time, not a subscription).
 * Free: [FREE_AIRCRAFT] aircraft with every feature. Pro: any number of aircraft.
 *
 * In Play Console create a one-time product with the id [PRO_ID]. Until the app is published there
 * (or when it is installed from outside Google Play) the product is not found and the app says so.
 */
object Billing : PurchasesUpdatedListener {
    const val PRO_ID = "aerohangar_pro"
    const val FREE_AIRCRAFT = 1

    data class Pro(
        val owned: Boolean = false,
        /** Product found in Google Play – buying is possible. */
        val available: Boolean = false,
        val price: String? = null,
        /** Payment started but not completed yet (e.g. cash at a shop). */
        val pending: Boolean = false,
        val checked: Boolean = false,
    )

    private val _pro = MutableStateFlow(Pro())
    val pro: StateFlow<Pro> = _pro.asStateFlow()

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (client != null) return
        // Last known state while Google Play answers (or when offline).
        _pro.value = Pro(owned = Repo.state.value.proCached)
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        val c = client ?: return
        runCatching {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
                    else _pro.value = _pro.value.copy(checked = true)
                }
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    /** Re-reads the product price and the user's purchases (also "Restore purchase"). */
    suspend fun refresh() {
        val c = client ?: return
        if (!c.isReady) { connect(); return }
        runCatching {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_ID).setProductType(BillingClient.ProductType.INAPP).build()
            val pd = c.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
            details = pd.productDetailsList?.firstOrNull()
            val pr = c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
            if (pr.billingResult.responseCode == BillingClient.BillingResponseCode.OK) handle(pr.purchasesList, fromQuery = true)
        }
        _pro.value = _pro.value.copy(
            available = details != null,
            price = details?.oneTimePurchaseOfferDetails?.formattedPrice,
            checked = true,
        )
    }

    private suspend fun handle(list: List<Purchase>, fromQuery: Boolean) {
        val mine = list.filter { PRO_ID in it.products }
        val bought = mine.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = mine.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        // Google refunds purchases that are not acknowledged within 3 days.
        if (bought != null && !bought.isAcknowledged) {
            runCatching {
                client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build())
            }
        }
        val owned = bought != null
        if (fromQuery || owned || pending) {
            _pro.value = _pro.value.copy(owned = owned, pending = pending && !owned)
            Repo.setProCached(owned)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> if (purchases != null) scope.launch { handle(purchases, fromQuery = false) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            else -> Unit
        }
    }

    /** Opens the Google Play payment sheet. False when the product is not available (not on Play yet). */
    fun buy(activity: Activity): Boolean {
        val d = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        return client?.launchBillingFlow(activity, params)?.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Aircraft owner with Pro: up to this many aircraft. */
    const val PRO_AIRCRAFT = 3

    /**
     * Free: one aircraft. Pro (aircraft owner): up to [PRO_AIRCRAFT]. The account's limit from the website wins
     * (owner plan 3, mechanic / school / dealer / service no limit). Aircraft added before a limit existed stay.
     */
    fun canAddAircraft(s: AppState = Repo.state.value): Boolean {
        s.accountMaxAircraft?.let { return s.aircraft.size < it }
        return s.aircraft.size < FREE_AIRCRAFT || (isPro(s) && s.aircraft.size < PRO_AIRCRAFT)
    }

    /** Bought in Google Play or granted by the app owner in the server panel. */
    fun isPro(s: AppState = Repo.state.value) = _pro.value.owned || s.proGranted || s.accountPro
}
