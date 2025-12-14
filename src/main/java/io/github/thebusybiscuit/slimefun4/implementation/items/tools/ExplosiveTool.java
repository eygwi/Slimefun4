package io.github.thebusybiscuit.slimefun4.implementation.items.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.inventory.ItemStack;

// Imports untuk ItemsAdder (Hapus jika tidak pakai ItemsAdder)
import dev.lone.itemsadder.api.CustomBlock;

import io.github.thebusybiscuit.slimefun4.api.events.ExplosiveToolBreakBlocksEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.DamageableItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.ToolUseHandler;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.utils.tags.SlimefunTag;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * This {@link SlimefunItem} is a super class for items like the {@link ExplosivePickaxe} or {@link ExplosiveShovel}.
 * * Combined Official Logic + Chinese Fork Fixes (ExoticGarden & ItemsAdder support).
 */
public class ExplosiveTool extends SimpleSlimefunItem<ToolUseHandler> implements NotPlaceable, DamageableItem {

    private final ItemSetting<Boolean> damageOnUse = new ItemSetting<>(this, "damage-on-use", true);
    private final ItemSetting<Boolean> callExplosionEvent = new ItemSetting<>(this, "call-explosion-event", false);
    
    // Cek apakah ItemsAdder terinstall agar tidak error saat runtime
    private final boolean isItemsAdderLoaded;

    @ParametersAreNonnullByDefault
    public ExplosiveTool(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
        addItemSetting(damageOnUse, callExplosionEvent);
        this.isItemsAdderLoaded = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    @Nonnull
    @Override
    public ToolUseHandler getItemHandler() {
        return (e, tool, fortune, drops) -> {
            Player p = e.getPlayer();

            if (!p.isSneaking()) {
                Block b = e.getBlock();

                // Visual explosion only
                b.getWorld().createExplosion(b.getLocation(), 0);
                SoundEffect.EXPLOSIVE_TOOL_EXPLODE_SOUND.playAt(b);

                List<Block> blocks = findBlocks(b);
                breakBlocks(e, p, tool, b, blocks, drops);
            }
        };
    }

    @ParametersAreNonnullByDefault
    private void breakBlocks(BlockBreakEvent e, Player p, ItemStack item, Block b, List<Block> blocks, List<ItemStack> drops) {
        List<Block> blocksToDestroy = new ArrayList<>();

        if (callExplosionEvent.getValue()) {
            BlockExplodeEvent blockExplodeEvent = new BlockExplodeEvent(b, b.getState(), blocks, 0.0F, ExplosionResult.DESTROY);
            Bukkit.getServer().getPluginManager().callEvent(blockExplodeEvent);

            if (!blockExplodeEvent.isCancelled()) {
                for (Block block : blockExplodeEvent.blockList()) {
                    processBlockForDestruction(p, block, blocksToDestroy, drops);
                }
            }
        } else {
            for (Block block : blocks) {
                processBlockForDestruction(p, block, blocksToDestroy, drops);
            }
        }

        ExplosiveToolBreakBlocksEvent event = new ExplosiveToolBreakBlocksEvent(p, b, blocksToDestroy, item, this);
        Bukkit.getServer().getPluginManager().callEvent(event);

        if (Bukkit.getPluginManager().isPluginEnabled("ExoticGarden")) {
            blocksToDestroy.sort((block1, block2) -> Boolean.compare(
                    block2.getType() == Material.PLAYER_HEAD || block2.getType() == Material.PLAYER_WALL_HEAD,
                    block1.getType() == Material.PLAYER_HEAD || block1.getType() == Material.PLAYER_WALL_HEAD
            ));
        }

        if (!event.isCancelled()) {
            for (Block block : blocksToDestroy) {
                breakBlock(e, p, item, block, drops);
            }
        }
    }

    private void processBlockForDestruction(Player p, Block block, List<Block> blocksToDestroy, List<ItemStack> drops) {
        if (canBreak(p, block)) {
            if (isItemsAdderLoaded && CustomBlock.byAlreadyPlaced(block) != null) {
                CustomBlock cb = CustomBlock.byAlreadyPlaced(block);
                drops.addAll(cb.getLoot());
                cb.remove();
                return; 
            }
            blocksToDestroy.add(block);
        }
    }

    @Nonnull
    private List<Block> findBlocks(@Nonnull Block b) {
        List<Block> blocks = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    blocks.add(b.getRelative(x, y, z));
                }
            }
        }
        return blocks;
    }

    @Override
    public boolean isDamageable() {
        return damageOnUse.getValue();
    }

    protected boolean canBreak(@Nonnull Player p, @Nonnull Block b) {
        if (b.isEmpty() || b.isLiquid()) {
            return false;
        } else if (SlimefunTag.UNBREAKABLE_MATERIALS.isTagged(b.getType())) {
            return false;
        } else if (!b.getWorld().getWorldBorder().isInside(b.getLocation())) {
            return false;
        } else {
            return Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), io.github.bakedlibs.dough.protection.Interaction.BREAK_BLOCK);
        }
    }

    @ParametersAreNonnullByDefault
    private void breakBlock(BlockBreakEvent event, Player player, ItemStack item, Block block, List<ItemStack> drops) {
        Slimefun.getProtectionManager().logAction(player, block, io.github.bakedlibs.dough.protection.Interaction.BREAK_BLOCK);
        
        Material material = block.getType();
        block.getWorld().playEffect(block.getLocation(), Effect.STEP_SOUND, material);
        Location blockLocation = block.getLocation();

        SlimefunItem sfItem = BlockStorage.check(blockLocation);
        AtomicBoolean isUseVanillaBlockBreaking = new AtomicBoolean(true);

        if (sfItem != null) {

            if (Bukkit.getPluginManager().isPluginEnabled("ExoticGarden") 
                    && (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD)) {
                
                Location leavesLocation = blockLocation.clone().add(0, -1, 0);
                Block leaveBlock = leavesLocation.getBlock();

                if (Tag.LEAVES.isTagged(leaveBlock.getType())) {
                    SlimefunItem leavesSfItem = BlockStorage.check(leavesLocation);
                    
                    if (leavesSfItem != null) {
                        Collection<ItemStack> sfItemDrops = sfItem.getDrops();
                        Collection<ItemStack> leavesSfItemDrops = leavesSfItem.getDrops();
                        
                        if (!sfItemDrops.isEmpty() && !leavesSfItemDrops.isEmpty()) {
                             leaveBlock.setType(Material.AIR);
                             BlockStorage.clearBlockInfo(leavesLocation);
                             isUseVanillaBlockBreaking.set(false);
                        }
                    }
                }
            }

            if (isUseVanillaBlockBreaking.get()) {
                isUseVanillaBlockBreaking.set(sfItem.useVanillaBlockBreaking());
            }

            if (!isUseVanillaBlockBreaking.get()) {
                BlockBreakEvent dummyEvent = new BlockBreakEvent(block, event.getPlayer());
                
                sfItem.callItemHandler(BlockBreakHandler.class, handler -> handler.onPlayerBreak(dummyEvent, item, drops));

                if (!dummyEvent.isCancelled()) {
                    drops.addAll(sfItem.getDrops(player));
                    block.setType(Material.AIR);
                    BlockStorage.clearBlockInfo(blockLocation);
                }
                
                return; 
            }
        }
        block.breakNaturally(item);
        damageItem(player, item);
    }
}