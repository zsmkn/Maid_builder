package com.maidbuilder.client;

import com.maidbuilder.network.Payloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/** Key bindings for positioning a schematic with the Blueprint Wand (only act while holding it). */
public final class WandKeys {
    private static final String CATEGORY = "key.categories.maidbuilder";

    public static final KeyMapping ROTATE = key("rotate", GLFW.GLFW_KEY_R);
    public static final KeyMapping MIRROR = key("mirror", GLFW.GLFW_KEY_M);
    public static final KeyMapping UP = key("up", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping DOWN = key("down", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping FORWARD = key("forward", GLFW.GLFW_KEY_UP);
    public static final KeyMapping BACK = key("back", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping LEFT = key("left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping RIGHT = key("right", GLFW.GLFW_KEY_RIGHT);

    static final Map<KeyMapping, Payloads.Adjustment> ACTIONS = Map.of(
            ROTATE, Payloads.Adjustment.ROTATE,
            MIRROR, Payloads.Adjustment.MIRROR,
            UP, Payloads.Adjustment.UP,
            DOWN, Payloads.Adjustment.DOWN,
            FORWARD, Payloads.Adjustment.FORWARD,
            BACK, Payloads.Adjustment.BACK,
            LEFT, Payloads.Adjustment.LEFT,
            RIGHT, Payloads.Adjustment.RIGHT);

    static final List<KeyMapping> ALL = List.of(ROTATE, MIRROR, UP, DOWN, FORWARD, BACK, LEFT, RIGHT);

    private WandKeys() {
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.maidbuilder." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, CATEGORY);
    }
}
