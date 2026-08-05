package com.brianthemint.apricornharvester;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Persists Baritone selection corners ({@code pos1}/{@code pos2}) to files under
 * {@code .minecraft/baritone/selections/}.
 */
public final class SelectionStorage {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private SelectionStorage() {
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public static Path selectionsDir(Path gameDirectory) {
        return gameDirectory.resolve("baritone").resolve("selections");
    }

    public static Path selectionFile(Path gameDirectory, String name) {
        return selectionsDir(gameDirectory).resolve(name.toLowerCase(Locale.ROOT) + ".sel");
    }

    public static void save(Path gameDirectory, String name, BlockPos pos1, BlockPos pos2) throws IOException {
        Path dir = selectionsDir(gameDirectory);
        Files.createDirectories(dir);
        Path file = selectionFile(gameDirectory, name);
        String contents = pos1.getX() + "," + pos1.getY() + "," + pos1.getZ() + System.lineSeparator()
                + pos2.getX() + "," + pos2.getY() + "," + pos2.getZ() + System.lineSeparator();
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    /** Removes a saved selection. Returns true when there was one to remove. */
    public static boolean delete(Path gameDirectory, String name) {
        try {
            return Files.deleteIfExists(selectionFile(gameDirectory, name));
        } catch (IOException e) {
            return false;
        }
    }

    public static Optional<SavedSelection> load(Path gameDirectory, String name) throws IOException {
        Path file = selectionFile(gameDirectory, name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (lines.size() < 2) {
            throw new IOException("Selection file must contain pos1 and pos2 lines");
        }
        BetterBlockPos pos1 = parsePos(lines.get(0));
        BetterBlockPos pos2 = parsePos(lines.get(1));
        return Optional.of(new SavedSelection(name, pos1, pos2));
    }

    public static List<String> listNames(Path gameDirectory) throws IOException {
        Path dir = selectionsDir(gameDirectory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".sel"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        names.add(fileName.substring(0, fileName.length() - 4));
                    });
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static BetterBlockPos parsePos(String line) throws IOException {
        String[] parts = line.split(",");
        if (parts.length != 3) {
            throw new IOException("Expected x,y,z but got '" + line + "'");
        }
        try {
            return new BetterBlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            );
        } catch (NumberFormatException e) {
            throw new IOException("Invalid coordinates in '" + line + "'", e);
        }
    }

    public record SavedSelection(String name, BetterBlockPos pos1, BetterBlockPos pos2) {
    }
}
