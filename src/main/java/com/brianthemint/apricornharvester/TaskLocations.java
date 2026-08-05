package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where each task happens: a saved Baritone selection (see {@code #save} / {@code #load}) and the
 * server command that takes the player there.
 *
 * <p>A farm normally has several work areas - the tree field to harvest, an empty plot to plant,
 * a nursery to bone-meal, a mining spot, the crafting base - each reached with its own
 * {@code /home}, {@code /warp} or {@code /rtp}. This class holds one selection name and one travel
 * command per task, persisted to {@code .minecraft/baritone/apricorn-tasks.properties} so the setup
 * survives a restart.
 */
public final class TaskLocations {

    /** The jobs that can have their own area and travel command. */
    public enum Task {
        HARVEST("harvest", "Apricorn harvesting"),
        PLANT("plant", "Planting"),
        BONEMEAL("bonemeal", "Bone-mealing"),
        MINE("mine", "Mining"),
        HUNT("hunt", "Apricorn hunting"),
        CRAFT("craft", "Crafting base");

        private final String key;
        private final String label;

        Task(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public static Task parse(String name) {
            if (name == null) {
                return null;
            }
            String n = name.toLowerCase(Locale.ROOT);
            for (Task task : values()) {
                if (task.key.equals(n) || task.name().toLowerCase(Locale.ROOT).equals(n)) {
                    return task;
                }
            }
            return null;
        }
    }

    private static final String FILE_NAME = "apricorn-tasks.properties";

    /** Saved selection name per task ("" = none). */
    private static final Map<Task, String> SELECTIONS = new EnumMap<>(Task.class);
    /** Travel command per task, without the leading slash ("" = do not teleport). */
    private static final Map<Task, String> COMMANDS = new EnumMap<>(Task.class);
    /** Seconds each task's command must be left alone between uses (server warp cooldowns). */
    private static final Map<Task, Integer> COOLDOWNS = new EnumMap<>(Task.class);
    /** When each command was last sent, so the cooldown can be honoured. */
    private static final Map<Task, Long> LAST_SENT = new EnumMap<>(Task.class);

    private static boolean loaded;

    static {
        for (Task task : Task.values()) {
            SELECTIONS.put(task, "");
            COMMANDS.put(task, "");
        }
        // Sensible starting point for the two the Poke Ball factory always uses.
        COMMANDS.put(Task.MINE, "rtp");
        COMMANDS.put(Task.HUNT, "rtp");
        COMMANDS.put(Task.CRAFT, "home home2");
        // /rtp is commonly on a five minute cooldown; /home usually is not.
        COOLDOWNS.put(Task.MINE, 300);
        COOLDOWNS.put(Task.HUNT, 300);
    }

    private TaskLocations() {
    }

    // ---------------------------------------------------------------- accessors

    public static String getSelection(Task task) {
        ensureLoaded();
        return SELECTIONS.getOrDefault(task, "");
    }

    public static void setSelection(Task task, String name) {
        ensureLoaded();
        SELECTIONS.put(task, name == null ? "" : name.trim());
        save();
    }

    public static String getCommand(Task task) {
        ensureLoaded();
        return COMMANDS.getOrDefault(task, "");
    }

    public static void setCommand(Task task, String command) {
        ensureLoaded();
        COMMANDS.put(task, normalize(command));
        save();
    }

    /** Strips leading slashes; the client adds one when sending. */
    private static String normalize(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    // ---------------------------------------------------------------- actions

    /** Every saved selection name on disk, for dropdowns and tab completion. */
    public static List<String> savedSelectionNames() {
        try {
            return SelectionStorage.listNames(Minecraft.getInstance().gameDirectory.toPath());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Loads the task's saved selection into Baritone, replacing the current one. Returns false
     * (with no side effects) when the task has no selection configured or the file is gone.
     */
    public static boolean applySelection(IBaritone baritone, Task task) {
        String name = getSelection(task);
        if (name.isEmpty() || baritone == null) {
            return false;
        }
        try {
            Optional<SelectionStorage.SavedSelection> saved =
                    SelectionStorage.load(Minecraft.getInstance().gameDirectory.toPath(), name);
            if (saved.isEmpty()) {
                return false;
            }
            BetterBlockPos pos1 = saved.get().pos1();
            BetterBlockPos pos2 = saved.get().pos2();
            baritone.getSelectionManager().removeAllSelections();
            baritone.getSelectionManager().addSelection(pos1, pos2);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Seconds a task's travel command must be left alone between uses.
     *
     * <p>Server warps are usually on a cooldown - {@code /rtp} is commonly five minutes - and
     * spamming one just earns a refusal in chat while the bot believes it has teleported. The
     * cooldown is remembered per task and honoured by {@link #sendTravel}.
     */
    public static int getCooldownSeconds(Task task) {
        ensureLoaded();
        return COOLDOWNS.getOrDefault(task, 0);
    }

    public static void setCooldownSeconds(Task task, int seconds) {
        ensureLoaded();
        COOLDOWNS.put(task, Math.max(0, Math.min(3600, seconds)));
        save();
    }

    /** Seconds still to wait before this task's command may be sent again; 0 when it is ready. */
    public static int secondsUntilReady(Task task) {
        Long last = LAST_SENT.get(task);
        if (last == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return (int) Math.max(0, getCooldownSeconds(task) - elapsed);
    }

    public static boolean isReady(Task task) {
        return secondsUntilReady(task) == 0;
    }

    /**
     * Sends the task's travel command, if it has one and its cooldown has passed.
     *
     * @return true when something was actually sent
     */
    public static boolean sendTravel(Task task) {
        String command = getCommand(task);
        if (command.isEmpty() || Minecraft.getInstance().player == null || !isReady(task)) {
            return false;
        }
        Minecraft.getInstance().player.connection.sendCommand(command);
        LAST_SENT.put(task, System.currentTimeMillis());
        return true;
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
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                int dot = key.indexOf('.');
                if (dot <= 0) {
                    continue;
                }
                Task task = Task.parse(key.substring(0, dot));
                if (task == null) {
                    continue;
                }
                if (key.endsWith(".selection")) {
                    SELECTIONS.put(task, value);
                } else if (key.endsWith(".command")) {
                    COMMANDS.put(task, normalize(value));
                } else if (key.endsWith(".cooldown")) {
                    try {
                        COOLDOWNS.put(task, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        // Keep the default cooldown for this task.
                    }
                }
            }
        } catch (IOException ignored) {
            // A malformed or unreadable file just means the defaults stay in place.
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# Apricorn Harvester task locations: saved selection + travel command per task.");
        for (Task task : Task.values()) {
            lines.add(task.key() + ".selection=" + SELECTIONS.getOrDefault(task, ""));
            lines.add(task.key() + ".command=" + COMMANDS.getOrDefault(task, ""));
            lines.add(task.key() + ".cooldown=" + COOLDOWNS.getOrDefault(task, 0));
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Persisting is best-effort; the in-memory settings still work for this session.
        }
    }
}
