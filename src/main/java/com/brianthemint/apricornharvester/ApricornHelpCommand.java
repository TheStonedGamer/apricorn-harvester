package com.brianthemint.apricornharvester;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #ah} command: the addon's own overview. Baritone's {@code #help} lists every command
 * (including this addon's) and {@code #help <command>} prints one command's full page; this one
 * gives the bird's-eye view of what the addon can do and the order things are normally done in.
 */
public class ApricornHelpCommand implements ICommand {

    @Override
    public void execute(String label, IArgConsumer args) {
        String topic = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
        if (topic.equals("gui") || topic.equals("screen")) {
            Minecraft.getInstance().setScreen(new ApricornHelpScreen());
            return;
        }
        switch (topic) {
            case "" -> overview();
            case "harvest" -> lines(HARVEST);
            case "farm", "farms", "map" -> lines(FARMS);
            case "ore", "mine" -> lines(ORE);
            case "hunt" -> lines(HUNT);
            case "plant", "planting" -> lines(PLANT);
            case "bonemeal", "grow" -> lines(BONEMEAL);
            case "pokeball", "balls", "factory" -> lines(POKEBALL);
            case "loc", "areas", "locations" -> lines(AREAS);
            case "keys", "hotkeys" -> lines(KEYS);
            case "workflow", "flow" -> lines(WORKFLOW);
            default -> {
                logDirect("Unknown topic '" + topic + "'.");
                overview();
            }
        }
    }

    private void overview() {
        logDirect("Apricorn Harvester - Baritone addon for Pixelmon apricorn farms.");
        logDirect("");
        logDirect("  #apricorn   harvest ripe apricorns in the selection");
        logDirect("  #plant      plant apricorns on a grid, one colour per row");
        logDirect("  #bonemeal   bone-meal every sapling until it grows");
        logDirect("  #pokeball   mine, smelt and craft Poke Balls end to end");
        logDirect("  #farm       survey a farm, then pick which one the jobs work on");
        logDirect("  #ore        mine one ore on its own");
        logDirect("  #hunt       find apricorn colours you do not have yet");
        logDirect("  #loc        saved area + travel command per task");
        logDirect("  #config     settings screen (also on a hotkey)");
        logDirect("  #save/#load save and reload selections by name");
        logDirect("");
        logDirect("Topics: #ah workflow | farms | harvest | plant | bonemeal | pokeball");
        logDirect("        #ah ore | hunt | loc | keys");
        logDirect("Full page for any command: #help <command>. GUI version: #ah gui");
    }

    private void lines(String[] block) {
        for (String line : block) {
            logDirect(line);
        }
    }

    static final String[] FARMS = {
            "#farm - survey a field once, then work on it by name.",
            "  #farm map myfarm     walk the current selection and record it",
            "  #farm select myfarm  work on that farm (also sets the selection)",
            "  #farm list | info | remap | delete | stop",
            "The client only sees loaded chunks, so a farm bigger than your render",
            "distance cannot be planned from where you stand. Mapping walks it once so",
            "harvesting covers the whole field, not just the visible part."
    };

    static final String[] WORKFLOW = {
            "Typical cycle:",
            "  1. #sel pos1 / #sel pos2 around the field, then #farm map myfarm",
            "  2. #loc harvest cmd home farm   (how to get back to it)",
            "  3. #plant       lay out the grid, one apricorn colour per row",
            "  4. #bonemeal    grow every sapling into a tree",
            "  5. #apricorn    harvest the ripe apricorns",
            "  6. #pokeball    turn them into Poke Balls (mines and smelts the rest)"
    };

    static final String[] HARVEST = {
            "#apricorn - harvest ripe apricorns inside the selection.",
            "  #apricorn               start",
            "  #apricorn stop|pause|resume",
            "  #apricorn tops true     also try apricorns high in the trees",
            "  #apricorn deposit true  put the harvest in a nearby chest afterwards",
            "  #apricorn chestradius 24  how far to look for that chest",
            "The bot walks the farm paths only and never breaks a block."
    };

    static final String[] PLANT = {
            "#plant - plant apricorns on a grid inside the selection.",
            "  #plant                  start planting",
            "  #plant gui              GUI: spacing, clearance, row direction, colours",
            "  #plant spacing 3        blocks between plants (both axes)",
            "  #plant clearance 2      free blocks kept from walls and the border",
            "  #plant rows x|z|flip    which way the rows run",
            "  #plant all <colour>     set every row to one colour",
            "  #plant row 2 blue       set one row's colour",
            "Colours: black, white, pink, green, blue, yellow, red."
    };

    static final String[] BONEMEAL = {
            "#bonemeal - bone-meal every apricorn sapling in the selection until it grows.",
            "  #bonemeal               start",
            "  #bonemeal stop|pause|resume",
            "Up to 32 bone meals per sapling; stops when you run out."
    };

    static final String[] POKEBALL = {
            "#pokeball - the full Poke Ball pipeline, planned from the server's own recipes.",
            "  #pokeball               start a run",
            "  #pokeball gui           GUI: ball, amount, fuel, farm area, commands",
            "  #pokeball plan          dry run - what it would mine, harvest, smelt, craft",
            "  #pokeball list          every ball recipe the server has",
            "  #pokeball ball great ball / #pokeball count 32",
            "  #pokeball fuel minecraft:coal / #pokeball radius 24",
            "It mines ores and stone, harvests apricorns from the farm, smelts at the",
            "nearest furnace and crafts at the nearest crafting table."
    };

    static final String[] ORE = {
            "#ore - mine one ore on its own, without a Poke Ball run.",
            "  #ore platinum 64     mine 64 platinum ore",
            "  #ore silver | bauxite | iron | gold | coal | diamond | stone",
            "  #ore stop | status | list",
            "Goes out with the mine travel command and comes back with the craft one."
    };

    static final String[] HUNT = {
            "#hunt - find wild apricorn trees, especially colours you have none of.",
            "  #hunt                every colour you are missing (10 hops)",
            "  #hunt red,blue 25    those colours, up to 25 hops",
            "  #hunt scan           what is around you right now",
            "  #hunt missing        which colours you have none of",
            "Hops use #loc hunt cmd (/rtp by default), then it paths to what it finds."
    };

    static final String[] AREAS = {
            "#loc - where each job happens: a saved selection plus a travel command.",
            "  #loc                    list every task",
            "  #loc saved              saved selections you can pick from",
            "  #loc harvest sel myfarm / #loc harvest cmd home farm",
            "  #loc harvest go         travel there and load the selection",
            "Tasks: harvest, plant, bonemeal, mine, craft.",
            "The Poke Ball factory uses mine, harvest and craft automatically."
    };

    static final String[] KEYS = {
            "Hotkeys (Options > Controls > Apricorn Harvester, rebindable):",
            "  G   open the settings screen (#config)",
            "  K   open the planting GUI",
            "  J   open the Poke Ball factory GUI",
            "Baritone's own #pause / #resume freeze and continue any of these jobs."
    };

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("gui", "workflow", "farm", "plant", "bonemeal", "pokeball", "loc", "keys")
                    .filterPrefix(args.peekString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Overview of every Apricorn Harvester command";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The ah command is the addon's own help. On its own it lists every command",
                "the addon adds; with a topic it prints that topic's cheat sheet.",
                "",
                "Usage:",
                "> #ah              overview of every command",
                "> #ah gui          the same thing as a screen",
                "> #ah workflow     the order a farm is normally set up and run in",
                "> #ah farms        surveying farms (#farm)",
                "> #ah harvest      harvesting (#apricorn)",
                "> #ah ore          mining one ore (#ore)",
                "> #ah hunt         finding new colours (#hunt)",
                "> #ah plant        planting (#plant)",
                "> #ah bonemeal     growing saplings (#bonemeal)",
                "> #ah pokeball     the Poke Ball factory (#pokeball)",
                "> #ah loc          task areas and travel commands (#loc)",
                "> #ah keys         hotkeys",
                "",
                "Baritone's #help lists all commands (this addon's included), and",
                "#help <command> prints one command's full page."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("ah", "apricornhelp");
    }
}
