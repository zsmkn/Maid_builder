package com.maidbuilder.common.territory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * A building template from data packs ({@code data/<namespace>/maidbuilder/templates/*.json}); the
 * template id is the file id. Name and description are the translation keys
 * {@code template.<namespace>.<path>} and {@code ...desc}.
 *
 * @param structure     schematic in {@code data/<namespace>/maidbuilder/structures/<path>.litematic} (or {@code .nbt})
 * @param category      production / storage / housing / decoration (free text; level conditions refer to it)
 * @param group         buildings of the same kind share a group (small and large greenhouse: "greenhouse"); defaults to the id path
 * @param requiredLevel territory level that unlocks the template
 * @param bonus         range bonus inside the building: "greenhouse", "ranch" or "" for none
 * @param storage       its containers are a warehouse for products (see {@link Workplace})
 * @param icon          item shown in the catalog
 * @param order         sort order within a category
 */
public record TemplateDef(ResourceLocation id, ResourceLocation structure, String category, String group, int requiredLevel,
                          int prosperity, String bonus, boolean storage, Optional<Workplace> workplace,
                          ResourceLocation icon, int order) {
    public static final String BONUS_GREENHOUSE = "greenhouse";
    public static final String BONUS_RANCH = "ranch";

    /**
     * A maid assigned to the building works there with this TLM task.
     *
     * @param products items (ids or {@code #tags}) she takes to the warehouse
     * @param keep     how many of each product she keeps (e.g. seeds for replanting)
     */
    public record Workplace(ResourceLocation task, int maxMaids, List<String> products, int keep) {
        public static final Codec<Workplace> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("task").forGetter(Workplace::task),
                Codec.intRange(1, 64).optionalFieldOf("max_maids", 2).forGetter(Workplace::maxMaids),
                Codec.STRING.listOf().optionalFieldOf("products", List.of()).forGetter(Workplace::products),
                Codec.intRange(0, 4096).optionalFieldOf("keep", 0).forGetter(Workplace::keep)
        ).apply(i, Workplace::new));

        public static final StreamCodec<ByteBuf, Workplace> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Workplace::task,
                ByteBufCodecs.VAR_INT, Workplace::maxMaids,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), Workplace::products,
                ByteBufCodecs.VAR_INT, Workplace::keep,
                Workplace::new);
    }

    /** The JSON file, without the id (taken from the file name). */
    record Json(ResourceLocation structure, String category, Optional<String> group, int requiredLevel, int prosperity,
                        String bonus, boolean storage, Optional<Workplace> workplace, ResourceLocation icon, int order) {
    }

    public static final Codec<Json> JSON = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("structure").forGetter(Json::structure),
            Codec.STRING.optionalFieldOf("category", "production").forGetter(Json::category),
            Codec.STRING.optionalFieldOf("group").forGetter(Json::group),
            Codec.intRange(1, 1000).optionalFieldOf("required_level", 1).forGetter(Json::requiredLevel),
            Codec.intRange(0, 100000).optionalFieldOf("prosperity", 0).forGetter(Json::prosperity),
            Codec.STRING.optionalFieldOf("bonus", "").forGetter(Json::bonus),
            Codec.BOOL.optionalFieldOf("storage", false).forGetter(Json::storage),
            Workplace.CODEC.optionalFieldOf("workplace").forGetter(Json::workplace),
            ResourceLocation.CODEC.optionalFieldOf("icon", ResourceLocation.withDefaultNamespace("bricks")).forGetter(Json::icon),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Json::order)
    ).apply(i, Json::new));

    public static TemplateDef of(ResourceLocation id, Json json) {
        return new TemplateDef(id, json.structure(), json.category(), json.group().orElse(id.getPath()), json.requiredLevel(),
                json.prosperity(), json.bonus(), json.storage(), json.workplace(), json.icon(), json.order());
    }

    public static final StreamCodec<ByteBuf, TemplateDef> STREAM_CODEC = StreamCodec.of(
            (buf, d) -> {
                ResourceLocation.STREAM_CODEC.encode(buf, d.id());
                ResourceLocation.STREAM_CODEC.encode(buf, d.structure());
                ByteBufCodecs.STRING_UTF8.encode(buf, d.category());
                ByteBufCodecs.STRING_UTF8.encode(buf, d.group());
                ByteBufCodecs.VAR_INT.encode(buf, d.requiredLevel());
                ByteBufCodecs.VAR_INT.encode(buf, d.prosperity());
                ByteBufCodecs.STRING_UTF8.encode(buf, d.bonus());
                ByteBufCodecs.BOOL.encode(buf, d.storage());
                ByteBufCodecs.optional(Workplace.STREAM_CODEC).encode(buf, d.workplace());
                ResourceLocation.STREAM_CODEC.encode(buf, d.icon());
                ByteBufCodecs.VAR_INT.encode(buf, d.order());
            },
            buf -> new TemplateDef(ResourceLocation.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.optional(Workplace.STREAM_CODEC).decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    public String nameKey() {
        return "template." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    public String descriptionKey() {
        return nameKey() + ".desc";
    }
}
