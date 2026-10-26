<?php
/**
 * wp-admin: "AeroHangar" menu – the management panel (full screen), import from the standalone server,
 * and the information needed for the apps and the GitHub bot.
 */
defined('ABSPATH') || exit;

/** Panel sections shown as items of the WordPress menu "AeroHangar" (slug => [menu title, panel tab]). */
function ah_admin_sections(): array
{
    return [
        'aerohangar-panel' => ['Pulpit', 'home'],
        'aerohangar-p-bulletins' => ['Biuletyny', 'bulletins'],
        'aerohangar-p-types' => ['Typy samolotów', 'types'],
        'aerohangar-p-news' => ['Komunikaty', 'news'],
        'aerohangar-p-contacts' => ['Kontakty', 'c_service'],
        'aerohangar-p-money' => ['Partnerzy i reklamy', 'partners'],
        'aerohangar-p-accounts' => ['Konta użytkowników', 'accounts'],
        'aerohangar-p-users' => ['Zgłoszenia i statystyki', 'subs'],
        'aerohangar-p-history' => ['Historia i kopie', 'history'],
        'aerohangar-p-settings' => ['Ustawienia panelu', 'settings'],
    ];
}

add_action('admin_menu', function () {
    add_menu_page('AeroHangar', 'AeroHangar', 'aerohangar_view', 'aerohangar-panel', 'aerohangar_embed_page', 'dashicons-airplane', 58);
    $i = 0;
    foreach (ah_admin_sections() as $slug => [$title, $tab]) {
        $cap = $tab === 'settings' ? 'manage_options' : 'aerohangar_view';
        add_submenu_page('aerohangar-panel', 'AeroHangar – ' . $title, $title, $cap, $slug, 'aerohangar_embed_page', $i++);
    }
    add_submenu_page('aerohangar-panel', 'AeroHangar – informacje', 'Informacje i produkty', 'aerohangar_view', 'aerohangar', 'aerohangar_info_page', 50);
    add_submenu_page('aerohangar-panel', 'Import ze starego serwera', 'Import ze starego serwera', 'manage_options', 'aerohangar-import', 'aerohangar_import_page', 60);
    // the panel application itself (loaded inside the pages above), not in the menu
    add_submenu_page('', 'AeroHangar', '', 'aerohangar_view', 'aerohangar-app', '__return_null');
});

/** A panel section inside the normal wp-admin screen (WordPress menu on the left, the panel on the right). */
function aerohangar_embed_page(): void
{
    $slug = sanitize_key($_GET['page'] ?? 'aerohangar-panel');
    $tab = ah_admin_sections()[$slug][1] ?? 'home';
    $src = admin_url('admin.php?page=aerohangar-app&embed=1');
    ?>
    <div class="wrap ah-embed-wrap" style="margin:10px 20px 0 0">
        <iframe id="ah-panel-frame" title="AeroHangar" style="width:100%;border:0;display:block;background:#F2F4F8;border-radius:8px;height:calc(100vh - 32px - 30px)"></iframe>
    </div>
    <script>
    (function () {
        var f = document.getElementById('ah-panel-frame');
        var tab = (location.hash.length > 1 ? location.hash.slice(1) : <?php echo wp_json_encode($tab); ?>);
        f.src = <?php echo wp_json_encode($src); ?> + '#' + tab;
        document.getElementById('wpfooter') && (document.getElementById('wpfooter').style.display = 'none');
    })();
    </script>
    <?php
}

