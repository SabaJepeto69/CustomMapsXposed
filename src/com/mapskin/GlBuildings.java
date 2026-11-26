package com.mapskin;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Parcel;
import android.view.Choreographer;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real 3D buildings, the way GPU map engines (Mapbox, which Game Maps IRL uses) draw them: extruded blocks in true
 * perspective, drawn with OpenGL in a transparent layer laid exactly over the Google map. Every frame the layer reads
 * Google's camera (see {@link GlCamera}), so buildings turn, tilt and zoom with the map with no redrawing of tiles.
 * Touches pass straight through to the map underneath.
 */
final class GlBuildings extends TextureView implements TextureView.SurfaceTextureListener, Choreographer.FrameCallback {
    static final String PROJECTION = "com.google.android.gms.maps.internal.IProjectionDelegate";
    static final int TX_GET_PROJECTION = 26, TX_TO_SCREEN = 2;
    static final int DATA_ZOOM = 14;
    static final double TILE_M = 2 * GlCamera.HALF / (1 << DATA_ZOOM);   // z14 tile width, Mercator metres
    static final int MAX_TILES = 20;
    static final float MIN_ZOOM = 12.5f;                                 // fade in from here to 13.5

    final android.os.Binder engine;
    final BuildingTiles data;           // download + decode + height estimates (shared with the tile fallback)
    final float[] roof, edge;
    final float density;
    final Runnable onFail;
    volatile GlCamera cam;
    volatile boolean failed;
    Render render;

    GlBuildings(Context ctx, android.os.Binder engine, BuildingTiles data, int roof, int edge, Runnable onFail) {
        super(ctx);
        this.engine = engine;
        this.data = data;
        this.roof = rgb(roof);
        this.edge = rgb(edge);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.onFail = onFail;
        setOpaque(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
    }

    static float[] rgb(int c) { return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f}; }

    // ---------------- camera, read on the main thread every frame ----------------

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    int camFails;

    @Override public void doFrame(long nanos) {
        if (!isAttachedToWindow() || failed) return;
        try {
            GlCamera g = readCamera();
            if (g != null) {
                camFails = 0;
                GlCamera old = cam;
                if (old == null || differs(old, g)) {
                    cam = g;
                    Render r = render;
                    if (r != null) { r.wake(); loader.update(g, r); }
                }
            } else if (++camFails == 120) Hook.log("3D buildings: can't read the map camera");
        } catch (Throwable t) { Hook.log(t); }
        Choreographer.getInstance().postFrameCallback(this);
    }

    static boolean differs(GlCamera a, GlCamera b) {
        return a.et != b.et || a.nt != b.nt || a.bearing != b.bearing || a.k != b.k || a.c != b.c || a.D != b.D
                || a.cx != b.cx || a.cy != b.cy || a.W != b.W || a.H != b.H;
    }

    GlCamera readCamera() {
        double[] pos = cameraPosition(engine);            // lat, lng, zoom, tilt, bearing
        if (pos == null || getWidth() == 0) return null;
        IBinderHolder proj = projection();
        if (proj == null) return null;
        double et = GlCamera.mercE(pos[1]), nt = GlCamera.mercN(pos[0]), bearing = Math.toRadians(pos[4]);
        double ppm = 256 * Math.pow(2, pos[2]) * density / (2 * GlCamera.HALF);    // rough px per metre, sizes probes
        double a = 120 / ppm;
        double[][] pr = GlCamera.probes(et, nt, bearing, a);
        double[][] sc = new double[5][];
        for (int i = 0; i < 5; i++) {
            sc[i] = toScreen(proj.b, GlCamera.lat(pr[i][1]), GlCamera.lon(pr[i][0]));
            if (sc[i] == null) return null;
        }
        return GlCamera.fit(et, nt, bearing, a, sc, getWidth(), getHeight(), pos[2]);
    }

    static final class IBinderHolder { final android.os.IBinder b; IBinderHolder(android.os.IBinder b) { this.b = b; } }

