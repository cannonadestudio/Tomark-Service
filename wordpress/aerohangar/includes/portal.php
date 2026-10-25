<?php
/**
 * Client login to AeroHangar on the website – pilots, mechanics, flight schools, dealers, services.
 *
 * Page "AeroHangar – logowanie" (shortcode [aerohangar_login], created automatically): login button, the plans
 * on offer, and after logging in the client's AeroHangar: status and limits, the app key, the fleet from the app's
 * backup (+ CSV export), and – for businesses linked to their catalogue entry – service requests from app users,
 * their own listing in the app's catalogue and its statistics.
 *
 * What each account may use is set by the administrator (panel → Konta pilotów → Edytuj → "Możliwości"); until
 * then the plan's defaults apply. Without access (purchase or manual grant) nothing but the offer is shown.
 */
defined('ABSPATH') || exit;

/** Everything an account can be allowed in AeroHangar. */
function ah_caps_all(): array
{
    return [
        'app' => 'Aplikacja AeroHangar (klucz, kopia danych, kilka telefonów)',
        'fleet' => 'Flota w przeglądarce (samoloty, naloty, ostatnie przeglądy)',
        'export' => 'Eksport floty do pliku CSV',
        'requests' => 'Zapytania serwisowe od użytkowników aplikacji',
        'listing' => 'Edycja własnej wizytówki w katalogu aplikacji',
        'stats' => 'Statystyki wizytówki (wyświetlenia, telefony, kliknięcia)',
    ];
}

/** Defaults per plan (used until the administrator sets the account's own list). */
function ah_plan_caps(string $plan): array
{
    $business = ['app', 'fleet', 'export', 'requests', 'listing', 'stats'];
    return match ($plan) {
        'mechanic', 'school', 'dealer', 'service', 'club' => $business,
        default => ['app', 'fleet', 'export'],
    };
}

/** Explicit list set by the administrator, or null = plan defaults. */
function ah_caps_custom(int $uid): ?array
{
    $c = get_user_meta($uid, 'aerohangar_caps', true);
    return is_array($c) ? array_values(array_intersect(array_keys(ah_caps_all()), $c)) : null;
}

/** What the account may use right now (empty without access). Administrators and panel users: everything. */
function ah_caps(int $uid): array
{
    if (!ah_has_access($uid)) return [];
    if (user_can($uid, 'manage_options') || user_can($uid, 'aerohangar_view')) return array_keys(ah_caps_all());
    $custom = ah_caps_custom($uid);
    if ($custom !== null) return $custom;
    $acc = ah_access_active($uid) ? ah_access($uid) : null;
    $plan = (string)(get_user_meta($uid, 'aerohangar_plan', true) ?: ($acc['plan'] ?? 'pilot'));
    return ah_plan_caps($plan);
}

function ah_can(int $uid, string $cap): bool
{
    return in_array($cap, ah_caps($uid), true);
}

// The app key needs the "app" capability as well.
add_filter('cannonade_can_have_key', fn($ok, $uid) => $ok && ah_can((int)$uid, 'app'), 20, 2);

/** Catalogue entry (contacts) linked to a business account. */
function ah_user_contact_id(int $uid): string
{
    return (string)get_user_meta($uid, 'aerohangar_contact_id', true);
}

/** Contacts for the panel's "linked catalogue entry" field. */
function ah_contacts_brief(): array
{
    $d = json_decode(ah_read_editable('contacts'), true);
    $out = [];
    foreach (($d['contacts'] ?? []) as $c) {
        if (in_array($c['kind'] ?? '', ['ame', 'aemc'], true)) continue;
        $out[] = ['id' => $c['id'], 'name' => $c['name'] ?? $c['id'], 'city' => $c['city'] ?? '', 'kind' => $c['kind'] ?? ''];
    }
    usort($out, fn($a, $b) => strcasecmp($a['name'], $b['name']));
    return $out;
}

// ---------------------------------------------------------------- login page

function ah_login_page_url(): string
{
    $id = (int)get_option('aerohangar_login_page');
    return $id && get_post_status($id) === 'publish' ? (string)get_permalink($id) : home_url('/');
}

