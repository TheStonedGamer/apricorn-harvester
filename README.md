# Apricorn Harvester

A client-side **NeoForge 1.21.1** addon that teaches [Baritone](https://github.com/cabaletta/baritone)
to run a [Pixelmon](https://reforged.gg) apricorn farm: plant it, grow it, harvest it, and turn the
crop into Poke Balls.

Everything happens through the same selection you already use for Baritone
(`#sel pos1` / `#sel pos2`), the bot only ever **right-clicks** — `allowBreak` is forced off for
every job except mining, so no leaf, log or build of yours is ever broken.

| Job | Command | What it does |
| --- | --- | --- |
| Harvest | `#apricorn` | Patrols the farm paths, right-click-picks every ripe apricorn in reach, collects the drops, optionally deposits into a chest |
| Plant | `#plant` | Plants a grid with a configurable spacing, wall clearance and **one apricorn colour per row** |
| Grow | `#bonemeal` | Bone-meals every sapling in the selection until it becomes a tree |
| Craft | `#pokeball` | Full pipeline: mines the ore and stone, harvests apricorns, smelts, crafts bases, lids and balls |
| Map | `#farm` | Surveys a field once so runs can plan over the whole farm, not just the loaded part |
| Ore | `#ore` | Mines a single ore (platinum, silver, …) on its own, out and back |
| Hunt | `#hunt` | Finds wild apricorn trees, defaulting to the colours you have none of |
| Areas | `#loc` | Remembers a saved selection **and** a travel command (`/home`, `/warp`, `/rtp`) per job |
| Window | **G** | One tabbed window for every job and setting (also `#config`) |
| Help | `#ah` | The addon's own command overview, in chat or in the window |

> **Status:** the harvester and planter have been used in-game. The bone-mealer and the Poke Ball
> factory are newer and are **not yet runtime-tested** — start with `#pokeball plan`, which only
> prints what a run *would* do.

## Requirements

- Minecraft **1.21.1** + NeoForge **21.1.x**
- [Baritone](https://github.com/cabaletta/baritone/releases) NeoForge **v1.11.2**, the
  `baritone-api-neoforge-1.11.2.jar` (modid `baritoe`). The standalone jar is obfuscated and cannot
  be used by other mods, so the addon loads against the API jar.
- [Pixelmon](https://reforged.gg) **9.3.x** for NeoForge 1.21.1

Both are declared as required dependencies in `neoforge.mods.toml`, so NeoForge refuses to load the
addon without them. Drop all three jars into `mods/`.

## Install

1. Download `apricornharvester-1.0.0.jar` from the releases page (or build it, below).
2. Put it in `.minecraft/mods/` next to the Baritone API jar and Pixelmon.
3. Launch. **G** opens the addon window; `#ah` prints the command overview in chat.

## Quick start

```
#sel pos1                 stand at one corner of the field
#sel pos2                 ... and the opposite corner (ground level to tree top)
#farm map myfarm          walk the farm once and record it
#loc harvest cmd home farm   (optional) how to teleport back to it

#plant                    lay out the grid, one colour per row
#bonemeal                 grow every sapling into a tree
#apricorn                 harvest the ripe apricorns
#pokeball plan            see what it would take to craft balls
```

## Farms — `#farm`

Minecraft only gives the client blocks in **loaded chunks**, so a farm bigger than your render
distance cannot be planned from where you stand — the far half reads as empty air, and a harvest
quietly stops at the edge of what happened to be loaded.

`#farm map <name>` fixes that by surveying the field once: the bot walks a lawnmower route across the
selection so every chunk loads, recording the path stands, the trees and their colours, and the
containers. The map is saved to `.minecraft/baritone/apricorn-farms/` and reused, so later runs plan
over the whole farm without walking it again.

```
#farm                     list the farms you have surveyed
#farm map myfarm          survey the current selection under that name
#farm remap               survey the selected farm again
#farm select myfarm       work on that farm (also sets the selection)
#farm info [name]         what a survey found
#farm delete myfarm       forget a farm
#farm stop | status       stop a survey, or see how far it has got
```

Once a farm is selected, `#apricorn` seeds its plan from the map — stands in unloaded chunks are kept
and checked on arrival instead of being dropped — and the selection is set to the farm's bounds, so
the other commands need no `#sel` step. The **Farms** tab of the window lists every farm you have
surveyed with its stand and tree counts — click one to work on it, and the selected farm is lit — plus
Map selection / Re-map / Cancel.

## Harvesting — `#apricorn`

The bot **sweeps the farm paths**: it lists every column with a walkable, non-tree surface, keeps the
ones that can see a ripe apricorn, and walks them row by row like a lawnmower. At each stop it
right-clicks everything within block reach — whichever bush it belongs to — then collects what fell
before moving on. Apricorns no path can reach are left standing.

This suits the usual layout of 3×3×3 bushes with 1-wide paths between them, where a single stop
touches up to four bushes: each stand is visited once instead of once per neighbouring bush.

**Colour filter.** By default every colour is picked. Pick one, a set, or all — handy for topping up
a single colour without stripping the farm. Set it on the Harvest tab of the window (a
button per colour, plus All and Invert) or in chat. Colours the filter excludes are never clustered,
walked to or clicked, so the run only visits trees it actually wants.

```
#apricorn                 start
#apricorn stop|pause|resume
#apricorn tops true       after the patrol, also try high apricorns (towers up on dirt)
#apricorn deposit true    afterwards, find a container and deposit the crop
#apricorn chestradius 24  how far around the selection to look for that container (4-64)
#apricorn colours red     harvest only red apricorns
#apricorn colours red,blue   harvest only those colours
#apricorn colours all     harvest every colour (the default)
#apricorn colours         show the current filter
```

With `deposit true` the bot does not wait for the end of the run: **as soon as the inventory fills
up it breaks off, empties into the nearest container with free space, and resumes the sweep where it
left off**. If that container fills mid-deposit it moves on to the next one in range, remembering
which are full so it does not go back. Only when nothing in range has room does it say so once and
carry on without depositing.

Baritone's own `#pause` / `#resume` also freeze and continue a run.

## Planting — `#plant`

One plant every `spacing` blocks on both axes (default **3**, so two blocks of gap around each tree).
Rows run east–west or north–south, are numbered from the selection's minimum corner, and **each row
has its own apricorn colour**.

The grid keeps a **wall clearance** (default **2**, so the first plant sits on the 3rd block in from
a wall or the selection border), and any spot with a wall, fence, pillar or another tree inside that
radius is skipped — the trees need room to grow.

**Rows do not have to be perfectly straight.** With `snap 1` (the default) a plant may shift up to a
block off its grid cell to find usable soil, so a row that bends round a pond, sits a block off, or
runs over patchy ground still comes out complete instead of full of holes. Nudged plants still keep
their clearance and a sensible gap from their neighbours. `#plant snap 0` restores a strict grid.

Only free, plantable soil is used (grass, dirt, coarse/rooted dirt, podzol, mycelium, farmland, moss,
mud). The bot plants from the path beside each spot; it never stands on the block it is planting.
Apricorns are taken from the hotbar, or swapped in from the main inventory. When a colour runs out,
the rest of that colour's spots are skipped and reported.

```
#plant                    start planting
#plant gui                open the window on the Row colours tab
#plant spacing 3          grid spacing in blocks (1-16)
#plant clearance 2        blocks kept free from walls and the border (0-8)
#plant snap 1             how far a plant may shift off the grid to find soil (0-4)
#plant rows x|z|flip      which way the rows run
#plant all red            set every row of this selection to one colour
#plant row 2 blue         set one row's colour
#plant row                list the rows and their colours
```

In the planting screen, **left-click cycles a colour forwards, right-click backwards**.

## Growing — `#bonemeal`

Visits every apricorn sapling (`pixelmon:apricorn_plant_*`) in the selection from the path beside it
and applies bone meal until it becomes a tree — up to 32 applications per sapling by default,
configurable in the window. Bone meal comes from the hotbar or the main inventory; the run
ends as soon as you are out.

```
#bonemeal                 start
#bonemeal stop|pause|resume
```

## Poke Ball factory — `#pokeball`

Nothing about the recipes is hard-coded. The planner reads the **server's own recipe manager** and
walks the tree backwards from the ball you picked, so every ball type the server has a recipe for
appears in the dropdown with the base tier, lid and apricorn colours that recipe actually asks for.

In Pixelmon 9.3 the chain is plain vanilla crafting:

1. apricorn → furnace → cooked apricorn
2. 3 cooked apricorns (shaped `ABA`) → 3 lids, carrying the ball id as a data component
3. 3 ingots (e.g. `c:ingots/platinum`) → 5 bases
4. base + stone button + matching lid → 1 ball

Anything you are short of becomes a step: **craft** if a recipe makes it, else **smelt**, else
**harvest** (raw apricorns), else **mine**. So a platinum-based ball resolves all the way down to
`pixelmon:platinum_ore`, and a stone button down to smelting cobblestone.

Execution order: travel to the mining area → Baritone's miner gets the ores and stone → travel to the
farm → harvest apricorns → travel to the crafting base → furnaces → crafting table. Crafting goes
through the vanilla recipe-book placement call, which is what makes the data components on lids and
balls come out right.

**Smelting uses every furnace in range** (up to 12), not just the nearest — it is the slow part of a
run at 10 seconds an item, so a bank of furnaces divides the wait. The bot loads each one with fuel
and a share of the batch, then keeps walking the round, pulling what has finished and topping the
input back up; when a whole lap yields nothing it waits rather than pacing. Only ordinary furnaces
are used: a blast furnace would halve the time on ores but refuses apricorns, and one run smelts
both.

```
#pokeball                 start a run
#pokeball gui             open the window on the Poke Balls tab
#pokeball plan            dry run - what it would mine, harvest, smelt and craft
#pokeball list            every ball recipe the server has
#pokeball ball great ball / #pokeball count 32
#pokeball fuel minecraft:coal / #pokeball radius 24
#pokeball status | stop
```

`#pokeball plan` is read-only — run it first.

## Task areas — `#loc`

A farm usually has several work areas reached by different commands. `#loc` ties each job to a saved
selection (`#save <name>`) and the server command that gets there.

```
#loc                        list every task's area and command
#loc saved                  the saved selections you can pick from
#loc harvest sel treefarm   harvest that saved selection
#loc harvest cmd home farm  how to get to the tree farm
#loc harvest go             run the command and load the selection
#loc mine cmd rtp           how the factory reaches its mining area
#loc craft cmd home home2   how it gets back to the furnaces and table
```

Tasks: `harvest`, `plant`, `bonemeal`, `mine`, `craft`. The factory uses `mine`, `harvest` and
`craft` automatically. Leave a command empty to skip that teleport and work where you stand.

## The window

Everything lives in one window on one key: **G**, or `#config`. Rebind it under
**Options → Controls → Apricorn Harvester**.

| Tab | What it holds |
| --- | --- |
| **Farms** | Every surveyed farm listed with its size — click one to work on it; Map / Re-map |
| **Harvest** | Tops, deposit, chest search radius, colour filter; Run |
| **Planting** | Spacing, wall clearance, row snap, row direction, default colour; Run |
| **Row colours** | One row per line — left click for the next colour, right click for the previous |
| **Bone meal** | Applications per sapling; Run |
| **Poke Balls** | Ball, amount, fuel, station radius, live plan preview; Run |
| **Mine & hunt** | Mine one ore, or hunt the colours you are missing |
| **Task areas** | Saved area and travel command per task, each with a Go button |
| **Help** | The same cheat sheets as `#ah` |

Each job tab carries its own **Run** / **Cancel**; Run loads that task's saved area first, closes the
window and refuses to start while another job is running. The title bar shows what is running. The
window reopens on the tab you last used, and `#plant gui`, `#pokeball gui` and `#ah gui` open it
directly on theirs. Settings save as you change them.

Files written under `.minecraft/baritone/`:

- `apricorn-settings.properties` — the scalar settings
- `apricorn-tasks.properties` — task areas and travel commands
- `selections/<name>.sel` — saved selections (`#save` / `#load`)

## How apricorn detection works

Through the official [Pixelmon 9.3.x API](https://reforged.gg/docs/1211/), never by block id:

- `ApricornLeavesBlock` extends vanilla `LeavesBlock`; `ApricornLeavesBlock.AGE` is a static
  `IntegerProperty`. Growth goes bare leaf → flower → apricorn, and a block is ripe when its age is
  the property's maximum.
- Harvesting right-clicks the leaf (the block overrides `useWithoutItem`), simulated with Baritone's
  `processRightClickBlock`.
- Planting and colour handling go through `ApricornType`, which exposes the item, the sapling block
  and the log/leaves per colour.

## Building

Third-party jars are **not** in this repository. Create `libs/` and put in it:

- `baritone-api-neoforge-1.11.2.jar` — from [Baritone releases](https://github.com/cabaletta/baritone/releases)
- `Pixelmon-1.21.1-9.3.1-universal.jar` — from [reforged.gg](https://reforged.gg) (optional; Gradle
  can also pull it from the official ivy repo used by the
  [Pixelmon MDK](https://github.com/EnvyWare/Pixelmon-MDK))

Then:

```sh
./gradlew build          # jar in build/libs/
powershell -File build-jar.ps1   # same, plus a copy in dist/
```

Pixelmon is a `compileOnly` dependency and is never bundled into the output jar.

## Project layout

```
src/main/java/com/brianthemint/apricornharvester/
  ApricornHarvesterMod.java      @Mod entry point; hooks Baritone on the first client tick
  ApricornBlocks.java            apricorn/maturity detection, tree-block and container tests
  ApricornPlanting.java          item/sapling lookup via ApricornType, plantable-soil test
  ApricornHarvestProcess.java    harvesting: path-only patrol, click, pickup, deposit
  ApricornPlantProcess.java      planting: grid walk, equip, right-click, verify
  ApricornBonemealProcess.java   bone-mealing every sapling until it grows
  Apricorn*Command.java          the #apricorn, #plant, #bonemeal, #config, #ah commands
  ApricornGui.java               the single tabbed window: every job and setting
  PlantConfig.java               grid settings and per-row colours
  AddonSettings.java             every scalar setting, persisted
  TaskLocations.java             saved area + travel command per task
  SelectionStorage.java          .sel files behind #save / #load
  ApricornKeybinds.java          the one key binding, and the tick controllers
  AddonContext.java              Baritone plus every job, shared by the window and commands
  FarmMap / FarmMapper / FarmSelection.java   surveying a farm and working on it by name
  OreMineController.java         #ore, standalone ore mining
  ApricornHunter.java            #hunt, finding colours you do not have
  ui/FlatUI.java                 flat widget kit: button, toggle, stepper, dropdown
  pokeball/
    PokeballRecipes.java         reads the ball recipe tree, plans a run
    CraftPlan.java               the mine/harvest/smelt/craft step list
    PokeballFactory.java         the executor: travel, mining, furnaces, crafting
    PokeballConfig.java          ball, count, fuel, search radius

    PokeballCommand.java         the #pokeball command
```

## Credits

- [Baritone](https://github.com/cabaletta/baritone) — the pathfinder everything here builds on
- [Pixelmon](https://reforged.gg) — apricorns, Poke Balls and the API this reads them through

This addon is not affiliated with either project, nor with Nintendo, Game Freak or The Pokémon
Company.
