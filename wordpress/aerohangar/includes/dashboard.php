<?php
/**
 * Client page → "Karta": the app's home screen on the website – aircraft header, the framed "Do kolejnego
 * przeglądu" card, items due soon, open bulletins, the sponsor banner (rotating, "REKLAMA") and partners.
 * Computed on the server from the app's backup on the account, with the same rules as the app (Maintenance.kt).
 * Also: login form right on the client page (front end), no detour through wp-login.php.
 */
defined('ABSPATH') || exit;

// ---------------------------------------------------------------- front-end login

/** Login attempt from the form on the client page – before any output, so the session cookie can be set. */
add_action('template_redirect', function () {
    if (empty($_POST['ah_login']) || is_user_logged_in() || $_SERVER['REQUEST_METHOD'] !== 'POST') return;
    $login = sanitize_user(wp_unslash((string)($_POST['log'] ?? '')));
    $pass = (string)wp_unslash($_POST['pwd'] ?? '');
    if ($login === '' || $pass === '') { $GLOBALS['ah_login_error'] = 'Podaj login (lub e-mail) i hasło.'; return; }
    // e-mail works as login too
    if (is_email($login) && ($u = get_user_by('email', $login))) $login = $u->user_login;
    $user = wp_signon(['user_login' => $login, 'user_password' => $pass, 'remember' => !empty($_POST['rememberme'])], is_ssl());
    if (is_wp_error($user)) {
        $code = $user->get_error_code();
        $GLOBALS['ah_login_error'] = in_array($code, ['invalid_username', 'invalid_email', 'incorrect_password', 'empty_password', 'empty_username'], true)
            ? 'Nieprawidłowy login lub hasło.'
            : wp_strip_all_tags($user->get_error_message());
        $GLOBALS['ah_login_user'] = $login;
        return;
    }
    $to = ah_login_page_url();
    if (user_can($user, 'manage_options') || user_can($user, 'aerohangar_view')) $to = add_query_arg('ah', 'card', $to);
    wp_safe_redirect($to);
    exit;
});

/** The login form shown on the client page for guests. */
function ah_login_form_html(): string
{
    $err = (string)($GLOBALS['ah_login_error'] ?? '');
    $user = (string)($GLOBALS['ah_login_user'] ?? '');
    // Captcha / security plugins add their fields through the standard login_form hook – keep them working here.
    ob_start();
    do_action('login_form');
    $extra = (string)ob_get_clean();
    $page = ah_login_page_url();
    return '<div class="ahl">'
        . '<div class="ahl-head"><span class="ahl-logo">✈</span><div><b>AeroHangar</b><small>Zaloguj się do swojego konta</small></div></div>'
        . '<form method="post" action="' . esc_url($page) . '" class="ahl-body">'
        . ($err ? '<p class="ahl-err">' . esc_html($err) . '</p>' : '')
        . '<input type="hidden" name="ah_login" value="1">'
        . '<label for="ah-log">Login lub e-mail</label><input id="ah-log" type="text" name="log" autocomplete="username" required value="' . esc_attr($user) . '">'
        . '<label for="ah-pwd">Hasło</label><input id="ah-pwd" type="password" name="pwd" autocomplete="current-password" required>'
        . $extra
        . '<label class="ahl-rem"><input type="checkbox" name="rememberme" value="1" checked> Zapamiętaj mnie</label>'
        . '<button type="submit" class="ahl-btn">Zaloguj się</button>'
        . '<p class="ahl-links"><a href="' . esc_url(wp_lostpassword_url($page)) . '">Nie pamiętasz hasła?</a></p>'
        . '</form></div>';
}

// ---------------------------------------------------------------- maintenance (port of Maintenance.kt)

const AH_SOON_HOURS = 10.0;
const AH_SOON_DAYS = 30;
const AH_ST = ['OVERDUE' => 0, 'DUE' => 1, 'SOON' => 2, 'OK' => 3, 'NO_DATA' => 4, 'DONE' => 5, 'NO_INTERVAL' => 6];

function ah_json_file(string $f): array
{
    $d = json_decode((string)@file_get_contents(ah_data_path($f)), true);
    return is_array($d) ? $d : [];
}

/** Types and engines by id (catalogue + the owner's own types). */
function ah_mx_catalog(array $s): array
{
    static $base = null;
    if ($base === null) {
        $base = ['types' => [], 'engines' => []];
        $t = ah_json_file('types');
        foreach ((array)($t['types'] ?? $t) as $x) if (is_array($x) && isset($x['id'])) $base['types'][$x['id']] = $x;
        $e = ah_json_file('engines');
        foreach ((array)($e['engines'] ?? $e) as $x) if (is_array($x) && isset($x['id'])) $base['engines'][$x['id']] = $x;
    }
    $c = $base;
    foreach ((array)($s['customTypes'] ?? []) as $x) if (isset($x['id'])) $c['types'][$x['id']] ??= $x;
    return $c;
}

