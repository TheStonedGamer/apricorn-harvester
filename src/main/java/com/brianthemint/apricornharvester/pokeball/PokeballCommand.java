package com.brianthemint.apricornharvester.pokeball;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #pokeball} command: the chat side of the Poke Ball factory. Everything here is also in
 * the addon window (the Poke Balls tab).
 */
public class PokeballCommand implements ICommand {

    private final PokeballFactory factory;

    public PokeballCommand(PokeballFactory factory) {
        this.factory = factory;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            factory.start();
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start":
            case "go":
                factory.start();
                return;
            case "stop":
            case "cancel":
                factory.stop();
                return;
            case "gui":
                com.brianthemint.apricornharvester.ApricornGui.open(com.brianthemint.apricornharvester.ApricornGui.Tab.POKEBALL);
                return;
            case "status":
                logDirect("Factory: " + factory.status());
                return;
            case "list": {
                List<RecipeHolder<?>> balls = PokeballRecipes.ballRecipes();
                if (balls.isEmpty()) {
                    logDirect("No Poke Ball recipes known (join a world first).");
                    return;
                }
                logDirect(balls.size() + " ball recipes:");
                for (RecipeHolder<?> ball : balls) {
                    logDirect("  " + PokeballRecipes.ballName(ball));
                }
                return;
            }
            case "ball": {
                if (!args.hasAny()) {
                    logDirect("Ball = " + currentBallName() + ".");
                    return;
                }
                String query = args.rawRest().trim();
                RecipeHolder<?> match = findBall(query);
                if (match == null) {
                    logDirect("No ball recipe matches '" + query + "'. Try #pokeball list.");
                    return;
                }
                PokeballConfig.setBallRecipeId(match.id().toString());
                logDirect("Ball = " + PokeballRecipes.ballName(match) + ".");
                return;
            }
            case "count": {
                if (!args.hasAny()) {
                    logDirect("Count = " + PokeballConfig.getCount() + ".");
                    return;
                }
                try {
                    PokeballConfig.setCount(Integer.parseInt(args.getString()));
                    logDirect("Count = " + PokeballConfig.getCount() + ".");
                } catch (NumberFormatException e) {
                    logDirect("Expected a number.");
                }
                return;
            }
            case "minecmd": {
                if (!args.hasAny()) {
                    logDirect("Go-mine command = /" + PokeballConfig.getMineCommand());
                    return;
                }
                PokeballConfig.setMineCommand(args.rawRest());
                logDirect("Go-mine command = /" + PokeballConfig.getMineCommand());
                return;
            }
            case "homecmd": {
                if (!args.hasAny()) {
                    logDirect("Go-home command = /" + PokeballConfig.getHomeCommand());
                    return;
                }
                PokeballConfig.setHomeCommand(args.rawRest());
                logDirect("Go-home command = /" + PokeballConfig.getHomeCommand());
                return;
            }
            case "fuel": {
                if (!args.hasAny()) {
                    logDirect("Fuel = " + PokeballRecipes.nameOf(PokeballConfig.getFuel()));
                    return;
                }
                Item item = PokeballRecipes.itemById(args.getString());
                if (item == null) {
                    logDirect("Unknown item. Use a registry id, e.g. minecraft:coal.");
                    return;
                }
                PokeballConfig.setFuel(item);
                logDirect("Fuel = " + PokeballRecipes.nameOf(item));
                return;
            }
            case "radius": {
                if (!args.hasAny()) {
                    logDirect("Station search radius = " + PokeballConfig.getStationRadius() + " blocks.");
                    return;
                }
                try {
                    PokeballConfig.setStationRadius(Integer.parseInt(args.getString()));
                    logDirect("Station search radius = " + PokeballConfig.getStationRadius() + " blocks.");
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of blocks.");
                }
                return;
            }
            case "plan": {
                RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
                if (ball == null || Minecraft.getInstance().player == null) {
                    logDirect("Pick a ball first: #pokeball ball <name>.");
                    return;
                }
                CraftPlan plan = PokeballRecipes.plan(ball, PokeballConfig.getCount(),
                        Minecraft.getInstance().player.getInventory());
                if (!plan.isPossible()) {
                    logDirect("Cannot make it: missing " + String.join(", ", plan.missing));
                    return;
                }
                if (plan.isEmpty()) {
                    logDirect("You already have everything needed.");
                    return;
                }
                logDirect("Plan for " + PokeballConfig.getCount() + "x " + PokeballRecipes.ballName(ball) + ":");
                for (CraftPlan.Step step : plan.steps) {
                    logDirect("  - " + step.describe());
                }
                return;
            }
            default:
                logDirect("Unknown argument '" + sub + "'. Usage: #pokeball "
                        + "[start|stop|gui|status|list|ball|count|minecmd|homecmd|fuel|radius|plan]");
        }
    }

    private String currentBallName() {
        RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        return ball == null ? "(none)" : PokeballRecipes.ballName(ball);
    }

    /** Finds a ball recipe by display name or recipe id, case-insensitive substring match. */
    private RecipeHolder<?> findBall(String query) {
        String q = query.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        RecipeHolder<?> partial = null;
        for (RecipeHolder<?> ball : PokeballRecipes.ballRecipes()) {
            String name = PokeballRecipes.ballName(ball).toLowerCase(Locale.ROOT);
            if (name.equals(q) || ball.id().toString().equalsIgnoreCase(query)) {
                return ball;
            }
            if (partial == null && (name.contains(q) || ball.id().getPath().contains(q.replace(' ', '_')))) {
                partial = ball;
            }
        }
        return partial;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "stop", "gui", "status", "list", "ball", "count",
                            "minecmd", "homecmd", "fuel", "radius", "plan")
                    .filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2) && args.peekString().equalsIgnoreCase("ball")) {
            TabCompleteHelper helper = new TabCompleteHelper();
            for (RecipeHolder<?> ball : PokeballRecipes.ballRecipes()) {
                helper.append(PokeballRecipes.ballName(ball).replace(' ', '_'));
            }
            return helper.filterPrefix(args.peekString(1)).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mine, smelt and craft Poke Balls end to end";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The pokeball command runs the whole Poke Ball pipeline: it reads the",
                "server's own recipes, works out what you are short of, mines the ores and",
                "stone, smelts them (and your apricorns) in a nearby furnace and crafts the",
                "bases, lids and balls at a nearby crafting table.",
                "",
                "Travel between the mining area and your base uses the server commands you",
                "configure - by default /rtp to go mining and /home home2 to come back.",
                "Leave a command empty to skip that teleport and work where you stand.",
                "",
                "Usage:",
                "> #pokeball                 start a run with the current settings",
                "> #pokeball gui             open the factory GUI (also on its own hotkey)",
                "> #pokeball stop            stop the run",
                "> #pokeball status          what the factory is doing right now",
                "> #pokeball list            every ball recipe the server has",
                "> #pokeball ball great ball select the ball to make",
                "> #pokeball count 32        how many to make",
                "> #pokeball plan            show what a run would mine, smelt and craft",
                "> #pokeball minecmd rtp     command that takes you to the mining area",
                "> #pokeball homecmd home home2  command that takes you back to base",
                "> #pokeball fuel minecraft:coal   furnace fuel to use",
                "> #pokeball radius 24       how far to look for furnaces/crafting tables",
                "",
                "Apricorns are not mined - harvest them from the farm with #apricorn first."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("pokeball", "pokeballs");
    }
}
