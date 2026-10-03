package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.client.MinecraftClient;
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
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final String ORDER_ITEM_NAME = "spruce log";

    private static final int TARGET_LOGS   = 576;   // 9 stacks
    private static final int PRICE_PER_LOG = 52;
    private static final int LOGS_PER_TRIP = 192;   // craft 3 stacks, then sell (keeps inventory from filling)
    private static final int POLL_TICKS    = 600;   // re-check /orders every 30 s
    private static final boolean LOOP      = true;  // repeat the whole cycle
    private static final boolean DEBUG     = true;  // dumps every GUI to logs/latest.log

    /* ========================================== */

    private static final Logger LOG = LoggerFactory.getLogger("SpruceFast");

    private enum Phase {
        IDLE,
        ORDER_CMD, ORDER_GUI, ORDER_WAIT,
        COLLECT_CMD, COLLECT_GUI,
        CRAFT_OPEN, CRAFT,
        SELL_CMD, SELL_GUI
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
    private static boolean yourOrdersClicked = false;
    private static boolean movedPlanks = false;
    private static boolean confirmedSell = false;

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
            case SELL_CMD     -> sellCmd(c);
            case SELL_GUI     -> sellGui(c);
            default -> { }
        }
    }

    private static void start(MinecraftClient c) {

        if (countEmptySlots(c) < TARGET_LOGS / 64) {
            info(c, "Need at least " + (TARGET_LOGS / 64) + " empty inventory slots.");
            return;
        }

        collectedTotal = 0;
        tripLogs = 0;
        stuckCounter = 0;
        sellAttempts = 0;
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
        if (p == Phase.ORDER_WAIT) {
            orderFilled = false;
        }
    }

    /* ======================================================== */
    /*                      CHAT LISTENER                       */
    /* ======================================================== */

    private static void onChat(String msg) {

        if (phase == Phase.ORDER_GUI) {

            if (msg.contains("order")
                    && (msg.contains("created") || msg.contains("placed")
                        || msg.contains("success") || msg.contains("listed"))) {
                orderPlaced = true;
                return;
            }

            if (tickCounter - lastReplyTick < 10) {
                return;
            }

            String reply = null;

            if (msg.contains("price")) {
                reply = String.valueOf(PRICE_PER_LOG);
            } else if (msg.contains("amount") || msg.contains("quantity") || msg.contains("how many")) {
                reply = String.valueOf(TARGET_LOGS);
            } else if ((msg.contains("item") || msg.contains("search") || msg.contains("name"))
                    && (msg.contains("type") || msg.contains("enter") || msg.contains("chat"))) {
                reply = ORDER_ITEM_NAME;
            }

            if (reply != null) {
                pendingChat = reply;
                lastReplyTick = tickCounter;
            }
        }

        if (phase == Phase.ORDER_WAIT
                && msg.contains("order")
                && (msg.contains("filled") || msg.contains("completed")
                    || msg.contains("fulfilled") || msg.contains("ready"))) {
            orderFilled = true;
        }
    }

    /* ======================================================== */
    /*                         ORDERING                         */
    /* ======================================================== */

    private static void orderCmd(MinecraftClient c) {
        orderPlaced = false;
        itemPicked = false;
        c.player.networkHandler.sendChatCommand(ORDER_COMMAND);
        setPhase(Phase.ORDER_GUI);
        cooldown = 10;
    }

    private static void orderGui(MinecraftClient c) {

        if (orderPlaced) {
            closeScreens(c);
            info(c, "Order placed. Waiting for it to fill...");
            setPhase(Phase.ORDER_WAIT);
            return;
        }

        if (phaseTicks > 1200) {
            stop(c, "Order setup timed out. Check logs/latest.log [SpruceFast].");
            return;
        }

        HandledScreen<?> hs = openContainer(c);
        if (hs == null) {
            return; // waiting for GUI or a chat prompt
        }

        dump(hs);

        if (guiClicks >= 30) {
            stop(c, "Too many clicks in order GUI. Check logs/latest.log.");
            return;
        }

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);

        int s = find(h, cs, "confirm", "place order", "submit");
        if (s == -1) {
            s = find(h, cs, "new order", "create order", "make order");
        }
        if (s == -1 && !itemPicked) {
            s = findItem(h, cs, Items.SPRUCE_LOG);
            if (s != -1) {
                itemPicked = true;
            }
        }

        if (s != -1 && clickOnce(c, hs, h, s)) {
            guiClicks++;
            cooldown = 8;
        }
    }

    private static void orderWait(MinecraftClient c) {

        if (phaseTicks % 100 == 0) {
            info(c, "Waiting for order... " + (phaseTicks / 20) + "s");
        }

        if (orderFilled || (phaseTicks > 0 && phaseTicks % POLL_TICKS == 0)) {
            setPhase(Phase.COLLECT_CMD);
        }
    }

    /* ======================================================== */
    /*                        COLLECTING                        */
    /* ======================================================== */

    private static void collectCmd(MinecraftClient c) {
        logsAtCollectStart = count(c, Items.SPRUCE_LOG);
        yourOrdersClicked = false;
        c.player.networkHandler.sendChatCommand(ORDER_COMMAND);
        setPhase(Phase.COLLECT_GUI);
        cooldown = 10;
    }

    private static void collectGui(MinecraftClient c) {

        int gained = count(c, Items.SPRUCE_LOG) - logsAtCollectStart;

        if (gained >= TARGET_LOGS - collectedTotal) {
            finishCollect(c, gained);
            return;
        }

        if (phaseTicks > 600) {
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

        if (guiClicks >= 25) {
            closeScreens(c);
            setPhase(Phase.ORDER_WAIT);
            return;
        }

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);
        String title = hs.getTitle().getString().toLowerCase();

        int s = find(h, cs, "collect", "claim", "withdraw", "take all");

        if (s != -1) {
            if (clickOnce(c, hs, h, s)) {
                guiClicks++;
            }
            cooldown = 6;
            return;
        }

        if (title.contains("storage") || title.contains("delivery")
                || title.contains("collect") || title.contains("claim")) {
            if (quickMoveAll(c, h, 0, cs, Items.SPRUCE_LOG)) {
                cooldown = 6;
                return;
            }
        }

        if (!yourOrdersClicked) {
            s = find(h, cs, "your order", "my order", "view order");
            if (s != -1) {
                yourOrdersClicked = true;
                click(c, h, s, SlotActionType.PICKUP);
                guiClicks++;
                cooldown = 8;
                return;
            }
        }

        s = findItem(h, cs, Items.SPRUCE_LOG);
        if (s != -1 && clickOnce(c, hs, h, s)) {
            guiClicks++;
            cooldown = 8;
        }
    }

    private static void finishCollect(MinecraftClient c, int gained) {
        collectedTotal += Math.max(gained, 0);
        closeScreens(c);
        info(c, "Collected " + collectedTotal + "/" + TARGET_LOGS + " logs.");
        tripLogs = 0;
        setPhase(Phase.CRAFT_OPEN);
        cooldown = 8;
    }

    /* ======================================================== */
    /*                         CRAFTING                         */
    /* ======================================================== */

    private static void craftOpen(MinecraftClient c) {

        if (count(c, Items.SPRUCE_LOG) == 0) {
            afterSell(c);
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

        if (ls == -1 || tripLogs >= LOGS_PER_TRIP) {
            c.player.closeHandledScreen();
            tripLogs = 0;
            setPhase(Phase.SELL_CMD);
            cooldown = 8;
            return;
        }

        tripLogs += h.getSlot(ls).getStack().getCount();

        click(c, h, ls, SlotActionType.PICKUP);
        click(c, h, 1, SlotActionType.PICKUP);

        if (!h.getCursorStack().isEmpty()) {
            click(c, h, ls, SlotActionType.PICKUP);
        }

        cooldown = 4;
    }

    /* ======================================================== */
    /*                          SELLING                         */
    /* ======================================================== */

    private static void sellCmd(MinecraftClient c) {

        if (count(c, Items.SPRUCE_PLANKS) == 0) {
            afterSell(c);
            return;
        }

        movedPlanks = false;
        confirmedSell = false;
        c.player.networkHandler.sendChatCommand(SELL_COMMAND);
        setPhase(Phase.SELL_GUI);
        cooldown = 10;
    }

    private static void sellGui(MinecraftClient c) {

        HandledScreen<?> hs = openContainer(c);

        if (hs == null) {
            if (phaseTicks > 200) {
                stop(c, "Sell GUI never opened. Check logs/latest.log.");
            }
            return;
        }

        dump(hs);

        ScreenHandler h = hs.getScreenHandler();
        int cs = containerSize(h);

        // 1) move planks from player inventory into the sell GUI (6 per tick)
        if (!movedPlanks) {
            if (!quickMoveAll(c, h, cs, h.slots.size(), Items.SPRUCE_PLANKS)) {
                movedPlanks = true;
            }
            cooldown = 3;
            return;
        }

        // 2) confirm button if there is one
        if (!confirmedSell) {
            confirmedSell = true;
            int s = find(h, cs, "confirm", "sell all", "accept");
            if (s != -1) {
                click(c, h, s, SlotActionType.PICKUP);
                cooldown = 10;
                return;
            }
        }

        // 3) close (many /sell GUIs sell on close)
        if (c.currentScreen instanceof HandledScreen<?>) {
            c.player.closeHandledScreen();
        }

        sellAttempts++;
        cooldown = 15;

        if (count(c, Items.SPRUCE_PLANKS) > 0 && sellAttempts < 3) {
            setPhase(Phase.SELL_CMD);
        } else {
            afterSell(c);
        }
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

            if (countEmptySlots(c) < TARGET_LOGS / 64) {
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
        if (!DEBUG || hs == lastScreen) {
            return;
        }
        lastScreen = hs;
        lastClickKey = "";

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
