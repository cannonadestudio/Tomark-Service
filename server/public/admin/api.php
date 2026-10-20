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
        $path = ah_data_path($f);
        $raw = is_file($path) ? file_get_contents($path) : null;
        out(200, ['data' => $raw === null ? null : json_decode($raw, true), 'rev' => $raw === null ? '' : sha1($raw)]);

    case 'save':
        $u = require_login();
        require_csrf();
        if ($u['role'] === 'viewer') out(403, ['error' => 'read-only account']);
        $f = (string)($body['f'] ?? '');
        if (!in_array($f, AH_FILES, true)) out(400, ['error' => 'unknown file']);
        $err = ah_validate($f, $body['data'] ?? null);
        if ($err) out(422, ['error' => $err]);
        $path = ah_data_path($f);
        $current = is_file($path) ? file_get_contents($path) : '';
        if (($body['rev'] ?? '') !== ($current === '' ? '' : sha1($current))) {
            out(409, ['error' => 'the file was changed by someone else – reload']);
        }
        $json = json_encode($body['data'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
        ah_backup($f, $current);
        ah_write_atomic($path, $json);
        ah_log($u['login'], 'save ' . $f . ': ' . mb_substr((string)($body['message'] ?? ''), 0, 200));
        out(200, ['rev' => sha1($json)]);

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

    default:
        out(404, ['error' => 'unknown action']);
}
