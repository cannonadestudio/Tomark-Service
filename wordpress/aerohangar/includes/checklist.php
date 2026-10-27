<?php
/**
 * Inspection checklists (the same as in the app):
 *  - client page → "Checklista": pilots and mechanics open the checklist of an inspection of their aircraft,
 *    tick the work, write values and notes, edit the list (own version per aircraft) and finish the inspection –
 *    the protocol goes to the account and the app picks it up at the next refresh;
 *  - printable protocol (browser → Save as PDF / print);
 *  - corrections of the list proposed from the app or the website wait in wp-admin → AeroHangar → Poprawki checklist;
 *    approved ones are written into the shared lists (types / engines) and stay after the daily update from GitHub.
 */
defined('ABSPATH') || exit;

// ---------------------------------------------------------------- protocol helpers

function ah_chk_split(string $title): array
{
    $i = mb_strpos($title, ': ');
    return ($i !== false && $i >= 1 && $i <= 24) ? [mb_substr($title, 0, $i), mb_substr($title, $i + 2)] : ['', $title];
}

function ah_chk_full(array $p): string
{
    return ($p['section'] ?? '') !== '' ? $p['section'] . ': ' . $p['title'] : (string)$p['title'];
}

function ah_chk_from_item(array $it): array
{
    [$sec, $title] = ah_chk_split(ah_l10n($it['title'] ?? ''));
    $fields = array_map('ah_l10n', (array)($it['fields'] ?? []));
    return ['title' => $title, 'section' => $sec, 'everyHours' => isset($it['everyHours']) ? (float)$it['everyHours'] : null,
        'verified' => ($it['verified'] ?? true) !== false, 'fields' => $fields, 'unit' => (string)($it['unit'] ?? ''),
        'values' => array_fill(0, count($fields), ''), 'done' => false, 'na' => false, 'note' => ''];
}

function ah_chk_to_item(array $p): array
{
    $t = ah_chk_full($p);
    $it = ['title' => ['pl' => $t, 'en' => $t], 'verified' => true];
    if (!empty($p['everyHours'])) $it['everyHours'] = (float)$p['everyHours'];
    if (!empty($p['fields'])) $it['fields'] = array_map(fn($f) => ['pl' => $f, 'en' => $f], $p['fields']);
    if (($p['unit'] ?? '') !== '') $it['unit'] = $p['unit'];
    return $it;
}

function ah_chk_draft(array $s, string $aid, string $tid): ?array
{
    foreach ((array)($s['protocols'] ?? []) as $p) if (($p['aircraftId'] ?? '') === $aid && ($p['taskId'] ?? '') === $tid && empty($p['finished'])) return $p;
    return null;
}

function ah_chk_new(array $a, array $d): array
{
    return ['id' => wp_generate_uuid4(), 'aircraftId' => $a['id'], 'taskId' => (string)$d['task']['id'], 'taskTitle' => ah_l10n($d['task']['title'] ?? ''),
        'dueAtHours' => $d['dueHours'], 'items' => array_map('ah_chk_from_item', ah_mx_items($d['task'], $d['dueHours'])),
        'finished' => false, 'source' => implode(' · ', array_filter([(string)($d['task']['source'] ?? ''), (string)($d['task']['itemsSource'] ?? '')])),
        'updatedMillis' => (int)(microtime(true) * 1000)];
}

/** State changes made on the website (applied by ah_state_apply in backoffice.php). */
function ah_chk_apply(array &$s, array $op): void
{
    $aid = (string)($op['aircraftId'] ?? '');
    switch ($op['op']) {
        case 'protocol_save':
            $p = $op['protocol'];
            $s['protocols'] = array_values(array_filter((array)($s['protocols'] ?? []), fn($x) => ($x['id'] ?? '') !== $p['id']));
            $s['protocols'][] = $p;
            break;
        case 'protocol_delete':
            $s['protocols'] = array_values(array_filter((array)($s['protocols'] ?? []), fn($x) => ($x['id'] ?? '') !== $op['id']));
            break;
        case 'protocol_finish':
            $p = $op['protocol'];
            $rec = ['id' => $p['completionId'], 'aircraftId' => $aid, 'taskId' => $p['taskId'], 'epochDay' => (int)$p['epochDay'], 'hours' => (float)$p['hours'],
                'note' => implode(' · ', array_filter([(string)$p['mechanic'], (string)$p['notes']]))];
            if ($p['dueAtHours'] !== null) $rec['dueAtHours'] = (float)$p['dueAtHours'];
            $s['protocols'] = array_values(array_filter((array)($s['protocols'] ?? []), fn($x) => ($x['id'] ?? '') !== $p['id']));
            $s['protocols'][] = $p;
            $s['completions'] = array_values(array_filter((array)($s['completions'] ?? []), fn($x) => ($x['id'] ?? '') !== $rec['id']));
            $s['completions'][] = $rec;
            break;
        case 'items_edit':
            foreach ((array)($s['aircraft'] ?? []) as $i => $a) {
                if (($a['id'] ?? '') !== $aid) continue;
                $e = (array)($a['itemEdits'] ?? []);
                if ($op['items'] === null) unset($e[$op['taskId']]); else $e[$op['taskId']] = $op['items'];
                $s['aircraft'][$i]['itemEdits'] = $e ?: new stdClass();
            }
            break;
    }
}

// ---------------------------------------------------------------- corrections (suggestions) and the shared lists

function ah_chk_suggest(array $sug): bool
{
    return (bool)ah_store_update('checklist_suggestions', function (&$d) use ($sug) {
        $open = count(array_filter($d, fn($x) => ($x['status'] ?? '') === 'new'));
        if ($open >= 1000) return false;
        $d[] = $sug + ['id' => bin2hex(random_bytes(6)), 'date' => date('c'), 'status' => 'new'];
        if (count($d) > 3000) $d = array_slice($d, -3000);
        return true;
    });
}

