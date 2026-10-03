package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

public class SpruceFastClient implements ClientModInitializer {

    private static KeyBinding startKey;

    private static boolean enabled = false;
    private static boolean waitingForInventory = false;

    /*
     * How many crafting results we try to process per tick.
     */
    private static final int CRAFTS_PER_TICK = 20;

    @Override
    public void onInitializeClient() {

        startKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.spruce_fast.start",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                KeyBinding.Category.create(
                    Identifier.of("spruce_fast", "main")
                )
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            /*
             * BACKSPACE
             */
            while (startKey.wasPressed()) {

                if (client.player == null) {
                    return;
                }

                enabled = !enabled;

                if (enabled) {

                    waitingForInventory = true;

                    /*
                     * Trigger Minecraft's inventory key.
                     */
                    KeyBinding.onKeyPressed(
                        InputUtil.Type.KEYSYM.createFromCode(
                            GLFW.GLFW_KEY_E
                        )
                    );

                    client.player.sendMessage(
                        Text.literal("Spruce Fast: ON"),
                        true
                    );

                } else {

                    waitingForInventory = false;

                    client.player.sendMessage(
                        Text.literal("Spruce Fast: OFF"),
                        true
                    );
                }
            }

            if (!enabled) {
                return;
            }

            if (client.player == null ||
                client.interactionManager == null) {
                return;
            }

            /*
             * Wait for the inventory to open.
             */
            if (waitingForInventory) {

                if (client.player.currentScreenHandler
                        instanceof PlayerScreenHandler) {

                    waitingForInventory = false;
                }

                return;
            }

            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        if (!(client.player.currentScreenHandler
                instanceof PlayerScreenHandler)) {
            return;
        }

        PlayerScreenHandler handler =
            (PlayerScreenHandler)
                client.player.currentScreenHandler;

        /*
         * Don't do anything while holding an item
         * with the mouse cursor.
         */
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        /*
         * -----------------------------------------
         * CRAFTING GRID ALREADY HAS LOGS
         * -----------------------------------------
         */
        if (hasCraftingInput(handler)) {

            if (!onlySpruceLogsInCraftingGrid(handler)) {

                enabled = false;

                client.player.sendMessage(
                    Text.literal(
                        "Spruce Fast: Remove other items from the crafting grid."
                    ),
                    true
                );

                return;
            }

            /*
             * Take the crafting results and put them
             * DIRECTLY INTO THE HOTBAR.
             */
            takeCraftingResults(client, handler);

            return;
        }

        /*
         * -----------------------------------------
         * CRAFTING GRID IS EMPTY
         * -----------------------------------------
         */

        int logSlot = findLogInventorySlot(client);

        if (logSlot == -1) {

            enabled = false;

            client.player.sendMessage(
                Text.literal(
                    "Spruce Fast: Finished. No spruce logs left."
                ),
                true
            );

            return;
        }

        /*
         * Pick up the ENTIRE spruce-log stack.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * Put the entire stack into crafting slot 1.
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            1,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * Safety check.
         */
        if (!handler.getCursorStack().isEmpty()) {

            client.interactionManager.clickSlot(
                handler.syncId,
                logSlot,
                0,
                SlotActionType.PICKUP,
                client.player
            );
        }
    }

    /*
     * Checks whether anything is inside
     * the 2x2 crafting grid.
     */
    private static boolean hasCraftingInput(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            if (!slot.getStack().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /*
     * Makes sure the crafting grid only contains
     * spruce logs.
     */
    private static boolean onlySpruceLogsInCraftingGrid(
            PlayerScreenHandler handler) {

        for (var slot : handler.getInputSlots()) {

            ItemStack stack = slot.getStack();

            if (stack.isEmpty()) {
                continue;
            }

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                return false;
            }
        }

        return true;
    }

    /*
     * Takes the crafting result and places it
     * DIRECTLY INTO THE HOTBAR.
     */
    private static void takeCraftingResults(
            MinecraftClient client,
            PlayerScreenHandler handler) {

        for (int i = 0; i < CRAFTS_PER_TICK; i++) {

            ItemStack result =
                handler.getOutputSlot().getStack();

            /*
             * No crafting result.
             */
            if (result.isEmpty()) {
                break;
            }

            /*
             * Safety check.
             */
            if (!result.isOf(Items.SPRUCE_PLANKS)) {
                break;
            }

            /*
             * Find a hotbar slot that can accept
             * the 4 spruce planks.
             */
            int hotbarSlot =
                findHotbarSlotForPlanks(client);

            /*
             * Hotbar is completely full.
             */
            if (hotbarSlot == -1) {

                enabled = false;

                client.player.sendMessage(
                    Text.literal(
                        "Spruce Fast: Hotbar is full. Make space for spruce planks."
                    ),
                    true
                );

                return;
            }

            /*
             * PICK UP the 4 planks from the crafting
             * result instead of QUICK_MOVE.
             */
            client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.PICKUP,
                client.player
            );

            /*
             * Put those planks directly into the
             * selected hotbar slot.
             */
            client.interactionManager.clickSlot(
                handler.syncId,
                hotbarSlot,
                0,
                SlotActionType.PICKUP,
                client.player
            );

            /*
             * If Minecraft couldn't fit everything,
             * don't continue and potentially lose items.
             */
            if (!handler.getCursorStack().isEmpty()) {

                /*
                 * Put the remaining items back into
                 * the result slot.
                 */
                client.interactionManager.clickSlot(
                    handler.syncId,
                    0,
                    0,
                    SlotActionType.PICKUP,
                    client.player
                );

                break;
            }
        }
    }

    /*
     * Find a hotbar slot for spruce planks.
     *
     * Priority:
     *
     * 1. Existing spruce plank stack with >= 4 space
     * 2. Empty hotbar slot
     */
    private static int findHotbarSlotForPlanks(
            MinecraftClient client) {

        var stacks =
            client.player
                .getInventory()
                .getMainStacks();

        /*
         * First look for an existing spruce-plank stack
         * that can fit the full 4-plank result.
         */
        for (int i = 0; i < 9; i++) {

            ItemStack stack = stacks.get(i);

            if (stack.isOf(Items.SPRUCE_PLANKS)
                && stack.getCount() <= 60) {

                /*
                 * Screen hotbar slots are 36-44.
                 */
                return 36 + i;
            }
        }

        /*
         * Otherwise find an empty hotbar slot.
         */
        for (int i = 0; i < 9; i++) {

            ItemStack stack = stacks.get(i);

            if (stack.isEmpty()) {
                return 36 + i;
            }
        }

        return -1;
    }

    /*
     * Finds a spruce-log stack in the inventory.
     */
    private static int findLogInventorySlot(
            MinecraftClient client) {

        var stacks =
            client.player
                .getInventory()
                .getMainStacks();

        for (int i = 0; i < stacks.size(); i++) {

            ItemStack stack = stacks.get(i);

            if (!stack.isOf(Items.SPRUCE_LOG)) {
                continue;
            }

            /*
             * Hotbar inventory indexes 0-8
             * = screen slots 36-44.
             */
            if (i < 9) {
                return 36 + i;
            }

            /*
             * Main inventory indexes 9-35
             * = screen slots 9-35.
             */
            return i;
        }

        /*
         * Check offhand.
         */
        ItemStack offhand =
            client.player.getOffHandStack();

        if (offhand.isOf(Items.SPRUCE_LOG)) {
            return 45;
        }

        return -1;
    }
}
