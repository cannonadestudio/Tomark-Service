# AeroHangar

Aplikacja Android dla właścicieli i pilotów samolotów z silnikami **Rotax 912** – TOMARK Viper SD-4 / Skyper GT9 i 23 inne typy (Tecnam, Aquila, Diamond, Flight Design, WT9, Breezer, Bristell, C42, Aeroprakt, Shark, FK9, EV-97, Remos, Pipistrel, Faeta, VL3, Savage). Nieoficjalna – niezwiązana z producentami ani BRP-Rotax.

| Karta | Zawartość |
|---|---|
| **Karta** | Prędkości w siatce 3 kolumny (kt / km/h), nalot (także z logbooka), najbliższe prace, otwarte biuletyny |
| **Instrukcja** | Dane z TCDS / kart typu, własne wartości z AFM, PDF instrukcji offline, notatki |
| **Serwis** | Program EASA lub UL + Rotax wg MML-912, tolerancje, historia z edycją wpisów, własne pozycje |
| **Biuletyny** | TOMARK, Rotax (grupa ROTAX_912), EASA AD – status wykonany / nie dotyczy |
| **Kontakty** | Dealerzy, serwisy Rotax, mechanicy, instruktorzy, egzaminatorzy – najbliżsi wg GPS, import/eksport |

## Skąd aplikacja bierze dane (zawsze aktualne)

Aplikacja pobiera `data/bulletins.json`, `data/contacts.json` i `data/types.json` z tego repo **przy każdym otwarciu i raz dziennie**. Te same pliki są wbudowane w APK na wypadek braku sieci.

- **Panel webowy** `docs/admin/` – dodawanie, edycja i usuwanie biuletynów, kontaktów (w tym mechaników, instruktorów, egzaminatorów) i typów samolotów. Zapis idzie prosto do `data/*.json`.
- **Automat** `.github/workflows/update-bulletins.yml` – codziennie czyta publiczną listę wsparcia technicznego TOMARK i dopisuje nowe dokumenty.
- Dane osobiste (samoloty, nalot, wpisy serwisowe, własne kontakty) zostają tylko w telefonie użytkownika.

### Uruchomienie panelu
1. Repo → **Settings → Pages** → Source: *Deploy from a branch*, branch `main`, folder `/docs` → Save.
2. Panel: `https://cannonadestudio.github.io/Tomark-Service/admin/`
3. GitHub → **Settings → Developer settings → Fine-grained tokens** → nowy token: tylko repo *Tomark-Service*, uprawnienie *Contents: Read and write*. Wklej go w panelu (zostaje w przeglądarce).

## Logbook

Każdy użytkownik wskazuje w Ustawieniach (ⓘ) własny plik eksportu logbooka – CSV (`;`, `,` lub tab) albo JSON z kolumną znaków i kolumną czasu lotu (`1:30`, `1,5`, minuty). Przy samolocie zaznacza „Pobieraj nalot z logbooka”: obecny nalot zostaje, kolejne loty są doliczane przy każdym otwarciu i codziennie.

## Budowa

Każdy push do `main` buduje APK w Actions (artefakt `TomarkPilot-apk`). `tools/build_data.py --bootstrap` odtwarza dane od zera – **nadpisuje zmiany z panelu**, normalnie nieużywany.

## Pochodzenie danych

Prędkości z EASA TCDS, kart typu DULV/DAeC/Traficom/BMAA i POH producentów; Rotax wg MML-912 Ed.4 Rev.1 (olej/świece/reduktor z opracowań RAANZ – do potwierdzenia). Wartości ⚠ są niepotwierdzone. Dealerzy i serwisy ze stron dystrybutorów Rotax i producentów (stan 20.10.2026, współrzędne przybliżone). Wiążące są zawsze AFM, AMM, biuletyny i dyrektywy samolotu.
