package no.bangerfabrikken.helsemnpoliti;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class PoliceEvents {
    private PoliceEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("politi")
            .then(Commands.literal("gi")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("spiller", EntityArgument.player())
                    .then(Commands.argument("rang", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("politi");
                            builder.suggest("overbetjent");
                            builder.suggest("politisjef");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                            String rankId = StringArgumentType.getString(context, "rang");
                            var rank = PoliceRank.byId(rankId);
                            if (rank.isEmpty()) {
                                context.getSource().sendFailure(Component.literal("Ugyldig rang. Bruk politi, overbetjent eller politisjef."));
                                return 0;
                            }
                            PoliceData data = PoliceData.get(context.getSource().getServer());
                            data.setRank(target.getUUID(), rank.get());
                            context.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName() + " er nå " + rank.get().displayName() + "."), true);
                            target.sendSystemMessage(Component.literal("§9[HMN Politi] §fDu er utnevnt til " + rank.get().displayName() + "."));
                            return Command.SINGLE_SUCCESS;
                        }))))
            .then(Commands.literal("fjern")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("spiller", EntityArgument.player())
                    .executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                        PoliceData data = PoliceData.get(context.getSource().getServer());
                        if (!data.removeRank(target.getUUID())) {
                            context.getSource().sendFailure(Component.literal(target.getGameProfile().getName() + " har ingen politirang."));
                            return 0;
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§ePolitirangen til " + target.getGameProfile().getName() + " er fjernet."), true);
                        target.sendSystemMessage(Component.literal("§c[HMN Politi] Polititilgangen din er fjernet."));
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("rang")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("spiller", EntityArgument.player())
                    .executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                        var rank = PoliceData.get(context.getSource().getServer()).getRank(target.getUUID());
                        context.getSource().sendSuccess(() -> Component.literal(rank.map(r -> target.getGameProfile().getName() + " har rang: " + r.displayName()).orElse(target.getGameProfile().getName() + " er ikke politi.")), false);
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("liste")
                .requires(source -> source.hasPermission(4))
                .executes(context -> {
                    var entries = PoliceData.get(context.getSource().getServer()).listRanks();
                    if (entries.isEmpty()) {
                        context.getSource().sendSuccess(() -> Component.literal("Ingen registrerte politibetjenter."), false);
                    } else {
                        context.getSource().sendSuccess(() -> Component.literal("§9§lHMN POLITI §7(" + entries.size() + ")"), false);
                        entries.forEach(entry -> context.getSource().sendSuccess(() -> Component.literal("§b" + entry.name() + " §7- §f" + entry.rank().displayName()), false));
                    }
                    return entries.size();
                })));

        dispatcher.register(Commands.literal("jailset")
            .requires(source -> source.hasPermission(4))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                PoliceData.get(context.getSource().getServer()).setJail(player);
                context.getSource().sendSuccess(() -> Component.literal("§aFengselets plassering er lagret."), true);
                return Command.SINGLE_SUCCESS;
            }));

        dispatcher.register(Commands.literal("jail")
            .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
            .then(Commands.argument("spiller", EntityArgument.player())
                .then(Commands.argument("minutter", IntegerArgumentType.integer(1, 10080))
                    .then(Commands.argument("grunn", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                            int minutes = IntegerArgumentType.getInteger(context, "minutter");
                            String reason = StringArgumentType.getString(context, "grunn");
                            PoliceData data = PoliceData.get(context.getSource().getServer());
                            if (!data.hasJail()) {
                                context.getSource().sendFailure(Component.literal("Fengselet er ikke konfigurert. En OP må bruke /jailset."));
                                return 0;
                            }
                            if (data.getRank(target.getUUID()).isPresent() || context.getSource().getEntity() == target) {
                                context.getSource().sendFailure(Component.literal("Du kan ikke fengsle en politibetjent eller deg selv."));
                                return 0;
                            }
                            data.jail(target, minutes, reason, context.getSource().getTextName());
                            context.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName() + " er fengslet i " + minutes + " minutt(er). Grunn: " + reason), true);
                            return Command.SINGLE_SUCCESS;
                        })))));

        dispatcher.register(Commands.literal("unjail")
            .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
            .then(Commands.argument("spiller", EntityArgument.player())
                .executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                    if (!PoliceData.get(context.getSource().getServer()).release(target)) {
                        context.getSource().sendFailure(Component.literal(target.getGameProfile().getName() + " sitter ikke i fengsel."));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName() + " er løslatt."), true);
                    return Command.SINGLE_SUCCESS;
                })));

        dispatcher.register(Commands.literal("jaillist")
            .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
            .executes(context -> {
                var prisoners = PoliceData.get(context.getSource().getServer()).listPrisoners();
                if (prisoners.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("Ingen innsatte."), false);
                prisoners.forEach(p -> context.getSource().sendSuccess(() -> Component.literal("§c" + p.name() + " §7- " + p.remaining() + " min igjen (§f" + p.reason() + "§7)"), false));
                return prisoners.size();
            }));

        dispatcher.register(Commands.literal("jailcheck")
            .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
            .then(Commands.argument("spiller", EntityArgument.player())
                .executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                    var prisoner = PoliceData.get(context.getSource().getServer()).getPrisoner(target.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal(prisoner.map(p -> target.getGameProfile().getName() + " er fengslet. Gjenstående: " + p.remaining() + " min. Grunn: " + p.reason()).orElse(target.getGameProfile().getName() + " sitter ikke i fengsel.")), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PoliceData data = PoliceData.get(player.getServer());
        data.enforceOnLogin(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PoliceData data = PoliceData.get(player.getServer());
        data.tickPrisoner(player);
    }

    @SubscribeEvent
    public static void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
            && PoliceData.get(player.getServer()).isJailed(player.getUUID())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("§cDu kan ikke ødelegge blokker mens du sitter i fengsel."), true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
            && PoliceData.get(player.getServer()).isJailed(player.getUUID())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("§cDu kan ikke plassere blokker mens du sitter i fengsel."), true);
        }
    }

    @SubscribeEvent
    public static void onAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
            && PoliceData.get(player.getServer()).isJailed(player.getUUID())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("§cDu kan ikke angripe mens du sitter i fengsel."), true);
        }
    }

    @SubscribeEvent
    public static void onCommand(net.neoforged.neoforge.event.CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!PoliceData.get(player.getServer()).isJailed(player.getUUID())) return;
        String raw = event.getParseResults().getReader().getString().trim().toLowerCase(java.util.Locale.ROOT);
        String root = raw.startsWith("/") ? raw.substring(1).split("\\s+", 2)[0] : raw.split("\\s+", 2)[0];
        if (java.util.Set.of("home", "spawn", "tpa", "tpaccept", "tpahere", "warp", "back", "rtp", "hub", "lobby")
            .contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("§cDu kan ikke bruke teleportkommandoer mens du sitter i fengsel."));
        }
    }

}
