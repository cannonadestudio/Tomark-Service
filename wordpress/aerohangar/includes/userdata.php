<?php
/**
 * AeroHangar data of a pilot account (Cannonade key): backup of the app's aircraft, maintenance records,
 * bulletin statuses and own entries; Pro per account. REST: /wp-json/aerohangar/v1/account[/data]
 */
defined('ABSPATH') || exit;

const AH_USERDATA_MAX = 3 * 1024 * 1024;   // 3 MB per account

add_action('rest_api_init', function () {
    register_rest_route('aerohangar/v1', '/account', [
        'methods' => 'GET', 'permission_callback' => '__return_true',
        'callback' => function (WP_REST_Request $r) {
            $uid = ah_app_user($r);
            if (is_wp_error($uid)) return $uid;
            $u = get_userdata($uid);
            return ['name' => $u->display_name] + ah_permissions($uid) + ['lastSync' => (int)get_user_meta($uid, 'aerohangar_state_time', true),
                'lastDevice' => (string)get_user_meta($uid, 'aerohangar_state_device', true),
                'aircraft' => (int)get_user_meta($uid, 'aerohangar_aircraft', true), 'logbook' => has_filter('cannonade_logbook_hours') !== false];
        },
    ]);
    register_rest_route('aerohangar/v1', '/account/data', [
        [
            'methods' => 'GET', 'permission_callback' => '__return_true',
            'callback' => function (WP_REST_Request $r) {
                $uid = ah_app_user($r);
                if (is_wp_error($uid)) return $uid;
                $raw = (string)get_user_meta($uid, 'aerohangar_state', true);
                if ($raw === '') return new WP_Error('aerohangar_empty', 'no backup on this account yet', ['status' => 404]);
                do_action('aerohangar_state_downloaded', $uid);
                $res = new WP_REST_Response(ah_state_maps(json_decode($raw, true)));
                $res->header('X-AeroHangar-Saved', (string)get_user_meta($uid, 'aerohangar_state_time', true));
                return $res;
            },
        ],
        [
            'methods' => 'PUT', 'permission_callback' => '__return_true',
            'callback' => function (WP_REST_Request $r) {
                $uid = ah_app_user($r);
                if (is_wp_error($uid)) return $uid;
                $raw = (string)$r->get_body();
                if (strlen($raw) > AH_USERDATA_MAX) return new WP_Error('aerohangar_big', 'backup too large', ['status' => 413]);
                $d = json_decode($raw, true);
                if (!is_array($d) || !isset($d['aircraft']) || !is_array($d['aircraft'])) return new WP_Error('aerohangar_bad', 'not an AeroHangar backup', ['status' => 400]);
                // Never keep device-only fields or anything that could grant Pro.
                unset($d['proCached'], $d['proGranted'], $d['installId'], $d['backupUri'], $d['logbookUri'], $d['accountKey']);
                // changes made in the browser that this phone has not seen yet are applied on top
                $d = apply_filters('aerohangar_incoming_state', $d, $uid);
                $merged = !empty($d['__merged']);
                unset($d['__merged']);
                update_user_meta($uid, 'aerohangar_state', wp_slash(wp_json_encode($d, JSON_UNESCAPED_UNICODE)));
                update_user_meta($uid, 'aerohangar_state_time', time());
                update_user_meta($uid, 'aerohangar_aircraft', count($d['aircraft']));
                update_user_meta($uid, 'aerohangar_state_device', $merged ? 'web' : substr(sanitize_key((string)$r->get_header('x-aerohangar-device')), 0, 40));
                // merged: report an older time, so the phone pulls the combined copy at its next refresh
                return ['ok' => true, 'saved' => $merged ? time() - 1 : time(), 'merged' => $merged] + ah_permissions($uid);
            },
        ],
    ]);
});

/**
 * What the administrator allows this account in AeroHangar (panel → Konta użytkowników, or the user's profile):
 *   aerohangar_blocked      1 = no access for the apps
 *   aerohangar_pro          1 = Pro granted by hand, aerohangar_pro_until = unix time (0/empty = no end)
 *   aerohangar_plan         pilot | mechanic | school | dealer | service (label; WooCommerce purchases set it too)
 *   aerohangar_max_aircraft number of aircraft allowed in the app ('' = from the bought product, else 1 free / unlimited with Pro)
 *   aerohangar_max_types    number of own aircraft types allowed ('' = from the bought product, else unlimited)
 * The panel role (none / viewer / editor) is a WordPress capability on the user.
 */
