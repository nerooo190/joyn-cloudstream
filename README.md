# Joyn Mediathek für Cloudstream

Cloudstream-Repository für den deutschen Joyn-Mediathekskatalog. Der Anbieter zeigt Serien, Shows, Filme und Dokumentationen mit Vorschaubildern an.

**Status: Katalog funktionsfähig, Streaming noch nicht vollständig.** Die aktuelle Webplayer-Signierung für Playlist-Anfragen ist nicht implementiert. Der reguläre Ablauf mit anonymer Sitzung, Wiedergabeberechtigung und Playlist-Anfrage ist enthalten. Eine abgelehnte Anfrage erzeugt eine Fehlermeldung. Das Projekt ist daher noch kein zuverlässig abspielbares Joyn-Plugin.

## Funktionen und Grenzen

| Funktion | Stand |
| --- | --- |
| Startseite | Reihen für alle Serien und Shows, alle Filme, Dokumentationen sowie Kinder und Familie |
| Suche | Ergebnisse aus dem aktuellen Joyn-Suchindex, mit weiteren Seiten bei Bedarf |
| Serien und Shows | Alle von Joyn gemeldeten Staffeln und Folgen werden seitenweise geladen |
| Filme und Folgen | Detaildaten, Poster und Folgenbilder aus dem Joyn-Katalog |
| Joyn-Link in das Suchfeld einfügen | Unterstützt `/serien/…`, `/filme/…` und entsprechende `/play/…`-Links |
| Anonyme Sitzung und Wiedergabeberechtigung | Implementiert; Standort- und Zugangssperren werden angezeigt |
| DASH/HLS und Widevine-Übergabe an Cloudstream | Implementiert, nicht auf Android end-to-end bestätigt |
| Aktuelle Playlist-Signierung | **Offen; Streaming kann daran scheitern** |
| Login, Joyn+, Alters-PIN, Live-TV, Casting, Downloads | Nicht implementiert |

Der Katalog enthält auch Titel mit Joyn-Login oder Joyn+. Die Detailansicht zeigt den Zugangshinweis an, wenn Joyn ihn in den Metadaten meldet. Joyn prüft bei der Wiedergabe Anmeldung, Abo, Altersfreigabe und Standort. Anonyme Tokens bleiben im Arbeitsspeicher. Gespeicherte Episodendaten enthalten die Video-ID und den öffentlichen Inhaltslink. Sofern Joyn eine Lizenzadresse liefert, übernimmt Androids Widevine-CDM die Lizenzanfrage.

## In Cloudstream installieren

Unter **Einstellungen → Erweiterungen → Repository hinzufügen** diese URL eintragen:

```text
https://raw.githubusercontent.com/nerooo190/joyn-cloudstream/builds/repo.json
```

Danach **JoynProvider** installieren. Für eine vorhandene Installation unter **Erweiterungen** das Plugin aktualisieren. Ein erfolgreicher GitHub-Actions-Lauf veröffentlicht die neue Plugin-Version automatisch.

## Eigenes GitHub-Repository erstellen

1. Den Inhalt dieses Verzeichnisses in ein neues GitHub-Repository hochladen, zum Beispiel `DEIN_NAME/joyn-cloudstream`, Standardbranch `main`.
2. Unter **Settings → Actions → General** Actions aktivieren. Die Repository-/Organisationsrichtlinie muss dem Publish-Job `contents: write` erlauben. Der Workflow fordert dieses Recht selbst an.
3. Änderungen nach `main` pushen oder den Workflow unter **Actions → Build and publish Cloudstream repository → Run workflow** starten.
4. Der Workflow testet, baut `JoynProvider.cs3` und erstellt automatisch den Branch `builds`, auch beim ersten Lauf. Pull Requests bauen nur; sie veröffentlichen nichts.
5. In Cloudstream unter **Einstellungen → Erweiterungen → Repository hinzufügen** diese URL verwenden:

   ```text
   https://raw.githubusercontent.com/DEIN_NAME/joyn-cloudstream/builds/repo.json
   ```

6. `JoynProvider` installieren und den Anbieter **Joyn (experimentell)** auswählen.

Für einen Fork die URL der `repo.json` aus dessen `builds`-Branch verwenden. Der Workflow schreibt beim Build die passende GitHub-Adresse in diese Datei. Für die Installation müssen die Dateien aus Cloudstream erreichbar sein.

## Lokal bauen

