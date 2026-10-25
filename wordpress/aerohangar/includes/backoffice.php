<?php
/**
 * Back office ("zaplecze") for clients: pilots, mechanics, schools, dealers and services log in to the same panel
 * as the owner and see only the sections the administrator allows them (view or edit), plus "Moje samoloty":
 * their own aircraft from the app with their own bulletins and bulletin statuses, editable in the browser and synced
 * to the app on the phone (the phone pulls changes made on the web; changes made on the web while the phone had
 * unsent changes are merged into the phone's next upload).
 */
defined('ABSPATH') || exit;

/** Panel sections a client account can be given (key => label). */
function ah_panel_sections(): array
{
    return [
        'bulletins' => 'Biuletyny – wspólna baza',
        'types' => 'Typy samolotów',
        'news' => 'Komunikaty',
        'contacts' => 'Kontakty (serwisy, producenci, dealerzy, mechanicy, szkoły, centra)',
        'examiners' => 'Egzaminatorzy i lekarze orzecznicy',
        'partners' => 'Partnerzy',
        'requests' => 'Zapytania serwisowe (wszystkie)',
        'sponsor' => 'Sponsor / baner',
        'subs' => 'Zgłoszenia od użytkowników',
        'stats' => 'Statystyki',
        'installs' => 'Instalacje i Pro',
        'history' => 'Historia i kopie',
    ];
}

/** Client's panel sections: [section => 'view'|'edit']. */
function ah_user_sections(int $uid): array
{
    $m = get_user_meta($uid, 'aerohangar_panel', true);
    if (!is_array($m)) return [];
    $out = [];
    foreach (ah_panel_sections() as $k => $_) if (in_array($m[$k] ?? '', ['view', 'edit'], true)) $out[$k] = $m[$k];
    return $out;
}

function ah_is_staff(): bool
{
    return current_user_can('aerohangar_view') || current_user_can('manage_options');
}

/** May this account open the panel as a client (has access and at least one section or its fleet)? */
function ah_client_panel(int $uid): bool
{
    return $uid && ah_has_access($uid) && (ah_user_sections($uid) || ah_can($uid, 'fleet'));
}

/**
 * Login check for a panel action. $section: null = staff only; string/array = also clients with that section
 * ('view' for reading, 'edit' for POST); 'public' = any client (data that is public anyway); 'fleet' = own fleet.
 */
function ah_panel_user($section = null): array
{
    $wu = wp_get_current_user();
    if (!$wu || !$wu->ID) ah_out(401, ['error' => 'login required']);
    if (ah_is_staff()) {
        $role = (current_user_can('manage_options') || current_user_can('aerohangar_admin')) ? 'admin' : (current_user_can('aerohangar_edit') ? 'editor' : 'viewer');
        return ['login' => $wu->user_login, 'role' => $role, 'id' => $wu->ID];
    }
    if (!ah_client_panel($wu->ID) || $section === null) ah_out(401, ['error' => 'login required']);
    $need = $_SERVER['REQUEST_METHOD'] === 'POST' ? 'edit' : 'view';
    $sec = ah_user_sections($wu->ID);
    $ok = false;
    foreach ((array)$section as $s) {
        if ($s === 'public' && $need === 'view') $ok = true;
        elseif ($s === 'fleet') $ok = ah_can($wu->ID, 'fleet') && ($need === 'view' || ah_can($wu->ID, 'fleet_edit'));
        elseif (isset($sec[$s]) && ($need === 'view' || $sec[$s] === 'edit')) $ok = true;
        if ($ok) break;
    }
    if (!$ok) ah_out(403, ['error' => 'no permission for this section']);
    return ['login' => $wu->user_login, 'role' => 'client', 'id' => $wu->ID];
}

/** Section that guards a data file (reading public files is allowed to every client). */
function ah_file_section(string $f, bool $write)
{
    if (!$write && in_array($f, ['bulletins', 'types', 'news', 'sponsor'], true)) return ['public', $f];
    return match ($f) { 'contacts' => ['contacts', 'partners'], default => $f };
}

