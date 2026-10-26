<?php
/**
 * "Konto Cannonade" – one WordPress account and ONE key for the Cannonade apps (AeroHangar, Pilot Logbook).
 *
 * The pilot logs in to WordPress, generates a key on the account page ([cannonade_konto] shortcode or the
 * WordPress profile) and pastes it into each app. The apps send it as "Authorization: Bearer <key>".
 *
 * Shared by several plugins: whichever plugin loads first defines it (guarded by CANNONADE_ACCOUNT).
 * Other plugins plug in with:
 *   cannonade_user_from_request(WP_REST_Request $r): int|WP_Error   – authenticate an app request
 *   filter 'cannonade_account_apps'  (array $apps, int $user_id)     – add your app's status to the account page / /me
 *   filter 'cannonade_logbook_hours' (array $hoursByReg, int $user_id) – Pilot Logbook provides flight hours per registration
 *   filter 'cannonade_user_from_key' (int $user_id, string $key)     – accept an older key format of your plugin
 */
defined('ABSPATH') || exit;

if (!defined('CANNONADE_ACCOUNT')) {
    define('CANNONADE_ACCOUNT', '1.0');

    /** Key as shown to the pilot: CNA-xxxx-xxxx-xxxx-xxxx (only its HMAC is stored). */
    function cannonade_key_hash(string $key): string
    {
        return hash_hmac('sha256', strtoupper(trim($key)), wp_salt('auth'));
    }

    function cannonade_key_secret(): string
    {
        return hash('sha256', wp_salt('auth') . 'cannonade-key', true);
    }

    function cannonade_key_encrypt(string $key): string
    {
        if (!function_exists('openssl_encrypt')) return '';
        $iv = random_bytes(16);
        $ct = openssl_encrypt($key, 'aes-256-cbc', cannonade_key_secret(), OPENSSL_RAW_DATA, $iv);
        return $ct === false ? '' : base64_encode($iv . $ct);
    }

    /** The account's whole key (null for keys made before keys were kept, or after "disconnect"). */
    function cannonade_key_plain(int $user_id): ?string
    {
        $shared = apply_filters('cannonade_key_plain', null, $user_id);
        if (is_string($shared) && $shared !== '') return $shared;
        $enc = (string)get_user_meta($user_id, 'cannonade_key_enc', true);
        if ($enc === '' || !function_exists('openssl_decrypt')) return null;
        $raw = base64_decode($enc, true);
        if ($raw === false || strlen($raw) < 17) return null;
        $key = openssl_decrypt(substr($raw, 16), 'aes-256-cbc', cannonade_key_secret(), OPENSSL_RAW_DATA, substr($raw, 0, 16));
        // only a key that still matches the active one
        return ($key && hash_equals((string)get_user_meta($user_id, 'cannonade_key_hash', true), cannonade_key_hash($key))) ? $key : null;
    }

    function cannonade_new_key(int $user_id): string
    {
        // A plugin may provide the shared key (Pilot Logbook: its personal pilot key), so one key works in every app.
        $key = (string)apply_filters('cannonade_new_key', '', $user_id);
        if ($key === '') {
            $raw = strtoupper(bin2hex(random_bytes(10)));
            $key = 'CNA-' . implode('-', str_split($raw, 5));
        }
        update_user_meta($user_id, 'cannonade_key_hash', cannonade_key_hash($key));
        update_user_meta($user_id, 'cannonade_key_hint', substr($key, -5));
        update_user_meta($user_id, 'cannonade_key_created', time());
        // Encrypted copy, so the owner can always see the key on the account page and in the user menu.
        update_user_meta($user_id, 'cannonade_key_enc', cannonade_key_encrypt($key));
        do_action('cannonade_key_changed', $user_id);
        return $key;
    }

    function cannonade_revoke_key(int $user_id): void
    {
        delete_user_meta($user_id, 'cannonade_key_hash');
        delete_user_meta($user_id, 'cannonade_key_hint');
        delete_user_meta($user_id, 'cannonade_key_enc');
        do_action('cannonade_key_revoked', $user_id);
        do_action('cannonade_key_changed', $user_id);
    }

    function cannonade_user_from_key(string $key): int
    {
        $key = trim($key);
        if ($key === '') return 0;
        $users = get_users(['meta_key' => 'cannonade_key_hash', 'meta_value' => cannonade_key_hash($key), 'number' => 1, 'fields' => 'ID']);
        $id = $users ? (int)$users[0] : 0;
        return (int)apply_filters('cannonade_user_from_key', $id, $key);
    }

    /** User id of the app request (Bearer key), or WP_Error 401. Rate-limited against key guessing. */
    function cannonade_user_from_request(WP_REST_Request $r)
    {
        $h = (string)$r->get_header('authorization');
        if ($h === '' && !empty($_SERVER['REDIRECT_HTTP_AUTHORIZATION'])) $h = sanitize_text_field(wp_unslash($_SERVER['REDIRECT_HTTP_AUTHORIZATION']));
        $key = preg_match('/^Bearer\s+(.+)$/i', $h, $m) ? $m[1] : (string)$r->get_header('x-cannonade-key');
        $ipKey = 'cna_fail_' . md5($_SERVER['REMOTE_ADDR'] ?? '');
        if ((int)get_transient($ipKey) >= 20) return new WP_Error('cannonade_blocked', 'too many wrong keys – wait 15 minutes', ['status' => 429]);
        $uid = cannonade_user_from_key($key);
        if (!$uid) {
            set_transient($ipKey, (int)get_transient($ipKey) + 1, 15 * MINUTE_IN_SECONDS);
            return new WP_Error('cannonade_key', 'invalid key', ['status' => 401]);
        }
        update_user_meta($uid, 'cannonade_last_seen', time());
        // Paid service: a key works only while the account has access (bought or granted by the administrator).
        if (!cannonade_can_have_key($uid)) return new WP_Error('cannonade_no_access', 'no active access on this account', ['status' => 402]);
        return $uid;
    }

    /** Whether this account may create and use an app key (AeroHangar: bought or granted access). */
    function cannonade_can_have_key(int $uid): bool
    {
        return (bool)apply_filters('cannonade_can_have_key', true, $uid);
    }

    function cannonade_apps(int $uid): array
    {
        return (array)apply_filters('cannonade_account_apps', [], $uid);
    }

    // ------------------------------------------------------------ REST: /wp-json/cannonade/v1/…
    add_action('rest_api_init', function () {
        register_rest_route('cannonade/v1', '/me', [
            'methods' => 'GET', 'permission_callback' => '__return_true',
            'callback' => function (WP_REST_Request $r) {
                $uid = cannonade_user_from_request($r);
                if (is_wp_error($uid)) return $uid;
                $u = get_userdata($uid);
                return ['id' => $uid, 'name' => $u->display_name, 'email' => $u->user_email,
                    'keyHint' => get_user_meta($uid, 'cannonade_key_hint', true), 'apps' => cannonade_apps($uid)];
            },
        ]);
        // Flight hours per registration from Pilot Logbook (when that plugin is installed on this site).
        register_rest_route('cannonade/v1', '/logbook/hours', [
            'methods' => 'GET', 'permission_callback' => '__return_true',
            'callback' => function (WP_REST_Request $r) {
                $uid = cannonade_user_from_request($r);
                if (is_wp_error($uid)) return $uid;
                $hours = (array)apply_filters('cannonade_logbook_hours', [], $uid);
                return ['available' => has_filter('cannonade_logbook_hours') !== false, 'hoursByReg' => (object)$hours];
            },
        ]);
    });

    // ------------------------------------------------------------ account page
    require_once __DIR__ . '/i18n.php';

    function cannonade_account_html(): string
    {
        $t = 'cannonade_t';
        if (!is_user_logged_in()) {
            $reg = get_option('users_can_register') ? ' · <a href="' . esc_url(wp_registration_url()) . '">' . esc_html($t('register')) . '</a>' : '';
            return '<div class="cna-box"><p>' . esc_html($t('login_needed')) . '</p><p><a class="cna-btn" href="' . esc_url(wp_login_url(get_permalink() ?: home_url())) . '">' . esc_html($t('login')) . '</a>' . $reg . '</p></div>';
        }
        $uid = get_current_user_id();
        $newKey = '';
        $allowed = cannonade_can_have_key($uid);
        if (!empty($_POST['cna_action']) && check_admin_referer('cna_key_' . $uid)) {
            if ($_POST['cna_action'] === 'new' && $allowed) $newKey = cannonade_new_key($uid);
            if ($_POST['cna_action'] === 'revoke') cannonade_revoke_key($uid);
        }
        $hint = get_user_meta($uid, 'cannonade_key_hint', true);
        ob_start(); ?>
        <div class="cna-box">
            <h3><?php echo esc_html($t('title')); ?></h3>
            <p><?php echo esc_html($t('intro')); ?></p>
            <?php echo apply_filters('cannonade_account_top_html', '', $uid); ?>
            <?php if (!$allowed): ?>
                <p><b><?php echo esc_html($t('paid_only')); ?></b></p>
                <?php if ($hint): ?><p class="cna-warn"><?php echo esc_html($t('no_access_key')); ?></p><?php endif; ?>
                <?php echo apply_filters('cannonade_offer_html', '', $uid); ?>
            <?php elseif (($full = $newKey ?: cannonade_key_plain($uid))): ?>
                <p><?php echo esc_html(cannonade_t('your_key')); ?></p>
                <p class="cna-key"><code id="cna-key"><?php echo esc_html($full); ?></code>
                    <button type="button" class="cna-btn" onclick="navigator.clipboard&&navigator.clipboard.writeText(document.getElementById('cna-key').textContent);this.textContent='✓'"><?php echo esc_html($t('copy')); ?></button></p>
                <p class="cna-small"><?php echo esc_html(cannonade_t('key_where')); ?></p>
            <?php elseif ($hint): ?>
                <p><?php echo esc_html(sprintf($t('key_active'), '…' . $hint)); ?></p>
                <p class="cna-warn"><?php echo esc_html(cannonade_t('key_old')); ?></p>
            <?php else: ?>
                <p><?php echo esc_html($t('no_key')); ?></p>
            <?php endif; ?>
            <?php if ($allowed || $hint): ?><form method="post" style="display:inline">
                <?php wp_nonce_field('cna_key_' . $uid); ?>
                <?php if ($allowed): ?><button class="cna-btn" name="cna_action" value="new"<?php if ($hint) echo ' onclick="return confirm(' . esc_attr(wp_json_encode($t('confirm_new'))) . ')"'; ?>><?php echo esc_html($hint ? $t('new_key') : $t('make_key')); ?></button><?php endif; ?>
                <?php if ($hint): ?><button class="cna-btn cna-ghost" name="cna_action" value="revoke" onclick="return confirm(<?php echo esc_attr(wp_json_encode($t('confirm_revoke'))); ?>)"><?php echo esc_html($t('revoke')); ?></button><?php endif; ?>
            </form><?php endif; ?>
            <h4><?php echo esc_html($t('apps')); ?></h4>
            <ul class="cna-apps">
                <?php foreach (cannonade_apps($uid) as $app): ?>
                    <li><b><?php echo esc_html($app['name'] ?? ''); ?></b> – <?php echo esc_html($app['status'] ?? ''); ?></li>
                <?php endforeach; ?>
                <?php if (!has_filter('cannonade_logbook_hours')): ?>
                    <li><b>Pilot Logbook</b> – <?php echo esc_html($t('logbook_missing')); ?></li>
                <?php endif; ?>
            </ul>
            <p class="cna-small"><?php echo esc_html($t('howto')); ?></p>
        </div>
        <style>.cna-offer{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:10px;margin:10px 0 14px}.cna-offer div{border:1px solid #DDE3EB;border-radius:12px;padding:10px 12px}.cna-offer b{display:block}.cna-box{border:1px solid #DDE3EB;border-radius:14px;padding:16px 18px;max-width:720px}.cna-btn{background:#0B2A5B;color:#fff;border:0;border-radius:999px;padding:8px 16px;cursor:pointer;text-decoration:none;display:inline-block}.cna-ghost{background:transparent;color:#0B2A5B;border:1px solid #0B2A5B}.cna-key code{font-size:1.15em;padding:6px 10px;background:#F2F4F8;border-radius:8px}.cna-warn{color:#C62828}.cna-small{font-size:.9em;color:#55606F}</style>
        <?php return (string)ob_get_clean();
    }

    // User menu (top bar, "Witaj, …"): the whole key with a copy action.
    add_action('admin_bar_menu', function (WP_Admin_Bar $bar) {
        if (!is_user_logged_in()) return;
        $uid = get_current_user_id();
        $key = cannonade_key_plain($uid);
        $page = function_exists('ah_login_page_url') ? add_query_arg('ah', 'key', ah_login_page_url()) : admin_url('profile.php?page=cannonade-account');
        if ($key) {
            $bar->add_node(['parent' => 'user-actions', 'id' => 'cannonade-key', 'title' => esc_html(cannonade_t('key_menu')) . ' <code style="font-size:12px">' . esc_html($key) . '</code>', 'href' => '#',
                'meta' => ['onclick' => 'navigator.clipboard&&navigator.clipboard.writeText(' . esc_attr(wp_json_encode($key)) . ');this.querySelector("code").textContent="✓ ' . esc_js(cannonade_t('copied')) . '";return false;', 'title' => cannonade_t('copy')]]);
        } elseif (cannonade_can_have_key($uid)) {
            $bar->add_node(['parent' => 'user-actions', 'id' => 'cannonade-key', 'title' => esc_html(cannonade_t('key_menu_make')), 'href' => esc_url($page)]);
        }
    }, 90);

    add_shortcode('cannonade_konto', 'cannonade_account_html');
    add_shortcode('cannonade_account', 'cannonade_account_html');

    // Same page in wp-admin for every logged-in user: Profile → "Konto Cannonade"
    add_action('admin_menu', function () {
        add_submenu_page('profile.php', 'Konto Cannonade', cannonade_t('title'), 'read', 'cannonade-account', function () {
            echo '<div class="wrap"><h1>' . esc_html(cannonade_t('title')) . '</h1>' . cannonade_account_html() . '</div>';
        });
    });
}
