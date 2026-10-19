package dev.wivuwzov.telegramauth;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(TelegramAuth.ID)
public final class TelegramAuth {
    public static final String ID = "telegramauth";
    private AuthService service;

    public TelegramAuth() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void start(ServerStartingEvent event) {
        service = new AuthService(event.getServer());
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    @SubscribeEvent
    public void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p) service.join(p);
    }

    @SubscribeEvent
    public void leave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p) service.leave(p);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && service != null) service.tick();
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && service != null && event.player instanceof ServerPlayer p) {
            service.playerTick(p);
        }
    }

    @SubscribeEvent
    public void preventChat(ServerChatEvent event) {
        if (service != null && service.blocked(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void preventCommands(CommandEvent event) {
        if (service != null && event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer p
                && service.blocked(p)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void preventBreak(BlockEvent.BreakEvent event) {
        if (service != null && event.getPlayer() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventAttack(AttackEntityEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventDamage(LivingAttackEvent event) {
        if (service == null) return;
        if (event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        } else if (event.getSource().getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventPickup(EntityItemPickupEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventSpecificClick(PlayerInteractEvent.EntityInteractSpecific event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preventLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (service != null && event.getEntity() instanceof ServerPlayer p && service.blocked(p)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        var player = Commands.argument("player", StringArgumentType.word());
        var literal = Commands.literal("tgauth").requires(source -> source.hasPermission(3));
        for (String action : new String[]{"reset", "ban", "unban", "kick", "whois"}) {
            literal.then(Commands.literal(action)
                .then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
                    String nickname = StringArgumentType.getString(ctx, "player");
                    String result;
                    if (service == null) result = "TelegramAuth не запущен.";
                    else result = "whois".equals(action) ? service.whois(nickname) : service.adminAction(action, nickname);
                    ctx.getSource().sendSuccess(() -> Component.literal(result), false);
                    return 1;
                })));
        }
        for (String command : new String[]{"online", "list"}) {
            literal.then(Commands.literal(command)
            .executes(ctx -> {
                String output = service == null ? "TelegramAuth не запущен." : service.onlinePlayers(1);
                ctx.getSource().sendSuccess(() -> Component.literal(output), false);
                return 1;
            })
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    String output = service == null ? "TelegramAuth не запущен."
                        : service.onlinePlayers(IntegerArgumentType.getInteger(ctx, "page"));
                    ctx.getSource().sendSuccess(() -> Component.literal(output), false);
                    return 1;
                })));
        }
        literal.then(Commands.literal("history")
            .then(Commands.argument("days", IntegerArgumentType.integer(1, 7))
                .executes(ctx -> {
                    String output = service == null ? "TelegramAuth не запущен." : service.history(IntegerArgumentType.getInteger(ctx, "days"), 1);
                    ctx.getSource().sendSuccess(() -> Component.literal(output), false);
                    return 1;
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String output = service == null ? "TelegramAuth не запущен."
                            : service.history(IntegerArgumentType.getInteger(ctx, "days"), IntegerArgumentType.getInteger(ctx, "page"));
                        ctx.getSource().sendSuccess(() -> Component.literal(output), false);
                        return 1;
                    }))));
        literal.then(Commands.literal("reload").executes(ctx -> {
            String output = service == null ? "TelegramAuth не запущен." : service.reload();
            ctx.getSource().sendSuccess(() -> Component.literal(output), false);
            return 1;
        }));
        event.getDispatcher().register(literal);
    }
}
