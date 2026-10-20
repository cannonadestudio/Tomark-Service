# Viper & Skyper Pilot

Aplikacja Android dla właścicieli i pilotów samolotów TOMARK **Viper SD-4** (RTC, Night-VFR, UL/LSA) i **Skyper GT9**.
Nieoficjalna – niezwiązana z TOMARK s.r.o.

## Co robi

| Karta | Zawartość |
|---|---|
| **Karta** | Najważniejsze prędkości (VS0, VS1, podejście, VFE, VA, VC, VNE…) w kt / km/h, nalot, najbliższe prace, biuletyny bez statusu |
| **Instrukcja** | Ograniczenia z TCDS (masy, CG, obciążenia, klapy, napęd, płyny), edycja prędkości z własnej AFM, dołączony PDF instrukcji (offline), notatki |
| **Serwis** | Program obsługi EASA lub UL: terminy w godzinach i miesiącach („co nastąpi wcześniej”), tolerancje wg SB_SD4-03-2023, historia, własne pozycje |
| **Biuletyny** | AD / ASB / SB / SIB przypisane do typu, status wykonany / nie dotyczy, codzienne pobieranie nowej listy i powiadomienia |

Wiele samolotów, 4 języki (PL, EN, SK, FR – wg języka telefonu).

## Pierwsze uruchomienie (budowa APK)

1. Załóż na koncie **cannonadestudio** publiczne repozytorium **Tomark-Service** (publiczne – aplikacja czyta z niego listę biuletynów).
2. Wgraj zawartość tego folderu (np. `git init && git add . && git commit -m init && git remote add origin https://github.com/cannonadestudio/Tomark-Service.git && git push -u origin main`).
3. W zakładce **Actions** uruchomi się „Build APK” – po kilku minutach pobierzesz `TomarkPilot-apk` (debug i release).

Release jest podpisany kluczem debug – przed publikacją w Google Play podmień `signingConfig` na własny keystore.

## Jak opublikować nowy biuletyn

1. Dopisz wpis w `tools/build_data.py` (lista `bulletins`) – `id`, data, tytuł, grupy (`SD4_RTC`, `SD4_NVFR`, `SD4_UL`, `GT9`), rodzaj, obowiązkowy.
2. `python3 tools/build_data.py` (generuje `data/bulletins.json` i dane wbudowane).
3. Commit + push. Telefony pobiorą listę w ciągu doby i pokażą powiadomienie – bez wydawania nowej wersji.

Teksty interfejsu: `tools/build_strings.py` → `python3 tools/build_strings.py`.

## Pochodzenie danych

**Potwierdzone (verified):**
- Prędkości, masy, CG, obciążenia, klapy, napęd, płyny Viper SD-4 RTC / Night-VFR – EASA TCDS A.606 Issue 4 (18.03.2025).
- Przeglądy EASA 25 h / 50 h / 100 h / 2000 h i tolerancje (±1,25 / ±2,5 / ±5 / ±100 h, liczenie od terminu, nie od wykonania) – SB_SD4-03-2023, SB-SD4-01-2024.
- Przegląd 100 h / 1 rok dla Viper UL – SB_LŠZ_SD4-03-2017.
- ARC co 12 mies. – Part-ML.
- Lista biuletynów – strona wsparcia technicznego TOMARK (stan 20.10.2026), EASA AD 2026-0170, Rotax ASB-2026-001.

**Do weryfikacji (⚠ w aplikacji):**
- Prędkości Viper UL i Skyper GT9 – dane marketingowe producenta / Wikipedia, nie AFM.
- Prędkość podejścia – obliczona 1,3×VS0.
- VR, VX, VY, najlepsze szybowanie – brak w źródłach publicznych, do wpisania z AFM.
- Interwały Rotax (olej 100 h/12 mies., świece 200 h, reduktor 1000 h, guma 5 lat, TBO 2000 h/15 lat), przegląd roczny EASA, przepisy krajowe UL – do potwierdzenia z AMM i Rotax MML-912.
- Śmigło i spadochron ratowniczy – interwał do ustawienia wg instrukcji producenta.
- Status „obowiązkowy” przy części SB – tam gdzie nieznany, aplikacja pokazuje „Sprawdź dokument”.

Wiążące są zawsze aktualne AFM, AMM, biuletyny i dyrektywy samolotu.
