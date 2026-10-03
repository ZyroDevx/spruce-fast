package com.example.sprucefast;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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
     * Ticks to wait between actions so the server
     * has time to send back the crafting result.
     */
    private static int cooldown = 0;

    /*
     * Counts how many times we shift-clicked while logs
     * were still in the grid (means inventory is probably full).
     */
    private static int stuckCounter = 0;

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
                    cooldown = 0;
                    stuckCounter = 0;

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

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            /*
             * Wait for the inventory SCREEN to actually open.
             */
            if (waitingForInventory) {

                if (client.currentScreen instanceof InventoryScreen) {
                    waitingForInventory = false;
                    cooldown = 2;
                }

                return;
            }

            /*
             * If the player closed the inventory, stop.
             */
            if (!(client.currentScreen instanceof InventoryScreen)) {
                enabled = false;
                client.player.sendMessage(
                    Text.literal("Spruce Fast: OFF (inventory closed)"),
                    true
                );
                return;
            }

            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        if (!(client.player.currentScreenHandler
                instanceof PlayerScreenHandler handler)) {
            return;
        }

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
         * Shift-click the result ONCE. Vanilla repeats
         * the craft until the logs are used up.
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
             * If logs are still there after several attempts,
             * the inventory is probably full.
             */
            if (++stuckCounter > 5) {

                enabled = false;
                stuckCounter = 0;

                client.player.sendMessage(
                    Text.literal("Spruce Fast: No room for planks."),
                    true
                );

                return;
            }

            client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
            );

            /*
             * Give the server time to respond.
             */
            cooldown = 3;

            return;
        }

        stuckCounter = 0;

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
         * Safety check: return anything left on the cursor.
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

        /*
         * Wait for the server to compute the crafting result.
         */
        cooldown = 3;
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
