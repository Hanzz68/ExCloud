// use an integer for version numbers
version = 13

cloudstream {
    language = "id"

    // Deskripsi singkat ekstensi
    description = "Streaming anime dan donghua dari Auratail.vip"

    // Nama pembuat ekstensi
    authors = listOf("Zephyra77")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // Aktif dan berfungsi normal

    tvTypes = listOf(
        "AnimeMovie",
        "Anime"
    )

    // Gunakan favicon resmi Auratail
    iconUrl = "https://www.google.com/s2/favicons?domain=auratail.vip&sz=%size%"
}