// The panel application: full screen (front-end clients) or inside the wp-admin pages above (?embed=1).
add_action('admin_init', function () {
    if (($_GET['page'] ?? '') !== 'aerohangar-app') return;
    if (!current_user_can('aerohangar_view') && !current_user_can('manage_options')) wp_die('Brak uprawnień.');
    $html = (string)file_get_contents(AEROHANGAR_DIR . '/assets/panel.html');
    $cfg = ['api' => rest_url('aerohangar/v1/api'), 'nonce' => wp_create_nonce('wp_rest'), 'assets' => AEROHANGAR_URL . 'assets/',
        'wpAdmin' => admin_url('admin.php?page=aerohangar'), 'login' => wp_login_url(admin_url('admin.php?page=aerohangar-panel')),
        'embed' => !empty($_GET['embed']), 'adminPages' => array_map(fn($x) => $x[1], ah_admin_sections()), 'adminBase' => admin_url('admin.php?page=')];
    $html = str_replace('/*AH_WP_CONFIG*/', 'window.AH_WP = ' . wp_json_encode($cfg) . ';', $html);
    $html = str_replace('href="favicon.ico"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/favicon.ico') . '"', $html);
    $html = str_replace(['href="icon-192.png"', 'href="apple-touch-icon.png"'], ['href="' . esc_url(AEROHANGAR_URL . 'assets/icon-192.png') . '"', 'href="' . esc_url(AEROHANGAR_URL . 'assets/apple-touch-icon.png') . '"'], $html);
    nocache_headers();
    header('Content-Type: text/html; charset=utf-8');
    header('X-Frame-Options: SAMEORIGIN');
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
        <?php $lp = get_option('aerohangar_last_pull'); $nx = wp_next_scheduled('aerohangar_daily_pull'); ?>
        <h2>Codzienna aktualizacja z GitHuba</h2>
        <p>Strona sama, raz dziennie (rano), pobiera nowe biuletyny (AD EASA, producenci), typy samolotów, katalog silników i listy ULC. Aplikacje pobierają je przy każdym uruchomieniu i raz dziennie w tle.<br>
        Ostatnio: <b><?php echo $lp ? esc_html(wp_date('Y-m-d H:i', $lp['time']) . ' (' . ($lp['by'] === 'cron' ? 'automatycznie' : $lp['by']) . ')') : 'jeszcze nie'; ?></b><?php
        if ($lp && isset($lp['result']['bulletins']['new'])) echo esc_html(' – nowe biuletyny: ' . (int)$lp['result']['bulletins']['new'] . ', razem ' . (int)($lp['result']['bulletins']['total'] ?? 0)); ?>
        · następna: <?php echo $nx ? esc_html(wp_date('Y-m-d H:i', $nx)) : '—'; ?></p>
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
        <?php if ($admin) aerohangar_products_box(); ?>
        <h2>Konta użytkowników (AeroHangar + Pilot Logbook)</h2>
        <p>Wstaw na dowolną stronę krótki kod <code>[cannonade_konto]</code> – zalogowany pilot utworzy tam swój klucz i wklei go w obu aplikacjach. Ta sama strona jest też w <a href="<?php echo esc_url(admin_url('profile.php?page=cannonade-account')); ?>">Profil → Konto Cannonade</a>.</p>
        <p>Rejestracja nowych użytkowników: Ustawienia → Ogólne → „Każdy może się zarejestrować” (rola: Subskrybent).</p>
        <h2>Pliki danych</h2>
        <p>Publiczne dane dla aplikacji: <code><?php echo esc_html($data); ?></code>. Dane prywatne (wpisy ukryte, instalacje, zapytania, kopie, dziennik) są w bazie WordPressa – zwykła kopia bazy obejmuje je.</p>
    </div>
    <?php
}

/** WooCommerce: the five monthly AeroHangar plans – one button creates them. */
function aerohangar_products_box(): void
{
    echo '<h2>Sprzedaż dostępu – produkty WooCommerce</h2>';
    if (!class_exists('WooCommerce')) { echo '<p>Zainstaluj i włącz WooCommerce, żeby sprzedawać abonamenty.</p>'; return; }
    $msg = [];
    if (!empty($_POST['ah_products']) && check_admin_referer('ah_products')) $msg = ah_create_products();
    if ($msg) echo '<div class="notice notice-success inline"><p>' . implode('<br>', array_map('esc_html', $msg)) . '</p></div>';
    $subs = class_exists('WC_Product_Subscription');
    if (!$subs) {
        echo '<div class="notice notice-warning inline"><p><b>Automatyczna płatność co miesiąc wymaga wtyczki WooCommerce Subscriptions</b> (plus bramka płatności z płatnościami cyklicznymi, np. Stripe lub WooPayments). '
            . 'Bez niej produkty powstaną jako <b>szkice na 30 dni</b> bez odnawiania – po zainstalowaniu Subscriptions kliknij przycisk ponownie, a zamienią się w subskrypcje i zostaną opublikowane.</p></div>';
    }
    if (get_woocommerce_currency() !== 'PLN') echo '<p style="color:#b32d2e">Waluta sklepu to ' . esc_html(get_woocommerce_currency()) . ' – kwoty są w złotych (WooCommerce → Ustawienia → Ogólne → Waluta).</p>';
    $ids = (array)get_option('aerohangar_products', []);
    echo '<table class="widefat striped" style="max-width:900px"><tr><th>Plan</th><th>Netto / mies.</th><th>Produkt</th></tr>';
    foreach (AH_PRODUCTS as $plan => [$name, $net]) {
        $id = (int)($ids[$plan] ?? 0);
        $p = $id ? wc_get_product($id) : null;
        $cell = $p && get_post_status($id) !== 'trash'
            ? '<a href="' . esc_url(get_edit_post_link($id)) . '">' . esc_html($p->get_name()) . '</a> · ' . esc_html($p->get_regular_price() . ' ' . get_woocommerce_currency()) . ' · ' . esc_html($p->is_type('subscription') ? 'subskrypcja' : 'bez odnawiania') . ' · ' . esc_html(get_post_status($id) === 'publish' ? 'opublikowany' : 'szkic')
            : '<span class="description">nie utworzono</span>';
        echo '<tr><td>' . esc_html($name) . '</td><td>' . esc_html($net) . ' zł</td><td>' . $cell . '</td></tr>';
    }
    echo '</table>';
    echo '<p class="description">Ceny są netto: jeśli sklep wpisuje ceny z podatkiem (WooCommerce → Ustawienia → Podatek), wtyczka dolicza stawkę VAT sklepu. Po zakupie konto kupującego dostaje Pro i plan; limit samolotów i własnych typów ustawiasz w produkcie (pole AeroHangar) albo przy koncie w panelu → Konta pilotów.</p>';
    echo '<form method="post">';
    wp_nonce_field('ah_products');
    echo '<p><button class="button button-primary" name="ah_products" value="1">Utwórz / uzupełnij produkty AeroHangar</button></p></form>';
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
