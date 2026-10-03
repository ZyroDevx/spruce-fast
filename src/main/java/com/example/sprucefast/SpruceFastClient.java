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

import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Cycle: /orders (buy 576 spruce logs @ 52 each) -> wait -> collect
 *        -> craft planks -> /sell planks -> repeat.
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
    private static final boolean DEBUG     = true;  // dumps every GUI to logs/latest.log

    /* ========================================== */

    private static final Logger LOG = LoggerFactory.getLogger("SpruceFast");

    private enum Phase {
        IDLE,
        ORDER_CMD, ORDER_GUI, ORDER_WAIT,
        COLLECT_CMD, COLLECT_GUI,
        CRAFT_OPEN, CRAFT,
        TABLE_OPEN, TABLE,
        SELL_CMD, SELL_GUI, PICKUP_WAIT
    }

    private static KeyBinding startKey;

    private static Phase phase = Phase.IDLE;
    private static int cooldown = 0;
    private static int phaseTicks = 0;
    private static int tickCounter = 0;

    private static String pendingChat = null;
    private static int lastReplyTick = -100;

    private static volatile boolean orderPlaced = false;
    private static volatile boolean orderFilled = false;

    private static int collectedTotal = 0;
    private static int logsAtCollectStart = 0;
    private static int tripLogs = 0;
    private static int stuckCounter = 0;
    private static int sellAttempts = 0;
    private static int guiClicks = 0;

    private static boolean itemPicked = false;
    private static int orderStep = 0;
    private static boolean textSet = false;
    private static boolean movedAny = false;
    private static Object dialogScreen = null;
    private static int dialogSince = 0;
    private static int lastPressTick = -1000;
    private static Object lastWidgetScreen = null;
    private static boolean yourOrdersClicked = false;
    private static boolean movedPlanks = false;
    private static boolean confirmedSell = false;
    private static int tableRetries = 0;
    private static int noProgress = 0;
    private static int lastGridPlanks = -1;
    private static int pickupLastCount = -1;
    private static int pickupStable = 0;

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

        if (c.player == null || c.interactionManager == null) {
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
            case CRAFT_OPEN   -> craftOpen(c);
            case CRAFT        -> craft(c);
            case TABLE_OPEN   -> tableOpen(c);
            case TABLE        -> table(c);
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

        collectedTotal = 0;
        tripLogs = 0;
        stuckCounter = 0;
        sellAttempts = 0;
        tableRetries = 0;
        pendingChat = null;

        info(c, "ON - ordering " + TARGET_LOGS + " spruce logs @ " + PRICE_PER_LOG);
        setPhase(Phase.ORDER_CMD);
    }

    private static void stop(MinecraftClient c, String reason) {
        phase = Phase.IDLE;
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
        phaseTicks = 0;
        guiClicks = 0;
        lastClickKey = "";
        lastScreen = null;
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
        textSet = false;
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
                textSet = false;
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
        if (tickCounter - dialogSince < 2) {
            return;
        }

        // Pressed recently on this same screen -> wait; retry only if nothing changed.
        if (tickCounter - lastPressTick < 15) {
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
        tripLogs = 0;
        setPhase(Phase.CRAFT_OPEN);
    }

    /* ======================================================== */
    /*                         CRAFTING                         */
    /* ======================================================== */

    private static void craftOpen(MinecraftClient c) {

        if (count(c, Items.SPRUCE_LOG) == 0) {
            if (count(c, Items.SPRUCE_PLANKS) >= 3) {
                setPhase(Phase.TABLE_OPEN);
            } else {
                afterSell(c);
            }
            return;
        }

        c.setScreen(new InventoryScreen(c.player));
        stuckCounter = 0;
        setPhase(Phase.CRAFT);
        cooldown = 3;
    }

    private static void craft(MinecraftClient c) {

        if (!(c.currentScreen instanceof InventoryScreen)) {
            if (phaseTicks > 10) {
                stop(c, "Inventory closed.");
            }
            return;
        }

        PlayerScreenHandler h = c.player.playerScreenHandler;

        if (!h.getCursorStack().isEmpty()) {
            if (!clearCursor(c, h)) {
                stop(c, "Cursor holds an item and there is no free slot.");
            }
            cooldown = 3;
            return;
        }

        // Logs in the grid -> shift-click the result once.
        if (gridHasItems(h)) {

            if (!gridOnlyLogs(h)) {
                stop(c, "Remove other items from the crafting grid.");
                return;
            }

            if (++stuckCounter > 8) {
                stop(c, "No room for planks.");
                return;
            }

            click(c, h, 0, SlotActionType.QUICK_MOVE);
            cooldown = 4;
            return;
        }

        stuckCounter = 0;

        int ls = findSlotWith(h, Items.SPRUCE_LOG);

        if (ls == -1) {
            c.player.closeHandledScreen();
            setPhase(Phase.TABLE_OPEN);
            cooldown = 6;
            return;
        }

        click(c, h, ls, SlotActionType.PICKUP);
        click(c, h, 1, SlotActionType.PICKUP);

        if (!h.getCursorStack().isEmpty()) {
            click(c, h, ls, SlotActionType.PICKUP);
        }

        cooldown = 4;
    }

    /* ======================================================== */
    /*              SLABS (crafting table, Ctrl+Q drops)        */
    /* ======================================================== */

    /*
     * CraftingScreenHandler slots:
     *   0 = result, 1-9 = grid, 10-36 = inventory, 37-45 = hotbar
     */
    private static final int T_INV_FROM = 10;
    private static final int T_INV_TO   = 46;

    private static void tableOpen(MinecraftClient c) {

        if (count(c, Items.SPRUCE_PLANKS) < 3) {
            enterPickupWait();
            return;
        }

        BlockPos pos = findCraftingTable(c);

        if (pos == null) {
            stop(c, "No crafting table within reach. Place one next to you.");
            return;
        }

        // Look straight down so Ctrl+Q drops land at our feet (and get picked up again later).
        c.player.setPitch(90.0f);

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, hit);

        noProgress = 0;
        lastGridPlanks = -1;
        setPhase(Phase.TABLE);
    }

    private static void table(MinecraftClient c) {

        if (phaseTicks > 6000) {
            stop(c, "Slab crafting timed out.");
            return;
        }

        if (!(c.player.currentScreenHandler instanceof CraftingScreenHandler h)) {
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

        // Anything stuck on the cursor goes back into the inventory first.
        if (!h.getCursorStack().isEmpty()) {
            if (!putCursorAway(c, h, T_INV_FROM, T_INV_TO)) {
                stop(c, "Cursor holds an item and there is no free slot.");
            }
            cooldown = 1;
            return;
        }

        /*
         * A) Inventory is full -> Ctrl+Q (drop the WHOLE stack) on every slab stack.
         */
        if (countFree(h, T_INV_FROM, T_INV_TO) == 0
                && countOf(h, Items.SPRUCE_SLAB, T_INV_FROM, T_INV_TO) > 0) {

            for (int i = T_INV_FROM; i < T_INV_TO; i++) {
                if (h.getSlot(i).getStack().isOf(Items.SPRUCE_SLAB)) {
                    c.interactionManager.clickSlot(h.syncId, i, 1, SlotActionType.THROW, c.player);
                }
            }

            cooldown = 2;
            return;
        }

        /*
         * B) Planks in the grid -> shift-click the result (crafts everything in the grid).
         */
        int gridPlanks = 0;
        boolean gridHas = false;

        for (int i = 1; i <= 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) {
                continue;
            }
            gridHas = true;
            if (!st.isOf(Items.SPRUCE_PLANKS)) {
                stop(c, "Remove other items from the crafting grid.");
                return;
            }
            gridPlanks += st.getCount();
        }

        if (gridHas) {

            if (gridPlanks == lastGridPlanks) {
                if (++noProgress >= 6) {
                    // Leftover planks that can't form a recipe -> back to the inventory.
                    for (int i = 1; i <= 9; i++) {
                        if (!h.getSlot(i).getStack().isEmpty()) {
                            click(c, h, i, SlotActionType.QUICK_MOVE);
                        }
                    }
                    noProgress = 0;
                    lastGridPlanks = -1;
                    cooldown = 2;
                    return;
                }
            } else {
                noProgress = 0;
                lastGridPlanks = gridPlanks;
            }

            click(c, h, 0, SlotActionType.QUICK_MOVE);
            cooldown = 2;
            return;
        }

        lastGridPlanks = -1;
        noProgress = 0;

        /*
         * C) Grid empty -> load the next planks, or finish.
         */
        int stacks = stacksOf(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);
        int biggest = biggestStack(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);

        if (biggest < 3 && stacks < 3) {
            c.player.closeHandledScreen();
            info(c, "Slabs crafted. Picking up and selling...");
            enterPickupWait();
            return;
        }

        int[] cells = {7, 8, 9};

        if (stacks >= 3) {

            // three full stacks side by side (bottom row) -> up to 384 slabs per shift-click
            for (int k = 0; k < 3; k++) {
                int ps = findIn(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);
                if (ps == -1) {
                    break;
                }
                click(c, h, ps, SlotActionType.PICKUP);
                click(c, h, cells[k], SlotActionType.PICKUP);
                if (!h.getCursorStack().isEmpty()) {
                    click(c, h, ps, SlotActionType.PICKUP);
                }
            }

        } else {

            // fewer than 3 stacks: pick one up and drag-split it across the 3 cells
            int ps = findIn(h, Items.SPRUCE_PLANKS, T_INV_FROM, T_INV_TO);
            click(c, h, ps, SlotActionType.PICKUP);

            c.interactionManager.clickSlot(h.syncId, -999,
                ScreenHandler.packQuickCraftData(0, 0), SlotActionType.QUICK_CRAFT, c.player);

            for (int cell : cells) {
                c.interactionManager.clickSlot(h.syncId, cell,
                    ScreenHandler.packQuickCraftData(1, 0), SlotActionType.QUICK_CRAFT, c.player);
            }

            c.interactionManager.clickSlot(h.syncId, -999,
                ScreenHandler.packQuickCraftData(2, 0), SlotActionType.QUICK_CRAFT, c.player);
        }

        cooldown = 3; // the server has to compute the recipe result
    }

    private static BlockPos findCraftingTable(MinecraftClient c) {

        BlockPos base = c.player.getBlockPos();
        Vec3d eye = c.player.getEyePos();

        BlockPos best = null;
        double bestD = 4.4 * 4.4;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {

                    BlockPos p = base.add(dx, dy, dz);

                    if (!c.world.getBlockState(p).isOf(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }

                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(p));

                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }

        return best;
    }

    /* ======================================================== */
    /*                  PICK UP + SELL (one shot)               */
    /* ======================================================== */

    private static void enterPickupWait() {
        pickupLastCount = -1;
        pickupStable = 0;
        setPhase(Phase.PICKUP_WAIT);
    }

    /*
     * Dropped slabs lie at our feet and are picked up again automatically as soon as
     * the inventory has room. Sell whatever is in the inventory once it stops growing,
     * then repeat until nothing more shows up.
     */
    private static void pickupWait(MinecraftClient c) {

        if (c.currentScreen instanceof HandledScreen<?>) {
            closeScreens(c);
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
        enterPickupWait();
    }

    private static void afterSell(MinecraftClient c) {

        sellAttempts = 0;
        cooldown = 10;

        if (count(c, Items.SPRUCE_LOG) > 0) {
            setPhase(Phase.CRAFT_OPEN);
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

    /** Shift-clicks up to 6 matching slots in [from, to). Returns true if anything was clicked. */
    private static boolean quickMoveAll(MinecraftClient c, ScreenHandler h, int from, int to, Item item) {
        int done = 0;
        for (int i = from; i < to && done < 6; i++) {
            if (h.getSlot(i).getStack().isOf(item)) {
                click(c, h, i, SlotActionType.QUICK_MOVE);
                done++;
            }
        }
        return done > 0;
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

    /** Types the text and presses the button in the same tick. */
    private static boolean typeAndPress(MinecraftClient c, List<ClickableWidget> ws, String text, String label) {

        TextFieldWidget tf = firstTextField(ws);
        ClickableWidget b = button(ws, label);

        if (tf == null || b == null) {
            return false;
        }

        tf.setText(text);
        pressButton(b);
        return true;
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

    private static int biggestStack(ScreenHandler h, Item item, int from, int to) {
        int best = 0;
        for (int i = from; i < to; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isOf(item) && st.getCount() > best) best = st.getCount();
        }
        return best;
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

    private static int findSlotWith(PlayerScreenHandler h, Item item) {
        for (int i = 9; i <= 45; i++) {
            if (h.getSlot(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private static boolean gridHasItems(PlayerScreenHandler h) {
        for (int i = 1; i <= 4; i++) {
            if (!h.getSlot(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    private static boolean gridOnlyLogs(PlayerScreenHandler h) {
        for (int i = 1; i <= 4; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty() && !s.isOf(Items.SPRUCE_LOG)) return false;
        }
        return true;
    }

    private static boolean clearCursor(MinecraftClient c, PlayerScreenHandler h) {
        for (int s = 9; s <= 44; s++) {
            if (h.getSlot(s).getStack().isEmpty()) {
                click(c, h, s, SlotActionType.PICKUP);
                return true;
            }
        }
        return false;
    }
}