// ---------------------------------------------------------------- the panel at a front-end address

/** Outputs the panel page (same app as wp-admin → AeroHangar → Otwórz panel). */
function ah_output_panel(): void
{
    $html = (string)file_get_contents(AEROHANGAR_DIR . '/assets/panel.html');
    $cfg = ['api' => rest_url('aerohangar/v1/api'), 'nonce' => wp_create_nonce('wp_rest'), 'assets' => AEROHANGAR_URL . 'assets/',
        'wpAdmin' => ah_is_staff() ? admin_url('admin.php?page=aerohangar') : ah_login_page_url(),
        'login' => wp_login_url(home_url('/index.php/aerohangar-panel')), 'client' => !ah_is_staff()];
    $html = str_replace('/*AH_WP_CONFIG*/', 'window.AH_WP = ' . wp_json_encode($cfg) . ';', $html);
    $html = str_replace('href="favicon.ico"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/favicon.ico') . '"', $html);
    $html = str_replace(['href="icon-192.png"', 'href="apple-touch-icon.png"'], ['href="' . esc_url(AEROHANGAR_URL . 'assets/icon-192.png') . '"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/apple-touch-icon.png') . '"'], $html);
    nocache_headers();
    header('Content-Type: text/html; charset=utf-8');
    echo $html;
    exit;
}

// /aerohangar-panel: clients get the panel right here (no wp-admin), staff go to the wp-admin page.
add_action('init', function () {
    $path = trim((string)parse_url($_SERVER['REQUEST_URI'] ?? '', PHP_URL_PATH), '/');
    $home = trim((string)parse_url(home_url(), PHP_URL_PATH), '/');
    if ($home !== '' && str_starts_with($path, $home)) $path = trim(substr($path, strlen($home)), '/');
    if (!preg_match('~^(index\.php/)?(aerohangar-panel|panel-aerohangar|zaplecze)$~', $path)) return;
    if (!is_user_logged_in()) { wp_safe_redirect(wp_login_url(home_url('/index.php/aerohangar-panel'))); exit; }
    if (ah_is_staff()) { wp_safe_redirect(admin_url('admin.php?page=aerohangar-panel')); exit; }
    if (ah_client_panel(get_current_user_id())) ah_output_panel();
    wp_safe_redirect(ah_login_page_url());
    exit;
}, 0);

// Logging in from the panel link: clients land back in the panel, not in wp-admin.
add_filter('login_redirect', function ($to, $requested, $user) {
    if ($user instanceof WP_User && str_contains((string)$requested, 'aerohangar-panel') && !user_can($user, 'aerohangar_view') && !user_can($user, 'manage_options')) return $requested;
    return $to;
}, 20, 3);

// ---------------------------------------------------------------- own fleet: aircraft, own bulletins, statuses

function ah_state_get(int $uid): ?array
{
    $s = json_decode((string)get_user_meta($uid, 'aerohangar_state', true), true);
    return is_array($s) ? $s : null;
}

/** Applies one web change to a state array. */
function ah_state_apply(array &$s, array $op): void
{
    $aid = (string)($op['aircraftId'] ?? '');
    switch ($op['op'] ?? '') {
        case 'own_save':
            $b = $op['bulletin'];
            $s['ownBulletins'] = array_values(array_filter((array)($s['ownBulletins'] ?? []), fn($o) => !(($o['aircraftId'] ?? '') === $aid && ($o['bulletin']['id'] ?? '') === $b['id'])));
            $s['ownBulletins'][] = ['aircraftId' => $aid, 'bulletin' => $b];
            break;
        case 'own_delete':
            $s['ownBulletins'] = array_values(array_filter((array)($s['ownBulletins'] ?? []), fn($o) => !(($o['aircraftId'] ?? '') === $aid && ($o['bulletin']['id'] ?? '') === $op['id'])));
            $s['bulletinStatus'] = array_values(array_filter((array)($s['bulletinStatus'] ?? []), fn($x) => !(($x['aircraftId'] ?? '') === $aid && ($x['bulletinId'] ?? '') === $op['id'])));
            break;
        case 'status':
            $s['bulletinStatus'] = array_values(array_filter((array)($s['bulletinStatus'] ?? []), fn($x) => !(($x['aircraftId'] ?? '') === $aid && ($x['bulletinId'] ?? '') === $op['bulletinId'])));
            if (!empty($op['status'])) {
                $row = ['aircraftId' => $aid, 'bulletinId' => $op['bulletinId'], 'status' => $op['status'], 'epochDay' => (int)$op['epochDay'], 'note' => (string)($op['note'] ?? '')];
                if (isset($op['hours']) && $op['hours'] !== null) $row['hours'] = (float)$op['hours'];
                $s['bulletinStatus'][] = $row;
            }
            break;
    }
}

