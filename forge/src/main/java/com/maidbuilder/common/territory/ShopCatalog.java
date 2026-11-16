package com.maidbuilder.common.territory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.maidbuilder.MaidBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the building supplies shop sells, from data packs ({@code data/<namespace>/maidbuilder/shop/*.json},
 * read in file id order). Each file is one shelf: a category, the territory level it needs, the price
 * in emeralds for a bundle of {@code count} items, how many of each item the shop sells per day, and
 * the items (ids or {@code #tags}). An item on several shelves is sold on the first; items in the tag
 * {@code maidbuilder:not_for_sale} are never sold.
 */
public final class ShopCatalog extends SimplePreparableReloadListener<List<ShopCatalog.Shelf>> {
    private static final FileToIdConverter DEFINITIONS = FileToIdConverter.json("maidbuilder/shop");
    public static final TagKey<Item> NOT_FOR_SALE = TagKey.create(Registries.ITEM, MaidBuilder.id("not_for_sale"));

    /** One shelf as written in its file. */
    public record Shelf(ResourceLocation id, String category, int requiredLevel, int price, int count, int dailyLimit, List<String> items) {
    }

    private record Json(String category, int requiredLevel, int price, int count, int dailyLimit, List<String> items) {
    }

    private static final Codec<Json> JSON = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("category").forGetter(Json::category),
            Codec.intRange(1, 1000).optionalFieldOf("required_level", 1).forGetter(Json::requiredLevel),
            Codec.intRange(1, 64).fieldOf("price").forGetter(Json::price),
            Codec.intRange(1, 64).fieldOf("count").forGetter(Json::count),
            Codec.intRange(1, 100000).optionalFieldOf("daily_limit", 64).forGetter(Json::dailyLimit),
            Codec.STRING.listOf().fieldOf("items").forGetter(Json::items)
    ).apply(i, Json::new));

    /** One item for sale: {@code count} of it for {@code price} emeralds, at most {@code dailyLimit} a day. */
    public record Offer(Item item, String category, int requiredLevel, int price, int count, int dailyLimit) {
    }

    private static volatile List<Shelf> shelves = List.of();
    /** The shelves expanded to items; built on first use after a reload (tags are bound only after all listeners ran). */
    @Nullable
    private static volatile Map<Item, Offer> offers;
    /** Entries of the shelves that name no item or an empty tag (checked by the game tests). */
    private static volatile List<String> unknown = List.of();

    @Override
    protected List<Shelf> prepare(ResourceManager manager, ProfilerFiller profiler) {
        List<Shelf> result = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> file : new java.util.TreeMap<>(DEFINITIONS.listMatchingResources(manager)).entrySet()) {
            ResourceLocation id = DEFINITIONS.fileToId(file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Optional<Json> parsed = JSON.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> MaidBuilder.LOGGER.error("Bad shop shelf {}: {}", id, error));
                parsed.ifPresent(j -> result.add(new Shelf(id, j.category(), j.requiredLevel(), j.price(), j.count(), j.dailyLimit(), j.items())));
            } catch (IOException | RuntimeException e) {
                MaidBuilder.LOGGER.error("Cannot load shop shelf {}", id, e);
            }
        }
        return result;
    }

    @Override
    protected void apply(List<Shelf> prepared, ResourceManager manager, ProfilerFiller profiler) {
        shelves = List.copyOf(prepared);
        offers = null;
        MaidBuilder.LOGGER.info("Loaded {} shop shelves", prepared.size());
    }

    /** Every item for sale, in shelf order. */
    public static Map<Item, Offer> offers() {
        Map<Item, Offer> result = offers;
        if (result == null) {
            result = Collections.unmodifiableMap(expand(shelves));
            offers = result;
        }
        return result;
    }

    public static List<String> unknownEntries() {
        offers();
        return unknown;
    }

    @Nullable
    public static Offer offer(Item item) {
        return offers().get(item);
    }

    private static Map<Item, Offer> expand(List<Shelf> shelves) {
        Map<Item, Offer> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Shelf shelf : shelves) {
            for (String entry : shelf.items()) {
                List<Item> items = resolve(shelf.id(), entry);
                if (items.isEmpty()) missing.add(shelf.id() + ": " + entry);
                for (Item item : items) {
                    if (item == Items.AIR || result.containsKey(item) || item.builtInRegistryHolder().is(NOT_FOR_SALE)) continue;
                    result.put(item, new Offer(item, shelf.category(), shelf.requiredLevel(), shelf.price(), shelf.count(), shelf.dailyLimit()));
                }
            }
        }
        unknown = List.copyOf(missing);
        return result;
    }

    private static List<Item> resolve(ResourceLocation shelf, String entry) {
        boolean tag = entry.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? entry.substring(1) : entry);
        if (id == null) {
            MaidBuilder.LOGGER.warn("Shop shelf {}: bad entry {}", shelf, entry);
            return List.of();
        }
        if (tag) {
            List<Item> items = new ArrayList<>();
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id))
                    .ifPresent(set -> set.forEach(holder -> items.add(holder.value())));
            return items;
        }
        Optional<Holder.Reference<Item>> holder = BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, id));
        if (holder.isEmpty()) {
            MaidBuilder.LOGGER.debug("Shop shelf {}: no item {}", shelf, entry);
            return List.of();
        }
        return List.of(holder.get().value());
    }
}