function ah_l10n($v): string
{
    if (is_array($v)) return (string)($v['pl'] ?? $v['en'] ?? reset($v) ?? '');
    return (string)$v;
}

function ah_fmt_h(float $h): string
{
    $r = round($h, 1);
    return fmod($r, 1.0) == 0.0 ? (string)(int)$r : str_replace('.', ',', (string)$r);
}

function ah_today_day(): int
{
    return intdiv((int)strtotime(wp_date('Y-m-d') . ' 00:00:00 UTC'), 86400);
}

/** Epoch day + months, the day clamped to the month's length (like Java's plusMonths). */
function ah_plus_months(int $day, int $months): int
{
    [$y, $m, $d] = array_map('intval', explode('-', gmdate('Y-n-j', $day * 86400)));
    $m += $months;
    $y += intdiv($m - 1, 12);
    $m = ($m - 1) % 12 + 1;
    if ($m < 1) { $m += 12; $y--; }
    $d = min($d, cal_days_in_month_safe($m, $y));
    return intdiv((int)gmmktime(0, 0, 0, $m, $d, $y), 86400);
}

function cal_days_in_month_safe(int $m, int $y): int
{
    return (int)gmdate('t', (int)gmmktime(0, 0, 0, $m, 1, $y));
}

function ah_mx_engine(array $a, ?array $type, array $engines): ?array
{
    return $engines[$a['engineId'] ?? ''] ?? $engines[$type['engine'] ?? ''] ?? $engines['rotax_912'] ?? (reset($engines) ?: null);
}

/** The aircraft's programme: [task, isCustom][] – same merging as the app. */
function ah_mx_tasks(array $a, array $cat): array
{
    $custom = array_map(fn($t) => [$t, true], (array)($a['customTasks'] ?? []));
    $type = $cat['types'][$a['typeId'] ?? ''] ?? null;
    if (!$type) return $custom;
    $airframe = array_values(array_filter((array)($type['tasks'] ?? []), fn($t) => !preg_match('/^(rotax_.*|prop|brs_.*)$/', (string)($t['id'] ?? ''))));
    $eng = ah_mx_engine($a, $type, $cat['engines']);
    $engine = array_values((array)($eng['tasks'] ?? []));
    $out = [];

    $engBase = null;
    foreach ($engine as $i => $t) {
        if (isset($t['intervalHours'], $t['intervalMonths']) && !isset($t['oneTimeAtHours']) && !str_ends_with((string)$t['id'], 'tbo')
            && ($engBase === null || $t['intervalHours'] < $engine[$engBase]['intervalHours'])) $engBase = $i;
    }
    $afBase = null;
    if ($engBase !== null) {
        foreach ($airframe as $i => $t) {
            if (($t['intervalHours'] ?? null) == $engine[$engBase]['intervalHours'] && isset($t['intervalMonths']) && !isset($t['oneTimeAtHours'])) { $afBase = $i; break; }
        }
    }
    if ($engBase !== null && $afBase !== null) {
        $e = $engine[$engBase];
        $af = $airframe[$afBase];
        $h = ah_fmt_h((float)$e['intervalHours']);
        $m = min((int)$e['intervalMonths'], (int)$af['intervalMonths']);
        $items = [['title' => 'Płatowiec: przegląd okresowy wg instrukcji obsługi producenta']];
        foreach ((array)($e['items'] ?? []) as $it) $items[] = ['title' => 'Silnik: ' . ah_l10n($it['title'] ?? ''), 'everyHours' => $it['everyHours'] ?? null];
        $out[] = array_merge($e, [
            'title' => "Przegląd okresowy $h h / $m mies.", 'category' => 'periodic', 'intervalMonths' => $m, 'items' => $items,
            'aliases' => array_merge((array)($e['aliases'] ?? []), [$af['id']], (array)($af['aliases'] ?? [])), 'mandatory' => true,
        ]);
        unset($airframe[$afBase], $engine[$engBase]);
    }
    foreach ($airframe as $t) $out[] = $t;
    foreach ($engine as $t) $out[] = $t;
    $prop = $a['propInspection'] ?? (($a['propeller'] ?? 'fixed') === 'inflight');
    if ($prop) $out[] = ['id' => 'prop', 'title' => 'Przegląd śmigła (wg instrukcji producenta śmigła)', 'category' => 'propeller', 'intervalMonths' => 12];
    if (!empty($a['rescueSystem'])) {
        $rm = (int)($a['rescueMonths'] ?? 72);
        $ys = $rm % 12 === 0 ? (string)($rm / 12) : number_format($rm / 12, 1, ',', '');
        $out[] = ['id' => 'brs_repack', 'title' => "System ratunkowy – resurs / przepakowanie (co $ys lat)", 'category' => 'rescue', 'intervalMonths' => $rm, 'aliases' => ['brs_rocket']];
    }
    return array_merge(array_map(fn($t) => [$t, false], $out), $custom);
}

