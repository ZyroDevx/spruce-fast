package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bucket cycle (key: DELETE = start / stop):
 *   1. /orders -> order 144 empty buckets
 *   2. check /orders every 5 seconds
 *   3. collect the order once it is fully delivered
 *   4. put EVERY empty bucket into the nearby dispensers (nothing is sold while this runs)
 *   5. /ah sell every water bucket for 8.7K (opens the inventory and moves water buckets to the hotbar when needed)
 *   6. after 135 sales: take everything out of the dispensers and /sell it
 *   7. loop
 */
public class SpruceFastClient implements ClientModInitializer {

    /* ================= CONFIG ================= */

    private static final String ORDER_COMMAND = "orders";   // no slash
    private static final String SELL_COMMAND  = "sell";     // no slash

    private static final boolean LOOP  = true;  // repeat the whole cycle
    private static final boolean DEBUG = true;  // dumps every GUI to logs/latest.log
    private static final boolean STATUS_CHAT = true; // prints phase changes + every command it sends into YOUR chat

    private static final String JOB_SEARCH = "Bucket";      // text typed in the order search box
    private static final Item   JOB_ITEM   = Items.BUCKET;  // what we order / collect

    private static final int    BUCKET_ORDER_AMOUNT   = 144;   // 9 stacks of 16 empty buckets
    private static final int    BUCKET_ORDER_PRICE    = 1000;  // per empty bucket ("1K")
    private static final int    BUCKET_MIN_FREE_SLOTS = 9;     // 144 buckets = 9 stacks
    private static final int    MAX_DISPENSERS        = 9;     // use the nearest N dispensers
    private static final double DISPENSER_REACH       = 4.3;   // max eye-to-dispenser distance
    private static final String LIST_PRICE            = "7000"; // 8.7K: water buckets are listed with /ah sell 8700
    private static final int    STUCK_BUCKETS         = 9;     // buckets that stay looping inside the dispensers
    private static final int    BUCKET_IDLE_TICKS     = 20 * 90; // no water bucket for 90 s -> finish
    private static final int    MAX_LOAD_PASSES       = 8;     // how many times to go over the dispensers to place every empty bucket
    /* ---------------- USER SPEED CONFIG ----------------
       Lower values = faster. 20 ticks = 1 second. */
    private static final int ORDER_SPEED_TICKS             = 4;   // order GUI actions
    private static final int ORDER_COLLECT_SPEED_TICKS      = 4;   // collect GUI actions
    private static final int DISPENSER_BUCKET_SPEED_TICKS   = 1;   // minimum is 1 tick (0.05s)
    private static final int SELLING_SPEED_TICKS            = 20;  // minimum gap between two /ah sell listings

    private static final int    ORDER_POLL_BUCKET     = 100;  // check the order every 5 s (100 ticks)

    /* ---------------- TIMING (ticks) ---------------- */
    private static final int DIALOG_SETTLE = 6;  // a fresh dialog must exist this long before we click
    private static final int PRESS_RETRY   = 30; // ticks before re-clicking the same dialog

    /* ---------------- ANTI-SPAM ---------------- */
    private static final int COMMAND_GAP_TICKS = 30;  // minimum gap between ANY two commands (1.5 s)
    private static final int LIST_DELAY_TICKS  = SELLING_SPEED_TICKS; // gap between two /ah sell commands
    private static final int CLICK_GAP         = 5;   // inventory click gap
    private static final int SELL_SYNC_WAIT    = 8;   // wait for server slot update before selling

    /* ========================================== */

    private static final Logger LOG = LoggerFactory.getLogger("SpruceFast");

    private enum Phase {
        IDLE,
        ORDER_CMD, ORDER_GUI, ORDER_WAIT,
        COLLECT_CMD, COLLECT_GUI,
        SELL_CMD, SELL_GUI,
        B_PREP_INV, B_DISP_OPEN, B_DISP_GUI,
        B_SELL_FIND, B_SELL_CMD, B_SELL_DIALOG, B_RETURN
    }

    private static KeyBinding bucketKey;

    private static Phase phase = Phase.IDLE;
    private static int cooldown = 0;
    private static int phaseTicks = 0;
    private static int tickCounter = 0;

    private static String pendingChat = null;

    private static int sellAttempts = 0;

    private static int collectedTotal = 0;
    private static int itemsAtCollectStart = 0;

    private static int orderStep = 0;
    private static boolean movedAny = false;
    private static Object dialogScreen = null;
    private static int dialogSince = 0;
    private static int lastPressTick = -1000;
    private static Object lastWidgetScreen = null;
    private static boolean movedItems = false;
    private static boolean confirmedSell = false;

    private static Object lastScreen = null;
    private static String lastClickKey = "";

