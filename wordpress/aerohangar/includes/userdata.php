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
                $res = new WP_REST_Response(json_decode($raw, true));
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
                update_user_meta($uid, 'aerohangar_state', wp_slash(wp_json_encode($d, JSON_UNESCAPED_UNICODE)));
                update_user_meta($uid, 'aerohangar_state_time', time());
                update_user_meta($uid, 'aerohangar_aircraft', count($d['aircraft']));
                update_user_meta($uid, 'aerohangar_state_device', substr(sanitize_key((string)$r->get_header('x-aerohangar-device')), 0, 40));
                return ['ok' => true, 'saved' => time()] + ah_permissions($uid);
            },
        ],
    ]);
});

/**
 * What the administrator allows this account in AeroHangar (panel → Konta pilotów, or the user's profile):
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
    return ['blocked' => $blocked, 'pro' => !$blocked && ah_account_pro($uid),
        'plan' => (string)(get_user_meta($uid, 'aerohangar_plan', true) ?: ($acc['plan'] ?? '')),
        'maxAircraft' => $lim('aerohangar_max_aircraft', 'maxAircraft'), 'maxTypes' => $lim('aerohangar_max_types', 'maxTypes')];
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
    // panel role (never changes administrators)
    if (!$u->has_cap('manage_options')) {
        $role = (string)($b['panelRole'] ?? '');
        foreach (['aerohangar_view', 'aerohangar_edit'] as $c) $u->remove_cap($c);
        if ($role === 'viewer') $u->add_cap('aerohangar_view');
        if ($role === 'editor') { $u->add_cap('aerohangar_view'); $u->add_cap('aerohangar_edit'); }
    }
    ah_log($by, "account #$uid ({$u->user_login}): " . wp_json_encode(array_intersect_key($b, array_flip(['blocked', 'pro', 'proUntil', 'plan', 'maxAircraft', 'maxTypes', 'panelRole']))));
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
            'registered' => substr($u->user_registered, 0, 10), 'wpRole' => implode(', ', $u->roles),
            'key' => (bool)get_user_meta($u->ID, 'cannonade_key_hash', true), 'keyHint' => (string)get_user_meta($u->ID, 'cannonade_key_hint', true),
            'lastSeen' => (int)get_user_meta($u->ID, 'cannonade_last_seen', true), 'lastSync' => (int)get_user_meta($u->ID, 'aerohangar_state_time', true),
            'aircraft' => (int)get_user_meta($u->ID, 'aerohangar_aircraft', true),
            'blocked' => (bool)get_user_meta($u->ID, 'aerohangar_blocked', true), 'pro' => (bool)get_user_meta($u->ID, 'aerohangar_pro', true),
            'proUntil' => $until ? gmdate('Y-m-d', $until) : '', 'plan' => (string)get_user_meta($u->ID, 'aerohangar_plan', true),
            'maxAircraft' => ($max === '' || $max === false) ? '' : (int)$max,
            'maxTypes' => ($mt === '' || $mt === false) ? '' : (int)$mt, 'note' => (string)get_user_meta($u->ID, 'aerohangar_note', true),
            'panelRole' => ah_panel_role($u), 'proActive' => ah_account_pro($u->ID),
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
    if ($p['blocked']) return '<span style="color:#b0003a">zablokowany</span>';
    $bits = array_filter([$p['pro'] ? 'Pro' : 'darmowy', $p['plan'] ?: '', get_user_meta($uid, 'cannonade_key_hash', true) ? 'klucz ✓' : '']);
    return esc_html(implode(' · ', $bits)) . ' · <a href="' . esc_url(admin_url('admin.php?page=aerohangar-panel#accounts')) . '">zarządzaj</a>';
}, 10, 3);
