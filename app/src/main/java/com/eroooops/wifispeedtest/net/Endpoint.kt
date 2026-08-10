package com.eroooops.wifispeedtest.net

/**
 * A server the test can run against.
 *
 * [uploadUrl] is null for the many hosts that will happily feed you a gigabyte
 * but have nowhere to POST to.
 */
data class Endpoint(
    val name: String,
    val downloadUrl: String,
    val uploadUrl: String? = null,
)

/**
 * The cascade, tried until one actually serves bytes.
 *
 * Two deliberate properties:
 *
 * **Spread.** Twenty hosts across unrelated operators and countries, ordered
 * roughly by distance from Europe/Turkey. One blocked provider no longer ends
 * the test.
 *
 * **Mixed schemes.** Roughly a third are plain HTTP. That is the practical
 * answer to filtering that keys on the TLS server name: an http:// request
 * carries no SNI at all, so there is nothing for that kind of block to match.
 * (Encrypted Client Hello would be the principled fix, but Android exposes no
 * public API for setting an ECHConfig, and static-file hosts do not offer ECH
 * regardless.) Cleartext is permitted in the network security config.
 */
object Endpoints {

    val BUILT_IN: List<Endpoint> = listOf(
        Endpoint("Hetzner FRA", "https://speed.hetzner.de/100MB.bin"),
        Endpoint("Vultr FRA", "https://fra-de-ping.vultr.com/vultr.com.100MB.bin"),
        Endpoint("Leaseweb NL", "http://mirror.leaseweb.com/speedtest/100mb.bin"),
        Endpoint("Linode FRA", "https://speedtest.frankfurt.linode.com/100MB-frankfurt.bin"),
        Endpoint("OVH FR", "https://proof.ovh.net/files/100Mb.dat"),
        Endpoint("DO Frankfurt", "http://speedtest-fra1.digitalocean.com/100mb.test"),
        Endpoint("Online FR", "https://ping.online.net/100Mo.dat"),
        Endpoint("Vultr AMS", "https://ams-nl-ping.vultr.com/vultr.com.100MB.bin"),
        Endpoint("DO Amsterdam", "http://speedtest-ams2.digitalocean.com/100mb.test"),
        Endpoint("Linode LON", "https://speedtest.london.linode.com/100MB-london.bin"),
        Endpoint("ThinkBroadband UK", "http://ipv4.download.thinkbroadband.com/100MB.zip"),
        Endpoint("DO London", "http://speedtest-lon1.digitalocean.com/100mb.test"),
        Endpoint("Vultr LON", "https://lon-gb-ping.vultr.com/vultr.com.100MB.bin"),
        Endpoint(
            "Tele2 SE",
            "http://speedtest.tele2.net/100MB.zip",
            "http://speedtest.tele2.net/upload.php",
        ),
        Endpoint(
            "Tele2 SE (TLS)",
            "https://speedtest.tele2.net/100MB.zip",
            "https://speedtest.tele2.net/upload.php",
        ),
        Endpoint("Vultr PAR", "https://par-fr-ping.vultr.com/vultr.com.100MB.bin"),
        Endpoint("Linode NJ", "https://speedtest.newark.linode.com/100MB-newark.bin"),
        Endpoint("DO New York", "http://speedtest-nyc1.digitalocean.com/100mb.test"),
        Endpoint("Vultr NJ", "https://nj-us-ping.vultr.com/vultr.com.100MB.bin"),
        Endpoint("Linode SGP", "https://speedtest.singapore.linode.com/100MB-singapore.bin"),
    )

    /**
     * POST targets, tried after a download server is settled. Public endpoints
     * that accept a large body are far rarer than ones that serve a file, so a
     * run may legitimately end up download-only.
     */
    val UPLOAD_TARGETS: List<String> = listOf(
        "http://speedtest.tele2.net/upload.php",
        "https://speedtest.tele2.net/upload.php",
        "https://httpbin.org/post",
    )

    val DEFAULT: Endpoint = BUILT_IN.first()
    val DEFAULT_UPLOAD: String = UPLOAD_TARGETS.first()

    /**
     * Defaults shipped by earlier builds. Anything stored that matches one of
     * these is a leftover default rather than a deliberate choice, so it must
     * not suppress the cascade.
     */
    val LEGACY_DEFAULTS: Set<String> = setOf(
        "https://speed.cloudflare.com/__down?bytes=200000000",
        "https://speed.cloudflare.com/__up",
    )
}
