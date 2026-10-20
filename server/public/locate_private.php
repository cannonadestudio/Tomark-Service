<?php
/**
 * Finds the private folder (config, backups, log). Best: next to the web folder (not reachable from the web).
 * On simple hostings it may sit inside the web folder – its .htaccess then blocks access.
 */
foreach ([__DIR__ . '/../private', __DIR__ . '/../../private', __DIR__ . '/private'] as $p) {
    if (is_file($p . '/bootstrap.php')) {
        require_once $p . '/bootstrap.php';
        return;
    }
}
http_response_code(500);
exit('AeroHangar: private/ folder not found');
