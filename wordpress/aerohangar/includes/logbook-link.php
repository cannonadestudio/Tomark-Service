<?php
/**
 * One key for AeroHangar and Pilot Logbook: when the Pilot Logbook plugin is active, the account key IS the
 * pilot's personal Pilot Logbook key (plb_<id>_<secret>~<site>). AeroHangar accepts it, creating / replacing /
 * revoking a key on either side keeps both in step, and older CNA-… keys keep working.
 */
defined('ABSPATH') || exit;

function ah_plb_on(): bool
{
    return function_exists('plb_personal_key') && function_exists('plb_user_from_key');
}

/** Token part of a Pilot Logbook key (without "~site"). */
function ah_plb_token(string $key): string
{
    return (string)preg_replace('/~.*$/', '', trim($key));
}

// AeroHangar accepts the Pilot Logbook key (with or without "~site").
// Pilot Logbook decides whether a plb_ key is valid (a key replaced in Pilot Logbook stops here too).
add_filter('cannonade_user_from_key', function ($id, $key) {
    if (!ah_plb_on() || stripos(trim((string)$key), 'plb_') !== 0) return $id;
    return (int)plb_user_from_key(ah_plb_token((string)$key));
}, 10, 2);

// The key shown everywhere = the pilot's Pilot Logbook key; keeps AeroHangar's copy in step.
add_filter('cannonade_key_plain', function ($k, $uid) {
    if (!ah_plb_on()) return $k;
    $p = (string)plb_personal_key((int)$uid, false);
    if ($p === '') return $k;
    $h = cannonade_key_hash($p);
    if (get_user_meta($uid, 'cannonade_key_hash', true) !== $h) {
        update_user_meta($uid, 'cannonade_key_hash', $h);
        update_user_meta($uid, 'cannonade_key_hint', substr(ah_plb_token($p), -5));
        if (!get_user_meta($uid, 'cannonade_key_created', true)) update_user_meta($uid, 'cannonade_key_created', time());
    }
    return $p;
}, 10, 2);

// Creating / replacing the key creates a new Pilot Logbook key (the old one stops in both apps).
add_filter('cannonade_new_key', function ($k, $uid) {
    if (!ah_plb_on() || !function_exists('plb_reset_personal_key')) return $k;
    return (string)plb_reset_personal_key((int)$uid);
}, 10, 2);

// Revoking: the Pilot Logbook key goes too.
add_action('cannonade_key_revoked', function ($uid) {
    if (!ah_plb_on()) return;
    $tok = function_exists('plb_decrypt') ? plb_decrypt(get_user_meta($uid, 'plb_pkey', true)) : '';
    if ($tok && function_exists('plb_revoke_token')) plb_revoke_token($tok);
    delete_user_meta($uid, 'plb_pkey');
});
