<?php
/**
 * WooCommerce: selling access to the AeroHangar service (Pro for the account – pilots, mechanics, anyone).
 *
 * Product → "AeroHangar" box: "gives access" + plan (pilot / mechanic / club) + duration in days (0 = no end).
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
    $a['until'] = $until; $a['plan'] = $plan;
    update_user_meta($uid, 'aerohangar_access', $a);
}

function ah_product_access(WC_Product $p): ?array
{
    $id = $p->get_parent_id() ?: $p->get_id();
    if (get_post_meta($id, '_aerohangar_access', true) !== 'yes') return null;
    return ['plan' => (string)get_post_meta($id, '_aerohangar_plan', true) ?: 'pilot', 'days' => max(0, (int)get_post_meta($id, '_aerohangar_days', true))];
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
        if (!$acc) continue;
        $qty = max(1, (int)$item->get_quantity());
        $a['orders'][$order_id] = ['start' => time(), 'days' => $acc['days'] ? $acc['days'] * $qty : 0, 'plan' => $acc['plan']];
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
        $plan = 'pilot';
        foreach ($sub->get_items() as $it) if (($p = $it->get_product()) && ($acc = ah_product_access($p))) $plan = $acc['plan'];
        $a['orders']['sub' . $sub->get_id()] = ['start' => time(), 'days' => 0, 'plan' => $plan];
        ah_access_recompute($uid, $a);
    });
    foreach (['expired', 'cancelled', 'on-hold'] as $s) add_action("woocommerce_subscription_status_$s", function ($sub) {
        $uid = (int)$sub->get_user_id();
        $a = ah_access($uid);
        unset($a['orders']['sub' . $sub->get_id()]);
        if (!$a['orders']) delete_user_meta($uid, 'aerohangar_access'); else ah_access_recompute($uid, $a);
    });

    // Product settings box
    add_action('woocommerce_product_options_general_product_data', function () {
        echo '<div class="options_group"><p class="form-field"><strong>AeroHangar</strong></p>';
        woocommerce_wp_checkbox(['id' => '_aerohangar_access', 'label' => 'Daje dostęp AeroHangar', 'description' => 'Po opłaceniu zamówienia konto kupującego dostaje Pro (wszystkie jego telefony, klucz Cannonade).']);
        woocommerce_wp_select(['id' => '_aerohangar_plan', 'label' => 'Plan', 'options' => ['pilot' => 'Pilot / właściciel', 'mechanic' => 'Mechanik / serwis', 'club' => 'Aeroklub / szkoła']]);
        woocommerce_wp_text_input(['id' => '_aerohangar_days', 'label' => 'Czas dostępu (dni)', 'type' => 'number', 'custom_attributes' => ['min' => 0, 'step' => 1], 'description' => '365 = rok, 0 = bez limitu. Przy subskrypcji (WooCommerce Subscriptions) decyduje subskrypcja.', 'desc_tip' => true]);
        echo '</div>';
    });
    add_action('woocommerce_admin_process_product_object', function (WC_Product $p) {
        $p->update_meta_data('_aerohangar_access', !empty($_POST['_aerohangar_access']) ? 'yes' : 'no');
        $p->update_meta_data('_aerohangar_plan', sanitize_key($_POST['_aerohangar_plan'] ?? 'pilot'));
        $p->update_meta_data('_aerohangar_days', max(0, (int)($_POST['_aerohangar_days'] ?? 365)));
    });

    // Access products need an account (no guest checkout for them).
    add_filter('woocommerce_checkout_registration_required', function ($required) {
        if (!WC()->cart) return $required;
        foreach (WC()->cart->get_cart() as $ci) if (!empty($ci['data']) && ah_product_access($ci['data'])) return true;
        return $required;
    });

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

register_activation_hook(AEROHANGAR_DIR . '/aerohangar.php', function () {
    add_rewrite_endpoint('aerohangar', EP_ROOT | EP_PAGES);
    flush_rewrite_rules();
});
