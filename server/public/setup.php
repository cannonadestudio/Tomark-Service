<?php
/** First-run setup: creates the admin account and copies the initial data. Disabled once configured. */
declare(strict_types=1);
define('AH_DATA', __DIR__ . '/data');
require __DIR__ . '/locate_private.php';

$msg = '';
$checks = [
    'PHP ≥ 8.1' => version_compare(PHP_VERSION, '8.1', '>='),
    'Folder data/ zapisywalny' => is_writable(AH_DATA),
    'Folder private/state zapisywalny' => is_writable(AH_STATE),
    'HTTPS' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https'),
];
$scheme = $checks['HTTPS'] ? 'https' : 'http';
$guess = $scheme . '://' . ($_SERVER['HTTP_HOST'] ?? 'example.com') . rtrim(dirname($_SERVER['SCRIPT_NAME'] ?? '/'), '/') . '/data';

if (ah_configured()) {
    http_response_code(403);
    $msg = 'System jest już skonfigurowany. Ten plik możesz usunąć z serwera.';
} elseif ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $login = trim((string)($_POST['login'] ?? ''));
    $p1 = (string)($_POST['p1'] ?? '');
    $p2 = (string)($_POST['p2'] ?? '');
    $base = rtrim(trim((string)($_POST['base'] ?? '')), '/');
    if (!preg_match('/^[A-Za-z0-9._@-]{3,40}$/', $login)) $msg = 'Login: 3–40 znaków (litery, cyfry, . _ @ -).';
    elseif (strlen($p1) < 10) $msg = 'Hasło musi mieć co najmniej 10 znaków.';
    elseif ($p1 !== $p2) $msg = 'Hasła się różnią.';
    elseif (!str_starts_with($base, 'https://')) $msg = 'Adres danych musi zaczynać się od https:// (aplikacje nie pobierają danych bez HTTPS).';
    else {
        ah_save_config([
            'users' => [['login' => $login, 'role' => 'admin', 'hash' => password_hash($p1, PASSWORD_DEFAULT)]],
            'publicBase' => $base,
            'cronKey' => bin2hex(random_bytes(16)),
        ]);
        foreach (AH_FILES as $f) {
            $dst = AH_DATA . "/$f.json";
            $src = __DIR__ . "/initial-data/$f.json";
            if (!is_file($dst) && is_file($src)) copy($src, $dst);
        }
        header('Location: admin/');
        exit;
    }
}
?><!doctype html>
<html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>AeroHangar – instalacja</title><link rel="icon" href="admin/favicon.ico" sizes="any"><link rel="apple-touch-icon" href="admin/apple-touch-icon.png">
<style>
body{margin:0;font-family:system-ui,sans-serif;background:linear-gradient(135deg,#7A3E9D,#2E8FC4 55%,#0FB3A6);min-height:100vh;color:#2B2A33}
.card{max-width:520px;margin:40px auto;background:#fff;border-radius:14px;padding:22px;box-shadow:0 6px 20px rgba(0,0,0,.15)}
label{display:block;margin:10px 0 4px;font-size:13px;color:#6b6676}
input{width:100%;box-sizing:border-box;padding:9px;border:1px solid #e7e2ee;border-radius:8px;font:inherit}
button{margin-top:16px;padding:10px 18px;border:0;border-radius:8px;background:#7A3E9D;color:#fff;font:inherit;cursor:pointer}
.err{background:#fde8ef;border-left:4px solid #E84F8C;padding:8px 12px;border-radius:6px}
li.ok::marker{content:"✔ "} li.bad::marker{content:"✖ ";color:#c62828}
</style></head><body><div class="card">
<h2>AeroHangar – instalacja</h2>
<ul><?php foreach ($checks as $k => $v): ?><li class="<?= $v ? 'ok' : 'bad' ?>"><?= htmlspecialchars($k) ?></li><?php endforeach ?></ul>
<?php if ($msg): ?><p class="err"><?= htmlspecialchars($msg) ?></p><?php endif ?>
<?php if (!ah_configured()): ?>
<form method="post">
  <label>Login administratora</label><input name="login" required autocomplete="username">
  <label>Hasło (min. 10 znaków)</label><input name="p1" type="password" required autocomplete="new-password">
  <label>Powtórz hasło</label><input name="p2" type="password" required autocomplete="new-password">
  <label>Publiczny adres danych (tu aplikacje będą pobierać pliki)</label><input name="base" value="<?= htmlspecialchars($guess) ?>">
  <button>Utwórz konto i zainstaluj</button>
</form>
<?php endif ?>
</div></body></html>
