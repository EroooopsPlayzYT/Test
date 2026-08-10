# Wi-Fi Speed Test

An Android app that measures what your Wi-Fi connection actually delivers:
latency, jitter, download and upload throughput — alongside the radio details
(band, channel, signal, negotiated link rate) that explain the result.

Native Kotlin, Jetpack Compose, no third-party runtime dependencies.

## What it measures

| Metric | How |
| --- | --- |
| **Ping** | TCP handshake time to the test server. The hostname is resolved once up front so DNS never lands in a sample, and the first sample is dropped from the average because it also pays for ARP and route setup. |
| **Jitter** | Mean absolute difference between consecutive ping samples. |
| **Download** | Parallel HTTP GETs, bytes counted over a fixed window. |
| **Upload** | Parallel HTTP POSTs of random (incompressible) payload. |

Two details that make the numbers trustworthy:

- **Every socket is pinned to the Wi-Fi network.** Connections are opened
  through the Wi-Fi `Network` handle, so results describe the Wi-Fi link even
  when mobile data is up and Android would otherwise route around it.
- **The first 1.5 s of each transfer is discarded.** TCP slow start would
  otherwise drag the average well below the real rate.

The Wi-Fi card also reports the **negotiated link rate** from `WifiInfo`. That
is the PHY rate between the phone and the access point — it is the ceiling for
throughput, not a measurement of it, and it is normally much higher than the
speed you actually see.

## Testing the Wi-Fi vs. testing your internet

By default the app talks to Cloudflare's public endpoints
(`speed.cloudflare.com`), so it measures your internet connection *through*
Wi-Fi — whichever is slower is what you see.

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

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Runs the transfers. |
| `ACCESS_NETWORK_STATE` | Finds the Wi-Fi network and pins sockets to it. |
| `ACCESS_WIFI_STATE` | Reads signal, link rate, frequency. |
| `ACCESS_FINE_LOCATION` | **Optional.** Android only reveals the SSID/BSSID of the connected network to apps holding a location permission. Denying it hides the network name; everything else still works. |

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
├── net/            SpeedTestEngine — ping, download, upload; TestConfig
├── wifi/           WifiInfoProvider — live radio state via NetworkCallback
├── data/           ResultStore — JSON history + saved settings
└── ui/             Compose screen, gauge, settings sheet, ViewModel
```
