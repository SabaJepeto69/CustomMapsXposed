package com.mapskin;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    static final int PICK = 1;
    static final String LIBERTY = "https://tiles.openfreemap.org/styles/liberty";

    // Dark "game HUD" look
    static final int BG = 0xFF0D0E13, SURFACE = 0xFF171922, SURFACE2 = 0xFF1F2230, STROKE = 0xFF2A2E3D;
    static final int TEXT = 0xFFF2F3F7, SUB = 0xFF9AA0B4, ACCENT = 0xFFFF3B5C, ACCENT2 = 0xFFFF7A45, GOOD = 0xFF3DDC97;

    SharedPreferences sp;
    boolean active;
    String themeId, iconId, customB64;
    Switch enabled, tilt3d;
    LinearLayout appsList;
    TextView applyBtn;
    final List<View> themeCards = new ArrayList<View>(), iconTiles = new ArrayList<View>(), bundleCards = new ArrayList<View>();
    ImageView customTileImg;
    TextView customTilePlus;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { sp = getSharedPreferences("prefs", MODE_WORLD_READABLE); active = true; }
        catch (SecurityException e) { sp = getSharedPreferences("prefs", MODE_PRIVATE); active = false; }
        themeId = sp.getString("theme", Themes.IDS[0]);
        iconId = sp.getString("icon", "off");
        customB64 = sp.getString("icon_b64", null);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setBackgroundColor(BG);

        FrameLayout frame = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(120));
        scroll.addView(root);
        frame.addView(scroll);

        // ---- header ----
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(Icons.make("chevron"));
        logo.setBackground(round(SURFACE2, 14, STROKE));
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        head.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(14), 0, 0, 0);
        TextView title = text("MapSkin", 28, TEXT);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        ht.addView(title);
        ht.addView(text("Game-style maps for your map apps", 13, SUB));
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);

        TextView chip = text(active ? "●  Module active" : "●  Module not active", 12, active ? GOOD : ACCENT);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackground(round(active ? 0x1A3DDC97 : 0x1AFF3B5C, 99, 0));
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(18);
        root.addView(chip, clp);
        if (!active) {
            TextView hint = text("Turn MapSkin on in LSPosed / Vector, tick your map apps in its scope, then reopen this app.", 13, SUB);
            hint.setPadding(dp(2), dp(8), 0, 0);
            root.addView(hint);
        }

        // ---- main switches ----
        LinearLayout sw = card();
        enabled = toggleRow(sw, "Re-skin maps", "Master switch for everything below", sp.getBoolean("enabled", true));
        // Saved straight away, so the hooked apps never see a stale on/off value.
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                sp.edit().putBoolean("enabled", on).commit();
                Toast.makeText(MainActivity.this, on
                        ? "Re-skinning ON. Force-stop your map apps and reopen them."
                        : "Re-skinning OFF. Map apps will look normal after a restart.", Toast.LENGTH_SHORT).show();
            }
        });
        divider(sw);
        tilt3d = toggleRow(sw, "3D buildings", "Tilted map with real 3D buildings", sp.getBoolean("tilt3d", false));
        tilt3d.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { sp.edit().putBoolean("tilt3d", on).commit(); }
        });
        root.addView(sw, cardLp(16));

        // ---- bundles ----
        section(root, "Game bundles", "Theme, player icon and view in one tap");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (final String[] bundle : Themes.BUNDLES) {
            View card = bundleCard(bundle);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectTheme(bundle[2]);
                    selectIcon(bundle[3]);
                    tilt3d.setChecked("3d".equals(bundle[4]));
                    refreshBundles();
                    Toast.makeText(MainActivity.this, "Bundle picked. Tap Apply to use it.", Toast.LENGTH_SHORT).show();
                }
            });
            card.setTag(bundle);
            bundleCards.add(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(248), -2);
            lp.rightMargin = dp(12);
            row.addView(card, lp);
        }
        hs.addView(row);
        root.addView(hs);

        // ---- themes: 2-column grid of little map previews ----
        section(root, "Map theme", null);
        LinearLayout grid = null;
        for (int i = 0; i < Themes.IDS.length; i++) {
            if (i % 2 == 0) {
                grid = new LinearLayout(this);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
                glp.bottomMargin = dp(12);
                root.addView(grid, glp);
            }
            View t = themeCard(Themes.IDS[i], Themes.NAMES[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 0) lp.rightMargin = dp(6); else lp.leftMargin = dp(6);
            grid.addView(t, lp);
        }

        // ---- player icon ----
        section(root, "Player icon", "Replaces the location arrow in Mapbox / MapLibre apps");
        HorizontalScrollView is = new HorizontalScrollView(this);
        is.setHorizontalScrollBarEnabled(false);
        LinearLayout irow = new LinearLayout(this);
        for (int i = 0; i < Icons.IDS.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), -2);
            lp.rightMargin = dp(10);
            irow.addView(iconTile(Icons.IDS[i], Icons.NAMES[i]), lp);
        }
        is.addView(irow);
        root.addView(is);

        // ---- apps ----
        section(root, "Map apps on this phone", "Tick these in MapSkin's scope in LSPosed / Vector");
        appsList = card();
        appsList.addView(text("Scanning…", 14, SUB));
        root.addView(appsList, cardLp(0));

        // ---- how to ----
        section(root, "How to use", null);
        LinearLayout how = card();
        String[] steps = {"Turn MapSkin on in LSPosed / Vector", "Tick your map apps in its scope",
                "Pick a look here and tap Apply", "Force-stop the map app and open it again"};
        for (int i = 0; i < steps.length; i++) how.addView(step(i + 1, steps[i]));
        TextView note = text("Works with apps built on Google Maps SDK, Mapbox or MapLibre. The Google Maps app itself and Waze draw "
                + "their maps with their own engines, so they don't change.", 12, SUB);
        note.setPadding(0, dp(10), 0, 0);
        how.addView(note);
        root.addView(how, cardLp(0));

        // ---- apply bar, pinned to the bottom ----
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(20), dp(28), dp(20), dp(20));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{BG, BG, BG & 0x00FFFFFF}));
        applyBtn = text("Apply", 17, Color.WHITE);
        applyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        applyBtn.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(16));
        applyBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
        applyBtn.setElevation(dp(6));
        applyBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { apply(enabled.isChecked()); } });
        bar.addView(applyBtn, new LinearLayout.LayoutParams(-1, dp(56)));
        frame.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(frame);
        refreshThemes();
        refreshIcons();
        refreshBundles();
        scanApps();
    }

    // ---------------- pieces ----------------

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(SURFACE, 18, STROKE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    LinearLayout.LayoutParams cardLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    void divider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        parent.addView(d, lp);
    }

    void section(LinearLayout root, String title, String sub) {
        TextView t = text(title, 18, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(2), dp(30), 0, sub == null ? dp(12) : dp(2));
        root.addView(t);
        if (sub != null) {
            TextView s = text(sub, 13, SUB);
            s.setPadding(dp(2), 0, 0, dp(12));
            root.addView(s);
        }
    }

    Switch toggleRow(LinearLayout parent, String title, String sub, boolean on) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 16, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tt.addView(t);
        tt.addView(text(sub, 12, SUB));
        r.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        final Switch s = new Switch(this);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{Color.WHITE, 0xFFB8BCCB}));
        s.setTrackTintList(new ColorStateList(st, new int[]{ACCENT, 0xFF3A3F52}));
        r.addView(s);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { s.toggle(); } });
        parent.addView(r);
        return s;
    }

    View bundleCard(String[] bundle) {
        FrameLayout f = new FrameLayout(this);
        f.setClipToOutline(true);
        ImageView map = new ImageView(this);
        map.setImageBitmap(MiniMap.render(bundle[2], dp(248), dp(132), "3d".equals(bundle[4])));
        map.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(map, new FrameLayout.LayoutParams(-1, dp(132)));
        ImageView ic = new ImageView(this);
        ic.setImageBitmap(Icons.make(bundle[3]));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_HORIZONTAL);
        ilp.topMargin = dp(44);
        f.addView(ic, ilp);
        LinearLayout lbl = new LinearLayout(this);
        lbl.setOrientation(LinearLayout.VERTICAL);
        lbl.setPadding(dp(14), dp(10), dp(14), dp(12));
        String[] parts = bundle[1].split(": ", 2);
        TextView n = text(parts[0], 16, TEXT);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.addView(n);
        TextView d = text(parts.length > 1 ? parts[1] : "", 12, SUB);
        d.setMaxLines(2);
        d.setEllipsize(TextUtils.TruncateAt.END);
        lbl.addView(d);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(132);
        f.addView(lbl, llp);
        return f;
    }

    View themeCard(final String id, String name) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipToOutline(true);
        c.setTag(id);
        ImageView map = new ImageView(this);
        map.setImageBitmap(MiniMap.render(id, dp(170), dp(106), Themes.BUILDING_EDGE[Themes.index(id)] != null));
        map.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.addView(map, new LinearLayout.LayoutParams(-1, dp(96)));
        LinearLayout lbl = new LinearLayout(this);
        lbl.setOrientation(LinearLayout.VERTICAL);
        lbl.setPadding(dp(12), dp(9), dp(10), dp(11));
        int paren = name.indexOf(" (");
        TextView n = text(paren > 0 ? name.substring(0, paren) : name, 14, TEXT);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        lbl.addView(n);
        if (paren > 0) {
            TextView d = text(name.substring(paren + 2, name.length() - 1), 11, SUB);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            lbl.addView(d);
        }
        c.addView(lbl);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectTheme(id); refreshBundles(); }
        });
        themeCards.add(c);
        return c;
    }

    View iconTile(final String id, String name) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setTag(id);
        FrameLayout box = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setPadding(dp(12), dp(12), dp(12), dp(12));
        Bitmap bm = "custom".equals(id) ? Icons.fromBase64(customB64) : Icons.make(id);
        if (bm != null) img.setImageBitmap(bm);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if ("off".equals(id) || "custom".equals(id)) {
            TextView sym = text("off".equals(id) ? "—" : "+", 26, SUB);
            sym.setGravity(Gravity.CENTER);
            box.addView(sym, new FrameLayout.LayoutParams(-1, -1));
            if ("custom".equals(id)) {
                customTileImg = img;
                customTilePlus = sym;
                sym.setVisibility(bm == null ? View.VISIBLE : View.GONE);
            }
        }
        t.addView(box, new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView n = text(shortIconName(id, name), 11, SUB);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, 0);
        n.setMaxLines(2);
        t.addView(n, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if ("custom".equals(id) && customB64 == null) { pickImage(); return; }
                selectIcon(id);
                sp.edit().putString("icon", iconId).commit();
                refreshBundles();
            }
        });
        if ("custom".equals(id)) t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { pickImage(); return true; }
        });
        iconTiles.add(t);
        return t;
    }

    static String shortIconName(String id, String name) {
        if ("off".equals(id)) return "App's own";
        if ("custom".equals(id)) return "Your image";
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }

    View step(int n, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView num = text(String.valueOf(n), 13, Color.WHITE);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(0x33FF3B5C, 99, 0));
        r.addView(num, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = text(s, 14, TEXT);
        t.setPadding(dp(12), 0, 0, 0);
        r.addView(t);
        return r;
    }

    // ---------------- selection ----------------

    void selectTheme(String id) { themeId = id; refreshThemes(); }

    void selectIcon(String id) { iconId = id; refreshIcons(); }

    void refreshThemes() {
        for (View v : themeCards) {
            boolean on = themeId.equals(v.getTag());
            v.setBackground(round(on ? SURFACE2 : SURFACE, 16, 0));
            v.setForeground(round(0, 16, on ? ACCENT : STROKE, on ? 2 : 1));   // outline drawn over the map picture
        }
    }

    void refreshIcons() {
        for (View v : iconTiles) {
            boolean on = iconId.equals(v.getTag());
            LinearLayout t = (LinearLayout) v;
            t.getChildAt(0).setBackground(round(on ? SURFACE2 : SURFACE, 18, on ? ACCENT : STROKE, on ? 2 : 1));
            ((TextView) t.getChildAt(1)).setTextColor(on ? TEXT : SUB);
        }
    }

    void refreshBundles() {
        for (View v : bundleCards) {
            String[] bd = (String[]) v.getTag();
            boolean on = bd[2].equals(themeId) && bd[3].equals(iconId);
            v.setBackground(round(SURFACE, 18, 0));
            android.graphics.drawable.LayerDrawable fg = new android.graphics.drawable.LayerDrawable(new Drawable[]{
                    round(0, 18, on ? ACCENT : STROKE, on ? 2 : 1),
                    new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, round(Color.WHITE, 18, 0))});
            v.setForeground(fg);
        }
    }

    // ---------------- actions ----------------

    void apply(final boolean enabled) {
        final String theme = themeId, icon = iconId, b64 = customB64;
        applyBtn.setText("Applying…");
        applyBtn.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                String mb = null, err = null;
                try { mb = Themes.mapbox(download(LIBERTY), theme); } catch (Throwable t) { err = t.toString(); }
                SharedPreferences.Editor e = sp.edit()
                        .putBoolean("enabled", enabled)
                        .putString("theme", theme)
                        .putString("icon", icon)
                        .putString("icon_b64", b64);
                if (mb != null) e.putString("mbjson", mb);
                boolean saved = e.commit();
                final String msg = !saved
                        ? "Couldn't save settings. Reboot the phone and try again."
                        : err == null
                        ? "Saved! Force-stop your map apps and reopen them."
                        : "Saved, but couldn't download the base map for Mapbox/MapLibre apps (" + err + "). Check internet and press Apply again.";
                final boolean ok = saved && err == null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        applyBtn.setEnabled(true);
                        applyBtn.setText(ok ? "Applied ✓" : "Apply");
                        if (ok) applyBtn.postDelayed(new Runnable() { public void run() { applyBtn.setText("Apply"); } }, 2500);
                    }
                });
            }
        }).start();
    }

    static String download(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Pick player icon"), PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap src = BitmapFactory.decodeStream(in);
            in.close();
            float s = 128f / Math.max(src.getWidth(), src.getHeight());
            Bitmap small = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)), Math.max(1, Math.round(src.getHeight() * s)), true);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 100, o);
            customB64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            sp.edit().putString("icon", "custom").putString("icon_b64", customB64).commit();
            customTileImg.setImageBitmap(small);
            customTilePlus.setVisibility(View.GONE);
            selectIcon("custom");
            refreshBundles();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't load that image: " + t, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- map apps on the phone ----------------

    void scanApps() {
        new Thread(new Runnable() {
            public void run() {
                PackageManager pm = getPackageManager();
                final List<Object[]> found = new ArrayList<Object[]>();      // {label, package, kind, icon}
                for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    String kind = system ? detectLib(ai) : detect(ai);   // preinstalled apps: only real map engines
                    if (kind == null) continue;
                    Drawable icon = null;
                    try { icon = pm.getApplicationIcon(ai); } catch (Throwable ignored) {}
                    found.add(new Object[]{pm.getApplicationLabel(ai).toString(), ai.packageName, kind, icon});
                }
                Collections.sort(found, new Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) {
                        int ga = ((String) a[2]).startsWith("Google") ? 1 : 0, gb = ((String) b[2]).startsWith("Google") ? 1 : 0;
                        return ga != gb ? ga - gb : ((String) a[0]).compareToIgnoreCase((String) b[0]);
                    }
                });
                runOnUiThread(new Runnable() { public void run() { showApps(found); } });
            }
        }).start();
    }

    void showApps(List<Object[]> found) {
        appsList.removeAllViews();
        if (found.isEmpty()) { appsList.addView(text("No map apps found.", 14, SUB)); return; }
        boolean anyGoogle = false;
        for (int i = 0; i < found.size(); i++) {
            Object[] a = found.get(i);
            if (i > 0) divider(appsList);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            if (a[3] != null) ic.setImageDrawable((Drawable) a[3]);
            r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout tt = new LinearLayout(this);
            tt.setOrientation(LinearLayout.VERTICAL);
            tt.setPadding(dp(12), 0, dp(8), 0);
            TextView n = text((String) a[0], 15, TEXT);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            tt.addView(n);
            TextView pk = text((String) a[1], 11, SUB);
            pk.setSingleLine(true);
            pk.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            tt.addView(pk);
            r.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            boolean google = ((String) a[2]).startsWith("Google");
            anyGoogle |= google;
            TextView badge = text(google ? "Google · maybe" : (String) a[2], 11, google ? SUB : GOOD);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setBackground(round(google ? 0x14FFFFFF : 0x1A3DDC97, 99, 0));
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            r.addView(badge);
            appsList.addView(r);
        }
        if (anyGoogle) {
            TextView foot = text("“Google · maybe”: the app has a Google Maps key, so it only changes if it really shows a map.", 11, SUB);
            foot.setPadding(0, dp(12), 0, 0);
            appsList.addView(foot);
        }
    }

    /** Mapbox/MapLibre are found by their native libraries (a sure sign); Google Maps only by its API key, which many non-map apps also carry. */
    static String detect(ApplicationInfo ai) {
        String lib = detectLib(ai);
        if (lib != null) return lib;
        Bundle md = ai.metaData;
        if (md != null && (md.containsKey("com.google.android.geo.API_KEY") || md.containsKey("com.google.android.maps.v2.API_KEY")))
            return "Google Maps key, may not show a map";
        return null;
    }

    static String detectLib(ApplicationInfo ai) {
        List<String> apks = new ArrayList<String>();
        if (ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) Collections.addAll(apks, ai.splitSourceDirs);
        String[] libs = ai.nativeLibraryDir == null ? null : new File(ai.nativeLibraryDir).list();
        if (libs != null) for (String l : libs) { String k = libKind(l); if (k != null) return k; }
        for (String apk : apks) {
            try {
                ZipFile z = new ZipFile(apk);
                try {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.startsWith("lib/")) { String k = libKind(n); if (k != null) return k; }
                    }
                } finally { z.close(); }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static String libKind(String name) {
        String n = name.toLowerCase();
        if (n.contains("maplibre")) return "MapLibre";
        if (n.contains("mapbox")) return "Mapbox";
        return null;
    }

    // ---------------- helpers ----------------

    GradientDrawable round(int fill, int radiusDp, int stroke) { return round(fill, radiusDp, stroke, 1); }

    GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