    IBinderHolder projection() {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(Hook.MAP_DELEGATE);
            engine.transact(TX_GET_PROJECTION, d, r, 0);
            r.readException();
            android.os.IBinder b = r.readStrongBinder();
            return b == null ? null : new IBinderHolder(b);
        } catch (Throwable t) { return null; }
        finally { d.recycle(); r.recycle(); }
    }

    /** IProjectionDelegate.toScreenLocation(LatLng) -> IObjectWrapper(Point); the wrapper is local, so unwrap it. */
    static double[] toScreen(android.os.IBinder proj, double lat, double lng) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(PROJECTION);
            d.writeInt(1);
            int obj = BuildingTiles.begin(d, 20293);
            d.writeInt((8 << 16) | 2); d.writeDouble(lat);
            d.writeInt((8 << 16) | 3); d.writeDouble(lng);
            BuildingTiles.end(d, obj);
            proj.transact(TX_TO_SCREEN, d, r, 0);
            r.readException();
            Object p = unwrap(r.readStrongBinder());
            if (!(p instanceof android.graphics.Point)) return null;
            android.graphics.Point pt = (android.graphics.Point) p;
            return new double[]{pt.x, pt.y};
        } catch (Throwable t) { return null; }
        finally { d.recycle(); r.recycle(); }
    }

    static final Map<Class<?>, java.lang.reflect.Field> wrapperField = new HashMap<Class<?>, java.lang.reflect.Field>();

    static Object unwrap(android.os.IBinder b) throws Exception {
        if (b == null) return null;
        Class<?> k = b.getClass();
        java.lang.reflect.Field f;
        synchronized (wrapperField) { f = wrapperField.get(k); }
        if (f != null) return f.get(b);
        for (Class<?> c = k; c != null && c != android.os.Binder.class; c = c.getSuperclass())
            for (java.lang.reflect.Field x : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(x.getModifiers()) || x.getType().isPrimitive()) continue;
                x.setAccessible(true);
                Object v = x.get(b);
                if (v instanceof android.graphics.Point) { synchronized (wrapperField) { wrapperField.put(k, x); } return v; }
            }
        return null;
    }

    /** {lat, lng, zoom, tilt, bearing} from map transaction 1, read straight from the CameraPosition SafeParcel. */
    static double[] cameraPosition(android.os.Binder engine) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(Hook.MAP_DELEGATE);
            engine.transact(1, d, r, 0);
            r.readException();
            if (r.readInt() == 0) return null;
            double[] out = new double[5];
            int stop = objStart(r);
            if (stop < 0) return null;
            boolean target = false;
            while (r.dataPosition() < stop) {
                int fh = r.readInt(), id = fh & 0xFFFF;
                int fs = (fh >>> 16) == 0xFFFF ? r.readInt() : fh >>> 16;
                int next = r.dataPosition() + fs;
                if (id == 2) {                                   // LatLng target
                    int lstop = objStart(r);
                    while (lstop > 0 && r.dataPosition() < lstop) {
                        int lh = r.readInt(), lid = lh & 0xFFFF;
                        int ls = (lh >>> 16) == 0xFFFF ? r.readInt() : lh >>> 16;
                        int lnext = r.dataPosition() + ls;
                        if (lid == 2) out[0] = r.readDouble();
                        else if (lid == 3) out[1] = r.readDouble();
                        r.setDataPosition(lnext);
                    }
                    target = true;
                } else if (id == 3) out[2] = r.readFloat();
                else if (id == 4) out[3] = r.readFloat();
                else if (id == 5) out[4] = r.readFloat();
                r.setDataPosition(next);
            }
            return target ? out : null;
        } catch (Throwable t) { return null; }
        finally { d.recycle(); r.recycle(); }
    }

    /** Reads a SafeParcel object header; returns where the object ends, or -1. */
    static int objStart(Parcel r) {
        int h = r.readInt();
        if ((h & 0xFFFF) != 20293) return -1;
        int size = (h >>> 16) == 0xFFFF ? r.readInt() : h >>> 16;
        return r.dataPosition() + size;
    }

    // ---------------- building meshes, built off the main thread ----------------

    static final class Mesh {
        final String key;
        final double e0, n0;            // tile origin (north-west corner), Mercator metres
        float[] tris, lines;            // x, y, z, shade per vertex
        int triCount, lineCount, triVbo, lineVbo;
        Mesh(String key, double e0, double n0) { this.key = key; this.e0 = e0; this.n0 = n0; }
    }

    final Loader loader = new Loader();

    final class Loader {
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final Set<String> wanted = new HashSet<String>(), pending = new HashSet<String>();
        String lastCenter;

        /** Pick the z14 tiles around (mostly ahead of) the camera; build any that are missing. */
        void update(GlCamera g, final Render render) {
            double view = Math.max(600, Math.min(5000, Math.max(g.W, g.H) / g.k * 1.2));
            double ce = g.et + Math.sin(g.bearing) * view * 0.35, cn = g.nt + Math.cos(g.bearing) * view * 0.35;
            int tx = (int) Math.floor((ce + GlCamera.HALF) / TILE_M), ty = (int) Math.floor((GlCamera.HALF - cn) / TILE_M);
            int rad = (int) Math.ceil(view / TILE_M);
            String center = tx + "/" + ty + "/" + rad;
            if (center.equals(lastCenter)) return;
            lastCenter = center;
            final List<double[]> cand = new ArrayList<double[]>();
            int n = 1 << DATA_ZOOM;
            for (int y = ty - rad; y <= ty + rad; y++)
                for (int x = tx - rad; x <= tx + rad; x++) {
                    if (y < 0 || y >= n) continue;
                    double me = (((x % n) + n) % n + 0.5) * TILE_M - GlCamera.HALF, mn = GlCamera.HALF - (y + 0.5) * TILE_M;
                    double d = Math.hypot(me - ce, mn - cn);
                    if (d <= view + TILE_M) cand.add(new double[]{d, ((x % n) + n) % n, y});
                }
            java.util.Collections.sort(cand, new java.util.Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            Set<String> want = new HashSet<String>();
            for (int i = 0; i < cand.size() && i < MAX_TILES; i++) want.add((int) cand.get(i)[1] + "/" + (int) cand.get(i)[2]);
            synchronized (this) { wanted.clear(); wanted.addAll(want); }
            render.retain(want);
            for (int i = 0; i < cand.size() && i < MAX_TILES; i++) {
                final int x = (int) cand.get(i)[1], y = (int) cand.get(i)[2];
                final String key = x + "/" + y;
                synchronized (this) { if (render.has(key) || !pending.add(key)) continue; }
                exec.execute(new Runnable() {
                    public void run() {
                        try {
                            synchronized (Loader.this) { if (!wanted.contains(key)) return; }
                            Mesh m = build(key, x, y);
                            if (m != null) render.add(m);
                        } catch (Throwable t) { Hook.log("3D buildings: tile " + key + " failed"); Hook.log(t); }
                        finally { synchronized (Loader.this) { pending.remove(key); } }
                    }
                });
            }
        }
    }

    static final float LX = -0.45f, LY = -0.89f;   // light comes from the south-south-west (unit vector, E/N)

    Mesh build(String key, int x, int y) throws Exception {
        return mesh(key, x, y, data.data(DATA_ZOOM, x, y));
    }

    static Mesh mesh(String key, int x, int y, List<BuildingTiles.Building> list) {
        double e0 = x * TILE_M - GlCamera.HALF, n0 = GlCamera.HALF - y * TILE_M;
        Mesh m = new Mesh(key, e0, n0);
        if (list == null) { m.tris = new float[0]; m.lines = new float[0]; return m; }
        double lat = Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 0.5) / (1 << DATA_ZOOM))));
        float hScale = (float) (1 / Math.cos(lat));           // real metres -> Mercator metres
        float T = (float) TILE_M;
        FloatList tris = new FloatList(1 << 16), lines = new FloatList(1 << 14);
        for (BuildingTiles.Building b : list) {
            if (b.area < 12) continue;
            float[] r0 = b.rings.get(0);
            int n = r0.length / 2;
            if (n < 3) continue;
            float[] p = new float[n * 2];                     // E/N metres from the tile's north-west corner
            for (int i = 0; i < n; i++) { p[i * 2] = r0[i * 2] * T; p[i * 2 + 1] = -r0[i * 2 + 1] * T; }
            float top = Math.max(b.height, BuildingTiles.MIN_HEIGHT) * hScale;
            float base = Math.min(b.minHeight * hScale, top - 0.5f);
            double area2 = 0;
            for (int i = 0; i < n; i++) { int j = (i + 1) % n; area2 += (double) p[i * 2] * p[j * 2 + 1] - (double) p[j * 2] * p[i * 2 + 1]; }
            boolean ccw = area2 > 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float x1 = p[i * 2], y1 = p[i * 2 + 1], x2 = p[j * 2], y2 = p[j * 2 + 1];
                float ex = x2 - x1, ey = y2 - y1, len = (float) Math.sqrt(ex * ex + ey * ey);
                if (len < 1e-3f) continue;
                float nx = ccw ? ey / len : -ey / len, ny = ccw ? -ex / len : ex / len;   // outward normal
                float lit = 0.5f + 0.38f * Math.max(0f, nx * LX + ny * LY) + 0.08f * Math.abs(nx);
                float low = lit * 0.55f;                        // darker at street level, like a GPU vertical gradient
                tris.add(x1, y1, base, low); tris.add(x2, y2, base, low); tris.add(x2, y2, top, lit);
                tris.add(x1, y1, base, low); tris.add(x2, y2, top, lit); tris.add(x1, y1, top, lit);
                lines.add(x1, y1, top + 0.15f, 1f); lines.add(x2, y2, top + 0.15f, 1f);
            }
            Earcut.triangulate(p, n, ccw, top, tris);
        }
        m.tris = tris.toArray();
        m.lines = lines.toArray();
        return m;
    }

    static final class FloatList {
        float[] a; int n;
        FloatList(int cap) { a = new float[cap]; }
        void add(float x, float y, float z, float w) {
            if (n + 4 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = w;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    // ---------------- OpenGL thread ----------------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        Render r = new Render(st);
        render = r;
        loader.lastCenter = null;                           // new GL context: everything must be uploaded again
        r.start();
        GlCamera g = cam;
        if (g != null) loader.update(g, r);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { if (render != null) render.wake(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        Render r = render;
        render = null;
        if (r != null) r.quit();
        return false;                                       // the render thread releases it after its EGL teardown
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    static final String VS =
        "uniform mat4 uMvp; attribute vec4 aPos; varying float vShade;" +
        "void main() { vShade = aPos.w; gl_Position = uMvp * vec4(aPos.xyz, 1.0); }";
    static final String FS =
        "precision mediump float; uniform vec3 uColor; uniform vec3 uHole; uniform float uAlpha; varying float vShade;" +
        "void main() {" +
        "  float d = distance(gl_FragCoord.xy, uHole.xy);" +
        "  float a = uAlpha * mix(0.3, 1.0, smoothstep(uHole.z * 0.55, uHole.z, d));" +   // see the player arrow through buildings
        "  gl_FragColor = vec4(uColor * vShade * a, a);" +
        "}";

    final class Render extends Thread {
        final SurfaceTexture st;
        final Map<String, Mesh> meshes = new HashMap<String, Mesh>();
        final List<Mesh> incoming = new ArrayList<Mesh>();
        final Set<String> keep = new HashSet<String>();
        boolean dirty = true, quit, keepSet;
        EGLDisplay dpy;
        EGLContext ctx;
        EGLSurface surf;
        int prog, uMvp, uColor, uHole, uAlpha, aPos;

        Render(SurfaceTexture st) { super("MapSkin-3D"); this.st = st; }

        synchronized void wake() { dirty = true; notifyAll(); }
        synchronized void quit() { quit = true; notifyAll(); }
        synchronized void add(Mesh m) { incoming.add(m); dirty = true; notifyAll(); }
        synchronized boolean has(String key) {
            if (meshes.containsKey(key)) return true;
            for (Mesh m : incoming) if (m.key.equals(key)) return true;
            return false;
        }
        synchronized void retain(Set<String> want) { keep.clear(); keep.addAll(want); keepSet = true; dirty = true; notifyAll(); }

        @Override public void run() {
            try {
                initGl();
                float[] mvp = new float[16];
                while (true) {
                    List<Mesh> add;
                    Set<String> retain = null;
                    synchronized (this) {
                        while (!dirty && !quit) wait();
                        if (quit) break;
                        dirty = false;
                        add = new ArrayList<Mesh>(incoming);
                        incoming.clear();
                        if (keepSet) { retain = new HashSet<String>(keep); keepSet = false; }
                    }
                    for (Mesh m : add) { upload(m); synchronized (this) { Mesh old = meshes.put(m.key, m); if (old != null) free(old); } }
                    if (retain != null) synchronized (this) {
                        for (java.util.Iterator<Map.Entry<String, Mesh>> it = meshes.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<String, Mesh> e = it.next();
                            if (!retain.contains(e.getKey())) { free(e.getValue()); it.remove(); }
                        }
                    }
                    draw(mvp);
                }
            } catch (Throwable t) {
                Hook.log("3D buildings: OpenGL layer failed, falling back to map tiles");
                Hook.log(t);
                fail();
            } finally {
                try { releaseGl(); } catch (Throwable ignored) {}
                st.release();
            }
        }

        void initGl() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
            int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, num, 0) || num[0] == 0) throw new IllegalStateException("no EGL config");
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], st, new int[]{EGL14.EGL_NONE}, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("eglMakeCurrent");
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
            GLES20.glLinkProgram(prog);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(prog));
            uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
            uColor = GLES20.glGetUniformLocation(prog, "uColor");
            uHole = GLES20.glGetUniformLocation(prog, "uHole");
            uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha");
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            Hook.log("3D buildings: OpenGL layer ready (" + GLES20.glGetString(GLES20.GL_RENDERER) + ")");
        }

        int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        void upload(Mesh m) {
            int[] ids = new int[2];
            GLES20.glGenBuffers(2, ids, 0);
            m.triVbo = ids[0]; m.lineVbo = ids[1];
            m.triCount = m.tris.length / 4; m.lineCount = m.lines.length / 4;
            buffer(m.triVbo, m.tris);
            buffer(m.lineVbo, m.lines);
            m.tris = null; m.lines = null;
        }

        void buffer(int id, float[] a) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id);
            if (a.length == 0) return;
            FloatBuffer fb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(a).position(0);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.length * 4, fb, GLES20.GL_STATIC_DRAW);
        }

        void free(Mesh m) { GLES20.glDeleteBuffers(2, new int[]{m.triVbo, m.lineVbo}, 0); }

        void draw(float[] mvp) {
            GlCamera g = cam;
            int w = getWidth(), h = getHeight();
            GLES20.glViewport(0, 0, w, h);
            GLES20.glClearColor(0, 0, 0, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            float alpha = g == null ? 0 : (float) Math.max(0, Math.min(1, g.zoom - MIN_ZOOM));
            if (alpha > 0 && g.W == w && g.H == h) {
                GLES20.glUseProgram(prog);
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                GLES20.glDepthFunc(GLES20.GL_LEQUAL);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glUniform1f(uAlpha, alpha);
                GLES20.glUniform3f(uHole, (float) g.cx, (float) (h - g.cy), 46 * density);   // GL y is bottom-up
                GLES20.glLineWidth(Math.max(1f, 1.5f * density));
                GLES20.glEnableVertexAttribArray(aPos);
                synchronized (this) {
                    for (Mesh m : meshes.values()) {
                        if (m.triCount == 0) continue;
                        g.mvp(m.e0, m.n0, mvp);
                        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
                        GLES20.glUniform3f(uColor, roof[0], roof[1], roof[2]);
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.triVbo);
                        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 16, 0);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.triCount);
                        GLES20.glUniform3f(uColor, edge[0], edge[1], edge[2]);
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.lineVbo);
                        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 16, 0);
                        GLES20.glDrawArrays(GLES20.GL_LINES, 0, m.lineCount);
                    }
                }
            }
            if (!EGL14.eglSwapBuffers(dpy, surf)) throw new IllegalStateException("eglSwapBuffers 0x" + Integer.toHexString(EGL14.eglGetError()));
        }

        void releaseGl() {
            if (dpy == null) return;
            synchronized (this) { for (Mesh m : meshes.values()) free(m); meshes.clear(); }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != null) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        }
    }

    void fail() {
        if (failed) return;
        failed = true;
        post(new Runnable() {
            public void run() {
                if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(GlBuildings.this);
                if (onFail != null) onFail.run();
            }
        });
    }
}
