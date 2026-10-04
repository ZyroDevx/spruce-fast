package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cycle: /orders (buy 576 spruce logs @ 53 each) -> wait -> collect
 *        -> PLANKS : player inventory (E, 2x2 grid) + Recipe Book: logs -> planks (shift-click result)
 *        -> walk onto the DEEPSLATE block -> crafting table + Recipe Book: planks -> slabs,
 *           slabs are dropped DIRECTLY from the result slot (Ctrl+Q) onto the COBBLESTONE area
 *        -> (planks/slabs repeat while logs are left and the inventory has to be emptied in between)
 *        -> walk onto the COBBLESTONE block -> /sell until no slabs are left
 *        -> walk back onto the deepslate block -> repeat.
 *
 * Farm layout (found automatically within LAYOUT_RADIUS blocks):
 *   crafting table  - nearest one to the player when the mod is started
 *   deepslate block - the one nearest to the table  (player stands here to craft + throw)
 *   cobblestone     - the one nearest to the table  (slabs land here, player stands here to sell)
 *
 * BACKSPACE = start / stop.
 *
 * RECIPE BOOK NOTES
 *   "Selecting a recipe in the recipe book" is, in vanilla, exactly one call:
 *   ClientPlayerInteractionManager.clickRecipe(syncId, recipeId, craftAll), which sends a
 *   CraftRequestC2SPacket. The SERVER then moves the ingredients from the inventory into the grid.
 *   Whether the recipe book panel is open or closed on screen is purely cosmetic and has no
 *   influence on that packet, so nothing has to be toggled. The recipe id is looked up in the
 *   player's real (client) recipe book, so only recipes that are really unlocked can be used.
 *   Slabs (3 planks in a row) do not fit the 2x2 inventory grid, which is why they still need the
 *   real crafting table.
 */
public class SpruceFastClient implements ClientModInitializer {

    /* ================= CONFIG ================= */

    private static final String ORDER_COMMAND = "orders";   // no slash
    private static final String SELL_COMMAND  = "sell";     // no slash
    private static final String ORDER_ITEM_NAME = "Spruce logs"; // text typed in the search box

    private static final int TARGET_LOGS   = 576;   // 9 stacks
    private static final int PRICE_PER_LOG = 53;
    private static final int POLL_TICKS    = 30;    // retry collecting every 1.5 s (orders fill instantly)
    private static final int REQUIRED_FREE_SLOTS = 34; // 576 logs -> 2304 planks = 36 stacks
    private static final boolean LOOP      = true;  // repeat the whole cycle
    private static final boolean USE_RECIPE_BOOK = true; // fill the grid with ONE recipe-book click (falls back to manual loading per item)
    private static final boolean DIRECT_LOAD = true; // (manual fallback only) stacks go straight from the inventory into the grid
    private static final boolean DEBUG     = true;  // dumps every GUI to logs/latest.log

    /* ---------------- DISCORD PROFIT WEBHOOK ---------------- */
    // Create a Discord webhook in your channel and paste its URL here.
    // No Python, no API key, and no Discord bot are required.
    private static final String DISCORD_WEBHOOK_URL = "https://discord.com/api/webhooks/1546568205777113131/WgJwZZFq-L-rzGSqiltKkxZXfuxId2zw_IPFKWyb3EA8GJKBH2-f-BjNcmGwpolbtmNK";
    private static final int ORDER_COST = TARGET_LOGS * PRICE_PER_LOG; // $30,528
    private static final HttpClient PROFIT_HTTP = HttpClient.newHttpClient();

    private static int sellBatchSlabs = 0;
    private static boolean sellPayoutReceived = false;
    private static double sellRevenue = 0.0;
    private static int completedOrders = 0;
    private static long profitStartTick = -1;
    private static long lastDiscordUpdateTick = -999999;
    private static String discordMessageId = null;


    /* ---------------- FARM LAYOUT ---------------- */
    private static final Block STAND_BLOCK = Blocks.DEEPSLATE;    // stand here to use the crafting table + throw slabs
    private static final Block DROP_BLOCK  = Blocks.COBBLESTONE;  // slabs are thrown here; stand here to sell
    private static final int    LAYOUT_RADIUS = 16;   // search radius (blocks) for table / deepslate / cobblestone
    private static final double TABLE_REACH   = 4.4;  // max eye-to-table distance when standing on the deepslate
    private static final float  THROW_PITCH_TWEAK = 0.0f; // add degrees if slabs land short (-) or long (+)... see notes

    /* ---------------- TIMING (ticks) ---------------- */
    private static final int OPEN_WAIT     = 4;  // wait after the crafting table opens before the first click
    private static final int INV_OPEN_WAIT = 2;  // wait after opening the player inventory before the first click
    private static final int CLICK_GAP     = 2;  // ticks between queued inventory clicks (manual fallback)
    private static final int SETTLE        = 5;  // wait after the last manual-fallback click
    private static final int DIALOG_SETTLE = 2;  // a fresh dialog must exist this long before we click
    private static final int PRESS_RETRY   = 15; // ticks before re-clicking the same dialog

    /* ---------------- RECIPE-BOOK CRAFTING (ticks) ---------------- */
    // These are only MINIMUM waits. After that the mod polls the grid every tick and continues
    // the moment the server's answer is visible; the TIMEOUTs only matter if the server is stuck.
    private static final int FILL_MIN_WAIT  = 1;
    private static final int FILL_TIMEOUT   = 30;  // 1.5 s without the grid being filled = fill failed
    private static final int CRAFT_MIN_WAIT = 1;
    private static final int CRAFT_TIMEOUT  = 30;  // 1.5 s without the grid being used up = crafting stalled
    private static final int RECIPE_WAIT    = 40;  // how long to wait for a recipe to be unlocked in the book
    private static final int PLANKS_MIN_FREE = 4;  // free slots needed before turning another log stack into planks
    private static final int MAX_STAGE_ROUNDS = 12; // safety net against planks <-> slabs ping-pong

    /* ========================================== */

    private static final Logger LOG = LoggerFactory.getLogger("SpruceFast");

    private enum Phase {
        IDLE,
        ORDER_CMD, ORDER_GUI, ORDER_WAIT,
        COLLECT_CMD, COLLECT_GUI,
        PLANKS,
        TABLE_OPEN, TABLE,
        GO_DROP, PICKUP_WAIT,
        SELL_CMD, SELL_GUI
    }

    private enum CraftState { WORKING, DONE, FAILED }

    /** Describes one crafting stage (what goes in, what comes out, where the slots are). */
    private static final class Spec {
        final String name;
        final Item input;
        final Item output;
        final int gridFrom, gridTo;               // crafting grid slots [from, to)
        final int invFrom, hotbarFrom, invTo;     // main inventory [invFrom, hotbarFrom), hotbar [hotbarFrom, invTo)
        final int minItems;                       // minimum amount of input the recipe book needs
        final int[] manualCells;                  // grid cells used by the manual fallback (one stack each)
        final boolean dropOutput;                 // true: Ctrl+Q the result (slabs), false: shift-click it (planks)

        Spec(String name, Item input, Item output, int gridFrom, int gridTo,
             int invFrom, int hotbarFrom, int invTo, int minItems, int[] manualCells, boolean dropOutput) {
            this.name = name;
            this.input = input;
            this.output = output;
            this.gridFrom = gridFrom;
            this.gridTo = gridTo;
            this.invFrom = invFrom;
            this.hotbarFrom = hotbarFrom;
            this.invTo = invTo;
            this.minItems = minItems;
            this.manualCells = manualCells;
            this.dropOutput = dropOutput;
        }
    }

