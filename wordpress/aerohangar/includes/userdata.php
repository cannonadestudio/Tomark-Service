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
            $uid = cannonade_user_from_request($r);
            if (is_wp_error($uid)) return $uid;
            $u = get_userdata($uid);
            return ['name' => $u->display_name, 'pro' => ah_account_pro($uid), 'lastSync' => (int)get_user_meta($uid, 'aerohangar_state_time', true),
                'aircraft' => (int)get_user_meta($uid, 'aerohangar_aircraft', true), 'logbook' => has_filter('cannonade_logbook_hours') !== false];
        },
    ]);
    register_rest_route('aerohangar/v1', '/account/data', [
        [
            'methods' => 'GET', 'permission_callback' => '__return_true',
            'callback' => function (WP_REST_Request $r) {
                $uid = cannonade_user_from_request($r);
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
                $uid = cannonade_user_from_request($r);
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
                return ['ok' => true, 'saved' => time(), 'pro' => ah_account_pro($uid)];
            },
        ],
    ]);
});

/** Pro granted by the owner to the whole account (all the pilot's phones). */
function ah_account_pro(int $uid): bool
{
    return (bool)get_user_meta($uid, 'aerohangar_pro', true);
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

/** Panel list: accounts that have a key or AeroHangar data. */
function ah_accounts_list(): array
{
    $ids = array_unique(array_merge(
        get_users(['meta_key' => 'cannonade_key_hash', 'fields' => 'ID', 'number' => 2000]),
        get_users(['meta_key' => 'aerohangar_state_time', 'fields' => 'ID', 'number' => 2000])
    ));
    $out = [];
    foreach ($ids as $id) {
        $u = get_userdata((int)$id);
        if (!$u) continue;
        $out[] = ['id' => $u->ID, 'login' => $u->user_login, 'name' => $u->display_name, 'email' => $u->user_email,
            'key' => (bool)get_user_meta($u->ID, 'cannonade_key_hash', true), 'keyHint' => (string)get_user_meta($u->ID, 'cannonade_key_hint', true),
            'lastSeen' => (int)get_user_meta($u->ID, 'cannonade_last_seen', true), 'lastSync' => (int)get_user_meta($u->ID, 'aerohangar_state_time', true),
            'aircraft' => (int)get_user_meta($u->ID, 'aerohangar_aircraft', true), 'pro' => ah_account_pro($u->ID)];
    }
    usort($out, fn($a, $b) => max($b['lastSeen'], $b['lastSync']) <=> max($a['lastSeen'], $a['lastSync']));
    return $out;
}
