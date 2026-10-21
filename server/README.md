# AeroHangar – system zarządzania (serwer)

Panel do zarządzania danymi aplikacji: biuletyny, kontakty (dealerzy, serwisy, mechanicy, instruktorzy, egzaminatorzy), typy samolotów (prędkości, program obsługi) i komunikaty wyświetlane na karcie głównej. Do tego kopie zapasowe z przywracaniem, dziennik zmian, konta z rolami (admin / editor / viewer) i codzienny automat biuletynów TOMARK.

Aplikacje czytają zwykłe pliki `data/*.json` – serwer nie musi być mocny, wystarczy hosting z PHP.

## Wymagania
- PHP 8.1+ (bez baz danych i bibliotek), Apache z `.htaccess` lub nginx
- **HTTPS** (aplikacje nie pobierają danych po http)

## Instalacja na hostingu (np. tym, na którym stoi WordPress)
1. Przez FTP wgraj:
   - zawartość `public/` do folderu na stronie, np. `public_html/aerohangar/`
   - folder `private/` **obok** `public_html` (poza stroną). Jeśli hosting na to nie pozwala, wgraj go jako `public_html/aerohangar/private/` – plik `.htaccess` blokuje do niego dostęp.
2. Upewnij się, że `aerohangar/data/` i `private/state/` są zapisywalne (zwykle 755 wystarcza; jeśli nie, 775).
3. Otwórz `https://twoja-domena.pl/aerohangar/setup.php`, utwórz konto administratora. Dane startowe skopiują się same. Potem usuń `setup.php`.
4. Panel: `https://twoja-domena.pl/aerohangar/admin/`
5. Automat TOMARK – w panelu hostingu dodaj zadanie cron raz dziennie:
   `php /ścieżka/do/public_html/aerohangar/cron/fetch_tomark.php`
   albo wywołanie adresu `https://twoja-domena.pl/aerohangar/cron/fetch_tomark.php?key=…` (klucz `cronKey` jest w `private/state/config.php`).

## Instalacja w Dockerze (VPS)
```
docker compose up -d --build
```
Panel na porcie 8080 – postaw przed nim HTTPS (Caddy, Traefik albo nginx + Let's Encrypt). Dane i konfiguracja zostają w `volumes/`. Kontener `cron` sprawdza TOMARK raz dziennie.

## Automatyczne dane z GitHuba
Biuletyny (EASA, producenci, TOMARK – codziennie) i listy ULC (egzaminatorzy, organizacje obsługowe – co tydzień) zbiera automat na GitHubie. Żeby trafiały też na serwer, dodaj w repozytorium (Settings → Secrets and variables → Actions) dwa sekrety:
- `AH_SERVER` = `https://twoja-domena.pl/aerohangar/public` (adres folderu, w którym leżą `admin/` i `data/`)
- `AH_CRON_KEY` = wartość `cronKey` z `private/state/config.php`

Serwer dopisuje tylko nowe biuletyny, a listy ULC podmienia w całości – wpisy dodane w panelu zostają nietknięte.

## Przełączenie aplikacji na serwer
W repozytorium GitHub zmień `data/config.json`:
```json
{ "dataBaseUrl": "https://twoja-domena.pl/aerohangar/public/data" }
```
Każda zainstalowana aplikacja przy następnym otwarciu odczyta ten adres i od tej pory pobiera wszystko z Twojego serwera. Powrót: `"dataBaseUrl": null`.

## Jak przechowywane są dane
Bez bazy SQL – zwykłe pliki JSON (wystarczą przy tysiącach wpisów i nie wymagają konfiguracji):
- `data/*.json` – to, co pobierają aplikacje (tylko wpisy publiczne),
- `private/state/master_contacts.json`, `master_examiners.json` – pełna baza z wpisami ukrytymi,
- `private/state/submissions.json` – zgłoszenia z aplikacji, `installs.json` – anonimowy licznik instalacji,
- `private/state/backups/` – kopie przed każdą zmianą, `audit.log` – kto co zmienił.
Kopia zapasowa = skopiowanie folderów `data/` i `private/state/`.

## Bezpieczeństwo
- Hasła zapisane jako hash (`password_hash`), sesja tylko HTTPS/HttpOnly/SameSite, token CSRF przy każdym zapisie.
- Blokada po 8 nieudanych logowaniach z jednego adresu na 15 minut.
- Zapis jest walidowany (zła struktura nie trafi do aplikacji) i atomowy; poprzednia wersja idzie do kopii (40 na plik).
- Rób kopię folderu `private/state/` i `data/` razem z kopią strony.

## Zarabianie (partnerzy, zapytania, sponsor)
- **Partnerzy** – w dowolnym wpisie serwisu/dealera/szkoły wypełnij sekcję „Partner” (data „Partner do”, logo https, tekst promocyjny, „przyjmuje zapytania”). Aktywny partner jest w aplikacji na górze listy (do ~250 km), z wyróżnieniem. Zakładka „Partnerzy” pokazuje anonimowe statystyki (wyświetlenia, kliknięcia telefon/e-mail/strona/mapa, zapytania) i gotowy raport dla partnera.
- **Zapytania serwisowe** – przycisk „Umów przegląd” w aplikacji. Do partnera z zaznaczonym „przyjmuje zapytania” zapytanie idzie przez serwer e-mailem (PHP `mail()`), kopia na adres z „Ustawienia poczty”. Do pozostałych serwisów aplikacja otwiera gotowy e-mail w telefonie. Zapytania starsze niż 400 dni są usuwane.
- **Sponsor / baner** – jeden baner na karcie głównej (pierwszy aktywny w zakresie dat), z licznikiem wyświetleń i kliknięć.
- Pliki: `data/sponsor.json` (publiczny), `private/state/requests.json`, `events.json`, `settings.json`.
