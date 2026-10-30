version = 1

cloudstream {
    description = "Joyn Mediathek: kostenlose Serien, Shows und Filme (DE). Experimentell."
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
