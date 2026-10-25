<?php
/**
 * wp-admin: "AeroHangar" menu – the management panel (full screen), import from the standalone server,
 * and the information needed for the apps and the GitHub bot.
 */
defined('ABSPATH') || exit;

add_action('admin_menu', function () {
    add_menu_page('AeroHangar', 'AeroHangar', 'aerohangar_view', 'aerohangar', 'aerohangar_info_page', 'dashicons-airplane', 58);
    add_submenu_page('aerohangar', 'AeroHangar – informacje', 'Informacje i ustawienia', 'aerohangar_view', 'aerohangar', 'aerohangar_info_page');
    add_submenu_page('aerohangar', 'Panel AeroHangar', 'Otwórz panel', 'aerohangar_view', 'aerohangar-panel', '__return_null');
    add_submenu_page('aerohangar', 'Import ze starego serwera', 'Import ze starego serwera', 'manage_options', 'aerohangar-import', 'aerohangar_import_page');
});

// The panel is a full-screen page of its own (it has its own layout and menu).
add_action('admin_init', function () {
    if (($_GET['page'] ?? '') !== 'aerohangar-panel') return;
    if (!current_user_can('aerohangar_view') && !current_user_can('manage_options')) wp_die('Brak uprawnień.');
    $html = (string)file_get_contents(AEROHANGAR_DIR . '/assets/panel.html');
    $cfg = ['api' => rest_url('aerohangar/v1/api'), 'nonce' => wp_create_nonce('wp_rest'), 'assets' => AEROHANGAR_URL . 'assets/',
        'wpAdmin' => admin_url('admin.php?page=aerohangar'), 'login' => wp_login_url(admin_url('admin.php?page=aerohangar-panel'))];
    $html = str_replace('/*AH_WP_CONFIG*/', 'window.AH_WP = ' . wp_json_encode($cfg) . ';', $html);
    $html = str_replace('href="favicon.ico"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/favicon.ico') . '"', $html);
    $html = str_replace(['href="icon-192.png"', 'href="apple-touch-icon.png"'], ['href="' . esc_url(AEROHANGAR_URL . 'assets/icon-192.png') . '"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/apple-touch-icon.png') . '"'], $html);
    nocache_headers();
    header('Content-Type: text/html; charset=utf-8');
    echo $html;
    exit;
});

function aerohangar_info_page(): void
{
    $api = rest_url('aerohangar/v1/api');
    $data = ah_data_url();
    $admin = current_user_can('manage_options');
    ?>
    <div class="wrap">
        <h1>AeroHangar</h1>
        <p><a class="button button-primary button-hero" href="<?php echo esc_url(admin_url('admin.php?page=aerohangar-panel')); ?>">Otwórz panel AeroHangar</a></p>
        <h2>Połączenie aplikacji z tą stroną</h2>
        <p>W repozytorium GitHub zmień <code>data/config.json</code> – wszystkie zainstalowane aplikacje przejdą na tę stronę przy następnym otwarciu:</p>
        <pre style="background:#fff;padding:12px;border:1px solid #ddd;max-width:900px;overflow:auto"><?php echo esc_html(wp_json_encode(['dataBaseUrl' => $data, 'apiUrl' => $api . '?a='], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)); ?></pre>
        <?php if ($admin): ?>
            <h2>Automat GitHub (biuletyny, listy ULC)</h2>
            <p>Sekrety w repozytorium (Settings → Secrets and variables → Actions):</p>
            <table class="widefat striped" style="max-width:900px">
                <tr><td><code>AH_SERVER</code></td><td><code><?php echo esc_html($api); ?></code></td></tr>
                <tr><td><code>AH_CRON_KEY</code></td><td><code><?php echo esc_html(ah_cron_key()); ?></code></td></tr>
            </table>
        <?php endif; ?>
        <h2>Konta pilotów (AeroHangar + Pilot Logbook)</h2>
        <p>Wstaw na dowolną stronę krótki kod <code>[cannonade_konto]</code> – zalogowany pilot utworzy tam swój klucz i wklei go w obu aplikacjach. Ta sama strona jest też w <a href="<?php echo esc_url(admin_url('profile.php?page=cannonade-account')); ?>">Profil → Konto Cannonade</a>.</p>
        <p>Rejestracja nowych użytkowników: Ustawienia → Ogólne → „Każdy może się zarejestrować” (rola: Subskrybent).</p>
        <h2>Pliki danych</h2>
        <p>Publiczne dane dla aplikacji: <code><?php echo esc_html($data); ?></code>. Dane prywatne (wpisy ukryte, instalacje, zapytania, kopie, dziennik) są w bazie WordPressa – zwykła kopia bazy obejmuje je.</p>
    </div>
    <?php
}

