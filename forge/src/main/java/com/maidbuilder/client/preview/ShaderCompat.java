package com.maidbuilder.client.preview;

import com.maidbuilder.MaidBuilder;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Detects an active shader pack (Oculus, the Forge port of Iris, through the Iris public API it
 * ships, looked up reflectively so Oculus is not a dependency). Shader packs re-light and re-blend everything drawn inside the level pass,
 * which makes the translucent ghost blocks look like real ones; with a pack active the preview is
 * drawn after the pack's final pass instead.
 */
public final class ShaderCompat {
    @Nullable
    private static MethodHandle inUse;
    private static boolean initialised;

    private ShaderCompat() {
    }

    public static boolean shaderPackInUse() {
        if (!initialised) init();
        if (inUse == null) return false;
        try {
            return (boolean) inUse.invoke();
        } catch (Throwable t) {
            MaidBuilder.LOGGER.warn("Oculus/Iris API call failed; treating shaders as off", t);
            inUse = null;
            return false;
        }
    }

    private static void init() {
        initialised = true;
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) return;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            inUse = MethodHandles.publicLookup()
                    .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MaidBuilder.LOGGER.warn("Oculus/Iris is installed but its API could not be found; shader compatibility is off", e);
        }
    }
}
