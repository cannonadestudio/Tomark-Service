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

    function cannonade_new_key(int $user_id): string
    {
        $raw = strtoupper(bin2hex(random_bytes(10)));
        $key = 'CNA-' . implode('-', str_split($raw, 5));
        update_user_meta($user_id, 'cannonade_key_hash', cannonade_key_hash($key));
        update_user_meta($user_id, 'cannonade_key_hint', substr($key, -5));
        update_user_meta($user_id, 'cannonade_key_created', time());
        do_action('cannonade_key_changed', $user_id);
        return $key;
    }

    function cannonade_revoke_key(int $user_id): void
    {
        delete_user_meta($user_id, 'cannonade_key_hash');
        delete_user_meta($user_id, 'cannonade_key_hint');
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
        $key = preg_match('/^Bearer\s+(.+)$/i', $h, $m) ? $m[1] : (string)$r->get_header('x-cannonade-key');
        $ipKey = 'cna_fail_' . md5($_SERVER['REMOTE_ADDR'] ?? '');
        if ((int)get_transient($ipKey) >= 20) return new WP_Error('cannonade_blocked', 'too many wrong keys – wait 15 minutes', ['status' => 429]);
        $uid = cannonade_user_from_key($key);
        if (!$uid) {
            set_transient($ipKey, (int)get_transient($ipKey) + 1, 15 * MINUTE_IN_SECONDS);
            return new WP_Error('cannonade_key', 'invalid key', ['status' => 401]);
        }
        update_user_meta($uid, 'cannonade_last_seen', time());
        return $uid;
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
        if (!empty($_POST['cna_action']) && check_admin_referer('cna_key_' . $uid)) {
            if ($_POST['cna_action'] === 'new') $newKey = cannonade_new_key($uid);
            if ($_POST['cna_action'] === 'revoke') cannonade_revoke_key($uid);
        }
        $hint = get_user_meta($uid, 'cannonade_key_hint', true);
        ob_start(); ?>
        <div class="cna-box">
            <h3><?php echo esc_html($t('title')); ?></h3>
            <p><?php echo esc_html($t('intro')); ?></p>
            <?php if ($newKey): ?>
                <p class="cna-key"><code id="cna-key"><?php echo esc_html($newKey); ?></code>
                    <button type="button" class="cna-btn" onclick="navigator.clipboard&&navigator.clipboard.writeText(document.getElementById('cna-key').textContent);this.textContent='✓'"><?php echo esc_html($t('copy')); ?></button></p>
                <p class="cna-warn"><?php echo esc_html($t('shown_once')); ?></p>
            <?php elseif ($hint): ?>
                <p><?php echo esc_html(sprintf($t('key_active'), '…' . $hint)); ?></p>
            <?php else: ?>
                <p><?php echo esc_html($t('no_key')); ?></p>
            <?php endif; ?>
            <form method="post" style="display:inline">
                <?php wp_nonce_field('cna_key_' . $uid); ?>
                <button class="cna-btn" name="cna_action" value="new"<?php if ($hint) echo ' onclick="return confirm(' . esc_attr(wp_json_encode($t('confirm_new'))) . ')"'; ?>><?php echo esc_html($hint ? $t('new_key') : $t('make_key')); ?></button>
                <?php if ($hint): ?><button class="cna-btn cna-ghost" name="cna_action" value="revoke" onclick="return confirm(<?php echo esc_attr(wp_json_encode($t('confirm_revoke'))); ?>)"><?php echo esc_html($t('revoke')); ?></button><?php endif; ?>
            </form>
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
        <style>.cna-box{border:1px solid #e3dcea;border-radius:14px;padding:16px 18px;max-width:720px}.cna-btn{background:#7A3E9D;color:#fff;border:0;border-radius:999px;padding:8px 16px;cursor:pointer;text-decoration:none;display:inline-block}.cna-ghost{background:transparent;color:#7A3E9D;border:1px solid #7A3E9D}.cna-key code{font-size:1.15em;padding:6px 10px;background:#f4f1f8;border-radius:8px}.cna-warn{color:#b0003a}.cna-small{font-size:.9em;color:#6b6676}</style>
        <?php return (string)ob_get_clean();
    }

    add_shortcode('cannonade_konto', 'cannonade_account_html');
    add_shortcode('cannonade_account', 'cannonade_account_html');

    // Same page in wp-admin for every logged-in user: Profile → "Konto Cannonade"
    add_action('admin_menu', function () {
        add_submenu_page('profile.php', 'Konto Cannonade', cannonade_t('title'), 'read', 'cannonade-account', function () {
            echo '<div class="wrap"><h1>' . esc_html(cannonade_t('title')) . '</h1>' . cannonade_account_html() . '</div>';
        });
    });
}
