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
    static boolean iconLoaded;
    static ClassLoader cl;
    static final Map<Object, Object> markers = new WeakHashMap<Object, Object>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (PKG.equals(lp.packageName) || !lp.isFirstApplication) return;
        XSharedPreferences sp = new XSharedPreferences(PKG, "prefs");
        sp.reload();
        if (!sp.getBoolean("enabled", true)) return;
        theme = sp.getString("theme", "neon");
        mbJson = sp.getString("mbjson", null);
        iconId = sp.getString("icon", "off");
        iconB64 = sp.getString("icon_b64", null);
        cl = lp.classLoader;
        try { gJson = Themes.google(theme); } catch (Throwable t) { log(t); }

        safe(new Runnable() { public void run() { hookGoogle(); } });
        safe(new Runnable() { public void run() { hookMapbox(); } });
        safe(new Runnable() { public void run() { hookMapLibre("org.maplibre.android.maps"); } });
        safe(new Runnable() { public void run() { hookMapLibre("com.mapbox.mapboxsdk.maps"); } });
        safe(new Runnable() { public void run() { hookMapboxPuck(); } });
    }

    // ---------------- Google Maps Android SDK ----------------
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
                if (n.toLowerCase().contains("uri") || n.toLowerCase().contains("url")) {
                    String u = styleFile();
                    if (u != null) p.args[0] = u;
                } else {
                    p.args[0] = mbJson;
                }
            }
        };
        boolean found = false;
        Class<?> mm = find("com.mapbox.maps.MapboxMap");
        if (mm != null) {
            found = true;
            for (String n : new String[]{"loadStyleUri", "loadStyleJson", "loadStyle"}) XposedBridge.hookAllMethods(mm, n, h);
        }
        Class<?> nm = find("com.mapbox.maps.NativeMapImpl");
        if (nm != null) {
            found = true;
            XposedBridge.hookAllMethods(nm, "setStyleURI", h);
            XposedBridge.hookAllMethods(nm, "setStyleJSON", h);
        }
        if (found) log("Mapbox SDK found");
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

    static void log(String s) { XposedBridge.log(TAG + s); }
    static void log(Throwable t) { XposedBridge.log(TAG + t); }

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
