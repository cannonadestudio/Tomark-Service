<?php
/**
 * wp-admin → AeroHangar → "Klucze aplikacji": every account's key in one table, always visible in full.
 * One key per account works in both apps (AeroHangar and Pilot Logbook). The administrator can create, replace,
 * revoke and copy keys, search accounts and create the missing keys for all accounts with access in one click.
 */
defined('ABSPATH') || exit;

add_action('admin_menu', function () {
    add_submenu_page('aerohangar', 'Klucze aplikacji', 'Klucze aplikacji', 'manage_options', 'aerohangar-keys', 'aerohangar_keys_page');
    // also under Users, where WordPress accounts are managed
    add_users_page('Klucze aplikacji', 'Klucze aplikacji (AeroHangar)', 'manage_options', 'aerohangar-keys-users', 'aerohangar_keys_page');
}, 20);

/** Key state of one account for the table. */
function ah_key_row(WP_User $u): array
{
    $hash = (string)get_user_meta($u->ID, 'cannonade_key_hash', true);
    $plain = $hash ? cannonade_key_plain($u->ID) : null;
    return [
        'key' => $plain,
        'legacy' => $hash && !$plain,                       // made by an older version: works, but cannot be shown
        'hint' => (string)get_user_meta($u->ID, 'cannonade_key_hint', true),
        'created' => (int)get_user_meta($u->ID, 'cannonade_key_created', true),
        'seen' => (int)get_user_meta($u->ID, 'cannonade_last_seen', true),
        'works' => $hash && cannonade_can_have_key($u->ID),
        'access' => function_exists('ah_has_access') ? ah_has_access($u->ID) : true,
    ];
}

