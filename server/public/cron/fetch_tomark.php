<?php
/**
 * Daily: adds new documents from the public TOMARK technical-support list to data/bulletins.json.
 * Run from cron:   php /path/to/public/cron/fetch_tomark.php
 * or by URL:       https://your.server/aerohangar/cron/fetch_tomark.php?key=<cronKey from private/config.php>
 */
declare(strict_types=1);
define('AH_DATA', __DIR__ . '/../data');
require __DIR__ . '/../locate_private.php';

if (PHP_SAPI !== 'cli') {
    header('Content-Type: text/plain; charset=utf-8');
    if (!hash_equals((string)(ah_config()['cronKey'] ?? 'x'), (string)($_GET['key'] ?? ''))) {
        http_response_code(403);
        exit("bad key\n");
    }
}

const TOMARK_URL = 'https://www.tomarkaero.com/en/technical-support/';
const SECTIONS = [['Viper SD-4 RTC', 'SD4_RTC'], ['Viper SD-4 Night', 'SD4_NVFR'], ['Viper SD-4 (', 'SD4_UL'], ['Skyper GT9', 'GT9']];

function norm(string $s): string { return preg_replace('/[^A-Z0-9]/', '', strtoupper($s)); }

function kind(string $n): string
{
    $n = strtoupper($n);
    if (str_starts_with($n, 'EASA AD')) return 'AD';
    if (str_starts_with($n, 'ASB')) return 'ASB';
    if (str_starts_with($n, 'SIB')) return 'SIB';
    if (preg_match('/^(SB|ZB)/', $n)) return 'SB';
    return 'DOC';
}

$ctx = stream_context_create(['http' => ['timeout' => 30, 'header' => "User-Agent: Mozilla/5.0 (AeroHangar bulletin check)\r\n"]]);
$html = @file_get_contents(getenv('AH_TOMARK_URL') ?: TOMARK_URL, false, $ctx);  // env override for testing
if ($html === false) exit("cannot download the TOMARK page\n");

$doc = new DOMDocument();
libxml_use_internal_errors(true);
$doc->loadHTML('<?xml encoding="utf-8"?>' . $html);
$xp = new DOMXPath($doc);

$rows = [];
$group = null;
foreach ($xp->query('//h2|//h3|//h4|//tr') as $node) {
    if ($node->nodeName !== 'tr') {
        $t = trim($node->textContent);
        $group = null;
        foreach (SECTIONS as [$prefix, $g]) if (str_starts_with($t, $prefix)) $group = $g;
        continue;
    }
    if (!$group) continue;
    $tds = $xp->query('td', $node);
    if ($tds->length < 3) continue;
    $rows[] = [$group, trim(preg_replace('/\s+/', ' ', $tds[0]->textContent)), trim(preg_replace('/\s+/', ' ', $tds[1]->textContent)), trim(preg_replace('/\s+/', ' ', $tds[2]->textContent))];
}
if (count($rows) < 10) exit('parsed only ' . count($rows) . " rows – page layout changed? nothing written\n");

$path = ah_data_path('bulletins');
$raw = (string)file_get_contents($path);
$feed = json_decode($raw, true);
$seenFile = AH_STATE . '/tomark_seen.json';
$seen = is_file($seenFile) ? array_flip(json_decode((string)file_get_contents($seenFile), true) ?: []) : [];
if (!$seen) foreach ($rows as $r) $seen[norm($r[2])] = 1;   // first run: current list is the baseline
$byId = [];
foreach ($feed['bulletins'] as $i => $b) $byId[$b['id']] = $i;

$added = 0;
foreach ($rows as [$g, $date, $num, $title]) {
    $key = norm($num);
    if ($key === '') continue;
    if (isset($seen[$key])) {
        if (isset($byId[$key]) && !in_array($g, $feed['bulletins'][$byId[$key]]['groups'], true)) $feed['bulletins'][$byId[$key]]['groups'][] = $g;
        continue;
    }
    $d = DateTime::createFromFormat('d. M Y', $date) ?: new DateTime();
    $k = kind($num);
    $feed['bulletins'][] = ['id' => $key, 'date' => $d->format('Y-m-d'), 'title' => "$num: $title", 'groups' => [$g], 'kind' => $k,
        'mandatory' => in_array($k, ['AD', 'ASB'], true) ? true : null, 'url' => TOMARK_URL];
    $byId[$key] = count($feed['bulletins']) - 1;
    $seen[$key] = 1;
    $added++;
}
file_put_contents($seenFile, json_encode(array_keys($seen)));
if ($added) {
    usort($feed['bulletins'], fn($a, $b) => strcmp($b['date'], $a['date']));
    $feed['version'] = ($feed['version'] ?? 0) + 1;
    $feed['updated'] = $feed['bulletins'][0]['date'];
    ah_backup('bulletins', $raw);
    ah_write_atomic($path, json_encode($feed, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT) . "\n");
    ah_log('cron', "TOMARK: $added new");
}
echo 'rows: ' . count($rows) . ", new: $added\n";