/** Creates the page once (administrator in wp-admin); the owner may rename or move it freely. */
add_action('admin_init', function () {
    if (!current_user_can('manage_options')) return;
    $id = (int)get_option('aerohangar_login_page');
    if ($id && get_post($id) && get_post_status($id) !== 'trash') return;
    $id = wp_insert_post(['post_type' => 'page', 'post_status' => 'publish', 'post_title' => 'AeroHangar – logowanie',
        'post_name' => 'aerohangar-logowanie', 'post_content' => '<!-- wp:shortcode -->[aerohangar_login]<!-- /wp:shortcode -->', 'comment_status' => 'closed']);
    if ($id && !is_wp_error($id)) update_option('aerohangar_login_page', (int)$id, false);
});

// CSV export (before any output)
add_action('template_redirect', function () {
    if (empty($_GET['ah_export']) || !is_user_logged_in()) return;
    $uid = get_current_user_id();
    if (!wp_verify_nonce((string)($_GET['_wpnonce'] ?? ''), 'ah_export_' . $uid) || !ah_can($uid, 'export')) wp_die('Brak dostępu.');
    $rows = ah_fleet($uid);
    nocache_headers();
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="aerohangar-flota-' . gmdate('Y-m-d') . '.csv"');
    $f = fopen('php://output', 'w');
    fwrite($f, "\xEF\xBB\xBF");
    fputcsv($f, ['Znaki', 'Typ', 'Nr seryjny', 'Nr silnika', 'Rok', 'Nalot (h)', 'Nalot z dnia', 'Ostatni wpis serwisowy', 'Biuletyny wykonane', 'Notatki'], ';');
    foreach ($rows as $r) fputcsv($f, [$r['reg'], $r['type'], $r['serial'], $r['engineSerial'], $r['year'], $r['hours'], $r['hoursDate'], $r['lastService'], $r['bulletinsDone'], $r['notes']], ';');
    fclose($f);
    exit;
});

/** The client's aircraft from the app's backup on the account. */
function ah_fleet(int $uid): array
{
    $s = json_decode((string)get_user_meta($uid, 'aerohangar_state', true), true);
    if (!is_array($s)) return [];
    $types = [];
    $t = json_decode((string)@file_get_contents(ah_data_path('types')), true);
    foreach ((array)($t['types'] ?? $t ?? []) as $x) if (is_array($x) && isset($x['id'])) $types[$x['id']] = $x['name'] ?? $x['id'];
    foreach ((array)($s['customTypes'] ?? []) as $x) if (isset($x['id'])) $types[$x['id']] = ($x['name'] ?? $x['id']) . ' (własny)';
    $day = fn($d) => $d === null || $d === '' ? '' : gmdate('Y-m-d', (int)$d * 86400);
    $out = [];
    foreach ((array)($s['aircraft'] ?? []) as $a) {
        $id = $a['id'] ?? '';
        $last = null; $lastH = null;
        foreach (array_merge((array)($s['completions'] ?? []), (array)($s['logEntries'] ?? [])) as $c) {
            if (($c['aircraftId'] ?? '') !== $id) continue;
            if ($last === null || (int)$c['epochDay'] > $last) { $last = (int)$c['epochDay']; $lastH = $c['hours'] ?? null; }
        }
        $done = count(array_filter((array)($s['bulletinStatus'] ?? []), fn($b) => ($b['aircraftId'] ?? '') === $id && ($b['status'] ?? '') === 'DONE'));
        $out[] = ['reg' => (string)($a['registration'] ?? ''), 'type' => $types[$a['typeId'] ?? ''] ?? (string)($a['typeId'] ?? ''),
            'serial' => (string)($a['serial'] ?? ''), 'engineSerial' => (string)($a['engineSerial'] ?? ''), 'year' => (string)($a['yearBuilt'] ?? ''),
            'hours' => isset($a['hours']) ? round((float)$a['hours'], 1) : '', 'hoursDate' => $day($a['hoursUpdatedEpochDay'] ?? null),
            'lastService' => $last === null ? '' : $day($last) . ($lastH !== null ? ' (' . round((float)$lastH, 1) . ' h)' : ''),
            'bulletinsDone' => $done, 'notes' => (string)($a['notes'] ?? '')];
    }
    return $out;
}

