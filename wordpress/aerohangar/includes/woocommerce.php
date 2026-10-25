<?php
/**
 * WooCommerce: selling access to the AeroHangar service (Pro for the account – pilots, mechanics, anyone).
 *
 * Product → "AeroHangar" box: "gives access" + plan (pilot / mechanic / school / dealer / service) + duration in days
 * (0 = no end) + optional limits (aircraft, own types). "Utwórz produkty AeroHangar" (AeroHangar → Informacje) makes the
 * five monthly plans – as WooCommerce Subscriptions products (automatic payment) when that plugin is active.
 * Paid order (processing/completed) → access added to the buyer's WordPress account (time is added on top of
 * an active access). Refund / cancellation → that order's access removed. WooCommerce Subscriptions (optional):
 * active subscription = access, expired / cancelled = no access.
 * The buyer must have an account: checkout requires creating one when the cart contains an access product.
 * My Account → "AeroHangar": the Cannonade account key for the apps.
 */
defined('ABSPATH') || exit;

/** Access granted by purchases: ['until' => unix|0 (0 = no end), 'plan' => …, 'orders' => [orderId => [until, plan]]] */
function ah_access(int $uid): array
{
    $a = get_user_meta($uid, 'aerohangar_access', true);
    return is_array($a) ? $a + ['until' => null, 'plan' => '', 'orders' => []] : ['until' => null, 'plan' => '', 'orders' => []];
}

function ah_access_active(int $uid): bool
{
    $a = ah_access($uid);
    if ($a['until'] === null) return false;
    return (int)$a['until'] === 0 || (int)$a['until'] > time();
}

/** Recomputes 'until' from all orders still counted (each order adds its days from its own start). */
function ah_access_recompute(int $uid, array $a): void
{
    $until = null; $plan = '';
    $orders = $a['orders'];
    uasort($orders, fn($x, $y) => ($x['start'] ?? 0) <=> ($y['start'] ?? 0));
    foreach ($orders as $o) {
        if (($o['days'] ?? 0) === 0) { $until = 0; $plan = $o['plan'] ?? $plan; break; }
        $start = max((int)($o['start'] ?? time()), $until ?: 0);
        $until = $start + (int)$o['days'] * DAY_IN_SECONDS;
        $plan = $o['plan'] ?? $plan;
    }
    // limits come from the most recent purchase that sets them
    $a['maxAircraft'] = null; $a['maxTypes'] = null;
    foreach ($orders as $o) foreach (['maxAircraft', 'maxTypes'] as $k) if (isset($o[$k]) && $o[$k] !== null) $a[$k] = (int)$o[$k];
    $a['until'] = $until; $a['plan'] = $plan;
    update_user_meta($uid, 'aerohangar_access', $a);
}

function ah_product_access(WC_Product $p): ?array
{
    $id = $p->get_parent_id() ?: $p->get_id();
    if (get_post_meta($id, '_aerohangar_access', true) !== 'yes') return null;
    $lim = fn($k) => ($v = get_post_meta($id, $k, true)) === '' || $v === false ? null : max(0, (int)$v);
    return ['plan' => (string)get_post_meta($id, '_aerohangar_plan', true) ?: 'pilot', 'days' => max(0, (int)get_post_meta($id, '_aerohangar_days', true)),
        'maxAircraft' => $lim('_aerohangar_max_aircraft'), 'maxTypes' => $lim('_aerohangar_max_types')];
}

/** Subscription products are handled by the subscription's status, not by the order. */
function ah_is_subscription(WC_Product $p): bool
{
    return class_exists('WC_Subscriptions_Product') && WC_Subscriptions_Product::is_subscription($p);
}