function ah_mx_status(?float $remH, ?int $remD, float $tol): string
{
    if (($remH !== null && $remH < -$tol) || ($remD !== null && $remD < 0)) return 'OVERDUE';
    if (($remH !== null && $remH <= 0.0) || ($remD !== null && $remD === 0)) return 'DUE';
    if (($remH !== null && $remH <= AH_SOON_HOURS) || ($remD !== null && $remD <= AH_SOON_DAYS)) return 'SOON';
    return 'OK';
}

/** Due info of every visible task, sorted like the app (status, hours left, days left). */
function ah_mx_compute(array $a, array $s, array $cat): array
{
    $today = ah_today_day();
    $hours = (float)($a['hours'] ?? 0);
    $ov = (array)($a['taskOverrides'] ?? []);
    $out = [];
    foreach (ah_mx_tasks($a, $cat) as [$t, $custom]) {
        $id = (string)($t['id'] ?? '');
        $o = $ov[$id] ?? null;
        if (array_key_exists('hidden', (array)$o) ? !empty($o['hidden']) : !empty($t['defaultHidden'])) continue;
        if (is_array($o)) {
            if (isset($o['intervalHours'])) $t['intervalHours'] = $o['intervalHours'];
            if (isset($o['intervalMonths'])) $t['intervalMonths'] = $o['intervalMonths'];
        }
        $ids = array_merge([$id], (array)($t['aliases'] ?? []));
        $last = null;
        foreach ((array)($s['completions'] ?? []) as $c) {
            if (($c['aircraftId'] ?? '') !== ($a['id'] ?? '') || !in_array($c['taskId'] ?? '', $ids, true)) continue;
            if ($last === null || [(float)$c['hours'], (int)$c['epochDay']] > [(float)$last['hours'], (int)$last['epochDay']]) $last = $c;
        }
        $tol = (float)($t['toleranceHours'] ?? 0);
        $d = ['task' => $t, 'last' => $last, 'dueHours' => null, 'dueDay' => null, 'remH' => null, 'remD' => null, 'custom' => $custom];
        if (isset($t['oneTimeAtHours'])) {
            $one = (float)$t['oneTimeAtHours'];
            if ($last !== null || $hours >= $one * 2 + 10) $d['status'] = 'DONE';
            else { $d['dueHours'] = $one; $d['remH'] = $one - $hours; $d['status'] = ah_mx_status($d['remH'], null, $tol); }
            $out[] = $d;
            continue;
        }
        $ih = isset($t['intervalHours']) ? (float)$t['intervalHours'] : null;
        $im = isset($t['intervalMonths']) ? (int)$t['intervalMonths'] : null;
        if ($ih === null && $im === null) { $d['status'] = 'NO_INTERVAL'; $out[] = $d; continue; }
        if ($ih !== null) {
            if ($last === null) $d['dueHours'] = $hours < $ih ? $ih : null;
            elseif (isset($last['dueAtHours'], $t['toleranceHours']) && abs((float)$last['hours'] - (float)$last['dueAtHours']) <= (float)$t['toleranceHours']) $d['dueHours'] = (float)$last['dueAtHours'] + $ih;
            else $d['dueHours'] = (float)$last['hours'] + $ih;
        }
        if ($im !== null) {
            $ref = $last['epochDay'] ?? ($a['referenceEpochDay'] ?? null);
            if ($ref !== null) $d['dueDay'] = ah_plus_months((int)$ref, $im);
        }
        if ($d['dueHours'] === null && $d['dueDay'] === null) { $d['status'] = 'NO_DATA'; $out[] = $d; continue; }
        $d['remH'] = $d['dueHours'] !== null ? $d['dueHours'] - $hours : null;
        $d['remD'] = $d['dueDay'] !== null ? $d['dueDay'] - $today : null;
        $d['status'] = ah_mx_status($d['remH'], $d['remD'], $tol);
        $out[] = $d;
    }
    usort($out, fn($x, $y) => [AH_ST[$x['status']], $x['remH'] ?? PHP_FLOAT_MAX, $x['remD'] ?? PHP_INT_MAX] <=> [AH_ST[$y['status']], $y['remH'] ?? PHP_FLOAT_MAX, $y['remD'] ?? PHP_INT_MAX]);
    return $out;
}

/** Items to do at the inspection due at $dueHours (every-time items + those whose own step falls in). */
function ah_mx_items(array $t, ?float $dueHours): array
{
    $items = (array)($t['items'] ?? []);
    $base = isset($t['intervalHours']) ? (float)$t['intervalHours'] : null;
    return array_values(array_filter($items, function ($it) use ($dueHours, $base) {
        $e = isset($it['everyHours']) ? (float)$it['everyHours'] : null;
        if ($e === null) return true;
        if ($dueHours === null || $base === null || $e <= 0) return false;
        return floor($dueHours / $e) > floor(($dueHours - $base) / $e);
    }));
}

// ---------------------------------------------------------------- bulletins (port of Repo.kt)