    @Override
    public void onInitializeClient() {

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("spruce_fast", "main"));

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

        if (bucketKey.wasPressed()) {

            while (bucketKey.wasPressed()) {
                // swallow extra queued presses - they would toggle it straight back off
            }

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

        if (STATUS_CHAT && phaseTicks > 0 && phaseTicks % 200 == 0) {
            chat(c, "still in " + phase + " for " + (phaseTicks / 20) + "s");
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
            case ORDER_CMD     -> orderCmd(c);
            case ORDER_GUI     -> orderGui(c);
            case ORDER_WAIT    -> orderWait(c);
            case COLLECT_CMD   -> collectCmd(c);
            case COLLECT_GUI   -> collectGui(c);
            case SELL_CMD      -> sellCmd(c);
            case SELL_GUI      -> sellGui(c);
            case B_PREP_INV    -> prepBucketInventory(c);
            case B_DISP_OPEN   -> dispOpen(c);
            case B_DISP_GUI    -> dispGui(c);
            case B_SELL_FIND   -> bucketSellFind(c);
            case B_SELL_CMD    -> bucketSellCmd(c);
            case B_SELL_DIALOG -> bucketSellDialog(c);
            case B_RETURN      -> bucketReturn(c);
            default -> { }
        }
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
            chat(c, "STOPPED: " + reason);
        }
    }

    private static void setPhase(Phase p) {
        if (STATUS_CHAT && p != phase) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                chat(mc, "phase -> " + p);
            }
        }
        phase = p;
        phaseTicks = 0;
        lastClickKey = "";
        lastScreen = null;
    }

    private static void releaseKeys(MinecraftClient c) {
        if (c.options != null) {
            c.options.forwardKey.setPressed(false);
            c.options.backKey.setPressed(false);
            c.options.jumpKey.setPressed(false);
        }
    }

    /* ---------- command throttle ---------- */

    private static int lastCommandTick = -1000;

    /** Sends a command only if the last one was long enough ago. Returns false = try again next tick. */
    private static boolean sendCmd(MinecraftClient c, String cmd) {
        return sendCmd(c, cmd, COMMAND_GAP_TICKS);
    }

    /** Sends a command with a custom minimum gap. */
    private static boolean sendCmd(MinecraftClient c, String cmd, int gapTicks) {
        if (tickCounter - lastCommandTick < Math.max(0, gapTicks)) {
            return false;
        }
        lastCommandTick = tickCounter;
        c.player.networkHandler.sendChatCommand(cmd);
        LOG.info("[CMD] /{}", cmd);
        if (STATUS_CHAT) {
            chat(c, "sent /" + cmd);
        }
        return true;
    }

    /** After selling: walk back to where selling started so the dispensers are in reach again. */
    private static void bucketReturn(MinecraftClient c) {

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
        }

        if (strafeOrigin != null && phaseTicks < 400) {

            double dx = strafeOrigin.x - c.player.getX();
            double dz = strafeOrigin.z - c.player.getZ();

            if (Math.sqrt(dx * dx + dz * dz) > 0.4) {
                c.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                c.options.forwardKey.setPressed(true);
                c.options.jumpKey.setPressed(c.player.horizontalCollision);
                return;
            }
        }

        releaseKeys(c);
        cooldown = 10;
        beginDispensers(c, true);
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

    /* ======================================================== */
    /*                      CHAT LISTENER                       */
    /* ======================================================== */

    private static volatile String ahError = null;

    private static void onChat(String msg) {

        // The auction house refusing a listing (listing limit etc.) while we are selling.
        if ((phase == Phase.B_SELL_FIND || phase == Phase.B_SELL_CMD || phase == Phase.B_SELL_DIALOG)
                && (msg.contains("auction") || msg.contains("/ah") || msg.contains("listing"))
                && (msg.contains("limit") || msg.contains("maximum") || msg.contains("too many")
                    || msg.contains("cannot list") || msg.contains("can't list") || msg.contains("max listings"))) {
            ahError = msg;
        }
    }

    /* ======================================================== */
    /*                         ORDERING                         */
    /* ======================================================== */

    private static void orderCmd(MinecraftClient c) {
        if (!sendCmd(c, ORDER_COMMAND)) {
            return;
        }
        orderStep = 0;
        dialogScreen = null;
        setPhase(Phase.ORDER_GUI);
        cooldown = ORDER_SPEED_TICKS;
    }

    /*
     * Walkthrough:
     * 0  /orders chest menu      -> click "Your Orders"
     * 1  Your Orders chest menu  -> click "New Order"
     * 2  Choose Item dialog      -> type "Bucket", press Search
     * 3  Search results          -> press "Bucket"
     * 4  Amount dialog           -> type amount, press Next
     * 5  Price dialog            -> type price, press Review Order
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
                // /orders did not open -> send it again after 3 s
                if (c.currentScreen == null && phaseTicks > 60 && phaseTicks % 60 == 0) {
                    sendCmd(c, ORDER_COMMAND);
                }
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
                    && !h.getSlot(0).getStack().isOf(JOB_ITEM)) {
                s = 0; // "New Order" is the first slot
            }

            if (s != -1) {
                click(c, h, s, SlotActionType.PICKUP);
                orderStep = 2;
                cooldown = ORDER_SPEED_TICKS;
                return;
            }

            s = find(h, cs, "your orders");
            if (s != -1 && clickOnce(c, hs, h, s)) {
                orderStep = 1;
                cooldown = ORDER_SPEED_TICKS;
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
                info(c, "Order created. Checking it every " + (ORDER_POLL_BUCKET / 20) + " s...");
                setPhase(Phase.ORDER_WAIT);   // first check happens after the first poll interval
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

            tf.setText(String.valueOf(BUCKET_ORDER_PRICE));
            pressButton(b);

        } else if (tf != null && (b = button(ws, "next")) != null) {

            tf.setText(String.valueOf(BUCKET_ORDER_AMOUNT));
            pressButton(b);

        } else if (tf != null && title.contains("result")) {

            b = bucketResultButton(ws);
            if (b == null) {
                return;
            }
            pressButton(b);

        } else if (tf != null && (b = button(ws, "search")) != null) {

            tf.setText(JOB_SEARCH);
            pressButton(b);

        } else {
            return;
        }

        lastPressTick = tickCounter;
        cooldown = ORDER_SPEED_TICKS;
    }

    /** Waits, and every ORDER_POLL_BUCKET ticks (5 s) opens /orders to check the order. */
    private static void orderWait(MinecraftClient c) {

        // The server may re-open the /orders menu after creating the order.
        if (openContainer(c) != null) {
            closeScreens(c);
        }

        if (phaseTicks >= ORDER_POLL_BUCKET) {
            setPhase(Phase.COLLECT_CMD);
        }
    }

    /* ======================================================== */
    /*                        COLLECTING                        */
    /* ======================================================== */

    private static final int COLLECT_TIMEOUT = 240;   // ticks for the whole menu walk (4 menus, laggy server)
    private static int lastOrdersCmdTick = -1000;

    // order slots whose "Collect Items" menu was empty (already collected) - skipped from then on
    private static final Set<Integer> doneOrderSlots = new HashSet<>();
    private static int lastOrderSlot = -1;
    private static int collectEmptyTicks = 0;

    private static void collectCmd(MinecraftClient c) {
        if (!sendCmd(c, ORDER_COMMAND)) {
            return;
        }
        itemsAtCollectStart = count(c, JOB_ITEM);
        movedAny = false;
        collectEmptyTicks = 0;
        lastOrdersCmdTick = tickCounter;
        setPhase(Phase.COLLECT_GUI);
    }

    /*
     * Walkthrough (decided by the menu title):
     *   "Orders (Page 1)"         -> click the "Your Orders" chest
     *   "Orders -> Your Orders"   -> click the bucket order once it is fully delivered
     *   "Orders -> Edit Order"    -> click the "Collect" chest
     *   "Orders -> Collect Items" -> shift-click the buckets into the inventory
     */
    private static void collectGui(MinecraftClient c) {

        int gained = count(c, JOB_ITEM) - itemsAtCollectStart;

        if (gained >= BUCKET_ORDER_AMOUNT - collectedTotal) {
            finishCollect(c, gained);
            return;
        }

        if (phaseTicks > COLLECT_TIMEOUT) {
            closeScreens(c);
            info(c, "Collection check timed out - checking the order again in " + (ORDER_POLL_BUCKET / 20) + " seconds.");
            setPhase(Phase.ORDER_WAIT);
            return;
        }

        HandledScreen<?> hs = openContainer(c);

        if (hs == null) {
            // /orders did not open (command swallowed / still closing a screen) -> send it again
            if (c.currentScreen == null && tickCounter - lastOrdersCmdTick > 60
                    && sendCmd(c, ORDER_COMMAND)) {
                lastOrdersCmdTick = tickCounter;
                LOG.info("[COLLECT] /orders did not open - sending it again");
            }
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

        /* ---- Step 4: Collect Items -> move the buckets into the inventory ---- */
        if (title.contains("collect items")) {

            if (quickMoveAllNow(c, h, 0, cs, JOB_ITEM)) {
                movedAny = true;
                cooldown = ORDER_COLLECT_SPEED_TICKS;
            } else if (movedAny) {
                finishCollect(c, count(c, JOB_ITEM) - itemsAtCollectStart);
            } else if (++collectEmptyTicks > 25) {
                // empty: this order was collected before -> skip it and look at the next one
                if (lastOrderSlot != -1) {
                    doneOrderSlots.add(lastOrderSlot);
                }
                closeScreens(c);
                setPhase(Phase.COLLECT_CMD);
            }

            return;
        }

        int s;

        if (title.contains("edit order")) {

            /* ---- Step 3: the "Collect" chest ---- */
            s = findChest(h, cs, "collect");

        } else if (title.contains("your orders")) {

            /* ---- Step 2: the bucket order ---- */
            s = -1;
            boolean waiting = false;

            for (int i = 0; i < cs; i++) {

                if (doneOrderSlots.contains(i) || !h.getSlot(i).getStack().isOf(JOB_ITEM)) {
                    continue;
                }

                if (fullyDelivered(h.getSlot(i).getStack())) {
                    s = i;
                    break;
                }

                waiting = true;   // a bucket order that is not complete yet
            }

            if (s == -1 && waiting) {
                closeScreens(c);
                info(c, "Order not complete yet - checking again in " + (ORDER_POLL_BUCKET / 20) + " s...");
                setPhase(Phase.ORDER_WAIT);
                return;
            }

            if (s == -1) {
                return;   // no bucket order visible (yet)
            }

            lastOrderSlot = s;

        } else {

            /* ---- Step 1: the "Your Orders" chest in the main menu ---- */
            s = findChest(h, cs, "your orders");

            if (s == -1) {
                for (int i = Math.max(0, cs - 9); i < cs; i++) {   // fallback: any chest in the bottom row
                    if (h.getSlot(i).getStack().isOf(Items.CHEST)) {
                        s = i;
                        break;
                    }
                }
            }
        }

        if (s == -1) {
            return;
        }

        if (clickOnce(c, hs, h, s)) {
            LOG.info("[COLLECT] '{}' -> clicked slot {}", title, s);
            cooldown = ORDER_COLLECT_SPEED_TICKS;
        }
    }

    /** A chest whose name/lore contains the key (falls back to any item containing it). */
    private static int findChest(ScreenHandler h, int cs, String key) {
        for (int i = 0; i < cs; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isOf(Items.CHEST) && textOf(st).contains(key)) {
                return i;
            }
        }
        return find(h, cs, key);
    }

    private static void finishCollect(MinecraftClient c, int gained) {
        collectedTotal += Math.max(gained, 0);
        closeScreens(c);

        int total = count(c, JOB_ITEM);
        if (total < BUCKET_ORDER_AMOUNT) {
            info(c, "Only " + total + "/" + BUCKET_ORDER_AMOUNT + " buckets are in the inventory. Checking the order again...");
            setPhase(Phase.ORDER_WAIT);
            return;
        }

        info(c, "Collected " + total + "/" + BUCKET_ORDER_AMOUNT + ". Moving the buckets to the hotbar...");
        prepTries = 0;
        setPhase(Phase.B_PREP_INV);
    }

    /* ======================================================== */
    /*                  /sell (leftover buckets)                */
    /* ======================================================== */

    private static void sellCmd(MinecraftClient c) {

        if (sellableCount(c) == 0) {
            afterSell(c);
            return;
        }

        if (!sendCmd(c, SELL_COMMAND)) {
            return;
        }

        movedItems = false;
        confirmedSell = false;
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

        // 1) ONE SHOT: shift-click every bucket stack into the sell GUI at once
        if (!movedItems) {
            quickMoveAllNow(c, h, cs, h.slots.size(), Items.BUCKET);
            quickMoveAllNow(c, h, cs, h.slots.size(), Items.WATER_BUCKET);
            movedItems = true;
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

        if (sellableCount(c) > 0 && ++sellAttempts < 3) {
            setPhase(Phase.SELL_CMD);      // the sell GUI was too small - sell the rest
        } else {
            afterSell(c);
        }
    }

    /** Everything is sold: start the next cycle (or finish). */
    private static void afterSell(MinecraftClient c) {

        cooldown = 10;
        sellAttempts = 0;

        if (!LOOP) {
            stop(c, "Done.");
            return;
        }

        int free = countEmptySlots(c);

        if (free < BUCKET_MIN_FREE_SLOTS) {
            stop(c, "Not enough free slots for the next batch (" + free + " free, need " + BUCKET_MIN_FREE_SLOTS + ").");
            return;
        }

        collectedTotal = 0;
        doneOrderSlots.clear();
        lastOrderSlot = -1;
        info(c, "Cycle complete. Ordering the next " + BUCKET_ORDER_AMOUNT + " buckets...");
        setPhase(Phase.ORDER_CMD);
    }

    /* ======================================================== */
    /*       BUCKET MODE: order -> dispensers -> /ah sell       */
    /* ======================================================== */

    private static List<BlockPos> dispensers = new ArrayList<>();
    private static int dispIdx = 0;
    private static boolean dispEmptyMode = false;
    private static int dispStage = 0;
    private static boolean dispActed = false;
    private static int dispRetries = 0;
    private static int dispLeft = -1;
    private static int dispClicks = 0;

    // loading: keep going over the dispensers until no empty bucket is left
    private static int dispPasses = 0;
    private static int passStartBuckets = 0;
    private static int passNoProgress = 0;
    private static int prepTries = 0;

    private static int bucketSold = 0;
    private static int bucketSellTarget = 0;
    private static int bucketIdle = 0;
    private static int bucketFail = 0;
    private static boolean yesPressed = false;

    // pulling water buckets up from the inventory into the hotbar (with the inventory screen open)
    private static int pullClicks = 0;
    private static int lastPullTick = -1000;

    private static Vec3d strafeOrigin = null;

    private static void startBucket(MinecraftClient c) {

        int free = countEmptySlots(c);

        if (free < BUCKET_MIN_FREE_SLOTS) {
            chat(c, "NOT STARTED: you have " + free + " free inventory slots, need " + BUCKET_MIN_FREE_SLOTS + ". Empty some slots and press DELETE again.");
            return;
        }

        collectedTotal = 0;
        sellAttempts = 0;
        pendingChat = null;
        lastCommandTick = -1000;
        ahError = null;
        doneOrderSlots.clear();
        lastOrderSlot = -1;

        chat(c, "STARTED (free slots: " + free + ")");
        info(c, "BUCKET MODE ON - ordering " + BUCKET_ORDER_AMOUNT + " buckets @ " + BUCKET_ORDER_PRICE);
        setPhase(Phase.ORDER_CMD);
    }

    /**
     * The plain "Bucket" result button (not Lava Bucket, Milk Bucket, Bucket of Axolotl ...).
     * The button label starts with an icon/sprite, so the text is cleaned first:
     * "[sprite] Bucket" / "<icon> Bucket" -> "bucket".
     */
    private static ClickableWidget bucketResultButton(List<ClickableWidget> ws) {

        for (ClickableWidget w : ws) {
            if (!(w instanceof PressableWidget)) {
                continue;
            }
            if (cleanLabel(w.getMessage().getString()).equals("bucket")) {
                return w;
            }
        }

        return null;
    }

    private static String cleanLabel(String raw) {
        return raw.toLowerCase(Locale.ROOT)
            .replaceAll("\\[[^\\]]*\\]", " ")          // [sprite descriptions]
            .replaceAll("[^\\p{L}\\p{N}() ]", " ")        // icon glyphs and other symbols
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** "144/144 Delivered" / "Order Completed" in the order's tooltip. */
    private static boolean fullyDelivered(ItemStack stack) {
        String t = textOf(stack);
        if (t.contains("order completed")) {
            return true;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s*delivered").matcher(t);
        return m.find() && m.group(1).equals(m.group(2));
    }

    private static int sellableCount(MinecraftClient c) {
        return count(c, Items.BUCKET) + count(c, Items.WATER_BUCKET);
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

    /** Number of inventory slots (main + hotbar + offhand) holding EMPTY buckets. */
    private static int bucketStacks(MinecraftClient c) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        int n = 0;
        for (int i = 9; i <= 45; i++) {
            if (h.getSlot(i).getStack().isOf(Items.BUCKET)) n++;
        }
        return n;
    }

    /** empty = false: put EVERY empty-bucket stack into the dispensers. empty = true: take everything out. */
    private static void beginDispensers(MinecraftClient c, boolean empty) {

        dispEmptyMode = empty;

        if (!empty) {

            dispensers = findDispensers(c);

            if (dispensers.isEmpty()) {
                stop(c, "No dispenser within reach (" + DISPENSER_REACH + " blocks).");
                return;
            }

            dispPasses = 0;
            passNoProgress = 0;
            passStartBuckets = count(c, Items.BUCKET);

            bucketSellTarget = Math.max(0, BUCKET_ORDER_AMOUNT - STUCK_BUCKETS);
            LOG.info("Dispensers: {} ({} bucket stacks) sell target {}", dispensers, bucketStacks(c), bucketSellTarget);
            chat(c, "found " + dispensers.size() + " dispensers, " + bucketStacks(c) + " bucket stacks to load");
        }

        dispIdx = 0;
        dispStage = 0;
        dispActed = false;
        dispRetries = 0;

        setPhase(Phase.B_DISP_OPEN);
        cooldown = empty ? 0 : DISPENSER_BUCKET_SPEED_TICKS;
    }

    /* Open the inventory and move every empty-bucket stack into the 9 hotbar slots. */
    private static void prepBucketInventory(MinecraftClient c) {

        if (!(c.currentScreen instanceof InventoryScreen)) {
            c.setScreen(new InventoryScreen(c.player));
            cooldown = ORDER_COLLECT_SPEED_TICKS;
            return;
        }

        PlayerScreenHandler h = c.player.playerScreenHandler;

        // SWAP moves the previous hotbar item back into the inventory, so nothing is lost.
        for (int hb = 0; hb < 9; hb++) {
            if (h.getSlot(36 + hb).getStack().isOf(Items.BUCKET)) {
                continue;
            }

            int inv = -1;
            for (int i = 9; i <= 35; i++) {
                if (h.getSlot(i).getStack().isOf(Items.BUCKET)) {
                    inv = i;
                    break;
                }
            }

            if (inv == -1) {
                break;
            }

            c.interactionManager.clickSlot(h.syncId, inv, hb, SlotActionType.SWAP, c.player);
            cooldown = ORDER_COLLECT_SPEED_TICKS;
            return;
        }

        // wait a moment for the server to confirm, but never forever
        if (++prepTries < 6) {
            cooldown = ORDER_COLLECT_SPEED_TICKS;
            return;
        }

        c.player.closeHandledScreen();
        beginDispensers(c, false);
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
            cooldown = DISPENSER_BUCKET_SPEED_TICKS;
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
        dispLeft = -1;
        dispClicks = 0;
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

            if (phaseTicks < 6) {
                return; // let the contents arrive
            }

            if (dispLeft < 0) {
                if (dispEmptyMode) {
                    dispLeft = 0;
                } else {
                    // Share of the empty-bucket stacks that are STILL in the inventory:
                    // spread evenly over this dispenser and the ones after it in this pass.
                    int stacks = bucketStacks(c);
                    int dispensersLeft = Math.max(1, dispensers.size() - dispIdx);
                    dispLeft = (stacks + dispensersLeft - 1) / dispensersLeft;
                }
                dispClicks = 0;
            }

            int slot = -1;

            if (!dispEmptyMode) {
                // shift-click ONE empty-bucket stack at a time (hotbar first, then the main inventory).
                // Only EMPTY buckets - a water bucket is never put into a dispenser.
                if (dispLeft > 0) {
                    for (int i = Math.min(36, h.slots.size()); i <= 44 && i < h.slots.size(); i++) {
                        if (h.getSlot(i).getStack().isOf(Items.BUCKET)) {
                            slot = i;
                            break;
                        }
                    }
                    if (slot == -1) {
                        for (int i = cs; i < Math.min(36, h.slots.size()); i++) {
                            if (h.getSlot(i).getStack().isOf(Items.BUCKET)) {
                                slot = i;
                                break;
                            }
                        }
                    }
                }
            } else {
                // take everything out (one shift-click at a time)
                for (int i = 0; i < cs; i++) {
                    if (!h.getSlot(i).getStack().isEmpty()) {
                        slot = i;
                        break;
                    }
                }
            }

            if (slot != -1 && ++dispClicks <= 40) {
                click(c, h, slot, SlotActionType.QUICK_MOVE);
                if (!dispEmptyMode) {
                    dispLeft--;
                }
                cooldown = DISPENSER_BUCKET_SPEED_TICKS;
                return;
            }

            dispActed = true;
            cooldown = DISPENSER_BUCKET_SPEED_TICKS;
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
            cooldown = DISPENSER_BUCKET_SPEED_TICKS;
            return;
        }

        if (dispEmptyMode) {
            info(c, "Dispensers emptied. Selling the leftover buckets with /sell...");
            sellAttempts = 0;
            setPhase(Phase.SELL_CMD);
            cooldown = 5;
            return;
        }

        /*
         * One pass over all dispensers is done. The dispensers start throwing water buckets the moment
         * they get the first stack, so some empty buckets can still be left (full dispensers, a slot that
         * was busy, lag ...). KEEP GOING over the dispensers until there is no empty bucket left.
         * Nothing is sold until that is finished.
         */
        int left = count(c, Items.BUCKET);

        if (left > 0) {

            passNoProgress = (left < passStartBuckets) ? 0 : passNoProgress + 1;

            if (dispPasses + 1 < MAX_LOAD_PASSES && passNoProgress < 3) {
                dispPasses++;
                info(c, left + " empty buckets still in the inventory - loading them again (pass " + (dispPasses + 1) + ")...");
                passStartBuckets = left;
                dispIdx = 0;
                dispStage = 0;
                dispActed = false;
                dispRetries = 0;
                setPhase(Phase.B_DISP_OPEN);
                cooldown = DISPENSER_BUCKET_SPEED_TICKS * 2;
                return;
            }

            info(c, "Could not place the last " + left + " empty buckets (dispensers full?). Selling anyway.");
        }

        bucketSold = 0;
        bucketIdle = 0;
        bucketFail = 0;
        yesPressed = false;
        ahError = null;
        pullClicks = 0;
        strafeOrigin = new Vec3d(c.player.getX(), c.player.getY(), c.player.getZ());
        info(c, "All empty buckets are in the dispensers. Selling water buckets for " + LIST_PRICE + "...");
        setPhase(Phase.B_SELL_FIND);
    }

    /* ---------- selling water buckets: hold -> /ah sell <price> -> Yes ---------- */

    /** Water bucket in the main inventory (not the hotbar), or -1. */
    private static int inventoryWaterSlot(MinecraftClient c) {
        PlayerScreenHandler h = c.player.playerScreenHandler;
        for (int i = 9; i <= 35; i++) {
            if (h.getSlot(i).getStack().isOf(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    /** First hotbar index (0-8) holding a water bucket, or -1. */
    private static int firstHotbarWater(PlayerScreenHandler h) {
        for (int i = 0; i < 9; i++) {
            if (h.getSlot(36 + i).getStack().isOf(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    /** Hotbar slot that can receive a water bucket: an EMPTY one, else any slot that is not already a water bucket. */
    private static int pullTarget(PlayerScreenHandler h) {
        for (int i = 0; i < 9; i++) {
            if (h.getSlot(36 + i).getStack().isEmpty()) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (!h.getSlot(36 + i).getStack().isOf(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    private static void bucketSellFind(MinecraftClient c) {

        if (ahError != null) {
            stop(c, "The auction house refused a listing: " + ahError);
            return;
        }

        if (bucketSold >= bucketSellTarget) {
            if (c.currentScreen instanceof InventoryScreen) {
                c.player.closeHandledScreen();
            }
            finishBucketSelling(c);
            return;
        }

        PlayerScreenHandler h = c.player.playerScreenHandler;
        int invSlot = inventoryWaterSlot(c);
        int target = pullTarget(h);

        /*
         * A) The inventory screen is open: pull water buckets up into the hotbar like a real player does.
         */
        if (c.currentScreen instanceof InventoryScreen) {

            if (invSlot != -1 && target != -1 && pullClicks < 12) {
                c.interactionManager.clickSlot(h.syncId, invSlot, target, SlotActionType.SWAP, c.player);
                pullClicks++;
                lastPullTick = tickCounter;
                cooldown = CLICK_GAP;
                return;
            }

            // nothing more to pull: give the server a moment to confirm the moves, then close the inventory
            if (tickCounter - lastPullTick < SELL_SYNC_WAIT) {
                return;
            }

            c.player.closeHandledScreen();
            pullClicks = 0;
            cooldown = 3;
            return;
        }

        /* B) ANY hotbar slot holding a water bucket -> sell it right away. */
        int hb = firstHotbarWater(h);

        if (hb != -1) {
            bucketIdle = 0;
            c.player.getInventory().setSelectedSlot(hb);
            // The new selected slot reaches the server on the next tick, and the command is sent after that.
            cooldown = 1;
            setPhase(Phase.B_SELL_CMD);
            return;
        }

        /* C) None in the hotbar but some in the inventory -> open the inventory and pull them up. */
        if (invSlot != -1 && target != -1) {
            bucketIdle = 0;
            c.setScreen(new InventoryScreen(c.player));
            pullClicks = 0;
            lastPullTick = tickCounter;
            cooldown = 3;
            return;
        }

        /* D) Nothing anywhere -> wait for the dispensers to produce more. */
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
    }

    private static void bucketSellCmd(MinecraftClient c) {

        PlayerScreenHandler h = c.player.playerScreenHandler;
        int held = 36 + c.player.getInventory().getSelectedSlot();

        if (!h.getSlot(held).getStack().isOf(Items.WATER_BUCKET)) {
            if (++bucketFail > 25) {
                stop(c, "Cannot hold the water bucket.");
                return;
            }
            setPhase(Phase.B_SELL_FIND);
            return;
        }

        // the gap between two listings is LIST_DELAY_TICKS (SELLING_SPEED_TICKS)
        if (!sendCmd(c, "ah sell " + LIST_PRICE, LIST_DELAY_TICKS)) {
            return;   // too soon after the last listing - try again next tick
        }

        yesPressed = false;
        dialogScreen = null;
        lastPressTick = -1000;
        setPhase(Phase.B_SELL_DIALOG);
    }

    /**
     * After /ah sell the server shows "Are you sure you want to sell this?" - Yes / No.
     * A sale is COUNTED when we pressed Yes and the dialog closed (not by looking at the hotbar:
     * the dispensers keep throwing new water buckets into the just-emptied slot, which used to look
     * like "the bucket is still held").
     */
    private static void bucketSellDialog(MinecraftClient c) {

        if (ahError != null) {
            stop(c, "The auction house refused a listing: " + ahError);
            return;
        }

        Screen sc = c.currentScreen;

        /* ---- chest-style confirmation ---- */
        if (sc instanceof HandledScreen<?> hs) {

            ScreenHandler h = hs.getScreenHandler();
            int cs = containerSize(h);
            int confirm = find(h, cs, "yes", "confirm", "confirm listing", "sell", "list", "accept");

            if (confirm != -1 && tickCounter - lastPressTick >= 5) {
                click(c, h, confirm, SlotActionType.PICKUP);
                lastPressTick = tickCounter;
                yesPressed = true;
                cooldown = 2;
                return;
            }

            if (phaseTicks > 160) {
                closeScreens(c);
                failSale(c, "The /ah sell confirmation GUI could not be confirmed. Check the chat.");
            }
            return;
        }

        /* ---- dialog confirmation (Yes / No) ---- */
        if (sc != null) {

            if (sc != dialogScreen) {
                dialogScreen = sc;
                dialogSince = tickCounter;
                lastPressTick = -1000;
            }

            if (tickCounter - dialogSince < DIALOG_SETTLE) {
                return;
            }

            if (tickCounter - lastPressTick < 5) {
                return;
            }

            ClickableWidget yes = sellConfirmButton(widgets(sc));

            if (yes != null) {
                pressButton(yes);
                lastPressTick = tickCounter;
                yesPressed = true;
                cooldown = 2;
                return;
            }

            if (phaseTicks > 160) {
                closeScreens(c);
                failSale(c, "The /ah sell confirmation dialog could not be confirmed. Check the chat.");
            }
            return;
        }

        /* ---- no screen ---- */

        if (yesPressed) {
            // we pressed Yes and the dialog is gone -> listed
            bucketSold++;
            bucketFail = 0;
            yesPressed = false;
            chat(c, "listed " + bucketSold + "/" + bucketSellTarget);
            setPhase(Phase.B_SELL_FIND);
            return;
        }

        // The dialog never appeared (maybe the server lists without asking): give it 2 s, then decide
        // by looking at the held slot.
        if (phaseTicks >= 40) {
            PlayerScreenHandler h = c.player.playerScreenHandler;
            int held = 36 + c.player.getInventory().getSelectedSlot();

            if (!h.getSlot(held).getStack().isOf(Items.WATER_BUCKET)) {
                bucketSold++;
                bucketFail = 0;
                setPhase(Phase.B_SELL_FIND);
            } else {
                failSale(c, "No confirmation dialog after /ah sell and the bucket is still held. Check the chat.");
            }
        }
    }

    private static void failSale(MinecraftClient c, String message) {
        if (++bucketFail >= 5) {
            stop(c, message);
        } else {
            setPhase(Phase.B_SELL_FIND);
            cooldown = 2;
        }
    }

    private static ClickableWidget sellConfirmButton(List<ClickableWidget> ws) {
        for (ClickableWidget w : ws) {
            if (!(w instanceof PressableWidget)) {
                continue;
            }
            String label = cleanLabel(w.getMessage().getString());
            if (label.equals("yes")
                    || label.equals("confirm")
                    || label.equals("confirm listing")
                    || label.equals("sell")
                    || label.equals("list")
                    || label.equals("accept")) {
                return w;
            }
        }
        return null;
    }

    private static void finishBucketSelling(MinecraftClient c) {
        info(c, "Sold " + bucketSold + " water buckets. Taking the rest out of the dispensers...");
        chat(c, "sold " + bucketSold + "/" + bucketSellTarget + " water buckets");
        releaseKeys(c);
        setPhase(Phase.B_RETURN);
    }

    /* ======================================================== */
    /*                         HELPERS                          */
    /* ======================================================== */

    /** A normal chat line only YOU see (not the action bar, so it can't be missed). */
    private static void chat(MinecraftClient c, String text) {
        if (c.player != null) {
            c.player.sendMessage(Text.literal("\u00a78[SpruceFast] \u00a77" + text), false);
        }
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

    private static int lastClickTick = -1000;

    /** Click a slot, but not the same slot twice in a row on the same screen (retries after 30 ticks if nothing happened). */
    private static boolean clickOnce(MinecraftClient c, HandledScreen<?> hs, ScreenHandler h, int slot) {
        String key = hs.getTitle().getString() + ":" + slot;
        if (key.equals(lastClickKey) && tickCounter - lastClickTick < 30) {
            return false;
        }
        lastClickKey = key;
        lastClickTick = tickCounter;
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
