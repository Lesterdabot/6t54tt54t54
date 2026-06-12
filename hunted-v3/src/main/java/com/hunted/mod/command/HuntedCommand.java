package com.hunted.mod.command;

import com.hunted.mod.event.HuntedEventManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class HuntedCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("hunted")
            .then(Commands.literal("start")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    boolean ok = HuntedEventManager.startEvent();
                    if (ok) {
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("§a[Hunted] Event started!"), true);
                    } else {
                        ctx.getSource().sendFailure(Component.literal(
                            "§c[Hunted] Event already running! Phase: " + HuntedEventManager.getPhase()));
                    }
                    return ok ? 1 : 0;
                })
            )
            .then(Commands.literal("status")
                .executes(ctx -> {
                    String phase  = HuntedEventManager.getPhase().name();
                    String target = HuntedEventManager.getTargetName();
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("§6[Hunted] §ePhase: §f" + phase
                            + (phase.equals("ACTIVE") ? "  §eTarget: §c" + target : "")),
                        false);
                    return 1;
                })
            )
        );
    }
}