function ah_bulletin_groups(array $a, array $cat): array
{
    $t = $cat['types'][$a['typeId'] ?? ''] ?? null;
    $g = [];
    foreach ((array)($t['bulletinGroups'] ?? []) as $x) {
        if (str_starts_with($x, 'ROTAX_')) continue;
        if ($x === 'EVEKTOR') {
            $n = strtolower((string)($t['name'] ?? ''));
            $x = str_contains($n, 'harmony') ? 'EVEKTOR_HARMONY' : (str_contains($n, 'rtc') ? 'EVEKTOR_RTC' : (str_contains($n, 'sportstar') ? 'EVEKTOR_SPORTSTAR' : 'EVEKTOR_EV97'));
        }
        $g[] = $x;
    }
    $e = ah_mx_engine($a, $t, $cat['engines']);
    return array_unique(array_merge($g, (array)($e['bulletinGroups'] ?? [])));
}

function ah_bulletin_groups_of(array $b): array
{
    $g = (array)($b['groups'] ?? []);
    if (!in_array('EVEKTOR', $g, true)) return $g;
    $id = strtoupper((string)$b['id']);
    $spec = ['EVEKTOR_EV97', 'EVEKTOR_SPORTSTAR', 'EVEKTOR_RTC', 'EVEKTOR_HARMONY'];
    foreach (['EVEKTOREV97' => 'EVEKTOR_EV97', 'EVEKTORSPORTSTAR' => 'EVEKTOR_SPORTSTAR', 'EVEKTORRTC' => 'EVEKTOR_RTC', 'EVEKTORHARMONY' => 'EVEKTOR_HARMONY'] as $p => $v) if (str_starts_with($id, $p)) { $spec = [$v]; break; }
    return array_merge(array_diff($g, ['EVEKTOR']), $spec);
}

/** Open bulletins of the aircraft (no DONE / NA status; information-only documents left out). */
function ah_open_bulletins(array $a, array $s, array $cat): array
{
    static $feed = null;
    $feed ??= (array)(ah_json_file('bulletins')['bulletins'] ?? []);
    $groups = ah_bulletin_groups($a, $cat);
    $own = [];
    foreach ((array)($s['ownBulletins'] ?? []) as $o) if (($o['aircraftId'] ?? '') === ($a['id'] ?? '') && isset($o['bulletin']['id'])) $own[$o['bulletin']['id']] = $o['bulletin'] + ['own' => true];
    $all = $own;
    foreach ($feed as $b) if (isset($b['id']) && !isset($all[$b['id']]) && array_intersect(ah_bulletin_groups_of($b), $groups)) $all[$b['id']] = $b;
    $handled = [];
    foreach ((array)($s['bulletinStatus'] ?? []) as $x) if (($x['aircraftId'] ?? '') === ($a['id'] ?? '')) $handled[$x['bulletinId'] ?? ''] = true;
    $open = array_values(array_filter($all, fn($b) => ($b['kind'] ?? '') !== 'DOC' && !(($b['kind'] ?? '') === 'SIB' && ($b['mandatory'] ?? null) === false) && !isset($handled[$b['id']])));
    usort($open, fn($x, $y) => strcmp((string)($y['date'] ?? ''), (string)($x['date'] ?? '')));
    return $open;
}

// ---------------------------------------------------------------- sponsor + partners

function ah_direct_image_url(string $u): string
{
    $s = trim($u);
    if (str_contains($s, 'drive.google.com') || str_contains($s, 'docs.google.com')) {
        if (preg_match('~/d/([A-Za-z0-9_-]{10,})~', $s, $m) || preg_match('~[?&]id=([A-Za-z0-9_-]{10,})~', $s, $m)) return 'https://lh3.googleusercontent.com/d/' . $m[1];
    }
    if (str_contains($s, 'dropbox.com')) {
        $s = str_replace('dl=0', 'raw=1', $s);
        if (!str_contains($s, 'raw=1')) $s .= (str_contains($s, '?') ? '&' : '?') . 'raw=1';
    }
    return $s;
}

function ah_sponsor_html(): string
{
    $f = ah_json_file('sponsor');
    $today = wp_date('Y-m-d');
    $live = array_values(array_filter((array)($f['banners'] ?? []), fn($b) => ($b['active'] ?? true)
        && (($b['from'] ?? '') === '' || $b['from'] <= $today) && (($b['until'] ?? '') === '' || $b['until'] >= $today)
        && (str_starts_with((string)($b['image'] ?? ''), 'https://') || trim((string)($b['text'] ?? '')) !== '' || trim((string)($b['title'] ?? '')) !== '')));
    if (!$live) return '';
    shuffle($live);
    $rot = (int)($f['rotateSeconds'] ?? 8);
    $h = '<div class="ahd-ad" data-rotate="' . max(0, $rot) . '">';
    foreach ($live as $i => $b) {
        $img = str_starts_with((string)($b['image'] ?? ''), 'https://') ? ah_direct_image_url($b['image']) : '';
        $in = $img ? '<img src="' . esc_url($img) . '" alt="' . esc_attr($b['title'] ?? '') . '" loading="lazy">' : '<span class="ahd-ad-text">' . esc_html(($b['text'] ?? '') ?: ($b['title'] ?? '')) . '</span>';
        $url = (string)($b['url'] ?? '');
        $h .= '<div class="ahd-ad-slide' . ($i ? '' : ' on') . '">' . ($url ? '<a href="' . esc_url($url) . '" target="_blank" rel="sponsored noopener">' . $in . '</a>' : $in) . '</div>';
    }
    $h .= '<span class="ahd-ad-label">REKLAMA</span></div>';
    if (count($live) > 1 && $rot > 0) {
        $h .= '<script>(function(){var w=document.currentScript.previousElementSibling,s=w.querySelectorAll(".ahd-ad-slide"),i=0;setInterval(function(){s[i].classList.remove("on");i=(i+1)%s.length;s[i].classList.add("on");},' . ($rot * 1000) . ');})();</script>';
    }
    return $h;
}

