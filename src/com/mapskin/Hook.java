package com.mapskin;

import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    static final String PKG = "com.mapskin";
    static final String TAG = "MapSkin: ";

    static String theme, gJson, mbJson, iconId, iconB64, styleUri;
    static Bitmap icon;
    static boolean iconLoaded, tilt3d;
    static final float TILT = 55f;   // degrees; Google and Mapbox clamp it at low zoom
    static ClassLoader cl;
    static final Map<Object, Object> markers = new WeakHashMap<Object, Object>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (PKG.equals(lp.packageName) || !lp.isFirstApplication) return;
        XSharedPreferences sp = new XSharedPreferences(PKG, "prefs");
        sp.reload();
        File pf = null;
        try { pf = sp.getFile(); } catch (Throwable ignored) {}
        if (pf == null || !pf.canRead()) {
            log(lp.packageName + ": can't read MapSkin settings (" + pf + "). Open MapSkin once and tap Apply.");
            return;
        }
        if (!sp.getBoolean("enabled", true)) {
            log(lp.packageName + ": re-skinning is switched off in MapSkin, doing nothing");
            return;
        }
        theme = sp.getString("theme", "neon");
        mbJson = sp.getString("mbjson", null);
        iconId = sp.getString("icon", "off");
        iconB64 = sp.getString("icon_b64", null);
        tilt3d = sp.getBoolean("tilt3d", false);
        cl = lp.classLoader;
        log("loaded into " + lp.packageName + ": theme=" + theme + " icon=" + iconId + " 3D=" + tilt3d
                + " mapboxStyle=" + (mbJson == null ? "missing (tap Apply with internet on)" : mbJson.length() + " chars"));
        try { gJson = Themes.google(theme); } catch (Throwable t) { log(t); }

        safe(new Runnable() { public void run() { hookGoogle(); } });
        safe(new Runnable() { public void run() { hookGoogleEngine(); } });
        safe(new Runnable() { public void run() { hookMapbox(); } });
        safe(new Runnable() { public void run() { hookMapLibre("org.maplibre.android.maps"); } });
        safe(new Runnable() { public void run() { hookMapLibre("com.mapbox.mapboxsdk.maps"); } });
        safe(new Runnable() { public void run() { hookMapboxPuck(); } });
        if (tilt3d) safe(new Runnable() { public void run() { hookMapboxTilt(); } });
    }

    // ---------------- 3D view: tilt the camera so 3D buildings stand up ----------------

    /** Mapbox v10/v11: every camera change goes through a CameraOptions; give each one our pitch. */
    static void hookMapboxTilt() {
        Class<?> co = find("com.mapbox.maps.CameraOptions");
        if (co == null) return;
        // Only the opening camera moves (app start + first jump to your location) get tilted; after that the
        // camera belongs to the user, so dragging, pinching and tilting work normally.
        final long until = android.os.SystemClock.uptimeMillis() + 15000;
        int n = 0;
        for (final Constructor<?> k : co.getDeclaredConstructors()) {
            final java.util.List<Integer> dbl = new java.util.ArrayList<Integer>();
            Class<?>[] pt = k.getParameterTypes();
            for (int i = 0; i < pt.length; i++) if (pt[i] == Double.class) dbl.add(i);
            if (dbl.size() != 3) continue;                     // (center, padding, anchor, zoom, bearing, pitch)
            final int pitch = dbl.get(2);
            XposedBridge.hookAllConstructors(co, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length <= pitch || !(p.method instanceof Constructor)
                            || !java.util.Arrays.equals(((Constructor<?>) p.method).getParameterTypes(), k.getParameterTypes())) return;
                    if (android.os.SystemClock.uptimeMillis() > until) return;
                    Object v = p.args[pitch];
                    if (v == null || ((Double) v) < TILT) p.args[pitch] = (double) TILT;
                }
            });
            n++;
            break;
        }
        if (n > 0) log("Mapbox 3D view on (pitch " + TILT + "°)");
    }

    // Google: camera moves go through the engine's camera-update factory, found the same way as the map engine.
    // Fixed transaction numbers: map 1 = getCameraPosition, map 4 = moveCamera, factory 7 = newCameraPosition.
    static final String CAMERA_FACTORY = "com.google.android.gms.maps.internal.ICameraUpdateFactoryDelegate";
    static volatile android.os.Binder cameraFactory;
    static final java.util.Set<Class<?>> cameraClassesHooked = new java.util.HashSet<Class<?>>();

    /** The renamed CameraPosition: a Parcelable with one object field (LatLng target) and three floats (zoom, tilt, bearing). */
    static boolean isCameraPosition(Class<?> p) {
        if (p.isInterface() || !android.os.Parcelable.class.isAssignableFrom(p)) return false;
        try { p.getDeclaredField("CREATOR"); } catch (NoSuchFieldException e) { return false; }
        int floats = 0, objs = 0, others = 0;
        for (Field f : p.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == float.class) floats++;
            else if (!f.getType().isPrimitive()) objs++;
            else others++;
        }
        return floats == 3 && objs == 1 && others == 0;
    }

    static Class<?> cameraPositionClass(Object factory) {
        for (Class<?> k = factory.getClass(); k != null && k != android.os.Binder.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && isCameraPosition(pt[0])) return pt[0];
            }
        return null;
    }

    static final float BUILDINGS_ZOOM = 17f;          // Google only draws 3D buildings from about street level
    static final ThreadLocal<Boolean> tilting = new ThreadLocal<Boolean>();

    /**
     * CameraPosition(target, zoom, tilt, bearing). Only our own one-time camera move is changed (tilt + zoom in close
     * enough for 3D buildings); positions built while the user drags are left alone so gestures keep working.
     */
    static synchronized void hookCameraPosition(Class<?> cp) {
        if (!cameraClassesHooked.add(cp)) return;
        XposedBridge.hookAllConstructors(cp, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!Boolean.TRUE.equals(tilting.get())) return;
                Class<?>[] pt = ((Constructor<?>) p.method).getParameterTypes();
                if (pt.length != 4 || pt[1] != float.class || pt[2] != float.class || pt[3] != float.class) return;
                if ((Float) p.args[1] < BUILDINGS_ZOOM) p.args[1] = BUILDINGS_ZOOM;
                if ((Float) p.args[2] < TILT) p.args[2] = TILT;
            }
        });
        log("Google 3D view ready (camera class " + cp.getName() + ")");
    }

    // ---- 3D buildings switch: map transaction 41 = setBuildingsEnabled(boolean) ----
    static final int TX_SET_BUILDINGS = 41;
    static final ThreadLocal<Boolean> injectingBuildings = new ThreadLocal<Boolean>();
    static final java.util.Set<Method> boolSettersHooked = new java.util.HashSet<Method>();
    static volatile Method setBuildingsMethod;

    /** Turn 3D buildings on, learn which engine method did it, and stop the app from switching them off again. */
    static void enableGoogleBuildings(android.os.Binder engine) {
        for (Class<?> k = engine.getClass(); k != null && k != android.os.Binder.class; k = k.getSuperclass())
            for (final Method m : k.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getReturnType() != void.class || pt.length != 1 || pt[0] != boolean.class) continue;
                synchronized (boolSettersHooked) { if (!boolSettersHooked.add(m)) continue; }
                XposedBridge.hookAllMethods(m.getDeclaringClass(), m.getName(), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!(p.method instanceof Method) || !sameSig((Method) p.method, m)) return;
                        if (Boolean.TRUE.equals(injectingBuildings.get())) { if (setBuildingsMethod == null) setBuildingsMethod = m; return; }
                        if (setBuildingsMethod != null && sameSig((Method) p.method, setBuildingsMethod) && Boolean.FALSE.equals(p.args[0])) {
                            p.args[0] = Boolean.TRUE;
                            log("app tried to turn 3D buildings off, kept them on");
                        }
                    }
                });
            }
        android.os.Parcel d = android.os.Parcel.obtain(), r = android.os.Parcel.obtain();
        try {
            d.writeInterfaceToken(MAP_DELEGATE);
            d.writeInt(1);
            injectingBuildings.set(Boolean.TRUE);
            engine.transact(TX_SET_BUILDINGS, d, r, 0);
            r.readException();
            log("3D buildings on (setBuildingsEnabled=" + (setBuildingsMethod == null ? "not identified" : setBuildingsMethod.getName()) + ")");
        } catch (Throwable t) { log(t); }
        finally { injectingBuildings.remove(); d.recycle(); r.recycle(); }
    }

    static void tiltGoogle(android.os.Binder engine) {
        android.os.Binder factory = cameraFactory;
        if (factory == null) { log("3D view: no camera factory yet"); return; }
        Class<?> cp = cameraPositionClass(factory);
        if (cp == null) { log("3D view: couldn't find CameraPosition on " + factory.getClass()); return; }
        hookCameraPosition(cp);
        android.os.Parcel d = android.os.Parcel.obtain(), r = android.os.Parcel.obtain();
        try {
            // 1) current camera; decoding it goes through the hooked constructor, so it comes back tilted
            d.writeInterfaceToken(MAP_DELEGATE);
            engine.transact(1, d, r, 0);
            r.readException();
            if (r.readInt() == 0) return;
            Object pos;
            tilting.set(Boolean.TRUE);
            try { pos = ((android.os.Parcelable.Creator<?>) getField(null, cp, "CREATOR")).createFromParcel(r); }
            finally { tilting.remove(); }
            // 2) factory.newCameraPosition(pos) -> CameraUpdate wrapper
            d.recycle(); r.recycle(); d = android.os.Parcel.obtain(); r = android.os.Parcel.obtain();
            d.writeInterfaceToken(CAMERA_FACTORY);
            d.writeInt(1);
            ((android.os.Parcelable) pos).writeToParcel(d, 0);
            factory.transact(7, d, r, 0);
            r.readException();
            android.os.IBinder update = r.readStrongBinder();
            // 3) map.moveCamera(update)
            d.recycle(); r.recycle(); d = android.os.Parcel.obtain(); r = android.os.Parcel.obtain();
            d.writeInterfaceToken(MAP_DELEGATE);
            d.writeStrongBinder(update);
            engine.transact(4, d, r, 0);
            r.readException();
            log("3D view: map tilted");
        } catch (Throwable t) { log(t); }
        finally { d.recycle(); r.recycle(); }
    }

    // ---------------- Google Maps engine (works even when the app renamed the SDK's classes) ----------------
    //
    // Apps shrink/rename play-services-maps, so "GoogleMap" usually doesn't exist by name. What can't be renamed:
    //  - the engine registers itself with Binder.attachInterface(this, "com.google.android.gms.maps.internal.IGoogleMapDelegate")
    //  - setMapStyle is transaction 91 of that interface (fixed, so apps and Play services stay compatible)
    // So: catch the engine when it is created, send it transaction 91 with our style, note which engine method
    // handled it (that's the real setMapStyle), and hook that method so the app's own styles get swapped for ours.
    static final String MAP_DELEGATE = "com.google.android.gms.maps.internal.IGoogleMapDelegate";
    static final int TX_SET_MAP_STYLE = 91;
    static final ThreadLocal<Boolean> injecting = new ThreadLocal<Boolean>();
    static final java.util.Set<Method> candidatesHooked = new java.util.HashSet<Method>();
    static volatile Method setStyleMethod;           // learned from our own transaction
    static final Map<Class<?>, Object> styleObjs = new java.util.HashMap<Class<?>, Object>();

    static void hookGoogleEngine() {
        if (gJson == null) return;
        XposedBridge.hookAllMethods(android.os.Binder.class, "attachInterface", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof android.os.Binder)) return;
                if (tilt3d && CAMERA_FACTORY.equals(p.args[1])) {
                    final android.os.Binder f = (android.os.Binder) p.thisObject;
                    cameraFactory = f;
                    new android.os.Handler(Looper.getMainLooper()).post(new Runnable() {   // hook app-built camera positions early
                        public void run() {
                            try { Class<?> cp = cameraPositionClass(f); if (cp != null) hookCameraPosition(cp); } catch (Throwable t) { log(t); }
                        }
                    });
                    return;
                }
                if (!MAP_DELEGATE.equals(p.args[1])) return;
                final android.os.Binder engine = (android.os.Binder) p.thisObject;
                android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
                // attachInterface runs inside the engine's constructor: wait until it is fully built.
                main.post(new Runnable() { public void run() { onGoogleEngine(engine); } });
                if (tilt3d) {
                    main.post(new Runnable() { public void run() { enableGoogleBuildings(engine); } });
                    // Tilt only while the map settles on its first position, then leave the camera to the user.
                    for (long delay : new long[]{2000, 5000})
                        main.postDelayed(new Runnable() { public void run() { tiltGoogle(engine); } }, delay);
                }
            }
        });
    }

    static void onGoogleEngine(android.os.Binder engine) {
        try {
            hookStyleCandidates(engine.getClass());
            Class<?> styleCls = setStyleMethod != null ? setStyleMethod.getParameterTypes()[0] : firstCandidateParam(engine.getClass());
            if (styleCls == null) { log("Google map engine found but no setMapStyle-like method on " + engine.getClass()); return; }
            Object style = styleObj(styleCls);
            android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken(MAP_DELEGATE);
                data.writeInt(1);                                   // "non-null parcelable follows"
                ((android.os.Parcelable) style).writeToParcel(data, 0);
                injecting.set(Boolean.TRUE);
                engine.transact(TX_SET_MAP_STYLE, data, reply, 0);
                reply.readException();
                boolean ok = reply.readInt() != 0;
                log("Google map themed (engine " + engine.getClass().getName() + ", style accepted=" + ok
                        + ", setMapStyle=" + (setStyleMethod == null ? "not identified" : setStyleMethod.getName()) + ")");
            } finally {
                injecting.remove();
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) { log(t); }
    }

    /** Methods shaped like setMapStyle(MapStyleOptions): boolean m(P) where P is a Parcelable holding just one String. */
    static java.util.List<Method> styleCandidates(Class<?> c) {
        java.util.List<Method> out = new java.util.ArrayList<Method>();
        for (Class<?> k = c; k != null && k != android.os.Binder.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getReturnType() != boolean.class || pt.length != 1 || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (isStyleOptions(pt[0])) out.add(m);
            }
        return out;
    }

    static boolean isStyleOptions(Class<?> p) {
        if (p.isInterface() || !android.os.Parcelable.class.isAssignableFrom(p)) return false;
        try { p.getDeclaredField("CREATOR"); } catch (NoSuchFieldException e) { return false; }
        int strings = 0, others = 0;
        for (Field f : p.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == String.class) strings++; else others++;
        }
        return strings == 1 && others == 0;
    }

    static Class<?> firstCandidateParam(Class<?> c) {
        java.util.List<Method> l = styleCandidates(c);
        return l.isEmpty() ? null : l.get(0).getParameterTypes()[0];
    }

    static synchronized void hookStyleCandidates(Class<?> c) {
        for (final Method m : styleCandidates(c)) {
            if (!candidatesHooked.add(m)) continue;
            XposedBridge.hookAllMethods(m.getDeclaringClass(), m.getName(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!(p.method instanceof Method) || !sameSig((Method) p.method, m)) return;
                    if (Boolean.TRUE.equals(injecting.get())) {          // our transaction 91 landed here: this is setMapStyle
                        if (setStyleMethod == null) setStyleMethod = m;
                        return;
                    }
                    if (setStyleMethod == null || !sameSig((Method) p.method, setStyleMethod)) return;
                    try {
                        Object ours = styleObj(m.getParameterTypes()[0]);
                        if (p.args[0] != ours) { p.args[0] = ours; log("app tried to set its own map style, kept MapSkin's"); }
                    } catch (Throwable t) { log(t); }
                }
            });
        }
    }

    static boolean sameSig(Method a, Method b) {
        return a.getName().equals(b.getName()) && a.getDeclaringClass() == b.getDeclaringClass()
                && java.util.Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    static synchronized Object styleObj(Class<?> styleCls) throws Exception {
        Object o = styleObjs.get(styleCls);
        if (o == null) { o = newInst(styleCls, gJson); styleObjs.put(styleCls, o); }
        return o;
    }

    // ---------------- Google Maps Android SDK (apps that kept the real class names) ----------------
    static void hookGoogle() {
        Class<?> gm = find("com.google.android.gms.maps.GoogleMap");
        final Class<?> mso = find("com.google.android.gms.maps.model.MapStyleOptions");
        if (gm == null || mso == null || gJson == null) return;
        log("Google Maps SDK found");
        XposedBridge.hookAllConstructors(gm, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { call(p.thisObject, "setMapStyle", newInst(mso, gJson)); } catch (Throwable t) { log(t); }
            }
        });
        XposedBridge.hookAllMethods(gm, "setMapStyle", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { p.args[0] = newInst(mso, gJson); } catch (Throwable t) { log(t); }
            }
        });
        XposedBridge.hookAllMethods(gm, "setMyLocationEnabled", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(p.args[0]) && icon() != null) {
                    p.args[0] = Boolean.FALSE;   // hide the SDK's blue dot, draw our own marker instead
                    trackGoogle(p.thisObject);
                }
            }
        });
    }

    static void trackGoogle(final Object map) {
        if (markers.containsKey(map)) return;
        markers.put(map, null);
        Context ctx = app();
        if (ctx == null) return;
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        final Bitmap bmp = icon();
        LocationListener l = new LocationListener() {
            public void onLocationChanged(Location loc) {
                try {
                    Object ll = newInst(find("com.google.android.gms.maps.model.LatLng"), loc.getLatitude(), loc.getLongitude());
                    Object mk = markers.get(map);
                    if (mk == null) {
                        Object bd = callStatic(find("com.google.android.gms.maps.model.BitmapDescriptorFactory"), "fromBitmap", bmp);
                        Object mo = newInst(find("com.google.android.gms.maps.model.MarkerOptions"));
                        call(mo, "position", ll);
                        call(mo, "icon", bd);
                        call(mo, "anchor", 0.5f, 0.5f);
                        call(mo, "flat", true);
                        call(mo, "zIndex", 1000f);
                        mk = call(map, "addMarker", mo);
                        markers.put(map, mk);
                    } else {
                        call(mk, "setPosition", ll);
                    }
                    if (loc.hasBearing()) call(mk, "setRotation", loc.getBearing());
                } catch (Throwable t) {
                    log(t);
                    try { lm.removeUpdates(this); } catch (Throwable ignored) {}
                }
            }
            public void onStatusChanged(String s, int i, Bundle b) {}
            public void onProviderEnabled(String s) {}
            public void onProviderDisabled(String s) {}
        };
        for (String pr : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(pr)) continue;
                lm.requestLocationUpdates(pr, 1000L, 0f, l, Looper.getMainLooper());
                Location last = lm.getLastKnownLocation(pr);
                if (last != null) l.onLocationChanged(last);
            } catch (Throwable t) { log(t); }
        }
    }

    // ---------------- Mapbox Maps SDK v10 / v11 ----------------
    static void hookMapbox() {
        if (mbJson == null) return;
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof String)) return;
                String n = p.method.getName();
                String was = (String) p.args[0];
                if (n.toLowerCase().contains("uri") || n.toLowerCase().contains("url")) {
                    String u = styleFile();
                    if (u == null || u.equals(was)) return;
                    p.args[0] = u;
                } else {
                    if (mbJson.equals(was)) return;
                    p.args[0] = mbJson;
                }
                log("Mapbox " + n + ": replaced style " + (was.length() > 80 ? was.substring(0, 80) + "…" : was));
            }
        };
        // v10 loads styles via MapboxMap/NativeMapImpl; v11 routes everything through
        // MapboxStyleManager -> StyleManager (the JNI peer), so hook every layer.
        String[][] targets = {
            {"com.mapbox.maps.MapboxMap", "loadStyleUri", "loadStyleJson", "loadStyle"},
            {"com.mapbox.maps.NativeMapImpl", "setStyleURI", "setStyleJSON"},
            {"com.mapbox.maps.MapboxStyleManager", "setStyleURI", "setStyleJSON"},
            {"com.mapbox.maps.StyleManager", "setStyleURI", "setStyleJSON"},
        };
        StringBuilder got = new StringBuilder();
        for (String[] t : targets) {
            Class<?> c = find(t[0]);
            if (c == null) continue;
            for (int i = 1; i < t.length; i++) {
                int n = 0;
                try { java.util.Set<?> s = XposedBridge.hookAllMethods(c, t[i], h); n = s == null ? 0 : s.size(); }
                catch (Throwable e) { log(e); }
                if (n > 0) got.append(' ').append(c.getSimpleName()).append('.').append(t[i]).append('×').append(n);
            }
        }
        if (got.length() > 0) log("Mapbox SDK found, hooked:" + got);
    }

    /** Replaces the default 2D location puck image with the chosen icon. */
    static void hookMapboxPuck() {
        Class<?> puck = find("com.mapbox.maps.plugin.LocationPuck2D");
        final Class<?> holder = find("com.mapbox.maps.ImageHolder");
        if (puck == null || holder == null) return;
        XposedBridge.hookAllConstructors(puck, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Bitmap b = icon();
                if (b == null) return;
                try {
                    Object ih;
                    try { ih = callStatic(holder, "from", b); }
                    catch (Throwable t) { ih = call(getField(null, holder, "Companion"), "from", b); }
                    setField(p.thisObject, "topImage", ih);
                    setField(p.thisObject, "bearingImage", null);
                    setField(p.thisObject, "shadowImage", null);
                    log("Mapbox location puck replaced");
                } catch (Throwable t) { log(t); }
            }
        });
    }

    // ---------------- MapLibre & legacy Mapbox (v9 and older) ----------------
    static void hookMapLibre(String pkg) {
        if (mbJson == null) return;
        Class<?> builder = find(pkg + ".Style$Builder");
        Class<?> nativeView = find(pkg + ".NativeMapView");
        if (builder == null && nativeView == null) return;
        log("MapLibre/legacy Mapbox found: " + pkg);
        XC_MethodHook toFile = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof String) { String u = styleFile(); if (u != null) p.args[0] = u; }
            }
        };
        XC_MethodHook toJson = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof String) p.args[0] = mbJson;
            }
        };
        if (builder != null) {
            XposedBridge.hookAllMethods(builder, "fromUri", toFile);
            XposedBridge.hookAllMethods(builder, "fromUrl", toFile);
            XposedBridge.hookAllMethods(builder, "fromJson", toJson);
        }
        if (nativeView != null) {
            XposedBridge.hookAllMethods(nativeView, "setStyleUri", toFile);
            XposedBridge.hookAllMethods(nativeView, "setStyleUrl", toFile);
            XposedBridge.hookAllMethods(nativeView, "setStyleJson", toJson);
        }
    }

    // ---------------- helpers ----------------
    static synchronized Bitmap icon() {
        if (!iconLoaded) {
            iconLoaded = true;
            try { icon = Icons.get(iconId, iconB64); } catch (Throwable t) { log(t); }
        }
        return icon;
    }

    /** Writes the themed style into the hooked app's own cache dir so it can be loaded as a file:// URI. */
    static synchronized String styleFile() {
        if (styleUri != null) return styleUri;
        Context ctx = app();
        if (ctx == null || mbJson == null) return null;
        try {
            File f = new File(ctx.getCacheDir(), "mapskin_style.json");
            FileOutputStream o = new FileOutputStream(f);
            o.write(mbJson.getBytes("UTF-8"));
            o.close();
            styleUri = "file://" + f.getAbsolutePath();
        } catch (Throwable t) { log(t); }
        return styleUri;
    }

    static Context app() {
        try { return (Context) callStatic(Class.forName("android.app.ActivityThread"), "currentApplication"); }
        catch (Throwable t) { return null; }
    }

    static Class<?> find(String n) {
        try { return Class.forName(n, false, cl); } catch (Throwable t) { return null; }
    }

    static void safe(Runnable r) { try { r.run(); } catch (Throwable t) { log(t); } }

    static void log(String s) {
        android.util.Log.i("MapSkin", s);
        try { XposedBridge.log(TAG + s); } catch (Throwable ignored) {}
    }
    static void log(Throwable t) {
        android.util.Log.e("MapSkin", "error", t);
        try { XposedBridge.log(TAG + android.util.Log.getStackTraceString(t)); } catch (Throwable ignored) {}
    }

    static boolean fits(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) { if (t.isPrimitive()) return false; continue; }
            if (t.isPrimitive()) t = box(t);
            if (!t.isInstance(a)) return false;
        }
        return true;
    }

    static Class<?> box(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    static Object call(Object o, String name, Object... a) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && fits(m.getParameterTypes(), a)) { m.setAccessible(true); return m.invoke(o, a); }
        throw new NoSuchMethodException(name);
    }

    static Object callStatic(Class<?> c, String name, Object... a) throws Exception {
        for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && fits(m.getParameterTypes(), a)) { m.setAccessible(true); return m.invoke(null, a); }
        throw new NoSuchMethodException(name);
    }

    static Object newInst(Class<?> c, Object... a) throws Exception {
        for (Constructor<?> k : c.getDeclaredConstructors())
            if (fits(k.getParameterTypes(), a)) { k.setAccessible(true); return k.newInstance(a); }
        throw new NoSuchMethodException(c.getName() + ".<init>");
    }

    static Object getField(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }

    static void setField(Object o, String name, Object v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); return; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
