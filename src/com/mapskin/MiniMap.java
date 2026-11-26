package com.mapskin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** A small made-up map drawn in a theme's colours, for the theme picker (park, river, blocks, roads, highway). */
final class MiniMap {
    static Bitmap render(String themeId, int w, int h, boolean threeD) {
        String[] c = Themes.palette(themeId);
        String edgeS = Themes.BUILDING_EDGE[Themes.index(themeId)];
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.scale(w / 320f, h / 200f);                       // draw in a 320 x 200 design space
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        cv.drawColor(col(c[0]));

        // park and wood
        p.setColor(col(c[1]));
        cv.drawRoundRect(new RectF(-10, -10, 92, 70), 18, 18, p);
        p.setColor(col(c[2]));
        cv.drawOval(new RectF(18, 6, 60, 40), p);

        // river
        Path river = new Path();
        river.moveTo(190, 210); river.cubicTo(215, 150, 260, 150, 330, 120);
        river.lineTo(330, 160); river.cubicTo(280, 175, 245, 185, 235, 210); river.close();
        p.setColor(col(c[3]));
        cv.drawPath(river, p);

        // city blocks
        int bld = col(c[7]), top = col(c[8]);
        Integer edge = edgeS != null ? col(edgeS) : null;
        float[][] blocks = {
            {112, 18, 30, 22}, {148, 18, 26, 22}, {182, 14, 34, 26}, {226, 20, 28, 20}, {262, 16, 40, 24},
            {112, 64, 30, 26}, {150, 66, 24, 22}, {222, 64, 34, 24}, {266, 62, 26, 26},
            {20, 98, 34, 24}, {62, 100, 28, 22}, {112, 112, 30, 24}, {150, 110, 28, 26},
            {20, 142, 30, 26}, {60, 146, 34, 24}, {110, 152, 26, 22}};
        for (float[] k : blocks) {
            float lift = threeD ? 5 + (k[2] * k[3] % 7) : 0;
            if (threeD) {
                p.setColor(shade(top, 0.6f));
                cv.drawRect(k[0], k[1] - lift + k[3], k[0] + k[2], k[1] + k[3], p);
            }
            p.setColor(threeD ? top : bld);
            RectF r = new RectF(k[0], k[1] - lift, k[0] + k[2], k[1] - lift + k[3]);
            cv.drawRect(r, p);
            if (edge != null) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(edge);
                cv.drawRect(r, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        // roads: minor grid, a main road, then the highway with its casing
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(col(c[4])); p.setStrokeWidth(4);
        cv.drawLine(104, 52, 320, 52, p);
        cv.drawLine(104, 98, 210, 98, p);
        cv.drawLine(0, 136, 200, 136, p);
        cv.drawLine(104, 0, 104, 200, p);
        cv.drawLine(212, 0, 212, 98, p);
        p.setColor(col(c[5])); p.setStrokeWidth(6);
        cv.drawLine(0, 84, 320, 84 - 26, p);
        Path hw = new Path();
        hw.moveTo(-10, 196); hw.cubicTo(80, 170, 150, 120, 200, 0);
        p.setColor(col(c[6])); p.setStrokeWidth(13);
        cv.drawPath(hw, p);
        p.setColor(col(c[11])); p.setStrokeWidth(8);
        cv.drawPath(hw, p);
        p.setStyle(Paint.Style.FILL);
        return b;
    }

    static int col(String s) { return Color.parseColor(s); }

    static int shade(int c, float f) {
        return 0xFF000000 | (Math.round(((c >> 16) & 255) * f) << 16) | (Math.round(((c >> 8) & 255) * f) << 8) | Math.round((c & 255) * f);
    }
}