function ah_order_grant(int $order_id): void
{
    $order = wc_get_order($order_id);
    if (!$order || !$order->get_user_id()) return;
    $uid = (int)$order->get_user_id();
    $a = ah_access($uid);
    if (isset($a['orders'][$order_id])) return;   // already counted
    foreach ($order->get_items() as $item) {
        $p = $item->get_product();
        $acc = $p ? ah_product_access($p) : null;
        if (!$acc || ah_is_subscription($p)) continue;
        $qty = max(1, (int)$item->get_quantity());
        $a['orders'][$order_id] = ['start' => time(), 'days' => $acc['days'] ? $acc['days'] * $qty : 0, 'plan' => $acc['plan'], 'maxAircraft' => $acc['maxAircraft'], 'maxTypes' => $acc['maxTypes']];
        ah_access_recompute($uid, $a);
        $order->add_order_note('AeroHangar: dostęp przyznany (' . $acc['plan'] . ', ' . ($acc['days'] ? $acc['days'] * $qty . ' dni' : 'bez limitu') . ').');
        ah_log('woocommerce', "order #$order_id: access for user #$uid ({$acc['plan']})");
        return;
    }
}

function ah_order_revoke(int $order_id): void
{
    $order = wc_get_order($order_id);
    if (!$order || !$order->get_user_id()) return;
    $uid = (int)$order->get_user_id();
    $a = ah_access($uid);
    if (!isset($a['orders'][$order_id])) return;
    unset($a['orders'][$order_id]);
    if (!$a['orders']) { delete_user_meta($uid, 'aerohangar_access'); }
    else ah_access_recompute($uid, $a);
    $order->add_order_note('AeroHangar: dostęp z tego zamówienia odebrany.');
    ah_log('woocommerce', "order #$order_id: access removed for user #$uid");
}

