package com.mapskin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Binder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A Google Maps tile provider (spoken to over Binder, so it works whatever the app renamed) that draws
 * raised "3D" buildings from Overture Maps / OpenStreetMap data at every zoom from 13 up, in place of
 * Google's own 3D buildings (which MapSkin switches off). Each building: shaded walls, a roof lifted by its real
 * height, neon edge.
 */
public final class BuildingTiles extends Binder {
    static final String DESC = "com.google.android.gms.maps.model.internal.ITileProviderDelegate";
    static final int TX_GET_TILE = 1;
    static final int MIN_ZOOM = 13;          // OpenMapTiles has buildings from z13
    static final int MAX_ZOOM = 21;          // Google's own 3D buildings are switched off, so draw all the way in
    static final int DATA_ZOOM = 14;         // highest zoom the vector tiles exist at
    static final int SIZE = 512;             // tile bitmap size (crisp on high-dpi screens)
    // Game-map look (like apps on Mapbox's 3D engine): every building gets at least MIN_HEIGHT, and heights are
    // exaggerated more the further out you zoom, so blocks still read as 3D in suburbs and at city scale.
    static final float MIN_HEIGHT = 4f;      // metres; even a shed stands up a little
    static final float EXAGGERATION = 1.8f;  // at zoom 16+; doubles every two zoom levels further out

    static float exaggeration(int z) {
        return z >= 16 ? EXAGGERATION : (float) (EXAGGERATION * Math.pow(2, (16 - z) * 0.5));
    }

