# Prüfbericht

Stand: 27. November 2026.

## Bestätigte Ergebnisse

- Der Kotlin-Provider wurde gegen die aktuelle Cloudstream-Vorabversion kompiliert.
- Ein `.cs3`-Paket mit `manifest.json` und `classes.dex` wurde erzeugt. Der Manifest-Einstiegspunkt ist `de.joyncloudstream.JoynPlugin`.
- Die Projekttests prüfen unter anderem das Nachladen von Katalogseiten und Staffeln, die Auflösung von Poster-URLs und die Zuordnung von Zugangshinweisen.
- Der Browser-Katalog liefert separate Filter für Serien und Filme sowie zusätzliche Genre-Filter. Die Live-Tests verwenden dieselben Clientmethoden wie das Plugin und prüfen Bilddownloads sowie das Nachladen bis zur letzten Seite.
- Der Workflow erzeugt die Repository-Dateien und prüft Dateigröße, Prüfsumme und Downloadadressen vor der Veröffentlichung.

## Nicht als funktionierende Wiedergabe bestätigt

Die anonyme Anmeldung funktioniert. Die bisherige Live-Wiedergabediagnose erhielt von Joyn `ENT_AssetNotAvailableInCountry`. Der Anbieter zeigt diesen Fehler als Standortablehnung an. Android-Wiedergabe, Lizenzabruf, Ton und Untertitel bleiben separat zu prüfen.

Die aktuelle Webplayer-Anfragesignierung für die Playlist ist offen. Die Wiedergabediagnose protokolliert die tatsächliche Antwort des Joyn-Dienstes.

## Bereitstellung

Die [GitHub-Action](https://github.com/nerooo190/joyn-cloudstream/actions) veröffentlicht erfolgreiche Builds im Branch `builds`. Die für die Cloudstream-Installation benötigte [repo.json](https://raw.githubusercontent.com/nerooo190/joyn-cloudstream/builds/repo.json) befindet sich in diesem Branch.

## Build-Umgebung

- JDK 17
- Gradle 8.12 mit SHA-256-Prüfung des Wrappers
- Android SDK 35 und Build Tools 35.0.0
- Android Gradle Plugin 8.7.3
- Kotlin 2.4.0, passend zu den aktuellen API-Stubs
- R8/D8 9.5.28, explizit als Build-Abhängigkeit für aktuelle Kotlin-Metadaten
- NiceHttp 0.4.18 und Jackson 2.13.1

Die Cloudstream-Werkzeuge werden über `com.github.recloudstream:gradle:-SNAPSHOT` bezogen; die API-Stubs stammen aus `cloudstream:pre-release`. Diese Upstream-Dateien können sich nach diesem Prüfdatum ändern.
