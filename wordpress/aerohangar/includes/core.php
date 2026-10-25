<?php
/**
 * AeroHangar for WordPress – storage and shared helpers (port of the standalone server's bootstrap.php).
 * Public data (what the apps download): wp-content/uploads/aerohangar/data/*.json
 * Private data (panel working copies, installs, requests, backups, log): database table {prefix}aerohangar_store
 */
defined('ABSPATH') || exit;

const AH_FILES = ['bulletins', 'contacts', 'types', 'news', 'examiners', 'sponsor'];
const AH_KEEP_BACKUPS = 40;
/** Paid-listing fields set in the panel – kept when official lists (ULC) are re-imported. */
const AH_PARTNER_FIELDS = ['partnerUntil', 'promo', 'logo', 'requests'];
const AH_MASTERED = ['contacts', 'examiners'];

function ah_table(): string
{
    global $wpdb;
    return $wpdb->prefix . 'aerohangar_store';
}

function ah_data_dir(): string
{
    return wp_upload_dir(null, false)['basedir'] . '/aerohangar/data';
}

/** Public address of the data folder – the apps' "dataBaseUrl". */
function ah_data_url(): string
{
    return set_url_scheme(wp_upload_dir(null, false)['baseurl'] . '/aerohangar/data', 'https');
}

function ah_data_path(string $f): string
{
    return ah_data_dir() . '/' . $f . '.json';
}

function ah_write_atomic(string $path, string $content): void
{
    $dir = dirname($path);
    if (!is_dir($dir)) wp_mkdir_p($dir);
    $tmp = $path . '.tmp' . wp_rand();
    if (file_put_contents($tmp, $content, LOCK_EX) === false) {
        throw new RuntimeException("cannot write $path – check folder permissions");
    }
    @chmod($tmp, 0644);
    rename($tmp, $path);
}

// ---------------------------------------------------------------- private key/value store (database)

function ah_priv_get(string $name): ?string
{
    global $wpdb;
    $v = $wpdb->get_var($wpdb->prepare('SELECT value FROM ' . ah_table() . ' WHERE name = %s', $name));
    return $v === null ? null : (string)$v;
}

function ah_priv_put(string $name, string $value): void
{
    global $wpdb;
    $wpdb->replace(ah_table(), ['name' => $name, 'value' => $value, 'updated' => current_time('mysql', true)], ['%s', '%s', '%s']);
}

function ah_priv_delete(string $name): void
{
    global $wpdb;
    $wpdb->delete(ah_table(), ['name' => $name], ['%s']);
}

/** Read-modify-write of a JSON store under a database lock (installs, requests, events, rate limits…). */
function ah_store_update(string $name, callable $fn)
{
    global $wpdb;
    $lock = 'ah_' . substr(md5($wpdb->prefix . $name), 0, 40);
    $wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 10)', $lock));
    try {
        $data = json_decode((string)ah_priv_get('store:' . $name), true) ?: [];
        $res = $fn($data);
        ah_priv_put('store:' . $name, wp_json_encode($data, JSON_UNESCAPED_UNICODE));
        return $res;
    } finally {
        $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)', $lock));
    }
}

function ah_store_read(string $name): array
{
    return json_decode((string)ah_priv_get('store:' . $name), true) ?: [];
}

// ---------------------------------------------------------------- settings / cron key

function ah_settings(): array
{
    return ah_store_read('settings') + ['notifyEmail' => '', 'mailFrom' => '', 'logbookPackage' => ''];
}

function ah_cron_key(): string
{
    $k = get_option('aerohangar_cron_key');
    if (!$k) {
        $k = bin2hex(random_bytes(16));
        update_option('aerohangar_cron_key', $k, false);
    }
    return (string)$k;
}

/** Public app configuration: where the apps find the API and the Pilot Logbook app id. */
function ah_write_app_config(): void
{
    $s = ah_settings();
    ah_write_atomic(ah_data_path('app_config'), ah_json([
        'apiUrl' => rest_url('aerohangar/v1/api') . '?a=',
        'accountUrl' => rest_url('cannonade/v1/'),
        'logbookPackage' => $s['logbookPackage'] ?: null,
    ]));
}

// ---------------------------------------------------------------- backups and audit log

function ah_backup(string $f, string $content): void
{
    global $wpdb;
    if ($content === '') return;
    ah_priv_put('bak:' . $f . '-' . gmdate('Ymd-His') . '-' . substr(sha1($content), 0, 6) . '.json', $content);
    $names = $wpdb->get_col($wpdb->prepare('SELECT name FROM ' . ah_table() . ' WHERE name LIKE %s ORDER BY name DESC', 'bak:' . $wpdb->esc_like($f) . '-%'));
    foreach (array_slice($names, AH_KEEP_BACKUPS) as $old) ah_priv_delete($old);
}

