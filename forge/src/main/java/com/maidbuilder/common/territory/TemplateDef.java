package com.maidbuilder.common.territory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.maidbuilder.network.Payloads;
import net.minecraft.network.FriendlyByteBuf;
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

        public void write(FriendlyByteBuf buf) {
            buf.writeResourceLocation(task);
            buf.writeVarInt(maxMaids);
            Payloads.writeList(buf, products, FriendlyByteBuf::writeUtf);
            buf.writeVarInt(keep);
        }

        public static Workplace read(FriendlyByteBuf buf) {
            return new Workplace(buf.readResourceLocation(), buf.readVarInt(), Payloads.readList(buf, 256, FriendlyByteBuf::readUtf),
                    buf.readVarInt());
        }
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
            ResourceLocation.CODEC.optionalFieldOf("icon", new ResourceLocation("bricks")).forGetter(Json::icon),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Json::order)
    ).apply(i, Json::new));

    public static TemplateDef of(ResourceLocation id, Json json) {
        return new TemplateDef(id, json.structure(), json.category(), json.group().orElse(id.getPath()), json.requiredLevel(),
                json.prosperity(), json.bonus(), json.storage(), json.workplace(), json.icon(), json.order());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        buf.writeResourceLocation(structure);
        buf.writeUtf(category);
        buf.writeUtf(group);
        buf.writeVarInt(requiredLevel);
        buf.writeVarInt(prosperity);
        buf.writeUtf(bonus);
        buf.writeBoolean(storage);
        buf.writeOptional(workplace, (b, w) -> w.write(b));
        buf.writeResourceLocation(icon);
        buf.writeVarInt(order);
    }

    public static TemplateDef read(FriendlyByteBuf buf) {
        return new TemplateDef(buf.readResourceLocation(), buf.readResourceLocation(), buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(), buf.readBoolean(), buf.readOptional(Workplace::read), buf.readResourceLocation(),
                buf.readVarInt());
    }

    public String nameKey() {
        return "template." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    public String descriptionKey() {
        return nameKey() + ".desc";
    }
}
