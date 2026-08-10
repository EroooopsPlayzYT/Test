# Speed Test

An Android app that measures what your connection actually delivers — over
**Wi-Fi or mobile data** — reporting latency, jitter, download and upload
throughput alongside the radio details that explain the result.

Native Kotlin, Jetpack Compose, no third-party runtime dependencies.

Pick the transport at the top of the screen. The status card and the recorded
history follow the selection, so Wi-Fi and cellular runs stay comparable but
never get confused for one another.

## What it measures

| Metric | How |
| --- | --- |
| **Ping** | TCP handshake time to the test server. The hostname is resolved once up front so DNS never lands in a sample, and the first sample is dropped from the average because it also pays for ARP and route setup. |
| **Jitter** | Mean absolute difference between consecutive ping samples. |
| **Download** | Parallel HTTP GETs, bytes counted over a fixed window. |
| **Upload** | Parallel HTTP POSTs of random (incompressible) payload. |

Three details that make the numbers trustworthy:

- **Every socket is pinned to the transport under test.** Connections are opened
  through that transport's `Network` handle, so a Wi-Fi run cannot silently
  measure cellular, or the reverse, whichever one Android happens to prefer.
- **Mobile data is requested and held for the test.** While Wi-Fi is connected
  the cellular radio is usually idle, so a cellular run files an explicit
  `requestNetwork` for the duration and releases it afterwards. Without that,
  testing mobile data without first turning Wi-Fi off would not work at all.
- **The first 1.5 s of each transfer is discarded.** TCP slow start would
  otherwise drag the average well below the real rate.

The Wi-Fi card reports the **negotiated link rate** from `WifiInfo`; the mobile
card reports the platform's **downlink estimate**. Both are the connection's own
claim about itself — a ceiling, not a measurement — and both normally sit well
above the speed you actually get.

## Mobile data costs data

A full test moves as much data as the link allows, which on a fast 5G connection
can be hundreds of megabytes per run. The app is explicit about this rather than
quiet:

- Selecting **Mobile data** shows a notice before you start, and a louder one
  while roaming.
- Bytes transferred are counted live during the run and stored with each history
  entry, so you can see exactly what a test cost.
- Shortening the per-direction duration in settings is the direct lever — 5 s
  instead of 10 s roughly halves the bill.

## Server fallback

A single hard-coded test server is a single point of failure. Some carriers
filter by TLS server name (SNI): the TCP handshake succeeds and the request that
follows is silently dropped, so the ping phase passes and the download returns
nothing — which looks exactly like a broken app rather than a blocked host.

So each run begins by probing **twenty servers** across unrelated operators and
countries, roughly ordered by distance from Europe, and uses the first that
actually serves bytes. Two design points:

- **The probe pulls real payload**, not just a socket connection. A connect-only
  check is precisely what SNI filtering fools.
- **About a third of the servers are plain HTTP.** An `http://` request carries
  no SNI at all, so there is nothing for that class of block to match. This is
  the practical answer to SNI filtering: Encrypted Client Hello would be the
  principled one, but Android exposes no public API for setting an ECHConfig,
  and static-file hosts do not offer ECH regardless.

Probing runs five at a time. A blocked host costs a full timeout, and twenty of
those in series would be two minutes of waiting; batching keeps it to seconds
while still honouring preference order.

The chosen server is shown under the gauge and stored with each history entry,
since runs against different servers are not comparable.

If no upload target is reachable, the test measures download only rather than
failing outright — public endpoints that accept a large POST are far rarer than
ones that serve a file. If nothing at all can be reached, the error names the
first few servers and why each was rejected.

Fallback applies only while the URLs are untouched. Point the app at your own
LAN box and it uses that or reports an error — silently hopping onto the
internet and presenting the result as your local link would be worse than
failing.

## Testing the link vs. testing your internet

By default the app talks to Cloudflare's public endpoints
(`speed.cloudflare.com`), so it measures your internet connection *through*
the selected transport — whichever is slower is what you see.

To measure the Wi-Fi link itself, open the settings sheet and point the download
and upload URLs at a machine on your own LAN. Anything that serves a large file
and accepts a POST works; for example, from a laptop on the same network:

```sh
# serve a 1 GB file of zeroes on port 8080
head -c 1G /dev/zero > big.bin
python3 -m http.server 8080
```

then set the download URL to `http://<laptop-ip>:8080/big.bin`. Cleartext HTTP
is permitted for exactly this reason (see `network_security_config.xml`).

A LAN target only makes sense for Wi-Fi. On mobile data the phone is not on your
network, so leave the public endpoints in place there.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Runs the transfers. |
| `ACCESS_NETWORK_STATE` | Finds the Wi-Fi and mobile networks and pins sockets to the one under test. |
| `ACCESS_WIFI_STATE` | Reads signal, link rate, frequency. |
| `CHANGE_NETWORK_STATE` | Asks the system to bring mobile data up for a cellular test. Normal permission, granted at install. |
| `ACCESS_FINE_LOCATION` | **Optional.** Android only reveals the SSID/BSSID of the connected network to apps holding a location permission. Denying it hides the network name; everything else still works. |
| `READ_PHONE_STATE` | **Optional.** Same idea on the cellular side: the carrier name is free, but the radio technology (LTE / 5G NR) needs this. Denying it hides that one field. |

## Building

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. CI builds every push and
attaches the APK as a workflow artifact.

Requires JDK 17 and the Android SDK (compileSdk 35). Minimum supported device is
Android 7.0 (API 24).

## Layout

```
app/src/main/java/com/eroooops/wifispeedtest/
├── MainActivity.kt
├── net/            SpeedTestEngine — ping, download, upload; TestConfig, Transport
├── wifi/           WifiInfoProvider — live Wi-Fi state via NetworkCallback
├── cell/           CellularInfoProvider — mobile state, and holding the radio up
├── data/           ResultStore — JSON history + saved settings
└── ui/             Compose screen, gauge, settings sheet, ViewModel
```
