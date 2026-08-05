package com.brianthemint.apricornharvester;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import com.brianthemint.apricornharvester.pokeball.PokeballCommand;
import com.brianthemint.apricornharvester.pokeball.PokeballFactory;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point for the Apricorn Harvester addon.
 *
 * <p>Client-side only. Baritone may not be ready during mod construction, so the Baritone
 * hookup is attempted lazily from the client tick until it succeeds (the {@code neoforge.mods.toml}
 * declares Baritone as a required dependency, so in practice this happens on the first tick).
 */
@Mod(ApricornHarvesterMod.MODID)
public final class ApricornHarvesterMod {

    public static final String MODID = "apricornharvester";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;

    public ApricornHarvesterMod(IEventBus modEventBus, ModContainer modContainer) {
        // The planting GUI's key binding must be registered during startup, before the first tick.
        ApricornKeybinds.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tryInitialize());
    }

    private static void tryInitialize() {
        if (initialized) {
            return;
        }
        try {
            IBaritoneProvider provider = BaritoneAPI.getProvider();
            if (provider == null) {
                return;
            }
            IBaritone baritone = provider.getPrimaryBaritone();
            if (baritone == null) {
                return;
            }
            ApricornHarvestProcess process = new ApricornHarvestProcess(baritone);
            baritone.getPathingControlManager().registerProcess(process);
            ApricornPlantProcess plantProcess = new ApricornPlantProcess(baritone);
            baritone.getPathingControlManager().registerProcess(plantProcess);
            ApricornBonemealProcess bonemealProcess = new ApricornBonemealProcess(baritone);
            baritone.getPathingControlManager().registerProcess(bonemealProcess);
            PokeballFactory pokeballFactory = new PokeballFactory(baritone, process);
            AddonContext context = new AddonContext(baritone, process, plantProcess,
                    bonemealProcess, pokeballFactory);
            ApricornKeybinds.setContext(context);
            baritone.getCommandManager().getRegistry().register(new ApricornHarvestCommand(process));
            baritone.getCommandManager().getRegistry().register(new ApricornPlantCommand(baritone, plantProcess));
            baritone.getCommandManager().getRegistry().register(new ApricornBonemealCommand(bonemealProcess));
            baritone.getCommandManager().getRegistry().register(new PokeballCommand(pokeballFactory));
            baritone.getCommandManager().getRegistry().register(new TaskLocationCommand(baritone));
            baritone.getCommandManager().getRegistry().register(new ApricornHelpCommand());
            baritone.getCommandManager().getRegistry().register(new ApricornConfigCommand(context));
            baritone.getCommandManager().getRegistry().register(new SelectionSaveCommand(baritone));
            baritone.getCommandManager().getRegistry().register(new SelectionLoadCommand(baritone));
            initialized = true;
            LOGGER.info("[ApricornHarvester] Hooked into Baritone. Type #ah for the command overview.");
        } catch (Throwable t) {
            LOGGER.error("[ApricornHarvester] Baritone is not available; addon inactive.", t);
        }
    }
}
