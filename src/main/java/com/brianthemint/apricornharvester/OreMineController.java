package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mines one ore on its own, without a whole Poke Ball run: travel to the mining area, let
 * Baritone's miner fetch the requested amount, then come back.
 *
 * <p>Driven from the client tick like {@link com.brianthemint.apricornharvester.pokeball.PokeballFactory}
 * rather than being a Baritone process, because the mining itself is Baritone's own process - this
 * only starts it, watches the inventory and stops it again.
 */
public final class OreMineController {

    /** Ticks a run may spend mining before it gives up. */
    private static final int MINE_TIMEOUT = 20 * 60 * 30;

    /** The ores worth a one-click button, with the item each one yields. */
    public enum Ore {
        PLATINUM("Platinum", "pixelmon:platinum_ore", "pixelmon:platinum_ore"),
        SILVER("Silver", "pixelmon:silver_ore", "pixelmon:silver_ore"),
        BAUXITE("Bauxite (aluminium)", "pixelmon:bauxite_ore", "pixelmon:bauxite_ore"),
        IRON("Iron", "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
        GOLD("Gold", "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
        COAL("Coal", "minecraft:coal", "minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
        DIAMOND("Diamond", "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
        STONE("Stone", "minecraft:cobblestone", "minecraft:stone");

        private final String label;
        private final String itemId;
        private final String[] blockIds;

        Ore(String label, String itemId, String... blockIds) {
            this.label = label;
            this.itemId = itemId;
            this.blockIds = blockIds;
        }

        public String label() {
            return label;
        }

        /** The item that lands in the inventory, which is what progress is measured on. */
        public Item item() {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            return item == null ? net.minecraft.world.item.Items.AIR : item;
        }

        /** Blocks Baritone should look for; ores that exist as deepslate too list both. */
        public String[] blocks() {
            return blockIds;
        }

        /** True when the server actually has this block, so absent mods stay out of the list. */
        public boolean exists() {
            for (String id : blockIds) {
                ResourceLocation key = ResourceLocation.tryParse(id);
                if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }

        public static Ore parse(String name) {
            if (name == null) {
                return null;
            }
            String n = name.toLowerCase(Locale.ROOT);
            for (Ore ore : values()) {
                if (ore.name().toLowerCase(Locale.ROOT).equals(n)
                        || ore.label.toLowerCase(Locale.ROOT).startsWith(n)) {
                    return ore;
                }
            }
            return null;
        }

        /** Only the ores this world knows about. */
        public static List<Ore> available() {
            List<Ore> list = new ArrayList<>();
            for (Ore ore : values()) {
                if (ore.exists()) {
                    list.add(ore);
                }
            }
            return list;
        }
    }

    private enum Stage { IDLE, TRAVEL_OUT, MINING, TRAVEL_BACK }

    private final IBaritone baritone;

    private Stage stage = Stage.IDLE;
    private Ore ore;
    private int wanted;
    private int baseline;
    private int ticks;

    public OreMineController(IBaritone baritone) {
        this.baritone = baritone;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return stage != Stage.IDLE;
    }

    public String status() {
        if (!isRunning()) {
            return "Idle";
        }
        return switch (stage) {
            case TRAVEL_OUT -> "Travelling to the mining area";
            case MINING -> "Mining " + ore.label() + " (" + gained() + "/" + wanted + ")";
            case TRAVEL_BACK -> "Coming home";
            default -> "Working";
        };
    }

    /** Starts a run for the given ore and amount. */
    public void start(Ore ore, int amount) {
        if (isRunning()) {
            logDirect("Already mining. Use #ore stop first.");
            return;
        }
        if (Minecraft.getInstance().player == null) {
            return;
        }
        this.ore = ore;
        this.wanted = Math.max(1, amount);
        this.baseline = count(ore.item());
        this.ticks = 0;
        this.stage = TaskLocations.getCommand(TaskLocations.Task.MINE).isEmpty()
                ? Stage.MINING : Stage.TRAVEL_OUT;
        logDirect("Mining " + wanted + "x " + ore.label() + "...");
        if (stage == Stage.MINING) {
            beginMining();
        }
    }

    public void stop() {
        if (!isRunning()) {
            logDirect("Not mining.");
            return;
        }
        finish("Mining stopped (" + gained() + "/" + wanted + " " + ore.label() + ").");
    }

    private void beginMining() {
        baseline = count(ore.item());
        ticks = 0;
        baritone.getMineProcess().mineByName(wanted, ore.blocks());
        stage = Stage.MINING;
    }

    private void finish(String message) {
        stage = Stage.IDLE;
        try {
            baritone.getMineProcess().cancel();
        } catch (Throwable ignored) {
            // Nothing to cancel if the miner never started.
        }
        logDirect(message);
    }

    private int gained() {
        return Math.max(0, count(ore.item()) - baseline);
    }

    private int count(Item item) {
        if (Minecraft.getInstance().player == null) {
            return 0;
        }
        var inv = Minecraft.getInstance().player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Called every client tick. */
    public void tick() {
        if (!isRunning() || Minecraft.getInstance().player == null) {
            return;
        }
        ticks++;
        switch (stage) {
            case TRAVEL_OUT -> {
                if (ticks == 1) {
                    TaskLocations.sendTravel(TaskLocations.Task.MINE);
                }
                if (ticks >= travelWait()) {
                    beginMining();
                }
            }
            case MINING -> {
                if (gained() >= wanted) {
                    baritone.getMineProcess().cancel();
                    logDirect("Mined " + gained() + "x " + ore.label() + ".");
                    if (TaskLocations.getCommand(TaskLocations.Task.CRAFT).isEmpty()) {
                        finish("Done.");
                    } else {
                        stage = Stage.TRAVEL_BACK;
                        ticks = 0;
                    }
                    return;
                }
                if (ticks > MINE_TIMEOUT) {
                    finish("Gave up mining " + ore.label() + " (" + gained() + "/" + wanted + ").");
                }
            }
            case TRAVEL_BACK -> {
                if (ticks == 1) {
                    TaskLocations.sendTravel(TaskLocations.Task.CRAFT);
                }
                if (ticks >= travelWait()) {
                    finish("Mining run complete.");
                }
            }
            default -> {
            }
        }
    }

    /** Ticks given to a teleport; shared with the Poke Ball factory's setting. */
    private static int travelWait() {
        return com.brianthemint.apricornharvester.pokeball.PokeballConfig.getTravelWaitTicks();
    }
}
