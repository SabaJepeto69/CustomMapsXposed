#!/bin/sh
# Builds MapSkin.apk without Gradle: javac -> dx -> aapt -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/stubs build/classes build/dex

javac -J-Xmx384m -nowarn -source 8 -target 8 -bootclasspath "$SDK" -d build/stubs $(find stubs -name '*.java')
javac -J-Xmx384m -nowarn -source 8 -target 8 -bootclasspath "$SDK" -cp build/stubs -d build/classes $(find src -name '*.java')
dalvik-exchange -JXmx384m --dex --min-sdk-version=26 --output=build/dex/classes.dex build/classes

aapt package -f -M AndroidManifest.xml -A assets -I "$SDK" -F build/unsigned.apk
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk

[ -f mapskin.keystore ] || keytool -genkeypair -keystore mapskin.keystore -alias mapskin -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass mapskin -keypass mapskin -dname "CN=MapSkin" >/dev/null 2>&1
apksigner sign --ks mapskin.keystore --ks-pass pass:mapskin --key-pass pass:mapskin --out build/MapSkin.apk build/aligned.apk
apksigner verify build/MapSkin.apk && echo "Built build/MapSkin.apk"