Benötigt werden JDK 17 und ein Android-SDK mit `platforms;android-35` und `build-tools;35.0.0`. Den SDK-Pfad über `ANDROID_HOME` oder eine lokale, nicht eingecheckte `local.properties` mit `sdk.dir=/pfad/zum/android-sdk` setzen. Der Compiler nutzt Kotlin 2.4.0 passend zur aktuell geprüften Cloudstream-Vorabversion; zum Laden des Plugins eine aktuelle Cloudstream-Vorabversion verwenden.

```bash
chmod +x gradlew
./gradlew -Prepository=DEIN_NAME/joyn-cloudstream :JoynProvider:testDebugUnitTest make makePluginsJson
python3 tools/prepare_repository.py --repository DEIN_NAME/joyn-cloudstream
```

Windows: `gradlew.bat` statt `./gradlew`. Ergebnisse:

- Plugin: `JoynProvider/build/JoynProvider.cs3`
- Installierbares Repository: `build/repository/` mit `repo.json`, `plugins.json`, Plugin und `BUILD_INFO.json`
- Testbericht: `JoynProvider/build/reports/tests/testDebugUnitTest/index.html`

`-Prepository` bestimmt die URLs im Plugin-Index. In GitHub Actions wird dafür automatisch `GITHUB_REPOSITORY` verwendet. Das Exportskript überprüft URLs, Dateigröße und, sofern vorhanden, Prüfsumme. Ein Beispielname beim lokalen Build veröffentlicht nichts.

## Tests

Die normalen Tests prüfen die Katalog- und Folgenpagination, doppelte Suchtreffer, Bildformate, abgelaufene Sitzungen, Standortfehler und abgelehnte Playlists.

Optional lassen sich dieselben Kotlin-Clientmethoden gegen Joyn prüfen:

```bash
JOYN_LIVE_TESTS=1 ./gradlew :JoynProvider:testDebugUnitTest --rerun-tasks --tests '*JoynLiveTest*'
JOYN_FULL_CATALOG_TESTS=1 ./gradlew :JoynProvider:testDebugUnitTest --rerun-tasks --tests '*JoynLiveTest*'
JOYN_PLAYBACK_TESTS=1 ./gradlew :JoynProvider:testDebugUnitTest --rerun-tasks --tests '*JoynLiveTest*'
```

Die Tests prüfen der Reihe nach Stichproben mit Bilddownloads, die vollständige Pagination der Serien- und Filmreihen und die Antwort des Wiedergabedienstes. Für die Wiedergabediagnose lässt sich `JOYN_TEST_VIDEO_ID` mit einer aktuellen Joyn-Video-ID setzen. Die Wiedergabe auf einem Android-Gerät ist separat zu prüfen.

## Entwicklung

- `JoynClient.kt`: API-Abfragen, Suche, Sitzungen und Pagination; HTTP-Transport ist für Tests austauschbar.
- `JoynModels.kt`: Parsing, Linkprüfung und verständliche Dienstfehler.
- `JoynProvider.kt`: Cloudstream-Anbindung und Übergabe der Stream-/Lizenzadressen.
- `.github/workflows/build.yml`: Build, Test und Publikation ohne Force-Push.

Bei einer Änderung am Plugin die ganzzahlige `version` in `JoynProvider/build.gradle.kts` erhöhen, damit Cloudstream ein Update erkennt. Der Provider trägt zunächst den experimentellen Status `3`. Die Cloudstream-Build-Werkzeuge und API-Stubs werden wie im offiziellen Template bezogen; diese Upstream-Abhängigkeiten sind veränderlich. Die Gradle-Distribution ist per SHA-256 abgesichert.

Beobachtungen und Prüfergebnisse: [docs/VALIDATION.md](docs/VALIDATION.md). Nichtoffizielle Entwicklung; keine Verbindung zu Joyn oder ProSiebenSat.1. Namen, Logos und Medien gehören den jeweiligen Rechteinhabern. Die MIT-Lizenz gilt für den eigenen Quellcode, nicht für externe Inhalte oder Build-Abhängigkeiten.

## Quellen

- [Cloudstream: Einstieg für Erweiterungsentwickler](https://recloudstream.github.io/csdocs/devs/gettingstarted/)
- [Offizielles Plugin-Template](https://github.com/recloudstream/TestPlugins)
- [Cloudstream-Repositoryformat](https://recloudstream.github.io/csdocs/devs/create-your-own-json-repository/)
- [Cloudstream-Provider-API](https://github.com/recloudstream/cloudstream)
- [Joyn-Webfrontend](https://www.joyn.de/)