function ah_kind_label(string $k): string
{
    return ['manufacturer' => 'Producent', 'dealer' => 'Dealer', 'rotax_distributor' => 'Dystrybutor Rotax', 'rotax_service' => 'Serwis Rotax', 'maintenance' => 'Serwis',
        'mechanic' => 'Mechanik', 'service' => 'Serwis', 'school' => 'Szkoła lotnicza', 'instructor' => 'Instruktor', 'examiner' => 'Egzaminator', 'doctor' => 'Lekarz orzecznik'][$k] ?? $k;
}

function ah_partners_html(): string
{
    $today = wp_date('Y-m-d');
    $p = array_values(array_filter((array)(ah_json_file('contacts')['contacts'] ?? []), fn($c) => !empty($c['partnerUntil']) && $c['partnerUntil'] >= $today));
    if (!$p) return '';
    shuffle($p);
    $h = '<div class="ahd-partners">';
    foreach (array_slice($p, 0, 3) as $c) {
        $sub = implode(' · ', array_filter([ah_kind_label((string)($c['kind'] ?? '')), (string)($c['city'] ?? '')]));
        $logo = str_starts_with((string)($c['logo'] ?? ''), 'https://') ? '<img class="ahd-p-logo" src="' . esc_url(ah_direct_image_url($c['logo'])) . '" alt="">' : '';
        $act = '';
        if (!empty($c['phone'])) $act .= '<a class="ahd-btn" href="tel:' . esc_attr(preg_replace('/\s+/', '', $c['phone'])) . '">Zadzwoń</a>';
        if (!empty($c['web'])) $act .= '<a class="ahd-btn ahd-btn-o" href="' . esc_url($c['web']) . '" target="_blank" rel="noopener">Strona</a>';
        if (isset($c['lat'], $c['lon'])) $act .= '<a class="ahd-btn ahd-btn-o" href="' . esc_url('https://www.google.com/maps/search/?api=1&query=' . $c['lat'] . ',' . $c['lon']) . '" target="_blank" rel="noopener">Mapa</a>';
        $h .= '<div class="ahd-partner"><span class="ahd-p-tab">★ PARTNER</span><div class="ahd-p-card"><span class="ahd-p-star">★</span>' . $logo
            . '<div class="ahd-p-main"><b>' . esc_html($c['name'] ?? '') . '</b><small>' . esc_html($sub) . '</small>'
            . (!empty($c['promo']) ? '<em>' . esc_html($c['promo']) . '</em>' : '') . '</div><div class="ahd-p-act">' . $act . '</div></div></div>';
    }
    return $h . '</div>';
}

// ---------------------------------------------------------------- the card

function ah_status_look(string $st): array
{
    return [
        'OVERDUE' => ['Po terminie', '#C62828'], 'DUE' => ['Teraz', '#C62828'], 'SOON' => ['Wkrótce', '#B26A00'],
        'OK' => ['OK', '#2E7D32'], 'NO_DATA' => ['Brak danych', '#55606F'], 'DONE' => ['Wykonane', '#2E7D32'], 'NO_INTERVAL' => ['—', '#55606F'],
    ][$st] ?? [$st, '#55606F'];
}

function ah_day_str(?int $d): string
{
    return $d === null ? '' : wp_date('d.m.Y', $d * 86400 + 43200);
}

