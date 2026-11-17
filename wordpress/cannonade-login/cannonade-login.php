<?php
/**
 * Plugin Name:       Cannonade – logowanie bez zaplecza
 * Description:       Jedno logowanie dla Pilot Logbook, AeroHangar i sklepu: pytanie Jetpack Protect w każdym formularzu logowania (bez drugiego logowania), po zalogowaniu od razu do logbooka, piloci i klienci nie widzą zaplecza WordPress ani paska administracyjnego, strony z logowaniem nie trafiają do pamięci podręcznej.
 * Version:           1.0.1
 * Requires at least: 5.8
 * Requires PHP:      7.4
 * Author:            Cannonade Łukasz Bełdowski
 * License:           GPL-2.0-or-later
 * Text Domain:       cannonade-login
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

define( 'CNL_VERSION', '1.0.1' );

/* Każdy hak wtyczki działa „bezpiecznie”: wyjątek w nim nie zatrzymuje strony – wtyczka wtedy po prostu nic nie zmienia. */
function cnl_safe( $fn ) {
	return function ( ...$args ) use ( $fn ) {
		try {
			return $fn( ...$args );
		} catch ( Throwable $e ) {
			error_log( 'cannonade-login: ' . $e->getMessage() . ' @ ' . basename( $e->getFile() ) . ':' . $e->getLine() ); // phpcs:ignore
			return $args[0] ?? null;
		}
	};
}

/* ---------------------------------------------------------------- kto widzi zaplecze
 * Administrator, redaktorzy/autorzy (edit_posts), obsługa sklepu i zespół AeroHangar.
 * Piloci, mechanicy, szkoły, klienci sklepu i subskrybenci – nie.
 */
function cnl_is_staff( $user = null ) {
	$user = $user ? $user : wp_get_current_user();
	if ( ! $user || ! $user->exists() ) {
		return false;
	}
	foreach ( array( 'manage_options', 'edit_posts', 'manage_woocommerce', 'aerohangar_view' ) as $cap ) {
		if ( user_can( $user, $cap ) ) {
			return true;
		}
	}
	return (bool) apply_filters( 'cnl_is_staff', false, $user );
}

/* Strona, na którą trafia pilot / klient: aplikacja Pilot Logbook, jego konto, AeroHangar, konto sklepu albo strona główna. */
function cnl_home_url_for( $user = null ) {
	foreach ( array( 'plb_account_page', 'aerohangar_login_page' ) as $opt ) {
		$id = (int) get_option( $opt );
		if ( $id && 'publish' === get_post_status( $id ) ) {
			return (string) apply_filters( 'cnl_home_url', get_permalink( $id ), $user );
		}
	}
	if ( function_exists( 'wc_get_page_permalink' ) ) {
		return (string) apply_filters( 'cnl_home_url', wc_get_page_permalink( 'myaccount' ), $user );
	}
	return (string) apply_filters( 'cnl_home_url', home_url( '/' ), $user );
}

/* ---------------------------------------------------------------- bez zaplecza i paska */
add_action( 'admin_init', cnl_safe( function () {
	if ( wp_doing_ajax() || ( defined( 'DOING_CRON' ) && DOING_CRON ) || ! is_user_logged_in() || cnl_is_staff() ) {
		return;
	}
	$file = isset( $GLOBALS['pagenow'] ) ? $GLOBALS['pagenow'] : '';
	if ( in_array( $file, array( 'admin-ajax.php', 'admin-post.php', 'async-upload.php' ), true ) ) {
		return;   // formularze i wysyłanie plików z części publicznej
	}
	wp_safe_redirect( cnl_home_url_for() );
	exit;
} ), 1 );

add_filter( 'show_admin_bar', cnl_safe( function ( $show ) {
	return ( is_user_logged_in() && ! cnl_is_staff() ) ? false : $show;
} ), 99 );

/* ---------------------------------------------------------------- po zalogowaniu: od razu do logbooka */
add_filter( 'login_redirect', cnl_safe( function ( $to, $requested, $user ) {
	if ( ! ( $user instanceof WP_User ) || cnl_is_staff( $user ) ) {
		return $to;
	}
	// zaplecze, profil albo brak celu → logbook; inny adres na stronie (np. AeroHangar, sklep) zostaje
	if ( '' === (string) $requested || false !== strpos( (string) $requested, '/wp-admin' ) || false !== strpos( (string) $to, '/wp-admin' ) ) {
		return cnl_home_url_for( $user );
	}
	return $to;
} ), 99, 3 );

add_filter( 'woocommerce_login_redirect', cnl_safe( function ( $to, $user ) {
	if ( ( $user instanceof WP_User ) && ! cnl_is_staff( $user ) && false !== strpos( (string) $to, '/wp-admin' ) ) {
		return cnl_home_url_for( $user );
	}
	return $to;
} ), 99, 2 );

/* Wylogowanie z logbooka/AeroHangar wraca na stronę logowania na froncie, nie do wp-login.php. */
add_filter( 'logout_redirect', cnl_safe( function ( $to, $requested, $user ) {
	if ( ( $user instanceof WP_User ) && ! cnl_is_staff( $user ) && ( '' === (string) $requested || false !== strpos( (string) $to, 'wp-login.php' ) ) ) {
		return cnl_home_url_for( $user );
	}
	return $to;
} ), 99, 3 );

