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

    /*
     * Crafting speed.
     *
     * 20 result clicks per tick.
     */
    private static final int CRAFTS_PER_TICK = 20;

    /*
     * We need to wait for Minecraft's normal
     * inventory key handling after simulating E.
     */
    private static boolean waitingForInventory = false;

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
             * BACKSPACE = toggle Spruce Fast
             */
            while (startKey.wasPressed()) {

                if (client.player == null) {
                    return;
                }

                enabled = !enabled;

                if (enabled) {

                    waitingForInventory = true;

                    /*
                     * ACTUALLY SIMULATE PRESSING E.
                     *
                     * This sends the E key into Minecraft's
                     * normal KeyBinding system.
                     */
                    KeyBinding.onKeyPressed(
                        InputUtil.Type.KEYSYM.createFromCode(
                            GLFW.GLFW_KEY_E
                        )
                    );

                    client.player.sendMessage(
                        Text.literal(
                            "Spruce Fast: ON"
                        ),
                        true
                    );

                } else {

                    waitingForInventory = false;

                    client.player.sendMessage(
                        Text.literal(
                            "Spruce Fast: OFF"
                        ),
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
             * Wait until Minecraft has processed the
             * simulated E and opened the inventory.
             */
            if (waitingForInventory) {

                if (client.player.currentScreenHandler
                        instanceof PlayerScreenHandler) {

                    waitingForInventory = false;
                }

                return;
            }

            /*
             * Now the inventory is open.
             */
            run(client);
        });
    }

    private static void run(MinecraftClient client) {

        /*
         * We specifically need the player's inventory
         * screen handler.
         */
        if (!(client.player.currentScreenHandler
                instanceof PlayerScreenHandler)) {

            return;
        }

        PlayerScreenHandler handler =
            (PlayerScreenHandler)
                client.player.currentScreenHandler;

        /*
         * Don't interfere with anything currently
         * held by the mouse cursor.
         */
        if (!handler.getCursorStack().isEmpty()) {
            return;
        }

        /*
         * ------------------------------------------------
         * STEP 1:
         * If there are logs already in the 2x2 crafting
         * grid, take the plank results.
         * ------------------------------------------------
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

            takeCraftingResults(client, handler);

            return;
        }

        /*
         * ------------------------------------------------
         * STEP 2:
         * Crafting grid is empty.
         *
         * Find a spruce log stack.
         * ------------------------------------------------
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
         * ------------------------------------------------
         * STEP 3:
         * Pick up the ENTIRE spruce log stack.
         * ------------------------------------------------
         */
        client.interactionManager.clickSlot(
            handler.syncId,
            logSlot,
            0,
            SlotActionType.PICKUP,
            client.player
        );

        /*
         * ------------------------------------------------
         * STEP 4:
         * Put the ENTIRE stack into crafting slot #1.
         *
         * PlayerScreenHandler:
         *
         * 0 = crafting result
         * 1 = crafting input
         * 2 = crafting input
         * 3 = crafting input
         * 4 = crafting input
         * ------------------------------------------------
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
     * Check whether the 2x2 crafting grid contains
     * anything.
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
     * Make sure the crafting grid contains only
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
     * Take the crafting result extremely quickly.
     *
     * 1 spruce log = 4 spruce planks.
     *
     * The whole log stack stays inside the crafting
     * slot while the result is repeatedly taken.
     */
    private static void takeCraftingResults(
            MinecraftClient client,
            PlayerScreenHandler handler) {

        for (int i = 0; i < CRAFTS_PER_TICK; i++) {

            ItemStack result =
                handler.getOutputSlot().getStack();

            /*
             * Nothing to craft.
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
             * Shift-click the result into inventory.
             */
            client.interactionManager.clickSlot(
                handler.syncId,
                0,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
            );
        }
    }

    /*
     * Find a spruce log stack.
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
             * correspond to screen slots 36-44.
             */
            if (i < 9) {
                return 36 + i;
            }

            /*
             * Main inventory indexes 9-35
             * correspond to screen slots 9-35.
             */
            return i;
        }

        /*
         * Offhand.
         */
        ItemStack offhand =
            client.player.getOffHandStack();

        if (offhand.isOf(Items.SPRUCE_LOG)) {
            return 45;
        }

        return -1;
    }
}