function ah_permissions(int $uid): array
{
    $blocked = (bool)get_user_meta($uid, 'aerohangar_blocked', true);
    $acc = ah_access_active($uid) ? ah_access($uid) : null;
    // the administrator's value wins; otherwise the limit of the product the account bought
    $lim = function (string $meta, string $k) use ($uid, $acc) {
        $v = get_user_meta($uid, $meta, true);
        if ($v !== '' && $v !== false) return max(0, (int)$v);
        return isset($acc[$k]) && $acc[$k] !== null && $acc[$k] !== '' ? max(0, (int)$acc[$k]) : null;
    };
    $plan = (string)(get_user_meta($uid, 'aerohangar_plan', true) ?: ($acc['plan'] ?? ''));
    $maxA = $lim('aerohangar_max_aircraft', 'maxAircraft');
    // Plan defaults when nothing is set: aircraft owner up to 3, businesses no limit (999 = no limit in the app).
    if ($maxA === null && !$blocked && ah_account_pro($uid)) $maxA = ah_plan_max_aircraft($plan);
    return ['blocked' => $blocked, 'pro' => !$blocked && ah_account_pro($uid),
        'plan' => $plan,
        'maxAircraft' => $maxA, 'maxTypes' => $lim('aerohangar_max_types', 'maxTypes'),
        'caps' => function_exists('ah_caps') ? ah_caps($uid) : []];
}

/** Pro granted by the owner to the whole account (all the pilot's phones): by hand (optionally until a date) or bought. */
function ah_account_pro(int $uid): bool
{
    if (get_user_meta($uid, 'aerohangar_blocked', true)) return false;
    $until = (int)get_user_meta($uid, 'aerohangar_pro_until', true);
    $manual = (bool)get_user_meta($uid, 'aerohangar_pro', true) && ($until === 0 || $until > time());
    return $manual || ah_access_active($uid);
}

/** App request user, refused when the administrator blocked the account. */
function ah_app_user(WP_REST_Request $r)
{
    $uid = cannonade_user_from_request($r);
    if (is_wp_error($uid)) return $uid;
    if (get_user_meta($uid, 'aerohangar_blocked', true)) return new WP_Error('aerohangar_blocked', 'account blocked by the administrator', ['status' => 403]);
    return $uid;
}

function ah_panel_role(WP_User $u): string
{
    if ($u->has_cap('manage_options')) return 'admin';
    if ($u->has_cap('aerohangar_edit')) return 'editor';
    if ($u->has_cap('aerohangar_view')) return 'viewer';
    return '';
}

