// Use an integer for version numbers
version = 1

cloudstream {
    description = "Flix World adult sources: OK.XXX videos and FikFap Shorts"
    authors = listOf("FlixWorld")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    status = 3

    tvTypes = listOf("NSFW")
    language = "en"

    // Served from this repo once pushed.
    iconUrl = "https://raw.githubusercontent.com/Shabd45261/FlixWorld_Adult/main/assets/adult.png"

    isCrossPlatform = false
}

android {
    buildFeatures {
        buildConfig = true
    }
}