function aerohangar_keys_page(): void
{
    if (!current_user_can('manage_options')) return;
    $msg = [];
    $me = wp_get_current_user()->user_login;

    if (!empty($_POST['ahk']) && check_admin_referer('ahk')) {
        $act = (string)$_POST['ahk'];
        $uid = (int)($_POST['uid'] ?? 0);
        $u = $uid ? get_userdata($uid) : null;
        if ($act === 'new' && $u) {
            cannonade_new_key($uid);
            $msg[] = 'Nowy klucz dla ' . $u->display_name . ' – poprzedni przestał działać.';
            ah_log($me, "key: new for #$uid");
        } elseif ($act === 'revoke' && $u) {
            cannonade_revoke_key($uid);
            $msg[] = 'Klucz konta ' . $u->display_name . ' unieważniony – aplikacje straciły połączenie.';
            ah_log($me, "key: revoked for #$uid");
        } elseif ($act === 'missing') {
            $n = 0;
            foreach (get_users(['number' => 2000, 'fields' => 'all']) as $x) {
                if (get_user_meta($x->ID, 'cannonade_key_hash', true)) continue;
                if (!cannonade_can_have_key($x->ID)) continue;
                cannonade_new_key($x->ID);
                $n++;
            }
            $msg[] = "Utworzono $n kluczy dla kont z dostępem, które nie miały klucza.";
            ah_log($me, "key: created $n missing keys");
        } elseif ($act === 'legacy') {
            $n = 0;
            foreach (get_users(['number' => 2000, 'meta_key' => 'cannonade_key_hash', 'meta_compare' => 'EXISTS']) as $x) {
                if (cannonade_key_plain($x->ID)) continue;
                cannonade_new_key($x->ID);
                $n++;
            }
            $msg[] = "Wymieniono $n starszych (niewidocznych) kluczy na nowe – trzeba je wkleić ponownie w aplikacjach.";
            ah_log($me, "key: replaced $n legacy keys");
        }
    }

    $q = sanitize_text_field(wp_unslash((string)($_GET['s'] ?? '')));
    $show = in_array($_GET['show'] ?? '', ['all', 'keys', 'nokey', 'access'], true) ? $_GET['show'] : 'all';
    $args = ['number' => 1000, 'orderby' => 'display_name'];
    if ($q !== '') { $args['search'] = '*' . $q . '*'; $args['search_columns'] = ['user_login', 'user_email', 'display_name', 'user_nicename']; }
    $rows = [];
    foreach (get_users($args) as $u) {
        $r = ah_key_row($u);
        if ($show === 'keys' && !$r['key'] && !$r['legacy']) continue;
        if ($show === 'nokey' && ($r['key'] || $r['legacy'])) continue;
        if ($show === 'access' && !$r['access']) continue;
        $rows[] = [$u, $r];
    }
    $base = admin_url('admin.php?page=' . sanitize_key($_GET['page'] ?? 'aerohangar-keys'));
    $nonce = wp_nonce_field('ahk', '_wpnonce', true, false);
    $btn = function (string $act, int $uid, string $label, string $confirm = '', string $cls = 'button') use ($nonce) {
        return '<form method="post" style="display:inline">' . $nonce . '<input type="hidden" name="ahk" value="' . esc_attr($act) . '"><input type="hidden" name="uid" value="' . $uid . '">'
            . '<button class="' . esc_attr($cls) . '"' . ($confirm ? ' onclick="return confirm(' . esc_attr(wp_json_encode($confirm)) . ')"' : '') . '>' . esc_html($label) . '</button></form>';
    };
    $legacyCount = count(array_filter($rows, fn($x) => $x[1]['legacy']));
    ?>
    <div class="wrap">
        <h1>Klucze aplikacji <span style="font-size:14px;font-weight:400;color:#55606f">– jeden klucz konta działa w AeroHangar i w Pilot Logbook</span></h1>
        <?php foreach ($msg as $m): ?><div class="notice notice-success is-dismissible"><p><?php echo esc_html($m); ?></p></div><?php endforeach; ?>
        <p>Pilot (albo mechanik, szkoła…) wkleja klucz w <b>AeroHangar</b>: ikona konta u góry → Konto Cannonade → Połącz, oraz w <b>Pilot Logbook</b> – oba telefony i obie aplikacje widzą wtedy to samo konto. Klucz działa tylko na koncie z dostępem (zakup w sklepie albo dostęp przyznany ręcznie w panelu → Konta pilotów).</p>
        <form method="get" style="margin:12px 0;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
            <input type="hidden" name="page" value="<?php echo esc_attr(sanitize_key($_GET['page'] ?? 'aerohangar-keys')); ?>">
            <input type="search" name="s" value="<?php echo esc_attr($q); ?>" placeholder="Szukaj: imię, login, e-mail" class="regular-text">
            <select name="show">
                <?php foreach (['all' => 'Wszystkie konta', 'keys' => 'Z kluczem', 'nokey' => 'Bez klucza', 'access' => 'Z dostępem'] as $k => $v): ?>
                    <option value="<?php echo esc_attr($k); ?>" <?php selected($show, $k); ?>><?php echo esc_html($v); ?></option>
                <?php endforeach; ?>
            </select>
            <button class="button">Pokaż</button>
            <span style="flex:1"></span>
            <?php echo $btn('missing', 0, 'Utwórz brakujące klucze (konta z dostępem)', 'Utworzyć klucze dla wszystkich kont z dostępem, które jeszcze nie mają klucza?', 'button button-primary'); ?>
            <?php if ($legacyCount) echo $btn('legacy', 0, "Wymień starsze klucze ($legacyCount)", 'Starsze klucze nie dają się wyświetlić. Wymienić je na nowe? Właściciele będą musieli wkleić nowe klucze w aplikacjach.'); ?>
        </form>
        <table class="widefat striped" style="max-width:1300px">
            <thead><tr><th>Konto</th><th style="width:330px">Klucz</th><th>Dostęp</th><th>Utworzony / użyty</th><th>Akcje</th></tr></thead>
            <tbody>
            <?php foreach ($rows as [$u, $r]): ?>
                <tr>
                    <td><b><?php echo esc_html($u->display_name); ?></b><br><span style="color:#55606f"><?php echo esc_html($u->user_login . ' · ' . $u->user_email); ?></span></td>
                    <td>
                        <?php if ($r['key']): ?>
                            <code style="font-size:13px;padding:4px 8px;background:#E6EEF8;color:#0B2A5B;border-radius:6px;user-select:all"><?php echo esc_html($r['key']); ?></code>
                            <button type="button" class="button button-small" onclick="navigator.clipboard&&navigator.clipboard.writeText(<?php echo esc_attr(wp_json_encode($r['key'])); ?>);this.textContent='✓ skopiowano'">Kopiuj</button>
                        <?php elseif ($r['legacy']): ?>
                            <span style="color:#8a6100">starszy klucz …<?php echo esc_html($r['hint']); ?> – działa, ale nie da się go pokazać</span>
                        <?php else: ?>
                            <span style="color:#55606f">brak klucza</span>
                        <?php endif; ?>
                    </td>
                    <td>
                        <?php if (get_user_meta($u->ID, 'aerohangar_blocked', true)): ?><span style="color:#C62828">zablokowane</span>
                        <?php elseif ($r['access']): ?><span style="color:#1B5E20">✓ dostęp</span><?php $pl = (string)get_user_meta($u->ID, 'aerohangar_plan', true); if ($pl) echo ' · ' . esc_html(ah_plans()[$pl] ?? $pl); ?>
                        <?php else: ?><span style="color:#C62828">brak dostępu</span><?php if ($r['key'] || $r['legacy']) echo '<br><small>klucz nie działa, dopóki konto nie dostanie dostępu</small>'; ?>
                        <?php endif; ?>
                    </td>
                    <td><small><?php echo $r['created'] ? esc_html(wp_date('Y-m-d', $r['created'])) : '—'; ?><br><?php echo $r['seen'] ? 'użyty ' . esc_html(wp_date('Y-m-d H:i', $r['seen'])) : 'jeszcze nieużyty'; ?></small></td>
                    <td style="white-space:nowrap">
                        <?php if ($r['key'] || $r['legacy']): ?>
                            <?php echo $btn('new', $u->ID, 'Nowy klucz', 'Wygenerować nowy klucz? Stary przestanie działać w obu aplikacjach.'); ?>
                            <?php echo $btn('revoke', $u->ID, 'Unieważnij', 'Unieważnić klucz? Aplikacje stracą połączenie z kontem.', 'button button-link-delete'); ?>
                        <?php else: ?>
                            <?php echo $btn('new', $u->ID, 'Generuj klucz', '', 'button button-primary'); ?>
                        <?php endif; ?>
                        <a class="button-link" style="margin-left:6px" href="<?php echo esc_url(admin_url('admin.php?page=aerohangar-panel#accounts')); ?>">uprawnienia</a>
                    </td>
                </tr>
            <?php endforeach; ?>
            <?php if (!$rows): ?><tr><td colspan="5">Brak kont.</td></tr><?php endif; ?>
            </tbody>
        </table>
        <p style="color:#55606f">Klucze są zapisane w bazie zaszyfrowane (szyfrowanie solą WordPressa) – widzi je tylko administrator i właściciel konta (Moje konto, menu użytkownika).</p>
    </div>
    <?php
}

// "Klucz" column and quick link in wp-admin → Users
add_filter('manage_users_columns', fn($c) => $c + ['cannonade_key' => 'Klucz aplikacji']);
add_filter('manage_users_custom_column', function ($v, $col, $uid) {
    if ($col !== 'cannonade_key') return $v;
    $k = cannonade_key_plain((int)$uid);
    if ($k) return '<code style="font-size:11px">' . esc_html($k) . '</code>';
    return get_user_meta($uid, 'cannonade_key_hash', true) ? 'starszy …' . esc_html((string)get_user_meta($uid, 'cannonade_key_hint', true)) : '<a href="' . esc_url(admin_url('admin.php?page=aerohangar-keys&s=' . rawurlencode(get_userdata($uid)->user_login))) . '">generuj</a>';
}, 10, 3);