/** Saves the administrator's settings for one account (panel). */
function ah_account_save(int $uid, array $b, string $by): void
{
    $u = get_userdata($uid);
    update_user_meta($uid, 'aerohangar_blocked', !empty($b['blocked']) ? 1 : 0);
    update_user_meta($uid, 'aerohangar_pro', !empty($b['pro']) ? 1 : 0);
    $until = !empty($b['proUntil']) && preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$b['proUntil']) ? strtotime($b['proUntil'] . ' 23:59:59 UTC') : 0;
    update_user_meta($uid, 'aerohangar_pro_until', $until);
    update_user_meta($uid, 'aerohangar_plan', isset(ah_plans()[$b['plan'] ?? '']) ? $b['plan'] : '');
    $max = $b['maxAircraft'] ?? '';
    update_user_meta($uid, 'aerohangar_max_aircraft', ($max === '' || $max === null) ? '' : max(0, min(999, (int)$max)));
    $mt = $b['maxTypes'] ?? '';
    update_user_meta($uid, 'aerohangar_max_types', ($mt === '' || $mt === null) ? '' : max(0, min(999, (int)$mt)));
    update_user_meta($uid, 'aerohangar_note', sanitize_text_field((string)($b['note'] ?? '')));
    // capabilities: explicit list, or plan defaults
    if (array_key_exists('caps', $b)) {
        if ($b['caps'] === null || $b['caps'] === 'default') delete_user_meta($uid, 'aerohangar_caps');
        else update_user_meta($uid, 'aerohangar_caps', array_values(array_intersect(array_keys(ah_caps_all()), (array)$b['caps'])));
    }
    if (array_key_exists('panel', $b) && function_exists('ah_panel_sections')) {
        $m = [];
        foreach (ah_panel_sections() as $k => $_) if (in_array(($b['panel'][$k] ?? ''), ['view', 'edit'], true)) $m[$k] = $b['panel'][$k];
        if ($m) update_user_meta($uid, 'aerohangar_panel', $m); else delete_user_meta($uid, 'aerohangar_panel');
    }
    if (array_key_exists('contactId', $b)) {
        $cid = sanitize_text_field((string)$b['contactId']);
        if ($cid === '') delete_user_meta($uid, 'aerohangar_contact_id');
        elseif (in_array($cid, array_column(ah_contacts_brief(), 'id'), true)) update_user_meta($uid, 'aerohangar_contact_id', $cid);
    }
    // panel role (never changes administrators)
    if (!$u->has_cap('manage_options')) {
        $role = (string)($b['panelRole'] ?? '');
        foreach (['aerohangar_view', 'aerohangar_edit'] as $c) $u->remove_cap($c);
        if ($role === 'viewer') $u->add_cap('aerohangar_view');
        if ($role === 'editor') { $u->add_cap('aerohangar_view'); $u->add_cap('aerohangar_edit'); }
    }
    ah_log($by, "account #$uid ({$u->user_login}): " . wp_json_encode(array_intersect_key($b, array_flip(['blocked', 'pro', 'proUntil', 'plan', 'maxAircraft', 'maxTypes', 'panelRole', 'caps', 'contactId', 'panel']))));
}

// Status line on the account page and in /cannonade/v1/me
add_filter('cannonade_account_apps', function (array $apps, int $uid) {
    $t = (int)get_user_meta($uid, 'aerohangar_state_time', true);
    $apps['aerohangar'] = [
        'name' => 'AeroHangar',
        'status' => sprintf(cannonade_t('ah_status'), $t ? wp_date('Y-m-d H:i', $t) : cannonade_t('never'), (int)get_user_meta($uid, 'aerohangar_aircraft', true), ah_account_pro($uid) ? cannonade_t('pro') : ''),
        'pro' => ah_account_pro($uid), 'lastSync' => $t,
    ];
    return $apps;
}, 10, 2);

/** Panel list: all WordPress accounts (search by login / name / e-mail), most recently active first. */
function ah_accounts_list(string $q = ''): array
{
    $args = ['number' => 500, 'orderby' => 'registered', 'order' => 'DESC'];
    if ($q !== '') { $args['search'] = '*' . $q . '*'; $args['search_columns'] = ['user_login', 'user_email', 'display_name', 'user_nicename']; }
    $out = [];
    foreach (get_users($args) as $u) {
        $acc = ah_access_active($u->ID) ? ah_access($u->ID) : null;
        $until = (int)get_user_meta($u->ID, 'aerohangar_pro_until', true);
        $max = get_user_meta($u->ID, 'aerohangar_max_aircraft', true);
        $mt = get_user_meta($u->ID, 'aerohangar_max_types', true);
        $out[] = ['id' => $u->ID, 'login' => $u->user_login, 'name' => $u->display_name, 'email' => $u->user_email,
            'firstName' => (string)$u->first_name, 'lastName' => (string)$u->last_name, 'wpRoleKey' => (string)($u->roles[0] ?? ''),
            'phone' => (string)get_user_meta($u->ID, 'billing_phone', true), 'isAdmin' => user_can($u->ID, 'manage_options'),
            'registered' => substr($u->user_registered, 0, 10), 'wpRole' => implode(', ', $u->roles),
            'key' => (bool)get_user_meta($u->ID, 'cannonade_key_hash', true), 'keyHint' => (string)get_user_meta($u->ID, 'cannonade_key_hint', true), 'keyFull' => cannonade_key_plain($u->ID),
            'lastSeen' => (int)get_user_meta($u->ID, 'cannonade_last_seen', true), 'lastSync' => (int)get_user_meta($u->ID, 'aerohangar_state_time', true),
            'aircraft' => (int)get_user_meta($u->ID, 'aerohangar_aircraft', true),
            'blocked' => (bool)get_user_meta($u->ID, 'aerohangar_blocked', true), 'pro' => (bool)get_user_meta($u->ID, 'aerohangar_pro', true),
            'proUntil' => $until ? gmdate('Y-m-d', $until) : '', 'plan' => (string)get_user_meta($u->ID, 'aerohangar_plan', true),
            'maxAircraft' => ($max === '' || $max === false) ? '' : (int)$max,
            'maxTypes' => ($mt === '' || $mt === false) ? '' : (int)$mt, 'note' => (string)get_user_meta($u->ID, 'aerohangar_note', true),
            'panelRole' => ah_panel_role($u), 'proActive' => ah_account_pro($u->ID), 'hasAccess' => ah_has_access($u->ID),
            'capsCustom' => ah_caps_custom($u->ID), 'caps' => ah_caps($u->ID), 'contactId' => ah_user_contact_id($u->ID),
            'panel' => (object)ah_user_sections($u->ID), 'clientPanel' => ah_client_panel($u->ID),
            'access' => $acc ? ['plan' => $acc['plan'], 'until' => (int)$acc['until'], 'maxAircraft' => $acc['maxAircraft'] ?? null, 'maxTypes' => $acc['maxTypes'] ?? null] : null];
    }
    usort($out, fn($a, $b) => max($b['lastSeen'], $b['lastSync']) <=> max($a['lastSeen'], $a['lastSync']));
    return $out;
}

