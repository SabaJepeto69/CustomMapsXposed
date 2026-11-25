package com.mapskin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Original map colour themes, plus converters to Google Maps SDK and Mapbox/MapLibre style JSON.
 * The game-style themes are inspired by a genre's look; nothing is copied from any game.
 */
public final class Themes {
    public static final String[] IDS = {"neoncity", "sunshine", "neon", "sunset", "toon", "ops"};
    public static final String[] NAMES = {
        "Neon City (cyberpunk-game style)",
        "Sunshine Coast (open-world crime-game style)",
        "Neon Night (pink & cyan neon)",
        "Sunset Coast (retro tropical)",
        "Toon Day (bright cartoon)",
        "Night Ops (tactical green)"
    };

    // Index: 0 bg  1 park  2 wood  3 water  4 road  5 roadMajor  6 casing  7 building  8 bld3d  9 label  10 halo  11 highway
    static final String[][] P = {
        {"#0b0a10", "#121a16", "#0f1512", "#081822", "#3b1a20", "#ff4d5e", "#000000", "#2a0b12", "#d01f3c", "#5ef6ff", "#000000", "#fcee0a"},
        {"#eadbc6", "#86c98a", "#6fb874", "#2fc6d9", "#ffffff", "#ffd166", "#b58aa6", "#ddcab4", "#d1b79c", "#3b1f4a", "#ffffff", "#ff4fa0"},
        {"#0a0618", "#12203a", "#0f1a30", "#061a3a", "#ff2bd6", "#00f0ff", "#000000", "#1a0f33", "#2a1550", "#e0f7ff", "#000000", "#00f0ff"},
        {"#ffd6c2", "#7fd6a8", "#5cbf8e", "#2ec4d6", "#ffffff", "#ff6f91", "#8a3b6b", "#ffb5a7", "#ff8fa3", "#4a1942", "#ffffff", "#ff6f91"},
        {"#bfe6a0", "#7ed957", "#5cc23f", "#4fc3ff", "#ffffff", "#ffd54a", "#3a3a3a", "#ffb38a", "#ff9f6b", "#222222", "#ffffff", "#ffd54a"},
        {"#141a16", "#1e2b22", "#1a261d", "#0d1f24", "#3c4a40", "#9be15d", "#070a08", "#222b25", "#2c3830", "#b8f5a0", "#000000", "#9be15d"},
    };

    /** Per-theme look tweaks: hide shop/POI clutter, hide transit, fewer labels (game-HUD feel), road line weight. */
    static final boolean[] HIDE_POI     = {true,  true,  false, false, false, true};
    static final boolean[] HIDE_TRANSIT = {true,  false, false, false, false, true};
    static final boolean[] FEW_LABELS   = {true,  false, false, false, false, false};
    static final double[]  ROAD_WEIGHT  = {1.2,   1.6,   1.0,   1.0,   1.0,   1.0};
    /** Glowing building edges (null = none). Neon City: dark-red blocks with neon-red outlines, red 3D extrusions. */
    static final String[]  BUILDING_EDGE = {"#ff3b4f", null, null, null, null, null};

    /** Bundles: a theme plus a matching player icon, picked together. {id, label, theme, icon} */
    public static final String[][] BUNDLES = {
        {"neoncity", "Neon City: cyberpunk-game style map + glowing neon chevron", "neoncity", "chevron"},
        {"sunshine", "Sunshine Coast: crime-game style map + white radar arrow", "sunshine", "radar"},
    };

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String[] palette(String id) { return P[index(id)]; }

    private static void add(JSONArray a, String feature, String element, Object... kv) throws JSONException {
        JSONObject o = new JSONObject();
        if (feature != null) o.put("featureType", feature);
        o.put("elementType", element);
        JSONArray st = new JSONArray();
        for (int i = 0; i < kv.length; i += 2) st.put(new JSONObject().put((String) kv[i], kv[i + 1]));
        o.put("stylers", st);
        a.put(o);
    }