    final Context ctx;
    volatile int logged;
    final int roof, edge;
    String tileUrl;                           // e.g. https://tiles.openfreemap.org/planet/<version>/{z}/{x}/{y}.pbf
    /** Decoded z14 tiles, coordinates 0..1 across the tile, heights already filled in. Decoded once, reused by every
     *  map tile and zoom that shows them (raw tiles stay on disk: central Tel Aviv alone is ~900 KB). */
    final Map<String, List<Building>> decoded = Collections.synchronizedMap(new LinkedHashMap<String, List<Building>>(32, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, List<Building>> e) { return size() > 24; }
    });

    BuildingTiles(Context ctx, int roof, int edge) {
        this.ctx = ctx;
        this.roof = roof;
        this.edge = edge;
    }

    static int shade(int c, float f) {
        return 0xFF000000 | (Math.round(((c >> 16) & 255) * f) << 16) | (Math.round(((c >> 8) & 255) * f) << 8) | Math.round((c & 255) * f);
    }

    // ---------------- Binder side: the map engine calls getTile(x, y, zoom) ----------------

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code != TX_GET_TILE) {
            try { return super.onTransact(code, data, reply, flags); } catch (Throwable t) { return false; }
        }
        data.enforceInterface(DESC);
        int x = data.readInt(), y = data.readInt(), z = data.readInt();
        byte[] png = null;
        long t0 = android.os.SystemClock.uptimeMillis();
        try { png = render(x, y, z); } catch (Throwable t) { Hook.log("3D buildings: tile " + z + "/" + x + "/" + y + " failed"); Hook.log(t); }
        if (logged < 5 && png != null) {
            logged++;
            Hook.log("3D buildings: drew " + z + "/" + x + "/" + y + " in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        }
        reply.writeNoException();
        reply.writeInt(1);                                  // non-null Tile follows
        writeTile(reply, png);
        return true;
    }

    /** Tile SafeParcelable: 2 = width, 3 = height, 4 = image bytes. No image -> NO_TILE (-1, -1). */
    static void writeTile(Parcel p, byte[] png) {
        int obj = begin(p, 20293);
        int w = png == null ? -1 : SIZE;
        p.writeInt((4 << 16) | 2); p.writeInt(w);
        p.writeInt((4 << 16) | 3); p.writeInt(w);
        if (png != null) { int f = begin(p, 4); p.writeByteArray(png); end(p, f); }
        end(p, obj);
    }

    static int begin(Parcel p, int id) { p.writeInt(0xFFFF0000 | id); p.writeInt(0); return p.dataPosition(); }

    static void end(Parcel p, int start) {
        int now = p.dataPosition();
        p.setDataPosition(start - 4);
        p.writeInt(now - start);
        p.setDataPosition(now);
    }

    /** TileOverlayOptions SafeParcelable: 2 = provider binder, 3 = visible, 4 = zIndex, 5 = fadeIn, 6 = transparency. */
    void writeOverlayOptions(Parcel p) {
        int obj = begin(p, 20293);
        int f = begin(p, 2); p.writeStrongBinder(this); end(p, f);
        p.writeInt((4 << 16) | 3); p.writeInt(1);
        p.writeInt((4 << 16) | 4); p.writeFloat(1f);
        p.writeInt((4 << 16) | 5); p.writeInt(1);
        p.writeInt((4 << 16) | 6); p.writeFloat(0f);
        end(p, obj);
    }

    // ---------------- rendering ----------------

    static final class Building {
        final List<float[]> rings = new ArrayList<float[]>();   // x0,y0,x1,y1,... in tile pixels; first ring = outer
        float height, minHeight;
        boolean known;                                           // height came from the data (else it's a guess)
        String cls;                                              // Overture class, e.g. house, apartments, commercial
        int idHash;                                              // stable per building (same in every tile it spans)
        float minX, minY, maxX, maxY;                            // outer ring bounds
        int orient = 1;                                          // +1 if the outer ring has positive area (MVT exterior)
        float depth;                                             // sort key for the current view
        float area;                                              // footprint, square metres

        /** Copy mapped to pixels: p * scale - off. */
        Building scaled(float scale, float offX, float offY) {
            Building c = new Building();
            for (float[] r : rings) {
                float[] q = new float[r.length];
                for (int i = 0; i < r.length; i += 2) { q[i] = r[i] * scale - offX; q[i + 1] = r[i + 1] * scale - offY; }
                c.rings.add(q);
            }
            c.height = height; c.minHeight = minHeight; c.known = known; c.cls = cls; c.idHash = idHash;
            c.orient = orient; c.area = area;
            c.minX = minX * scale - offX; c.maxX = maxX * scale - offX;
            c.minY = minY * scale - offY; c.maxY = maxY * scale - offY;
            return c;
        }
    }

    // ---------------- camera: buildings lean "up the screen", so they follow the map's rotation and tilt ----------------
    volatile float bearing;   // degrees clockwise from north, as last applied
    volatile float tilt = 55; // degrees

    /** How far roofs lean compared with the 55 degree default: flatter view = shorter lean (never zero, it's a game map). */
    static float tiltScale(float tilt) {
        return (float) (0.5 + 0.5 * Math.sin(Math.toRadians(tilt)) / Math.sin(Math.toRadians(55)));
    }

    byte[] render(int x, int y, int z) throws Exception {
        if (z < MIN_ZOOM || z > MAX_ZOOM) return null;
        double th = Math.toRadians(bearing);
        // In tile pixels (x east, y south): "up the screen" = (sin b, -cos b); towards the viewer = the opposite.
        final float ux = (float) Math.sin(th), uy = (float) -Math.cos(th);
        final float rx = -uy, ry = ux;                       // screen-right, for lighting
        int n = 1 << z;

        List<Building> list = new ArrayList<Building>();
        load(list, x, y, z, 0, 0);
        // roofs of buildings on the "down-screen" side lean into this tile: load those neighbours too
        int sx = ux > 0.05f ? -1 : ux < -0.05f ? 1 : 0, sy = uy > 0.05f ? -1 : uy < -0.05f ? 1 : 0;
        if (sx != 0) load(list, (x + sx + n) % n, y, z, sx * SIZE, 0);
        if (sy != 0 && y + sy >= 0 && y + sy < n) load(list, x, y + sy, z, 0, sy * SIZE);
        if (sx != 0 && sy != 0 && y + sy >= 0 && y + sy < n) load(list, (x + sx + n) % n, y + sy, z, sx * SIZE, sy * SIZE);
        if (list.isEmpty()) return null;

        // ground metres per tile pixel, at this tile's latitude
        double lat = Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 0.5) / n)));
        double mpp = 40075016.686 * Math.cos(lat) / n / SIZE;
        float pxPerMetre = (float) (exaggeration(z) * tiltScale(tilt) / mpp);

        // back to front: furthest up the screen first
        for (Building b : list) {
            float[] o = b.rings.get(0);
            float d = -Float.MAX_VALUE;
            for (int i = 0; i < o.length; i += 2) d = Math.max(d, -(o[i] * ux + o[i + 1] * uy));
            b.depth = d;
        }
        Collections.sort(list, new Comparator<Building>() {
            public int compare(Building a, Building b) { return Float.compare(a.depth, b.depth); }
        });

        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(z >= 15 ? 2f : 1.2f);
        line.setColor(edge);
        boolean drew = false;
        Path w = new Path(), r = new Path();
        r.setFillType(Path.FillType.EVEN_ODD);

        for (Building b : list) {
            float lift = Math.min(Math.max(b.height, MIN_HEIGHT) * pxPerMetre, SIZE * 0.45f);
            float base = Math.min(b.minHeight * pxPerMetre, lift);
            float lx = ux * lift, ly = uy * lift;
            // skip if neither the footprint nor the raised roof touches this tile
            if (Math.max(b.maxX, b.maxX + lx) < 0 || Math.min(b.minX, b.minX + lx) > SIZE
                    || Math.max(b.maxY, b.maxY + ly) < 0 || Math.min(b.minY, b.minY + ly) > SIZE) continue;
            float bx = ux * base, by = uy * base;
            float[] outer = b.rings.get(0);
            int pts = outer.length / 2;
            // walls: only the ones facing the viewer, shaded by which way they face on screen
            for (int i = 0; i < pts; i++) {
                float x1 = outer[i * 2], y1 = outer[i * 2 + 1];
                float x2 = outer[((i + 1) % pts) * 2], y2 = outer[((i + 1) % pts) * 2 + 1];
                float nx = (y2 - y1) * b.orient, ny = -(x2 - x1) * b.orient;      // outward normal
                if (-(nx * ux + ny * uy) <= 0) continue;                       // faces away from the camera
                float len = (float) Math.sqrt(nx * nx + ny * ny);
                float side = len > 0 ? (nx * rx + ny * ry) / len : 0;          // -1 left-facing .. +1 right-facing
                w.rewind();
                w.moveTo(x1 + bx, y1 + by); w.lineTo(x2 + bx, y2 + by); w.lineTo(x2 + lx, y2 + ly); w.lineTo(x1 + lx, y1 + ly); w.close();
                fill.setColor(shade(roof, 0.675f - 0.125f * side));            // lit from the left
                c.drawPath(w, fill);
            }
            r.rewind();
            for (float[] ring : b.rings) {
                r.moveTo(ring[0] + lx, ring[1] + ly);
                for (int i = 2; i < ring.length; i += 2) r.lineTo(ring[i] + lx, ring[i + 1] + ly);
                r.close();
            }
            fill.setColor(roof);
            c.drawPath(r, fill);
            c.drawPath(r, line);
            drew = true;
        }
        if (!drew) { bmp.recycle(); return null; }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        bmp.recycle();
        return o.toByteArray();
    }

    /**
     * Adds the buildings of map tile (x, y, z), shifted by (dx, dy) pixels. Always decodes zoom-14 data: deeper zooms
     * show part of one z14 tile, zoom 13 stitches four (lower-zoom data is thinned out and has no heights).
     */
    void load(List<Building> out, int x, int y, int z, float dx, float dy) throws Exception {
        if (z < DATA_ZOOM) {
            int k = 1 << (DATA_ZOOM - z);
            float s = SIZE / (float) k;
            for (int j = 0; j < k; j++)
                for (int i = 0; i < k; i++)
                    decodeSource(out, DATA_ZOOM, x * k + i, y * k + j, s, -(i * s) - dx, -(j * s) - dy);
            return;
        }
        int shift = z - DATA_ZOOM, sub = 1 << shift;
        int sx = x >> shift, sy = y >> shift;
        float ox = (x - sx * sub), oy = (y - sy * sub);       // which part of the source tile this map tile shows
        decodeSource(out, DATA_ZOOM, sx, sy, SIZE * sub, ox * SIZE - dx, oy * SIZE - dy);
    }

    /**
     * Adds the buildings of data tile (sx, sy, dz) drawn tilePx wide minus (offX, offY), but only those that can reach
     * the map tile being drawn (roofs lean up to 0.45 tile) and are big enough to see at this zoom.
     */
    void decodeSource(List<Building> out, int dz, int sx, int sy, float tilePx, float offX, float offY) throws Exception {
        List<Building> all = data(dz, sx, sy);
        if (all == null) return;
        float lo = -SIZE * 0.5f, hi = SIZE * 1.5f;
        float minArea = tilePx <= SIZE / 2f ? 50 : tilePx <= SIZE ? 20 : 0;   // m²; specks of a few pixels at z13/z14
        for (Building b : all) {
            if (b.area < minArea) continue;
            if (b.maxX * tilePx - offX < lo || b.minX * tilePx - offX > hi || b.maxY * tilePx - offY < lo || b.minY * tilePx - offY > hi) continue;
            out.add(b.scaled(tilePx, offX, offY));
        }
    }

    List<Building> data(int dz, int sx, int sy) throws Exception {
        String key = dz + "/" + sx + "/" + sy;
        List<Building> l = decoded.get(key);
        if (l != null) return l;
        byte[] pbf = fetch(dz, sx, sy);
        if (pbf == null) return null;
        l = new ArrayList<Building>();
        Mvt.decodeBuildings(pbf, l, 1f, 0f, 0f);
        double lat = Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (sy + 0.5) / (1 << dz))));
        double tileMetres = 40075016.686 * Math.cos(lat) / (1 << dz);
        Heights.estimate(l, 1f, 0f, 0f, tileMetres);
        decoded.put(key, l);
        return l;
    }

    // ---------------- network + cache ----------------

    /**
     * Building footprints: Overture Maps (OpenStreetMap plus machine-traced footprints from Microsoft/Google, so far
     * fewer missing buildings), read from its PMTiles archive. Falls back to OpenFreeMap if Overture can't be reached.
     */
    byte[] fetch(int z, int x, int y) throws Exception {
        String key = z + "/" + x + "/" + y;
        byte[] b;
        File dir = new File(ctx.getCacheDir(), "mapskin_buildings");
        File f = new File(dir, "ov_" + z + "_" + x + "_" + y + ".pbf");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) {
            b = readAll(new FileInputStream(f));
        } else {
            b = null;
            try {
                Pmtiles ov = overture();
                if (ov != null) { b = ov.tile(z, x, y); if (b == null) b = new byte[0]; }
            } catch (Throwable t) { Hook.log("3D buildings: Overture tile " + key + " failed (" + t + "), using OpenFreeMap"); }
            if (b == null) {
                b = openFreeMap(z, x, y);
                if (b == null) return null;
                f = new File(dir, z + "_" + x + "_" + y + ".pbf");
            }
            dir.mkdirs();
            FileOutputStream o = new FileOutputStream(f);
            o.write(b);
            o.close();
        }
        if (b.length > 2 && (b[0] & 255) == 0x1f && (b[1] & 255) == 0x8b) b = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(b)));
        return b.length == 0 ? null : b;
    }

    private Pmtiles overture;
    private long overtureFailedAt;

    /** The latest Overture release's buildings archive (from Overture's release catalog), or null if unreachable. */
    synchronized Pmtiles overture() {
        if (overture != null) return overture;
        if (System.currentTimeMillis() - overtureFailedAt < 5 * 60 * 1000) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://stac.overturemaps.org/catalog.json").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            String latest = new org.json.JSONObject(new String(readAll(c.getInputStream()), "UTF-8")).getString("latest");
            overture = new Pmtiles("https://tiles.overturemaps.org/" + latest + "/buildings.pmtiles");
            Hook.log("3D buildings: Overture release " + latest);
            return overture;
        } catch (Throwable t) {
            overtureFailedAt = System.currentTimeMillis();
            Hook.log("3D buildings: Overture catalog unreachable (" + t + ")");
            return null;
        }
    }

    /** Raw OpenFreeMap tile bytes; empty if it has none; null on network failure. */
    byte[] openFreeMap(int z, int x, int y) throws Exception {
        String url = url();
        if (url == null) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(url.replace("{z}", "" + z).replace("{x}", "" + x).replace("{y}", "" + y)).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code == 200) return readAll(c.getInputStream());
        return code == 204 || code == 404 ? new byte[0] : null;
    }

    synchronized String url() throws Exception {
        if (tileUrl != null) return tileUrl;
        HttpURLConnection c = (HttpURLConnection) new URL("https://tiles.openfreemap.org/planet").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        String json = new String(readAll(c.getInputStream()), "UTF-8");
        tileUrl = new org.json.JSONObject(json).getJSONArray("tiles").getString(0);
        Hook.log("3D buildings: tiles from " + tileUrl);
        return tileUrl;
    }

    static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally { in.close(); }
    }
}