/** REST (app): proposal of a correction – public, rate limited; the account name is added when the key is sent. */
function ah_chk_suggest_api(array $body): void
{
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') ah_out(405, ['error' => 'POST only']);
    if (!ah_rate_ok('chk_suggest', 40, 86400)) ah_out(429, ['error' => 'too many today']);
    $from = '';
    $key = (string)($_SERVER['HTTP_X_CANNONADE_KEY'] ?? '');
    if ($key !== '' && function_exists('cannonade_user_from_key') && ($uid = cannonade_user_from_key($key)) && ($u = get_userdata($uid))) $from = $u->display_name . ' (' . $u->user_login . ')';
    $item = is_array($body['item'] ?? null) ? ah_chk_clean_item($body['item']) : null;
    $sug = ['typeId' => sanitize_key((string)($body['typeId'] ?? '')), 'engineId' => sanitize_key((string)($body['engineId'] ?? '')),
        'taskId' => sanitize_key((string)($body['taskId'] ?? '')), 'original' => ah_clean_str($body['original'] ?? '', 300),
        'item' => $item, 'comment' => ah_clean_str($body['comment'] ?? '', 600), 'from' => $from ?: 'aplikacja', 'via' => 'app'];
    if ($sug['taskId'] === '' || ($item === null && $sug['original'] === '')) ah_out(400, ['error' => 'nothing to suggest']);
    if (!ah_chk_suggest($sug)) ah_out(503, ['error' => 'queue full']);
    ah_out(200, ['ok' => true]);
}

function ah_chk_clean_item(array $it): ?array
{
    $t = ah_clean_str(ah_l10n($it['title'] ?? ''), 300);
    if ($t === '') return null;
    $out = ['title' => ['pl' => $t, 'en' => ah_clean_str(is_array($it['title'] ?? null) ? ($it['title']['en'] ?? $t) : $t, 300) ?: $t], 'verified' => true];
    if (isset($it['everyHours']) && (float)$it['everyHours'] > 0) $out['everyHours'] = (float)$it['everyHours'];
    $f = array_slice(array_values(array_filter(array_map(fn($x) => ah_clean_str(ah_l10n($x), 40), (array)($it['fields'] ?? [])))), 0, 12);
    if ($f) $out['fields'] = array_map(fn($x) => ['pl' => $x, 'en' => $x], $f);
    $u = ah_clean_str($it['unit'] ?? '', 12);
    if ($u !== '') $out['unit'] = $u;
    return $out;
}

/**
 * Where an item of a (possibly merged) task lives: the engine catalogue or the aircraft type.
 * "Silnik: …" / "Engine: …" → engine task; "Płatowiec: …" / "Airframe: …" → the type's airframe task.
 */
function ah_chk_target(array $sug, array &$types, array &$engines): ?array
{
    $orig = (string)$sug['original'];
    $new = $sug['item'] ? ah_l10n($sug['item']['title']) : '';
    $probe = $orig !== '' ? $orig : $new;
    [$sec] = ah_chk_split($probe);
    $isEngine = in_array(mb_strtolower($sec), ['silnik', 'engine', 'motor', 'moteur'], true);
    $isAirframe = in_array(mb_strtolower($sec), ['płatowiec', 'airframe', 'drak', 'cellule', 'zelle'], true);
    $ei = null; foreach ($engines as $i => $e) if (($e['id'] ?? '') === $sug['engineId']) $ei = $i;
    $ti = null; foreach ($types as $i => $t) if (($t['id'] ?? '') === $sug['typeId']) $ti = $i;
    $find = function (array $tasks, string $id) { foreach ($tasks as $k => $t) if (($t['id'] ?? '') === $id) return $k; return null; };
    if ($ti !== null && !$isEngine && ($k = $find($types[$ti]['tasks'] ?? [], $sug['taskId'])) !== null) return ['types', $ti, $k, ''];
    if ($ei !== null && ($k = $find($engines[$ei]['tasks'] ?? [], $sug['taskId'])) !== null) {
        if (!$isAirframe) return ['engines', $ei, $k, $isEngine ? $sec : ''];
        // airframe part of a merged periodic inspection: the type task with the same hour interval
        $ih = $engines[$ei]['tasks'][$k]['intervalHours'] ?? null;
        if ($ti !== null) foreach ($types[$ti]['tasks'] ?? [] as $k2 => $t) if (($t['intervalHours'] ?? null) == $ih && isset($t['intervalMonths']) && !isset($t['oneTimeAtHours'])) return ['types', $ti, $k2, $sec];
    }
    return null;
}

/** Applies one approved correction to the lists in memory. Returns true when something changed. */
function ah_chk_apply_correction(array $sug, array &$types, array &$engines): bool
{
    $tg = ah_chk_target($sug, $types, $engines);
    if (!$tg) return false;
    [$file, $i, $k, $prefix] = $tg;
    if ($file === 'types') $items = (array)($types[$i]['tasks'][$k]['items'] ?? []); else $items = (array)($engines[$i]['tasks'][$k]['items'] ?? []);
    $strip = function (string $t) use ($prefix) { if ($prefix === '') return $t; [$s, $rest] = ah_chk_split($t); return $s !== '' ? $rest : $t; };
    $orig = $strip((string)$sug['original']);
    $item = $sug['item'];
    if ($item) {
        $t = $strip(ah_l10n($item['title']));
        $item['title'] = ['pl' => $t, 'en' => $strip((string)($item['title']['en'] ?? $t)) ?: $t];
    }
    $pos = null;
    foreach ($items as $n => $it) {
        $tl = (array)($it['title'] ?? []);
        if ($orig !== '' && (($tl['pl'] ?? '') === $orig || ($tl['en'] ?? '') === $orig)) { $pos = $n; break; }
    }
    if ($item && $pos !== null) {
        // keep the other languages unless the text changed
        $old = $items[$pos];
        // text changed: the other languages would no longer match – they take the new text until translated
        if (($old['title']['pl'] ?? '') !== $item['title']['pl']) $old['title'] = ['pl' => $item['title']['pl'], 'en' => $item['title']['en']];
        foreach (['everyHours', 'fields', 'unit'] as $f) { if (isset($item[$f])) $old[$f] = $item[$f]; else unset($old[$f]); }
        $old['verified'] = true;
        $items[$pos] = $old;
    } elseif ($item) {
        foreach ($items as $it) if (($it['title']['pl'] ?? '') === $item['title']['pl']) return false;
        $items[] = $item;
    } elseif ($pos !== null) {
        array_splice($items, $pos, 1);
    } else {
        return false;
    }
    if ($file === 'types') $types[$i]['tasks'][$k]['items'] = array_values($items); else $engines[$i]['tasks'][$k]['items'] = array_values($items);
    return true;
}

