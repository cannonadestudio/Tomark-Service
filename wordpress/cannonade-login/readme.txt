=== Cannonade – logowanie bez zaplecza ===
Contributors: Cannonade Łukasz Bełdowski
Requires at least: 5.8
Requires PHP: 7.4
Stable tag: 1.0.1
License: GPLv2 or later

Jedno logowanie dla Pilot Logbook, AeroHangar i sklepu WooCommerce.

* Pytanie matematyczne Jetpack Protect w każdym formularzu logowania na stronie – koniec z drugim logowaniem po stronie Jetpacka.
* Po zalogowaniu pilot / klient trafia od razu do logbooka (albo swojego konta).
* Piloci, mechanicy, szkoły i klienci sklepu nie widzą zaplecza WordPress ani górnego paska; wejście na /wp-admin przenosi do logbooka.
  Zaplecze widzą: administrator, redaktorzy, obsługa sklepu i zespół AeroHangar.
* Strony z logowaniem, kontem i logbookiem nie trafiają do pamięci podręcznej (LiteSpeed) – zalogowany pilot nie dostaje starej kopii z formularzem.
* Adres /logbook/ prowadzi do aplikacji logbooka, a nie do pliku LogBook.zip z biblioteki mediów; jeśli strona aplikacji zniknęła, tworzy się sama.

== Changelog ==
= 1.0.1 =
* Wtyczka nie tworzy już sama żadnej strony (wersja 1.0.0 dodawała stronę „Logbook” przy starcie WordPressa).
* Każdy hak jest zabezpieczony – błąd w nim nie wywołuje „krytycznego błędu” strony.
* /logbook/ prowadzi do aplikacji Pilot Logbook (pełny ekran), a nie do pliku LogBook.zip.

= 1.0.0 =
* Pierwsza wersja.
