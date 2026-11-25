package com.mapskin;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    static final int PICK = 1;
    static final String LIBERTY = "https://tiles.openfreemap.org/styles/liberty";

    SharedPreferences sp;
    boolean active;
    String themeId, iconId, customB64;
    ImageView preview;
    RadioGroup iconGroup;
    TextView appsView;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { sp = getSharedPreferences("prefs", MODE_WORLD_READABLE); active = true; }
        catch (SecurityException e) { sp = getSharedPreferences("prefs", MODE_PRIVATE); active = false; }
        themeId = sp.getString("theme", Themes.IDS[0]);
        iconId = sp.getString("icon", "off");
        customB64 = sp.getString("icon_b64", null);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("MapSkin", 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView status = text(active
                ? "Module is active. Pick your map apps in Vector's scope for MapSkin."
                : "Module not active. Enable MapSkin in Vector (LSPosed), tick your map apps in its scope, then reopen this app.", 14);
        status.setTextColor(active ? 0xFF2E7D32 : 0xFFC62828);
        root.addView(status);

        final Switch enabled = new Switch(this);
        enabled.setText("Re-skin maps");
        enabled.setChecked(sp.getBoolean("enabled", true));
        root.addView(enabled);

        root.addView(header("Map theme"));
        RadioGroup themes = new RadioGroup(this);
        for (int i = 0; i < Themes.IDS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(100 + i);
            r.setText(Themes.NAMES[i]);
            themes.addView(r);
            if (Themes.IDS[i].equals(themeId)) r.setChecked(true);
        }
        themes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) { themeId = Themes.IDS[id - 100]; }
        });
        root.addView(themes);

        root.addView(header("Player icon"));
        iconGroup = new RadioGroup(this);
        for (int i = 0; i < Icons.IDS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(200 + i);
            r.setText(Icons.NAMES[i]);
            iconGroup.addView(r);
            if (Icons.IDS[i].equals(iconId)) r.setChecked(true);
        }
        iconGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                iconId = Icons.IDS[id - 200];
                if ("custom".equals(iconId) && customB64 == null) pickImage();
                updatePreview();
            }
        });
        root.addView(iconGroup);

        preview = new ImageView(this);
        preview.setBackgroundColor(0xFF222233);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        lp.topMargin = dp(8);
        root.addView(preview, lp);
        updatePreview();

        Button change = new Button(this);
        change.setText("Choose custom image");
        change.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickImage(); } });
        root.addView(change);

        Button apply = new Button(this);
        apply.setText("Apply");
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { apply(enabled.isChecked()); }
        });
        root.addView(apply);

        root.addView(text("After applying, force-stop the map app (App info → Force stop) and open it again.", 13));

        root.addView(header("Map apps found on this phone"));
        appsView = text("Scanning…", 14);
        root.addView(appsView);
        root.addView(text("Works with apps built on Google Maps SDK, Mapbox or MapLibre. "
                + "The Google Maps app itself and Waze use their own engines and won't change.", 12));

        setContentView(scroll);
        scanApps();
    }

    void apply(final boolean enabled) {
        final String theme = themeId, icon = iconId, b64 = customB64;
        Toast.makeText(this, "Building theme…", Toast.LENGTH_SHORT).show();
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
                e.commit();
                final String msg = err == null
                        ? "Saved! Force-stop your map apps and reopen them."
                        : "Saved, but couldn't download the base map for Mapbox/MapLibre apps (" + err + "). Check internet and press Apply again.";
                runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); } });
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
            iconId = "custom";
            iconGroup.check(200 + 4);
            updatePreview();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't load that image: " + t, Toast.LENGTH_LONG).show();
        }
    }

    void updatePreview() {
        Bitmap b = Icons.get(iconId, customB64);
        if (b != null) preview.setImageBitmap(b); else preview.setImageDrawable(null);
    }

    void scanApps() {
        new Thread(new Runnable() {
            public void run() {
                PackageManager pm = getPackageManager();
                List<String> out = new ArrayList<String>();
                for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    String kind = detect(ai);
                    if (kind != null) out.add("• " + pm.getApplicationLabel(ai) + "  (" + kind + ")\n   " + ai.packageName);
                }
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                final StringBuilder sb = new StringBuilder();
                for (String s : out) sb.append(s).append('\n');
                if (out.isEmpty()) sb.append("None detected.");
                runOnUiThread(new Runnable() { public void run() { appsView.setText(sb.toString()); } });
            }
        }).start();
    }

    static String detect(ApplicationInfo ai) {
        Bundle md = ai.metaData;
        if (md != null && (md.containsKey("com.google.android.geo.API_KEY") || md.containsKey("com.google.android.maps.v2.API_KEY")))
            return "Google Maps SDK";
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

    TextView header(String s) {
        TextView t = text(s, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setGravity(Gravity.START);
        return t;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