// Column in wp-admin → Users
add_filter('manage_users_columns', fn($c) => $c + ['aerohangar' => 'AeroHangar']);
add_filter('manage_users_custom_column', function ($v, $col, $uid) {
    if ($col !== 'aerohangar') return $v;
    $p = ah_permissions((int)$uid);
    if ($p['blocked']) return '<span style="color:#C62828">zablokowany</span>';
    $bits = array_filter([$p['pro'] ? 'Pro' : 'bez dostępu', $p['plan'] ?: '', get_user_meta($uid, 'cannonade_key_hash', true) ? 'klucz ✓' : '']);
    return esc_html(implode(' · ', $bits)) . ' · <a href="' . esc_url(admin_url('admin.php?page=aerohangar-p-accounts')) . '">zarządzaj</a>';
}, 10, 3);

/**
 * Paid service: an account may create and use the app key only when it has access – bought in WooCommerce,
 * granted by the administrator (Pro by hand), or it is a panel user / administrator. Blocked = never.
 */
function ah_has_access(int $uid): bool
{
    if (!$uid || get_user_meta($uid, 'aerohangar_blocked', true)) return false;
    return user_can($uid, 'manage_options') || user_can($uid, 'aerohangar_view') || ah_account_pro($uid);
}
add_filter('cannonade_can_have_key', fn($ok, $uid) => $ok && ah_has_access((int)$uid), 10, 2);

/** Offer shown instead of the key to accounts without access: the published AeroHangar products. */
add_filter('cannonade_offer_html', function ($html, $uid) {
    if (!function_exists('wc_get_product')) return $html;
    $out = '';
    foreach ((array)get_option('aerohangar_products', []) as $id) {
        $p = wc_get_product((int)$id);
        if (!$p || $p->get_status() !== 'publish') continue;
        $plan = (string)get_post_meta($p->get_id(), '_aerohangar_plan', true) ?: 'pilot';
        $pm = get_post_meta($p->get_id(), '_aerohangar_max_aircraft', true);
        $limit = ($pm !== '' && $pm !== false) ? ((int)$pm >= 999 ? 'bez limitu samolotów' : 'do ' . (int)$pm . ' samolotów') : ah_plan_limit_text($plan);
        $out .= '<div><b>' . esc_html(preg_replace('/^AeroHangar – /u', '', $p->get_name())) . '</b>' . wp_kses_post($p->get_price_html())
            . '<div style="font-size:.9em;color:#55606f;margin-top:2px">' . esc_html($limit) . '</div>'
            . '<p style="margin:6px 0 0"><a class="cna-btn" href="' . esc_url($p->add_to_cart_url()) . '">' . esc_html(cannonade_t('buy')) . '</a></p></div>';
    }
    if (!$out) return $html;
    return $html . '<p class="cna-small" style="margin:8px 0 0"><b>Aplikacja AeroHangar jest bezpłatna dla jednego samolotu.</b> Za kolejne samoloty dopłacasz: właściciel samolotu może mieć maksymalnie 3 samoloty, a mechanik, szkoła lotnicza, dealer i serwis – bez limitu.</p><div class="cna-offer">' . $out . '</div>';
}, 10, 2);