/** Saves a web change on the account and remembers it until the phone has it. */
function ah_state_web_change(int $uid, array $op): void
{
    $s = ah_state_get($uid);
    if ($s === null) ah_out(404, ['error' => 'no app data on this account yet']);
    $ids = array_column((array)($s['aircraft'] ?? []), 'id');
    if (!in_array($op['aircraftId'] ?? '', $ids, true)) ah_out(404, ['error' => 'no such aircraft']);
    ah_state_apply($s, $op);
    update_user_meta($uid, 'aerohangar_state', wp_slash(wp_json_encode($s, JSON_UNESCAPED_UNICODE)));
    update_user_meta($uid, 'aerohangar_state_time', time());
    update_user_meta($uid, 'aerohangar_state_device', 'web');
    $p = get_user_meta($uid, 'aerohangar_web_pending', true);
    $p = is_array($p) ? $p : [];
    $p[] = $op;
    update_user_meta($uid, 'aerohangar_web_pending', array_slice($p, -500));
}

/** Phone upload while web changes were waiting: they are applied on top; the phone then pulls the result. */
add_filter('aerohangar_incoming_state', function (array $d, int $uid) {
    $p = get_user_meta($uid, 'aerohangar_web_pending', true);
    if (!is_array($p) || !$p) return $d;
    foreach ($p as $op) ah_state_apply($d, $op);
    delete_user_meta($uid, 'aerohangar_web_pending');
    $d['__merged'] = true;
    return $d;
}, 10, 2);
add_action('aerohangar_state_downloaded', fn($uid) => delete_user_meta($uid, 'aerohangar_web_pending'));

function ah_engines(): array
{
    static $e = null;
    if ($e !== null) return $e;
    foreach ([ah_data_path('engines'), AEROHANGAR_DIR . '/initial-data/engines.json'] as $f) {
        $d = is_file($f) ? json_decode((string)file_get_contents($f), true) : null;
        if (is_array($d)) { $e = []; foreach ((isset($d['engines']) ? $d['engines'] : $d) as $x) if (isset($x['id'])) $e[$x['id']] = $x; return $e; }
    }
    return $e = [];
}

/** Bulletin groups of an aircraft (airframe groups of its type + its engine's groups). */
function ah_aircraft_groups(array $a, array $types, array $custom): array
{
    $t = $types[$a['typeId'] ?? ''] ?? $custom[$a['typeId'] ?? ''] ?? [];
    $g = (array)($t['bulletinGroups'] ?? []);
    $eng = ah_engines()[$a['engineId'] ?? ($t['engine'] ?? '')] ?? null;
    if ($eng) $g = array_merge(array_filter($g, fn($x) => !str_starts_with($x, 'ROTAX_')), (array)($eng['bulletinGroups'] ?? []));
    if (in_array('EVEKTOR', $g, true)) $g = array_merge($g, ['EVEKTOR_EV97', 'EVEKTOR_SPORTSTAR', 'EVEKTOR_RTC', 'EVEKTOR_HARMONY']);
    return array_values(array_unique($g));
}

