package com.example.sprucefast;

import com.donututilities.client.PriceApi;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Cycle: /orders (buy 576 spruce logs @ 53 each) -> wait -> collect
 *        -> walk onto the DEEPSLATE block -> crafting table (logs -> planks -> slabs),
 *           slabs are dropped DIRECTLY from the result slot (Ctrl+Q) onto the COBBLESTONE area
 *        -> walk onto the COBBLESTONE block -> /sell until no slabs are left
 *        -> walk back onto the deepslate block -> repeat.
 *
 * Farm layout (found automatically within LAYOUT_RADIUS blocks):
 *   crafting table  - nearest one to the player when the mod is started
 *   deepslate block - the one nearest to the table  (player stands here to craft + throw)
 *   cobblestone     - the one nearest to the table  (slabs land here, player stands here to sell)
 *
 * BACKSPACE = start / stop.
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
    private static final boolean USE_RECIPE_BOOK = true; // fill the grid with ONE recipe-book click (falls back to manual loading)
    private static final boolean DIRECT_LOAD = true; // planks go straight from the inventory into the crafting grid (no hotbar stopover)
    private static final boolean DEBUG     = true;  // dumps every GUI to logs/latest.log

    /* ---------------- FARM LAYOUT ---------------- */
    private static final Block STAND_BLOCK = Blocks.DEEPSLATE;    // stand here to use the crafting table + throw slabs
    private static final Block DROP_BLOCK  = Blocks.COBBLESTONE;  // slabs are thrown here; stand here to sell
    private static final int    LAYOUT_RADIUS = 16;   // search radius (blocks) for table / deepslate / cobblestone
    private static final double TABLE_REACH   = 4.4;  // max eye-to-table distance when standing on the deepslate
    private static final float  THROW_PITCH_TWEAK = 0.0f; // add degrees if slabs land short (-) or long (+)... see notes

    /* ---------------- BUCKET MODE (key: DELETE) ---------------- */
    private static final int    BUCKET_ORDER_AMOUNT   = 144;   // 9 stacks of 16 empty buckets
    private static final int    BUCKET_ORDER_PRICE    = 1000;  // per empty bucket ("1K")
    private static final int    BUCKET_MIN_FREE_SLOTS = 12;    // water buckets don't stack
    private static final int    MAX_DISPENSERS        = 9;     // use the nearest N dispensers
    private static final double DISPENSER_REACH       = 4.3;   // max eye-to-dispenser distance
    private static final int    STUCK_BUCKETS         = 9;     // buckets that stay looping inside the dispensers
    private static final int    MIN_LIST_PRICE        = 2000;  // never list below this
    private static final int    MAX_LIST_PRICE        = 20000; // never list above this
    private static final double UNDERCUT              = 0.98;  // list 2% below the cheapest listing (5K -> 4.9K)
    private static final int    PRICE_REFRESH_MS      = 20000; // re-check the Market Tracking cache every 20 s
    private static final int    BUCKET_IDLE_TICKS     = 20 * 90; // no water bucket for 90 s -> finish
    private static final int    ORDER_POLL_BUCKET     = 200;   // check the bucket order every 10 s

    /* ---------------- TIMING (ticks) ---------------- */
    private static final int OPEN_WAIT     = 10; // wait after the crafting table opens before the first click
    private static final int CLICK_GAP     = 2;  // ticks between queued inventory clicks
    private static final int SETTLE        = 5;  // wait after a craft click before checking the result
    private static final int DIALOG_SETTLE = 2;  // a fresh dialog must exist this long before we click
    private static final int PRESS_RETRY   = 15; // ticks before re-clicking the same dialog

    /* ========================================== */

    private static final Logger LOG = LoggerFactory.getLogger("SpruceFast");

    private enum Phase {
        IDLE,
        ORDER_CMD, ORDER_GUI, ORDER_WAIT,
        COLLECT_CMD, COLLECT_GUI,
        TABLE_OPEN, TABLE,
        GO_DROP, PICKUP_WAIT,
        SELL_CMD, SELL_GUI,
        B_DISP_OPEN, B_DISP_GUI,
        B_SELL_FIND, B_SELL_CMD, B_SELL_DIALOG
    }

    private enum Mode { SPRUCE, BUCKET }

    private static KeyBinding startKey;
    private static KeyBinding bucketKey;

    private static Phase phase = Phase.IDLE;
    private static int cooldown = 0;
    private static int phaseTicks = 0;
    private static int tickCounter = 0;

    private static String pendingChat = null;

    private static volatile boolean orderFilled = false;

    private static Mode mode = Mode.SPRUCE;
    private static Item jobItem = Items.SPRUCE_LOG;       // what we order / collect
    private static String jobSearch = "Spruce logs";      // text typed in the search box
    private static int jobAmount = 576;
    private static int jobPrice = 53;
    private static int sellAttempts = 0;

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
    private static int noProgress = 0;
    private static int gridReturns = 0;
    private static int tableAimStage = 0;
    private static boolean droppedAny = false;
    private static int tableSeenSync = -1;
    private static int tableReadyAt = 0;
    private static int tableCloses = 0;
    private static int foreignCount = 0;
    private static final ArrayDeque<Runnable> clickQueue = new ArrayDeque<>();
    private static int lastGridPlanks = -1;
    private static boolean recipeBookFailed = false;
    private static boolean lastWasFill = false;
    private static int recipeFillStreak = 0;
    private static final Map<Item, NetworkRecipeId> recipeCache = new HashMap<>();
    private static int pickupLastCount = -1;
    private static int pickupStable = 0;

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

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("spruce_fast", "main"));

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.spruce_fast.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, category)
        );

        bucketKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.spruce_fast.bucket", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, category)
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
                startSpruce(c);
            } else {
                stop(c, "Stopped.");
            }
        }

        while (bucketKey.wasPressed()) {
            if (c.player == null) {
                return;
            }
            if (phase == Phase.IDLE) {
                startBucket(c);
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
            case TABLE_OPEN   -> tableOpen(c);
            case TABLE        -> table(c);
            case GO_DROP      -> goDrop(c);
            case PICKUP_WAIT  -> pickupWait(c);
            case SELL_CMD     -> sellCmd(c);
            case SELL_GUI     -> sellGui(c);
            case B_DISP_OPEN  -> dispOpen(c);
            case B_DISP_GUI   -> dispGui(c);
            case B_SELL_FIND  -> bucketSellFind(c);
            case B_SELL_CMD   -> bucketSellCmd(c);
            case B_SELL_DIALOG -> bucketSellDialog(c);
            default -> { }
        }
    }

    private static void startSpruce(MinecraftClient c) {

        if (countEmptySlots(c) < REQUIRED_FREE_SLOTS) {
            info(c, "Empty your inventory first (need " + REQUIRED_FREE_SLOTS + " free slots).");
            return;
        }

        if (!resolveLayout(c)) {
            return;
        }

        mode = Mode.SPRUCE;
        jobItem = Items.SPRUCE_LOG;
        jobSearch = ORDER_ITEM_NAME;
        jobAmount = TARGET_LOGS;
        jobPrice = PRICE_PER_LOG;
        sellAttempts = 0;

        collectedTotal = 0;
        tableRetries = 0;
        tableCloses = 0;
        foreignCount = 0;
        droppedAny = false;
        tableAimStage = 0;
        recipeBookFailed = false;
        lastWasFill = false;
        recipeFillStreak = 0;
        recipeCache.clear();
        pendingChat = null;

        info(c, "ON - ordering " + TARGET_LOGS + " spruce logs @ " + PRICE_PER_LOG);
        setPhase(Phase.ORDER_CMD);
    }

    private static void stop(MinecraftClient c, String reason) {
        phase = Phase.IDLE;
        releaseKeys(c);
        pendingChat = null;
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

        if (phase != Phase.ORDER_GUI && phase != Phase.ORDER_WAIT) {
            return;
        }

        // "<player> delivered you ..." or "Your <item> order ... filled/completed/ready"
        if (msg.contains("delivered")
                || (msg.contains("order")
                    && (msg.contains("filled") || msg.contains("completed")
                        || msg.contains("fulfilled") || msg.contains("ready")
                        || msg.contains("collect")))) {
            orderFilled = true;
        }
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
                    && !h.getSlot(0).getStack().isOf(jobItem)) {
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

            tf.setText(String.valueOf(jobPrice));
            pressButton(b);

        } else if (tf != null && (b = button(ws, "next")) != null) {

            tf.setText(String.valueOf(jobAmount));
            pressButton(b);

        } else if (tf != null && title.contains("result")) {

            b = jobResultButton(ws);
            if (b == null) {
                return;
            }
            pressButton(b);

        } else if (tf != null && (b = button(ws, "search")) != null) {

            tf.setText(jobSearch);
            pressButton(b);

        } else {
            return;
        }

        lastPressTick = tickCounter;
    }

    private static void orderWait(MinecraftClient c) {

        if (orderFilled && (mode == Mode.SPRUCE || phaseTicks >= 40)) {
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

        if (phaseTicks > 0 && phaseTicks % (mode == Mode.BUCKET ? ORDER_POLL_BUCKET : POLL_TICKS) == 0) {
            setPhase(Phase.COLLECT_CMD);
        }
    }

    /* ======================================================== */
    /*                        COLLECTING                        */
    /* ======================================================== */

    private static void collectCmd(MinecraftClient c) {
        logsAtCollectStart = count(c, jobItem);
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

        int gained = count(c, jobItem) - logsAtCollectStart;

        if (gained >= jobAmount - collectedTotal) {
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

            if (quickMoveAllNow(c, h, 0, cs, jobItem)) {
                movedAny = true;
            } else if (movedAny) {
                finishCollect(c, count(c, jobItem) - logsAtCollectStart);
            }

            return;
        }

        int s;

        if (title.contains("edit order")) {
            s = find(h, cs, "collect");
        } else if (title.contains("your orders")) {
            s = findItem(h, cs, jobItem);
            // bucket orders: only claim once EVERYTHING has been delivered
            if (s != -1 && mode == Mode.BUCKET && !fullyDelivered(h.getSlot(s).getStack())) {
                closeScreens(c);
                info(c, "Order not complete yet - waiting...");
                setPhase(Phase.ORDER_WAIT);
                return;
            }
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
        info(c, "Collected " + collectedTotal + "/" + jobAmount + ".");

        if (mode == Mode.BUCKET) {
            beginDispensers(c, false);
            return;
        }

        tableAimStage = 0;
        setPhase(Phase.TABLE_OPEN);
        cooldown = 6;
    }

    /* ======================================================== */
    /*       CRAFTING TABLE (stand on deepslate, throw slabs)   */
    /* ======================================================== */

    /*
     * CraftingScreenHandler slots:
     *   0 = result, 1-9 = grid, 10-36 = inventory, 37-45 = hotbar
     */
    private static final int T_INV_FROM = 10;
    private static final int T_INV_TO   = 46;

    private static void tableOpen(MinecraftClient c) {

        // Nothing left to craft -> go sell what is in the inventory / on the ground.
        if (count(c, Items.SPRUCE_LOG) == 0 && count(c, Items.SPRUCE_PLANKS) < 3) {
            releaseKeys(c);
            if (count(c, Items.SPRUCE_SLAB) > 0 || droppedAny) {
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

        noProgress = 0;
        gridReturns = 0;
        lastGridPlanks = -1;
        tableSeenSync = -1;
        setPhase(Phase.TABLE);
    }

    /*
     * Everything happens inside the REAL crafting table (server-side container):
     *   logs   -> planks  (1 log stack in the grid, shift-click the result -> planks go to the inventory)
     *   planks -> slabs   (3 plank stacks in the bottom row, Ctrl+Q on the RESULT slot: the server crafts
     *                      and drops every slab straight away - they never touch the inventory)
     *
     * Stacks are moved with hotbar SWAP clicks (no mouse cursor). Clicks are queued and sent
     * ONE per CLICK_GAP ticks, after a short wait when the table opens, so the server and
     * anti-cheat see human-like inventory activity.
     */
    private static void table(MinecraftClient c) {

        if (phaseTicks > 12000) {
            stop(c, "Crafting timed out.");
            return;
        }

        if (!(c.player.currentScreenHandler instanceof CraftingScreenHandler h)) {

            if (tableSeenSync != -1) {
                // It was open and now it isn't: something closed it (items in the grid go back to the hotbar).
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

        // Fresh table screen -> give the server time before touching anything.
        if (tableSeenSync != h.syncId) {
            tableSeenSync = h.syncId;
            tableReadyAt = tickCounter + OPEN_WAIT;
            // face the cobblestone so Ctrl+Q throws land on it
            c.player.setYaw(throwYaw);
            c.player.setPitch(throwPitch);
            clickQueue.clear();
            dumpTable(h, "table opened");
        }

        if (tickCounter < tableReadyAt) {
            return;
        }

        // Queued clicks go out one at a time.
        if (!clickQueue.isEmpty()) {
            clickQueue.poll().run();
            cooldown = clickQueue.isEmpty() ? SETTLE : CLICK_GAP;
            return;
        }

        // We never use the cursor, but if something is stuck on it, put it away or drop it.
        if (!h.getCursorStack().isEmpty()) {
            if (!putCursorAway(c, h, T_INV_FROM, T_INV_TO)) {
                c.interactionManager.clickSlot(h.syncId, -999, 0, SlotActionType.PICKUP, c.player);
            }
            cooldown = SETTLE;
            return;
        }

        /*
         * A) (Safety) Slabs that ended up in the inventory and the inventory is getting full
         *    -> Ctrl+Q (drop the WHOLE stack) on every slab stack. Normally slabs are dropped
         *    straight from the result slot (see B) and never get here.
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

        /*
         * B) Something in the grid -> craft it.
         *      logs   : shift-click the result (planks go into the inventory)
         *      planks : Ctrl+Q on the result slot (slabs are crafted and DROPPED directly)
         */
        int gridCount = 0;
        boolean gridHas = false;
        boolean gridHasPlanks = false;

        for (int i = 1; i <= 9; i++) {

            ItemStack st = h.getSlot(i).getStack();

            if (st.isEmpty()) {
                continue;
            }

            if (!st.isOf(Items.SPRUCE_PLANKS) && !st.isOf(Items.SPRUCE_LOG)) {

                // A foreign item: report it, send it back to the inventory and carry on.
                String name = st.getName().getString() + " x" + st.getCount() + " (grid slot " + i + ")";
                LOG.warn("Foreign item in crafting grid: {}", name);
                dumpTable(h, "foreign item");

                if (++foreignCount > 5) {
                    stop(c, "Unexpected item in the crafting grid: " + name);
                    return;
                }

                info(c, "Moving unexpected item out of the grid: " + name);
                q(c, h, i, 0, SlotActionType.QUICK_MOVE);
                return;
            }

            gridHas = true;
            gridCount += st.getCount();

            if (st.isOf(Items.SPRUCE_PLANKS)) {
                gridHasPlanks = true;
            }
        }

        if (gridHas) {

            lastWasFill = false;   // the grid is filled, so the last fill (if any) worked
            recipeFillStreak = 0;

            if (lastGridPlanks == -1) {
                lastGridPlanks = gridCount;
            } else if (gridCount < lastGridPlanks) {
                lastGridPlanks = gridCount;      // progress
                noProgress = 0;
                gridReturns = 0;
            } else if (++noProgress >= 4) {

                dumpTable(h, "no crafting progress");

                if (++gridReturns > 3) {
                    stop(c, "Crafting makes no progress (inventory full, or the server isn't crafting). Check logs/latest.log.");
                    return;
                }

                // give the grid contents back to the inventory and try again
                for (int i = 1; i <= 9; i++) {
                    if (!h.getSlot(i).getStack().isEmpty()) {
                        q(c, h, i, 0, SlotActionType.QUICK_MOVE);
                    }
                }

                noProgress = 0;
                lastGridPlanks = -1;
                return;
            }

            if (gridHasPlanks) {
                // Ctrl+Q on the result slot: the server crafts and drops slab after slab until
                // the planks in the grid are used up. Nothing goes through the inventory.
                c.interactionManager.clickSlot(h.syncId, 0, 1, SlotActionType.THROW, c.player);
                droppedAny = true;
            } else {
                click(c, h, 0, SlotActionType.QUICK_MOVE);
            }

            cooldown = SETTLE;
            return;
        }

        if (lastGridPlanks != -1) {
            gridReturns = 0; // grid went empty = it was crafted
        }

        lastGridPlanks = -1;
        noProgress = 0;

        /*
         * C) Grid empty -> load the next thing, or finish.
         */
        boolean recipeOk = USE_RECIPE_BOOK && !recipeBookFailed;
        boolean haveLogs = findIn(h, Items.SPRUCE_LOG, T_INV_FROM, T_INV_TO) != -1;
        int planksStacks = stacksOf(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);
        int planksTotal = countOf(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);
        // the recipe book spreads any amount of planks over the 3 cells; manual loading needs 3 stacks
        boolean havePlanks = recipeOk ? planksTotal >= 3 : planksStacks >= 3;
        int freeNow = countFree(h, T_INV_FROM, T_INV_TO);

        // logs first (ONE stack in the top-left cell) - unless the inventory is getting full
        // of planks, then turn planks into slabs (and drop them) to make room first.
        if (haveLogs && (freeNow >= 5 || !havePlanks)) {
            if (!recipeFill(c, h, Items.SPRUCE_PLANKS)) {
                queueLoad(c, h, Items.SPRUCE_LOG, new int[] {1});
            }
            return;
        }

        // planks -> slabs
        if (havePlanks) {
            if (recipeFill(c, h, Items.SPRUCE_SLAB)) {
                return;
            }
            if (planksStacks >= 3) {
                queueLoad(c, h, Items.SPRUCE_PLANKS, new int[] {7, 8, 9});   // three stacks side by side
                return;
            }
        }

        // nothing left to craft -> walk to the cobblestone and sell
        c.player.closeHandledScreen();
        info(c, "Crafting finished. Walking to the cobblestone to sell...");
        setPhase(Phase.GO_DROP);
        cooldown = 3;
    }

    /* ---------- recipe book: fill the whole grid with ONE click ---------- */

    /**
     * Presses the recipe in the recipe book with "craft all" (like shift-clicking it): the server
     * moves the ingredients from the inventory into the grid itself. Returns false if the recipe
     * book can't be used (then the caller loads the grid by hand).
     */
    private static boolean recipeFill(MinecraftClient c, ScreenHandler h, Item result) {

        if (!USE_RECIPE_BOOK || recipeBookFailed) {
            return false;
        }

        // The last fill didn't put anything in the grid -> after a few tries give up on the recipe book.
        if (lastWasFill && ++recipeFillStreak >= 3) {
            recipeBookFailed = true;
            LOG.warn("Recipe book fill did nothing {} times - switching to manual loading.", recipeFillStreak);
            info(c, "Recipe book not working here - loading the grid by hand.");
            return false;
        }

        NetworkRecipeId id = recipeCache.get(result);

        if (id == null) {
            id = findRecipe(c, result);
            if (id == null) {
                recipeBookFailed = true;
                LOG.warn("No recipe for {} in the recipe book (not unlocked?) - switching to manual loading.", result);
                info(c, "Recipe not in the recipe book - loading the grid by hand.");
                return false;
            }
            recipeCache.put(result, id);
        }

        c.interactionManager.clickRecipe(h.syncId, id, true);   // true = craft all (fill the grid with as much as possible)
        lastWasFill = true;
        cooldown = SETTLE * 2;                                  // wait for the server to fill the grid
        return true;
    }

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
     * Queues "move one whole stack of `item` into each of the given cells" without the cursor:
     * stacks already in the hotbar are swapped straight into the cell; others are first swapped
     * into a free hotbar slot (an EMPTY one if possible, so nothing foreign can end up in the grid).
     */
    private static void queueLoad(MinecraftClient c, ScreenHandler h, Item item, int[] cells) {

        List<Integer> srcs = new ArrayList<>();

        for (int i = 37; i < 46 && srcs.size() < cells.length; i++) {
            if (h.getSlot(i).getStack().isOf(item)) srcs.add(i);
        }
        for (int i = 10; i < 37 && srcs.size() < cells.length; i++) {
            if (h.getSlot(i).getStack().isOf(item)) srcs.add(i);
        }

        boolean[] hbUsed = new boolean[9];
        for (int src : srcs) {
            if (src >= 37) hbUsed[src - 37] = true;
        }

        for (int k = 0; k < srcs.size(); k++) {

            int src = srcs.get(k);

            if (src >= 37) {

                // already in the hotbar: ONE swap straight into the cell
                q(c, h, cells[k], src - 37, SlotActionType.SWAP);

            } else if (DIRECT_LOAD) {

                // main inventory -> crafting cell directly: pick the stack up, put it in the cell
                q(c, h, src, 0, SlotActionType.PICKUP);
                q(c, h, cells[k], 0, SlotActionType.PICKUP);

            } else {

                // old way: hotbar stopover (set DIRECT_LOAD = false if the direct way misbehaves)
                int hb = freeHotbar(h, hbUsed);
                hbUsed[hb] = true;
                q(c, h, src, hb, SlotActionType.SWAP);
                q(c, h, cells[k], hb, SlotActionType.SWAP);
            }
        }
    }

    private static int freeHotbar(ScreenHandler h, boolean[] used) {
        for (int hb = 0; hb < 9; hb++) {
            if (!used[hb] && h.getSlot(37 + hb).getStack().isEmpty()) return hb;
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
        LOG.info("[TABLE] {} (syncId {})", why, h.syncId);
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

        if (sellableCount(c) == 0) {
            if (mode == Mode.BUCKET) {
                afterSell(c);
            } else {
                enterPickupWait();
            }
            return;
        }

        movedPlanks = false;
        confirmedSell = false;
        c.player.networkHandler.sendChatCommand(SELL_COMMAND);
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
            if (mode == Mode.BUCKET) {
                quickMoveAllNow(c, h, cs, h.slots.size(), Items.BUCKET);
                quickMoveAllNow(c, h, cs, h.slots.size(), Items.WATER_BUCKET);
            } else {
                quickMoveAllNow(c, h, cs, h.slots.size(), Items.SPRUCE_SLAB);
                quickMoveAllNow(c, h, cs, h.slots.size(), Items.SPRUCE_PLANKS);
            }
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

        if (mode == Mode.BUCKET) {
            if (sellableCount(c) > 0 && ++sellAttempts < 3) {
                setPhase(Phase.SELL_CMD);      // the sell GUI was too small - sell the rest
            } else {
                afterSell(c);
            }
            return;
        }

        enterPickupWait();
    }

    /** Nothing more to sell: craft the logs that are left, start the next order, or finish. */
    private static void afterSell(MinecraftClient c) {

        cooldown = 10;

        if (mode == Mode.BUCKET) {
            sellAttempts = 0;

            if (!LOOP) {
                stop(c, "Done.");
                return;
            }

            if (countEmptySlots(c) < BUCKET_MIN_FREE_SLOTS) {
                stop(c, "Not enough free slots for the next batch.");
                return;
            }

            collectedTotal = 0;
            info(c, "Cycle complete. Ordering the next " + jobAmount + " buckets...");
            setPhase(Phase.ORDER_CMD);
            return;
        }

        if (count(c, Items.SPRUCE_LOG) > 0) {
            tableAimStage = 0;
            setPhase(Phase.TABLE_OPEN);   // walks back onto the deepslate block
            return;
        }

        if (collectedTotal >= jobAmount) {

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
    /*       BUCKET MODE: order -> dispensers -> /ah sell       */
    /* ======================================================== */

    private static List<BlockPos> dispensers = new ArrayList<>();
    private static int[] dispAssign = new int[0];
    private static int dispIdx = 0;
    private static boolean dispEmptyMode = false;
    private static int dispStage = 0;
    private static boolean dispActed = false;
    private static int dispRetries = 0;

    private static int bucketSold = 0;
    private static int bucketSellTarget = 0;
    private static int bucketIdle = 0;
    private static int bucketFail = 0;

    private static volatile long bucketPrice = -1;       // price we list water buckets for
    private static volatile long lowestMarketPrice = -1; // lowest current water-bucket market price
    private static volatile long lastPriceFetchMs = 0;
    private static volatile String priceError = "";

    private static void startBucket(MinecraftClient c) {

        if (countEmptySlots(c) < BUCKET_MIN_FREE_SLOTS) {
            info(c, "Free up your inventory first (need " + BUCKET_MIN_FREE_SLOTS + " free slots).");
            return;
        }

        mode = Mode.BUCKET;
        jobItem = Items.BUCKET;
        jobSearch = "Bucket";
        jobAmount = BUCKET_ORDER_AMOUNT;
        jobPrice = BUCKET_ORDER_PRICE;

        collectedTotal = 0;
        sellAttempts = 0;
        bucketPrice = -1;
        lowestMarketPrice = -1;
        lastPriceFetchMs = 0;
        priceError = "";
        pendingChat = null;

        refreshMarketPrice(c);   // use Market Tracking; it may already have a cached price

        info(c, "BUCKET MODE ON - ordering " + jobAmount + " buckets @ " + jobPrice);
        setPhase(Phase.ORDER_CMD);
    }

    /** The "Spruce Log" result button for spruce mode, the plain "Bucket" button for bucket mode. */
    private static ClickableWidget jobResultButton(List<ClickableWidget> ws) {

        if (mode == Mode.SPRUCE) {
            return spruceLogButton(ws);
        }

        for (ClickableWidget w : ws) {
            if (!(w instanceof PressableWidget)) {
                continue;
            }
            // drop any leading icon character, then it must be exactly "bucket" (not Water Bucket etc.)
            String t = w.getMessage().getString().trim().toLowerCase(Locale.ROOT).replaceAll("^[^a-z0-9]+", "");
            if (t.equals("bucket")) {
                return w;
            }
        }

        return null;
    }

    /** "144/144 Delivered" / "Order Completed" in the order's tooltip. */
    private static boolean fullyDelivered(ItemStack stack) {
        String t = textOf(stack);
        return t.contains(jobAmount + "/" + jobAmount) || t.contains("order completed");
    }

    private static int sellableCount(MinecraftClient c) {
        if (mode == Mode.BUCKET) {
            return count(c, Items.BUCKET) + count(c, Items.WATER_BUCKET);
        }
        return count(c, Items.SPRUCE_SLAB);
    }

    /* ---------- dispensers ---------- */

    private static List<BlockPos> findDispensers(MinecraftClient c) {

        BlockPos base = c.player.getBlockPos();
        Vec3d eye = c.player.getEyePos();
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {

                    BlockPos p = base.add(dx, dy, dz);

                    if (c.world.getBlockState(p).isOf(Blocks.DISPENSER)
                            && eye.distanceTo(Vec3d.ofCenter(p)) <= DISPENSER_REACH) {
                        found.add(p);
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));

        return found.size() > MAX_DISPENSERS ? new ArrayList<>(found.subList(0, MAX_DISPENSERS)) : found;
    }

    private static int bucketStacks(MinecraftClient c) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        int n = 0;
        for (int i = 9; i <= 45; i++) {
            if (h.getSlot(i).getStack().isOf(Items.BUCKET)) n++;
        }
        return n;
    }

    /** empty = false: put the empty-bucket stacks into the dispensers. empty = true: take everything out. */
    private static void beginDispensers(MinecraftClient c, boolean empty) {

        dispEmptyMode = empty;

        if (!empty) {

            dispensers = findDispensers(c);

            if (dispensers.isEmpty()) {
                stop(c, "No dispenser within reach (" + DISPENSER_REACH + " blocks).");
                return;
            }

            // spread the stacks over the dispensers (9 stacks + 9 dispensers = one each)
            int stacks = bucketStacks(c);
            dispAssign = new int[dispensers.size()];
            for (int i = 0; i < stacks; i++) {
                dispAssign[i % dispensers.size()]++;
            }

            bucketSellTarget = Math.max(0, jobAmount - STUCK_BUCKETS);
            LOG.info("Dispensers: {} ({} bucket stacks) sell target {}", dispensers, stacks, bucketSellTarget);
        }

        dispIdx = 0;
        dispStage = 0;
        dispActed = false;
        dispRetries = 0;

        setPhase(Phase.B_DISP_OPEN);
        cooldown = empty ? 0 : 4;
    }

    private static void dispOpen(MinecraftClient c) {

        if (phaseTicks > 200) {
            stop(c, "Could not open dispenser #" + (dispIdx + 1) + ".");
            return;
        }

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
            return;
        }

        BlockPos pos = dispensers.get(dispIdx);

        if (!c.world.getBlockState(pos).isOf(Blocks.DISPENSER)) {
            nextDispenser(c);
            return;
        }

        Vec3d center = Vec3d.ofCenter(pos);

        // Stage 0: look at it (servers check where we look), stage 1: use it.
        if (dispStage == 0) {
            aimAt(c, center);
            dispStage = 1;
            cooldown = 3;
            return;
        }

        dispStage = 0;

        Vec3d eye = c.player.getEyePos();
        Vec3d end = eye.add(c.player.getRotationVec(1.0f).multiply(5.0));

        BlockHitResult hit = c.world.raycast(new RaycastContext(
            eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, c.player));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            Direction side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            Vec3d on = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            hit = new BlockHitResult(on, side, pos, false);
        }

        c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, hit);
        c.player.swingHand(Hand.MAIN_HAND);

        dispActed = false;
        setPhase(Phase.B_DISP_GUI);
    }

    private static void dispGui(MinecraftClient c) {

        HandledScreen<?> hs = openContainer(c);

        if (hs == null) {
            if (phaseTicks > 40) {
                if (++dispRetries > 3) {
                    stop(c, "Dispenser #" + (dispIdx + 1) + " will not open.");
                } else {
                    dispStage = 0;
                    setPhase(Phase.B_DISP_OPEN);
                }
            }
            return;
        }

        dump(hs);

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);

        if (!dispActed) {

            if (phaseTicks < 3) {
                return; // let the contents arrive
            }

            if (!dispEmptyMode) {
                // put this dispenser's share of bucket stacks in (shift-click)
                int left = dispAssign[dispIdx];
                for (int i = cs; i < h.slots.size() && left > 0; i++) {
                    if (h.getSlot(i).getStack().isOf(Items.BUCKET)) {
                        click(c, h, i, SlotActionType.QUICK_MOVE);
                        left--;
                    }
                }
            } else {
                // take everything out (shift-click every filled dispenser slot)
                for (int i = 0; i < cs; i++) {
                    if (!h.getSlot(i).getStack().isEmpty()) {
                        click(c, h, i, SlotActionType.QUICK_MOVE);
                    }
                }
            }

            dispActed = true;
            cooldown = 3;
            return;
        }

        c.player.closeHandledScreen();
        dispRetries = 0;
        nextDispenser(c);
    }

    private static void nextDispenser(MinecraftClient c) {

        dispIdx++;
        dispStage = 0;
        dispActed = false;

        if (dispIdx < dispensers.size()) {
            setPhase(Phase.B_DISP_OPEN);
            cooldown = 3;
            return;
        }

        if (dispEmptyMode) {
            info(c, "Dispensers emptied. Selling the leftover buckets with /sell...");
            sellAttempts = 0;
            setPhase(Phase.SELL_CMD);
            cooldown = 5;
            return;
        }

        bucketSold = 0;
        bucketIdle = 0;
        bucketFail = 0;
        info(c, "Dispensers loaded. Selling water buckets on the AH...");
        setPhase(Phase.B_SELL_FIND);
    }

    /* ---------- selling water buckets: hold -> /ah sell <price> -> Yes ---------- */

    private static int waterBucketSlot(MinecraftClient c) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        for (int i = 36; i <= 44; i++) {
            if (h.getSlot(i).getStack().isOf(Items.WATER_BUCKET)) return i;   // hotbar first
        }
        for (int i = 9; i <= 35; i++) {
            if (h.getSlot(i).getStack().isOf(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    private static void bucketSellFind(MinecraftClient c) {

        if (System.currentTimeMillis() - lastPriceFetchMs > PRICE_REFRESH_MS) {
            refreshMarketPrice(c);
        }

        if (bucketPrice < 0) {
            if (phaseTicks > 400) {
                stop(c, "No water-bucket price from Market Tracking: "
                    + (priceError.isEmpty() ? "no price available yet" : priceError));
            }
            return;
        }

        if (bucketSold >= bucketSellTarget) {
            finishBucketSelling(c);
            return;
        }

        int slot = waterBucketSlot(c);

        if (slot == -1) {

            bucketIdle++;

            if (bucketIdle > BUCKET_IDLE_TICKS) {
                if (bucketSold > 0) {
                    finishBucketSelling(c);
                } else {
                    stop(c, "No water buckets arrived for 90 seconds.");
                }
            } else if (bucketIdle % 100 == 0) {
                info(c, "Sold " + bucketSold + "/" + bucketSellTarget + " - waiting for water buckets...");
            }
            return;
        }

        bucketIdle = 0;

        // Hold it: select its hotbar slot, or swap it into the selected one.
        var inv = c.player.getInventory();

        if (slot >= 36 && slot <= 44) {
            inv.setSelectedSlot(slot - 36);
        } else {
            c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId, slot,
                inv.getSelectedSlot(), SlotActionType.SWAP, c.player);
        }

        cooldown = 1;   // the server learns about the new selected slot on the next tick
        setPhase(Phase.B_SELL_CMD);
    }

    private static void bucketSellCmd(MinecraftClient c) {

        if (!c.player.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            if (++bucketFail > 25) {
                stop(c, "Cannot hold the water bucket.");
                return;
            }
            setPhase(Phase.B_SELL_FIND);
            return;
        }

        c.player.networkHandler.sendChatCommand("ah sell " + bucketPrice);

        dialogScreen = null;
        lastPressTick = -1000;
        setPhase(Phase.B_SELL_DIALOG);
    }

    private static void bucketSellDialog(MinecraftClient c) {

        Screen sc = c.currentScreen;

        // We pressed Yes and the dialog is gone -> listed.
        if (dialogScreen != null && lastPressTick > -500 && sc != dialogScreen) {
            bucketSold++;
            bucketFail = 0;
            setPhase(Phase.B_SELL_FIND);
            return;
        }

        if (sc == null || sc instanceof HandledScreen<?>) {
            if (phaseTicks > 40) {
                if (++bucketFail >= 5) {
                    stop(c, "The /ah sell confirmation never appeared (listing limit? price rejected?). Check the chat.");
                } else {
                    setPhase(Phase.B_SELL_FIND);
                }
            }
            return;
        }

        if (sc != dialogScreen) {
            dialogScreen = sc;
            dialogSince = tickCounter;
            lastPressTick = -1000;
        }

        if (phaseTicks > 160) {
            closeScreens(c);
            if (c.currentScreen != null) {
                c.setScreen(null);
            }
            if (++bucketFail >= 5) {
                stop(c, "The sell dialog does not close. Check the chat.");
            } else {
                setPhase(Phase.B_SELL_FIND);
            }
            return;
        }

        if (tickCounter - lastPressTick < 10) {
            return; // pressed - wait for the dialog to close
        }

        ClickableWidget yes = button(widgets(sc), "yes");

        if (yes == null) {
            return;
        }

        pressButton(yes);
        lastPressTick = tickCounter;
    }

    private static void finishBucketSelling(MinecraftClient c) {
        info(c, "Sold " + bucketSold + " water buckets. Emptying the dispensers...");
        beginDispensers(c, true);
    }

    /* ---------- Market Tracking price source ---------- */

    /**
     * Refreshes Donut Utilities' Market Tracking cache and reads the current
     * lowest water-bucket price from PriceApi.
     *
     * Example:
     *   market lowest = 5000
     *   5000 * 0.98 = 4900
     *   bucketPrice = 4900
     *
     * PriceApi itself controls the network refresh interval and keeps its
     * previous cache when the price service is temporarily unavailable.
     */
    private static void refreshMarketPrice(MinecraftClient c) {

        lastPriceFetchMs = System.currentTimeMillis();

        try {
            // This is the Market Tracking source. No DonutSMP API key is used here.
            PriceApi.refreshIfStale(c);

            PriceApi.Entry waterBucket = null;

            for (PriceApi.Entry entry : PriceApi.snapshot().values()) {
                String key = entry.key() == null
                    ? ""
                    : entry.key().toLowerCase(Locale.ROOT);

                String name = entry.name() == null
                    ? ""
                    : entry.name().toLowerCase(Locale.ROOT);

                if (key.equals("water_bucket")
                        || key.equals("minecraft:water_bucket")
                        || name.equals("water bucket")
                        || name.equals("water_bucket")) {
                    waterBucket = entry;
                    break;
                }
            }

            if (waterBucket == null) {
                throw new IllegalStateException(
                    "Market Tracking has no water bucket entry"
                );
            }

            Long lowest = waterBucket.currentMinPrice();

            if (lowest == null || lowest <= 0L) {
                throw new IllegalStateException(
                    "Market Tracking has no current water bucket price"
                );
            }

            long sellPrice = (long) Math.floor(lowest * UNDERCUT);
            sellPrice = Math.max(
                MIN_LIST_PRICE,
                Math.min(MAX_LIST_PRICE, sellPrice)
            );

            lowestMarketPrice = lowest;
            bucketPrice = sellPrice;
            priceError = "";

            LOG.info(
                "[MARKET] Water bucket lowest {} -> listing at {}",
                lowestMarketPrice,
                bucketPrice
            );

            info(c, "Water bucket market: " + lowestMarketPrice
                + " -> selling at " + bucketPrice);

        } catch (Exception e) {
            priceError = e.getMessage() == null
                ? e.toString()
                : e.getMessage();

            LOG.warn(
                "[MARKET] Could not read water bucket price: {}",
                priceError
            );
        }
    }

    /* ======================================================== */
    /*                         HELPERS                          */
    /* ======================================================== */

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