/** Button to the management panel for the administrator and panel users. */
add_filter('cannonade_account_top_html', function ($html, $uid) {
    if (!user_can($uid, 'aerohangar_view') && !user_can($uid, 'manage_options')) return $html;
    return $html . '<p><a class="cna-btn" href="' . esc_url(admin_url('admin.php?page=aerohangar-panel')) . '">✈ ' . esc_html(cannonade_t('open_panel')) . '</a></p>';
}, 10, 2);

// Top toolbar (site and wp-admin): "AeroHangar – panel" for panel users.
add_action('admin_bar_menu', function (WP_Admin_Bar $bar) {
    if (!current_user_can('aerohangar_view') && !current_user_can('manage_options')) return;
    $bar->add_node(['id' => 'aerohangar-panel', 'title' => '<span class="ab-icon dashicons dashicons-airplane" style="top:2px"></span>AeroHangar', 'href' => admin_url('admin.php?page=aerohangar-panel')]);
    $bar->add_node(['id' => 'aerohangar-panel-open', 'parent' => 'aerohangar-panel', 'title' => 'Panel (samoloty, biuletyny, konta…)', 'href' => admin_url('admin.php?page=aerohangar-panel')]);
    $bar->add_node(['id' => 'aerohangar-panel-accounts', 'parent' => 'aerohangar-panel', 'title' => 'Konta użytkowników', 'href' => admin_url('admin.php?page=aerohangar-p-accounts')]);
    $bar->add_node(['id' => 'aerohangar-panel-info', 'parent' => 'aerohangar-panel', 'title' => 'Produkty i ustawienia', 'href' => admin_url('admin.php?page=aerohangar')]);
}, 80);

/**
 * Site menu items by CSS class (Wygląd → Menu → "Klasy CSS"):
 *   ah-panel  – only for the administrator / panel users (e.g. a link to the panel)
 *   ah-guest  – only for visitors who are not logged in
 *   ah-user   – only for logged-in users
 */
add_filter('wp_nav_menu_objects', function ($items) {
    $panel = current_user_can('aerohangar_view') || current_user_can('manage_options');
    $in = is_user_logged_in();
    $drop = [];
    foreach ($items as $k => $it) {
        $c = (array)$it->classes;
        if ((in_array('ah-panel', $c, true) && !$panel) || (in_array('ah-guest', $c, true) && $in) || (in_array('ah-user', $c, true) && !$in) || in_array((int)$it->menu_item_parent, $drop, true)) {
            $drop[] = (int)$it->ID;
            unset($items[$k]);
        }
    }
    return $items;
});

/** Default number of aircraft per plan: aircraft owner (pilot) 3, mechanic / school / dealer / service no limit. */
function ah_plan_max_aircraft(string $plan): int
{
    return in_array($plan, ['mechanic', 'school', 'dealer', 'service', 'club'], true) ? 999 : 3;
}

/** Text for the website: what each plan allows. */
function ah_plan_limit_text(string $plan): string
{
    return ah_plan_max_aircraft($plan) >= 999 ? 'bez limitu samolotów' : 'do ' . ah_plan_max_aircraft($plan) . ' samolotów';
}

// ---------------------------------------------------------------- WordPress account data (panel → Konta użytkowników)

/** WordPress roles the panel may give (administrators are changed only in wp-admin). */
function ah_wp_roles(): array
{
    $out = [];
    foreach (wp_roles()->get_names() as $k => $n) if ($k !== 'administrator') $out[$k] = translate_user_role($n);
    return $out;
}

