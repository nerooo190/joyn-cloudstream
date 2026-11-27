version = 2

cloudstream {
    description = "Joyn Mediathek: Serien, Shows, Filme und Dokumentationen (DE). Wiedergabe experimentell."
    authors = listOf("Joyn Cloudstream Contributors")
    language = "de"
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.joyn.de/icons/apple-touch-icon-180x180.png"
    requiresResources = false
}

android {
    testOptions.unitTests.isReturnDefaultValues = true
}
