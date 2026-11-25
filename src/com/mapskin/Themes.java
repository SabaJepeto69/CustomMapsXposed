package com.mapskin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Original map colour themes, plus converters to Google Maps SDK and Mapbox/MapLibre style JSON. */
public final class Themes {
    public static final String[] IDS = {"neon", "sunset", "toon", "ops"};
    public static final String[] NAMES = {
        "Neon Night (cyberpunk-style neon city)",
        "Sunset Coast (retro tropical)",
        "Toon Day (bright cartoon)",
        "Night Ops (tactical green)"
    };

    // Index:           0 bg       1 park     2 wood     3 water    4 road     5 roadMajor 6 casing   7 building 8 bld3d    9 label    10 halo
    static final String[][] P = {
        {"#0a0618", "#12203a", "#0f1a30", "#061a3a", "#ff2bd6", "#00f0ff", "#000000", "#1a0f33", "#2a1550", "#e0f7ff", "#000000"},
        {"#ffd6c2", "#7fd6a8", "#5cbf8e", "#2ec4d6", "#ffffff", "#ff6f91", "#8a3b6b", "#ffb5a7", "#ff8fa3", "#4a1942", "#ffffff"},
        {"#bfe6a0", "#7ed957", "#5cc23f", "#4fc3ff", "#ffffff", "#ffd54a", "#3a3a3a", "#ffb38a", "#ff9f6b", "#222222", "#ffffff"},
        {"#141a16", "#1e2b22", "#1a261d", "#0d1f24", "#3c4a40", "#9be15d", "#070a08", "#222b25", "#2c3830", "#b8f5a0", "#000000"},
    };

    static String[] palette(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return P[i];
        return P[0];
    }

    private static void rule(JSONArray a, String feature, String element, String key, Object val) throws JSONException {
        JSONObject o = new JSONObject();
        if (feature != null) o.put("featureType", feature);
        o.put("elementType", element);
        o.put("stylers", new JSONArray().put(new JSONObject().put(key, val)));
        a.put(o);
    }

    /** Style JSON for GoogleMap.setMapStyle (Google Maps Android SDK). */
    public static String google(String id) throws JSONException {
        String[] c = palette(id);
        JSONArray a = new JSONArray();
        rule(a, null, "geometry", "color", c[0]);
        rule(a, null, "labels.text.fill", "color", c[9]);
        rule(a, null, "labels.text.stroke", "color", c[10]);
        rule(a, "landscape.natural", "geometry", "color", c[0]);
        rule(a, "landscape.natural.terrain", "geometry", "color", c[2]);
        rule(a, "landscape.man_made", "geometry", "color", c[7]);
        rule(a, "poi", "geometry", "color", c[0]);
        rule(a, "poi.park", "geometry", "color", c[1]);
        rule(a, "water", "geometry", "color", c[3]);
        rule(a, "road", "geometry.fill", "color", c[4]);
        rule(a, "road", "geometry.stroke", "color", c[6]);
        rule(a, "road.highway", "geometry.fill", "color", c[5]);
        rule(a, "road.highway", "geometry.stroke", "color", c[6]);
        rule(a, "transit", "geometry", "color", c[7]);
        rule(a, "administrative", "geometry.stroke", "color", c[6]);
        return a.toString();
    }

    /** Re-colours the OpenFreeMap "liberty" style JSON for Mapbox / MapLibre apps. */
    public static String mapbox(String liberty, String id) throws JSONException {
        String[] c = palette(id);
        JSONObject s = new JSONObject(liberty);
        s.put("name", "MapSkin " + id);
        JSONArray layers = s.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject l = layers.getJSONObject(i);
            String lid = l.optString("id"), t = l.optString("type");
            JSONObject p = l.optJSONObject("paint");
            if (p == null) { p = new JSONObject(); l.put("paint", p); }
            boolean line = t.equals("line"), fill = t.equals("fill");
            if (lid.equals("background")) p.put("background-color", c[0]);
            else if (t.equals("raster")) {
                JSONObject lay = l.optJSONObject("layout");
                if (lay == null) { lay = new JSONObject(); l.put("layout", lay); }
                lay.put("visibility", "none");
            }
            else if (lid.startsWith("park") && fill) p.put("fill-color", c[1]);
            else if (lid.equals("landcover_wood")) p.put("fill-color", c[2]);
            else if (lid.equals("landuse_residential")) p.put("fill-color", c[0]);
            else if (fill && lid.matches(".*(grass|pitch|cemetery|track).*")) p.put("fill-color", c[1]);
            else if (lid.equals("water")) p.put("fill-color", c[3]);
            else if (lid.startsWith("waterway") && line) p.put("line-color", c[3]);
            else if (line && lid.contains("casing")) p.put("line-color", c[6]);
            else if (line && !lid.contains("rail") && lid.matches(".*(motorway|trunk|primary).*")) p.put("line-color", c[5]);
            else if (line && lid.matches("(road|bridge|tunnel)_(minor|secondary|service|link|street|path).*")) p.put("line-color", c[4]);
            else if (lid.equals("building")) p.put("fill-color", c[7]);
            else if (t.equals("fill-extrusion")) { p.put("fill-extrusion-color", c[8]); p.put("fill-extrusion-opacity", 0.95); }
            else if (t.equals("symbol")) {
                JSONObject lay = l.optJSONObject("layout");
                if (lay != null && lay.has("text-field")) { p.put("text-color", c[9]); p.put("text-halo-color", c[10]); }
            }
        }
        return s.toString();
    }
}
