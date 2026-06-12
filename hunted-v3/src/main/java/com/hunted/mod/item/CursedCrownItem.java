package com.hunted.mod.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CursedCrownItem extends Item {

    public static final String REGISTRY_NAME = "cursed_crown";

    public CursedCrownItem() {
        super(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.EPIC)
            .fireResistant()
        );
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§cThe hunt never ends."));
        tooltip.add(Component.literal("§7Your location is revealed to all."));
        tooltip.add(Component.literal("§8Drop it — or die trying."));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true; // glowing enchant sheen
    }
}
