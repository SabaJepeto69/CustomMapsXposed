package com.mapskin;

import java.util.List;

/**
 * Guesses building heights where the map data has none (almost everywhere outside a few landmark towers).
 * Uses what the data does have: the building's type (Overture "class"), its footprint size, and how built-up the
 * surrounding ~250 m are. Small footprints in sparse areas are houses; mid-size blocks in dense areas are apartment
 * buildings; huge footprints in sparse areas are warehouses/malls. A stable per-building wobble stops whole streets
 * coming out exactly the same height.
 */
final class Heights {
    static final int GRID = 8;               // density grid over one z14 tile (~250 m cells in Israel)
    static final float FLOOR = 3.2f, ROOF = 1.0f;

    /**
     * @param seg        buildings decoded from one source tile (pixels, already shifted by offX/offY)
     * @param tilePx     the source tile's width in those pixels
     * @param tileMetres the source tile's width on the ground
     */
    static void estimate(List<BuildingTiles.Building> seg, float tilePx, float offX, float offY, double tileMetres) {
        if (seg.isEmpty()) return;
        double m2PerPx2 = (tileMetres / tilePx) * (tileMetres / tilePx);
        double cellArea = (tileMetres / GRID) * (tileMetres / GRID);
        double[] cover = new double[GRID * GRID];
        int n = seg.size();
        float[] area = new float[n];
        int[] cell = new int[n];
        for (int i = 0; i < n; i++) {
            BuildingTiles.Building b = seg.get(i);
            float[] r = b.rings.get(0);
            double a = 0, cx = 0, cy = 0;
            for (int k = 0; k < r.length; k += 2) {
                int j = (k + 2) % r.length;
                a += (double) r[k] * r[j + 1] - (double) r[j] * r[k + 1];
                cx += r[k]; cy += r[k + 1];
            }
            int pts = r.length / 2;
            area[i] = (float) (Math.abs(a) / 2 * m2PerPx2);
            int gx = clamp((int) ((cx / pts + offX) / tilePx * GRID)), gy = clamp((int) ((cy / pts + offY) / tilePx * GRID));
            cell[i] = gy * GRID + gx;
            cover[cell[i]] += area[i];
        }
        for (int i = 0; i < n; i++) {
            BuildingTiles.Building b = seg.get(i);
            // OpenFreeMap fills untagged buildings with a default of ~5 m: treat that as unknown too
            boolean known = b.known && !(b.height <= 5.5f && b.minHeight == 0 && b.cls == null);
            if (known) continue;
            b.height = guess(b.cls, area[i], density(cover, cell[i], cellArea), b.idHash);
            b.known = false;
        }
    }

    static int clamp(int g) { return g < 0 ? 0 : g >= GRID ? GRID - 1 : g; }

    /** Built-up share of the ground around a cell (3x3 cells), ignoring empty cells such as sea, parks or fields. */
    static float density(double[] cover, int c, double cellArea) {
        int cx = c % GRID, cy = c / GRID, cells = 0;
        double sum = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= GRID || y >= GRID || cover[y * GRID + x] <= 0) continue;
                sum += cover[y * GRID + x]; cells++;
            }
        return cells == 0 ? 0 : (float) (sum / cells / cellArea);
    }

    /** Height in metres for a building without data. */
    static float guess(String cls, float area, float density, int id) {
        int wob = ((id * 0x9E3779B1) >>> 29) % 3 - 1;            // -1, 0 or +1 floor, same for this building everywhere
        float floors;
        if (cls != null && cls.matches("roof|carport|shelter|greenhouse|kiosk|toilets|shed|hut|garages?|cabin|bunker|parking_entrance|transformer_tower|service")) {
            floors = 1; wob = 0;
        } else if (cls != null && cls.matches("house|detached|semidetached_house|bungalow|terrace|farm|farm_auxiliary|barn|static_caravan|stable|cowshed|houseboat")) {
            floors = area > 250 ? 3 : 2;
        } else if (cls != null && cls.matches("industrial|warehouse|manufacture|hangar|retail|supermarket|commercial|sports_centre|sports_hall|grandstand|train_station|transportation")) {
            floors = area > 3000 && density > 0.25f ? 4 : 2;
        } else if (cls != null && cls.matches("school|kindergarten|public|civic|government|church|mosque|synagogue|temple|religious|college|university|fire_station|police")) {
            floors = 3;
        } else if (cls != null && cls.matches("office|hotel|hospital|dormitory")) {
            floors = density > 0.25f ? 10 : 6;
        } else if (cls != null && cls.matches("apartments|residential")) {
            floors = apartments(area, density);
        } else {                                                  // no type: footprint size + neighbourhood
            if (area < 45) { floors = 1; wob = 0; }
            else if (area < 100) floors = density < 0.10f ? 1 : 2;
            else if (area < 220) floors = density < 0.08f ? 2 : density < 0.15f ? 3 : 4;
            else if (area < 1500) floors = density < 0.06f ? 2 : apartments(area, density);
            else floors = density < 0.15f ? 2 : density < 0.25f ? 4 : 7;   // big + sparse: warehouse/mall
        }
        floors = Math.max(1, floors + (floors >= 2 ? wob : 0));
        return floors * FLOOR + ROOF;
    }

    /** Apartment blocks: denser areas and bigger footprints are taller (Israeli towns: 4-8 floors, city towers more). */
    static float apartments(float area, float density) {
        float f = density < 0.08f ? 3 : density < 0.15f ? 4 : density < 0.22f ? 5 : 6;
        if (area > 700) f += 2;
        if (area > 1200 && density > 0.18f) f += 3;
        return f;
    }
}
