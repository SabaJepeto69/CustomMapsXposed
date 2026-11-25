# CustomMapsXposed (MapSkin)

An Xposed / LSPosed / Vector module that re-skins the maps inside other apps and swaps the player location icon.

## Features
- Pick which apps to change in Vector/LSPosed's scope.
- Original map themes: **Neon Night**, **Sunset Coast**, **Toon Day**, **Night Ops**.
- Player icons: neon arrow, glow orb, top-down car, or any image from your gallery.
- The settings app lists the apps on your phone that use maps it can change.

## Supported map engines
| Engine | Map style | Player icon |
|---|---|---|
| Mapbox Maps SDK v10/v11 | ✅ full style swap | ✅ replaces the 2D puck |
| MapLibre / legacy Mapbox (v9 and older) | ✅ full style swap | ❌ |
| Google Maps Android SDK | ✅ colour style | ✅ replaces the blue dot |
| Google Maps app, Waze | ❌ (they use their own engines) | ❌ |

Mapbox/MapLibre themes are built from the free [OpenFreeMap](https://openfreemap.org) "liberty" style, which the app downloads when you tap **Apply**.

## Usage
1. Install `MapSkin.apk`, enable it in Vector/LSPosed and tick your map apps in its scope.
2. Open **MapSkin**, pick a theme and an icon, then tap **Apply**.
3. Force-stop the map app and reopen it.
4. If nothing changes, check the Vector/LSPosed logs for lines starting with `MapSkin:`.

## Building (no Gradle needed)
On Debian/Ubuntu (works inside Termux proot-distro):
```sh
apt install openjdk-21-jdk-headless aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
./build.sh
```
Output: `build/MapSkin.apk`. `stubs/` holds compile-only Xposed API stubs; they are not packaged.
A signing keystore is generated on first build and is git-ignored.

## Credits
Map data © OpenStreetMap contributors, tiles/style by OpenFreeMap.