add_action('plugins_loaded', function () {
    if (!class_exists('WooCommerce')) return;

    foreach (['processing', 'completed'] as $s) add_action("woocommerce_order_status_$s", 'ah_order_grant');
    foreach (['refunded', 'cancelled', 'failed'] as $s) add_action("woocommerce_order_status_$s", 'ah_order_revoke');

    // WooCommerce Subscriptions: the subscription itself decides (no end date while active).
    add_action('woocommerce_subscription_status_active', function ($sub) {
        $uid = (int)$sub->get_user_id();
        $a = ah_access($uid);
        $e = null;
        foreach ($sub->get_items() as $it) if (($p = $it->get_product()) && ($acc = ah_product_access($p))) $e = $acc;
        if (!$e) return;   // not an AeroHangar subscription
        $key = 'sub' . $sub->get_id();
        $a['orders'][$key] = ['start' => $a['orders'][$key]['start'] ?? time(), 'days' => 0, 'plan' => $e['plan'], 'maxAircraft' => $e['maxAircraft'], 'maxTypes' => $e['maxTypes']];
        ah_access_recompute($uid, $a);
    });
    foreach (['expired', 'cancelled', 'on-hold'] as $s) add_action("woocommerce_subscription_status_$s", function ($sub) {
        $uid = (int)$sub->get_user_id();
        $a = ah_access($uid);
        if (!isset($a['orders']['sub' . $sub->get_id()])) return;
        unset($a['orders']['sub' . $sub->get_id()]);
        if (!$a['orders']) delete_user_meta($uid, 'aerohangar_access'); else ah_access_recompute($uid, $a);
    });

    // Product settings box
    add_action('woocommerce_product_options_general_product_data', function () {
        echo '<div class="options_group"><p class="form-field"><strong>AeroHangar</strong></p>';
        woocommerce_wp_checkbox(['id' => '_aerohangar_access', 'label' => 'Daje dostęp AeroHangar', 'description' => 'Po opłaceniu zamówienia konto kupującego dostaje Pro (wszystkie jego telefony, klucz Cannonade).']);
        woocommerce_wp_select(['id' => '_aerohangar_plan', 'label' => 'Plan', 'options' => ah_plans()]);
        woocommerce_wp_text_input(['id' => '_aerohangar_days', 'label' => 'Czas dostępu (dni)', 'type' => 'number', 'custom_attributes' => ['min' => 0, 'step' => 1], 'description' => '365 = rok, 0 = bez limitu. Przy subskrypcji (WooCommerce Subscriptions) decyduje subskrypcja.', 'desc_tip' => true]);
        woocommerce_wp_text_input(['id' => '_aerohangar_max_aircraft', 'label' => 'Limit samolotów', 'type' => 'number', 'custom_attributes' => ['min' => 0, 'step' => 1], 'description' => 'Puste = bez limitu (Pro). Ustawienie przy koncie w panelu ma pierwszeństwo.', 'desc_tip' => true]);
        woocommerce_wp_text_input(['id' => '_aerohangar_max_types', 'label' => 'Limit własnych typów', 'type' => 'number', 'custom_attributes' => ['min' => 0, 'step' => 1], 'description' => 'Ile własnych typów samolotów konto może dodać. Puste = bez limitu.', 'desc_tip' => true]);
        echo '</div>';
    });
    add_action('woocommerce_admin_process_product_object', function (WC_Product $p) {
        $p->update_meta_data('_aerohangar_access', !empty($_POST['_aerohangar_access']) ? 'yes' : 'no');
        $p->update_meta_data('_aerohangar_plan', sanitize_key($_POST['_aerohangar_plan'] ?? 'pilot'));
        $p->update_meta_data('_aerohangar_days', max(0, (int)($_POST['_aerohangar_days'] ?? 365)));
        foreach (['_aerohangar_max_aircraft', '_aerohangar_max_types'] as $k) {
            $v = trim((string)($_POST[$k] ?? ''));
            $p->update_meta_data($k, $v === '' ? '' : max(0, min(999, (int)$v)));
        }
    });

    // Access products need an account: no guest checkout for them, and the account is created at checkout
    // (even when open registration is switched off on the site – then buying is the only way to get an account).
    $hasAccess = function (): bool {
        if (!function_exists('WC') || !WC()->cart) return false;
        foreach (WC()->cart->get_cart() as $ci) if (!empty($ci['data']) && ah_product_access($ci['data'])) return true;
        return false;
    };
    add_filter('woocommerce_checkout_registration_required', fn($required) => $hasAccess() ? true : $required);
    add_filter('woocommerce_checkout_registration_enabled', fn($enabled) => $hasAccess() ? true : $enabled);

    // My Account → AeroHangar (key + status)
    add_action('init', fn() => add_rewrite_endpoint('aerohangar', EP_ROOT | EP_PAGES));
    add_filter('woocommerce_account_menu_items', function ($items) {
        $out = [];
        foreach ($items as $k => $v) { $out[$k] = $v; if ($k === 'dashboard') $out['aerohangar'] = 'AeroHangar / Pilot Logbook'; }
        return $out ?: $items + ['aerohangar' => 'AeroHangar / Pilot Logbook'];
    });
    add_action('woocommerce_account_aerohangar_endpoint', function () {
        $uid = get_current_user_id();
        $a = ah_access($uid);
        if (ah_access_active($uid)) {
            echo '<p><b>' . esc_html(cannonade_t('access_active')) . '</b> ' . esc_html((int)$a['until'] === 0 ? cannonade_t('access_unlimited') : sprintf(cannonade_t('access_until'), wp_date('Y-m-d', (int)$a['until']))) . '</p>';
        }
        echo cannonade_account_html();
    });
});

/** The five monthly plans (net prices in PLN). */
const AH_PRODUCTS = [
    'pilot'    => ['Pilot', 10],
    'mechanic' => ['Mechanik', 35],
    'school'   => ['Szkoła lotnicza', 400],
    'dealer'   => ['Dealer', 500],
    'service'  => ['Serwis', 500],
];

/** Shop price for a net amount: when the shop enters prices with tax, the base-country standard rate is added. */
function ah_shop_price(float $net): array
{
    if (!wc_prices_include_tax()) return [wc_format_decimal($net, 2), 'netto (sklep liczy VAT przy zamówieniu)'];
    $rate = 0.0;
    foreach (WC_Tax::get_base_tax_rates('') as $r) $rate += (float)$r['rate'];
    if ($rate <= 0) return [wc_format_decimal($net, 2), 'UWAGA: ceny w sklepie są brutto, a nie ma stawki VAT – wpisano kwotę netto'];
    return [wc_format_decimal(round($net * (1 + $rate / 100), 2), 2), sprintf('brutto z VAT %s%%', rtrim(rtrim(number_format($rate, 2, '.', ''), '0'), '.'))];
}