function ah_dashboard_html(int $uid, string $page): string
{
    $s = json_decode((string)get_user_meta($uid, 'aerohangar_state', true), true);
    $list = is_array($s) ? array_values(array_filter((array)($s['aircraft'] ?? []), 'is_array')) : [];
    $h = '';
    if (!$list) {
        return '<div class="ahp-card"><h3>Karta samolotu</h3><p>Brak danych z aplikacji. Połącz aplikację AeroHangar kluczem konta (ikona konta u góry → Konto Cannonade → Połącz) – po pierwszej synchronizacji Twoje samoloty, terminy przeglądów i biuletyny pojawią się tutaj.</p></div>' . ah_sponsor_html();
    }
    $cat = ah_mx_catalog($s);
    $sel = sanitize_text_field((string)($_GET['ac'] ?? ($s['selectedId'] ?? '')));
    $a = $list[0];
    foreach ($list as $x) if (($x['id'] ?? '') === $sel) $a = $x;

    if (count($list) > 1) {
        $h .= '<nav class="ahd-ac">';
        foreach ($list as $x) $h .= '<a class="' . (($x['id'] ?? '') === ($a['id'] ?? '') ? 'on' : '') . '" href="' . esc_url(add_query_arg(['ah' => 'card', 'ac' => $x['id'] ?? ''], $page)) . '">' . esc_html($x['registration'] ?? '?') . '</a>';
        $h .= '</nav>';
    }

    $type = $cat['types'][$a['typeId'] ?? ''] ?? null;
    $eng = ah_mx_engine($a, $type, $cat['engines']);
    $chip = ($type['category'] ?? '') === 'EASA' ? trim('EASA ' . ($type['tcds'] ?? '')) : 'UL / LSA';
    $h .= '<div class="ahd-hero"><div><div class="ahd-reg">' . esc_html($a['registration'] ?? '') . '</div><div class="ahd-type">' . esc_html($type['name'] ?? ($a['typeId'] ?? '')) . '</div>'
        . '<div class="ahd-sub">' . esc_html(implode(' · ', array_filter([$eng['name'] ?? '', !empty($a['engineSerial']) ? 'S/N silnika ' . $a['engineSerial'] : '', !empty($a['yearBuilt']) ? 'rok ' . $a['yearBuilt'] : '']))) . '</div></div>'
        . '<div class="ahd-hero-r"><span class="ahd-chip">' . esc_html($chip) . '</span><div class="ahd-hours"><b>' . esc_html(ah_fmt_h((float)($a['hours'] ?? 0))) . '</b> h nalotu'
        . (!empty($a['hoursUpdatedEpochDay']) ? '<small>stan z ' . esc_html(ah_day_str((int)$a['hoursUpdatedEpochDay'])) . '</small>' : '') . '</div></div></div>';

    $due = ah_mx_compute($a, $s, $cat);
    $next = null;
    foreach ($due as $d) if ($d['remH'] !== null && $d['status'] !== 'DONE' && ($next === null || $d['remH'] < $next['remH'])) $next = $d;

    if ($next) {
        [$label, $col] = ah_status_look($next['status']);
        $rem = (float)$next['remH'];
        $num = $rem >= 0 ? '<span class="ahd-num" style="color:' . $col . '">' . esc_html(ah_fmt_h($rem)) . '<small> h</small></span>'
            : '<span class="ahd-num ahd-num-over" style="color:' . $col . '">' . esc_html(ah_fmt_h(-$rem)) . ' h po terminie</span>';
        $items = ah_mx_items($next['task'], $next['dueHours']);
        $li = '';
        foreach (array_slice($items, 0, 8) as $it) $li .= '<li>' . esc_html(ah_l10n($it['title'] ?? '')) . '</li>';
        if (count($items) > 8) $li .= '<li class="muted">… i jeszcze ' . (count($items) - 8) . '</li>';
        $src = isset($a['logbookBaseHours']) ? 'Nalot z Pilot Logbook.' : 'Nalot wpisany w aplikacji.';
        $h .= '<details class="ahd-next" style="border-color:' . $col . '"><summary><div><span class="ahd-lbl">Do kolejnego przeglądu</span>' . $num . '</div>'
            . '<span class="ahd-st" style="background:' . $col . '">' . esc_html($label) . '</span><span class="ahd-chev">▾</span></summary>'
            . '<div class="ahd-next-body"><p><b>' . esc_html(ah_l10n($next['task']['title'] ?? '')) . '</b> przy ' . esc_html(ah_fmt_h((float)$next['dueHours'])) . ' h'
            . ($next['dueDay'] !== null ? ' · najpóźniej ' . esc_html(ah_day_str($next['dueDay'])) : '') . '</p>'
            . ($li ? '<ul>' . $li . '</ul>' : '') . '<p class="muted">' . esc_html($src) . '</p></div></details>';
    } elseif (array_filter($due, fn($d) => $d['status'] === 'NO_DATA')) {
        $h .= '<div class="ahp-card"><span class="ahd-lbl">Do kolejnego przeglądu</span><p>Brak danych do wyliczenia – wpisz w aplikacji, kiedy przeglądy były wykonane ostatnio.</p></div>';
    }

    $h .= ah_sponsor_html();

    // Items due soon / overdue (hours or calendar)
    $att = array_values(array_filter($due, fn($d) => in_array($d['status'], ['OVERDUE', 'DUE', 'SOON'], true)));
    $h .= '<div class="ahd-grid"><div class="ahp-card"><h3>Terminy</h3>';
    if (!$att) $h .= '<p class="ok">✓ Nic pilnego – żaden przegląd nie wypada w ciągu ' . (int)AH_SOON_HOURS . ' h ani ' . AH_SOON_DAYS . ' dni.</p>';
    foreach (array_slice($att, 0, 8) as $d) {
        [$label, $col] = ah_status_look($d['status']);
        $w = [];
        if ($d['remH'] !== null) $w[] = $d['remH'] >= 0 ? 'za ' . ah_fmt_h($d['remH']) . ' h' : ah_fmt_h(-$d['remH']) . ' h po terminie';
        if ($d['remD'] !== null) $w[] = $d['remD'] >= 0 ? 'za ' . $d['remD'] . ' dni (' . ah_day_str($d['dueDay']) . ')' : (-$d['remD']) . ' dni po terminie';
        $h .= '<div class="ahd-row"><span class="ahd-dot" style="background:' . $col . '"></span><div><b>' . esc_html(ah_l10n($d['task']['title'] ?? '')) . '</b><small>' . esc_html(implode(' · ', $w)) . '</small></div><span class="ahd-st" style="background:' . $col . '">' . esc_html($label) . '</span></div>';
    }
    $h .= '</div>';

    // Open bulletins
    $ob = ah_open_bulletins($a, $s, $cat);
    $h .= '<div class="ahp-card"><h3>Biuletyny do sprawdzenia <span class="ahd-count">' . count($ob) . '</span></h3>';
    if (!$ob) $h .= '<p class="ok">✓ Wszystkie biuletyny tego samolotu są oznaczone jako wykonane albo nie dotyczą.</p>';
    foreach (array_slice($ob, 0, 6) as $b) {
        $k = (string)($b['kind'] ?? '');
        $imp = $k === 'AD' || !empty($b['mandatory']);
        $t = esc_html($b['title'] ?? $b['id']);
        if (!empty($b['url'])) $t = '<a href="' . esc_url($b['url']) . '" target="_blank" rel="noopener">' . $t . '</a>';
        $h .= '<div class="ahd-row"><span class="ahd-kind' . ($imp ? ' imp' : '') . '">' . esc_html($k) . '</span><div>' . $t . '<small>' . esc_html(trim(($b['date'] ?? '') . (!empty($b['own']) ? ' · własny' : '') . ($imp ? ' · obowiązkowy' : ''), ' ·')) . '</small></div></div>';
    }
    if (count($ob) > 6) $h .= '<p class="muted">… i jeszcze ' . (count($ob) - 6) . '. Oznacz wykonane w aplikacji (Biuletyny)' . (ah_client_panel($uid) ? ' albo w <a href="' . esc_url(home_url('/index.php/aerohangar-panel')) . '">Zapleczu</a>' : '') . '.</p>';
    $h .= '</div></div>';

    $h .= ah_partners_html();
    $t = (int)get_user_meta($uid, 'aerohangar_state_time', true);
    $h .= '<p class="muted">Dane z ostatniej synchronizacji aplikacji' . ($t ? ' (' . esc_html(wp_date('Y-m-d H:i', $t)) . ')' : '') . '. Zmiany wpisuje się w aplikacji AeroHangar.</p>';
    return $h;
}