function ah_list_backups(): array
{
    global $wpdb;
    $rows = $wpdb->get_results('SELECT name, LENGTH(value) AS size FROM ' . ah_table() . " WHERE name LIKE 'bak:%' ORDER BY updated DESC, name DESC LIMIT 200", ARRAY_A);
    return array_map(fn($r) => ['name' => substr($r['name'], 4), 'size' => (int)$r['size']], $rows ?: []);
}

function ah_restore(string $name, string $by): bool
{
    if (!preg_match('/^(' . implode('|', AH_FILES) . ')-\d{8}-\d{6}-[0-9a-f]{6}\.json$/', $name, $m)) return false;
    $raw = ah_priv_get('bak:' . $name);
    if ($raw === null) return false;
    $restored = json_decode($raw, true);
    if (!is_array($restored)) return false;
    ah_backup($m[1], ah_read_editable($m[1]));
    ah_write_editable($m[1], $restored);
    ah_log($by, "restore $name");
    return true;
}

function ah_log(string $who, string $what): void
{
    ah_store_update('audit', function (&$d) use ($who, $what) {
        $d[] = gmdate('c') . "\t" . $who . "\t" . str_replace(["\n", "\t"], ' ', $what);
        if (count($d) > 3000) $d = array_slice($d, -2500);
    });
}

function ah_read_log(int $n): array
{
    return array_reverse(array_slice(ah_store_read('audit'), -$n));
}

/** Light structural checks so a typo in the panel cannot break the apps. */
function ah_validate(string $f, $d): ?string
{
    if (!is_array($d)) return 'data must be JSON';
    switch ($f) {
        case 'bulletins':
            if (!isset($d['bulletins']) || !is_array($d['bulletins'])) return 'missing "bulletins"';
            $ids = [];
            foreach ($d['bulletins'] as $b) {
                foreach (['id', 'date', 'title', 'groups', 'kind'] as $k) if (!isset($b[$k])) return "bulletin without $k";
                if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$b['date'])) return "bad date in {$b['id']}";
                if (isset($ids[$b['id']])) return "duplicate bulletin {$b['id']}";
                $ids[$b['id']] = 1;
            }
            return null;
        case 'contacts':
        case 'examiners':
            if (!isset($d['contacts']) || !is_array($d['contacts'])) return 'missing "contacts"';
            $ids = [];
            foreach ($d['contacts'] as $c) {
                if (empty($c['id']) || empty($c['name']) || empty($c['kind'])) return 'contact without id/name/kind';
                if (isset($ids[$c['id']])) return "duplicate contact {$c['id']}";
                $ids[$c['id']] = 1;
            }
            return null;
        case 'sponsor':
            if (!isset($d['banners']) || !is_array($d['banners'])) return 'missing "banners"';
            if (isset($d['rotateSeconds']) && (!is_int($d['rotateSeconds']) || $d['rotateSeconds'] < 0 || $d['rotateSeconds'] > 600)) return 'rotateSeconds must be 0–600';
            foreach ($d['banners'] as $b) {
                if (empty($b['id']) || (empty($b['title']) && empty($b['image']))) return 'banner without id and title/image';
                foreach (['url', 'image'] as $k) if (!empty($b[$k]) && !preg_match('~^https://~', (string)$b[$k])) return "banner {$b['id']}: $k must start with https://";
            }
            return null;
        case 'types':
            if (!array_is_list($d) || count($d) < 2) return 'types must be a list';
            $ids = [];
            foreach ($d as $t) {
                foreach (['id', 'name', 'family', 'category', 'bulletinGroups', 'speeds', 'manual', 'tasks'] as $k) if (!isset($t[$k])) return "type without $k";
                if (!in_array($t['category'], ['EASA', 'UL'], true)) return "bad category in {$t['id']}";
                if (isset($ids[$t['id']])) return "duplicate type {$t['id']}";
                $ids[$t['id']] = 1;
            }
            return null;
        case 'news':
            if (!isset($d['messages']) || !is_array($d['messages'])) return 'missing "messages"';
            foreach ($d['messages'] as $n) if (empty($n['id']) || empty($n['title'])) return 'message without id/title';
            return null;
    }
    return 'unknown file';
}


// ---------------------------------------------------------------- working copy vs. published file
// Contacts and examiners: the panel edits a working copy (all entries, incl. unpublished, in the database);
// the public file contains only entries with publish !== false. Other files are edited directly.

/** Raw JSON of what the panel edits. */
function ah_read_editable(string $f): string
{
    if (in_array($f, AH_MASTERED, true)) {
        $m = ah_priv_get('master:' . $f);
        if ($m !== null) return $m;
    }
    $p = ah_data_path($f);
    return is_file($p) ? (string)file_get_contents($p) : '';
}