/* ---------------------------------------------------------------- Jetpack Protect w każdym formularzu
 * Jetpack sprawdza pytanie matematyczne przy KAŻDYM logowaniu, a dodaje je tylko do wp-login.php.
 * Formularz bez pytania kończy się stroną Jetpacka i drugim logowaniem – dlatego dokładamy pole.
 */
function cnl_jetpack_math_html() {
	foreach ( array( 'Automattic\\Jetpack\\Waf\\Brute_Force_Protection\\Brute_Force_Protection_Math_Authenticate', 'Jetpack_Protect_Math_Authenticate' ) as $c ) {
		if ( ! class_exists( $c ) || ! method_exists( $c, 'math_form' ) ) {
			continue;
		}
		try {
			$m = new ReflectionMethod( $c, 'math_form' );
			if ( ! $m->isStatic() ) {
				continue;
			}
			ob_start();
			$c::math_form();
			return '<div class="cnl-math">' . ob_get_clean() . '</div>';
		} catch ( Throwable $e ) {
			if ( ob_get_level() ) {
				ob_end_clean();
			}
		}
	}
	return '';
}

// wp_login_form() – formularz na stronie konta Pilot Logbook i w motywach
add_filter( 'login_form_middle', cnl_safe( function ( $html ) {
	return ( false === strpos( (string) $html, 'jetpack_protect' ) ) ? $html . cnl_jetpack_math_html() : $html;
} ), 20 );

// formularz logowania WooCommerce (Moje konto, kasa)
add_action( 'woocommerce_login_form', cnl_safe( function () {
	echo cnl_jetpack_math_html(); // phpcs:ignore WordPress.Security.EscapeOutput
} ), 20 );

/* ---------------------------------------------------------------- bez pamięci podręcznej na stronach z logowaniem
 * LiteSpeed / inne cache nie mogą podać zalogowanemu pilotowi kopii strony sprzed zalogowania
 * (z formularzem logowania albo nieważnym kluczem bezpieczeństwa – to też wygląda jak „drugie logowanie”).
 */
function cnl_page_needs_nocache() {
	if ( is_user_logged_in() ) {
		return true;
	}
	if ( ! is_singular() ) {
		return false;
	}
	$p = get_post();
	if ( ! $p ) {
		return false;
	}
	foreach ( array( 'pilot_logbook_konto', 'pilot_logbook_loty', 'pilot_logbook_aplikacja', 'aerohangar_login', 'woocommerce_my_account', 'cannonade_konto' ) as $sc ) {
		if ( has_shortcode( $p->post_content, $sc ) ) {
			return true;
		}
	}
	foreach ( array( 'plb_app_page', 'plb_account_page', 'aerohangar_login_page' ) as $opt ) {
		if ( (int) get_option( $opt ) === (int) $p->ID ) {
			return true;
		}
	}
	return function_exists( 'is_account_page' ) && is_account_page();
}

add_action( 'template_redirect', cnl_safe( function () {
	if ( ! cnl_page_needs_nocache() ) {
		return;
	}
	if ( ! defined( 'DONOTCACHEPAGE' ) ) {
		define( 'DONOTCACHEPAGE', true );
	}
	do_action( 'litespeed_control_set_nocache', 'cannonade-login' );
	nocache_headers();
} ), 0 );

/* ---------------------------------------------------------------- /logbook/ prowadzi do aplikacji, nie do pliku ZIP
 * W bibliotece mediów jest plik LogBook.zip – WordPress kierował adres /logbook/ do tego pliku.
 */
add_action( 'template_redirect', cnl_safe( function () {
	$path = trim( (string) wp_parse_url( isset( $_SERVER['REQUEST_URI'] ) ? wp_unslash( $_SERVER['REQUEST_URI'] ) : '', PHP_URL_PATH ), '/' );
	$path = preg_replace( '#^index\.php/#', '', $path );
	if ( 'logbook' !== strtolower( (string) $path ) ) {
		return;
	}
	$to = function_exists( 'plb_app_page_url' ) ? plb_app_page_url() : '';
	if ( ! $to ) {
		$id = (int) get_option( 'plb_app_page' );
		$to = $id && 'publish' === get_post_status( $id ) ? get_permalink( $id ) : '';
	}
	if ( $to && untrailingslashit( $to ) !== untrailingslashit( home_url( '/' . $path ) ) ) {
		wp_safe_redirect( $to );
		exit;
	}
} ), -10 );

/* Czyszczenie po wersji 1.0.0 (która próbowała sama tworzyć stronę aplikacji). */
add_action( 'admin_init', cnl_safe( function () {
	if ( false !== get_option( 'cnl_checked', false ) ) {
		delete_option( 'cnl_checked' );
	}
} ) );

/* ---------------------------------------------------------------- wygląd pola Jetpacka w formularzach na froncie */
add_action( 'wp_head', cnl_safe( function () {
	echo '<style>.cnl-math{margin:10px 0}.cnl-math input[type=number]{width:80px!important;padding:6px 8px;border:1px solid #c9d2de;border-radius:8px}</style>';
} ) );
