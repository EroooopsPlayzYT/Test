package com.eroooops.wifispeedtest.net

/**
 * A server the test can run against.
 *
 * [uploadUrl] is null for hosts that only serve a large static file — plenty of
 * public endpoints will feed you a gigabyte but have nowhere to POST to.
 */
data class Endpoint(
    val name: String,
    val downloadUrl: String,
    val uploadUrl: String?,
)

/**
 * Tried in order until one answers.
 *
 * A single hard-coded host is a single point of failure: some carriers filter
 * by SNI, so the TCP handshake succeeds and the TLS request that follows is
 * dropped — which looks exactly like a broken app. Spreading across unrelated
 * operators and countries means one blocked host no longer ends the test.
 */
object Endpoints {

    val BUILT_IN: List<Endpoint> = listOf(
        Endpoint(
            name = "Cloudflare",
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=200000000",
            uploadUrl = "https://speed.cloudflare.com/__up",
        ),
        Endpoint(
            name = "Hetzner",
            downloadUrl = "https://speed.hetzner.de/1GB.bin",
            uploadUrl = null,
        ),
        Endpoint(
            name = "OVH",
            downloadUrl = "https://proof.ovh.net/files/1Gb.dat",
            uploadUrl = null,
        ),
        Endpoint(
            name = "Tele2",
            downloadUrl = "https://speedtest.tele2.net/1GB.zip",
            uploadUrl = "https://speedtest.tele2.net/upload.php",
        ),
    )

    val DEFAULT: Endpoint = BUILT_IN.first()
}
