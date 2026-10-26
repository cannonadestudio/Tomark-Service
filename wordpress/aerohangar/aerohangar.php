<?php
/**
 * Plugin Name:       AeroHangar
 * Plugin URI:        https://horyzontbezgranic.pl/
 * Description:       Serwer aplikacji AeroHangar w WordPressie: biuletyny, typy, kontakty, partnerzy, reklamy, zapytania serwisowe, instalacje i Pro – oraz konta pilotów z jednym kluczem dla AeroHangar i Pilot Logbook (Konto Cannonade).
 * Version:           1.6.0
 * Requires at least: 6.2
 * Requires PHP:      8.1
 * Author:            Cannonade Łukasz Bełdowski
 * License:           GPL-2.0-or-later
 * Text Domain:       aerohangar
 */
defined('ABSPATH') || exit;

// Too old PHP: show a notice instead of breaking the site.
if (PHP_VERSION_ID < 80100) {
    add_action('admin_notices', function () {
        echo '<div class="notice notice-error"><p>AeroHangar wymaga PHP 8.1 lub nowszego (jest ' . esc_html(PHP_VERSION) . '). Zmień wersję PHP w panelu hostingu.</p></div>';
    });
    return;
}

define('AEROHANGAR_VERSION', '1.6.0');
define('AEROHANGAR_DIR', __DIR__);
define('AEROHANGAR_URL', plugin_dir_url(__FILE__));

require_once __DIR__ . '/includes/core.php';
require_once __DIR__ . '/includes/api.php';
require_once __DIR__ . '/includes/account.php';
require_once __DIR__ . '/includes/userdata.php';
require_once __DIR__ . '/includes/admin.php';
require_once __DIR__ . '/includes/woocommerce.php';
require_once __DIR__ . '/includes/portal.php';
require_once __DIR__ . '/includes/backoffice.php';

register_activation_hook(__FILE__, 'aerohangar_activate_safe');

/** Activation / upgrade never breaks the site: errors are logged and shown as an admin notice. */
function aerohangar_activate_safe(): void
{
    try {
        aerohangar_activate();
        delete_option('aerohangar_setup_error');
    } catch (Throwable $e) {
        update_option('aerohangar_setup_error', $e->getMessage() . ' (' . basename($e->getFile()) . ':' . $e->getLine() . ')', false);
        error_log('AeroHangar setup: ' . $e);
    }
}

add_action('admin_notices', function () {
    $err = get_option('aerohangar_setup_error');
    if ($err && current_user_can('manage_options')) {
        echo '<div class="notice notice-error"><p><b>AeroHangar:</b> nie udało się przygotować danych – ' . esc_html($err) . '. Sprawdź, czy folder wp-content/uploads jest zapisywalny, i wyłącz/włącz wtyczkę.</p></div>';
    }
});

function aerohangar_activate(): void
{
    global $wpdb;
    require_once ABSPATH . 'wp-admin/includes/upgrade.php';
    dbDelta('CREATE TABLE ' . ah_table() . ' (
        name VARCHAR(191) NOT NULL,
        value LONGTEXT NOT NULL,
        updated DATETIME NOT NULL,
        PRIMARY KEY  (name)
    ) ' . $wpdb->get_charset_collate() . ';');

    // Roles: administrators manage everything; editors edit data; "aerohangar_view" = read-only.
    if ($r = get_role('administrator')) foreach (['aerohangar_admin', 'aerohangar_edit', 'aerohangar_view'] as $c) $r->add_cap($c);
    if ($r = get_role('editor')) foreach (['aerohangar_edit', 'aerohangar_view'] as $c) $r->add_cap($c);

    // Public data folder with the starting data (only files that do not exist yet).
    $dir = ah_data_dir();
    wp_mkdir_p($dir);
    @file_put_contents($dir . '/index.html', '');
    @file_put_contents($dir . '/.htaccess', "Options -Indexes\n<IfModule mod_headers.c>\nHeader set Cache-Control \"no-cache\"\n</IfModule>\n");
    foreach (AH_FILES as $f) {
        $src = __DIR__ . "/initial-data/$f.json";
        if (!is_file(ah_data_path($f)) && is_file($src)) {
            $d = json_decode((string)file_get_contents($src), true);
            if (is_array($d)) ah_write_editable($f, $d);
        }
    }
    // engine catalogue for the apps (not edited in the panel; refreshed with each plugin version)
    if (is_file(__DIR__ . '/initial-data/engines.json')) @copy(__DIR__ . '/initial-data/engines.json', $dir . '/engines.json');
    ah_cron_key();
    ah_write_app_config();
    update_option('aerohangar_version', AEROHANGAR_VERSION, false);
}

// The app/panel API.
add_action('rest_api_init', function () {
    register_rest_route('aerohangar/v1', '/api', [
        'methods' => ['GET', 'POST'],
        'permission_callback' => '__return_true',   // each action checks its own access (panel login, cron key, rate limits)
        'callback' => function () {
            ah_api_dispatch();
        },
    ]);
});

// Upgrades without re-activation (new table columns, new starting files, config).
// On "init" – WordPress (rewrite rules, REST URLs) is ready then; never on "plugins_loaded".
add_action('init', function () {
    if (get_option('aerohangar_version') !== AEROHANGAR_VERSION) aerohangar_activate_safe();
}, 99);