/** Name, e-mail and WordPress role of an account. */
function ah_account_profile(int $uid, array $b, string $by)
{
    $u = get_userdata($uid);
    $email = sanitize_email((string)($b['email'] ?? $u->user_email));
    if (!is_email($email)) return new WP_Error('email', 'Niepoprawny adres e-mail.');
    $other = email_exists($email);
    if ($other && (int)$other !== $uid) return new WP_Error('email', 'Ten e-mail ma już inne konto.');
    $first = sanitize_text_field((string)($b['firstName'] ?? ''));
    $last = sanitize_text_field((string)($b['lastName'] ?? ''));
    $display = sanitize_text_field((string)($b['name'] ?? '')) ?: trim("$first $last") ?: $u->user_login;
    $r = wp_update_user(['ID' => $uid, 'user_email' => $email, 'display_name' => $display, 'first_name' => $first, 'last_name' => $last]);
    if (is_wp_error($r)) return $r;
    $role = sanitize_key((string)($b['wpRole'] ?? ''));
    if ($role !== '' && isset(ah_wp_roles()[$role]) && !user_can($uid, 'manage_options') && $uid !== get_current_user_id()) {
        $x = new WP_User($uid);
        $x->set_role($role);
    }
    if (function_exists('WC') && isset($b['phone'])) update_user_meta($uid, 'billing_phone', sanitize_text_field((string)$b['phone']));
    ah_log($by, "account #$uid ({$u->user_login}): profile " . wp_json_encode(['email' => $email, 'name' => $display, 'role' => $role]));
    return true;
}

/** Deletes a WordPress account (never yourself or an administrator); its posts go to the one deleting. */
function ah_account_delete(int $uid, string $by)
{
    $u = get_userdata($uid);
    if ($uid === get_current_user_id()) return new WP_Error('self', 'Nie możesz usunąć własnego konta.');
    if (user_can($uid, 'manage_options')) return new WP_Error('admin', 'Konto administratora usuwa się tylko w WordPressie (Użytkownicy).');
    require_once ABSPATH . 'wp-admin/includes/user.php';
    if (function_exists('cannonade_revoke_key')) cannonade_revoke_key($uid);
    $ok = wp_delete_user($uid, get_current_user_id());
    if (!$ok) return new WP_Error('delete', 'Nie udało się usunąć konta.');
    ah_log($by, "account #$uid ({$u->user_login}, {$u->user_email}) deleted");
    return true;
}

/** New account from the panel; optionally sends the "set your password" e-mail. */
function ah_account_create(array $b, string $by)
{
    $email = sanitize_email((string)($b['email'] ?? ''));
    if (!is_email($email)) return new WP_Error('email', 'Podaj poprawny e-mail.');
    if (email_exists($email)) return new WP_Error('email', 'Ten e-mail ma już konto.');
    $login = sanitize_user((string)($b['login'] ?? ''), true) ?: sanitize_user(strstr($email, '@', true), true);
    $base = $login ?: 'pilot'; $i = 1;
    while (username_exists($login)) $login = $base . (++$i);
    $role = sanitize_key((string)($b['wpRole'] ?? ''));
    if (!isset(ah_wp_roles()[$role])) $role = get_option('default_role', 'subscriber');
    $uid = wp_insert_user(['user_login' => $login, 'user_email' => $email, 'user_pass' => wp_generate_password(24),
        'display_name' => sanitize_text_field((string)($b['name'] ?? '')) ?: $login, 'role' => $role]);
    if (is_wp_error($uid)) return $uid;
    if (!empty($b['sendLink'])) wp_new_user_notification($uid, null, 'user');
    ah_log($by, "account #$uid ($login, $email) created in the panel");
    return (int)$uid;
}

/**
 * PHP turns empty JSON objects into [] – the app's map fields must stay objects ({}), otherwise the phone
 * cannot read the account copy. Applied to everything the server sends back.
 */
function ah_state_maps($d)
{
    if (!is_array($d)) return $d;
    foreach ((array)($d['aircraft'] ?? []) as $i => $a) {
        foreach (['speedOverrides', 'taskOverrides'] as $k) {
            if (!isset($a[$k]) || (is_array($a[$k]) && !$a[$k])) $d['aircraft'][$i][$k] = new stdClass();
        }
    }
    return $d;
}