/** Writes all approved corrections into types.json / engines.json (after an approval and after the daily pull). */
function ah_chk_overlay_apply(string $by = 'system'): int
{
    $ov = (array)get_option('aerohangar_checklist_overlay', []);
    if (!$ov) return 0;
    $rawT = ah_read_editable('types');
    $types = json_decode($rawT, true);
    $rawE = (string)@file_get_contents(ah_data_path('engines'));
    $engines = json_decode($rawE, true);
    if (!is_array($types) || !is_array($engines)) return 0;
    $n = 0;
    foreach ($ov as $sug) if (ah_chk_apply_correction($sug, $types, $engines)) $n++;
    if ($n) {
        $newT = json_encode($types, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        if ($newT !== json_encode(json_decode($rawT, true), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)) { ah_backup('types', $rawT); ah_write_editable('types', $types); }
        ah_write_atomic(ah_data_path('engines'), json_encode($engines, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n");
        ah_log($by, "checklist corrections applied: $n");
    }
    return $n;
}
add_action('aerohangar_after_pull', fn() => ah_chk_overlay_apply('cron'));

// ---------------------------------------------------------------- wp-admin: review of corrections

add_action('admin_menu', function () {
    $n = count(array_filter(ah_store_read('checklist_suggestions'), fn($x) => ($x['status'] ?? '') === 'new'));
    add_submenu_page('aerohangar-panel', 'Poprawki checklist', 'Poprawki checklist' . ($n ? ' <span class="awaiting-mod">' . $n . '</span>' : ''), 'manage_options', 'aerohangar-checklists', 'ah_chk_admin_page', 8);
}, 20);

function ah_chk_admin_page(): void
{
    if (!current_user_can('manage_options')) return;
    $me = wp_get_current_user()->user_login;
    $msg = '';
    if (!empty($_POST['ahc']) && check_admin_referer('ahc')) {
        $id = sanitize_key((string)($_POST['sid'] ?? ''));
        $act = (string)$_POST['ahc'];
        $all = ah_store_read('checklist_suggestions');
        $sug = null; foreach ($all as $x) if (($x['id'] ?? '') === $id) $sug = $x;
        if ($sug && $act === 'approve') {
            // the administrator may correct the text before approving
            if (isset($_POST['title']) && trim((string)$_POST['title']) !== '') {
                $sug['item'] = ah_chk_clean_item(['title' => wp_unslash((string)$_POST['title']), 'everyHours' => (float)str_replace(',', '.', (string)($_POST['every'] ?? '')),
                    'fields' => array_map('trim', explode(',', wp_unslash((string)($_POST['fields'] ?? '')))), 'unit' => wp_unslash((string)($_POST['unit'] ?? ''))]);
            }
            $ov = (array)get_option('aerohangar_checklist_overlay', []);
            $ov[] = array_intersect_key($sug, array_flip(['id', 'typeId', 'engineId', 'taskId', 'original', 'item', 'comment', 'from', 'date']));
            update_option('aerohangar_checklist_overlay', $ov, false);
            $n = ah_chk_overlay_apply($me);
            $msg = $n ? 'Zatwierdzono – lista zaktualizowana dla wszystkich (aplikacje zobaczą ją przy następnym odświeżeniu).' : 'Zatwierdzono, ale nie znaleziono pozycji w bazie (lista mogła się zmienić) – sprawdź typ i przegląd.';
            ah_log($me, "checklist suggestion $id approved");
        }
        if ($sug && in_array($act, ['approve', 'reject'], true)) {
            ah_store_update('checklist_suggestions', function (&$d) use ($id, $act, $me) { foreach ($d as $i => $x) if (($x['id'] ?? '') === $id) { $d[$i]['status'] = $act === 'approve' ? 'approved' : 'rejected'; $d[$i]['by'] = $me; } });
            if ($act === 'reject') $msg = 'Odrzucono.';
        }
        if ($act === 'undo') {
            $ov = array_values(array_filter((array)get_option('aerohangar_checklist_overlay', []), fn($x) => ($x['id'] ?? '') !== $id));
            update_option('aerohangar_checklist_overlay', $ov, false);
            $msg = 'Usunięto z listy zatwierdzonych poprawek. Pozycja wróci do wersji z GitHuba przy najbliższej codziennej aktualizacji.';
        }
    }
    $types = []; foreach ((array)json_decode(ah_read_editable('types'), true) as $t) if (isset($t['id'])) $types[$t['id']] = $t['name'] ?? $t['id'];
    $eng = []; foreach ((array)json_decode((string)@file_get_contents(ah_data_path('engines')), true) as $e) if (isset($e['id'])) $eng[$e['id']] = $e['name'] ?? $e['id'];
    $list = array_reverse(ah_store_read('checklist_suggestions'));
    $nonce = wp_nonce_field('ahc', '_wpnonce', true, false);
    echo '<div class="wrap"><h1>Poprawki checklist</h1>';
    if ($msg) echo '<div class="notice notice-success"><p>' . esc_html($msg) . '</p></div>';
    echo '<p>Propozycje zmian list prac przy przeglądach – wysłane z aplikacji AeroHangar albo ze strony klienta (Checklista → Edytuj → „Zgłoś poprawkę do bazy”). Po zatwierdzeniu poprawka trafia do wspólnej listy typu samolotu lub silnika dla wszystkich użytkowników i zostaje także po codziennej aktualizacji z GitHuba. Możesz poprawić treść przed zatwierdzeniem.</p>';
    echo '<table class="widefat striped" style="max-width:1300px"><thead><tr><th>Data / od</th><th>Samolot · przegląd</th><th>Było</th><th>Propozycja</th><th>Akcje</th></tr></thead><tbody>';
    $shown = 0;
    foreach ($list as $x) {
        $st = $x['status'] ?? 'new';
        if ($st !== 'new' && $shown > 60) continue;
        $shown++;
        $it = $x['item'] ?? null;
        $fields = $it ? implode(', ', array_map('ah_l10n', (array)($it['fields'] ?? []))) : '';
        echo '<tr><td>' . esc_html(substr((string)$x['date'], 0, 16)) . '<br><small>' . esc_html($x['from'] ?? '') . ' · ' . esc_html($x['via'] ?? '') . '</small></td>';
        echo '<td>' . esc_html($types[$x['typeId']] ?? $x['typeId']) . '<br><small>' . esc_html($eng[$x['engineId']] ?? $x['engineId']) . ' · ' . esc_html($x['taskId']) . '</small></td>';
        echo '<td>' . ($x['original'] !== '' ? esc_html($x['original']) : '<i>nowa pozycja</i>') . '</td><td>';
        if ($st === 'new') {
            echo '<form method="post">' . $nonce . '<input type="hidden" name="sid" value="' . esc_attr($x['id']) . '">';
            if ($it) {
                echo '<input name="title" value="' . esc_attr(ah_l10n($it['title'])) . '" style="width:100%"><br>'
                    . '<label>co <input name="every" value="' . esc_attr(isset($it['everyHours']) ? (string)$it['everyHours'] : '') . '" size="4"> h</label> '
                    . '<label>pola <input name="fields" value="' . esc_attr($fields) . '" size="18"></label> '
                    . '<label>jedn. <input name="unit" value="' . esc_attr($it['unit'] ?? '') . '" size="4"></label>';
            } else echo '<b style="color:#C62828">usunąć pozycję</b>';
            if (!empty($x['comment'])) echo '<p class="description">„' . esc_html($x['comment']) . '”</p>';
            echo '<p><button class="button button-primary" name="ahc" value="approve">Zatwierdź</button> <button class="button" name="ahc" value="reject">Odrzuć</button></p></form>';
        } else {
            echo ($it ? esc_html(ah_l10n($it['title'])) . ($fields ? ' <small>[' . esc_html($fields) . ']</small>' : '') : '<i>usunięcie</i>');
            if (!empty($x['comment'])) echo '<p class="description">„' . esc_html($x['comment']) . '”</p>';
        }
        echo '</td><td>' . ($st === 'new' ? '<b>nowa</b>' : ($st === 'approved' ? '<span style="color:#1B5E20">✓ zatwierdzona</span><form method="post">' . $nonce . '<input type="hidden" name="sid" value="' . esc_attr($x['id']) . '"><button class="button-link" name="ahc" value="undo">cofnij</button></form>' : 'odrzucona')) . '</td></tr>';
    }
    if (!$list) echo '<tr><td colspan="5">Brak propozycji.</td></tr>';
    echo '</tbody></table></div>';
}

// ---------------------------------------------------------------- client page: checklist

function ah_chk_aircraft(int $uid, string $aid): array
{
    $s = ah_state_get($uid);
    if (!$s) return [null, null];
    foreach ((array)($s['aircraft'] ?? []) as $a) if (($a['id'] ?? '') === $aid) return [$s, $a];
    return [$s, null];
}

/** Saves a change made on the website into the account (and keeps it for the phone). */
function ah_chk_change(int $uid, array $op): void
{
    $s = ah_state_get($uid);
    if (!$s) return;
    if (!in_array($op['aircraftId'] ?? '', array_column((array)($s['aircraft'] ?? []), 'id'), true)) return;
    ah_state_web_change($uid, $op);
}

function ah_chk_post_items(array $p): array
{
    $items = $p['items'];
    foreach ($items as $i => $it) {
        $items[$i]['done'] = !empty($_POST['done'][$i]);
        $items[$i]['na'] = !empty($_POST['na'][$i]) && empty($_POST['done'][$i]);
        $items[$i]['note'] = ah_clean_str(wp_unslash((string)($_POST['note'][$i] ?? '')), 600);
        $vals = (array)($_POST['val'][$i] ?? []);
        $items[$i]['values'] = array_map(fn($k) => ah_clean_str(wp_unslash((string)($vals[$k] ?? '')), 40), array_keys((array)$it['fields']));
    }
    return $items;
}

function ah_checklist_html(int $uid, string $page): string
{
    $edit = function_exists('ah_can') ? ah_can($uid, 'fleet_edit') : true;
    $s = ah_state_get($uid);
    $list = $s ? array_values(array_filter((array)($s['aircraft'] ?? []), 'is_array')) : [];
    if (!$list) return '<div class="ahp-card"><h3>Checklista</h3><p>Brak danych z aplikacji – połącz aplikację AeroHangar kluczem konta, a Twoje samoloty i przeglądy pojawią się tutaj.</p></div>';
    $aid = sanitize_text_field((string)($_GET['ac'] ?? ($s['selectedId'] ?? '')));
    $a = $list[0]; foreach ($list as $x) if (($x['id'] ?? '') === $aid) $a = $x;
    $aid = (string)$a['id'];
    $tid = sanitize_key((string)($_GET['task'] ?? ''));
    $url = fn(array $q) => esc_url(add_query_arg(array_merge(['ah' => 'check', 'ac' => $aid], $q), $page));
    $nonce = wp_nonce_field('ahchk_' . $uid, '_wpnonce', true, false);
    $msg = '';
    $cat = ah_mx_catalog($s);

    // ---- actions
    if ($edit && !empty($_POST['ahchk']) && check_admin_referer('ahchk_' . $uid)) {
        $act = (string)$_POST['ahchk'];
        $due = []; foreach (ah_mx_compute($a, $s, $cat) as $d) $due[$d['task']['id']] = $d;
        $d = $due[$tid] ?? null;
        $p = ah_chk_draft($s, $aid, $tid) ?? ($d ? ah_chk_new($a, $d) : null);
        if ($p && in_array($act, ['save', 'finish'], true)) {
            $p['items'] = ah_chk_post_items($p);
            $p['updatedMillis'] = (int)(microtime(true) * 1000);
            if ($act === 'finish') {
                $day = strtotime((string)($_POST['date'] ?? '') . ' 00:00:00 UTC');
                $h = (float)str_replace(',', '.', (string)($_POST['hours'] ?? ''));
                if (!$day || $h <= 0) $msg = 'Podaj datę i nalot przy zakończeniu przeglądu.';
                else {
                    $p = array_merge($p, ['finished' => true, 'epochDay' => intdiv($day, 86400), 'hours' => $h,
                        'place' => ah_clean_str(wp_unslash((string)($_POST['place'] ?? '')), 80), 'mechanic' => ah_clean_str(wp_unslash((string)($_POST['mechanic'] ?? '')), 120),
                        'licence' => ah_clean_str(wp_unslash((string)($_POST['licence'] ?? '')), 80), 'notes' => ah_clean_str(wp_unslash((string)($_POST['notes'] ?? '')), 1500),
                        'completionId' => $p['completionId'] ?? wp_generate_uuid4()]);
                    ah_chk_change($uid, ['op' => 'protocol_finish', 'aircraftId' => $aid, 'protocol' => $p]);
                    $msg = 'Przegląd zakończony i zapisany – protokół jest gotowy do wydruku. Aplikacja zobaczy go przy następnym odświeżeniu.';
                    $GLOBALS['ah_chk_finished'] = $p['id'];
                }
            }
            if ($act === 'save' || !empty($msg) && empty($GLOBALS['ah_chk_finished'])) {
                ah_chk_change($uid, ['op' => 'protocol_save', 'aircraftId' => $aid, 'protocol' => $p]);
                if ($act === 'save') $msg = 'Zapisano.';
            }
        }
        if ($p && $act === 'discard') { ah_chk_change($uid, ['op' => 'protocol_delete', 'aircraftId' => $aid, 'id' => $p['id']]); $msg = 'Checklista odrzucona.'; }
        if ($act === 'reset') { ah_chk_change($uid, ['op' => 'items_edit', 'aircraftId' => $aid, 'taskId' => $tid, 'items' => null]); $msg = 'Przywrócono listę z bazy.'; }
        if ($p && $d && in_array($act, ['item_save', 'item_remove'], true)) {
            $idx = (int)($_POST['idx'] ?? -1);
            $p['items'] = ah_chk_post_items($p);
            $cur = $idx >= 0 ? ($p['items'][$idx] ?? null) : null;
            $fields = array_values(array_filter(array_map('trim', explode(',', wp_unslash((string)($_POST['fields'] ?? ''))))));
            $new = ['title' => ah_clean_str(wp_unslash((string)($_POST['title'] ?? '')), 300), 'section' => $cur['section'] ?? ah_clean_str(wp_unslash((string)($_POST['section'] ?? '')), 40),
                'everyHours' => ((float)str_replace(',', '.', (string)($_POST['every'] ?? ''))) ?: null, 'verified' => true, 'fields' => $fields,
                'unit' => ah_clean_str(wp_unslash((string)($_POST['unit'] ?? '')), 12)];
            $remove = $act === 'item_remove';
            if ($remove || $new['title'] !== '') {
                // the checklist in progress
                if ($remove && $cur) array_splice($p['items'], $idx, 1);
                elseif ($cur) $p['items'][$idx] = $new + ['values' => array_map(fn($k) => $cur['values'][$k] ?? '', array_keys($fields)), 'done' => $cur['done'], 'na' => $cur['na'], 'note' => $cur['note']];
                else $p['items'][] = $new + ['values' => array_fill(0, count($fields), ''), 'done' => false, 'na' => false, 'note' => ''];
                ah_chk_change($uid, ['op' => 'protocol_save', 'aircraftId' => $aid, 'protocol' => $p]);
                // the aircraft's own list of the task (all items)
                $all = (array)($d['task']['items'] ?? []);
                $pos = -1;
                if ($cur) foreach ($all as $k => $it) if (ah_chk_full(ah_chk_from_item($it)) === ah_chk_full($cur)) { $pos = $k; break; }
                if ($remove && $pos >= 0) array_splice($all, $pos, 1);
                elseif (!$remove && $pos >= 0) $all[$pos] = ah_chk_to_item($new);
                elseif (!$remove) $all[] = ah_chk_to_item($new);
                ah_chk_change($uid, ['op' => 'items_edit', 'aircraftId' => $aid, 'taskId' => $tid, 'items' => array_values($all)]);
                if (!empty($_POST['tobase'])) {
                    $u = wp_get_current_user();
                    ah_chk_suggest(['typeId' => (string)$a['typeId'], 'engineId' => (string)(ah_mx_engine($a, $cat['types'][$a['typeId']] ?? null, $cat['engines'])['id'] ?? ''),
                        'taskId' => $tid, 'original' => $cur ? ah_chk_full($cur) : '', 'item' => $remove ? null : ah_chk_to_item($new),
                        'comment' => ah_clean_str(wp_unslash((string)($_POST['comment'] ?? '')), 600), 'from' => $u->display_name . ' (' . $u->user_login . ')', 'via' => 'www']);
                    $msg = 'Zapisano dla ' . ($a['registration'] ?? 'samolotu') . ' i wysłano poprawkę do administratora.';
                } else $msg = 'Zapisano dla ' . ($a['registration'] ?? 'samolotu') . '.';
            }
        }
        // read the account again after the change
        $s = ah_state_get($uid);
        foreach ((array)($s['aircraft'] ?? []) as $x) if (($x['id'] ?? '') === $aid) $a = $x;
        $cat = ah_mx_catalog($s);
    }

    $h = '';
    if (count($list) > 1) {
        $h .= '<nav class="ahd-ac">';
        foreach ($list as $x) $h .= '<a class="' . (($x['id'] ?? '') === $aid ? 'on' : '') . '" href="' . esc_url(add_query_arg(['ah' => 'check', 'ac' => $x['id'] ?? ''], $page)) . '">' . esc_html($x['registration'] ?? '?') . '</a>';
        $h .= '</nav>';
    }
    if ($msg) $h .= '<p class="ok"><b>' . esc_html($msg) . '</b>' . (!empty($GLOBALS['ah_chk_finished']) ? ' <a class="cna-btn" target="_blank" href="' . esc_url(add_query_arg(['ah_protocol' => $GLOBALS['ah_chk_finished']], $page)) . '">Protokół – drukuj / PDF</a>' : '') . '</p>';
    $due = ah_mx_compute($a, $s, $cat);
    $d = null; foreach ($due as $x) if ($x['task']['id'] === $tid) $d = $x;

    // ---- one checklist
    if ($d && empty($GLOBALS['ah_chk_finished'])) {
        $p = ah_chk_draft($s, $aid, $tid) ?? ah_chk_new($a, $d);
        $done = count(array_filter($p['items'], fn($x) => !empty($x['done']) || !empty($x['na'])));
        $tot = count($p['items']);
        $own = isset(((array)($a['itemEdits'] ?? []))[$tid]);
        $h .= '<div class="ahc-head"><a href="' . $url([]) . '">← przeglądy</a><b>' . esc_html($p['taskTitle']) . '</b><small>' . esc_html(implode(' · ', array_filter([$a['registration'] ?? '', $cat['types'][$a['typeId']]['name'] ?? '', $p['dueAtHours'] !== null ? 'przy ' . ah_fmt_h((float)$p['dueAtHours']) . ' h' : '', $own ? 'własna lista' : '']))) . '</small>'
            . '<div class="ahc-prog"><span>' . $done . ' z ' . $tot . ' wykonanych</span><i style="width:' . ($tot ? round(100 * $done / $tot) : 0) . '%"></i></div></div>';
        $editIdx = isset($_GET['edit']) ? (int)$_GET['edit'] : null;
        $h .= '<form method="post" class="ahc">' . $nonce;
        $sec = null;
        foreach ($p['items'] as $i => $it) {
            if ($it['section'] !== $sec) {
                $sec = $it['section'];
                $in = array_filter($p['items'], fn($x) => $x['section'] === $sec);
                $h .= '<div class="ahc-sec"><span>' . esc_html(mb_strtoupper($sec !== '' ? $sec : $p['taskTitle'])) . '</span><span>' . count(array_filter($in, fn($x) => !empty($x['done']) || !empty($x['na']))) . '/' . count($in) . '</span></div>';
            }
            $tags = '';
            if (!empty($it['everyHours'])) $tags .= '<span class="ahc-tag">CO ' . esc_html(ah_fmt_h((float)$it['everyHours'])) . ' h</span>';
            if (empty($it['verified'])) $tags .= '<span class="ahc-tag un">NIEZWERYFIKOWANE</span>';
            $vals = '';
            foreach ((array)$it['fields'] as $k => $f) $vals .= '<label class="ahc-val"><small>' . esc_html($f . ($it['unit'] !== '' ? ' [' . $it['unit'] . ']' : '')) . '</small><input type="text" name="val[' . $i . '][' . $k . ']" value="' . esc_attr($it['values'][$k] ?? '') . '"' . ($edit ? '' : ' disabled') . '></label>';
            $h .= '<div class="ahc-it' . (!empty($it['done']) ? ' done' : '') . '"><label class="ahc-cb"><input type="checkbox" name="done[' . $i . ']" value="1"' . (!empty($it['done']) ? ' checked' : '') . ($edit ? '' : ' disabled') . '></label>'
                . '<div class="ahc-tx"><span class="t">' . esc_html($it['title']) . '</span>' . ($tags ? '<div>' . $tags . '</div>' : '') . ($vals ? '<div class="ahc-vals">' . $vals . '</div>' : '')
                . '<div class="ahc-row2"><input type="text" class="ahc-note" name="note[' . $i . ']" placeholder="uwaga" value="' . esc_attr($it['note']) . '"' . ($edit ? '' : ' disabled') . '>'
                . '<label class="ahc-na"><input type="checkbox" name="na[' . $i . ']" value="1"' . (!empty($it['na']) ? ' checked' : '') . ($edit ? '' : ' disabled') . '> nie dotyczy</label></div></div>'
                . ($edit ? '<a class="ahc-ed" title="Edytuj pozycję" href="' . $url(['task' => $tid, 'edit' => $i]) . '#ahc-edit">✎</a>' : '') . '</div>';
        }
        if (!$p['items']) $h .= '<p>Brak pozycji – dodaj pierwszą.</p>';
        if ($p['source'] !== '') $h .= '<p class="muted">Źródło: ' . esc_html($p['source']) . '</p>';
        if ($edit) {
            $h .= '<div class="ahc-bar"><button class="cna-btn" name="ahchk" value="save">Zapisz</button> <a href="' . $url(['task' => $tid, 'edit' => -1]) . '#ahc-edit">+ Dodaj pozycję</a>'
                . ($own ? ' · <button class="ahc-link" name="ahchk" value="reset" onclick="return confirm(\'Przywrócić listę z bazy dla tego przeglądu?\')">Przywróć listę z bazy</button>' : '')
                . ' · <button class="ahc-link" name="ahchk" value="discard" onclick="return confirm(\'Odrzucić tę checklistę?\')">Odrzuć</button></div>';
            $h .= '<details class="ahp-card ahc-fin"><summary><b>Zakończ przegląd</b></summary>'
                . '<div class="ahc-grid"><label>Data<input type="date" name="date" value="' . esc_attr(wp_date('Y-m-d')) . '"></label><label>Nalot (h)<input type="text" name="hours" value="' . esc_attr(ah_fmt_h((float)($a['hours'] ?? 0))) . '"></label>'
                . '<label>Miejsce (lotnisko)<input type="text" name="place" value="' . esc_attr($p['place'] ?? '') . '"></label><label>Mechanik<input type="text" name="mechanic" value="' . esc_attr($p['mechanic'] ?? wp_get_current_user()->display_name) . '"></label>'
                . '<label>Nr uprawnień<input type="text" name="licence" value="' . esc_attr($p['licence'] ?? '') . '"></label></div>'
                . '<label>Uwagi<textarea name="notes" rows="3">' . esc_textarea($p['notes'] ?? '') . '</textarea></label>'
                . '<p class="muted">Nieodhaczone pozycje będą w protokole jako niewykonane. Przegląd zapisze się w historii samolotu, a terminy policzą się od niego.</p>'
                . '<button class="cna-btn" name="ahchk" value="finish">Zakończ i utwórz protokół</button></details>';
        }
        $h .= '</form>';
        // item editor
        if ($edit && $editIdx !== null) {
            $cur = $editIdx >= 0 ? ($p['items'][$editIdx] ?? null) : null;
            $h .= '<form method="post" id="ahc-edit" class="ahp-card ahc-editf">' . $nonce . '<input type="hidden" name="idx" value="' . ($cur ? $editIdx : -1) . '">'
                . '<h3>' . ($cur ? 'Edytuj pozycję' : 'Dodaj pozycję') . '</h3>';
            // keep ticks / values typed in the list above
            foreach ($p['items'] as $i => $it) {
                if (!empty($it['done'])) $h .= '<input type="hidden" name="done[' . $i . ']" value="1">';
                if (!empty($it['na'])) $h .= '<input type="hidden" name="na[' . $i . ']" value="1">';
                $h .= '<input type="hidden" name="note[' . $i . ']" value="' . esc_attr($it['note']) . '">';
                foreach ((array)$it['values'] as $k => $v) $h .= '<input type="hidden" name="val[' . $i . '][' . $k . ']" value="' . esc_attr($v) . '">';
            }
            $h .= '<label>Treść<input type="text" name="title" value="' . esc_attr($cur['title'] ?? '') . '" required></label>'
                . (!$cur ? '<label>Grupa (np. Płatowiec, Silnik)<input type="text" name="section" value="' . esc_attr($p['items'] ? (string)($p['items'][count($p['items']) - 1]['section'] ?? '') : '') . '"></label>' : '')
                . '<div class="ahc-grid"><label>Co ile godzin (puste = przy każdym przeglądzie)<input type="text" name="every" value="' . esc_attr(!empty($cur['everyHours']) ? ah_fmt_h((float)$cur['everyHours']) : '') . '"></label>'
                . '<label>Wartości do wpisania (po przecinku)<input type="text" name="fields" value="' . esc_attr(implode(', ', (array)($cur['fields'] ?? []))) . '" placeholder="np. Cyl 1, Cyl 2, Cyl 3, Cyl 4"></label>'
                . '<label>Jednostka<input type="text" name="unit" value="' . esc_attr($cur['unit'] ?? '') . '" placeholder="np. N, rpm, bar"></label></div>'
                . '<label class="ahc-radio"><input type="radio" name="tobase" value="" checked> <b>Tylko dla ' . esc_html($a['registration'] ?? 'tego samolotu') . '</b> – Twoja wersja listy dla tego samolotu</label>'
                . '<label class="ahc-radio"><input type="radio" name="tobase" value="1"> <b>Zgłoś poprawkę do bazy</b> – zapisane też tutaj; administrator sprawdzi i poprawi listę dla wszystkich</label>'
                . '<label>Komentarz dla administratora (dokument, strona)<input type="text" name="comment"></label>'
                . '<p><button class="cna-btn" name="ahchk" value="item_save">Zapisz</button> <a href="' . $url(['task' => $tid]) . '">Anuluj</a>'
                . ($cur ? ' · <button class="ahc-link bad" name="ahchk" value="item_remove" onclick="return confirm(\'Usunąć pozycję?\')">Usuń pozycję</button>' : '') . '</p></form>';
        }
        return $h;
    }

    // ---- list of inspections of the aircraft + protocols
    $h .= '<div class="ahp-card"><h3>Checklisty przeglądów – ' . esc_html($a['registration'] ?? '') . '</h3><p class="muted">Lista prac wypadających przy najbliższym wykonaniu każdego przeglądu. Odhaczaj prace, wpisuj wartości i uwagi; checklista zapisuje się na koncie i jest widoczna też w aplikacji.</p>';
    foreach ($due as $x) {
        if (empty($x['task']['items']) && !ah_chk_draft($s, $aid, $x['task']['id'])) continue;
        if ($x['status'] === 'DONE') continue;
        [$label, $col] = ah_status_look($x['status']);
        $w = [];
        if ($x['remH'] !== null) $w[] = $x['remH'] >= 0 ? 'za ' . ah_fmt_h($x['remH']) . ' h' : ah_fmt_h(-$x['remH']) . ' h po terminie';
        if ($x['remD'] !== null) $w[] = $x['remD'] >= 0 ? 'za ' . $x['remD'] . ' dni' : (-$x['remD']) . ' dni po terminie';
        $dr = ah_chk_draft($s, $aid, $x['task']['id']);
        $n = count(ah_mx_items($x['task'], $x['dueHours']));
        $h .= '<div class="ahd-row"><span class="ahd-dot" style="background:' . $col . '"></span><div><b>' . esc_html(ah_l10n($x['task']['title'])) . '</b><small>' . esc_html(implode(' · ', array_filter([$x['dueHours'] !== null ? 'przy ' . ah_fmt_h((float)$x['dueHours']) . ' h' : '', implode(' · ', $w), $n . ' pozycji']))) . '</small></div>'
            . '<a class="cna-btn" href="' . $url(['task' => $x['task']['id']]) . '">' . ($dr ? 'Kontynuuj' : ($edit ? 'Otwórz checklistę' : 'Pokaż')) . '</a></div>';
    }
    $h .= '</div>';
    $protos = array_values(array_filter((array)($s['protocols'] ?? []), fn($x) => ($x['aircraftId'] ?? '') === $aid && !empty($x['finished'])));
    usort($protos, fn($x, $y) => ($y['epochDay'] ?? 0) <=> ($x['epochDay'] ?? 0));
    $h .= '<div class="ahp-card"><h3>Protokoły przeglądów</h3>';
    if (!$protos) $h .= '<p class="muted">Jeszcze brak zakończonych checklist.</p>';
    foreach ($protos as $x) {
        $ok = count(array_filter((array)$x['items'], fn($i) => !empty($i['done']) || !empty($i['na'])));
        $h .= '<div class="ahd-row"><div><b>' . esc_html($x['taskTitle']) . '</b><small>' . esc_html(implode(' · ', array_filter([ah_day_str((int)$x['epochDay']), ah_fmt_h((float)$x['hours']) . ' h', $x['mechanic'] ?? '', $ok . '/' . count((array)$x['items'])]))) . '</small></div>'
            . '<a class="cna-btn" target="_blank" href="' . esc_url(add_query_arg(['ah_protocol' => $x['id']], $page)) . '">Protokół – drukuj / PDF</a></div>';
    }
    return $h . '</div>';
}

function ah_checklist_css(): string
{
    return '<style>
.ahc-head{background:#0B2A5B;color:#fff;border-radius:16px;padding:12px 16px;margin-bottom:10px}.ahc-head a{color:#cfe0ff;font-size:.85em;display:block}.ahc-head b{display:block;font-size:1.15em}.ahc-head small{opacity:.85}
.ahc-prog{margin-top:8px;font-size:.85em}.ahc-prog i{display:block;height:7px;background:#6FA8F0;border-radius:6px;margin-top:4px;box-shadow:0 0 0 999px rgba(255,255,255,.0)}
.ahc-sec{display:flex;justify-content:space-between;color:#1C5DB8;font-weight:800;font-size:.8em;letter-spacing:.4px;margin:14px 4px 6px}
.ahc-it{display:flex;gap:10px;align-items:flex-start;background:#fff;border:1px solid #E3E7ED;border-radius:12px;padding:10px 12px;margin-bottom:7px}
.ahc-it.done .t{color:#55606F}.ahc-cb input{width:20px;height:20px;accent-color:#0B2A5B;margin-top:2px}.ahc-tx{flex:1}.ahc-tx .t{display:block}
.ahc-tag{display:inline-block;font-size:.7em;font-weight:800;border-radius:6px;padding:1px 7px;margin:4px 4px 0 0;background:#E6EEF8;color:#0B2A5B}.ahc-tag.un{background:#FFF1CF;color:#8a6100}
.ahc-vals{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}.ahc-val{display:flex;flex-direction:column;font-weight:400!important;margin:0!important}.ahc-val small{color:#55606F}.ahc-val input{width:96px!important;padding:5px 7px!important}
.ahc-row2{display:flex;flex-wrap:wrap;gap:6px 12px;align-items:center;margin-top:6px}.ahc-note{flex:1;padding:5px 8px!important;font-size:.9em}.ahc-na{display:inline-flex!important;gap:5px;align-items:center;font-weight:400!important;font-size:.85em;white-space:nowrap;margin:0!important}
.ahc-ed{text-decoration:none;color:#55606F;font-size:1.2em}.ahc-bar{margin:12px 0}.ahc-link{background:none;border:0;color:#1C5DB8;cursor:pointer;padding:0;font:inherit;text-decoration:underline}.ahc-link.bad{color:#C62828}
.ahc-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:4px 12px}.ahc-fin summary{cursor:pointer;color:#0B2A5B}.ahc-fin label input[type=text],.ahc-fin label input[type=date],.ahc-fin label textarea,.ahc-editf input[type=text],.ahc-editf textarea{width:100%;box-sizing:border-box}.ahc input[type=checkbox],.ahc-editf input[type=radio]{width:auto!important;display:inline-block}
.ahc-radio{font-weight:400!important;display:block;margin:8px 0!important}.ahc-editf{border:2px solid #1C5DB8}
</style>';
}

// ---------------------------------------------------------------- printable protocol

add_action('template_redirect', function () {
    if (empty($_GET['ah_protocol']) || !is_user_logged_in()) return;
    $uid = get_current_user_id();
    $id = sanitize_text_field((string)$_GET['ah_protocol']);
    $s = ah_state_get($uid);
    $p = null; foreach ((array)($s['protocols'] ?? []) as $x) if (($x['id'] ?? '') === $id) $p = $x;
    if (!$p) wp_die('Nie znaleziono protokołu na tym koncie.');
    $a = null; foreach ((array)($s['aircraft'] ?? []) as $x) if (($x['id'] ?? '') === $p['aircraftId']) $a = $x;
    $a = $a ?: ['registration' => '?', 'typeId' => ''];
    $cat = ah_mx_catalog($s);
    $type = $cat['types'][$a['typeId']] ?? null;
    $eng = ah_mx_engine($a, $type, $cat['engines']);
    $open = array_filter(ah_open_bulletins($a, $s, $cat), fn($b) => ($b['kind'] ?? '') === 'AD' || !empty($b['mandatory']));
    nocache_headers();
    header('Content-Type: text/html; charset=utf-8');
    $rows = '';
    foreach ((array)$p['items'] as $i => $it) {
        $vals = implode(' / ', array_filter((array)($it['values'] ?? []), fn($v) => $v !== ''));
        $res = implode(' · ', array_filter([$vals !== '' ? $vals . ($it['unit'] !== '' ? ' ' . $it['unit'] : '') : '', (string)($it['note'] ?? ''), !empty($it['na']) ? 'nie dotyczy' : '']));
        $mark = !empty($it['done']) ? '<b class="ok">✓</b>' : (!empty($it['na']) ? 'n/d' : '<b class="no">–</b>');
        $rows .= '<tr><td>' . ($i + 1) . '</td><td>' . esc_html(ah_chk_full($it)) . '</td><td>' . esc_html($res) . '</td><td class="c">' . $mark . '</td></tr>';
    }
    $nd = count(array_filter((array)$p['items'], fn($x) => empty($x['done']) && empty($x['na'])));
    echo '<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Protokół ' . esc_html($a['registration']) . '</title><style>
body{font-family:Lato,Arial,sans-serif;color:#1b2430;background:#E4E8EF;margin:0}.pg{background:#fff;max-width:800px;margin:20px auto;padding:34px 40px;box-shadow:0 4px 20px rgba(0,0,0,.15)}
.hd{display:flex;justify-content:space-between;border-bottom:3px solid #0B2A5B;padding-bottom:10px}.hd h1{margin:0;color:#0B2A5B;font-size:24px}.hd .r{text-align:right}.hd .r b{font-size:18px;color:#0B2A5B}
.info{display:flex;gap:30px;margin:12px 0;font-weight:700}table{width:100%;border-collapse:collapse;font-size:13px}th{background:#E6EEF8;color:#0B2A5B;text-align:left}td,th{padding:5px 6px;border-bottom:1px solid #E3E7ED;vertical-align:top}.c{text-align:center;width:40px}
.ok{color:#2E7D32}.no{color:#B26A00}.sig{display:flex;gap:30px;margin-top:50px}.sig div{flex:1;border-top:1px solid #333;padding-top:4px;color:#55606F}.disc{color:#55606F;font-size:11px;margin-top:18px}
.bl{font-size:12px;margin:0 0 0 18px;padding:0}.bt{max-width:800px;margin:16px auto 0;text-align:right}.bt button{background:#0B2A5B;color:#fff;border:0;border-radius:999px;padding:10px 20px;font-weight:700;cursor:pointer}
@media print{body{background:#fff}.pg{box-shadow:none;margin:0;max-width:none;padding:0}.bt{display:none}}
</style></head><body><div class="bt"><button onclick="window.print()">Drukuj / zapisz jako PDF</button></div><div class="pg">'
        . '<div class="hd"><div><h1>Protokół przeglądu</h1>' . esc_html($p['taskTitle']) . ($p['dueAtHours'] !== null ? ' (przy ' . esc_html(ah_fmt_h((float)$p['dueAtHours'])) . ' h)' : '') . '</div>'
        . '<div class="r"><b>' . esc_html($a['registration']) . '</b><br>' . esc_html(implode(' · ', array_filter([$type['name'] ?? '', $eng['name'] ?? '']))) . (!empty($a['serial']) ? '<br>Nr seryjny ' . esc_html($a['serial']) : '') . (!empty($a['engineSerial']) ? '<br>S/N silnika ' . esc_html($a['engineSerial']) : '') . '</div></div>'
        . '<div class="info"><span>Data ' . esc_html(ah_day_str((int)$p['epochDay'])) . '</span><span>Nalot ' . esc_html(ah_fmt_h((float)$p['hours'])) . ' h</span>' . (($p['place'] ?? '') !== '' ? '<span>Miejsce ' . esc_html($p['place']) . '</span>' : '') . '</div>'
        . '<table><tr><th>#</th><th>Czynność</th><th>Wynik / uwagi</th><th class="c"></th></tr>' . $rows . '</table>'
        . ($nd ? '<p class="no"><b>Niewykonane: ' . $nd . '</b></p>' : '')
        . '<p><b>Otwarte obowiązkowe biuletyny:</b> ' . ($open ? count($open) : '–') . '</p>' . ($open ? '<ul class="bl">' . implode('', array_map(fn($b) => '<li>' . esc_html(($b['date'] ?? '') . ' – ' . ($b['title'] ?? $b['id'])) . '</li>', array_slice($open, 0, 12))) . '</ul>' : '')
        . (($p['notes'] ?? '') !== '' ? '<p><b>Uwagi:</b> ' . nl2br(esc_html($p['notes'])) . '</p>' : '')
        . '<div class="sig"><div>Mechanik: ' . esc_html($p['mechanic'] ?? '') . (($p['licence'] ?? '') !== '' ? '<br>Nr uprawnień: ' . esc_html($p['licence']) : '') . '</div><div>Podpis</div></div>'
        . '<p class="disc">Lista prac jest pomocą opracowaną na podstawie dokumentów producenta (' . esc_html(($p['source'] ?? '') ?: '–') . '). Obowiązuje aktualna instrukcja obsługi technicznej producenta; za wykonanie prac odpowiada mechanik. Wygenerowano w AeroHangar.</p>'
        . '</div></body></html>';
    exit;
});
