package com.brianthemint.apricornharvester.pokeball;

import com.brianthemint.apricornharvester.TaskLocations;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Settings for the Poke Ball factory: what to make, how much, and the server commands used to get
 * to the mining area and back home. Shared by the GUI ({@link PokeballScreen}) and the
 * {@code #pokeball} command; the factory reads it when a run starts.
 */
public final class PokeballConfig {

    /** Recipe id of the ball to make, e.g. {@code pixelmon:pokeball/ball/great_ball}. */
    private static String ballRecipeId = "";
    private static int count = 16;
    /** Item burned in the furnaces. */
    private static Item fuel = Items.COAL;
    /** Blocks searched around the player for furnaces and crafting tables. */
    private static int stationRadius = 24;
    /** Ticks waited after a travel command before the bot assumes the teleport has happened. */
    private static int travelWaitTicks = 100;

    private PokeballConfig() {
    }

    public static String getBallRecipeId() {
        return ballRecipeId;
    }

    public static void setBallRecipeId(String id) {
        ballRecipeId = id == null ? "" : id;
    }

    public static int getCount() {
        return count;
    }

    public static void setCount(int value) {
        count = Math.max(1, Math.min(2304, value));
    }

    /**
     * Travel commands live in {@link TaskLocations} so every task shares one setup; these are
     * thin views onto the mining and crafting-base entries.
     */
    public static String getMineCommand() {
        return TaskLocations.getCommand(TaskLocations.Task.MINE);
    }

    public static void setMineCommand(String command) {
        TaskLocations.setCommand(TaskLocations.Task.MINE, command);
    }

    public static String getHomeCommand() {
        return TaskLocations.getCommand(TaskLocations.Task.CRAFT);
    }

    public static void setHomeCommand(String command) {
        TaskLocations.setCommand(TaskLocations.Task.CRAFT, command);
    }

    public static Item getFuel() {
        return fuel;
    }

    public static void setFuel(Item item) {
        if (item != null) {
            fuel = item;
        }
    }

    public static int getStationRadius() {
        return stationRadius;
    }

    public static void setStationRadius(int value) {
        stationRadius = Math.max(4, Math.min(64, value));
    }

    public static int getTravelWaitTicks() {
        return travelWaitTicks;
    }

    public static void setTravelWaitTicks(int value) {
        travelWaitTicks = Math.max(0, Math.min(600, value));
    }

}
