package com.mapskin;

/** Ear-clipping triangulation for building roofs (simple polygons; courtyards are roofed over). */
final class Earcut {
    /**
     * Adds the triangles of polygon p (x0,y0,x1,y1,... n points) at height z, shade 1, to out.
     * @param ccw whether p winds counter-clockwise (x east, y north)
     */
    static void triangulate(float[] p, int n, boolean ccw, float z, GlBuildings.FloatList out) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = ccw ? i : n - 1 - i;     // work counter-clockwise
        int m = n, guard = 0;
        if (n > 300) { fan(p, idx, m, z, out); return; }
        int i = 0;
        while (m > 3 && guard < n * n) {
            guard++;
            int a = idx[(i + m - 1) % m], b = idx[i % m], c = idx[(i + 1) % m];
            if (isEar(p, idx, m, a, b, c)) {
                tri(p, a, b, c, z, out);
                for (int k = i % m; k < m - 1; k++) idx[k] = idx[k + 1];
                m--;
                if (i >= m) i = 0;
            } else i = (i + 1) % m;
        }
        if (m > 3) fan(p, idx, m, z, out);                          // self-intersecting outline: best effort
        else if (m == 3) tri(p, idx[0], idx[1], idx[2], z, out);
    }

    static boolean isEar(float[] p, int[] idx, int m, int a, int b, int c) {
        float ax = p[a * 2], ay = p[a * 2 + 1], bx = p[b * 2], by = p[b * 2 + 1], cx = p[c * 2], cy = p[c * 2 + 1];
        float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross <= 0) return false;                                // reflex corner
        for (int k = 0; k < m; k++) {
            int v = idx[k];
            if (v == a || v == b || v == c) continue;
            float px = p[v * 2], py = p[v * 2 + 1];
            if (side(ax, ay, bx, by, px, py) >= 0 && side(bx, by, cx, cy, px, py) >= 0 && side(cx, cy, ax, ay, px, py) >= 0) return false;
        }
        return true;
    }

    static float side(float ax, float ay, float bx, float by, float px, float py) { return (bx - ax) * (py - ay) - (by - ay) * (px - ax); }

    static void tri(float[] p, int a, int b, int c, float z, GlBuildings.FloatList out) {
        out.add(p[a * 2], p[a * 2 + 1], z, 1f);
        out.add(p[b * 2], p[b * 2 + 1], z, 1f);
        out.add(p[c * 2], p[c * 2 + 1], z, 1f);
    }

    static void fan(float[] p, int[] idx, int m, float z, GlBuildings.FloatList out) {
        for (int k = 1; k < m - 1; k++) tri(p, idx[0], idx[k], idx[k + 1], z, out);
    }
}
