=== AeroHangar ===
Contributors: Cannonade Łukasz Bełdowski
Requires at least: 6.2
Requires PHP: 8.1
Stable tag: 1.10.0
License: GPLv2 or later

Serwer aplikacji AeroHangar w WordPressie + konta pilotów (Konto Cannonade: jeden klucz dla AeroHangar i Pilot Logbook) + sprzedaż dostępu przez WooCommerce.

== Instalacja ==
1. Wtyczki → Dodaj nową → Wyślij wtyczkę na serwer → aerohangar.zip → Włącz.
2. AeroHangar → Import ze starego serwera: adres starego serwera (np. https://horyzontbezgranic.pl/aerohangar/public) i – opcjonalnie – pliki z jego folderu private/state (wpisy ukryte, instalacje, zapytania).
3. AeroHangar → Informacje: skopiuj fragment do data/config.json w GitHubie (dataBaseUrl + apiUrl) i sekrety AH_SERVER / AH_CRON_KEY. Od tej chwili aplikacje i automat korzystają z WordPressa.
4. Utwórz stronę „Konto” z kodem [cannonade_konto] (albo korzystaj z WooCommerce → Moje konto → AeroHangar).

== Panel ==
AeroHangar → Otwórz panel: biuletyny, typy, kontakty (serwisy, producenci, dealerzy, mechanicy, instruktorzy, szkoły, egzaminatorzy, lekarze orzecznicy, centra medyczne), partnerzy, zapytania serwisowe, sponsor, zgłoszenia, statystyki, instalacje i Pro, konta pilotów, historia i kopie, ustawienia.
Uprawnienia: administrator – wszystko; redaktor – edycja danych.

== Konta pilotów ==
Pilot loguje się do WordPressa, tworzy klucz (CNA-…) i wkleja go w AeroHangar (O aplikacji → Konto) oraz w Pilot Logbook. Klucz jest zapisany tylko jako skrót (HMAC), widać go raz.
Na koncie: kopia danych aplikacji (samoloty, wpisy, biuletyny), Pro dla wszystkich telefonów pilota.
Dla innych wtyczek (Pilot Logbook): cannonade_user_from_request(), filtry cannonade_account_apps, cannonade_logbook_hours, cannonade_user_from_key.

== WooCommerce ==
Produkt → sekcja „AeroHangar”: „Daje dostęp AeroHangar”, plan (pilot / mechanik / aeroklub), czas w dniach (0 = bez limitu).
Opłacone zamówienie → dostęp na koncie kupującego (kolejne zakupy przedłużają). Zwrot / anulowanie → dostęp z tego zamówienia odebrany. Obsługa WooCommerce Subscriptions. Zakup wymaga konta.
UWAGA (Google Play): w aplikacji z Google Play nie umieszczaj linków ani przycisków prowadzących do zakupu na stronie – Google wymaga wtedy płatności przez Google Play. Dostęp kupiony na stronie (np. przez mechaników, aerokluby) może działać w aplikacji.

== Dane ==
Publiczne: wp-content/uploads/aerohangar/data/*.json. Prywatne: tabela {prefix}aerohangar_store w bazie WordPressa (kopia bazy = kopia wszystkiego).

== Changelog ==
= 1.10.0 =
* Panel wewnątrz WordPressa: działy jako pozycje menu AeroHangar (Pulpit, Biuletyny, Typy, Komunikaty, Kontakty, Partnerzy i reklamy, Konta użytkowników, Klucze aplikacji, Zgłoszenia i statystyki, Historia, Ustawienia, Informacje i produkty).
* „Konta pilotów” → „Konta użytkowników” z kategoriami: piloci/właściciele, mechanicy, szkoły lotnicze, dealerzy, serwisy, personel panelu, bez dostępu.
= 1.9.0 =
* Codzienna automatyczna aktualizacja z GitHuba (WP-Cron, rano): biuletyny i ich grupy, typy samolotów, katalog silników, listy ULC – bez sekretów GitHuba. Data ostatniej i następnej aktualizacji na stronie AeroHangar → Informacje.
= 1.8.2 =
* Naprawa: kopia danych z konta nie dawała się wczytać w aplikacji (puste pola speedOverrides/taskOverrides wysyłane jako [] zamiast {}).
= 1.8.1 =
* Nowe typy: Piper, Cessna, Cirrus, PZL (Wilga, Koliber, Iskierka), Socata (TB, Rallye) i silniki Lycoming, Continental, PZL-Franklin, AI-14RA. „Pobierz z GitHuba” w panelu pobiera też katalog silników.
= 1.8.0 =
* Panel → Konta pilotów: edycja danych konta (nazwa, imię, nazwisko, e-mail, telefon, rola WordPress), link do zmiany hasła, usuwanie kont (nie administratorów), „+ Nowe konto”.
= 1.7.1 =
* Jeden klucz dla AeroHangar i Pilot Logbook: klucz konta to osobisty klucz Pilot Logbook (plb_…); tworzenie, wymiana i unieważnianie działa w obu wtyczkach naraz. Klucze CNA-… nadal działają.
= 1.7.0 =
* AeroHangar → Klucze aplikacji (też Użytkownicy → Klucze aplikacji): wszystkie klucze widoczne w całości, kopiowanie, generowanie, wymiana, unieważnianie, wyszukiwarka, „Utwórz brakujące klucze”, wymiana starszych kluczy. Kolumna z kluczem na liście użytkowników i w panelu → Konta pilotów.
= 1.6.0 =
* Wygląd w stylu lot.com: granat, biel, jasne tło (panel, logowanie, konto).
* Limity: aplikacja bezpłatna dla 1 samolotu; właściciel do 3 samolotów, mechanik, szkoła, dealer i serwis bez limitu (informacja przy ofercie).
= 1.5.1 =
* Cały klucz aplikacji zawsze widoczny: strona konta, Moje konto i menu użytkownika (górny pasek) z kopiowaniem.
* engines.json (silniki) w danych dla aplikacji.
= 1.5.0 =
* Zaplecze dla klientów: piloci, mechanicy, szkoły, dealerzy i serwisy logują się do panelu (/index.php/aerohangar-panel) i widzą tylko działy przyznane przez administratora (podgląd lub edycja).
* Moje samoloty: samoloty z aplikacji, biuletyny spoza systemu i statusy biuletynów edytowane w przeglądarce, synchronizowane z aplikacją.
= 1.4.0 =
* Logowanie klientów: strona „AeroHangar – logowanie” ([aerohangar_login]) – status, klucz, flota z CSV, zapytania serwisowe, edycja wizytówki, statystyki.
* Możliwości konta ustawiane przez administratora (domyślnie według planu) i połączenie konta firmy z wizytówką w katalogu.
= 1.3.0 =
* Dostęp tylko płatny: klucz tylko dla kont z zakupem w WooCommerce lub dostępem przyznanym ręcznie; bez dostępu – oferta z przyciskami „Kup”, a stary klucz przestaje działać (HTTP 402).
* Szybkie przejście do panelu: pasek górny, przycisk w Moim koncie, adres /aerohangar-panel, klasy menu ah-panel / ah-guest / ah-user.
= 1.2.0 =
* AeroHangar → Informacje: przycisk „Utwórz / uzupełnij produkty AeroHangar” – 5 abonamentów miesięcznych (Pilot 10 zł, Mechanik 35 zł, Szkoła lotnicza 400 zł, Dealer 500 zł, Serwis 500 zł netto). Z WooCommerce Subscriptions – automatyczna płatność co miesiąc.
* Nowe plany: szkoła lotnicza, dealer, serwis.
* Limit samolotów i własnych typów w produkcie i przy koncie (konto ma pierwszeństwo).
= 1.1.1 =
* Naprawa krytycznego błędu przy instalacji (aktualizacja danych na „init”).
