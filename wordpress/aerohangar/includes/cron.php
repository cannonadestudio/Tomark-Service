<?php
/**
 * Daily update from GitHub (WP-Cron, about 06:30 site time) – the same as the panel button "Pobierz z GitHuba":
 * new bulletins (EASA ADs, manufacturers) and their groups, new aircraft types, the engine catalogue and the
 * official ULC lists. The apps read these files at every start and once a day in the background.
 */
defined('ABSPATH') || exit;

function ah_pull_github(string $by): array
{
    $base = rtrim('https://raw.githubusercontent.com/cannonadestudio/Tomark-Service/main/data', '/');
    $ctx = null;
    $res = [];
    foreach ([['examiners', ['ulc-exam-', 'ulc-ame-']], ['contacts', ['ulc-pl-', 'ulc-aemc-']]] as [$f, $prefixes]) {
        $raw = ah_http_get("$base/$f.json");
        $d = $raw ? json_decode($raw, true) : null;
        if (!is_array($d['contacts'] ?? null)) { $res[$f] = ['error' => 'download failed']; continue; }
        $inc = array_values(array_filter($d['contacts'], fn($c) => !empty($c['id']) && array_filter($prefixes, fn($p) => str_starts_with($c['id'], $p))));
        $res[$f] = $inc ? ah_merge_official($f, $prefixes, $inc, $by) : ['imported' => 0];
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
            ah_log($by, "pull $f: $extra other entries added");
        }
        $res[$f]['added_other'] = $extra;
    }
    // Bulletins (TOMARK, EASA ADs, manufacturers): add the ones the server does not have yet.
    $raw = ah_http_get("$base/bulletins.json");
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
            ah_log($by, "pull bulletins: $added new");
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
            if ($changed) { ah_backup('bulletins', $cur); ah_write_editable('bulletins', $feed); ah_log($by, "pull bulletins: groups updated on $changed"); }
            $res['bulletins']['groupsUpdated'] = $changed;
        }
    }
    // Aircraft types: add new ones (trikes, gyroplanes…); old-format entries get engine / kind / groups
    // and lose the engine tasks that now come from the engine catalogue. Speeds and texts you edited stay.
    $raw = ah_http_get("$base/types.json");
    $gt = $raw ? json_decode($raw, true) : null;
    if (is_array($gt) && array_is_list($gt)) {
        $cur = ah_read_editable('types');
        $types = $cur ? json_decode($cur, true) : [];
        $byId = []; foreach ($gt as $t) $byId[$t['id']] = $t;
        $added = 0; $upgraded = 0; $have = []; $checklists = 0;
        foreach ($types as &$t) {
            $have[$t['id']] = 1;
            $g = $byId[$t['id']] ?? null;
            // checklists added on GitHub reach tasks that still have no item list here (edited lists stay)
            if ($g) {
                $gtasks = []; foreach ((array)($g['tasks'] ?? []) as $gk) $gtasks[$gk['id']] = $gk;
                foreach ($t['tasks'] as &$k) {
                    $gk = $gtasks[$k['id']] ?? null;
                    if ($gk && empty($k['items']) && !empty($gk['items'])) { $k['items'] = $gk['items']; if (!empty($gk['itemsSource'])) $k['itemsSource'] = $gk['itemsSource']; $checklists++; }
                }
                unset($k);
            }
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
        if ($added || $upgraded || $checklists) { ah_backup('types', $cur); ah_write_editable('types', $types); ah_log($by, "pull types: $added new, $upgraded upgraded, $checklists checklists"); }
        $res['types'] = ['new' => $added, 'upgraded' => $upgraded, 'checklists' => $checklists, 'total' => count($types)];
    } else {
        $res['types'] = ['error' => 'download failed'];
    }
    // Engine catalogue (Rotax, Jabiru, Lycoming, Continental…): taken as it is on GitHub.
    $raw = ah_http_get("$base/engines.json");
    $ge = $raw ? json_decode($raw, true) : null;
    if (is_array($ge) && array_is_list($ge) && in_array('rotax_912', array_column($ge, 'id'), true)) {
        ah_write_atomic(ah_data_path('engines'), $raw);
        $res['engines'] = ['total' => count($ge)];
        ah_log($by, 'pull engines: ' . count($ge));
    } else {
        $res['engines'] = ['error' => 'download failed'];
    }
    // approved checklist corrections stay on top of the lists from GitHub
    do_action('aerohangar_after_pull');
    update_option('aerohangar_last_pull', ['time' => time(), 'by' => $by, 'result' => $res], false);
    return $res;
}

add_action('aerohangar_daily_pull', function () {
    try {
        ah_pull_github('cron');
    } catch (Throwable $e) {
        ah_log('cron', 'daily pull failed: ' . $e->getMessage());
    }
});

// Schedule once (also after updating the plugin without re-activation).
add_action('init', function () {
    if (!wp_next_scheduled('aerohangar_daily_pull')) {
        $t = strtotime('tomorrow 06:30', current_time('timestamp')) - (int)(get_option('gmt_offset') * HOUR_IN_SECONDS);
        wp_schedule_event($t, 'daily', 'aerohangar_daily_pull');
    }
}, 20);

register_deactivation_hook(AEROHANGAR_DIR . '/aerohangar.php', function () {
    wp_clear_scheduled_hook('aerohangar_daily_pull');
});
