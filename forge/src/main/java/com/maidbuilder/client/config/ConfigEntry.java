package com.maidbuilder.client.config;

import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One setting shown on the settings screen: its value and the spec entry (range, validation).
 * Labels and tooltips use the same {@code maidbuilder.configuration.<name>} language keys as the
 * 1.21.1 settings screen.
 */
record ConfigEntry(ForgeConfigSpec spec, ForgeConfigSpec.ConfigValue<?> value) {
    String name() {
        List<String> path = value.getPath();
        return path.get(path.size() - 1);
    }

    Component label() {
        return Component.translatable("maidbuilder.configuration." + name());
    }

    Component tooltip() {
        return Component.translatable("maidbuilder.configuration." + name() + ".tooltip");
    }

    ForgeConfigSpec.ValueSpec valueSpec() {
        return spec.getSpec().get(value.getPath());
    }

    @Nullable
    ForgeConfigSpec.Range<?> range() {
        return valueSpec().getRange();
    }

    boolean isBoolean() {
        return value instanceof ForgeConfigSpec.BooleanValue;
    }

    /** Parses and validates text typed for a number setting; null if it is not allowed. */
    @Nullable
    Object parse(String text) {
        try {
            Object parsed = value instanceof ForgeConfigSpec.DoubleValue ? (Object) Double.parseDouble(text.trim())
                    : value instanceof ForgeConfigSpec.LongValue ? (Object) Long.parseLong(text.trim())
                    : (Object) Integer.parseInt(text.trim());
            return valueSpec().test(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    void set(Object newValue) {
        ((ForgeConfigSpec.ConfigValue<Object>) value).set(newValue);
    }

    /** A titled group of settings: one page of the server settings, or all client settings. */
    record Section(String key, List<ConfigEntry> entries) {
        Component label() {
            return Component.translatable(key);
        }

        Component tooltip() {
            return Component.translatable(key + ".tooltip");
        }
    }
}