    /** Style JSON for GoogleMap.setMapStyle (Google Maps Android SDK). */
    public static String google(String id) throws JSONException {
        int t = index(id);
        String[] c = P[t];
        double w = ROAD_WEIGHT[t];
        JSONArray a = new JSONArray();
        add(a, null, "geometry", "color", c[0]);
        add(a, null, "labels.text.fill", "color", c[9]);
        add(a, null, "labels.text.stroke", "color", c[10]);
        add(a, "landscape.natural", "geometry", "color", c[0]);
        add(a, "landscape.natural.terrain", "geometry", "color", c[2]);
        add(a, "landscape.man_made", "geometry", "color", c[7]);
        if (BUILDING_EDGE[t] != null) {
            // Google's 3D buildings (tilted view, high zoom) take their fill and edge colours from these.
            add(a, "landscape.man_made", "geometry.fill", "color", c[8], "lightness", -35);
            add(a, "landscape.man_made", "geometry.stroke", "color", BUILDING_EDGE[t], "weight", 1.5);
        }
        add(a, "poi", "geometry", "color", c[0]);
        add(a, "poi.park", "geometry", "color", c[1]);
        add(a, "water", "geometry", "color", c[3]);
        add(a, "water", "labels.text.fill", "color", c[5]);
        add(a, "road", "geometry.fill", "color", c[4], "weight", 1.0 * w);
        add(a, "road", "geometry.stroke", "color", c[6]);
        add(a, "road.arterial", "geometry.fill", "color", c[5], "weight", 1.4 * w);
        add(a, "road.highway", "geometry.fill", "color", c[11], "weight", 2.0 * w);
        add(a, "road.highway", "geometry.stroke", "color", c[6]);
        add(a, "road.highway.controlled_access", "geometry.fill", "color", c[11], "weight", 2.6 * w);
        add(a, "transit", "geometry", "color", c[7]);
        add(a, "administrative", "geometry.stroke", "color", c[6]);
        if (HIDE_POI[t]) {
            add(a, "poi", "labels", "visibility", "off");
            add(a, "poi.business", "all", "visibility", "off");
        }
        if (HIDE_TRANSIT[t]) add(a, "transit", "all", "visibility", "off");
        if (FEW_LABELS[t]) {
            add(a, "road", "labels.icon", "visibility", "off");
            add(a, "road.local", "labels", "visibility", "off");
            add(a, "administrative.land_parcel", "labels", "visibility", "off");
            add(a, "administrative.neighborhood", "labels.text.fill", "color", c[11]);
        }
        return a.toString();
    }

    /** Re-colours the OpenFreeMap "liberty" style JSON for Mapbox / MapLibre apps. */
    public static String mapbox(String liberty, String id) throws JSONException {
        int ti = index(id);
        String[] c = P[ti];
        JSONObject s = new JSONObject(liberty);
        s.put("name", "MapSkin " + id);
        JSONArray layers = s.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject l = layers.getJSONObject(i);
            String lid = l.optString("id"), t = l.optString("type");
            JSONObject p = l.optJSONObject("paint");
            if (p == null) { p = new JSONObject(); l.put("paint", p); }
            JSONObject lay = l.optJSONObject("layout");
            boolean line = t.equals("line"), fill = t.equals("fill");
            if (lid.equals("background")) p.put("background-color", c[0]);
            else if (t.equals("raster")) hide(l);
            else if (lid.startsWith("park") && fill) p.put("fill-color", c[1]);
            else if (lid.equals("landcover_wood")) p.put("fill-color", c[2]);
            else if (lid.equals("landuse_residential")) p.put("fill-color", c[0]);
            else if (fill && lid.matches(".*(grass|pitch|cemetery|track).*")) p.put("fill-color", c[1]);
            else if (lid.equals("water")) p.put("fill-color", c[3]);
            else if (lid.startsWith("waterway") && line) p.put("line-color", c[3]);
            else if (line && lid.contains("casing")) p.put("line-color", c[6]);
            else if (line && !lid.contains("rail") && lid.matches(".*(motorway|trunk).*")) p.put("line-color", c[11]);
            else if (line && !lid.contains("rail") && lid.matches(".*primary.*")) p.put("line-color", c[5]);
            else if (line && lid.matches("(road|bridge|tunnel)_(minor|secondary|service|link|street|path).*")) p.put("line-color", c[4]);
            else if (lid.equals("building")) {
                p.put("fill-color", c[7]);
                if (BUILDING_EDGE[ti] != null) p.put("fill-outline-color", BUILDING_EDGE[ti]);
            }
            else if (t.equals("fill-extrusion")) {
                p.put("fill-extrusion-color", c[8]);
                p.put("fill-extrusion-opacity", BUILDING_EDGE[ti] != null ? 0.88 : 0.95);
                if (BUILDING_EDGE[ti] != null) p.put("fill-extrusion-vertical-gradient", true);   // darker at street level, brighter tops
            }
            else if (t.equals("symbol")) {
                if ((lid.startsWith("poi") && HIDE_POI[ti]) || (lid.contains("transit") && HIDE_TRANSIT[ti])) { hide(l); continue; }
                if (lay != null && lay.has("text-field")) { p.put("text-color", c[9]); p.put("text-halo-color", c[10]); }
            }
        }
        return s.toString();
    }

    private static void hide(JSONObject layer) throws JSONException {
        JSONObject lay = layer.optJSONObject("layout");
        if (lay == null) { lay = new JSONObject(); layer.put("layout", lay); }
        lay.put("visibility", "none");
    }
}
