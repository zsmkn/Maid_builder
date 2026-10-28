# Generates the built-in shop shelves: python tools/shop_shelves.py neoforge/src/main/resources/data/maidbuilder/maidbuilder/shop
import json, os, sys

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]


def family(base, stairs=True, slab=True, wall=False, extra=()):
    items = [base]
    stem = base[:-1] if base.endswith("bricks") or base.endswith("tiles") else base
    if stairs: items.append(stem + "_stairs")
    if slab: items.append(stem + "_slab")
    if wall: items.append(stem + "_wall")
    return items + list(extra)


stone = []
stone += family("cobblestone", wall=True) + family("mossy_cobblestone", wall=True)
stone += family("stone") + ["smooth_stone", "smooth_stone_slab"]
stone += family("stone_bricks", wall=True) + family("mossy_stone_bricks", wall=True)
stone += ["cracked_stone_bricks", "chiseled_stone_bricks"]
for r in ["andesite", "diorite", "granite"]:
    stone += family(r, wall=True) + family("polished_" + r)
stone += family("sandstone", wall=True) + family("smooth_sandstone") + ["cut_sandstone", "cut_sandstone_slab", "chiseled_sandstone"]
stone += family("red_sandstone", wall=True) + family("smooth_red_sandstone") + ["cut_red_sandstone", "cut_red_sandstone_slab", "chiseled_red_sandstone"]

brick = family("bricks", wall=True) + family("mud_bricks", wall=True) + ["packed_mud"]
brick += family("cobbled_deepslate", wall=True) + family("polished_deepslate", wall=True)
brick += family("deepslate_bricks", wall=True) + family("deepslate_tiles", wall=True)
brick += ["cracked_deepslate_bricks", "cracked_deepslate_tiles", "chiseled_deepslate"]
brick += family("tuff", wall=True) + family("polished_tuff", wall=True) + family("tuff_bricks", wall=True) + ["chiseled_tuff", "chiseled_tuff_bricks"]

colors = ["terracotta", "glass", "glass_pane"]
for c in COLORS:
    colors += [c + "_terracotta", c + "_concrete", c + "_wool", c + "_carpet", c + "_stained_glass", c + "_stained_glass_pane"]

decor = ["lantern", "soul_lantern", "chain", "flower_pot", "candle", "bookshelf", "iron_bars", "ladder", "torch", "soul_torch"]
decor += [c + "_candle" for c in COLORS] + [c + "_glazed_terracotta" for c in COLORS]

nature = ["#minecraft:small_flowers", "#minecraft:saplings", "#minecraft:leaves",
          "grass_block", "moss_block", "moss_carpet", "podzol", "coarse_dirt", "rooted_dirt",
          "sand", "red_sand", "gravel", "clay", "dirt_path", "short_grass", "fern", "vine", "lily_pad"]

rare = ["quartz_block", "quartz_stairs", "quartz_slab", "quartz_bricks", "quartz_pillar", "chiseled_quartz_block",
        "smooth_quartz", "smooth_quartz_stairs", "smooth_quartz_slab", "calcite", "dripstone_block"]
rare += family("nether_bricks", wall=True) + family("red_nether_bricks", wall=True) + ["nether_brick_fence", "cracked_nether_bricks", "chiseled_nether_bricks"]
rare += family("blackstone", wall=True) + family("polished_blackstone", wall=True) + family("polished_blackstone_bricks", wall=True)
rare += family("end_stone_bricks", wall=True) + ["purpur_block", "purpur_pillar", "purpur_stairs", "purpur_slab"]
rare += family("prismarine", wall=True) + family("prismarine_bricks") + family("dark_prismarine") + ["sea_lantern"]

wood = ["#minecraft:planks", "#minecraft:logs", "#minecraft:wooden_stairs", "#minecraft:wooden_slabs",
        "#minecraft:wooden_fences", "#minecraft:fence_gates", "#minecraft:wooden_doors", "#minecraft:wooden_trapdoors",
        "#minecraft:wooden_pressure_plates", "#minecraft:wooden_buttons"]

# file name -> (category, level, price, count, daily limit, items)
shelves = {
    "01_wood": ("wood", 2, 2, 16, 128, wood),
    "02_stone": ("stone", 2, 2, 16, 128, stone),
    "03_nature": ("nature", 2, 2, 8, 64, nature),
    "04_brick": ("brick", 3, 2, 8, 64, brick),
    "05_color": ("color", 3, 2, 8, 64, colors),
    "06_decor": ("decor", 3, 2, 4, 32, decor),
    "07_rare": ("rare", 4, 2, 8, 32, rare),
}


def full(i):
    return i if i.startswith("#") or ":" in i else "minecraft:" + i


for name, (cat, lvl, price, count, limit, items) in shelves.items():
    seen = []
    for i in items:
        f = full(i)
        if f not in seen: seen.append(f)
    with open(os.path.join(out, name + ".json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"category": cat, "required_level": lvl, "price": price, "count": count, "daily_limit": limit, "items": seen},
                  fh, indent=2)
        fh.write("\n")
    print(name, len(seen))