    /*
     * PlayerScreenHandler:   0 = result, 1-4 = grid, 5-8 armor, 9-35 inventory, 36-44 hotbar, 45 offhand
     * CraftingScreenHandler: 0 = result, 1-9 = grid, 10-36 inventory, 37-45 hotbar
     */
    private static final Spec SPEC_PLANKS = new Spec("planks",
        Items.SPRUCE_LOG, Items.SPRUCE_PLANKS, 1, 5, 9, 36, 45, 1, new int[] {1}, false);

    private static final Spec SPEC_SLABS = new Spec("slabs",
        Items.SPRUCE_PLANKS, Items.SPRUCE_SLAB, 1, 10, 10, 37, 46, 3, new int[] {7, 8, 9}, true);

    private static final int T_INV_FROM = 10;
    private static final int T_INV_TO   = 46;

    private static final int PENDING_NONE  = 0;
    private static final int PENDING_FILL  = 1;
    private static final int PENDING_CRAFT = 2;

    private static KeyBinding startKey;

    private static Phase phase = Phase.IDLE;
    private static int cooldown = 0;
    private static int phaseTicks = 0;
    private static int tickCounter = 0;

    private static String pendingChat = null;

    private static volatile boolean orderFilled = false;

    private static int collectedTotal = 0;
    private static int logsAtCollectStart = 0;

    private static int orderStep = 0;
    private static boolean movedAny = false;
    private static Object dialogScreen = null;
    private static int dialogSince = 0;
    private static int lastPressTick = -1000;
    private static Object lastWidgetScreen = null;
    private static boolean movedPlanks = false;
    private static boolean confirmedSell = false;

    private static int tableRetries = 0;
    private static int tableAimStage = 0;
    private static boolean droppedAny = false;
    private static int tableSeenSync = -1;
    private static int tableReadyAt = 0;
    private static int craftReadyAt = 0;
    private static int tableCloses = 0;
    private static int foreignCount = 0;
    private static int stageRounds = 0;
    private static final ArrayDeque<Runnable> clickQueue = new ArrayDeque<>();
    private static int pickupLastCount = -1;
    private static int pickupStable = 0;

    // recipe-book crafting state (reset for every crafting screen)
    private static int pendingKind = PENDING_NONE;
    private static int pendingSince = 0;
    private static int gridBeforeCraft = -1;
    private static int fillFails = 0;
    private static int craftStalls = 0;
    private static int stableTicks = 0;
    private static int recipeMissSince = -1;
    private static String failReason = "";
    private static final Map<Item, NetworkRecipeId> recipeCache = new HashMap<>();
    private static final Set<Item> recipeFailed = new HashSet<>(); // items whose recipe-book fill does not work here

    // farm layout (resolved in start())
    private static BlockPos tablePos = null;
    private static BlockPos standPos = null;
    private static BlockPos dropPos  = null;
    private static float throwYaw = 0f;
    private static float throwPitch = 0f;

    private static Object lastScreen = null;
    private static String lastClickKey = "";

    @Override
    public void onInitializeClient() {

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.spruce_fast.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                KeyBinding.Category.create(Identifier.of("spruce_fast", "main"))
            )
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (phase != Phase.IDLE && !overlay) {
                onChat(message.getString().toLowerCase());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(SpruceFastClient::tick);
    }

    /* ======================================================== */
    /*                         MAIN TICK                        */
    /* ======================================================== */

    private static void tick(MinecraftClient c) {

        while (startKey.wasPressed()) {
            if (c.player == null) {
                return;
            }
            if (phase == Phase.IDLE) {
                start(c);
            } else {
                stop(c, "Stopped.");
            }
        }

        if (phase == Phase.IDLE) {
            return;
        }

        if (c.player == null || c.interactionManager == null || c.world == null) {
            phase = Phase.IDLE;
            return;
        }

        tickCounter++;
        phaseTicks++;

        if (tickCounter - lastDiscordUpdateTick >= 300) {
            postDiscordProfit();
        }

        if (pendingChat != null) {
            c.player.networkHandler.sendChatMessage(pendingChat);
            pendingChat = null;
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        switch (phase) {
            case ORDER_CMD    -> orderCmd(c);
            case ORDER_GUI    -> orderGui(c);
            case ORDER_WAIT   -> orderWait(c);
            case COLLECT_CMD  -> collectCmd(c);
            case COLLECT_GUI  -> collectGui(c);
            case PLANKS       -> planks(c);
            case TABLE_OPEN   -> tableOpen(c);
            case TABLE        -> table(c);
            case GO_DROP      -> goDrop(c);
            case PICKUP_WAIT  -> pickupWait(c);
            case SELL_CMD     -> sellCmd(c);
            case SELL_GUI     -> sellGui(c);
            default -> { }
        }
    }

    private static void start(MinecraftClient c) {

        if (countEmptySlots(c) < REQUIRED_FREE_SLOTS) {
            info(c, "Empty your inventory first (need " + REQUIRED_FREE_SLOTS + " free slots).");
            return;
        }

        if (!resolveLayout(c)) {
            return;
        }

        collectedTotal = 0;
        tableRetries = 0;
        tableCloses = 0;
        foreignCount = 0;
        stageRounds = 0;
        droppedAny = false;
        tableAimStage = 0;
        recipeCache.clear();
        recipeFailed.clear();
        resetCraftState();
        pendingChat = null;
        sellBatchSlabs = 0;
        sellPayoutReceived = false;
        sellRevenue = 0.0;
        completedOrders = 0;
        profitStartTick = tickCounter;
        lastDiscordUpdateTick = -999999;
        discordMessageId = null;

        postDiscordProfit();
        info(c, "ON - ordering " + TARGET_LOGS + " spruce logs @ " + PRICE_PER_LOG);
        setPhase(Phase.ORDER_CMD);
    }

    private static void stop(MinecraftClient c, String reason) {
        phase = Phase.IDLE;
        releaseKeys(c);
        pendingChat = null;
        clickQueue.clear();
        if (c.player != null) {
            if (c.currentScreen instanceof HandledScreen<?>) {
                c.player.closeHandledScreen();
            }
            info(c, "OFF: " + reason);
        }
    }

    private static void setPhase(Phase p) {
        phase = p;
        clickQueue.clear();
        phaseTicks = 0;
        lastClickKey = "";
        lastScreen = null;
    }

    /* ======================================================== */
    /*                       FARM LAYOUT                        */
    /* ======================================================== */

    /** Finds the crafting table, the deepslate block and the cobblestone block. */
    private static boolean resolveLayout(MinecraftClient c) {

        tablePos = nearestBlock(c, c.player.getBlockPos(),
            p -> c.world.getBlockState(p).isOf(Blocks.CRAFTING_TABLE));

        if (tablePos == null) {
            info(c, "No crafting table found within " + LAYOUT_RADIUS + " blocks.");
            return false;
        }

        standPos = nearestBlock(c, tablePos,
            p -> c.world.getBlockState(p).isOf(STAND_BLOCK) && isFree(c, p.up()) && isFree(c, p.up(2)));

        if (standPos == null) {
            info(c, "No deepslate block (with free space above) found near the crafting table.");
            return false;
        }

        dropPos = nearestBlock(c, tablePos,
            p -> c.world.getBlockState(p).isOf(DROP_BLOCK) && isFree(c, p.up()));

        if (dropPos == null) {
            info(c, "No cobblestone block (with free space above) found near the crafting table.");
            return false;
        }

        Vec3d eyeAtStand = new Vec3d(standPos.getX() + 0.5, standPos.getY() + 1 + 1.62, standPos.getZ() + 0.5);
        double reach = eyeAtStand.distanceTo(Vec3d.ofCenter(tablePos));

        if (reach > TABLE_REACH) {
            info(c, "The crafting table is too far from the deepslate block (" + String.format("%.1f", reach) + " blocks).");
            return false;
        }

        LOG.info("Layout: table {}, stand (deepslate) {}, drop (cobblestone) {}", tablePos, standPos, dropPos);
        return true;
    }

    private static boolean isFree(MinecraftClient c, BlockPos p) {
        return c.world.getBlockState(p).getCollisionShape(c.world, p).isEmpty();
    }

    private static BlockPos nearestBlock(MinecraftClient c, BlockPos center, Predicate<BlockPos> ok) {

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (int dx = -LAYOUT_RADIUS; dx <= LAYOUT_RADIUS; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -LAYOUT_RADIUS; dz <= LAYOUT_RADIUS; dz++) {

                    BlockPos p = center.add(dx, dy, dz);

                    if (!ok.test(p)) {
                        continue;
                    }

                    double d = dx * dx + dy * dy + dz * dz;

                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }

        return best;
    }

    private static boolean onBlock(MinecraftClient c, BlockPos block) {
        return c.player.getBlockX() == block.getX()
            && c.player.getBlockZ() == block.getZ()
            && Math.abs(c.player.getY() - (block.getY() + 1)) < 0.7;
    }

    /**
     * Walks onto the middle of a block. Call every tick; returns true once the player
     * stands still on it.
     */
    private static boolean walkTo(MinecraftClient c, BlockPos block) {

        double dx = block.getX() + 0.5 - c.player.getX();
        double dz = block.getZ() + 0.5 - c.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist > 0.35) {
            c.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            c.options.backKey.setPressed(false);
            c.options.forwardKey.setPressed(true);
            c.options.jumpKey.setPressed(c.player.horizontalCollision);
            return false;
        }

        releaseKeys(c);

        double speed = c.player.getVelocity().horizontalLength();
        return onBlock(c, block) && speed < 0.03;
    }

