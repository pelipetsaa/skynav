# SkyNav

SkyNav is an Android navigator for driving when GPS cannot be used. It keeps a position from cell towers, Wi-Fi, the phone's motion sensors, and the road network, and it can correct that position with a street-level photo.

The working target is 50–100 m. It does not aim at lane-level accuracy.

## How a drive works

The phone starts from the serving cell and nearby Wi-Fi access points. Signal strength is a weight, not a distance. An LTE timing-advance step is 78 m. A timing-advance value of 0 on some phones means the modem did not measure it.

Between radio fixes the phone leads the mark:

- The gyroscope holds the heading. Calibration starts after two seconds of standing still, and only after a second still reading agrees with the first.
- Distance comes from the learned forward axis of the accelerometer. Until that axis is reliable, distance does not grow. A speed scale learned from trusted GPS, in 10 km/h bands, is kept between trips.
- The mark stays on drivable roads loaded from OpenStreetMap. A bend under 35° is not treated as a turn. At an ambiguous junction the step stops and the screen asks for a mark.
- A long press drops a mark on the nearest road. A second long press within a minute sets the heading between the two marks. **Reverse** turns the heading 180°. **Pause** holds the mark.

GPS is a normal source while it agrees with the network and with how far the car could have driven. A jump under 1 km is ignored. Faster than 300 km/h is treated as a fake fix. After a loss, GPS is taken back only after a run of accurate fixes that agree with the mark, or after the car has followed the road at the same speed. **GPS off** stops applying new GPS fixes. The screen stays on while the car is moving.

## Street photos

With a [Mapillary](https://www.mapillary.com/developer) token, the rear camera takes one frame every few seconds. The frame is compared, on the phone, with nearby Mapillary photos that face the same way. The mark moves to the matched photo's road position only when that photo is clearly a better match than the others and is within 150 m. While GPS is the active source, photos do not move the mark.

Mapillary images are CC BY-SA 4.0. The map shows that attribution while the token is set.

## Map

The map is [MapLibre](https://maplibre.org/) with the OpenFreeMap Liberty style. Search uses Nominatim. Routes use the public OSRM driving profile and leave in the car's direction. Road geometry for the inertial guide comes from Overpass. These services need a network connection. The app does not ship an offline map pack.

## Setup

Create `local.properties` in the project root. Do not commit it.

```properties
sdk.dir=/path/to/Android/sdk
opencellid.token=
wigle.name=
wigle.token=
mapillary.token=
```

| Key | Used for |
| --- | --- |
| `opencellid.token` | [OpenCelliD](https://opencellid.org/) cell coordinates. OpenCelliD data is CC BY-SA 4.0. |
| `wigle.name`, `wigle.token` | [WiGLE](https://wigle.net/) Wi-Fi coordinates. |
| `mapillary.token` | Street photos. Create an app at the [Mapillary developer dashboard](https://www.mapillary.com/dashboard/developers). |

Blank tokens turn that lookup off. The app still runs.

Build and install a debug build:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:installDebug
```

The app needs location, phone state, notifications, and, for street photos, the camera. On first launch the foreground service must start after location permission is granted.

## Tests

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:testDebugUnitTest
```

Domain rules, including the GPS gates, the speed table, road matching, and the street-photo decision, are covered by unit tests. A real drive is still required before treating the photo-match thresholds as measured.

## Stack

Kotlin, Jetpack Compose, MapLibre, Room, TensorFlow Lite. Minimum SDK 29. `applicationId` is `com.skynav`.
