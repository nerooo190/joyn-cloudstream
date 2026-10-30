# Prüfbericht

Stand: 30. Oktober 2026.

## Bestätigte Ergebnisse

- Der Kotlin-Provider wurde gegen die aktuelle Cloudstream-Vorabversion kompiliert.
- Ein `.cs3`-Paket mit `manifest.json` und `classes.dex` wurde erzeugt. Der Manifest-Einstiegspunkt ist `de.joyncloudstream.JoynPlugin`.
- 12 lokale Tests bestanden: 7 Clienttests und 5 Parsertests.
- Der optionale Live-Katalogtest benutzte die produktiven Kotlin-Clientmethoden und bestätigte 2 Inhaltsreihen auf der ersten Seite, weitere Reihen auf der zweiten Seite, 3 Suchtreffer für `K11`, 22 vollständig geladene Folgen einer Serie sowie die Detaildaten einer einzelnen Folge.
- Film-Detaildaten wurden zusätzlich direkt an der API geprüft.
- Die Repository-Dateien wurden lokal erzeugt und gegen Dateigröße, Hash und Downloadadressen geprüft.
- Der Veröffentlichungsablauf wurde mit einem lokalen Git-Remote simuliert: neuer `builds`-Branch beim ersten Lauf und normale Aktualisierung beim zweiten Lauf, ohne Force-Push.

## Nicht als funktionierende Wiedergabe bestätigt

Die anonyme Anmeldung funktioniert. Die Live-Wiedergabediagnose erhielt von Joyn jedoch `ENT_AssetNotAvailableInCountry`. Dieser Fehler wird als Standortablehnung angezeigt. Es wurde dabei **kein erfolgreich abgespieltes Video**, kein erfolgreicher Lizenzabruf und kein funktionierender Ton-/Untertitelpfad bestätigt.

Die aktuelle Webplayer-Anfragesignierung ist nicht implementiert. Die automatische Freigabeprüfung lehnte das Ausführen von verschleiertem Player-Code zur Untersuchung dieser Signierung wegen eines möglichen Eingriffs in Zugriffskontrollen ab. Dieser Schritt wurde nicht ausgeführt. Der vorhandene Client sendet eine reguläre token-authentifizierte Playlist-Anfrage ohne selbst rekonstruierte Signatur und meldet eine Ablehnung ausdrücklich.

Die beiden optionalen Live-Tests sind getrennt zu lesen: Der Katalogtest prüft Ergebnisse mit Assertions; die Wiedergabediagnose protokolliert auch eine Ablehnung und zählt nicht als Nachweis funktionierenden Streamings.

## Bereitstellung

GitHub verweigerte das Anlegen von `nerooo190/joyn-cloudstream` mit fehlender Berechtigung für `CreateRepository`. Deshalb wurde kein Remote-Repository erstellt oder veröffentlicht. Quellcode, lokales Git-Repository, Build-Artefakt und Workflow werden lokal bereitgestellt. Der Workflow wurde noch nicht auf GitHub ausgeführt.

Nach dem Hochladen in ein eigenes Repository erzeugt der Workflow die richtigen Repository-Adressen automatisch. Eine direkte Installation über Raw-URLs setzt deren Erreichbarkeit aus Cloudstream voraus.

## Build-Umgebung

- JDK 17
- Gradle 8.12 mit SHA-256-Prüfung des Wrappers
- Android SDK 35 und Build Tools 35.0.0
- Android Gradle Plugin 8.7.3
- Kotlin 2.4.0, passend zu den aktuellen API-Stubs
- R8/D8 9.5.28, explizit als Build-Abhängigkeit für aktuelle Kotlin-Metadaten
- NiceHttp 0.4.18 und Jackson 2.13.1

Die Cloudstream-Werkzeuge werden über `com.github.recloudstream:gradle:-SNAPSHOT` bezogen; die API-Stubs stammen aus `cloudstream:pre-release`. Diese Upstream-Dateien können sich nach diesem Prüfdatum ändern.
