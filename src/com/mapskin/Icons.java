package com.mapskin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Base64;

/** Built-in player icons, drawn in code (no copied art). */
public final class Icons {
    public static final String[] IDS = {"off", "chevron", "radar", "arrow", "orb", "car", "custom"};
    public static final String[] NAMES = {"Keep the app's own icon", "Neon chevron (glowing)", "Radar arrow (white)",
            "Neon arrow", "Glow orb", "Top-down car", "Custom image from gallery…"};

    public static Bitmap make(String id) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if ("chevron".equals(id)) {
            // Sharp double chevron with a soft yellow glow and a cyan core edge.
            Path path = new Path();
            path.moveTo(48, 10); path.lineTo(80, 82); path.lineTo(48, 64); path.lineTo(16, 82); path.close();
            p.setColor(0xCCFCEE0A);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(10, android.graphics.BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, p);
            p.setMaskFilter(null);
            p.setColor(0xFFFCEE0A); c.drawPath(path, p);
            Path inner = new Path();
            inner.moveTo(48, 30); inner.lineTo(64, 66); inner.lineTo(48, 56); inner.lineTo(32, 66); inner.close();
            p.setColor(0xFF05070D); c.drawPath(inner, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setStrokeJoin(Paint.Join.MITER);
            p.setColor(0xFF00E5FF); c.drawPath(path, p);
        } else if ("radar".equals(id)) {
            // Chunky white arrowhead, dark outline, half-shaded for a slight 3D look.
            Path path = new Path();
            path.moveTo(48, 6); path.lineTo(82, 86); path.lineTo(48, 70); path.lineTo(14, 86); path.close();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10); p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFF1B1B1F); c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE); c.drawPath(path, p);
            Path shade = new Path();
            shade.moveTo(48, 6); shade.lineTo(82, 86); shade.lineTo(48, 70); shade.close();
            p.setColor(0xFFD9D9E0); c.drawPath(shade, p);
        } else if ("arrow".equals(id)) {
            Path path = new Path();
            path.moveTo(48, 8); path.lineTo(84, 86); path.lineTo(48, 66); path.lineTo(12, 86); path.close();
            p.setColor(0xFFFF2BD6); c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6); p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.WHITE); c.drawPath(path, p);
        } else if ("orb".equals(id)) {
            p.setShader(new RadialGradient(40, 40, 44, 0xFFB8FFFF, 0xFF00A8E8, Shader.TileMode.CLAMP));
            c.drawCircle(48, 48, 36, p);
            p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7); p.setColor(Color.WHITE);
            c.drawCircle(48, 48, 36, p);
        } else if ("car".equals(id)) {
            RectF body = new RectF(28, 8, 68, 88);
            p.setColor(0xFFE53935); c.drawRoundRect(body, 16, 16, p);
            p.setColor(0xFF1A1A2E);
            c.drawRoundRect(new RectF(33, 26, 63, 42), 6, 6, p);
            c.drawRoundRect(new RectF(33, 64, 63, 76), 6, 6, p);
            p.setColor(0xFFFFF59D); c.drawCircle(36, 13, 4, p); c.drawCircle(60, 13, 4, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(Color.WHITE);
            c.drawRoundRect(body, 16, 16, p);
        } else {
            return null;
        }
        return b;
    }

    public static Bitmap fromBase64(String s) {
        if (s == null) return null;
        byte[] d = Base64.decode(s, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(d, 0, d.length);
    }

    public static Bitmap get(String id, String customB64) {
        if ("custom".equals(id)) return fromBase64(customB64);
        return make(id);
    }
}
