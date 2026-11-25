package com.mapskin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads single tiles out of a remote PMTiles v3 archive (one big file on a web server) with HTTP range requests:
 * header -> root directory -> (leaf directory) -> tile bytes. Plain java.net, so it works in the app and on a PC.
 */
final class Pmtiles {
    final String url;
    long rootOff, rootLen, leafOff, tileOff;
    int internalComp, tileComp, minZoom, maxZoom;
    private boolean ready;
    private final Map<Long, long[][]> dirs = Collections.synchronizedMap(new LinkedHashMap<Long, long[][]>(32, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, long[][]> e) { return size() > 24; }
    });

    Pmtiles(String url) { this.url = url; }

    private synchronized void init() throws Exception {
        if (ready) return;
        byte[] h = range(0, 127);
        if (h.length < 127 || h[0] != 'P' || h[1] != 'M' || h[7] != 3) throw new IllegalStateException("not PMTiles v3: " + url);
        rootOff = u64(h, 8); rootLen = u64(h, 16);
        leafOff = u64(h, 40);
        tileOff = u64(h, 56);
        internalComp = h[97]; tileComp = h[98];
        minZoom = h[100] & 255; maxZoom = h[101] & 255;
        ready = true;
    }

    /** Raw (decompressed) tile bytes, or null if the archive has no such tile. */
    byte[] tile(int z, int x, int y) throws Exception {
        init();
        if (z < minZoom || z > maxZoom) return null;
        long id = tileId(z, x, y);
        long dOff = rootOff, dLen = rootLen;
        for (int depth = 0; depth < 4; depth++) {
            long[][] dir = dir(dOff, dLen);
            long[] e = find(dir, id);
            if (e == null) return null;
            if (e[3] > 0) {                                   // run length > 0: a tile
                return decompress(range(tileOff + e[1], (int) e[2]), tileComp);
            }
            dOff = leafOff + e[1]; dLen = e[2];               // run length 0: a leaf directory
        }
        return null;
    }

    /** Entries as {tileId, offset, length, runLength}. */
    private long[][] dir(long off, long len) throws Exception {
        long[][] d = dirs.get(off);
        if (d != null) return d;
        byte[] b = decompress(range(off, (int) len), internalComp);
        int[] p = {0};
        int n = (int) varint(b, p);
        d = new long[n][4];
        long last = 0;
        for (int i = 0; i < n; i++) { last += varint(b, p); d[i][0] = last; }
        for (int i = 0; i < n; i++) d[i][3] = varint(b, p);
        for (int i = 0; i < n; i++) d[i][2] = varint(b, p);
        for (int i = 0; i < n; i++) {
            long v = varint(b, p);
            d[i][1] = (v == 0 && i > 0) ? d[i - 1][1] + d[i - 1][2] : v - 1;
        }
        dirs.put(off, d);
        return d;
    }

    private static long[] find(long[][] d, long id) {
        int lo = 0, hi = d.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            long c = id - d[m][0];
            if (c > 0) lo = m + 1; else if (c < 0) hi = m - 1; else return d[m];
        }
        if (hi >= 0) {                                        // id may sit inside a run, or under a leaf
            long[] e = d[hi];
            if (e[3] == 0) return e;
            if (id - e[0] < e[3]) return e;
        }
        return null;
    }

    static long tileId(int z, long x, long y) {
        long acc = 0;
        for (int t = 0; t < z; t++) acc += 1L << (2 * t);
        long n = 1L << z, d = 0;
        for (long s = n / 2; s > 0; s /= 2) {               // Hilbert curve index of (x, y)
            long rx = (x & s) > 0 ? 1 : 0, ry = (y & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { x = s - 1 - x; y = s - 1 - y; }
                long t = x; x = y; y = t;
            }
        }
        return acc + d;
    }

    private static long varint(byte[] b, int[] p) {
        long r = 0;
        for (int s = 0; s < 64; s += 7) {
            int c = b[p[0]++] & 255;
            r |= (long) (c & 127) << s;
            if (c < 128) break;
        }
        return r;
    }

    private static long u64(byte[] b, int o) {
        long r = 0;
        for (int i = 7; i >= 0; i--) r = (r << 8) | (b[o + i] & 255);
        return r;
    }

    private static byte[] decompress(byte[] b, int comp) throws Exception {
        if (comp == 2 || (b.length > 2 && (b[0] & 255) == 0x1f && (b[1] & 255) == 0x8b))
            return readAll(new GZIPInputStream(new ByteArrayInputStream(b)));
        if (comp > 2) throw new IllegalStateException("unsupported PMTiles compression " + comp);
        return b;
    }

    byte[] range(long off, int len) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Range", "bytes=" + off + "-" + (off + len - 1));
        int code = c.getResponseCode();
        if (code != 206 && code != 200) throw new IllegalStateException("HTTP " + code + " for " + url);
        byte[] b = readAll(c.getInputStream());
        if (code == 200 && b.length > len) throw new IllegalStateException("server ignored Range");
        return b;
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
