package com.eroooops.wifispeedtest.net

/** Which radio a test runs over. */
enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ;

    companion object {
        fun fromName(name: String?): Transport =
            entries.firstOrNull { it.name == name } ?: WIFI
    }
}
