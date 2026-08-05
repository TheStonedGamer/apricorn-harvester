package com.brianthemint.apricornharvester;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * How the bot looks after its tools: which ones it keeps, what they are made of, how worn they may
 * get, and whether two worn ones are combined before a new one is made.
 *
 * <p>Persisted to {@code .minecraft/baritone/apricorn-tools.properties}.
 */
public final class ToolConfig {

    private static final String FILE_NAME = "apricorn-tools.properties";

    /** The kinds of tool the upkeep module can look after. */
    public enum ToolKind {
        PICKAXE("Pickaxe", "pickaxe"),
        AXE("Axe", "axe"),
        SHOVEL("Shovel", "shovel"),
        SWORD("Sword", "sword");

        private final String label;
        private final String suffix;

        ToolKind(String label, String suffix) {
            this.label = label;
            this.suffix = suffix;
        }

        public String label() {
            return label;
        }

        /** The end of the registry name, e.g. {@code _pickaxe}. */
        public String suffix() {
            return "_" + suffix;
        }

        public static ToolKind parse(String name) {
            for (ToolKind kind : values()) {
                if (kind.suffix.equalsIgnoreCase(name) || kind.name().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Materials worth making a tool from, best first. */
    public enum Material {
        DIAMOND("Diamond", "minecraft:diamond"),
        IRON("Iron", "minecraft:iron"),
        STONE("Stone", "minecraft:stone"),
        GOLDEN("Gold", "minecraft:golden"),
        WOODEN("Wood", "minecraft:wooden");

        private final String label;
        private final String prefix;

        Material(String label, String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        public String label() {
            return label;
        }

        /** The registry id of this material's version of a tool, e.g. {@code minecraft:iron_axe}. */
        public String idFor(ToolKind kind) {
            return prefix + kind.suffix();
        }

        public static Material parse(String name) {
            for (Material material : values()) {
                if (material.name().equalsIgnoreCase(name) || material.label.equalsIgnoreCase(name)) {
                    return material;
                }
            }
            return null;
        }
    }

    private static final EnumSet<ToolKind> KEPT = EnumSet.of(ToolKind.PICKAXE);
    private static Material preferred = Material.IRON;
    /** Below this percentage of durability a tool counts as worn out. */
    private static int wornPercent = 15;
    /** Whether two worn tools of a kind are combined at a crafting table before making a new one. */
    private static boolean repairByCombining = true;
    /** How many spares of each kept tool to hold. */
    private static int spares = 1;

    private static boolean loaded;

    private ToolConfig() {
    }

    // ---------------------------------------------------------------- accessors

    public static boolean isKept(ToolKind kind) {
        ensureLoaded();
        return KEPT.contains(kind);
    }

    public static void setKept(ToolKind kind, boolean kept) {
        ensureLoaded();
        if (kept) {
            KEPT.add(kind);
        } else {
            KEPT.remove(kind);
        }
        save();
    }

    public static List<ToolKind> kept() {
        ensureLoaded();
        return new ArrayList<>(KEPT);
    }

    public static Material getPreferred() {
        ensureLoaded();
        return preferred;
    }

    public static void setPreferred(Material material) {
        ensureLoaded();
        preferred = material;
        save();
    }

    /**
     * The materials to try for a new tool: the preferred one first, then the cheaper ones, because
     * a stone pickaxe now beats an iron one that cannot be made.
     */
    public static List<Material> materialOrder() {
        ensureLoaded();
        List<Material> order = new ArrayList<>();
        order.add(preferred);
        for (Material material : Material.values()) {
            if (material != preferred && material.ordinal() > preferred.ordinal()) {
                order.add(material);
            }
        }
        for (Material material : Material.values()) {
            if (!order.contains(material)) {
                order.add(material);
            }
        }
        return order;
    }

    public static int getWornPercent() {
        ensureLoaded();
        return wornPercent;
    }

    public static void setWornPercent(int value) {
        ensureLoaded();
        wornPercent = Math.max(1, Math.min(90, value));
        save();
    }

    public static boolean isRepairByCombining() {
        ensureLoaded();
        return repairByCombining;
    }

    public static void setRepairByCombining(boolean value) {
        ensureLoaded();
        repairByCombining = value;
        save();
    }

    public static int getSpares() {
        ensureLoaded();
        return spares;
    }

    public static void setSpares(int value) {
        ensureLoaded();
        spares = Math.max(0, Math.min(9, value));
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
                    case "kept" -> {
                        KEPT.clear();
                        for (String name : value.split(",")) {
                            ToolKind kind = ToolKind.parse(name.trim());
                            if (kind != null) {
                                KEPT.add(kind);
                            }
                        }
                    }
                    case "material" -> {
                        Material material = Material.parse(value);
                        if (material != null) {
                            preferred = material;
                        }
                    }
                    case "wornPercent" -> wornPercent = parseInt(value, wornPercent);
                    case "spares" -> spares = parseInt(value, spares);
                    case "repairByCombining" -> repairByCombining = Boolean.parseBoolean(value);
                    default -> {
                        // Unknown keys are ignored so older files stay readable.
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: the defaults stand.
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
        lines.add("# Apricorn Harvester tool upkeep.");
        StringBuilder kept = new StringBuilder();
        for (ToolKind kind : KEPT) {
            if (kept.length() > 0) {
                kept.append(',');
            }
            kept.append(kind.name().toLowerCase(Locale.ROOT));
        }
        lines.add("kept=" + kept);
        lines.add("material=" + preferred.name());
        lines.add("wornPercent=" + wornPercent);
        lines.add("spares=" + spares);
        lines.add("repairByCombining=" + repairByCombining);
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
