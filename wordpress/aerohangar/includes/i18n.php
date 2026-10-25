<?php
/**
 * Texts for the pilot-facing parts (account page, app answers) in the app languages: pl, en, de, cs, sk, fr.
 * Language = WordPress user / site locale; ?lang=xx overrides.
 */
defined('ABSPATH') || exit;

if (!function_exists('cannonade_lang')) {
    function cannonade_lang(): string
    {
        $q = isset($_GET['lang']) ? strtolower(substr(sanitize_key($_GET['lang']), 0, 2)) : '';
        $l = $q ?: strtolower(substr(function_exists('determine_locale') ? determine_locale() : get_locale(), 0, 2));
        return in_array($l, ['pl', 'en', 'de', 'cs', 'sk', 'fr'], true) ? $l : 'en';
    }

    function cannonade_t(string $k): string
    {
        static $T = null;
        if ($T === null) $T = [
            'title' => ['pl' => 'Konto Cannonade – AeroHangar i Pilot Logbook', 'en' => 'Cannonade account – AeroHangar and Pilot Logbook', 'de' => 'Cannonade-Konto – AeroHangar und Pilot Logbook', 'cs' => 'Účet Cannonade – AeroHangar a Pilot Logbook', 'sk' => 'Účet Cannonade – AeroHangar a Pilot Logbook', 'fr' => 'Compte Cannonade – AeroHangar et Pilot Logbook'],
            'intro' => ['pl' => 'Jeden klucz łączy Twoje konto z obiema aplikacjami. Wklej go w AeroHangar (O aplikacji → Konto) i w Pilot Logbook.', 'en' => 'One key links your account with both apps. Paste it into AeroHangar (About → Account) and into Pilot Logbook.', 'de' => 'Ein Schlüssel verbindet Ihr Konto mit beiden Apps. In AeroHangar (Über → Konto) und in Pilot Logbook einfügen.', 'cs' => 'Jeden klíč propojí účet s oběma aplikacemi. Vložte ho do AeroHangar (O aplikaci → Účet) a do Pilot Logbook.', 'sk' => 'Jeden kľúč prepojí účet s oboma aplikáciami. Vložte ho do AeroHangar (O aplikácii → Účet) a do Pilot Logbook.', 'fr' => 'Une seule clé relie votre compte aux deux applications. Collez-la dans AeroHangar (À propos → Compte) et dans Pilot Logbook.'],
            'make_key' => ['pl' => 'Utwórz klucz', 'en' => 'Create key', 'de' => 'Schlüssel erstellen', 'cs' => 'Vytvořit klíč', 'sk' => 'Vytvoriť kľúč', 'fr' => 'Créer la clé'],
            'new_key' => ['pl' => 'Nowy klucz', 'en' => 'New key', 'de' => 'Neuer Schlüssel', 'cs' => 'Nový klíč', 'sk' => 'Nový kľúč', 'fr' => 'Nouvelle clé'],
            'revoke' => ['pl' => 'Odłącz aplikacje', 'en' => 'Disconnect apps', 'de' => 'Apps trennen', 'cs' => 'Odpojit aplikace', 'sk' => 'Odpojiť aplikácie', 'fr' => 'Déconnecter les applications'],
            'confirm_new' => ['pl' => 'Nowy klucz unieważni stary – trzeba go będzie wkleić ponownie w obu aplikacjach. Kontynuować?', 'en' => 'A new key cancels the old one – you will paste it again in both apps. Continue?', 'de' => 'Ein neuer Schlüssel ersetzt den alten. Fortfahren?', 'cs' => 'Nový klíč zruší starý. Pokračovat?', 'sk' => 'Nový kľúč zruší starý. Pokračovať?', 'fr' => 'Une nouvelle clé annule l\'ancienne. Continuer ?'],
            'confirm_revoke' => ['pl' => 'Aplikacje stracą połączenie z kontem. Kontynuować?', 'en' => 'The apps will lose the link to your account. Continue?', 'de' => 'Die Apps verlieren die Verbindung. Fortfahren?', 'cs' => 'Aplikace ztratí spojení s účtem. Pokračovat?', 'sk' => 'Aplikácie stratia prepojenie. Pokračovať?', 'fr' => 'Les applications perdront le lien. Continuer ?'],
            'copy' => ['pl' => 'Kopiuj', 'en' => 'Copy', 'de' => 'Kopieren', 'cs' => 'Kopírovat', 'sk' => 'Kopírovať', 'fr' => 'Copier'],
            'shown_once' => ['pl' => 'Zapisz go teraz – ze względów bezpieczeństwa klucz widać tylko raz.', 'en' => 'Save it now – for security the key is shown only once.', 'de' => 'Jetzt speichern – der Schlüssel wird nur einmal angezeigt.', 'cs' => 'Uložte si ho – klíč se zobrazí jen jednou.', 'sk' => 'Uložte si ho – kľúč sa zobrazí len raz.', 'fr' => 'Enregistrez-la – la clé n\'est affichée qu\'une fois.'],
            'key_active' => ['pl' => 'Klucz aktywny (kończy się na %s).', 'en' => 'Key active (ends with %s).', 'de' => 'Schlüssel aktiv (endet auf %s).', 'cs' => 'Klíč aktivní (končí %s).', 'sk' => 'Kľúč aktívny (končí %s).', 'fr' => 'Clé active (se termine par %s).'],
            'no_key' => ['pl' => 'Nie masz jeszcze klucza.', 'en' => 'You have no key yet.', 'de' => 'Noch kein Schlüssel.', 'cs' => 'Zatím nemáte klíč.', 'sk' => 'Zatiaľ nemáte kľúč.', 'fr' => 'Pas encore de clé.'],
            'apps' => ['pl' => 'Aplikacje', 'en' => 'Apps', 'de' => 'Apps', 'cs' => 'Aplikace', 'sk' => 'Aplikácie', 'fr' => 'Applications'],
            'logbook_missing' => ['pl' => 'wtyczka Pilot Logbook nie jest jeszcze zainstalowana na tej stronie', 'en' => 'the Pilot Logbook plugin is not installed on this site yet', 'de' => 'Pilot-Logbook-Plugin noch nicht installiert', 'cs' => 'plugin Pilot Logbook zatím není nainstalován', 'sk' => 'plugin Pilot Logbook ešte nie je nainštalovaný', 'fr' => 'le plugin Pilot Logbook n\'est pas encore installé'],
            'howto' => ['pl' => 'Klucz daje dostęp tylko do Twoich danych w aplikacjach (kopia samolotów i wpisów, nalot z logbooka). Hasła do WordPressa aplikacje nie znają.', 'en' => 'The key only gives access to your app data (aircraft and entries backup, logbook hours). The apps never see your WordPress password.', 'de' => 'Der Schlüssel erlaubt nur Zugriff auf Ihre App-Daten. Ihr WordPress-Passwort kennen die Apps nicht.', 'cs' => 'Klíč dává přístup jen k datům aplikací. Heslo k WordPressu aplikace neznají.', 'sk' => 'Kľúč dáva prístup len k dátam aplikácií. Heslo k WordPressu aplikácie nepoznajú.', 'fr' => 'La clé ne donne accès qu\'aux données des applications. Elles ne connaissent pas votre mot de passe.'],
            'login_needed' => ['pl' => 'Zaloguj się, aby zarządzać kontem AeroHangar i Pilot Logbook.', 'en' => 'Log in to manage your AeroHangar and Pilot Logbook account.', 'de' => 'Anmelden, um das Konto zu verwalten.', 'cs' => 'Přihlaste se pro správu účtu.', 'sk' => 'Prihláste sa na správu účtu.', 'fr' => 'Connectez-vous pour gérer votre compte.'],
            'login' => ['pl' => 'Zaloguj się', 'en' => 'Log in', 'de' => 'Anmelden', 'cs' => 'Přihlásit', 'sk' => 'Prihlásiť', 'fr' => 'Connexion'],
            'register' => ['pl' => 'Załóż konto', 'en' => 'Create account', 'de' => 'Registrieren', 'cs' => 'Registrace', 'sk' => 'Registrácia', 'fr' => 'Créer un compte'],
            'ah_status' => ['pl' => 'kopia danych: %1$s, samoloty: %2$d%3$s', 'en' => 'data backup: %1$s, aircraft: %2$d%3$s', 'de' => 'Datensicherung: %1$s, Flugzeuge: %2$d%3$s', 'cs' => 'záloha: %1$s, letadla: %2$d%3$s', 'sk' => 'záloha: %1$s, lietadlá: %2$d%3$s', 'fr' => 'sauvegarde : %1$s, avions : %2$d%3$s'],
            'never' => ['pl' => 'jeszcze nie', 'en' => 'not yet', 'de' => 'noch nicht', 'cs' => 'zatím ne', 'sk' => 'zatiaľ nie', 'fr' => 'pas encore'],
            'paid_only' => ['pl' => 'Klucz do aplikacji dostajesz po wykupieniu dostępu (abonament miesięczny). Po opłaceniu zamówienia wróć tutaj i utwórz klucz.', 'en' => 'You get the app key after buying access (monthly plan). Once the order is paid, come back here and create the key.', 'de' => 'Den App-Schlüssel erhalten Sie nach dem Kauf eines Zugangs (Monatsabo). Nach der Zahlung hier den Schlüssel erstellen.', 'cs' => 'Klíč k aplikaci získáte po zakoupení přístupu (měsíční předplatné). Po zaplacení se sem vraťte a vytvořte klíč.', 'sk' => 'Kľúč k aplikácii získate po zakúpení prístupu (mesačné predplatné). Po zaplatení sa sem vráťte a vytvorte kľúč.', 'fr' => 'La clé est disponible après l\'achat d\'un accès (abonnement mensuel). Une fois payé, revenez ici pour créer la clé.'],
            'no_access_key' => ['pl' => 'Twoje konto nie ma aktywnego dostępu – klucz nie działa w aplikacjach, dopóki dostęp nie zostanie wykupiony lub przyznany.', 'en' => 'Your account has no active access – the key does not work in the apps until access is bought or granted.', 'de' => 'Kein aktiver Zugang – der Schlüssel funktioniert erst nach Kauf oder Freigabe.', 'cs' => 'Účet nemá aktivní přístup – klíč v aplikacích nefunguje.', 'sk' => 'Účet nemá aktívny prístup – kľúč v aplikáciách nefunguje.', 'fr' => 'Pas d\'accès actif – la clé ne fonctionne pas dans les applications.'],
            'buy' => ['pl' => 'Kup', 'en' => 'Buy', 'de' => 'Kaufen', 'cs' => 'Koupit', 'sk' => 'Kúpiť', 'fr' => 'Acheter'],
            'open_panel' => ['pl' => 'Otwórz panel AeroHangar', 'en' => 'Open the AeroHangar panel', 'de' => 'AeroHangar-Panel öffnen', 'cs' => 'Otevřít panel AeroHangar', 'sk' => 'Otvoriť panel AeroHangar', 'fr' => 'Ouvrir le panneau AeroHangar'],
            'access_active' => ['pl' => 'Dostęp AeroHangar aktywny', 'en' => 'AeroHangar access active', 'de' => 'AeroHangar-Zugang aktiv', 'cs' => 'Přístup AeroHangar aktivní', 'sk' => 'Prístup AeroHangar aktívny', 'fr' => 'Accès AeroHangar actif'],
            'access_unlimited' => ['pl' => '(bez limitu czasu)', 'en' => '(no end date)', 'de' => '(unbegrenzt)', 'cs' => '(bez omezení)', 'sk' => '(bez obmedzenia)', 'fr' => '(sans limite)'],
            'access_until' => ['pl' => 'do %s', 'en' => 'until %s', 'de' => 'bis %s', 'cs' => 'do %s', 'sk' => 'do %s', 'fr' => 'jusqu\'au %s'],
            'pro' => ['pl' => ', Pro aktywne', 'en' => ', Pro active', 'de' => ', Pro aktiv', 'cs' => ', Pro aktivní', 'sk' => ', Pro aktívne', 'fr' => ', Pro actif'],
        ];
        $l = cannonade_lang();
        return $T[$k][$l] ?? $T[$k]['en'] ?? $k;
    }
}
