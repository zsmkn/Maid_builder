package com.maidbuilder.client.territory;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Territory key bindings. */
public final class TerritoryKeys {
    private static final String CATEGORY = "key.categories.maidbuilder";

    /** Opens the template catalog inside one's own territory (Caps Lock by default; rebindable). */
    public static final KeyMapping BUILD_MODE = key("build_mode", GLFW.GLFW_KEY_CAPS_LOCK);
    /** Shows / hides territory borders. */
    public static final KeyMapping BORDER = key("territory_border", GLFW.GLFW_KEY_G);

    public static final List<KeyMapping> ALL = List.of(BUILD_MODE, BORDER);

    private TerritoryKeys() {
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.maidbuilder." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, CATEGORY);
    }
}
