package com.brianthemint.apricornharvester;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Key binding that opens the planting GUI. It shows up in Options &gt; Controls under the
 * "Apricorn Harvester" category, so the key itself is set by the player (default: K).
 *
 * <p>The mapping is registered on the mod event bus during startup; the GUI can only be opened
 * once Baritone is hooked up ({@link #setContext}), which happens on the first client tick.
 */
public final class ApricornKeybinds {

    public static final String CATEGORY = "key.categories.apricornharvester";

    /**
     * The addon's only key: everything lives in one tabbed window, so there is nothing else to
     * bind.
     */
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.apricornharvester.gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private static AddonContext context;

    private ApricornKeybinds() {
    }

    /** Registers the mappings and the tick listener that reacts to them. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_GUI));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
    }

    /** Called once Baritone is available, so the GUIs have something to work against. */
    public static void setContext(AddonContext context) {
        ApricornKeybinds.context = context;
    }

    private static void onClientTick() {
        if (context == null) {
            return;
        }
        // The factory, the ore miner and the hunter are plain tick controllers (they hand control
        // to Baritone's own processes), so they are driven from here.
        context.tickControllers();

        boolean pressed = false;
        while (OPEN_GUI.consumeClick()) {
            pressed = true;
        }
        if (!pressed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || context.baritone() == null) {
            return;
        }
        mc.setScreen(new ApricornGui(context));
    }
}
