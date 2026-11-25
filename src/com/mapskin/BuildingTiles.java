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
 * raised "3D" buildings from OpenStreetMap data (OpenFreeMap vector tiles) at zoom levels where Google
 * doesn't draw its own 3D buildings. Each building: shaded walls, a roof lifted by its real height, neon edge.
 */
public final class BuildingTiles extends Binder {
    static final String DESC = "com.google.android.gms.maps.model.internal.ITileProviderDelegate";
    static final int TX_GET_TILE = 1;
    static final int MIN_ZOOM = 13;          // OpenMapTiles has buildings from z13
    static final int MAX_ZOOM = 16;          // from z17 Google draws its own 3D buildings
    static final int DATA_ZOOM = 14;         // highest zoom the vector tiles exist at
    static final int SIZE = 512;             // tile bitmap size (crisp on high-dpi screens)
    static final float LEAN = 0.55f;         // how far roofs are lifted per metre of height, relative to ground scale

    final Context ctx;
    final int roof, wallLight, wallDark, edge;
    String tileUrl;                           // e.g. https://tiles.openfreemap.org/planet/<version>/{z}/{x}/{y}.pbf
    final Map<String, byte[]> mem = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 48; }
    });

    BuildingTiles(Context ctx, int roof, int edge) {
        this.ctx = ctx;
        this.roof = roof;
        this.edge = edge;
        this.wallLight = shade(roof, 0.62f);
        this.wallDark = shade(roof, 0.38f);
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
        try { png = render(x, y, z); } catch (Throwable t) { Hook.log(t); }
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
        float height, minHeight, maxY;
    }

    byte[] render(int x, int y, int z) throws Exception {
        if (z < MIN_ZOOM || z > MAX_ZOOM) return null;
        List<Building> list = new ArrayList<Building>();
        load(list, x, y, z, 0);
        int n = 1 << z;
        if (y + 1 < n) load(list, x, y + 1, z, SIZE);       // roofs of buildings just south of this tile lean into it
        if (list.isEmpty()) return null;

        // ground metres per tile pixel, at this tile's latitude
        double lat = Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 0.5) / n)));
        double mpp = 40075016.686 * Math.cos(lat) / n / SIZE;
        float pxPerMetre = (float) (LEAN / mpp);

        Collections.sort(list, new Comparator<Building>() {   // back to front: northern (top) buildings first
            public int compare(Building a, Building b) { return Float.compare(a.maxY, b.maxY); }
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

        for (Building b : list) {
            float lift = Math.min(Math.max(b.height, 4f) * pxPerMetre, SIZE * 0.4f);
            float base = Math.min(b.minHeight * pxPerMetre, lift);
            if (b.maxY - lift > SIZE || b.maxY < 0) continue;    // nothing of it lands on this tile
            float[] outer = b.rings.get(0);
            int pts = outer.length / 2;
            // walls: one quad per edge, lit from the south (south-facing walls lighter)
            for (int i = 0; i < pts; i++) {
                float x1 = outer[i * 2], y1 = outer[i * 2 + 1];
                float x2 = outer[((i + 1) % pts) * 2], y2 = outer[((i + 1) % pts) * 2 + 1];
                Path w = new Path();
                w.moveTo(x1, y1 - base); w.lineTo(x2, y2 - base); w.lineTo(x2, y2 - lift); w.lineTo(x1, y1 - lift); w.close();
                fill.setColor((x2 - x1) > 0 ? wallLight : wallDark);
                c.drawPath(w, fill);
            }
            Path r = new Path();
            r.setFillType(Path.FillType.EVEN_ODD);
            for (float[] ring : b.rings) {
                r.moveTo(ring[0], ring[1] - lift);
                for (int i = 2; i < ring.length; i += 2) r.lineTo(ring[i], ring[i + 1] - lift);
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

    /** Adds the buildings of map tile (x, y, z), shifted down by dy pixels, decoded from the matching vector tile. */
    void load(List<Building> out, int x, int y, int z, float dy) throws Exception {
        int dz = Math.min(z, DATA_ZOOM), shift = z - dz;
        int sx = x >> shift, sy = y >> shift;
        byte[] pbf = fetch(dz, sx, sy);
        if (pbf == null) return;
        int sub = 1 << shift;
        float ox = (x - sx * sub), oy = (y - sy * sub);       // which part of the source tile this map tile shows
        Mvt.decodeBuildings(pbf, out, SIZE * sub, ox * SIZE, oy * SIZE - dy);
    }

    // ---------------- network + cache ----------------

    byte[] fetch(int z, int x, int y) throws Exception {
        String key = z + "/" + x + "/" + y;
        byte[] b = mem.get(key);
        if (b != null) return b.length == 0 ? null : b;
        File dir = new File(ctx.getCacheDir(), "mapskin_buildings");
        File f = new File(dir, z + "_" + x + "_" + y + ".pbf");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) {
            b = readAll(new FileInputStream(f));
        } else {
            String url = url();
            if (url == null) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(url.replace("{z}", "" + z).replace("{x}", "" + x).replace("{y}", "" + y)).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            b = code == 200 ? readAll(c.getInputStream()) : new byte[0];
            if (code == 200 || code == 204 || code == 404) {
                dir.mkdirs();
                FileOutputStream o = new FileOutputStream(f);
                o.write(b);
                o.close();
            }
        }
        if (b.length > 2 && (b[0] & 255) == 0x1f && (b[1] & 255) == 0x8b) b = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(b)));
        mem.put(key, b);
        return b.length == 0 ? null : b;
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
