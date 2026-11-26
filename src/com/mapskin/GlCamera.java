package com.mapskin;

/**
 * Rebuilds Google Maps' 3D camera from what its projection reports, so MapSkin's own OpenGL buildings line up with
 * the map exactly, in true perspective (like a Mapbox-style 3D map).
 *
 * Each frame the map tells us where five ground points land on screen: the camera target and points a metres
 * left / right / ahead / behind it. From those: k (pixels per metre at the target), the target's screen position
 * (cx, cy), the tilt (c = cos, s = sin) and D, the camera's distance (how strong the perspective is).
 *
 * Camera model, for a point x metres right, y metres ahead, z metres up from the target:
 *   w = D + y·s − z·c      (depth)
 *   u = cx + k·x·D / w      v = cy − k·(y·c + z·s)·D / w
 * All distances are Web Mercator metres (what the map is drawn in); real heights are scaled by 1/cos(latitude).
 */
final class GlCamera {
    static final double R = 6378137, HALF = Math.PI * R;

    double et, nt;          // target, Mercator metres
    double bearing;         // radians, clockwise from north
    double k, D, c, s, cx, cy, W, H, zoom;

    static double mercE(double lonDeg) { return R * Math.toRadians(lonDeg); }
    static double mercN(double latDeg) { return R * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latDeg) / 2)); }
    static double lon(double e) { return Math.toDegrees(e / R); }
    static double lat(double n) { return Math.toDegrees(2 * Math.atan(Math.exp(n / R)) - Math.PI / 2); }

    /** Probe points {E, N}: target, left, right, ahead, behind (a metres away, along the camera's bearing). */
    static double[][] probes(double et, double nt, double bearing, double a) {
        double fe = Math.sin(bearing), fn = Math.cos(bearing);   // ahead
        double re = Math.cos(bearing), rn = -Math.sin(bearing);  // right
        return new double[][]{
            {et, nt}, {et - a * re, nt - a * rn}, {et + a * re, nt + a * rn}, {et + a * fe, nt + a * fn}, {et - a * fe, nt - a * fn}
        };
    }

    /** screen = {u, v} of the five probes in the same order; null if the map gave something unusable. */
    static GlCamera fit(double et, double nt, double bearing, double a, double[][] screen, double W, double H, double zoom) {
        GlCamera g = new GlCamera();
        g.et = et; g.nt = nt; g.bearing = bearing; g.W = W; g.H = H; g.zoom = zoom;
        g.cx = screen[0][0]; g.cy = screen[0][1];
        g.k = Math.hypot(screen[2][0] - screen[1][0], screen[2][1] - screen[1][1]) / (2 * a);
        if (!(g.k > 0) || Double.isInfinite(g.k)) return null;
        double p = (g.cy - screen[3][1]) / (g.k * a), q = (screen[4][1] - g.cy) / (g.k * a);
        if (!(p > 0) || !(q > 0)) return null;
        double ip = 1 / p, iq = 1 / q;
        g.c = Math.min(1, 2 / (ip + iq));
        g.s = Math.sqrt(Math.max(0, 1 - g.c * g.c));
        double diff = ip - iq;
        double far = 2e4 * a;                                   // ~no perspective (looking straight down)
        g.D = diff > 1e-6 ? Math.min(far, 2 * a * g.s / (g.c * diff)) : far;
        return g;
    }

    /** Where ground/3D point (E, N, z) lands on screen, by the model (for checks). */
    double[] project(double e, double n, double z) {
        double dx = e - et, dy = n - nt, cb = Math.cos(bearing), sb = Math.sin(bearing);
        double x = dx * cb - dy * sb, y = dx * sb + dy * cb;
        double w = D + y * s - z * c;
        return new double[]{cx + k * x * D / w, cy - k * (y * c + z * s) * D / w};
    }

    /**
     * Column-major model-view-projection for vertices (vx, vy, vz) in metres relative to origin (e0, n0), straight
     * to OpenGL clip space for a W x H viewport.
     */
    void mvp(double e0, double n0, float[] m) {
        double cb = Math.cos(bearing), sb = Math.sin(bearing);
        double ox = e0 - et, oy = n0 - nt;
        double x0 = ox * cb - oy * sb, y0 = ox * sb + oy * cb;
        // rows as coefficient vectors over (vx, vy, vz, 1)
        double[] X = {cb, -sb, 0, x0};
        double[] Yg = {sb, cb, 0, y0};
        double[] Wp = {s * sb, s * cb, -c, D + s * y0};
        double[] Yc = {c * sb, c * cb, s, c * y0};
        double ax = 2 * cx / W - 1, bx = 2 * k * D / W;
        double ay = 1 - 2 * cy / H, by = 2 * k * D / H;
        double extent = 3 * Math.max(W, H) / k;                  // metres; generous view depth
        double near = Math.max(D * 0.02, D - extent), far = D + 4 * extent;
        double A = (far + near) / (far - near), B = -2 * far * near / (far - near);
        for (int col = 0; col < 4; col++) {
            m[col * 4]     = (float) (ax * Wp[col] + bx * X[col]);
            m[col * 4 + 1] = (float) (ay * Wp[col] + by * Yc[col]);
            m[col * 4 + 2] = (float) (A * Wp[col] + (col == 3 ? B : 0));
            m[col * 4 + 3] = (float) Wp[col];
        }
    }
}
