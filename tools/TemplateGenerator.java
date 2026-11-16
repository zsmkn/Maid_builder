import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates the built-in territory templates ({@code .litematic}).
 *
 * <pre>
 * ./gradlew :core:compileJava
 * java -cp core/build/classes/java/main tools/TemplateGenerator.java neoforge/src/main/resources/data/maidbuilder/maidbuilder/structures
 * </pre>
 *
 * Design rules: oak wood, cobblestone, glass, dirt and a few crafted basics only. Layer 0 sits on
 * top of the ground (build mode places the template's bottom above the block the player looks at),
 * so houses get a stair step in front of the door. Maids place states exactly, without neighbour
 * updates, so fence/pane connections and stair shapes are computed here.
 */
public class TemplateGenerator {
    static final int DATA_VERSION = 3955; // 1.21.1

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(out);
        Map<String, Grid> templates = new java.util.LinkedHashMap<>();
        templates.put("warehouse", warehouse());
        templates.put("sugar_cane_farm", sugarCaneFarm());
        templates.put("small_greenhouse", greenhouse(1));
        templates.put("lumberyard", lumberyard());
        templates.put("ranch", ranch(1));
        templates.put("apiary", apiary());
        templates.put("dormitory", dormitory());
        templates.put("large_greenhouse", greenhouse(2));
        templates.put("large_ranch", ranch(2));
        templates.put("fishing_dock", fishingDock());
        templates.put("pavilion", pavilion());
        templates.put("canteen", canteen());
        templates.put("village_hall", villageHall());
        templates.put("trading_post", tradingPost());
        templates.put("watchtower", watchtower());
        templates.put("shrine", shrine());
        templates.put("builders_shop", buildersShop());
        // Fine versions (building level 2): same size and layout, finer materials and trim.
        Map<String, Grid> fine = new java.util.LinkedHashMap<>();
        fine.put("warehouse", fine(warehouse(), g -> houseTrim(g, 0, 1, 7, 7, 3)));
        fine.put("dormitory", fine(dormitory(), g -> houseTrim(g, 1, 1, 9, 7, 3)));
        fine.put("canteen", fine(canteen(), g -> houseTrim(g, 1, 1, 9, 7, 3)));
        fine.put("village_hall", fine(villageHall(), g -> houseTrim(g, 1, 1, 11, 9, 3)));
        fine.put("small_greenhouse", fine(greenhouse(1), g -> greenhouseTrim(g, 1)));
        fine.put("large_greenhouse", fine(greenhouse(2), g -> greenhouseTrim(g, 2)));
        fine.put("ranch", fine(ranch(1), g -> {
        }));
        fine.put("large_ranch", fine(ranch(2), g -> {
        }));
        fine.put("sugar_cane_farm", fine(sugarCaneFarm(), g -> {
        }));
        fine.put("lumberyard", fine(lumberyard(), g -> g.replace(Map.of("oak_planks", "spruce_planks"))));
        fine.put("apiary", fine(apiary(), g -> {
        }));
        for (Map.Entry<String, Grid> e : fine.entrySet()) {
            Grid basic = templates.get(e.getKey());
            if (!basic.size().equals(e.getValue().size())) {
                throw new IllegalStateException(e.getKey() + "_fine is " + e.getValue().size() + ", not " + basic.size());
            }
            templates.put(e.getKey() + "_fine", e.getValue());
        }
        for (Map.Entry<String, Grid> e : templates.entrySet()) {
            Schematic s = e.getValue().toSchematic(e.getKey());
            try (OutputStream os = Files.newOutputStream(out.resolve(e.getKey() + ".litematic"))) {
                LitematicWriter.write(s, os);
            }
            System.out.println(e.getKey() + " " + e.getValue().summary());
        }
    }

    // ---------------------------------------------------------------- templates

    /** 7x7 house full of barrels: products are stored here. */
    static Grid warehouse() {
        Grid g = new Grid();
        house(g, 0, 1, 7, 7, 3, false);
        // Barrels along the back wall (two high) and the back half of the side walls.
        for (int x = 1; x <= 5; x++) {
            g.set(x, 1, 6, "barrel[facing=north]");
            g.set(x, 2, 6, "barrel[facing=north]");
        }
        for (int z = 4; z <= 5; z++) {
            g.set(1, 1, z, "barrel[facing=east]");
            g.set(5, 1, z, "barrel[facing=west]");
        }
        g.set(1, 1, 2, "crafting_table");
        g.set(1, 2, 2, "wall_torch[facing=east]");
        g.set(5, 2, 2, "wall_torch[facing=west]");
        g.set(0, 2, 4, "glass_pane");
        g.set(6, 2, 4, "glass_pane");
        return g;
    }

    /** Two water channels with dirt on both sides: 36 cane spots, framed by logs. */
    static Grid sugarCaneFarm() {
        Grid g = new Grid();
        int w = 11, d = 9;
        frame(g, 0, 0, 0, w, d);
        for (int x = 1; x <= w - 2; x++) {
            g.set(x, 0, 1, "dirt");
            g.set(x, 0, 2, "water");
            g.set(x, 0, 3, "dirt");
            g.set(x, 0, 4, "dirt_path");
            g.set(x, 0, 5, "dirt");
            g.set(x, 0, 6, "water");
            g.set(x, 0, 7, "dirt");
        }
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
            g.set(c[0], 1, c[1], "oak_fence");
            g.set(c[0], 2, c[1], "torch");
        }
        g.set(w - 1, 1, 4, "composter");
        return g;
    }

    /**
     * Greenhouse of {@code fields} 9x9 farmland plots, each watered from its centre, with a path
     * between plots. Plank band, glass pane walls, glass roof on log beams.
     */
    static Grid greenhouse(int fields) {
        Grid g = new Grid();
        int iw = fields * 9 + (fields - 1); // interior width
        int w = iw + 2, d = 11, top = 3, oz = 1;
        int doorX = w / 2;
        // Floor: cobblestone ring, farmland plots, path between plots.
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                g.set(x, 0, oz + z, edge ? "cobblestone" : "farmland[moisture=7]");
            }
        }
        for (int f = 0; f < fields; f++) {
            int cx = 1 + f * 10 + 4;
            g.set(cx, 0, oz + 5, "water");
            if (f > 0) for (int z = 1; z < d - 1; z++) g.set(cx - 5, 0, oz + z, "dirt_path");
        }
        // Walls.
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                if (!edgeX && !edgeZ) continue;
                boolean pillar = (edgeX && edgeZ) || (edgeZ && x % 10 == 0) || (edgeX && z == 5);
                for (int y = 1; y <= top; y++) {
                    if (pillar) g.set(x, y, oz + z, "oak_log");
                    else g.set(x, y, oz + z, y == 1 ? "oak_planks" : "glass_pane");
                }
            }
        }
        door(g, doorX, oz, top);
        // Roof: plank beams over the pillars and the waterholes, glass in between.
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean beamX = z == 0 || z == d - 1 || z == 5;
                boolean beamZ = x == 0 || x == w - 1 || x % 5 == 0;
                if (beamX || beamZ) g.set(x, top + 1, oz + z, "oak_planks");
                else g.set(x, top + 1, oz + z, "glass");
            }
        }
        return g;
    }

    /** Fenced dirt yard with four sapling spots and an open shed with a workbench. */
    static Grid lumberyard() {
        Grid g = new Grid();
        int w = 11, d = 13;
        g.fill(0, 0, 0, w - 1, 0, d - 1, "dirt");
        g.fill(1, 0, 9, w - 2, 0, d - 2, "oak_planks");
        for (int z = 1; z <= 8; z++) g.set(5, 0, z, "dirt_path");
        fenceRing(g, 0, 1, 0, w, d, 5);
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) g.set(c[0], 2, c[1], "torch");
        for (int[] s : new int[][]{{2, 2}, {8, 2}, {2, 6}, {8, 6}}) g.set(s[0], 1, s[1], "oak_sapling");
        // Shed: back wall, side walls, two front posts, slab roof.
        g.fill(1, 1, 11, w - 2, 2, 11, "oak_planks");
        g.fill(1, 1, 9, 1, 2, 11, "oak_planks");
        g.fill(w - 2, 1, 9, w - 2, 2, 11, "oak_planks");
        for (int x : new int[]{1, w - 2}) {
            g.fill(x, 1, 9, x, 2, 9, "oak_log");
            g.fill(x, 1, 11, x, 2, 11, "oak_log");
        }
        g.fill(0, 3, 8, w - 1, 3, d - 1, "oak_slab[type=bottom]");
        g.set(3, 1, 10, "crafting_table");
        g.set(5, 1, 10, "chest[facing=north]");
        g.set(7, 1, 10, "oak_log");
        g.set(5, 2, 10, "wall_torch[facing=north]");
        return g;
    }

    /** One (or two side by side) 11x11 fenced pens with a feeding shelter. */
    static Grid ranch(int pens) {
        Grid g = new Grid();
        int s = 11;
        for (int p = 0; p < pens; p++) {
            int ox = p * (s - 1);
            fenceRing(g, ox, 0, 0, s, s, ox + 5);
            for (int[] c : new int[][]{{ox, 0}, {ox + s - 1, 0}, {ox, s - 1}, {ox + s - 1, s - 1}}) g.set(c[0], 1, c[1], "torch");
            // Shelter in a back corner (mirrored for the second pen).
            int sx = p == 0 ? ox + 1 : ox + s - 4;
            for (int x : new int[]{sx, sx + 2}) {
                for (int z : new int[]{s - 4, s - 2}) g.fill(x, 0, z, x, 1, z, "oak_log");
            }
            g.fill(sx, 2, s - 4, sx + 2, 2, s - 2, "oak_slab[type=bottom]");
            g.set(sx + 1, 0, s - 2, "hay_block");
            g.set(sx + 1, 1, s - 2, "hay_block");
            g.set(sx + 1, 0, s - 3, "hay_block");
            // Lit hay bale in the middle keeps the pen free of monsters.
            g.set(ox + 5, 0, 5, "hay_block");
            g.set(ox + 5, 1, 5, "torch");
        }
        if (pens > 1) g.set(s - 1, 0, 5, "oak_fence_gate[facing=east]");
        return g;
    }

    /** Fenced flower garden with two beehives over campfires (the smoke calms the bees). */
    static Grid apiary() {
        Grid g = new Grid();
        int s = 7;
        g.fill(0, 0, 0, s - 1, 0, s - 1, "dirt");
        g.set(3, 0, 1, "dirt_path");
        g.set(3, 0, 2, "dirt_path");
        fenceRing(g, 0, 1, 0, s, s, 3);
        for (int[] c : new int[][]{{0, 0}, {s - 1, 0}, {0, s - 1}, {s - 1, s - 1}}) g.set(c[0], 2, c[1], "torch");
        for (int x : new int[]{2, 4}) {
            g.set(x, 1, 5, "campfire[facing=south,lit=true,signal_fire=false]");
            g.set(x, 2, 5, "beehive[facing=south,honey_level=0]");
        }
        g.set(3, 1, 5, "oak_log");
        g.set(3, 2, 5, "oak_slab[type=bottom]");
        String[] flowers = {"dandelion", "poppy", "oxeye_daisy", "cornflower", "allium", "azure_bluet", "dandelion", "poppy"};
        int[][] spots = {{1, 1}, {5, 1}, {1, 3}, {5, 3}, {2, 3}, {4, 3}, {1, 5}, {5, 5}};
        for (int i = 0; i < spots.length; i++) g.set(spots[i][0], 1, spots[i][1], flowers[i]);
        return g;
    }

    /** 9x7 house with four beds; ridge along the long side. */
    static Grid dormitory() {
        Grid g = new Grid();
        house(g, 1, 1, 9, 7, 3, true);
        for (int x : new int[]{2, 4, 6, 8}) {
            g.set(x, 1, 5, "white_bed[facing=south,part=foot,occupied=false]");
            g.set(x, 1, 6, "white_bed[facing=south,part=head,occupied=false]");
        }
        for (int x : new int[]{3, 7}) g.set(x, 1, 6, "barrel[facing=up]");
        g.set(5, 2, 6, "wall_torch[facing=north]");
        g.set(2, 2, 2, "wall_torch[facing=east]");
        g.set(8, 2, 2, "wall_torch[facing=west]");
        for (int x : new int[]{3, 7}) {
            g.set(x, 2, 1, "glass_pane");
            g.set(x, 2, 7, "glass_pane");
        }
        g.set(1, 2, 4, "glass_pane");
        g.set(9, 2, 4, "glass_pane");
        return g;
    }

    /**
     * Pier on log piles: layer 0 is the water surface, the deck is one above. A covered end with
     * a barrel; maids fish sitting in a boat or on a TLM cushion placed by the player.
     */
    static Grid fishingDock() {
        Grid g = new Grid();
        int w = 5, d = 11;
        for (int z : new int[]{2, 5, 8, d - 1}) {
            g.set(0, 0, z, "oak_log");
            g.set(w - 1, 0, z, "oak_log");
        }
        g.fill(0, 1, 0, w - 1, 1, d - 1, "oak_planks");
        for (int z = 1; z < d; z++) {
            g.set(0, 2, z, "oak_fence");
            g.set(w - 1, 2, z, "oak_fence");
        }
        g.fill(1, 2, d - 1, w - 2, 2, d - 1, "oak_fence");
        g.set(0, 3, 1, "torch");
        g.set(w - 1, 3, 1, "torch");
        // Roofed end.
        for (int x : new int[]{0, w - 1}) {
            for (int z : new int[]{6, d - 1}) g.fill(x, 2, z, x, 3, z, "oak_log");
        }
        g.fill(0, 4, 5, w - 1, 4, d - 1, "oak_slab[type=bottom]");
        g.set(2, 3, 8, "lantern[hanging=true]");
        g.set(1, 2, 9, "barrel[facing=up]");
        return g;
    }

    /** Open 7x7 pavilion with a hipped roof, benches and a hanging lantern. */
    static Grid pavilion() {
        Grid g = new Grid();
        int s = 7;
        for (int x = 0; x < s; x++) {
            for (int z = 0; z < s; z++) {
                boolean edge = x == 0 || z == 0 || x == s - 1 || z == s - 1;
                g.set(x, 0, z, edge ? "cobblestone_slab[type=bottom]" : "oak_planks");
            }
        }
        for (int[] c : new int[][]{{1, 1}, {5, 1}, {1, 5}, {5, 5}}) g.fill(c[0], 1, c[1], c[0], 3, c[1], "oak_log");
        g.set(2, 1, 1, "oak_fence");
        g.set(4, 1, 1, "oak_fence");
        g.set(2, 2, 1, "potted_poppy");
        g.set(4, 2, 1, "potted_dandelion");
        for (int z = 2; z <= 4; z++) {
            g.set(1, 1, z, "oak_fence");
            g.set(5, 1, z, "oak_fence");
        }
        for (int x = 2; x <= 4; x++) g.set(x, 1, 5, "oak_stairs[facing=south,half=bottom]");
        g.fill(1, 4, 1, 5, 4, 5, "oak_planks");
        hipRing(g, 4, 0, s - 1);
        hipRing(g, 5, 1, s - 2);
        hipRing(g, 6, 2, s - 3);
        g.set(3, 6, 3, "oak_planks");
        g.set(3, 7, 3, "oak_slab[type=bottom]");
        g.set(3, 3, 3, "lantern[hanging=true]");
        return g;
    }

    /** 9x7 dining house: two tables with chairs, stoves and barrels along the back wall. */
    static Grid canteen() {
        Grid g = new Grid();
        house(g, 1, 1, 9, 7, 3, true);
        g.set(3, 1, 6, "smoker[facing=north,lit=false]");
        g.set(4, 1, 6, "barrel[facing=north]");
        g.set(6, 1, 6, "barrel[facing=north]");
        g.set(7, 1, 6, "furnace[facing=north,lit=false]");
        g.set(5, 1, 6, "crafting_table");
        for (int x : new int[]{3, 7}) {
            g.set(x, 1, 4, "oak_fence");
            g.set(x, 2, 4, "oak_pressure_plate");
            g.set(x - 1, 1, 4, "oak_stairs[facing=west,half=bottom]");
            g.set(x + 1, 1, 4, "oak_stairs[facing=east,half=bottom]");
        }
        g.set(5, 2, 6, "wall_torch[facing=north]");
        g.set(2, 2, 2, "wall_torch[facing=east]");
        g.set(8, 2, 2, "wall_torch[facing=west]");
        for (int x : new int[]{3, 7}) g.set(x, 2, 1, "glass_pane");
        g.set(1, 2, 4, "glass_pane");
        g.set(9, 2, 4, "glass_pane");
        return g;
    }

    /** 11x9 hall with three beds and job site blocks: villagers move in here. */
    static Grid villageHall() {
        Grid g = new Grid();
        house(g, 1, 1, 11, 9, 3, true);
        for (int x : new int[]{3, 6, 9}) {
            g.set(x, 1, 7, "white_bed[facing=south,part=foot,occupied=false]");
            g.set(x, 1, 8, "white_bed[facing=south,part=head,occupied=false]");
        }
        g.set(2, 1, 4, "composter[level=0]");
        g.set(10, 1, 4, "fletching_table");
        g.set(10, 1, 5, "barrel[facing=west]");
        g.set(2, 1, 5, "crafting_table");
        g.set(6, 2, 8, "wall_torch[facing=north]");
        g.set(2, 2, 2, "wall_torch[facing=east]");
        g.set(10, 2, 2, "wall_torch[facing=west]");
        for (int x : new int[]{3, 9}) g.set(x, 2, 1, "glass_pane");
        for (int z : new int[]{4, 6}) {
            g.set(1, 2, z, "glass_pane");
            g.set(11, 2, z, "glass_pane");
        }
        return g;
    }

    /**
     * Fenced 11x11 yard (traders stay inside, the gate keeps their llamas in) with a roofed
     * counter at the back: the cartography table there is the travel desk.
     */
    static Grid tradingPost() {
        Grid g = new Grid();
        int s = 11;
        for (int x = 0; x < s; x++) {
            for (int z = 0; z < s; z++) {
                boolean edge = x == 0 || z == 0 || x == s - 1 || z == s - 1;
                g.set(x, 0, z, edge ? "cobblestone" : "dirt");
            }
        }
        for (int z = 1; z <= 7; z++) g.set(5, 0, z, "cobblestone");
        fenceRing(g, 0, 1, 0, s, s, 5);
        for (int[] c : new int[][]{{0, 0}, {s - 1, 0}, {0, s - 1}, {s - 1, s - 1}}) g.set(c[0], 2, c[1], "torch");
        // Counter: plank floor, back wall, two front posts, slab roof.
        g.fill(1, 0, 8, s - 2, 0, s - 2, "oak_planks");
        g.fill(1, 1, 9, s - 2, 2, 9, "oak_planks");
        for (int x : new int[]{1, s - 2}) {
            g.fill(x, 1, 8, x, 2, 8, "oak_log");
            g.fill(x, 1, 9, x, 2, 9, "oak_log");
        }
        g.fill(0, 3, 7, s - 1, 3, s - 1, "oak_slab[type=bottom]");
        g.set(5, 1, 8, "cartography_table");
        g.set(3, 1, 8, "barrel[facing=up]");
        g.set(7, 1, 8, "barrel[facing=up]");
        g.set(5, 2, 8, "wall_torch[facing=north]");
        // Hay for the llamas.
        g.set(1, 1, 1, "hay_block");
        g.set(2, 1, 1, "hay_block");
        g.set(1, 1, 2, "hay_block");
        return g;
    }

    /** 5x5 stone and timber tower: a ladder inside up to a railed lookout under a slab roof. */
    static Grid watchtower() {
        Grid g = new Grid();
        int s = 5, oz = 1, deck = 9;
        for (int x = 0; x < s; x++) {
            for (int z = 0; z < s; z++) {
                boolean edgeX = x == 0 || x == s - 1, edgeZ = z == 0 || z == s - 1;
                g.set(x, 0, oz + z, edgeX || edgeZ ? "cobblestone" : "oak_planks");
                if (!edgeX && !edgeZ) continue;
                for (int y = 1; y < deck; y++) {
                    String state = edgeX && edgeZ ? "oak_log" : y <= 2 ? "cobblestone" : "oak_planks";
                    g.set(x, y, oz + z, state);
                }
            }
        }
        door(g, 2, oz, 2);
        for (int y = 1; y <= deck; y++) g.set(2, y, oz + 3, "ladder[facing=north]");
        // Windows on the sides.
        for (int y : new int[]{4, 7}) {
            g.set(0, y, oz + 2, "glass_pane");
            g.set(s - 1, y, oz + 2, "glass_pane");
            g.set(2, y, oz, "glass_pane");
        }
        // Lookout: plank deck (hole over the ladder), railing, corner posts, slab roof.
        for (int x = 0; x < s; x++) {
            for (int z = 0; z < s; z++) if (!(x == 2 && z == 3)) g.set(x, deck, oz + z, "oak_planks");
        }
        for (int x = 0; x < s; x++) {
            for (int z = 0; z < s; z++) {
                boolean corner = (x == 0 || x == s - 1) && (z == 0 || z == s - 1);
                boolean edge = x == 0 || z == 0 || x == s - 1 || z == s - 1;
                if (corner) g.fill(x, deck + 1, oz + z, x, deck + 2, oz + z, "oak_log");
                else if (edge) g.set(x, deck + 1, oz + z, "oak_fence");
            }
        }
        g.fill(0, deck + 3, oz, s - 1, deck + 3, oz + s - 1, "oak_slab[type=bottom]");
        g.set(2, deck + 2, oz + 1, "lantern[hanging=true]");
        return g;
    }

    /** A torii over a stone path through a flower garden, stone lanterns, and a small hall at the end. */
    static Grid shrine() {
        Grid g = new Grid();
        int w = 9;
        for (int z = 0; z <= 7; z++) {
            for (int x = 0; x < w; x++) g.set(x, 0, z, x >= 3 && x <= 5 ? "stone_bricks" : "dirt");
        }
        // Torii.
        for (int x : new int[]{1, 7}) g.fill(x, 1, 2, x, 4, 2, "red_wool");
        g.fill(1, 4, 2, 7, 4, 2, "red_wool");
        g.fill(0, 5, 2, 8, 5, 2, "red_wool");
        g.fill(0, 6, 2, 8, 6, 2, "oak_slab[type=bottom]");
        // Stone lanterns and flowers.
        for (int x : new int[]{2, 6}) {
            g.set(x, 1, 5, "cobblestone_wall");
            g.set(x, 2, 5, "lantern[hanging=false]");
        }
        String[] flowers = {"poppy", "dandelion", "oxeye_daisy", "cornflower", "allium", "azure_bluet"};
        int[][] spots = {{0, 0}, {8, 0}, {0, 4}, {8, 4}, {1, 6}, {7, 6}};
        for (int i = 0; i < spots.length; i++) g.set(spots[i][0], 1, spots[i][1], flowers[i]);
        // Hall.
        house(g, 1, 8, 7, 7, 3, false);
        g.set(4, 1, 13, "barrel[facing=up]");
        g.set(3, 1, 13, "potted_poppy");
        g.set(5, 1, 13, "potted_dandelion");
        g.set(2, 2, 10, "wall_torch[facing=east]");
        g.set(6, 2, 10, "wall_torch[facing=west]");
        g.set(4, 2, 13, "wall_torch[facing=north]");
        return g;
    }

    /**
     * 9x8 shop open at the front: customers stand under the porch, the clerk behind a counter of
     * barrels around the stonecutter (the shop desk), goods on show along the back wall.
     */
    static Grid buildersShop() {
        Grid g = new Grid();
        int w = 9, d = 8, top = 3;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || x == w - 1 || z == 0 || z == d - 1;
                g.set(x, 0, z, edge ? "cobblestone" : "oak_planks");
            }
        }
        // Back and side walls (the front two rows are an open porch).
        for (int y = 1; y <= top; y++) {
            for (int x = 0; x < w; x++) g.set(x, y, d - 1, x == 0 || x == w - 1 ? "oak_log" : y == 1 ? "cobblestone" : "oak_planks");
            for (int z = 2; z < d - 1; z++) {
                for (int x : new int[]{0, w - 1}) g.set(x, y, z, z == 2 ? "oak_log" : y == 1 ? "cobblestone" : "oak_planks");
            }
            g.set(0, y, 0, "oak_log");
            g.set(w - 1, y, 0, "oak_log");
        }
        g.fill(0, top + 1, 0, w - 1, top + 1, d - 1, "oak_planks");
        g.fill(0, top + 2, 0, w - 1, top + 2, 0, "oak_slab[type=bottom]");
        g.set(0, 2, 4, "glass_pane");
        g.set(w - 1, 2, 4, "glass_pane");
        // Counter with the stonecutter in the middle; ways round it at both ends.
        for (int x : new int[]{2, 3, 5, 6}) g.set(x, 1, 3, "barrel[facing=up]");
        g.set(4, 1, 3, "stonecutter[facing=south]");
        // Goods on show.
        g.set(1, 1, 6, "oak_log[axis=x]");
        g.set(2, 1, 6, "oak_log[axis=x]");
        g.set(1, 2, 6, "oak_log[axis=x]");
        g.set(3, 1, 6, "cobblestone");
        g.set(4, 1, 6, "cobblestone");
        g.set(3, 2, 6, "cobblestone");
        g.set(6, 1, 6, "glass");
        g.set(7, 1, 6, "glass");
        g.set(7, 2, 6, "glass");
        g.set(5, 1, 6, "crafting_table");
        g.set(4, top, 4, "lantern[hanging=true]");
        g.set(1, 2, 0, "wall_torch[facing=east]");
        g.set(w - 2, 2, 0, "wall_torch[facing=west]");
        return g;
    }

    // ---------------------------------------------------------------- fine versions

    /** Material swaps from a basic template to its fine version. */
    static final Map<String, String> FINE = Map.ofEntries(
            Map.entry("cobblestone", "stone_bricks"),
            Map.entry("cobblestone_slab", "stone_brick_slab"),
            Map.entry("cobblestone_wall", "stone_brick_wall"),
            Map.entry("oak_log", "spruce_log"),
            Map.entry("oak_stairs", "dark_oak_stairs"),
            Map.entry("oak_slab", "dark_oak_slab"),
            Map.entry("oak_door", "spruce_door"),
            Map.entry("oak_fence", "spruce_fence"),
            Map.entry("oak_fence_gate", "spruce_fence_gate"),
            Map.entry("torch", "lantern"));

    /** The fine version of a basic template: swapped materials, then the template's own trim. */
    static Grid fine(Grid basic, java.util.function.Consumer<Grid> trim) {
        basic.replace(FINE);
        trim.accept(basic);
        return basic;
    }

    private static final String[] POTS = {"potted_poppy", "potted_dandelion", "potted_azure_bluet", "potted_oxeye_daisy"};

    /**
     * Trim of a {@link #house}: a stripped spruce beam round the top of the walls, a lantern post on
     * each side of the door step and potted flowers along the front.
     */
    static void houseTrim(Grid g, int ox, int oz, int w, int d, int top) {
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                if ((edgeX || edgeZ) && !(edgeX && edgeZ)) g.set(ox + x, top, oz + z, "stripped_spruce_log[axis=" + (edgeZ ? "x" : "z") + "]");
            }
        }
        int doorX = ox + w / 2;
        for (int x : new int[]{doorX - 1, doorX + 1}) {
            g.set(x, 0, oz - 1, "spruce_fence");
            g.set(x, 1, oz - 1, "lantern");
        }
        int pot = 0;
        for (int x = ox; x < ox + w; x++) {
            if (Math.abs(x - doorX) <= 1 || (x - ox) % 2 != 0) continue;
            g.set(x, 0, oz - 1, POTS[pot++ % POTS.length]);
        }
    }

    /** Trim of a {@link #greenhouse}: lanterns at the door and one hanging over each water hole. */
    static void greenhouseTrim(Grid g, int fields) {
        int iw = fields * 9 + (fields - 1), w = iw + 2, oz = 1, top = 3, doorX = w / 2;
        for (int x : new int[]{doorX - 1, doorX + 1}) {
            g.set(x, 0, oz - 1, "spruce_fence");
            g.set(x, 1, oz - 1, "lantern");
        }
        for (int f = 0; f < fields; f++) g.set(1 + f * 10 + 4, top, oz + 5, "lantern[hanging=true]");
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * A house with walls at x in [ox, ox+w), z in [oz, oz+d): cobblestone foundation and base band,
     * log corners, plank walls, plank floor, door in the middle of the front (low z) wall with a
     * step in front, and a gable roof overhanging the gable ends by one block.
     */
    static void house(Grid g, int ox, int oz, int w, int d, int top, boolean ridgeAlongX) {
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                g.set(ox + x, 0, oz + z, edgeX || edgeZ ? "cobblestone" : "oak_planks");
                if (!edgeX && !edgeZ) continue;
                for (int y = 1; y <= top; y++) {
                    String state = edgeX && edgeZ ? "oak_log" : y == 1 ? "cobblestone" : "oak_planks";
                    g.set(ox + x, y, oz + z, state);
                }
            }
        }
        door(g, ox + w / 2, oz, top);
        int span = ridgeAlongX ? d : w;
        int length = ridgeAlongX ? w : d;
        int half = span / 2;
        for (int k = 0; k <= half; k++) {
            int y = top + 1 + k;
            for (int l = -1; l <= length; l++) {
                if (k == half && span % 2 == 1) {
                    roof(g, ridgeAlongX, ox, oz, l, k, y, "oak_slab[type=bottom]");
                } else if (k < half || span % 2 == 0) {
                    roof(g, ridgeAlongX, ox, oz, l, k, y, ridgeAlongX ? "oak_stairs[facing=south,half=bottom]" : "oak_stairs[facing=east,half=bottom]");
                    roof(g, ridgeAlongX, ox, oz, l, span - 1 - k, y, ridgeAlongX ? "oak_stairs[facing=north,half=bottom]" : "oak_stairs[facing=west,half=bottom]");
                }
            }
            // Gable walls under the roof.
            for (int c = k + 1; c <= span - 2 - k; c++) {
                for (int end : new int[]{0, length - 1}) roof(g, ridgeAlongX, ox, oz, end, c, y, "oak_planks");
            }
        }
    }

    /** Sets a roof cell given its position along the ridge ({@code l}) and across it ({@code c}). */
    private static void roof(Grid g, boolean ridgeAlongX, int ox, int oz, int l, int c, int y, String state) {
        if (ridgeAlongX) g.set(ox + l, y, oz + c, state);
        else g.set(ox + c, y, oz + l, state);
    }

    /** Door in the front (low z) wall at {@code x}, a plank lintel and a stair step outside. */
    static void door(Grid g, int x, int oz, int top) {
        g.set(x, 1, oz, "oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        g.set(x, 2, oz, "oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        for (int y = 3; y <= top; y++) g.set(x, y, oz, "oak_planks");
        g.set(x, 0, oz - 1, "oak_stairs[facing=south,half=bottom]");
    }

    /** Fence around the rectangle, with a gate in the front (low z) side at {@code gateX}. */
    static void fenceRing(Grid g, int ox, int y, int oz, int w, int d, int gateX) {
        for (int x = ox; x < ox + w; x++) {
            g.set(x, y, oz, x == gateX ? "oak_fence_gate[facing=south]" : "oak_fence");
            g.set(x, y, oz + d - 1, "oak_fence");
        }
        for (int z = oz; z < oz + d; z++) {
            g.set(ox, y, z, "oak_fence");
            g.set(ox + w - 1, y, z, "oak_fence");
        }
    }

    /** Horizontal log frame around the rectangle. */
    static void frame(Grid g, int ox, int y, int oz, int w, int d) {
        for (int x = ox; x < ox + w; x++) {
            g.set(x, y, oz, "oak_log[axis=x]");
            g.set(x, y, oz + d - 1, "oak_log[axis=x]");
        }
        for (int z = oz + 1; z < oz + d - 1; z++) {
            g.set(ox, y, z, "oak_log[axis=z]");
            g.set(ox + w - 1, y, z, "oak_log[axis=z]");
        }
    }

    /** One ring of a hipped roof over the square [lo, hi]^2; corner shapes are computed later. */
    static void hipRing(Grid g, int y, int lo, int hi) {
        for (int i = lo; i <= hi; i++) {
            g.set(i, y, lo, "oak_stairs[facing=south,half=bottom]");
            g.set(i, y, hi, "oak_stairs[facing=north,half=bottom]");
        }
        for (int i = lo + 1; i < hi; i++) {
            g.set(lo, y, i, "oak_stairs[facing=east,half=bottom]");
            g.set(hi, y, i, "oak_stairs[facing=west,half=bottom]");
        }
    }

    // ---------------------------------------------------------------- grid

    /** Sparse block grid; coordinates may be any non-negative ints. */
    static final class Grid {
        private final Map<IntPos, BlockStateData> blocks = new HashMap<>();

        void set(int x, int y, int z, String state) {
            if (x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("negative position " + x + "," + y + "," + z);
            BlockStateData s = BlockStateData.parse(state);
            if (s.isAir()) blocks.remove(new IntPos(x, y, z));
            else blocks.put(new IntPos(x, y, z), s);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, state);
        }

        /** Swaps blocks by path (without namespace), keeping their properties. */
        void replace(Map<String, String> paths) {
            blocks.replaceAll((p, s) -> {
                String to = paths.get(s.path());
                return to == null ? s : new BlockStateData("minecraft:" + to, s.properties());
            });
        }

        BlockStateData get(IntPos p) {
            return blocks.getOrDefault(p, BlockStateData.AIR);
        }

        IntPos size() {
            int mx = 0, my = 0, mz = 0;
            for (IntPos p : blocks.keySet()) {
                mx = Math.max(mx, p.x());
                my = Math.max(my, p.y());
                mz = Math.max(mz, p.z());
            }
            return new IntPos(mx + 1, my + 1, mz + 1);
        }

        Schematic toSchematic(String name) {
            Map<IntPos, BlockStateData> resolved = new HashMap<>();
            for (Map.Entry<IntPos, BlockStateData> e : blocks.entrySet()) resolved.put(e.getKey(), withShape(e.getKey(), e.getValue()));
            IntPos size = size();
            LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(name, IntPos.ZERO, size);
            resolved.forEach((p, s) -> rb.set(p.x(), p.y(), p.z(), s));
            return LitematicWriter.schematic(name, "Maid Builder", 0, DATA_VERSION, List.of(rb.build()));
        }

        String summary() {
            Map<String, Integer> counts = new TreeMap<>();
            for (BlockStateData s : blocks.values()) {
                if ("upper".equals(s.get("half")) && s.path().endsWith("_door")) continue;
                if ("head".equals(s.get("part"))) continue;
                counts.merge(s.path(), 1, Integer::sum);
            }
            return "size=" + size() + " " + counts;
        }

        // ---- neighbour-dependent state, mirroring what the game computes on placement

        private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // north east south west
        private static final String[] DIR_NAMES = {"north", "east", "south", "west"};

        private BlockStateData withShape(IntPos p, BlockStateData s) {
            if (isFence(s) || isPane(s)) {
                for (int i = 0; i < 4; i++) {
                    BlockStateData n = get(p.add(DIRS[i][0], 0, DIRS[i][1]));
                    boolean connect = isPane(s)
                            ? isPane(n) || isSturdy(n)
                            : isFence(n) || isSturdy(n) || (isGate(n) && gateConnects(n, i));
                    s = s.with(DIR_NAMES[i], Boolean.toString(connect));
                }
                return s.with("waterlogged", "false");
            }
            if (s.path().endsWith("_stairs")) return s.with("shape", stairShape(p, s)).with("waterlogged", "false");
            return s;
        }

        private String stairShape(IntPos p, BlockStateData s) {
            int f = dirIndex(s.get("facing"));
            BlockStateData front = get(step(p, f));
            if (isStairs(front) && front.get("half").equals(s.get("half"))) {
                int f1 = dirIndex(front.get("facing"));
                if (f1 % 2 != f % 2 && canTakeShape(p, s, (f1 + 2) % 4)) return f1 == ccw(f) ? "outer_left" : "outer_right";
            }
            BlockStateData back = get(step(p, (f + 2) % 4));
            if (isStairs(back) && back.get("half").equals(s.get("half"))) {
                int f2 = dirIndex(back.get("facing"));
                if (f2 % 2 != f % 2 && canTakeShape(p, s, f2)) return f2 == ccw(f) ? "inner_left" : "inner_right";
            }
            return "straight";
        }

        private boolean canTakeShape(IntPos p, BlockStateData s, int dir) {
            BlockStateData n = get(step(p, dir));
            return !isStairs(n) || !n.get("facing").equals(s.get("facing")) || !n.get("half").equals(s.get("half"));
        }

        private static IntPos step(IntPos p, int dir) {
            return p.add(DIRS[dir][0], 0, DIRS[dir][1]);
        }

        private static int ccw(int dir) {
            return (dir + 3) % 4;
        }

        private static int dirIndex(String name) {
            for (int i = 0; i < 4; i++) if (DIR_NAMES[i].equals(name)) return i;
            throw new IllegalArgumentException("not horizontal: " + name);
        }

        private static boolean isStairs(BlockStateData s) {
            return s.path().endsWith("_stairs");
        }

        private static boolean isFence(BlockStateData s) {
            return s.path().endsWith("_fence") && !s.path().equals("nether_brick_fence");
        }

        private static boolean isGate(BlockStateData s) {
            return s.path().endsWith("_fence_gate");
        }

        private static boolean isPane(BlockStateData s) {
            return s.path().endsWith("glass_pane") || s.path().equals("iron_bars");
        }

        /** A fence gate connects on the sides perpendicular to its facing. */
        private static boolean gateConnects(BlockStateData gate, int dirFromFence) {
            return dirIndex(gate.get("facing")) % 2 != dirFromFence % 2;
        }

        private static final Set<String> STURDY = Set.of("cobblestone", "glass", "dirt", "hay_block", "barrel",
                "crafting_table", "beehive", "stone_bricks");

        /** Full blocks whose side faces fences and panes attach to. */
        private static boolean isSturdy(BlockStateData s) {
            String p = s.path();
            return STURDY.contains(p) || p.endsWith("_planks") || p.endsWith("_log") || p.endsWith("_wood");
        }
    }
}