add_shortcode('aerohangar_login', 'ah_portal_html');

function ah_portal_html(): string
{
    $page = ah_login_page_url();
    $css = '<style>.ahp{max-width:980px}.ahp-tabs{display:flex;flex-wrap:wrap;gap:6px;margin:10px 0 16px}.ahp-tabs a{padding:7px 14px;border-radius:999px;border:1px solid #7A3E9D;color:#7A3E9D;text-decoration:none}.ahp-tabs a.on{background:#7A3E9D;color:#fff}.ahp-card{border:1px solid #e3dcea;border-radius:14px;padding:16px 18px;margin-bottom:14px}.ahp table{width:100%;border-collapse:collapse;font-size:.95em}.ahp td,.ahp th{padding:7px 8px;border-bottom:1px solid #eee;text-align:left;vertical-align:top}.ahp .muted{color:#6b6676;font-size:.9em}.ahp input[type=text],.ahp input[type=email],.ahp input[type=url],.ahp textarea{width:100%;padding:7px 9px;border:1px solid #ccc;border-radius:8px}.ahp label{display:block;margin:8px 0 3px;font-weight:600}.ahp .cna-btn,.ahp button.cna-btn{background:#7A3E9D;color:#fff;border:0;border-radius:999px;padding:8px 16px;cursor:pointer;text-decoration:none;display:inline-block}.ahp .chip{display:inline-block;padding:2px 10px;border-radius:999px;background:#f1ecf6;margin:2px 4px 2px 0;font-size:.9em}.ahp .ok{color:#1b7a3d}.ahp .bad{color:#b0003a}</style>';

    if (!is_user_logged_in()) {
        $h = '<div class="ahp"><div class="ahp-card"><h3>AeroHangar – logowanie</h3>'
            . '<p>Konto dla pilotów i właścicieli, mechaników, szkół lotniczych, dealerów i serwisów. Zaloguj się, żeby zobaczyć swoją flotę, klucz do aplikacji i zapytania od klientów.</p>'
            . '<p><a class="cna-btn" href="' . esc_url(wp_login_url($page)) . '">' . esc_html(cannonade_t('login')) . '</a> &nbsp;<a href="' . esc_url(wp_lostpassword_url($page)) . '">Nie pamiętasz hasła?</a></p></div>';
        $offer = apply_filters('cannonade_offer_html', '', 0);
        if ($offer) $h .= '<div class="ahp-card"><h3>Nie masz konta?</h3><p>Wybierz abonament – konto założysz przy zamówieniu, a dostęp włączy się po opłaceniu.</p>' . $offer . '</div>';
        return $css . $h . '</div>';
    }

    $uid = get_current_user_id();
    $u = wp_get_current_user();
    $perm = ah_permissions($uid);
    $caps = ah_caps($uid);
    $cid = ah_user_contact_id($uid);
    $msg = '';

    // actions
    if (!empty($_POST['ahp']) && check_admin_referer('ahp_' . $uid)) {
        $act = (string)$_POST['ahp'];
        if ($act === 'req' && in_array('requests', $caps, true) && $cid) {
            $rid = sanitize_key((string)($_POST['rid'] ?? ''));
            $st = ($_POST['st'] ?? '') === 'done' ? 'done' : 'new';
            ah_store_update('requests', function (&$d) use ($rid, $st, $cid) {
                foreach ($d as $i => $x) if ($x['id'] === $rid && ($x['contactId'] ?? '') === $cid) { $d[$i]['status'] = $st; return; }
            });
            $msg = 'Zapisano.';
        }
        if ($act === 'listing' && in_array('listing', $caps, true) && $cid) {
            $raw = ah_read_editable('contacts');
            $d = json_decode($raw, true);
            $changed = false;
            foreach ($d['contacts'] ?? [] as $i => $c) {
                if (($c['id'] ?? '') !== $cid) continue;
                $fields = ['phone' => 40, 'email' => 120, 'web' => 200, 'address' => 200, 'city' => 80, 'note' => 600, 'promo' => 300];
                foreach ($fields as $k => $max) {
                    $v = trim(sanitize_textarea_field(wp_unslash((string)($_POST[$k] ?? ''))));
                    $v = mb_substr($v, 0, $max);
                    if ($k === 'email' && $v !== '' && !is_email($v)) continue;
                    if ($k === 'web' && $v !== '') $v = esc_url_raw($v);
                    if ($v === '') unset($d['contacts'][$i][$k]); else $d['contacts'][$i][$k] = $v;
                }
                $d['contacts'][$i]['updatedBy'] = 'owner:' . $u->user_login;
                $changed = true;
            }
            if ($changed) {
                ah_backup('contacts', $raw);
                $d['updated'] = gmdate('Y-m-d');
                ah_write_editable('contacts', $d);
                ah_log($u->user_login, "listing $cid edited by its owner");
                $msg = 'Wizytówka zapisana – aplikacje zobaczą zmiany przy następnym odświeżeniu.';
            }
        }
    }

    $tabs = ['start' => 'Moje AeroHangar'];
    if (in_array('app', $caps, true) || get_user_meta($uid, 'cannonade_key_hint', true)) $tabs['key'] = 'Klucz do aplikacji';
    if (in_array('fleet', $caps, true)) $tabs['fleet'] = 'Flota';
    if ($cid && in_array('requests', $caps, true)) $tabs['requests'] = 'Zapytania';
    if ($cid && in_array('listing', $caps, true)) $tabs['listing'] = 'Wizytówka';
    if ($cid && in_array('stats', $caps, true)) $tabs['stats'] = 'Statystyki';
    $tab = isset($tabs[$_GET['ah'] ?? '']) ? $_GET['ah'] : 'start';
    $link = fn($t) => esc_url(add_query_arg('ah', $t, $page));

    $h = '<div class="ahp"><p>Zalogowano jako <b>' . esc_html($u->display_name) . '</b> · <a href="' . esc_url(wp_logout_url($page)) . '">Wyloguj</a>';
    if (current_user_can('aerohangar_view') || current_user_can('manage_options')) $h .= ' · <a class="cna-btn" href="' . esc_url(admin_url('admin.php?page=aerohangar-panel')) . '">✈ ' . esc_html(cannonade_t('open_panel')) . '</a>';
    $h .= '</p>';
    if ($msg) $h .= '<p class="ok"><b>' . esc_html($msg) . '</b></p>';
    $h .= '<nav class="ahp-tabs">';
    foreach ($tabs as $k => $v) $h .= '<a class="' . ($k === $tab ? 'on' : '') . '" href="' . $link($k) . '">' . esc_html($v) . '</a>';
    $h .= '</nav>';
    $nonce = wp_nonce_field('ahp_' . $uid, '_wpnonce', true, false);

    switch ($tab) {
        case 'start':
            $acc = ah_access_active($uid) ? ah_access($uid) : null;
            $h .= '<div class="ahp-card"><h3>Status</h3>';
            if ($perm['blocked']) $h .= '<p class="bad"><b>Konto zablokowane przez administratora.</b></p>';
            elseif (!ah_has_access($uid)) $h .= '<p class="bad"><b>Brak aktywnego dostępu.</b> ' . esc_html(cannonade_t('paid_only')) . '</p>' . apply_filters('cannonade_offer_html', '', $uid);
            else {
                $h .= '<p class="ok"><b>Dostęp aktywny</b>' . ($acc ? ' – ' . ((int)$acc['until'] === 0 ? 'bez limitu czasu' : 'do ' . esc_html(wp_date('Y-m-d', (int)$acc['until']))) : '') . '</p>';
                $h .= '<table><tr><td>Plan</td><td>' . esc_html(ah_plans()[$perm['plan']] ?? ($perm['plan'] ?: '—')) . '</td></tr>'
                    . '<tr><td>Limit samolotów</td><td>' . esc_html($perm['maxAircraft'] === null ? 'bez limitu' : (string)$perm['maxAircraft']) . '</td></tr>'
                    . '<tr><td>Limit własnych typów</td><td>' . esc_html($perm['maxTypes'] === null ? 'bez limitu' : (string)$perm['maxTypes']) . '</td></tr>'
                    . '<tr><td>Możliwości</td><td>' . implode('', array_map(fn($c) => '<span class="chip">' . esc_html(ah_caps_all()[$c]) . '</span>', $caps)) . '</td></tr>';
                if ($cid) { $c = ah_public_contact($cid); $h .= '<tr><td>Wizytówka w katalogu</td><td>' . esc_html($c['name'] ?? $cid) . '</td></tr>'; }
                $h .= '</table>';
                if (!$cid && array_intersect(['requests', 'listing', 'stats'], $caps)) $h .= '<p class="muted">Firma (serwis, mechanik, szkoła, dealer)? Administrator połączy Twoje konto z wizytówką w katalogu – wtedy pojawią się zakładki Zapytania, Wizytówka i Statystyki.</p>';
            }
            $h .= '</div>';
            if (function_exists('wc_get_account_endpoint_url')) $h .= '<p class="muted"><a href="' . esc_url(wc_get_account_endpoint_url('orders')) . '">Zamówienia i faktury</a> · <a href="' . esc_url(wc_get_account_endpoint_url('edit-account')) . '">Dane konta i hasło</a></p>';
            break;

        case 'key':
            $h .= cannonade_account_html();
            break;

        case 'fleet':
            $rows = ah_fleet($uid);
            $t = (int)get_user_meta($uid, 'aerohangar_state_time', true);
            $h .= '<div class="ahp-card"><h3>Flota</h3><p class="muted">Z kopii danych aplikacji' . ($t ? ' z ' . esc_html(wp_date('Y-m-d H:i', $t)) : '') . '.</p>';
            if (!$rows) $h .= '<p>Brak danych – połącz aplikację AeroHangar kluczem, a samoloty pojawią się tutaj po pierwszej synchronizacji.</p>';
            else {
                $h .= '<table><tr><th>Znaki</th><th>Typ</th><th>Nalot</th><th>Ostatni wpis serwisowy</th><th>Biuletyny</th></tr>';
                foreach ($rows as $r) $h .= '<tr><td><b>' . esc_html($r['reg']) . '</b><div class="muted">' . esc_html(trim(($r['serial'] ? 'S/N ' . $r['serial'] : '') . ($r['year'] ? ' · ' . $r['year'] : ''), ' ·')) . '</div></td><td>' . esc_html($r['type']) . '</td><td>' . esc_html($r['hours'] !== '' ? $r['hours'] . ' h' : '—') . '<div class="muted">' . esc_html($r['hoursDate']) . '</div></td><td>' . esc_html($r['lastService'] ?: '—') . '</td><td>' . (int)$r['bulletinsDone'] . ' wykonanych</td></tr>';
                $h .= '</table>';
                if (in_array('export', $caps, true)) $h .= '<p><a class="cna-btn" href="' . esc_url(wp_nonce_url(add_query_arg('ah_export', 'csv', $page), 'ah_export_' . $uid)) . '">Pobierz CSV</a></p>';
            }
            $h .= '</div>';
            break;

        case 'requests':
            $list = array_reverse(array_values(array_filter(ah_store_read('requests'), fn($r) => ($r['contactId'] ?? '') === $cid)));
            $h .= '<div class="ahp-card"><h3>Zapytania o przegląd</h3><p class="muted">Wysłane z aplikacji przez właścicieli samolotów (za ich zgodą). Każde przychodzi też e-mailem.</p>';
            if (!$list) $h .= '<p>Na razie brak zapytań.</p>';
            foreach ($list as $r) {
                $ac = $r['aircraft'] ?? [];
                $h .= '<div class="ahp-card"><b>' . esc_html(($ac['registration'] ?? '') . ' ' . ($ac['type'] ?? '')) . '</b> <span class="chip">' . ($r['status'] === 'done' ? 'załatwione' : 'nowe') . '</span><div class="muted">' . esc_html(substr($r['date'], 0, 16)) . '</div>'
                    . '<p>' . esc_html($r['work'] ?: 'serwis') . (!empty($ac['hours']) ? ' · nalot ' . esc_html($ac['hours']) . ' h' : '') . ($r['preferredDate'] ? ' · termin: ' . esc_html($r['preferredDate']) : '') . '</p>'
                    . '<p>' . esc_html($r['name']) . ' · <a href="tel:' . esc_attr($r['phone']) . '">' . esc_html($r['phone']) . '</a> · <a href="mailto:' . esc_attr($r['email']) . '">' . esc_html($r['email']) . '</a></p>'
                    . ($r['message'] ? '<p class="muted">' . nl2br(esc_html($r['message'])) . '</p>' : '')
                    . '<form method="post">' . $nonce . '<input type="hidden" name="ahp" value="req"><input type="hidden" name="rid" value="' . esc_attr($r['id']) . '"><input type="hidden" name="st" value="' . ($r['status'] === 'done' ? 'new' : 'done') . '"><button class="cna-btn">' . ($r['status'] === 'done' ? 'Oznacz jako nowe' : 'Oznacz jako załatwione') . '</button></form></div>';
            }
            $h .= '</div>';
            break;

        case 'listing':
            $c = json_decode(ah_read_editable('contacts'), true);
            $c = current(array_filter($c['contacts'] ?? [], fn($x) => ($x['id'] ?? '') === $cid)) ?: [];
            $f = fn($k, $label, $type = 'text') => '<label>' . esc_html($label) . '</label><input type="' . $type . '" name="' . $k . '" value="' . esc_attr($c[$k] ?? '') . '">';
            $h .= '<div class="ahp-card"><h3>' . esc_html($c['name'] ?? 'Wizytówka') . '</h3><p class="muted">Tak Twoja firma wygląda w katalogu aplikacji AeroHangar. Nazwę, rodzaj i położenie na mapie zmienia administrator.</p><form method="post">' . $nonce . '<input type="hidden" name="ahp" value="listing">'
                . $f('phone', 'Telefon') . $f('email', 'E-mail (tu przychodzą zapytania o przegląd)', 'email') . $f('web', 'Strona www', 'url') . $f('address', 'Adres') . $f('city', 'Miejscowość')
                . '<label>Opis (zakres usług, godziny)</label><textarea name="note" rows="4">' . esc_textarea($c['note'] ?? '') . '</textarea>'
                . '<label>Promocja dla użytkowników aplikacji</label><input type="text" name="promo" value="' . esc_attr($c['promo'] ?? '') . '">'
                . '<p><button class="cna-btn">Zapisz wizytówkę</button></p></form></div>';
            break;

        case 'stats':
            $ev = ah_store_read('events');
            krsort($ev);
            $names = ['view' => 'wyświetlenia', 'call' => 'telefony', 'email' => 'e-maile', 'web' => 'strona www', 'map' => 'mapa', 'request' => 'zapytania'];
            $h .= '<div class="ahp-card"><h3>Statystyki wizytówki</h3><table><tr><th>Miesiąc</th>';
            foreach ($names as $v) $h .= '<th>' . esc_html($v) . '</th>';
            $h .= '</tr>';
            $any = false;
            foreach (array_slice($ev, 0, 12, true) as $m => $row) {
                if (empty($row[$cid])) continue;
                $any = true;
                $h .= '<tr><td>' . esc_html($m) . '</td>';
                foreach ($names as $k => $v) $h .= '<td>' . (int)($row[$cid][$k] ?? 0) . '</td>';
                $h .= '</tr>';
            }
            $h .= '</table>' . ($any ? '' : '<p>Brak danych za ostatnie 12 miesięcy.</p>') . '</div>';
            break;
    }
    return $css . $h . '</div>';
}

// WooCommerce My Account → AeroHangar: link to the full client page.
add_filter('cannonade_account_top_html', function ($html, $uid) {
    $id = (int)get_option('aerohangar_login_page');
    if (!$id || is_page($id)) return $html;
    return $html . '<p><a href="' . esc_url(ah_login_page_url()) . '">Moje AeroHangar – flota, zapytania, wizytówka →</a></p>';
}, 5, 2);