function ah_json(array $d): string
{
    return json_encode($d, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n";
}

/** Writes the editable copy and (for contacts/examiners) regenerates the published file. Returns new rev. */
function ah_write_editable(string $f, array $d): string
{
    $json = ah_json($d);
    if (in_array($f, AH_MASTERED, true)) {
        ah_priv_put('master:' . $f, $json);
        $pub = $d;
        unset($pub['publishNew'], $pub['deletedIds']);
        $pub['contacts'] = array_values(array_map(function ($c) { unset($c['publish']); return $c; },
            array_filter($d['contacts'] ?? [], fn($c) => ($c['publish'] ?? true) !== false)));
        ah_write_atomic(ah_data_path($f), ah_json($pub));
    } else {
        ah_write_atomic(ah_data_path($f), $json);
    }
    return sha1($json);
}

/** Bot / GitHub import of official ULC entries into a mastered file (keeps the owner's publish choice). */
function ah_merge_official(string $target, array $prefixes, array $incoming, string $who): array
{
    $raw = ah_read_editable($target);
    $feed = $raw ? json_decode($raw, true) : ['version' => 0, 'updated' => '', 'contacts' => []];
    $default = $feed['publishNew'] ?? true;   // ULC lists are public documents
    $old = [];
    foreach ($feed['contacts'] as $c) $old[$c['id']] = $c;
    $keep = array_values(array_filter($feed['contacts'], fn($c) => !array_filter($prefixes, fn($p) => str_starts_with($c['id'], $p))));
    $ids = array_flip(array_column($keep, 'id'));
    $added = 0;
    foreach ($incoming as $c) {
        if (isset($ids[$c['id']])) continue;
        $c['publish'] = isset($old[$c['id']]) ? ($old[$c['id']]['publish'] ?? true) : $default;
        if (isset($old[$c['id']])) foreach (AH_PARTNER_FIELDS as $k) if (isset($old[$c['id']][$k])) $c[$k] = $old[$c['id']][$k];
        if (!isset($old[$c['id']])) $added++;
        $keep[] = $c;
        $ids[$c['id']] = 1;
    }
    $feed['contacts'] = $keep;
    $feed['version'] = ($feed['version'] ?? 0) + 1;
    $feed['updated'] = date('Y-m-d');
    ah_backup($target, $raw);
    ah_write_editable($target, $feed);
    ah_log($who, "import $target: " . count($incoming) . " official entries ($added new)");
    return ['imported' => count($incoming), 'new' => $added, 'total' => count($keep)];
}


/** Simple per-IP limiter for public endpoints: max $n calls per $sec. */
function ah_rate_ok(string $bucket, int $n, int $sec): bool
{
    $ip = sha1(($_SERVER['REMOTE_ADDR'] ?? '') . 'ah');
    return ah_store_update('rate_' . $bucket, function (&$d) use ($ip, $n, $sec) {
        $now = time();
        foreach ($d as $k => $ts) { $d[$k] = array_values(array_filter($ts, fn($t) => $t > $now - $sec)); if (!$d[$k]) unset($d[$k]); }
        $d[$ip] = $d[$ip] ?? [];
        if (count($d[$ip]) >= $n) return false;
        $d[$ip][] = $now;
        return true;
    });
}

function ah_clean_str($v, int $max): string
{
    return mb_substr(trim(preg_replace('/[\x00-\x1F\x7F]+/u', ' ', (string)$v)), 0, $max);
}



// ---------------------------------------------------------------- partners, service requests, e-mail

function ah_partner_active(array $c): bool
{
    $u = (string)($c['partnerUntil'] ?? '');
    return $u !== '' && $u >= date('Y-m-d');
}

/** Published contact by id (what the apps see). */
function ah_public_contact(string $id): ?array
{
    $raw = @file_get_contents(ah_data_path('contacts'));
    $d = $raw ? json_decode($raw, true) : null;
    foreach (($d['contacts'] ?? []) as $c) if (($c['id'] ?? '') === $id) return $c;
    return null;
}

/** Plain-text e-mail through WordPress (wp_mail – works with SMTP plugins). */
function ah_mail(string $to, string $subject, string $body, string $replyTo = ''): bool
{
    if (!is_email($to)) return false;
    $h = ['Content-Type: text/plain; charset=UTF-8'];
    $from = ah_settings()['mailFrom'];
    if ($from && is_email($from)) $h[] = 'From: AeroHangar <' . $from . '>';
    if ($replyTo !== '' && is_email($replyTo)) $h[] = 'Reply-To: ' . $replyTo;
    return (bool)wp_mail($to, $subject, $body, $h);
}
