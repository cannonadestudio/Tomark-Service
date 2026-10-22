<?php
/**
 * AeroHangar – management API.
 * Public data lives in ../data/*.json (read directly by the apps).
 * This endpoint is for the admin panel only: login, read, save (with backups), restore.
 */
declare(strict_types=1);

define('AH_DATA', __DIR__ . '/../data');
require __DIR__ . '/../locate_private.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

$action = $_GET['a'] ?? '';
$body = json_decode(file_get_contents('php://input') ?: 'null', true);

function out(int $code, array $data): never
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function require_login(): array
{
    $u = $_SESSION['user'] ?? null;
    if (!$u) out(401, ['error' => 'login required']);
    return $u;
}

function require_csrf(): void
{
    $t = $_SERVER['HTTP_X_CSRF'] ?? '';
    if (!hash_equals($_SESSION['csrf'] ?? '', $t)) out(403, ['error' => 'bad csrf token – reload the panel']);
}

if (!ah_configured()) out(503, ['error' => 'not configured – open setup.php']);

switch ($action) {
    case 'me':
        $u = $_SESSION['user'] ?? null;
        out(200, ['user' => $u, 'csrf' => $u ? $_SESSION['csrf'] : null, 'files' => AH_FILES, 'publicBase' => ah_public_base()]);

    case 'login':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (ah_login_blocked()) out(429, ['error' => 'too many attempts – wait 15 minutes']);
        $login = trim((string)($body['login'] ?? ''));
        $pass = (string)($body['password'] ?? '');
        $user = ah_find_user($login);
        if (!$user || !password_verify($pass, $user['hash'])) {
            ah_login_failed();
            out(401, ['error' => 'wrong login or password']);
        }
        session_regenerate_id(true);
        $_SESSION['user'] = ['login' => $user['login'], 'role' => $user['role']];
        $_SESSION['csrf'] = bin2hex(random_bytes(24));
        ah_log($user['login'], 'login');
        out(200, ['user' => $_SESSION['user'], 'csrf' => $_SESSION['csrf']]);

    case 'logout':
        $_SESSION = [];
        session_destroy();
        out(200, ['ok' => true]);

    case 'get':
        require_login();
        $f = (string)($_GET['f'] ?? '');
        if (!in_array($f, AH_FILES, true)) out(400, ['error' => 'unknown file']);
        $raw = ah_read_editable($f);
        out(200, ['data' => $raw === '' ? null : json_decode($raw, true), 'rev' => $raw === '' ? '' : sha1($raw)]);

    case 'save':
        $u = require_login();
        require_csrf();
        if ($u['role'] === 'viewer') out(403, ['error' => 'read-only account']);
        $f = (string)($body['f'] ?? '');
        if (!in_array($f, AH_FILES, true)) out(400, ['error' => 'unknown file']);
        $err = ah_validate($f, $body['data'] ?? null);
        if ($err) out(422, ['error' => $err]);
        $current = ah_read_editable($f);
        if (($body['rev'] ?? '') !== ($current === '' ? '' : sha1($current))) {
            out(409, ['error' => 'the file was changed by someone else – reload']);
        }
        ah_backup($f, $current);
        $rev = ah_write_editable($f, $body['data']);
        ah_log($u['login'], 'save ' . $f . ': ' . mb_substr((string)($body['message'] ?? ''), 0, 200));
        out(200, ['rev' => $rev]);

    case 'backups':
        require_login();
        out(200, ['backups' => ah_list_backups()]);

    case 'restore':
        $u = require_login();
        require_csrf();
        if ($u['role'] !== 'admin') out(403, ['error' => 'admin only']);
        $name = basename((string)($body['name'] ?? ''));
        $ok = ah_restore($name, $u['login']);
        if (!$ok) out(404, ['error' => 'backup not found']);
        out(200, ['ok' => true]);

    case 'log':
        $u = require_login();
        out(200, ['log' => ah_read_log(200)]);

    case 'users':
        $u = require_login();
        if ($u['role'] !== 'admin') out(403, ['error' => 'admin only']);
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            require_csrf();
            $err = ah_save_user((string)($body['login'] ?? ''), (string)($body['password'] ?? ''), (string)($body['role'] ?? 'editor'), (bool)($body['delete'] ?? false), $u['login']);
            if ($err) out(422, ['error' => $err]);
        }
        out(200, ['users' => ah_list_users()]);

    case 'import':
        // Bulletins collected by the GitHub bot (EASA ADs, manufacturers' pages). Adds only unknown ids.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!hash_equals((string)(ah_config()['cronKey'] ?? 'x'), (string)($_GET['key'] ?? ''))) out(403, ['error' => 'bad key']);
        $incoming = $body['bulletins'] ?? null;
        if (!is_array($incoming)) out(400, ['error' => 'no bulletins']);
        $path = ah_data_path('bulletins');
        $raw = is_file($path) ? (string)file_get_contents($path) : '';
        $feed = $raw ? json_decode($raw, true) : ['version' => 0, 'updated' => '', 'bulletins' => []];
        $have = array_flip(array_column($feed['bulletins'], 'id'));
        $added = 0;
        foreach ($incoming as $b) {
            if (!is_array($b) || empty($b['id']) || isset($have[$b['id']])) continue;
            if (empty($b['title']) || empty($b['groups']) || empty($b['kind']) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)($b['date'] ?? ''))) continue;
            $feed['bulletins'][] = $b;
            $have[$b['id']] = 1;
            $added++;
        }
        if ($added) {
            usort($feed['bulletins'], fn($a, $b) => strcmp($b['date'], $a['date']));
            $feed['version'] = ($feed['version'] ?? 0) + 1;
            $feed['updated'] = date('Y-m-d');
            ah_backup('bulletins', $raw);
            ah_write_atomic($path, json_encode($feed, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n");
            ah_log('bot', "import: $added new bulletins");
        }
        out(200, ['added' => $added]);

    case 'import_contacts':
        // Official lists collected by the GitHub bot (ULC examiners, maintenance organisations).
        // Replaces all entries with the given id prefixes; contacts added in the panel stay untouched.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!hash_equals((string)(ah_config()['cronKey'] ?? 'x'), (string)($_GET['key'] ?? ''))) out(403, ['error' => 'bad key']);
        $prefixes = array_values(array_filter((array)($body['prefixes'] ?? []), fn($p) => is_string($p) && preg_match('/^ulc-[a-z-]+$/', $p)));
        $incoming = array_values(array_filter((array)($body['contacts'] ?? []), fn($c) => is_array($c) && !empty($c['id']) && !empty($c['name']) && !empty($c['kind'])
            && array_filter($prefixes, fn($p) => str_starts_with($c['id'], $p))));
        if (!$prefixes || !$incoming) out(400, ['error' => 'nothing to import']);
        $target = in_array($body['file'] ?? 'contacts', ['contacts', 'examiners'], true) ? ($body['file'] ?? 'contacts') : 'contacts';
        out(200, ah_merge_official($target, $prefixes, $incoming, 'bot'));

    case 'pull_github':
        // Panel button: take the newest official ULC lists straight from the GitHub repository.
        $u = require_login();
        require_csrf();
        if ($u['role'] === 'viewer') out(403, ['error' => 'read-only account']);
        $base = rtrim((string)(ah_config()['githubData'] ?? 'https://raw.githubusercontent.com/cannonadestudio/Tomark-Service/main/data'), '/');
        $ctx = stream_context_create(['http' => ['timeout' => 30, 'header' => "User-Agent: AeroHangar server\r\n"]]);
        $res = [];
        foreach ([['examiners', ['ulc-exam-']], ['contacts', ['ulc-pl-']]] as [$f, $prefixes]) {
            $raw = @file_get_contents("$base/$f.json", false, $ctx);
            $d = $raw ? json_decode($raw, true) : null;
            if (!is_array($d['contacts'] ?? null)) { $res[$f] = ['error' => 'download failed']; continue; }
            $inc = array_values(array_filter($d['contacts'], fn($c) => !empty($c['id']) && array_filter($prefixes, fn($p) => str_starts_with($c['id'], $p))));
            $res[$f] = $inc ? ah_merge_official($f, $prefixes, $inc, $u['login']) : ['imported' => 0];
            // Other shared entries (manufacturers, Rotax service, dealers…): add only ones the server has never had.
            // Entries edited here are not overwritten; entries deleted here are not brought back.
            $raw2 = ah_read_editable($f);
            $feed = $raw2 ? json_decode($raw2, true) : ['version' => 0, 'updated' => '', 'contacts' => []];
            $have = array_flip(array_column($feed['contacts'], 'id'));
            $deleted = array_flip($feed['deletedIds'] ?? []);
            $extra = 0;
            foreach ($d['contacts'] as $c) {
                if (empty($c['id']) || empty($c['name']) || str_starts_with($c['id'], 'ulc-') || isset($have[$c['id']]) || isset($deleted[$c['id']])) continue;
                $feed['contacts'][] = $c; $have[$c['id']] = 1; $extra++;
            }
            if ($extra) {
                $feed['version'] = ($feed['version'] ?? 0) + 1;
                $feed['updated'] = date('Y-m-d');
                ah_backup($f, $raw2);
                ah_write_editable($f, $feed);
                ah_log($u['login'], "pull $f: $extra other entries added");
            }
            $res[$f]['added_other'] = $extra;
        }
        // Bulletins (TOMARK, EASA ADs, manufacturers): add the ones the server does not have yet.
        $raw = @file_get_contents("$base/bulletins.json", false, $ctx);
        $d = $raw ? json_decode($raw, true) : null;
        if (is_array($d['bulletins'] ?? null)) {
            $cur = ah_read_editable('bulletins');
            $feed = $cur ? json_decode($cur, true) : ['version' => 0, 'updated' => '', 'bulletins' => []];
            $have = array_flip(array_column($feed['bulletins'], 'id'));
            $added = 0;
            foreach ($d['bulletins'] as $b) {
                if (empty($b['id']) || isset($have[$b['id']]) || empty($b['title']) || empty($b['groups'])) continue;
                $feed['bulletins'][] = $b; $have[$b['id']] = 1; $added++;
            }
            if ($added) {
                usort($feed['bulletins'], fn($a, $b) => strcmp($b['date'], $a['date']));
                $feed['version'] = ($feed['version'] ?? 0) + 1;
                $feed['updated'] = date('Y-m-d');
                ah_backup('bulletins', $cur);
                ah_write_editable('bulletins', $feed);
                ah_log($u['login'], "pull bulletins: $added new");
            }
            $res['bulletins'] = ['new' => $added, 'total' => count($feed['bulletins'])];
        } else {
            $res['bulletins'] = ['error' => 'download failed'];
        }
        // Bulletin groups are maintained on GitHub (e.g. Evektor split per model, Rotax per engine family).
        if (is_array($d['bulletins'] ?? null)) {
            $cur = ah_read_editable('bulletins');
            $feed = $cur ? json_decode($cur, true) : null;
            if ($feed) {
                $gh = []; foreach ($d['bulletins'] as $b) if (!empty($b['id'])) $gh[$b['id']] = $b['groups'] ?? null;
                $changed = 0;
                foreach ($feed['bulletins'] as &$b) if (isset($gh[$b['id']]) && $gh[$b['id']] && $gh[$b['id']] !== ($b['groups'] ?? null)) { $b['groups'] = $gh[$b['id']]; $changed++; }
                unset($b);
                if ($changed) { ah_backup('bulletins', $cur); ah_write_editable('bulletins', $feed); ah_log($u['login'], "pull bulletins: groups updated on $changed"); }
                $res['bulletins']['groupsUpdated'] = $changed;
            }
        }
        // Aircraft types: add new ones (trikes, gyroplanes…); old-format entries get engine / kind / groups
        // and lose the engine tasks that now come from the engine catalogue. Speeds and texts you edited stay.
        $raw = @file_get_contents("$base/types.json", false, $ctx);
        $gt = $raw ? json_decode($raw, true) : null;
        if (is_array($gt) && array_is_list($gt)) {
            $cur = ah_read_editable('types');
            $types = $cur ? json_decode($cur, true) : [];
            $byId = []; foreach ($gt as $t) $byId[$t['id']] = $t;
            $added = 0; $upgraded = 0; $have = [];
            foreach ($types as &$t) {
                $have[$t['id']] = 1;
                $g = $byId[$t['id']] ?? null;
                if ($g && empty($t['engine'])) {
                    $t['engine'] = $g['engine'] ?? 'rotax_912';
                    $t['kind'] = $g['kind'] ?? 'airplane';
                    $t['bulletinGroups'] = $g['bulletinGroups'];
                    $t['tasks'] = array_values(array_filter($t['tasks'], fn($k) => !preg_match('/^(rotax_|prop$|brs_)/', $k['id'])));
                    $upgraded++;
                }
            }
            unset($t);
            foreach ($gt as $t) if (!isset($have[$t['id']])) { $types[] = $t; $added++; }
            if ($added || $upgraded) { ah_backup('types', $cur); ah_write_editable('types', $types); ah_log($u['login'], "pull types: $added new, $upgraded upgraded"); }
            $res['types'] = ['new' => $added, 'upgraded' => $upgraded, 'total' => count($types)];
        } else {
            $res['types'] = ['error' => 'download failed'];
        }
        out(200, $res);

    // ------------------------------------------------------------ public endpoints used by the app

    case 'ping':
        // Anonymous install counter: random id generated on the phone, app version, language, Android version.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!ah_rate_ok('ping', 30, 3600)) out(429, ['error' => 'slow down']);
        $id = (string)($body['id'] ?? '');
        if (!preg_match('/^[0-9a-f-]{36}$/', $id)) out(400, ['error' => 'bad id']);
        $info = ['v' => ah_clean_str($body['v'] ?? '', 20), 'lang' => ah_clean_str($body['lang'] ?? '', 5), 'sdk' => (int)($body['sdk'] ?? 0), 'types' => array_slice(array_map(fn($t) => ah_clean_str($t, 40), (array)($body['types'] ?? [])), 0, 5), 'pro' => !empty($body['pro']), 'ac' => max(0, min(99, (int)($body['ac'] ?? 0)))];
        $granted = ah_store_update('installs', function (&$d) use ($id, $info) {
            if (!isset($d[$id]) && count($d) > 200000) return false;
            $today = date('Y-m-d');
            $d[$id] = ($d[$id] ?? ['first' => $today]) + [];
            $d[$id] = array_merge($d[$id], $info, ['last' => $today]);
            return !empty($d[$id]['proGranted']);
        });
        out(200, ['ok' => true, 'pro' => (bool)$granted]);

    case 'submit':
        // Contact proposed from the app – waits in the panel until the owner accepts it.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!ah_rate_ok('submit', 10, 86400)) out(429, ['error' => 'too many submissions today']);
        $c = (array)($body['contact'] ?? []);
        $kinds = ['manufacturer', 'dealer', 'rotax_distributor', 'rotax_service', 'maintenance', 'mechanic', 'instructor', 'examiner', 'school'];
        $name = ah_clean_str($c['name'] ?? '', 120);
        if ($name === '' || !in_array($c['kind'] ?? '', $kinds, true)) out(400, ['error' => 'name and kind required']);
        $clean = ['name' => $name, 'kind' => $c['kind']];
        foreach (['country' => 2, 'city' => 80, 'address' => 160, 'phone' => 40, 'email' => 120, 'web' => 200, 'note' => 1000] as $k => $max) {
            $v = ah_clean_str($c[$k] ?? '', $max);
            if ($v !== '') $clean[$k] = $v;
        }
        $clean['brands'] = array_slice(array_values(array_filter(array_map(fn($b) => ah_clean_str($b, 60), (array)($c['brands'] ?? [])))), 0, 10);
        foreach (['lat', 'lon'] as $k) if (is_numeric($c[$k] ?? null)) $clean[$k] = (float)$c[$k];
        $sub = ['id' => bin2hex(random_bytes(6)), 'date' => date('c'), 'contact' => $clean,
            'message' => ah_clean_str($body['message'] ?? '', 1000), 'from' => ah_clean_str($body['from'] ?? '', 160),
            'consent' => (bool)($body['consent'] ?? false)];
        $count = ah_store_update('submissions', function (&$d) use ($sub) {
            if (count($d) >= 2000) return -1;
            $d[] = $sub;
            return count($d);
        });
        if ($count < 0) out(503, ['error' => 'queue full']);
        out(200, ['ok' => true]);

    case 'event':
        // Anonymous counters for partners and sponsors (views and taps) – the numbers you show them.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!ah_rate_ok('event', 120, 3600)) out(429, ['error' => 'slow down']);
        $allowed = ['view', 'call', 'email', 'web', 'map', 'request', 'sponsor_view', 'sponsor_click'];
        $evs = array_slice((array)($body['events'] ?? []), 0, 50);
        $month = date('Y-m');
        ah_store_update('events', function (&$d) use ($evs, $allowed, $month) {
            foreach ($evs as $e) {
                $t = (string)($e['t'] ?? '');
                $c = ah_clean_str($e['c'] ?? '', 80);
                if (!in_array($t, $allowed, true) || $c === '') continue;
                if (!isset($d[$month][$c]) && count($d[$month] ?? []) > 5000) continue;
                $d[$month][$c][$t] = ($d[$month][$c][$t] ?? 0) + 1;
            }
            // keep two years
            foreach (array_keys($d) as $m) if ($m < date('Y-m', strtotime('-24 months'))) unset($d[$m]);
        });
        out(200, ['ok' => true]);

    case 'request':
        // "Book an inspection" from the app – forwarded by e-mail to a partner service that accepts requests.
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(405, ['error' => 'POST only']);
        if (!ah_rate_ok('request', 6, 86400)) out(429, ['error' => 'too many requests today']);
        if (empty($body['consent'])) out(400, ['error' => 'consent required']);
        $c = ah_public_contact((string)($body['contactId'] ?? ''));
        if (!$c || empty($c['requests']) || empty($c['email']) || !ah_partner_active($c)) out(404, ['error' => 'service does not take requests']);
        $a = (array)($body['aircraft'] ?? []);
        $r = ['id' => bin2hex(random_bytes(6)), 'date' => date('c'), 'status' => 'new', 'contactId' => $c['id'], 'contactName' => $c['name'],
            'aircraft' => [], 'work' => ah_clean_str($body['work'] ?? '', 300), 'preferredDate' => ah_clean_str($body['preferredDate'] ?? '', 10),
            'name' => ah_clean_str($body['name'] ?? '', 120), 'phone' => ah_clean_str($body['phone'] ?? '', 40),
            'email' => ah_clean_str($body['email'] ?? '', 120), 'message' => ah_clean_str($body['message'] ?? '', 2000)];
        foreach (['registration' => 20, 'type' => 80, 'hours' => 12, 'engineSerial' => 40, 'year' => 4] as $k => $max) {
            $v = ah_clean_str($a[$k] ?? '', $max);
            if ($v !== '') $r['aircraft'][$k] = $v;
        }
        if ($r['name'] === '' || ($r['phone'] === '' && !filter_var($r['email'], FILTER_VALIDATE_EMAIL))) out(400, ['error' => 'name and phone or e-mail required']);
        $ac = $r['aircraft'];
        $txt = "Zapytanie o przegląd z aplikacji AeroHangar\n\n"
            . "Samolot: " . ($ac['registration'] ?? '') . "  " . ($ac['type'] ?? '') . "\n"
            . "Nalot: " . ($ac['hours'] ?? '') . " h" . (!empty($ac['year']) ? "   Rok produkcji: {$ac['year']}" : '') . (!empty($ac['engineSerial']) ? "\nS/N silnika: {$ac['engineSerial']}" : '') . "\n"
            . "Zakres prac: {$r['work']}\n"
            . ($r['preferredDate'] ? "Preferowany termin: {$r['preferredDate']}\n" : '')
            . "\nWłaściciel: {$r['name']}\nTelefon: {$r['phone']}\nE-mail: {$r['email']}\n"
            . ($r['message'] ? "\nWiadomość:\n{$r['message']}\n" : '')
            . "\n--\nWłaściciel wyraził zgodę na przekazanie tych danych Państwa firmie. Odpowiedz bezpośrednio na ten e-mail lub zadzwoń.\n";
        $subject = 'Zapytanie o przegląd: ' . ($ac['registration'] ?? '') . ' – ' . ($r['work'] ?: 'serwis');
        $r['mailed'] = ah_mail($c['email'], $subject, $txt, $r['email']);
        $notify = ah_settings()['notifyEmail'];
        if ($notify) ah_mail($notify, '[AeroHangar] ' . $subject . ' → ' . $c['name'], $txt);
        $n = ah_store_update('requests', function (&$d) use ($r) {
            $d = array_values(array_filter($d, fn($x) => strtotime($x['date']) > time() - 400 * 86400));
            if (count($d) >= 5000) return -1;
            $d[] = $r;
            return count($d);
        });
        if ($n < 0) out(503, ['error' => 'queue full']);
        ah_store_update('events', function (&$d) use ($c) { $m = date('Y-m'); $d[$m][$c['id']]['request'] = ($d[$m][$c['id']]['request'] ?? 0) + 1; });
        out(200, ['ok' => true, 'mailed' => $r['mailed']]);

    // ------------------------------------------------------------ panel: partners, requests, settings

    case 'requests':
        $u = require_login();
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            require_csrf();
            if ($u['role'] === 'viewer') out(403, ['error' => 'read-only account']);
            $rid = (string)($body['id'] ?? '');
            $act = (string)($body['action'] ?? '');
            ah_store_update('requests', function (&$d) use ($rid, $act) {
                foreach ($d as $i => $x) if ($x['id'] === $rid) {
                    if ($act === 'delete') array_splice($d, $i, 1); else $d[$i]['status'] = $act === 'done' ? 'done' : 'new';
                    return;
                }
            });
            ah_log($u['login'], "request $rid: $act");
        }
        out(200, ['requests' => array_reverse(ah_store_read('requests'))]);

    case 'events':
        require_login();
        out(200, ['events' => ah_store_read('events')]);

    case 'installs':
        // Panel: list of installations, manual Pro, counter reset.
        $u = require_login();
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            require_csrf();
            if ($u['role'] !== 'admin') out(403, ['error' => 'admin only']);
            $act = (string)($body['action'] ?? '');
            if ($act === 'reset') {
                // Copy kept in private/state; installs with Pro granted by hand stay (their grant must not vanish).
                $cur = ah_store_read('installs');
                @file_put_contents(AH_STATE . '/installs-before-reset-' . date('Ymd-His') . '.json', json_encode($cur));
                $kept = ah_store_update('installs', function (&$d) {
                    $d = array_filter($d, fn($i) => !empty($i['proGranted']));
                    return count($d);
                });
                ah_log($u['login'], 'installs counter reset (' . count($cur) . ' removed, ' . $kept . ' with manual Pro kept)');
            } elseif ($act === 'grant' || $act === 'note') {
                $id = (string)($body['id'] ?? '');
                $ok = ah_store_update('installs', function (&$d) use ($id, $act, $body) {
                    if (!isset($d[$id])) return false;
                    if ($act === 'grant') $d[$id]['proGranted'] = !empty($body['on']); else $d[$id]['note'] = ah_clean_str($body['note'] ?? '', 200);
                    return true;
                });
                if (!$ok) out(404, ['error' => 'install not found']);
                ah_log($u['login'], "install " . substr($id, 0, 8) . ": " . ($act === 'grant' ? ('manual Pro ' . (!empty($body['on']) ? 'ON' : 'OFF')) : 'note'));
            }
        }
        $list = [];
        foreach (ah_store_read('installs') as $id => $i) $list[] = ['id' => $id, 'code' => strtoupper(substr(str_replace('-', '', $id), 0, 8))] + $i;
        usort($list, fn($a, $b) => strcmp($b['last'] ?? '', $a['last'] ?? ''));
        out(200, ['installs' => $list]);

    case 'settings':
        $u = require_login();
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            require_csrf();
            if ($u['role'] !== 'admin') out(403, ['error' => 'admin only']);
            $new = [];
            foreach (['notifyEmail', 'mailFrom'] as $k) {
                $v = ah_clean_str($body[$k] ?? '', 120);
                if ($v !== '' && !filter_var($v, FILTER_VALIDATE_EMAIL)) out(400, ['error' => "$k: niepoprawny adres e-mail"]);
                $new[$k] = $v;
            }
            // Pilot Logbook: Google Play link or package id -> package id
            if (array_key_exists('logbookPackage', $body ?? [])) {
                $lp = trim((string)$body['logbookPackage']);
                if (preg_match('/[?&]id=([A-Za-z][\w.]+)/', $lp, $m)) $lp = $m[1];
                if ($lp !== '' && !preg_match('/^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$/', $lp)) out(400, ['error' => 'Pilot Logbook: podaj link z Google Play albo identyfikator, np. com.firma.aplikacja']);
                $new['logbookPackage'] = $lp;
            }
            ah_store_update('settings', function (&$d) use ($new) { $d = array_merge($d, $new); });
            // Public part read by the apps (no e-mail addresses there)
            $s = ah_settings();
            ah_write_atomic(ah_data_path('app_config'), ah_json(['logbookPackage' => $s['logbookPackage'] ?: null]));
            ah_log($u['login'], 'settings changed');
        }
        if (($_GET['test'] ?? '') === '1' && $u['role'] === 'admin') {
            $to = ah_settings()['notifyEmail'];
            out(200, ['settings' => ah_settings(), 'test' => $to ? ah_mail($to, 'AeroHangar – test poczty', "Poczta z serwera działa.\n") : false]);
        }
        out(200, ['settings' => ah_settings()]);

    // ------------------------------------------------------------ panel: statistics and submissions

    case 'stats':
        require_login();
        $d = ah_store_read('installs');
        $now = time();
        $active = fn($days) => count(array_filter($d, fn($i) => strtotime($i['last'] ?? '1970-01-01') >= $now - $days * 86400));
        $by = function ($key) use ($d) { $r = []; foreach ($d as $i) { $k = (string)($i[$key] ?? '?'); $r[$k] = ($r[$k] ?? 0) + 1; } arsort($r); return array_slice($r, 0, 15, true); };
        $types = [];
        foreach ($d as $i) foreach (($i['types'] ?? []) as $t) $types[$t] = ($types[$t] ?? 0) + 1;
        arsort($types);
        $months = [];
        foreach ($d as $i) { $m = substr($i['first'] ?? '', 0, 7); if ($m) $months[$m] = ($months[$m] ?? 0) + 1; }
        ksort($months);
        $pro = count(array_filter($d, fn($i) => !empty($i['pro']) || !empty($i['proGranted'])));
        $multi = count(array_filter($d, fn($i) => empty($i['pro']) && ($i['ac'] ?? 0) >= 1 && strtotime($i['last'] ?? '1970-01-01') >= $now - 30 * 86400));
        out(200, ['total' => count($d), 'active7' => $active(7), 'active30' => $active(30), 'pro' => $pro, 'freeActiveWithAircraft' => $multi,
            'versions' => $by('v'), 'langs' => $by('lang'), 'types' => array_slice($types, 0, 15, true), 'newPerMonth' => $months]);

    case 'submissions':
        $u = require_login();
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            require_csrf();
            if ($u['role'] === 'viewer') out(403, ['error' => 'read-only account']);
            $sid = (string)($body['id'] ?? '');
            $act = (string)($body['action'] ?? '');
            $sub = ah_store_update('submissions', function (&$d) use ($sid) {
                foreach ($d as $i => $s) if ($s['id'] === $sid) { array_splice($d, $i, 1); return $s; }
                return null;
            });
            if (!$sub) out(404, ['error' => 'not found']);
            if ($act === 'accept') {
                $c = $sub['contact'];
                $target = $c['kind'] === 'examiner' ? 'examiners' : 'contacts';
                $c['id'] = 'sub-' . $sub['id'];
                $c['publish'] = false;   // published only after the owner has the person's consent
                $c['note'] = trim(($c['note'] ?? '') . ' [zgłoszenie z aplikacji ' . substr($sub['date'], 0, 10) . ']');
                $raw = ah_read_editable($target);
                $feed = $raw ? json_decode($raw, true) : ['version' => 0, 'updated' => '', 'contacts' => []];
                $feed['contacts'][] = $c;
                $feed['version'] = ($feed['version'] ?? 0) + 1;
                $feed['updated'] = date('Y-m-d');
                ah_backup($target, $raw);
                ah_write_editable($target, $feed);
                ah_log($u['login'], "accept submission {$sub['id']} → $target (unpublished): " . $c['name']);
            } else {
                ah_log($u['login'], "reject submission {$sub['id']}: " . ($sub['contact']['name'] ?? ''));
            }
        }
        out(200, ['submissions' => ah_store_read('submissions')]);

    default:
        out(404, ['error' => 'unknown action']);
}