    private static void releaseKeys(MinecraftClient c) {
        if (c.options != null) {
            c.options.forwardKey.setPressed(false);
            c.options.backKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
        }
    }

    /** Turns the player to look at a point (yaw/pitch like vanilla). */
    private static void aimAt(MinecraftClient c, Vec3d target) {
        Vec3d eye = c.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        c.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        c.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horiz)));
    }

    /* ---------- throw aiming ---------- */

    /** Works out where to look so thrown slabs land on the cobblestone block. */
    private static void computeThrowAim(MinecraftClient c) {

        double dx = dropPos.getX() + 0.5 - c.player.getX();
        double dz = dropPos.getZ() + 0.5 - c.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double ground = dropPos.getY() - standPos.getY(); // height difference between the two blocks

        throwYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        throwPitch = bestThrowPitch(dist, ground) + THROW_PITCH_TWEAK;

        LOG.info("Throw aim: yaw {} pitch {} (distance {} to cobblestone)", throwYaw, throwPitch, dist);
    }

    private static float bestThrowPitch(double dist, double ground) {

        float best = 0f;
        double bestErr = Double.MAX_VALUE;

        for (int p = -45; p <= 60; p++) {
            double err = Math.abs(simulateThrow(p, ground) - dist);
            if (err < bestErr) {
                bestErr = err;
                best = p;
            }
        }

        return best;
    }

    /** Rough simulation of a dropped item (vanilla throw speed 0.3, gravity 0.04, drag 0.98, ground friction). */
    private static double simulateThrow(float pitchDeg, double ground) {

        double rad = Math.toRadians(pitchDeg);
        double vx = 0.3 * Math.cos(rad);
        double vy = -0.3 * Math.sin(rad) + 0.1;
        double x = 0.0;
        double y = 1.32; // eye height - 0.3

        for (int t = 0; t < 400; t++) {

            vy -= 0.04;
            x += vx;
            y += vy;

            boolean onGround = false;

            if (y <= ground) {
                y = ground;
                vy = 0;
                onGround = true;
            }

            double f = onGround ? 0.588 : 0.98;
            vx *= f;
            vy *= 0.98;

            if (onGround && vx < 0.003) {
                break;
            }
        }

        return x;
    }

    /* ======================================================== */
    /*                      CHAT LISTENER                       */
    /* ======================================================== */

    private static void onChat(String msg) {

        // Order completion detection.
        if (phase == Phase.ORDER_GUI || phase == Phase.ORDER_WAIT) {
            if (msg.contains("delivered")
                    || (msg.contains("order")
                        && (msg.contains("filled") || msg.contains("completed")
                            || msg.contains("fulfilled") || msg.contains("ready")
                            || msg.contains("collect")))) {
                orderFilled = true;
            }
        }

        // When /sell pays us, DonutSMP normally puts a dollar amount in the chat.
        // We only accept it while we are actually selling, preventing unrelated
        // money messages from being counted.
        if ((phase == Phase.SELL_GUI || phase == Phase.PICKUP_WAIT) && !sellPayoutReceived) {
            Double payout = extractSellPayout(msg);
            if (payout != null && payout > 0) {
                sellRevenue += payout;
                sellPayoutReceived = true;
                postDiscordProfit();
            }
        }
    }

    private static Double extractSellPayout(String msg) {
        String lower = msg.toLowerCase();

        // Only parse messages that look like a /sell result.
        if (!(lower.contains("sold")
                || lower.contains("sell")
                || lower.contains("earned")
                || lower.contains("received")
                || lower.contains("made")
                || lower.contains("profit"))) {
            return null;
        }

        Pattern p = Pattern.compile(
                "(?:\\$\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(lower);

        Double best = null;
        while (m.find()) {
            try {
                double value = Double.parseDouble(m.group(1).replace(",", ""));
                String suffix = m.group(2);
                if (suffix != null) {
                    switch (suffix.toLowerCase()) {
                        case "k" -> value *= 1_000.0;
                        case "m" -> value *= 1_000_000.0;
                        case "b" -> value *= 1_000_000_000.0;
                    }
                }
                if (best == null || value > best) {
                    best = value;
                }
            } catch (Exception ignored) {
            }
        }

        return best;
    }

    /* ======================================================== */
    /*                         ORDERING                         */
    /* ======================================================== */

    private static void orderCmd(MinecraftClient c) {
        orderStep = 0;
        dialogScreen = null;
        orderFilled = false;
        c.player.networkHandler.sendChatCommand(ORDER_COMMAND);
        setPhase(Phase.ORDER_GUI);
        cooldown = 0;
    }

    /*
     * Walkthrough:
     * 0  /orders chest menu      -> click "Your Orders"
     * 1  Your Orders chest menu  -> click "New Order"
     * 2  Choose Item dialog      -> type "Spruce logs", press Search
     * 3  Search results          -> press "Spruce Log"
     * 4  Amount dialog           -> type 576, press Next
     * 5  Price dialog            -> type 53, press Review Order
     * 6  Review dialog           -> press Create Order
     */
    private static void orderGui(MinecraftClient c) {

        if (phaseTicks > 1200) {
            stop(c, "Order setup timed out at step " + orderStep + ". Check logs/latest.log.");
            return;
        }

        HandledScreen<?> hs = openContainer(c);

        /* ---------- steps 0-1: chest menus ---------- */
        if (orderStep <= 1) {

            if (hs == null) {
                return;
            }

            dump(hs);

            ScreenHandler h = hs.getScreenHandler();
            int cs = containerSize(h);
            String title = hs.getTitle().getString().toLowerCase();

            if (title.contains("deliver") || title.contains("fulfill") || title.contains("fill order")) {
                stop(c, "Opened a deliver screen. Aborted.");
                return;
            }

            int s = find(h, cs, "new order");

            if (s == -1 && title.contains("your orders") && !h.getSlot(0).getStack().isEmpty()
                    && !h.getSlot(0).getStack().isOf(Items.SPRUCE_LOG)) {
                s = 0; // "New Order" is the first slot
            }

            if (s != -1) {
                click(c, h, s, SlotActionType.PICKUP);
                orderStep = 2;
                cooldown = 0;
                return;
            }

            s = find(h, cs, "your orders");
            if (s != -1 && clickOnce(c, hs, h, s)) {
                orderStep = 1;
                cooldown = 0;
            }

            return;
        }

        /* ---------- step 7: "Create Order" was pressed ---------- */
        if (orderStep == 7) {
            if (tickCounter - lastPressTick >= 3) {
                closeScreens(c);
                if (c.currentScreen != null) {
                    c.setScreen(null);
                }
                info(c, "Order created. Collecting...");
                completedOrders++;
                if (profitStartTick < 0) profitStartTick = tickCounter;
                postDiscordProfit();
                setPhase(Phase.COLLECT_CMD);
            }
            return;
        }

        /* ---------- steps 2-6: dialog screens ---------- */
        if (hs != null || c.currentScreen == null) {
            return; // old chest still closing, or dialog not open yet
        }

        Screen sc = c.currentScreen;

        // New dialog screen -> remember when we first saw it.
        if (sc != dialogScreen) {
            dialogScreen = sc;
            dialogSince = tickCounter;
            lastPressTick = -1000;
        }

        // Let a fresh screen finish building before touching it.
        if (tickCounter - dialogSince < DIALOG_SETTLE) {
            return;
        }

        // Pressed recently on this same screen -> wait; retry only if nothing changed.
        if (tickCounter - lastPressTick < PRESS_RETRY) {
            return;
        }

        List<ClickableWidget> ws = widgets(sc);
        dumpWidgets(sc, ws);

        String title = sc.getTitle().getString().toLowerCase();
        TextFieldWidget tf = firstTextField(ws);
        ClickableWidget b;

        /*
         * The action is chosen by what is ON the screen (not by a step counter),
         * so a lost click is simply retried and a slow server can't desync us.
         */
        if ((b = button(ws, "create order")) != null) {

            pressButton(b);
            orderStep = 7;

        } else if (tf != null && (b = button(ws, "review order")) != null) {

            tf.setText(String.valueOf(PRICE_PER_LOG));
            pressButton(b);

        } else if (tf != null && (b = button(ws, "next")) != null) {

            tf.setText(String.valueOf(TARGET_LOGS));
            pressButton(b);

        } else if (tf != null && title.contains("result")) {

            b = spruceLogButton(ws);
            if (b == null) {
                return;
            }
            pressButton(b);

        } else if (tf != null && (b = button(ws, "search")) != null) {

            tf.setText(ORDER_ITEM_NAME);
            pressButton(b);

        } else {
            return;
        }

        lastPressTick = tickCounter;
    }

    private static void orderWait(MinecraftClient c) {

        if (orderFilled) {
            setPhase(Phase.COLLECT_CMD);
            return;
        }

        // The server may re-open the /orders menu after creating the order.
        if (openContainer(c) != null) {
            closeScreens(c);
        }

        if (phaseTicks % 100 == 0) {
            info(c, "Waiting for order... " + (phaseTicks / 20) + "s");
        }

        if (phaseTicks > 0 && phaseTicks % POLL_TICKS == 0) {
            setPhase(Phase.COLLECT_CMD);
        }
    }

    /* ======================================================== */
    /*                        COLLECTING                        */
    /* ======================================================== */

    private static void collectCmd(MinecraftClient c) {
        logsAtCollectStart = count(c, Items.SPRUCE_LOG);
        movedAny = false;
        orderFilled = false;
        c.player.networkHandler.sendChatCommand(ORDER_COMMAND);
        setPhase(Phase.COLLECT_GUI);
    }

    /*
     * Walkthrough (decided by the menu title, one action per menu, no delays):
     *   "Orders (Page 1)"        -> click the "Your Orders" chest
     *   "Orders -> Your Orders"  -> click the spruce log order
     *   "Orders -> Edit Order"   -> click the "Collect" chest
     *   "Orders -> Collect Items"-> shift-click all spruce logs
     */
    private static void collectGui(MinecraftClient c) {

        int gained = count(c, Items.SPRUCE_LOG) - logsAtCollectStart;

        if (gained >= TARGET_LOGS - collectedTotal) {
            finishCollect(c, gained);
            return;
        }

        if (phaseTicks > 80) {
            if (gained > 0) {
                finishCollect(c, gained);
            } else {
                closeScreens(c);
                info(c, "Nothing to collect yet.");
                setPhase(Phase.ORDER_WAIT);
            }
            return;
        }

        HandledScreen<?> hs = openContainer(c);
        if (hs == null) {
            return;
        }

        dump(hs);

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);
        String title = hs.getTitle().getString().toLowerCase();

        if (title.contains("deliver") || title.contains("fulfill") || title.contains("fill order")) {
            stop(c, "Opened a deliver screen. Aborted.");
            return;
        }

        if (title.contains("collect items")) {

            if (quickMoveAllNow(c, h, 0, cs, Items.SPRUCE_LOG)) {
                movedAny = true;
            } else if (movedAny) {
                finishCollect(c, count(c, Items.SPRUCE_LOG) - logsAtCollectStart);
            }

            return;
        }

        int s;

        if (title.contains("edit order")) {
            s = find(h, cs, "collect");
        } else if (title.contains("your orders")) {
            s = findItem(h, cs, Items.SPRUCE_LOG);
        } else {
            s = find(h, cs, "your orders");
        }

        if (s != -1) {
            clickOnce(c, hs, h, s);
        }
    }

    private static void finishCollect(MinecraftClient c, int gained) {
        collectedTotal += Math.max(gained, 0);
        closeScreens(c);
        info(c, "Collected " + collectedTotal + "/" + TARGET_LOGS + " logs.");
        stageRounds = 0;
        tableAimStage = 0;
        setPhase(Phase.PLANKS);   // logs -> planks in the player inventory first
        cooldown = 4;
    }

    /* ======================================================== */
    /*                  STAGE 1: LOGS -> PLANKS                 */
    /*        (player inventory, 2x2 grid, recipe book)         */
    /* ======================================================== */

    /**
     * Opens the player inventory (like pressing E), then repeats
     *   recipe book click "Spruce Planks" (server moves one log stack into the grid)
     *   -> shift-click the result (server crafts everything and puts the planks into the inventory)
     * until the logs are gone or the inventory has to be emptied first (then the table stage runs).
     */
    private static void planks(MinecraftClient c) {

        if (phaseTicks > 3000) {
            stop(c, "Making planks timed out.");
            return;
        }

        // Nothing to convert -> straight to the table.
        if (count(c, Items.SPRUCE_LOG) == 0) {
            closeScreens(c);
            tableAimStage = 0;
            setPhase(Phase.TABLE_OPEN);
            return;
        }

        // Open the player inventory (E).
        if (!(c.currentScreen instanceof InventoryScreen)) {

            if (c.currentScreen != null) {
                closeScreens(c);
                if (c.currentScreen != null) {
                    c.setScreen(null);
                }
                cooldown = 2;
                return;
            }

            c.setScreen(new InventoryScreen(c.player));
            resetCraftState();
            craftReadyAt = tickCounter + INV_OPEN_WAIT;
            dumpTable(c.player.playerScreenHandler, "inventory opened");
            return;
        }

        PlayerScreenHandler h = c.player.playerScreenHandler;

        if (c.player.currentScreenHandler != h) {
            return;
        }

        if (tickCounter < craftReadyAt) {
            return;
        }

        if (runQueue()) {
            return;
        }

        CraftState r = craftTick(c, h, SPEC_PLANKS);

        if (r == CraftState.FAILED) {
            stop(c, failReason);
            return;
        }

        if (r == CraftState.DONE) {

            closeScreens(c);   // closing returns anything left in the 2x2 grid to the inventory

            if (count(c, Items.SPRUCE_LOG) > 0 && count(c, Items.SPRUCE_PLANKS) < 3) {
                stop(c, "Inventory too full to craft planks. Free up more slots.");
                return;
            }

            tableAimStage = 0;
            setPhase(Phase.TABLE_OPEN);
            cooldown = 1;
        }
    }

    /** Back to the planks stage (counted, so a planks <-> slabs loop can't run forever). */
    private static void toPlanks(MinecraftClient c) {
        if (++stageRounds > MAX_STAGE_ROUNDS) {
            stop(c, "Crafting keeps switching between planks and slabs. Check logs/latest.log.");
            return;
        }
        tableAimStage = 0;
        setPhase(Phase.PLANKS);
    }

    /* ======================================================== */
    /*     STAGE 2: PLANKS -> SLABS (crafting table, throw)     */
    /* ======================================================== */

    private static void tableOpen(MinecraftClient c) {

        int logs = count(c, Items.SPRUCE_LOG);
        int planksLeft = count(c, Items.SPRUCE_PLANKS);

        // Not enough planks for a slab craft.
        if (planksLeft < 3) {
            releaseKeys(c);
            if (logs > 0) {
                toPlanks(c);     // there are still logs -> make planks first
            } else if (count(c, Items.SPRUCE_SLAB) > 0 || droppedAny) {
                setPhase(Phase.GO_DROP);
            } else {
                afterSell(c);
            }
            return;
        }

        if (phaseTicks > 600) {
            releaseKeys(c);
            stop(c, "Could not get onto the deepslate block.");
            return;
        }

        if (!c.world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
            releaseKeys(c);
            stop(c, "The crafting table is gone.");
            return;
        }

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
        }

        // 1) Walk onto the deepslate block.
        if (!walkTo(c, standPos)) {
            tableAimStage = 0;
            return;
        }

        Vec3d center = Vec3d.ofCenter(tablePos);

        // 2) Stage 0: work out the throw direction, then LOOK at the table (servers check where we look).
        if (tableAimStage == 0) {
            computeThrowAim(c);
            aimAt(c, center);
            tableAimStage = 1;
            cooldown = 3;
            return;
        }

        tableAimStage = 0;

        // 3) Stage 1: use the block exactly where the crosshair points.
        Vec3d eye = c.player.getEyePos();
        Vec3d end = eye.add(c.player.getRotationVec(1.0f).multiply(5.0));

        BlockHitResult hit = c.world.raycast(new RaycastContext(
            eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, c.player));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(tablePos)) {
            hit = new BlockHitResult(center, Direction.UP, tablePos, false);
        }

        c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, hit);
        c.player.swingHand(Hand.MAIN_HAND);

        resetCraftState();
        tableSeenSync = -1;
        setPhase(Phase.TABLE);
    }

    /*
     * Inside the REAL crafting table (server-side container):
     *   recipe book click "Spruce Slab" -> the server moves up to 3 stacks of planks into the grid
     *   Ctrl+Q on the RESULT slot       -> the server crafts and drops every slab straight away
     *                                      (they never touch the inventory)
     */
    private static void table(MinecraftClient c) {

        if (phaseTicks > 12000) {
            stop(c, "Crafting timed out.");
            return;
        }

        if (!(c.player.currentScreenHandler instanceof CraftingScreenHandler h)) {

            if (tableSeenSync != -1) {
                // It was open and now it isn't: something closed it (items in the grid go back to the inventory).
                tableSeenSync = -1;
                clickQueue.clear();
                LOG.warn("Crafting table was CLOSED by the server (or another source). Reopening.");
                if (++tableCloses > 3) {
                    stop(c, "The crafting table keeps closing (server/anti-cheat?).");
                    return;
                }
                info(c, "Crafting table closed - reopening...");
                setPhase(Phase.TABLE_OPEN);
                cooldown = 20;
                return;
            }

            if (phaseTicks > 30) {
                if (++tableRetries > 3) {
                    stop(c, "Could not open the crafting table.");
                } else {
                    setPhase(Phase.TABLE_OPEN);
                }
            }
            return;
        }

        tableRetries = 0;

        // Fresh table screen -> give the server a moment before touching anything.
        if (tableSeenSync != h.syncId) {
            tableSeenSync = h.syncId;
            tableReadyAt = tickCounter + OPEN_WAIT;
            // face the cobblestone so Ctrl+Q throws land on it
            c.player.setYaw(throwYaw);
            c.player.setPitch(throwPitch);
            resetCraftState();
            dumpTable(h, "table opened");
        }

        if (tickCounter < tableReadyAt) {
            return;
        }

        if (runQueue()) {
            return;
        }

        /*
         * (Safety) Slabs that ended up in the inventory while the inventory is getting full
         * -> Ctrl+Q (drop the WHOLE stack) on every slab stack. Normally slabs are dropped
         * straight from the result slot and never get here.
         */
        if (countFree(h, T_INV_FROM, T_INV_TO) < 4
                && countOf(h, Items.SPRUCE_SLAB, T_INV_FROM, T_INV_TO) > 0) {

            for (int i = T_INV_FROM; i < T_INV_TO; i++) {
                if (h.getSlot(i).getStack().isOf(Items.SPRUCE_SLAB)) {
                    q(c, h, i, 1, SlotActionType.THROW);
                    droppedAny = true;
                }
            }
            return;
        }

        CraftState r = craftTick(c, h, SPEC_SLABS);

        if (r == CraftState.FAILED) {
            stop(c, failReason);
            return;
        }

        if (r == CraftState.DONE) {

            c.player.closeHandledScreen();

            if (count(c, Items.SPRUCE_LOG) > 0) {
                info(c, "Planks turned into slabs. More logs left - back to planks...");
                toPlanks(c);
                cooldown = 2;
            } else {
                info(c, "Crafting finished. Walking to the cobblestone to sell...");
                setPhase(Phase.GO_DROP);
                cooldown = 3;
            }
        }
    }

    /* ======================================================== */
    /*            RECIPE-BOOK CRAFTING ENGINE (shared)          */
    /* ======================================================== */

    private static void resetCraftState() {
        pendingKind = PENDING_NONE;
        pendingSince = 0;
        gridBeforeCraft = -1;
        fillFails = 0;
        craftStalls = 0;
        stableTicks = 0;
        recipeMissSince = -1;
        clickQueue.clear();
    }

    /**
     * One step of a crafting stage on ANY crafting handler (player 2x2 or crafting table 3x3).
     * Call once per tick; returns WORKING while there is something to do or to wait for,
     * DONE once the input is used up and everything is settled, FAILED (see failReason) otherwise.
     *
     *   grid has input  -> take the output (shift-click for planks, Ctrl+Q for slabs) and wait until
     *                      the grid is empty (server confirmed) - never a blind fixed delay
     *   grid is empty   -> recipe book click (craft all) and wait until the grid is filled
     *                      (if the recipe book can't be used: manual loading as a fallback)
     */
    private static CraftState craftTick(MinecraftClient c, ScreenHandler h, Spec s) {

        // 0) We never use the cursor (except in the manual fallback); if something is stuck on it, put it away.
        if (!h.getCursorStack().isEmpty()) {
            if (!putCursorAway(c, h, s.invFrom, s.invTo)) {
                c.interactionManager.clickSlot(h.syncId, -999, 0, SlotActionType.PICKUP, c.player);
            }
            cooldown = CLICK_GAP;
            return CraftState.WORKING;
        }

        // 1) Look at the grid.
        int gridCount = 0;

        for (int i = s.gridFrom; i < s.gridTo; i++) {

            ItemStack st = h.getSlot(i).getStack();

            if (st.isEmpty()) {
                continue;
            }

            if (!st.isOf(s.input)) {

                // A foreign item: report it, send it back to the inventory and carry on.
                String name = st.getName().getString() + " x" + st.getCount() + " (grid slot " + i + ")";
                LOG.warn("Foreign item in crafting grid: {}", name);
                dumpTable(h, "foreign item");

                if (++foreignCount > 5) {
                    failReason = "Unexpected item in the crafting grid: " + name;
                    return CraftState.FAILED;
                }

                info(c, "Moving unexpected item out of the grid: " + name);
                q(c, h, i, 0, SlotActionType.QUICK_MOVE);
                return CraftState.WORKING;
            }

            gridCount += st.getCount();
        }

        /* ---------------- grid has ingredients -> craft ---------------- */
        if (gridCount > 0) {

            stableTicks = 0;

            if (pendingKind == PENDING_FILL) {
                pendingKind = PENDING_NONE;   // the server filled the grid
                fillFails = 0;
            }

            if (pendingKind == PENDING_CRAFT) {

                if (tickCounter - pendingSince < CRAFT_TIMEOUT) {
                    return CraftState.WORKING;   // the server is still working on our last click
                }

                pendingKind = PENDING_NONE;

                if (gridBeforeCraft >= 0 && gridCount < gridBeforeCraft) {
                    craftStalls = 0;             // partial progress (e.g. inventory was full), go on
                } else if (++craftStalls >= 3) {
                    dumpTable(h, "no crafting progress");
                    failReason = "Crafting " + s.name + " makes no progress (inventory full, or the server isn't crafting). Check logs/latest.log.";
                    return CraftState.FAILED;
                }
            }

            gridBeforeCraft = gridCount;

            if (s.dropOutput) {
                // Ctrl+Q on the result slot: the server crafts and drops slab after slab until the grid is used up.
                c.interactionManager.clickSlot(h.syncId, 0, 1, SlotActionType.THROW, c.player);
                droppedAny = true;
            } else {
                // Shift-click on the result slot: the server crafts everything and moves the planks into the inventory.
                click(c, h, 0, SlotActionType.QUICK_MOVE);
            }

            pendingKind = PENDING_CRAFT;
            pendingSince = tickCounter;
            cooldown = CRAFT_MIN_WAIT;
            return CraftState.WORKING;
        }

        /* ---------------- grid is empty ---------------- */

        if (pendingKind == PENDING_CRAFT) {
            pendingKind = PENDING_NONE;      // the whole grid was crafted
            craftStalls = 0;
            gridBeforeCraft = -1;
        }

        if (pendingKind == PENDING_FILL) {

            if (tickCounter - pendingSince < FILL_TIMEOUT) {
                return CraftState.WORKING;   // wait for the server to fill the grid
            }

            pendingKind = PENDING_NONE;
            recipeCache.remove(s.output);    // maybe the cached id is stale: look it up again next time

            if (++fillFails >= 3) {
                recipeFailed.add(s.output);
                LOG.warn("Recipe book fill for {} did nothing {} times - switching to manual loading.", s.name, fillFails);
                info(c, "Recipe book not working for " + s.name + " - loading the grid by hand.");
            }
        }

        boolean recipeOk = USE_RECIPE_BOOK && !recipeFailed.contains(s.output);
        boolean have = haveIngredients(h, s, recipeOk);

        // Planks go into the inventory: when it is nearly full, let the table turn planks into slabs first.
        if (have && !s.dropOutput && countFree(h, s.invFrom, s.invTo) < PLANKS_MIN_FREE) {
            have = false;
        }

        /* ---------------- nothing (more) to load -> finish ---------------- */
        if (!have) {
            // Wait a few quiet ticks so every server answer has arrived before we move on.
            if (++stableTicks < 3) {
                return CraftState.WORKING;
            }
            stableTicks = 0;
            return CraftState.DONE;
        }

        stableTicks = 0;

        /* ---------------- load via the recipe book ---------------- */
        if (recipeOk) {

            NetworkRecipeId id = lookupRecipe(c, s.output);

            if (id != null) {
                recipeMissSince = -1;
                // true = "craft all" (like shift-clicking the recipe): the server fills the grid with as much as fits
                c.interactionManager.clickRecipe(h.syncId, id, true);
                pendingKind = PENDING_FILL;
                pendingSince = tickCounter;
                cooldown = FILL_MIN_WAIT;
                return CraftState.WORKING;
            }

            // Not in the recipe book (yet). Recipes are unlocked by the server a moment after we get the
            // ingredients, so wait a little before giving up on the recipe book for this item.
            if (recipeMissSince < 0) {
                recipeMissSince = tickCounter;
            }

            if (tickCounter - recipeMissSince < RECIPE_WAIT) {
                return CraftState.WORKING;
            }

            recipeFailed.add(s.output);
            LOG.warn("No recipe for {} in the recipe book (not unlocked?) - switching to manual loading.", s.name);
            info(c, "Recipe for " + s.name + " not in the recipe book - loading the grid by hand.");
        }

        /* ---------------- manual fallback ---------------- */
        if (stacksOf(h, s.input, s.invFrom, s.invTo) >= s.manualCells.length) {
            queueLoad(c, h, s.input, s.manualCells, s.invFrom, s.hotbarFrom, s.invTo);
            return CraftState.WORKING;
        }

        return CraftState.DONE;   // can't load the grid at all (e.g. not enough full stacks for manual loading)
    }

    private static boolean haveIngredients(ScreenHandler h, Spec s, boolean recipeOk) {
        if (recipeOk) {
            // the recipe book spreads any amount over the cells
            return countOf(h, s.input, s.invFrom, s.invTo) >= s.minItems;
        }
        return stacksOf(h, s.input, s.invFrom, s.invTo) >= s.manualCells.length;
    }

    private static NetworkRecipeId lookupRecipe(MinecraftClient c, Item result) {

        NetworkRecipeId id = recipeCache.get(result);

        if (id == null) {
            id = findRecipe(c, result);
            if (id != null) {
                recipeCache.put(result, id);
            }
        }

        return id;
    }

    /** Looks the recipe up in the player's REAL recipe book (only unlocked recipes are in there). */
    private static NetworkRecipeId findRecipe(MinecraftClient c, Item result) {

        var ctx = SlotDisplayContexts.createParameters(c.world);

        for (RecipeResultCollection collection : c.player.getRecipeBook().getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                for (ItemStack stack : entry.getStacks(ctx)) {
                    if (stack.isOf(result)) {
                        return entry.id();
                    }
                }
            }
        }

        return null;
    }

    /** Runs ONE queued click (manual fallback / safety clicks). Returns true if it did something. */
    private static boolean runQueue() {
        if (clickQueue.isEmpty()) {
            return false;
        }
        clickQueue.poll().run();
        cooldown = clickQueue.isEmpty() ? SETTLE : CLICK_GAP;
        return true;
    }

    /** Queues one click; it is sent later, one per CLICK_GAP ticks. */
    private static void q(MinecraftClient c, ScreenHandler h, int slot, int button, SlotActionType type) {
        clickQueue.add(() -> {
            if (c.player != null && c.player.currentScreenHandler == h) {
                c.interactionManager.clickSlot(h.syncId, slot, button, type, c.player);
            } else {
                clickQueue.clear();
            }
        });
    }

    /*
     * MANUAL FALLBACK (only used if the recipe book can't be used for an item):
     * queues "move one whole stack of `item` into each of the given cells". Stacks already in the
     * hotbar are swapped straight into the cell; the others are picked up and put into the cell.
     */
    private static void queueLoad(MinecraftClient c, ScreenHandler h, Item item, int[] cells,
                                  int invFrom, int hotbarFrom, int invTo) {

        List<Integer> srcs = new ArrayList<>();

        for (int i = hotbarFrom; i < invTo && srcs.size() < cells.length; i++) {
            if (h.getSlot(i).getStack().isOf(item)) srcs.add(i);
        }
        for (int i = invFrom; i < hotbarFrom && srcs.size() < cells.length; i++) {
            if (h.getSlot(i).getStack().isOf(item)) srcs.add(i);
        }

        boolean[] hbUsed = new boolean[9];
        for (int src : srcs) {
            if (src >= hotbarFrom) hbUsed[src - hotbarFrom] = true;
        }

        for (int k = 0; k < srcs.size(); k++) {

            int src = srcs.get(k);

            if (src >= hotbarFrom) {

                // already in the hotbar: ONE swap straight into the cell
                q(c, h, cells[k], src - hotbarFrom, SlotActionType.SWAP);

            } else if (DIRECT_LOAD) {

                // main inventory -> grid cell directly: pick the stack up, put it in the cell
                q(c, h, src, 0, SlotActionType.PICKUP);
                q(c, h, cells[k], 0, SlotActionType.PICKUP);

            } else {

                // hotbar stopover (set DIRECT_LOAD = false if the direct way misbehaves)
                int hb = freeHotbar(h, hbUsed, hotbarFrom);
                hbUsed[hb] = true;
                q(c, h, src, hb, SlotActionType.SWAP);
                q(c, h, cells[k], hb, SlotActionType.SWAP);
            }
        }
    }

    private static int freeHotbar(ScreenHandler h, boolean[] used, int hotbarFrom) {
        for (int hb = 0; hb < 9; hb++) {
            if (!used[hb] && h.getSlot(hotbarFrom + hb).getStack().isEmpty()) return hb;
        }
        for (int hb = 0; hb < 9; hb++) {
            if (!used[hb]) return hb;
        }
        return 8;
    }

    private static void dumpTable(ScreenHandler h, String why) {
        if (!DEBUG) {
            return;
        }
        LOG.info("[CRAFT] {} (syncId {})", why, h.syncId);
        for (int i = 0; i < h.slots.size(); i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty()) {
                LOG.info("  slot {} -> {} x{}", i, st.getItem(), st.getCount());
            }
        }
    }

    /* ======================================================== */
    /*             COBBLESTONE: PICK UP + SELL (loop)           */
    /* ======================================================== */

    /** Walks onto the cobblestone block (and stays there until everything is sold). */
    private static void goDrop(MinecraftClient c) {

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
        }

        if (phaseTicks > 600) {
            releaseKeys(c);
            stop(c, "Could not get onto the cobblestone block.");
            return;
        }

        if (!walkTo(c, dropPos)) {
            return;
        }

        enterPickupWait();
    }

    private static void enterPickupWait() {
        pickupLastCount = -1;
        pickupStable = 0;
        setPhase(Phase.PICKUP_WAIT);
    }

    /*
     * Standing on the cobblestone, thrown slabs are picked up automatically as soon as the
     * inventory has room. Sell whatever is in the inventory once it stops growing, then repeat
     * until nothing more shows up.
     */
    private static void pickupWait(MinecraftClient c) {

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
        }

        // pushed off the cobblestone? walk back onto it
        if (!onBlock(c, dropPos)) {
            setPhase(Phase.GO_DROP);
            return;
        }

        int slabs = count(c, Items.SPRUCE_SLAB);

        if (slabs > 0) {

            if (slabs == pickupLastCount) {
                pickupStable++;
            } else {
                pickupLastCount = slabs;
                pickupStable = 0;
            }

            if (pickupStable >= 8) {
                setPhase(Phase.SELL_CMD);
            }

            phaseTicks = 0; // keep waiting while slabs are present
            return;
        }

        if (phaseTicks > 100) { // nothing left on the ground
            droppedAny = false;
            afterSell(c);
        }
    }

    private static void sellCmd(MinecraftClient c) {

        if (count(c, Items.SPRUCE_SLAB) == 0) {
            enterPickupWait();
            return;
        }

        movedPlanks = false;
        confirmedSell = false;
        sellBatchSlabs = count(c, Items.SPRUCE_SLAB);
        sellPayoutReceived = false;
        c.player.networkHandler.sendChatCommand(SELL_COMMAND);
        postDiscordProfit();
        setPhase(Phase.SELL_GUI);
    }

    private static void sellGui(MinecraftClient c) {

        HandledScreen<?> hs = openContainer(c);

        if (hs == null) {
            if (phaseTicks > 100) {
                stop(c, "Sell GUI never opened. Check logs/latest.log.");
            }
            return;
        }

        dump(hs);

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);

        // 1) ONE SHOT: shift-click every slab (and leftover plank) stack into the sell GUI at once
        if (!movedPlanks) {
            quickMoveAllNow(c, h, cs, h.slots.size(), Items.SPRUCE_SLAB);
            quickMoveAllNow(c, h, cs, h.slots.size(), Items.SPRUCE_PLANKS);
            movedPlanks = true;
            cooldown = 3;
            return;
        }

        // 2) confirm button if there is one
        if (!confirmedSell) {
            confirmedSell = true;
            int s = find(h, cs, "confirm", "sell all", "accept");
            if (s != -1) {
                click(c, h, s, SlotActionType.PICKUP);
                cooldown = 6;
                return;
            }
        }

        // 3) close (many /sell GUIs sell on close)
        if (c.currentScreen instanceof HandledScreen<?>) {
            c.player.closeHandledScreen();
        }

        cooldown = 10;
        postDiscordProfit();
        enterPickupWait();
    }

    /** Nothing more to sell: craft the logs that are left, start the next order, or finish. */
    private static void afterSell(MinecraftClient c) {

        cooldown = 10;

        if (count(c, Items.SPRUCE_LOG) > 0) {
            toPlanks(c);   // logs first (inventory), then back onto the deepslate block for the table
            return;
        }

        if (collectedTotal >= TARGET_LOGS) {

            if (!LOOP) {
                stop(c, "Done.");
                return;
            }

            collectedTotal = 0;

            if (countEmptySlots(c) < REQUIRED_FREE_SLOTS) {
                stop(c, "Not enough free slots for the next batch.");
                return;
            }

            info(c, "Cycle complete. Starting next order...");
            setPhase(Phase.ORDER_CMD);
            return;
        }

        setPhase(Phase.ORDER_WAIT);
    }

    /* ======================================================== */
    /*                         HELPERS                          */
    /* ======================================================== */


    /* ======================================================== */
    /*                    DISCORD WEBHOOK                       */
    /* ======================================================== */

    private static void postDiscordProfit() {
        if (DISCORD_WEBHOOK_URL.startsWith("PASTE_")) {
            return;
        }

        lastDiscordUpdateTick = tickCounter;

        double net = sellRevenue - (completedOrders * ORDER_COST);
        double hours = Math.max((tickCounter - Math.max(profitStartTick, 0)) / 72000.0, 1.0 / 3600.0);
        double perHour = net / hours;

        String content =
                "💰 **Spruce Fast Profit**\\n"
                + "Sell revenue: **$" + money(sellRevenue) + "**\\n"
                + "Orders: **" + completedOrders + "** × $" + money(ORDER_COST)
                + " = **$" + money((double) completedOrders * ORDER_COST) + "**\\n"
                + "NET PROFIT: **$" + money(net) + "**\\n"
                + "Current rate: **$" + money(perHour) + "/hr**";

        try {
            if (discordMessageId == null) {
                String url = DISCORD_WEBHOOK_URL
                        + (DISCORD_WEBHOOK_URL.contains("?") ? "&wait=true" : "?wait=true");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\""
                                + jsonEscape(content) + "\"}"))
                        .build();

                PROFIT_HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9]+)\"").matcher(response.body());
                            if (m.find()) {
                                discordMessageId = m.group(1);
                            }
                        })
                        .exceptionally(ex -> null);
            } else {
                String base = DISCORD_WEBHOOK_URL;
                int q = base.indexOf('?');
                if (q >= 0) base = base.substring(0, q);

                String url = base + "/messages/" + discordMessageId;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                "{\"content\":\"" + jsonEscape(content) + "\"}"))
                        .build();

                PROFIT_HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .exceptionally(ex -> null);
            }
        } catch (Exception ignored) {
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String money(double value) {
        return String.format(java.util.Locale.US, "%,.0f", value);
    }

    private static void info(MinecraftClient c, String text) {
        if (c.player != null) {
            c.player.sendMessage(Text.literal("Spruce Fast: " + text), true);
        }
        LOG.info(text);
    }

    private static void closeScreens(MinecraftClient c) {
        if (c.currentScreen instanceof HandledScreen<?>) {
            c.player.closeHandledScreen();
        }
    }

    private static void click(MinecraftClient c, ScreenHandler h, int slot, SlotActionType type) {
        c.interactionManager.clickSlot(h.syncId, slot, 0, type, c.player);
    }

    /** Server-side GUI (not the player inventory). */
    private static HandledScreen<?> openContainer(MinecraftClient c) {
        if (c.currentScreen instanceof HandledScreen<?> hs
                && !(c.currentScreen instanceof InventoryScreen)) {
            return hs;
        }
        return null;
    }

    /** Container slots = all slots minus the 36 player inventory slots. */
    private static int containerSize(ScreenHandler h) {
        return Math.max(0, h.slots.size() - 36);
    }

    private static String textOf(ItemStack stack) {
        StringBuilder sb = new StringBuilder(stack.getName().getString());
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                sb.append(' ').append(line.getString());
            }
        }
        return sb.toString().toLowerCase();
    }

    private static int find(ScreenHandler h, int cs, String... keys) {
        for (int i = 0; i < cs; i++) {
            ItemStack stack = h.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String t = textOf(stack);
            for (String k : keys) {
                if (t.contains(k)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findItem(ScreenHandler h, int cs, Item item) {
        for (int i = 0; i < cs; i++) {
            if (h.getSlot(i).getStack().isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    /** Click a slot, but not the same slot twice in a row on the same screen. */
    private static boolean clickOnce(MinecraftClient c, HandledScreen<?> hs, ScreenHandler h, int slot) {
        String key = hs.getTitle().getString() + ":" + slot;
        if (key.equals(lastClickKey)) {
            return false;
        }
        lastClickKey = key;
        click(c, h, slot, SlotActionType.PICKUP);
        return true;
    }

    /** Shift-clicks EVERY matching slot in [from, to) in one tick. */
    private static boolean quickMoveAllNow(MinecraftClient c, ScreenHandler h, int from, int to, Item item) {
        boolean any = false;
        for (int i = from; i < to; i++) {
            if (h.getSlot(i).getStack().isOf(item)) {
                click(c, h, i, SlotActionType.QUICK_MOVE);
                any = true;
            }
        }
        return any;
    }

    /** Logs a GUI once so we can see exact titles and button names. */
    private static void dump(HandledScreen<?> hs) {
        if (hs == lastScreen) {
            return;
        }
        lastScreen = hs;
        lastClickKey = "";

        if (!DEBUG) {
            return;
        }

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);
        LOG.info("[{}] GUI '{}' ({} container slots)", phase, hs.getTitle().getString(), cs);

        for (int i = 0; i < cs; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty()) {
                LOG.info("  slot {} -> {}", i, textOf(s));
            }
        }
    }

    /* ---------- dialog screen (widget) helpers ---------- */

    private static List<ClickableWidget> widgets(Screen screen) {
        List<ClickableWidget> out = new ArrayList<>();
        collectWidgets(screen.children(), out);
        return out;
    }

    private static void collectWidgets(List<? extends Element> elements, List<ClickableWidget> out) {
        for (Element e : elements) {
            if (e instanceof ClickableWidget w && !out.contains(w)) {
                out.add(w);
            }
            if (e instanceof ParentElement p) {
                collectWidgets(p.children(), out);
            }
        }
    }

    private static TextFieldWidget firstTextField(List<ClickableWidget> ws) {
        for (ClickableWidget w : ws) {
            if (w instanceof TextFieldWidget tf) {
                return tf;
            }
        }
        return null;
    }

    /** Pressable button whose text equals the label (ignores case). */
    private static ClickableWidget button(List<ClickableWidget> ws, String label) {
        for (ClickableWidget w : ws) {
            if (w instanceof PressableWidget
                    && w.getMessage().getString().trim().equalsIgnoreCase(label)) {
                return w;
            }
        }
        return null;
    }

    /** The "Spruce Log" result button (not stripped logs, not "Spruce Logs" labels). */
    private static ClickableWidget spruceLogButton(List<ClickableWidget> ws) {
        for (ClickableWidget w : ws) {
            if (!(w instanceof PressableWidget)) {
                continue;
            }
            String t = w.getMessage().getString().trim().toLowerCase();
            if (t.endsWith("spruce log") && !t.contains("stripped")) {
                return w;
            }
        }
        return null;
    }

    /*
     * Simulates a left click in the middle of the widget.
     * This is the only version-sensitive call (written for Minecraft 1.21.9+).
     */
    private static void pressButton(ClickableWidget w) {
        double x = w.getX() + w.getWidth() / 2.0;
        double y = w.getY() + w.getHeight() / 2.0;
        w.mouseClicked(new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
    }

    private static void dumpWidgets(Screen screen, List<ClickableWidget> ws) {
        if (!DEBUG || screen == lastWidgetScreen) {
            return;
        }
        lastWidgetScreen = screen;
        LOG.info("[ORDER step {}] screen {} widgets:", orderStep, screen.getClass().getSimpleName());
        for (ClickableWidget w : ws) {
            LOG.info("  {} '{}'", w.getClass().getSimpleName(), w.getMessage().getString());
        }
    }

    /* ---------- generic slot-range helpers (any ScreenHandler) ---------- */

    private static int countFree(ScreenHandler h, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (h.getSlot(i).getStack().isEmpty()) n++;
        }
        return n;
    }

    private static int countOf(ScreenHandler h, Item item, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    private static int stacksOf(ScreenHandler h, Item item, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (h.getSlot(i).getStack().isOf(item)) n++;
        }
        return n;
    }

    private static int findIn(ScreenHandler h, Item item, int from, int to) {
        for (int i = from; i < to; i++) {
            if (h.getSlot(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private static boolean putCursorAway(MinecraftClient c, ScreenHandler h, int from, int to) {
        for (int i = from; i < to; i++) {
            if (h.getSlot(i).getStack().isEmpty()) {
                click(c, h, i, SlotActionType.PICKUP);
                return true;
            }
        }
        return false;
    }

    /* ---------- player inventory (PlayerScreenHandler indexes) ---------- */

    private static int count(MinecraftClient c, Item item) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        int n = 0;
        for (int i = 1; i <= 4; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (s.isOf(item)) n += s.getCount();
        }
        for (int i = 9; i <= 45; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static int countEmptySlots(MinecraftClient c) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            if (h.getSlot(i).getStack().isEmpty()) n++;
        }
        return n;
    }
}
