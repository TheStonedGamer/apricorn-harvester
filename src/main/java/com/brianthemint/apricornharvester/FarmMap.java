package com.brianthemint.apricornharvester;

import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A surveyed farm: its bounds, the path stands the bot may work from, where the apricorn trees and
 * containers are, and what colours it grows.
 *
 * <p>The client can only read blocks in chunks it has loaded, so a farm larger than the render
 * distance is invisible to a plain scan - which is why harvesting a big field used to stop at the
 * edge of what happened to be loaded. {@link FarmMapper} walks the field once and records what it
 * sees; the map is then kept on disk and reused, so later runs can plan over the whole farm without
 * walking it again.
 */
public final class FarmMap {

    private static final String DIR = "apricorn-farms";

    public final String name;
    public BlockPos min;
    public BlockPos max;
    /** Feet positions of the farm's walkable paths. */
    public final Set<BlockPos> stands = new LinkedHashSet<>();
    /** Apricorn leaf positions seen while mapping, whatever their growth stage. */
    public final Set<BlockPos> trees = new LinkedHashSet<>();
    /** Chests, barrels and shulker boxes in and around the farm. */
    public final Set<BlockPos> containers = new LinkedHashSet<>();
    /** How many apricorn leaf blocks of each colour the farm has. */
    public final Map<ApricornType, Integer> colours = new EnumMap<>(ApricornType.class);
    /** World time when the survey finished; 0 while it has never been mapped. */
    public long mappedAt;

    public FarmMap(String name, BlockPos min, BlockPos max) {
        this.name = name;
        this.min = min;
        this.max = max;
    }

    public boolean isMapped() {
        return mappedAt > 0;
    }

    /** One-line summary for chat and the GUI. */
    public String summary() {
        if (!isMapped()) {
            return name + ": not mapped yet";
        }
        StringBuilder colourText = new StringBuilder();
        for (Map.Entry<ApricornType, Integer> entry : colours.entrySet()) {
            if (colourText.length() > 0) {
                colourText.append(", ");
            }
            colourText.append(ApricornPlanting.displayName(entry.getKey()))
                    .append(" ").append(entry.getValue());
        }
        return name + ": " + stands.size() + " stands, " + trees.size() + " tree blocks, "
                + containers.size() + " containers"
                + (colourText.length() == 0 ? "" : " [" + colourText + "]");
    }

    // ---------------------------------------------------------------- storage

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve(DIR);
    }

    private static Path file(String name) {
        return dir().resolve(name.toLowerCase(Locale.ROOT) + ".farm");
    }

    /** Every farm name on disk. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".farm")).forEach(p -> {
                String file = p.getFileName().toString();
                names.add(file.substring(0, file.length() - 5));
            });
        } catch (IOException ignored) {
            // An unreadable directory just means no farms are offered.
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    /** Writes the map. Positions are stored one per line, which keeps the format trivial. */
    public void save() {
        List<String> lines = new ArrayList<>();
        lines.add("name=" + name);
        lines.add("min=" + min.getX() + "," + min.getY() + "," + min.getZ());
        lines.add("max=" + max.getX() + "," + max.getY() + "," + max.getZ());
        lines.add("mappedAt=" + mappedAt);
        for (Map.Entry<ApricornType, Integer> entry : colours.entrySet()) {
            lines.add("colour=" + entry.getKey().name() + "," + entry.getValue());
        }
        for (BlockPos pos : stands) {
            lines.add("stand=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        for (BlockPos pos : trees) {
            lines.add("tree=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        for (BlockPos pos : containers) {
            lines.add("container=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        try {
            Files.createDirectories(dir());
            Files.write(file(name), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            baritone.api.utils.Helper.HELPER.logDirect("Could not save the farm map: " + e);
        }
    }

    /** Reads a map, or null when there is none by that name. */
    public static FarmMap load(String name) {
        Path path = file(name);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            BlockPos min = BlockPos.ZERO;
            BlockPos max = BlockPos.ZERO;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("min=")) {
                    min = parsePos(line.substring(4));
                } else if (line.startsWith("max=")) {
                    max = parsePos(line.substring(4));
                }
            }
            FarmMap map = new FarmMap(name, min, max);
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                switch (key) {
                    case "mappedAt" -> map.mappedAt = Long.parseLong(value);
                    case "stand" -> map.stands.add(parsePos(value));
                    case "tree" -> map.trees.add(parsePos(value));
                    case "container" -> map.containers.add(parsePos(value));
                    case "colour" -> {
                        String[] parts = value.split(",");
                        ApricornType type = ApricornPlanting.parse(parts[0]);
                        if (type != null && parts.length > 1) {
                            map.colours.put(type, Integer.parseInt(parts[1]));
                        }
                    }
                    default -> {
                        // name/min/max already handled, anything else is ignored.
                    }
                }
            }
            return map;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(name));
        } catch (IOException e) {
            return false;
        }
    }

    private static BlockPos parsePos(String text) {
        String[] parts = text.split(",");
        return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }
}
