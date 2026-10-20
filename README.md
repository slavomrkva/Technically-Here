# Technically Here

Natívna Android aplikácia na evidenciu pracovného času a ďalších činností. Záznam spustíš ručne alebo automaticky podľa pripojenej Wi-Fi siete, GPS oblasti či ich kombinácie. Hodiny môžeš upravovať v tabuľke a exportovať do PDF alebo Markdownu.

Aplikácia funguje lokálne, bez účtu, servera, WebView a povolenia na internet. Rozhranie je v slovenčine. Čistá inštalácia začína bez činností a bez addonov.

## Čo vie

- Jeden formulár pre názov činnosti, dennú prestávku, zaokrúhľovanie a spúšťanie.
- Ručné spúšťanie, Wi-Fi podľa SSID, GPS s polomerom alebo Wi-Fi **alebo** GPS.
- Predvolený GPS polomer 200 m a hodinové čakanie pred ukončením záznamu.
- Návrat počas čakania zachová súvislý záznam; potvrdený odchod sa uloží spätne k začiatku výpadku.
- Mesačný prehľad s editáciou časov, činnosti a poznámok.
- PDF/Markdown export, výber stĺpcov a činností; s vlastným addonom aj týždenná šablóna.
- Vymazanie činnosti so zachovaním historických hodín a ich výpočtov.
- JSON záloha a obnova údajov.
- Deklaratívne addony pre vlastné polia, smeny, podpis a vzhľad výkazu. Addon nevykonáva kód.

Repozitár obsahuje základ aplikácie a syntetické testovacie dáta. Neobsahuje súkromné addony, firemné formuláre ani údaje používateľa.

## Používanie

Krátky návod je v [MANUAL.md](MANUAL.md). Formát vlastných addonov opisuje [ADDONS.md](app/src/main/appassets/ADDONS.md).

Pre automatické sledovanie otvor **Nastavenia → Spoľahlivé sledovanie**, povoľ presnú polohu, polohu Vždy a beh bez úspory batérie. Na Samsungu pridaj aplikáciu medzi Nikdy neuspávané aplikácie. Android si môže vyžiadať jednorazové potvrdenia; aplikácia ich nemôže nastaviť sama.

Sledovanie používa foreground službu s tichým systémovým oznámením. Pri povolenej polohe na pozadí obnovuje používateľom zapnuté sledovanie po reštarte a prvom odomknutí aj po aktualizácii. Vynútené zastavenie ho preruší do opätovného otvorenia aplikácie. Nepretržitý beh celé týždne nemožno garantovať; závisí aj od telefónu, povolení a úsporných režimov. Možné dlhšie medzery vo vyhodnocovaní sa zobrazia v aplikácii na kontrolu.

## Vývoj

- Android 8.0+ (minSdk 26), compileSdk/targetSdk 35.
- Java 17, Android Views, Gradle Wrapper 8.11.1.
- Android SDK Platform 35 a Build Tools 35.0.0.

Otvor projekt v Android Studiu alebo nastav `ANDROID_HOME` a spusti:

```sh
# Iba unit testy; nevytvorí inštalačné APK
./gradlew :app:testDebugUnitTest

# Kontrola zdrojov
./gradlew :app:lintDebug

# APK zostav iba vtedy, keď ho potrebuješ
./gradlew :app:assembleDebug
```

Debug build používa lokálny vývojový podpis. Pre distribúciu si nastav vlastný release podpis; podpisové kľúče nepatria do gitu. Alternatívny SDK-only postup je v `tools/build-local.sh`.

Testy pokrývajú výpočty času, kombinované spúšťanie, ochranu pred prekrývaním, export, stránkovanie, natívne formuláre, ukladanie nastavení, mazanie so zachovaním histórie a obnovu sledovania. Prístrojový PDF test vyžaduje emulátor alebo telefón. Overenie aktuálnych zdrojov opisuje [VERIFICATION.md](VERIFICATION.md).

## Licencia

[MIT](LICENSE), copyright © 2026 slavomrkva. Závislosti a Gradle Wrapper si zachovávajú svoje vlastné licencie.
