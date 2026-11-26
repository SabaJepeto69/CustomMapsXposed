# CustomMapsXposed (MapSkin) 2.0

An Xposed / LSPosed / Vector module that re-skins the maps inside other apps, swaps the player location icon and adds real 3D buildings.

## What's new in 2.0
- **Real 3D buildings** in apps that use Google Maps: drawn with OpenGL in true perspective over the map and kept in step with Google's camera every frame, so they rotate, tilt and zoom with the map. Falls back to a tile overlay if OpenGL or the map view isn't available.
- **Better building data**: footprints from Overture Maps (more complete than OpenStreetMap alone), with heights estimated from building type, size and neighbourhood where the data has none.
- **Game bundles**: *Neon City* (cyberpunk-game style 3D map + neon chevron) and *Sunshine Coast* (crime-game style map + radar arrow).
- **New app screen**: dark game-style design with map-preview theme cards, icon tiles, a list of map apps on the phone, and a launcher icon.

## Features
- Pick which apps to change in Vector/LSPosed's scope.
- Original map themes: **Neon City**, **Sunshine Coast**, **Neon Night**, **Sunset Coast**, **Toon Day**, **Night Ops**.
- Player icons: neon chevron, radar arrow, neon arrow, glow orb, top-down car, or any image from your gallery.
- 3D buildings switch: tilts the map and draws MapSkin's own buildings (Google's own 3D buildings are turned off).

## Supported map engines
| Engine | Map style | Player icon | 3D buildings |
|---|---|---|---|
| Mapbox Maps SDK v10/v11 | ✅ full style swap | ✅ replaces the 2D puck | tilt only |
| MapLibre / legacy Mapbox (v9 and older) | ✅ full style swap | ❌ | ❌ |
| Google Maps Android SDK | ✅ colour style, even in apps that rename/shrink the SDK (e.g. Bump) | ✅ only in apps that kept the SDK's class names | ✅ OpenGL layer |
| Google Maps app, Waze | ❌ (they use their own engines) | ❌ | ❌ |

Mapbox/MapLibre themes are built from the free [OpenFreeMap](https://openfreemap.org) "liberty" style, which the app downloads when you tap **Apply**.

## Usage
1. Install `MapSkin.apk`, enable it in Vector/LSPosed and tick your map apps in its scope.
2. Open **MapSkin**, pick a bundle or a theme and an icon, then tap **Apply**.
3. Force-stop the map app and reopen it.
4. If nothing changes, check the Vector/LSPosed logs (or `adb logcat -s MapSkin`) for lines starting with `MapSkin:`. They say whether the module is switched off, which map engine it found, and what it changed.

## Building (no Gradle needed)
On Windows with Android Studio's SDK and a JDK installed:
```powershell
.\build.ps1
```

On Debian/Ubuntu (works inside Termux proot-distro):
```sh
apt install openjdk-21-jdk-headless aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
./build.sh
```
Output: `build/MapSkin.apk`. `stubs/` holds compile-only Xposed API stubs; they are not packaged.
A signing keystore is generated on first build and is git-ignored.

## Credits
Map data © OpenStreetMap contributors, tiles/style by OpenFreeMap. 3D building footprints from [Overture Maps](https://overturemaps.org) (OSM, Microsoft, Google Open Buildings and others); heights are estimated where the data has none.
