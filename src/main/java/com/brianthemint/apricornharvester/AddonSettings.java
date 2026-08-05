package com.brianthemint.apricornharvester;

import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every scalar setting of the addon in one place, persisted to
 * {@code .minecraft/baritone/apricorn-settings.properties} so a setup survives a restart.
 *
 * <p>The per-row apricorn colours stay in {@link PlantConfig} (they belong to a selection, not to
 * the addon), but the grid settings are mirrored here so they are saved too. Task areas and travel
 * commands have their own file, see {@link TaskLocations}.
 */
public final class AddonSettings {

    private static final String FILE_NAME = "apricorn-settings.properties";

    // -- harvesting
    private static boolean harvestTops;
    private static boolean harvestDeposit;
    /** How far from the selection the deposit pass looks for a chest, barrel or shulker box. */
    private static int chestRadius = 16;
    public static final int MIN_CHEST_RADIUS = 4;
    public static final int MAX_CHEST_RADIUS = 64;

    // -- bone meal
    /** Bone meal applications spent on one sapling before it is given up on. */
    private static int bonemealMax = 32;
    public static final int MIN_BONEMEAL_MAX = 1;
    public static final int MAX_BONEMEAL_MAX = 128;

    private static boolean loaded;

    private AddonSettings() {
    }

    // ---------------------------------------------------------------- accessors

    public static boolean isHarvestTops() {
        ensureLoaded();
        return harvestTops;
    }

    public static void setHarvestTops(boolean value) {
        ensureLoaded();
        harvestTops = value;
        save();
    }

    public static boolean isHarvestDeposit() {
        ensureLoaded();
        return harvestDeposit;
    }

    public static void setHarvestDeposit(boolean value) {
        ensureLoaded();
        harvestDeposit = value;
        save();
    }

    public static int getChestRadius() {
        ensureLoaded();
        return chestRadius;
    }

    public static void setChestRadius(int value) {
        ensureLoaded();
        chestRadius = Math.max(MIN_CHEST_RADIUS, Math.min(MAX_CHEST_RADIUS, value));
        save();
    }

    public static int getBonemealMax() {
        ensureLoaded();
        return bonemealMax;
    }

    public static void setBonemealMax(int value) {
        ensureLoaded();
        bonemealMax = Math.max(MIN_BONEMEAL_MAX, Math.min(MAX_BONEMEAL_MAX, value));
        save();
    }

    /** Called by {@link PlantConfig} whenever one of its grid settings changes. */
    public static void notifyPlantSettingsChanged() {
        ensureLoaded();
        save();
    }

    // ---------------------------------------------------------------- persistence

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                switch (key) {
                    case "harvest.tops" -> harvestTops = Boolean.parseBoolean(value);
                    case "harvest.deposit" -> harvestDeposit = Boolean.parseBoolean(value);
                    case "harvest.chestRadius" -> chestRadius = parseInt(value, chestRadius);
                    case "bonemeal.max" -> bonemealMax = parseInt(value, bonemealMax);
                    case "plant.spacing" -> PlantConfig.setSpacingQuiet(parseInt(value,
                            PlantConfig.getSpacing()));
                    case "plant.clearance" -> PlantConfig.setClearanceQuiet(parseInt(value,
                            PlantConfig.getClearance()));
                    case "plant.rowAxis" -> {
                        try {
                            PlantConfig.setRowAxisQuiet(PlantConfig.RowAxis.valueOf(value));
                        } catch (IllegalArgumentException ignored) {
                            // Unknown axis name: keep the default.
                        }
                    }
                    case "plant.defaultType" -> {
                        ApricornType type = ApricornPlanting.parse(value);
                        if (type != null) {
                            PlantConfig.setDefaultTypeQuiet(type);
                        }
                    }
                    default -> {
                        // Unknown keys are ignored so older/newer files stay readable.
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: defaults stay in place.
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# Apricorn Harvester settings. Task areas live in apricorn-tasks.properties.");
        lines.add("harvest.tops=" + harvestTops);
        lines.add("harvest.deposit=" + harvestDeposit);
        lines.add("harvest.chestRadius=" + chestRadius);
        lines.add("bonemeal.max=" + bonemealMax);
        lines.add("plant.spacing=" + PlantConfig.getSpacing());
        lines.add("plant.clearance=" + PlantConfig.getClearance());
        lines.add("plant.rowAxis=" + PlantConfig.getRowAxis().name());
        lines.add("plant.defaultType=" + PlantConfig.getDefaultType().name());
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort; the in-memory settings still work for this session.
        }
    }
}