function ah_myfleet_api(?array $body): void
{
    $u = ah_panel_user('fleet');
    $uid = (int)$u['id'];
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $act = (string)($body['action'] ?? '');
        $aid = (string)($body['aircraftId'] ?? '');
        $date = (string)($body['date'] ?? '');
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) $date = gmdate('Y-m-d');
        if ($act === 'own_save') {
            $b = (array)($body['bulletin'] ?? []);
            $id = substr(preg_replace('/[^\w.\-\/ ]/u', '', (string)($b['id'] ?? '')), 0, 60);
            $title = ah_clean_str($b['title'] ?? '', 300);
            if ($id === '' || $title === '') ah_out(400, ['error' => 'number and title required']);
            $bd = (string)($b['date'] ?? '');
            $nb = ['id' => $id, 'date' => preg_match('/^\d{4}-\d{2}-\d{2}$/', $bd) ? $bd : gmdate('Y-m-d'), 'title' => $title, 'groups' => ['OWN'],
                'kind' => in_array($b['kind'] ?? '', ['AD', 'ASB', 'SB', 'SIB', 'DOC'], true) ? $b['kind'] : 'SB'];
            if (isset($b['mandatory']) && $b['mandatory'] !== null && $b['mandatory'] !== '') $nb['mandatory'] = (bool)$b['mandatory'];
            $url = esc_url_raw((string)($b['url'] ?? ''));
            if (str_starts_with($url, 'http')) $nb['url'] = $url;
            ah_state_web_change($uid, ['op' => 'own_save', 'aircraftId' => $aid, 'bulletin' => $nb]);
        } elseif ($act === 'own_delete') {
            ah_state_web_change($uid, ['op' => 'own_delete', 'aircraftId' => $aid, 'id' => (string)($body['id'] ?? '')]);
        } elseif ($act === 'status') {
            $st = in_array($body['status'] ?? '', ['DONE', 'NA'], true) ? $body['status'] : null;
            $h = $body['hours'] ?? null;
            ah_state_web_change($uid, ['op' => 'status', 'aircraftId' => $aid, 'bulletinId' => (string)($body['bulletinId'] ?? ''), 'status' => $st,
                'epochDay' => intdiv(strtotime($date . ' 12:00 UTC'), 86400), 'hours' => is_numeric($h) ? (float)$h : null, 'note' => ah_clean_str($body['note'] ?? '', 300)]);
        } else ah_out(400, ['error' => 'unknown action']);
        ah_log($u['login'], "myfleet $act $aid");
    }
    $s = ah_state_get($uid);
    $types = [];
    $td = json_decode((string)@file_get_contents(ah_data_path('types')), true);
    foreach ((array)($td['types'] ?? $td ?? []) as $x) if (is_array($x) && isset($x['id'])) $types[$x['id']] = $x;
    $custom = [];
    foreach ((array)($s['customTypes'] ?? []) as $x) if (isset($x['id'])) $custom[$x['id']] = $x;
    $out = [];
    foreach ((array)($s['aircraft'] ?? []) as $a) {
        $t = $types[$a['typeId'] ?? ''] ?? $custom[$a['typeId'] ?? ''] ?? null;
        $out[] = ['id' => $a['id'], 'registration' => $a['registration'] ?? '', 'type' => $t['name'] ?? ($a['typeId'] ?? ''), 'hours' => $a['hours'] ?? null,
            'serial' => $a['serial'] ?? '', 'engineSerial' => $a['engineSerial'] ?? '', 'groups' => ah_aircraft_groups($a, $types, $custom)];
    }
    ah_out(200, ['aircraft' => $out, 'own' => array_values((array)($s['ownBulletins'] ?? [])), 'status' => array_values((array)($s['bulletinStatus'] ?? [])),
        'canEdit' => ah_is_staff() || ah_can($uid, 'fleet_edit'), 'lastSync' => (int)get_user_meta($uid, 'aerohangar_state_time', true),
        'pending' => count((array)(get_user_meta($uid, 'aerohangar_web_pending', true) ?: []))]);
}
