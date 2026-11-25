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
    public static final String[] IDS = {"off", "arrow", "orb", "car", "custom"};
    public static final String[] NAMES = {"Keep the app's own icon", "Neon arrow", "Glow orb", "Top-down car", "Custom image from gallery…"};

    public static Bitmap make(String id) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if ("arrow".equals(id)) {
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