function aerohangar_import_page(): void
{
    if (!current_user_can('manage_options')) return;
    $msg = [];
    if (!empty($_POST['ah_import']) && check_admin_referer('ah_import')) {
        // 1. Public data from the old server (data/*.json)
        $base = esc_url_raw(trim((string)($_POST['old'] ?? '')));
        if ($base) {
            $base = preg_replace('~/(admin/?|data/?)?$~', '', rtrim($base, '/'));
            foreach (AH_FILES as $f) {
                $raw = ah_http_get("$base/data/$f.json");
                $d = $raw ? json_decode($raw, true) : null;
                if (!is_array($d) || ah_validate($f, $d)) { $msg[] = "$f: nie pobrano"; continue; }
                ah_backup($f, ah_read_editable($f));
                ah_write_editable($f, $d);
                $msg[] = "$f: pobrano";
            }
            $cfg = json_decode(ah_http_get("$base/data/app_config.json"), true);
            if (!empty($cfg['logbookPackage'])) ah_store_update('settings', function (&$s) use ($cfg) { $s['logbookPackage'] = $cfg['logbookPackage']; });
        }
        // 2. Private files from the old server's private/state folder
        $map = ['master_contacts.json' => 'master:contacts', 'master_examiners.json' => 'master:examiners',
            'installs.json' => 'store:installs', 'submissions.json' => 'store:submissions', 'requests.json' => 'store:requests',
            'events.json' => 'store:events', 'settings.json' => 'store:settings'];
        foreach ((array)($_FILES['state']['name'] ?? []) as $i => $name) {
            $name = basename((string)$name);
            $tmp = $_FILES['state']['tmp_name'][$i] ?? '';
            if (!isset($map[$name]) || !is_uploaded_file($tmp)) { if ($name) $msg[] = "$name: pominięto"; continue; }
            $raw = (string)file_get_contents($tmp);
            $d = json_decode($raw, true);
            if (!is_array($d)) { $msg[] = "$name: to nie jest JSON"; continue; }
            if (str_starts_with($map[$name], 'master:')) {
                $f = substr($map[$name], 7);
                ah_backup($f, ah_read_editable($f));
                ah_write_editable($f, $d);
            } else {
                ah_priv_put($map[$name], wp_json_encode($d, JSON_UNESCAPED_UNICODE));
            }
            $msg[] = "$name: wczytano";
        }
        ah_write_app_config();
        ah_log(wp_get_current_user()->user_login, 'import from the standalone server: ' . implode(', ', $msg));
    }
    ?>
    <div class="wrap">
        <h1>Import ze starego serwera AeroHangar</h1>
        <?php if ($msg): ?><div class="notice notice-success"><p><?php echo esc_html(implode(' · ', $msg)); ?></p></div><?php endif; ?>
        <form method="post" enctype="multipart/form-data">
            <?php wp_nonce_field('ah_import'); ?>
            <h2>1. Dane publiczne</h2>
            <p>Adres starego serwera (folder z <code>admin/</code> i <code>data/</code>):</p>
            <p><input type="url" name="old" class="regular-text" placeholder="https://horyzontbezgranic.pl/aerohangar/public"></p>
            <h2>2. Dane prywatne (opcjonalnie)</h2>
            <p>Pliki z folderu <code>private/state</code> starego serwera: <code>master_contacts.json</code>, <code>master_examiners.json</code> (wpisy ukryte), <code>installs.json</code>, <code>submissions.json</code>, <code>requests.json</code>, <code>events.json</code>, <code>settings.json</code>.</p>
            <p><input type="file" name="state[]" multiple accept=".json"></p>
            <p><button class="button button-primary" name="ah_import" value="1">Importuj</button></p>
        </form>
        <p>Konta panelu ze starego serwera nie są przenoszone – w WordPressie logujesz się swoim kontem (administrator = pełny dostęp, redaktor = edycja danych).</p>
    </div>
    <?php
}

// "Settings" link on the plugins screen.
add_filter('plugin_action_links_' . plugin_basename(AEROHANGAR_DIR . '/aerohangar.php'), function ($links) {
    array_unshift($links, '<a href="' . esc_url(admin_url('admin.php?page=aerohangar-panel')) . '">Panel</a>');
    return $links;
});