function ah_dashboard_css(): string
{
    return '<style>
.ahp{--n:#0B2A5B;--b:#1C5DB8;--pg:#F2F4F8;--g:#E8A317;font-family:Lato,system-ui,sans-serif}
.ahp-top{display:flex;flex-wrap:wrap;align-items:center;gap:10px;background:var(--n);color:#fff;border-radius:16px;padding:14px 18px;margin-bottom:12px}
.ahp-top b{font-size:1.05em}.ahp-top a{color:#fff}.ahp-top .sp{flex:1}.ahp-top .cna-btn{background:#fff!important;color:var(--n)!important;font-weight:700}
.ahd-ac{display:flex;gap:6px;flex-wrap:wrap;margin:0 0 10px}.ahd-ac a{padding:5px 12px;border-radius:8px;background:#E6EEF8;color:var(--n);text-decoration:none;font-weight:700}.ahd-ac a.on{background:var(--b);color:#fff}
.ahd-hero{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;background:linear-gradient(135deg,#0B2A5B,#1C5DB8);color:#fff;border-radius:18px;padding:18px 20px}
.ahd-reg{font-size:2em;font-weight:800;letter-spacing:.5px;line-height:1.1}.ahd-type{font-weight:600}.ahd-sub{opacity:.8;font-size:.9em}
.ahd-hero-r{text-align:right}.ahd-chip{display:inline-block;background:#fff;color:var(--n);border-radius:999px;padding:3px 10px;font-size:.8em;font-weight:700}
.ahd-hours{margin-top:8px}.ahd-hours b{font-size:1.5em}.ahd-hours small{display:block;opacity:.8}
.ahd-next{display:block;background:#fff;border:2px solid;border-radius:18px;margin:12px 0;box-shadow:0 3px 10px rgba(11,42,91,.12)}
.ahd-next summary{display:flex;align-items:center;gap:10px;padding:12px 16px;cursor:pointer;list-style:none}.ahd-next summary::-webkit-details-marker{display:none}
.ahd-next summary>div{flex:1}.ahd-lbl{display:block;color:var(--b);font-weight:700;font-size:.9em}.ahd-num{font-size:2em;font-weight:800;line-height:1.1}.ahd-num small{font-size:.5em}.ahd-num-over{font-size:1.3em}
.ahd-chev{transition:transform .2s;color:#55606F}.ahd-next[open] .ahd-chev{transform:rotate(180deg)}.ahd-next-body{padding:0 16px 12px}.ahd-next-body ul{margin:6px 0 6px 18px}
.ahd-st{color:#fff;border-radius:999px;padding:3px 10px;font-size:.8em;font-weight:700;white-space:nowrap}
.ahd-ad{position:relative;margin:12px 0 22px;border-radius:14px;overflow:visible}.ahd-ad-slide{display:none;border-radius:14px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.12);background:#fff}.ahd-ad-slide.on{display:block;animation:ahf .6s}
.ahd-ad img{display:block;width:100%;height:auto}.ahd-ad-text{display:block;padding:14px}.ahd-ad-label{position:absolute;right:16px;bottom:-14px;background:var(--n);color:#fff;font-size:9px;font-weight:700;padding:1px 6px;border-radius:0 0 5px 5px;letter-spacing:.5px}
@keyframes ahf{from{opacity:0}to{opacity:1}}
.ahd-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px}.ahd-grid .ahp-card{margin:0;background:#fff}
.ahd-row{display:flex;align-items:center;gap:10px;padding:7px 0;border-bottom:1px solid #EEF1F5}.ahd-row>div{flex:1}.ahd-row small{display:block;color:#55606F}
.ahd-dot{width:10px;height:10px;border-radius:50%;flex:none}.ahd-kind{flex:none;min-width:38px;text-align:center;border-radius:6px;background:#E6EEF8;color:var(--n);font-size:.75em;font-weight:800;padding:2px 4px}.ahd-kind.imp{background:#C62828;color:#fff}
.ahd-count{background:var(--b);color:#fff;border-radius:999px;padding:0 9px;font-size:.75em;vertical-align:middle}
.ahd-partners{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin:16px 0}
.ahd-p-tab{display:inline-block;background:var(--n);color:#fff;font-size:.72em;font-weight:800;letter-spacing:.6px;padding:3px 10px;border-radius:8px 8px 0 0}
.ahd-p-card{display:flex;align-items:center;gap:10px;flex-wrap:wrap;background:#fff;border:1px solid #DDE3EB;border-radius:0 14px 14px 14px;padding:10px 12px;box-shadow:0 2px 6px rgba(11,42,91,.08)}
.ahd-p-star{width:34px;height:34px;flex:none;border-radius:50%;background:#FFF1CF;border:2px solid var(--g);color:var(--g);display:flex;align-items:center;justify-content:center;font-size:18px}
.ahd-p-logo{height:34px;width:auto;max-width:70px;object-fit:contain}.ahd-p-main{flex:1;min-width:140px}.ahd-p-main b{display:block;color:var(--n)}.ahd-p-main small{color:#55606F}.ahd-p-main em{display:block;font-size:.85em;color:#8a6100}
.ahd-p-act{display:flex;gap:6px}.ahd-btn{background:var(--n);color:#fff!important;border-radius:999px;padding:5px 12px;font-size:.85em;text-decoration:none;font-weight:700}.ahd-btn-o{background:#fff;color:var(--n)!important;border:1px solid var(--n)}
.ahl{max-width:420px;margin:10px auto 18px;border-radius:18px;overflow:hidden;box-shadow:0 6px 24px rgba(11,42,91,.18);background:#fff;font-family:Lato,system-ui,sans-serif}
.ahl-head{display:flex;align-items:center;gap:12px;background:linear-gradient(135deg,#0B2A5B,#1C5DB8);color:#fff;padding:18px 22px}.ahl-head b{display:block;font-size:1.3em}.ahl-head small{opacity:.85}
.ahl-logo{width:42px;height:42px;border-radius:12px;background:rgba(255,255,255,.15);display:flex;align-items:center;justify-content:center;font-size:22px}
.ahl-body{padding:18px 22px 20px}.ahl-body label{display:block;font-weight:700;color:#0B2A5B;margin:10px 0 4px}.ahl-body input[type=text],.ahl-body input[type=password],.ahl-body input[type=email],.ahl-body input[type=number]{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid #C9D2DE;border-radius:10px;font-size:1em}
.ahl-body input:focus{outline:2px solid #1C5DB8;border-color:#1C5DB8}.ahl-body label.ahl-rem{font-weight:400;color:#333;display:flex;gap:6px;align-items:center}
.ahl-btn{width:100%;margin-top:14px;background:#0B2A5B;color:#fff;border:0;border-radius:999px;padding:11px;font-size:1em;font-weight:700;cursor:pointer}.ahl-btn:hover{background:#1C5DB8}
.ahl-err{background:#FDECEC;color:#C62828;border-radius:10px;padding:8px 12px;font-weight:700}.ahl-links{text-align:center;margin:12px 0 0}
</style>';
}
