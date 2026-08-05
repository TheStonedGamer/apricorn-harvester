package com.brianthemint.apricornharvester;

import com.brianthemint.apricornharvester.pokeball.PokeballScreen;
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

    public static final KeyMapping OPEN_PLANT_GUI = new KeyMapping(
            "key.apricornharvester.plant_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    public static final KeyMapping OPEN_POKEBALL_GUI = new KeyMapping(
            "key.apricornharvester.pokeball_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            CATEGORY);

    public static final KeyMapping OPEN_CONFIG_GUI = new KeyMapping(
            "key.apricornharvester.config_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private static AddonContext context;

    private ApricornKeybinds() {
    }

    /** Registers the mappings and the tick listener that reacts to them. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(OPEN_PLANT_GUI);
            event.register(OPEN_POKEBALL_GUI);
            event.register(OPEN_CONFIG_GUI);
        });
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

        boolean plantPressed = false;
        while (OPEN_PLANT_GUI.consumeClick()) {
            plantPressed = true;
        }
        boolean pokeballPressed = false;
        while (OPEN_POKEBALL_GUI.consumeClick()) {
            pokeballPressed = true;
        }
        boolean configPressed = false;
        while (OPEN_CONFIG_GUI.consumeClick()) {
            configPressed = true;
        }
        if (!plantPressed && !pokeballPressed && !configPressed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || context.baritone() == null) {
            return;
        }
        if (configPressed) {
            mc.setScreen(new ApricornConfigScreen(context));
        } else if (plantPressed && context.plant() != null) {
            mc.setScreen(new ApricornPlantScreen(context.baritone(), context.plant()));
        } else if (pokeballPressed && context.pokeball() != null) {
            mc.setScreen(new PokeballScreen(context.pokeball()));
        }
    }
}