/**
 * Creates (or brings up to date) the five AeroHangar monthly products. With WooCommerce Subscriptions they are
 * subscriptions – billed every month automatically by the payment gateway – and published. Without it they are
 * 30-day products saved as drafts (no automatic payment), and become subscriptions when this runs again later.
 * Prices / names / limits changed by hand afterwards are not overwritten.
 */
function ah_create_products(): array
{
    $subs = class_exists('WC_Product_Subscription');
    $ids = (array)get_option('aerohangar_products', []);
    $msg = [];
    foreach (AH_PRODUCTS as $plan => [$name, $net]) {
        $id = (int)($ids[$plan] ?? 0);
        $p = $id ? wc_get_product($id) : null;
        if ($p && get_post_status($id) === 'trash') $p = null;
        [$price, $how] = ah_shop_price((float)$net);
        if ($p) {
            if ($subs && !$p->is_type('subscription')) {
                wp_set_object_terms($id, 'subscription', 'product_type');
                $p = wc_get_product($id);
                foreach (['_subscription_price' => $p->get_regular_price() ?: $price, '_subscription_period' => 'month', '_subscription_period_interval' => '1', '_subscription_length' => '0', '_subscription_trial_length' => '0', '_subscription_sign_up_fee' => ''] as $k => $v) $p->update_meta_data($k, $v);
                $p->update_meta_data('_aerohangar_days', 0);
                $p->set_status('publish');
                $p->save();
                $msg[] = "$name: zmieniono na subskrypcję miesięczną i opublikowano";
            } else $msg[] = "$name: już jest (#$id)";
            continue;
        }
        $p = $subs ? new WC_Product_Subscription() : new WC_Product_Simple();
        $p->set_name("AeroHangar – $name (abonament miesięczny)");
        $p->set_slug('aerohangar-' . $plan);
        $p->set_regular_price($price);
        $p->set_virtual(true);
        $p->set_sold_individually(true);
        $p->set_tax_status('taxable');
        $p->set_catalog_visibility('visible');
        $p->set_short_description("Dostęp do AeroHangar dla planu „{$name}” – {$net} zł netto miesięcznie" . ($subs ? ', płatność odnawia się automatycznie co miesiąc. Rezygnacja w Moje konto → Subskrypcje.' : '.'));
        $p->set_description('Po opłaceniu konto dostaje AeroHangar Pro (wszystkie telefony połączone kluczem Cannonade z Moje konto → AeroHangar). Uprawnienia, limit samolotów i typów ustawia administrator.');
        $p->update_meta_data('_aerohangar_access', 'yes');
        $p->update_meta_data('_aerohangar_plan', $plan);
        $p->update_meta_data('_aerohangar_days', $subs ? 0 : 30);
        $p->update_meta_data('_aerohangar_max_aircraft', '');
        $p->update_meta_data('_aerohangar_max_types', '');
        if ($subs) foreach (['_subscription_price' => $price, '_subscription_period' => 'month', '_subscription_period_interval' => '1', '_subscription_length' => '0', '_subscription_trial_length' => '0', '_subscription_sign_up_fee' => ''] as $k => $v) $p->update_meta_data($k, $v);
        $p->set_status($subs ? 'publish' : 'draft');
        $ids[$plan] = $p->save();
        $msg[] = "$name: utworzono #{$ids[$plan]} – $price " . get_woocommerce_currency() . " ($how)" . ($subs ? ', subskrypcja co miesiąc' : ', szkic (30 dni, bez automatycznej płatności)');
    }
    update_option('aerohangar_products', $ids, false);
    ah_log(wp_get_current_user()->user_login ?: 'system', 'WooCommerce products: ' . implode('; ', $msg));
    return $msg;
}

register_activation_hook(AEROHANGAR_DIR . '/aerohangar.php', function () {
    add_rewrite_endpoint('aerohangar', EP_ROOT | EP_PAGES);
    flush_rewrite_rules();
});
