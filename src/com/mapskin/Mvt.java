package com.mapskin;

import java.util.ArrayList;
import java.util.List;

/** Minimal Mapbox Vector Tile (protobuf) reader: just the "building" layer's polygons and heights. */
final class Mvt {
    private final byte[] b;
    private int pos, end;

    private Mvt(byte[] b, int pos, int end) { this.b = b; this.pos = pos; this.end = end; }

    private long varint() {
        long r = 0;
        for (int s = 0; s < 64; s += 7) {
            int c = b[pos++] & 255;
            r |= (long) (c & 127) << s;
            if (c < 128) return r;
        }
        return r;
    }

    private void skip(int wire) {
        if (wire == 0) varint();
        else if (wire == 1) pos += 8;
        else if (wire == 2) { int n = (int) varint(); pos += n; }
        else if (wire == 5) pos += 4;
        else throw new IllegalStateException("bad wire type " + wire);
    }

    private Mvt sub() { int n = (int) varint(); Mvt m = new Mvt(b, pos, pos + n); pos += n; return m; }

    private static int zigzag(long v) { return (int) ((v >>> 1) ^ -(v & 1)); }

    /**
     * Adds every building polygon of the tile to {@code out}, mapped to pixels: tile coordinates (0..extent)
     * scale to {@code size} pixels, then {@code offX}/{@code offY} are subtracted.
     */
    static void decodeBuildings(byte[] data, List<BuildingTiles.Building> out, float size, float offX, float offY) {
        Mvt t = new Mvt(data, 0, data.length);
        while (t.pos < t.end) {
            long key = t.varint();
            if ((key >>> 3) == 3 && (key & 7) == 2) {
                Mvt layer = t.sub();
                layer.readLayer(out, size, offX, offY);
            } else t.skip((int) (key & 7));
        }
    }

    private void readLayer(List<BuildingTiles.Building> out, float size, float offX, float offY) {
        // First pass: name, keys, values, extent (features are read after, they refer to keys/values by index).
        int start = pos;
        String name = null;
        List<String> keys = new ArrayList<String>();
        List<Double> values = new ArrayList<Double>();
        int extent = 4096;
        while (pos < end) {
            long key = varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 1 && wire == 2) { int n = (int) varint(); name = str(n); }
            else if (field == 3 && wire == 2) { int n = (int) varint(); keys.add(str(n)); }
            else if (field == 4 && wire == 2) values.add(sub().readValue());
            else if (field == 5 && wire == 0) extent = (int) varint();
            else skip(wire);
            if (name != null && !"building".equals(name)) return;
        }
        if (!"building".equals(name)) return;
        int hKey = keys.indexOf("render_height"), minKey = keys.indexOf("render_min_height");
        float scale = size / extent;
        pos = start;
        while (pos < end) {
            long key = varint();
            if ((key >>> 3) == 2 && (key & 7) == 2) {
                sub().readFeature(out, hKey, minKey, values, scale, offX, offY);
            } else skip((int) (key & 7));
        }
    }

    private String str(int n) {
        String s;
        try { s = new String(b, pos, n, "UTF-8"); } catch (Exception e) { s = ""; }
        pos += n;
        return s;
    }

    /** Value message; only numbers matter here (non-numbers become NaN). */
    private Double readValue() {
        double v = Double.NaN;
        while (pos < end) {
            long key = varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 2 && wire == 5) { v = Float.intBitsToFloat(int32()); }
            else if (field == 3 && wire == 1) { v = Double.longBitsToDouble(int64()); }
            else if ((field == 4 || field == 5) && wire == 0) v = varint();
            else if (field == 6 && wire == 0) v = zigzag(varint());
            else skip(wire);
        }
        return v;
    }

    private int int32() { int r = (b[pos] & 255) | (b[pos + 1] & 255) << 8 | (b[pos + 2] & 255) << 16 | (b[pos + 3] & 255) << 24; pos += 4; return r; }

    private long int64() { long lo = int32() & 0xFFFFFFFFL, hi = int32() & 0xFFFFFFFFL; return lo | hi << 32; }

    /**
     * One feature can hold many buildings (OpenFreeMap merges same-height buildings into a multipolygon):
     * every exterior ring (positive area in tile coordinates) starts a building, interior rings are its holes.
     */
    private void readFeature(List<BuildingTiles.Building> out, int hKey, int minKey, List<Double> values, float scale, float offX, float offY) {
        int type = 0;
        float height = 10f, minHeight = 0f;
        int geomStart = -1, geomEnd = -1;
        while (pos < end) {
            long key = varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 3 && wire == 0) type = (int) varint();
            else if (field == 2 && wire == 2) {                      // tags: packed key/value index pairs
                int n = (int) varint(), stop = pos + n;
                while (pos < stop) {
                    int k = (int) varint(), v = (int) varint();
                    double d = v < values.size() ? values.get(v) : Double.NaN;
                    if (Double.isNaN(d)) continue;
                    if (k == hKey) height = (float) d;
                    else if (k == minKey) minHeight = (float) d;
                }
            } else if (field == 4 && wire == 2) {
                int n = (int) varint();
                geomStart = pos; geomEnd = pos + n; pos += n;
            } else skip(wire);
        }
        if (type != 3 || geomStart < 0) return;                       // polygons only

        BuildingTiles.Building[] cur = new BuildingTiles.Building[1];
        pos = geomStart;
        int cx = 0, cy = 0;
        float[] ring = null;
        int rn = 0;
        while (pos < geomEnd) {
            int cmd = (int) varint();
            int id = cmd & 7, count = cmd >>> 3;
            if (id == 1 || id == 2) {
                for (int i = 0; i < count; i++) {
                    cx += zigzag(varint());
                    cy += zigzag(varint());
                    if (id == 1) {
                        if (ring != null) addRing(out, cur, ring, rn, height, minHeight);
                        ring = new float[64]; rn = 0;
                    }
                    if (rn + 2 > ring.length) ring = java.util.Arrays.copyOf(ring, ring.length * 2);
                    ring[rn++] = cx * scale - offX; ring[rn++] = cy * scale - offY;
                }
            } else if (id == 7) {
                if (ring != null) addRing(out, cur, ring, rn, height, minHeight);
                ring = null; rn = 0;
            }
        }
        if (ring != null) addRing(out, cur, ring, rn, height, minHeight);
    }

    private static void addRing(List<BuildingTiles.Building> out, BuildingTiles.Building[] cur, float[] ring, int n,
                                float height, float minHeight) {
        if (n < 6) return;
        double area = 0;
        for (int i = 0; i < n; i += 2) {
            int j = (i + 2) % n;
            area += (double) ring[i] * ring[j + 1] - (double) ring[j] * ring[i + 1];
        }
        float[] r = java.util.Arrays.copyOf(ring, n);
        if (area > 0 || cur[0] == null) {                             // exterior ring: a new building
            BuildingTiles.Building bd = new BuildingTiles.Building();
            bd.height = height;
            bd.minHeight = minHeight;
            bd.orient = area >= 0 ? 1 : -1;
            bd.minX = bd.minY = Float.MAX_VALUE;
            bd.maxX = bd.maxY = -Float.MAX_VALUE;
            for (int i = 0; i < n; i += 2) {
                bd.minX = Math.min(bd.minX, r[i]); bd.maxX = Math.max(bd.maxX, r[i]);
                bd.minY = Math.min(bd.minY, r[i + 1]); bd.maxY = Math.max(bd.maxY, r[i + 1]);
            }
            bd.rings.add(r);
            out.add(bd);
            cur[0] = bd;
        } else {
            cur[0].rings.add(r);                                      // hole in the current building
        }
    }
}
