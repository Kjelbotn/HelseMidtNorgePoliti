package no.bangerfabrikken.helsemnpoliti;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;

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
                            data.refreshPoliceTag(target);
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
                        data.refreshPoliceTag(target);
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

        dispatcher.register(Commands.literal("jailcell")
            .requires(source -> source.hasPermission(4))
            .then(Commands.literal("set")
                .then(Commands.argument("celle", IntegerArgumentType.integer(1, 6))
                    .then(Commands.literal("spawn").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int id = IntegerArgumentType.getInteger(context, "celle");
                        PoliceData.get(context.getSource().getServer()).setCellSpawn(id, player);
                        context.getSource().sendSuccess(() -> Component.literal("§aSpawn for celle " + id + " er lagret."), true);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("door")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            int id = IntegerArgumentType.getInteger(context, "celle");
                            var pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            if (!(player.level().getBlockState(pos).getBlock() instanceof DoorBlock)) {
                                context.getSource().sendFailure(Component.literal("Du må angi en vanlig dørblokk."));
                                return 0;
                            }
                            PoliceData.get(context.getSource().getServer()).setCellDoor(id, player.serverLevel(), pos);
                            context.getSource().sendSuccess(() -> Component.literal("§aDøren til celle " + id + " er registrert."), true);
                            return Command.SINGLE_SUCCESS;
                        })))
                    .then(Commands.literal("pos1").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int id = IntegerArgumentType.getInteger(context, "celle");
                        PoliceData.get(context.getSource().getServer()).setCellCorner(id, player, true);
                        context.getSource().sendSuccess(() -> Component.literal("§aFørste hjørne for celle " + id + " er lagret."), true);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("pos2").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int id = IntegerArgumentType.getInteger(context, "celle");
                        PoliceData.get(context.getSource().getServer()).setCellCorner(id, player, false);
                        context.getSource().sendSuccess(() -> Component.literal("§aAndre hjørne for celle " + id + " er lagret."), true);
                        return Command.SINGLE_SUCCESS;
                    }))))
            .then(Commands.literal("info")
                .then(Commands.argument("celle", IntegerArgumentType.integer(1, 6)).executes(context -> {
                    int id = IntegerArgumentType.getInteger(context, "celle");
                    PoliceData data = PoliceData.get(context.getSource().getServer());
                    var c = data.getCell(id);
                    context.getSource().sendSuccess(() -> Component.literal("§9Celle " + id + " §7- Spawn: " + (c.spawnConfigured ? "OK" : "mangler")
                        + ", dør: " + (c.doorConfigured ? "OK" : "mangler") + ", område: " + (c.areaConfigured ? "OK" : "mangler")
                        + ", status: " + (data.isCellOccupied(id) ? "opptatt" : "ledig")), false);
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("list").executes(context -> {
                PoliceData data = PoliceData.get(context.getSource().getServer());
                for (int id = 1; id <= 6; id++) {
                    var c = data.getCell(id);
                    context.getSource().sendSuccess(() -> Component.literal("§9Celle " + id + " §7- " +
                        (data.isCellConfigured(id) ? "konfigurert" : "ufullstendig") + " / " +
                        (data.isCellOccupied(id) ? "§copptatt" : "§aledig")), false);
                }
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("assign")
                .then(Commands.argument("spiller", EntityArgument.player())
                    .then(Commands.argument("celle", IntegerArgumentType.integer(1, 6)).executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                        int id = IntegerArgumentType.getInteger(context, "celle");
                        PoliceData data = PoliceData.get(context.getSource().getServer());
                        if (!data.isJailed(target.getUUID())) {
                            context.getSource().sendFailure(Component.literal("Spilleren sitter ikke i fengsel."));
                            return 0;
                        }
                        if (!data.isCellConfigured(id) || data.isCellOccupied(id)) {
                            context.getSource().sendFailure(Component.literal("Cellen er ikke komplett konfigurert eller er opptatt."));
                            return 0;
                        }
                        if (!data.assignCell(target, id)) {
                            context.getSource().sendFailure(Component.literal("Kunne ikke tildele cellen."));
                            return 0;
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName() + " er tildelt celle " + id + "."), true);
                        return Command.SINGLE_SUCCESS;
                    }))))
            .then(Commands.literal("clear")
                .then(Commands.argument("celle", IntegerArgumentType.integer(1, 6)).executes(context -> {
                    int id = IntegerArgumentType.getInteger(context, "celle");
                    PoliceData data = PoliceData.get(context.getSource().getServer());
                    if (data.isCellOccupied(id)) {
                        context.getSource().sendFailure(Component.literal("Cellen er opptatt og kan ikke nullstilles."));
                        return 0;
                    }
                    data.clearCell(id);
                    context.getSource().sendSuccess(() -> Component.literal("§aCelle " + id + " er nullstilt."), true);
                    return Command.SINGLE_SUCCESS;
                }))));

        dispatcher.register(Commands.literal("jailarea")
            .requires(source -> source.hasPermission(4))
            .then(Commands.literal("pos1").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                PoliceData.get(context.getSource().getServer()).setJailAreaCorner(player, true);
                context.getSource().sendSuccess(() -> Component.literal("§aFengselets første ytterhjørne er lagret."), true);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("pos2").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                PoliceData.get(context.getSource().getServer()).setJailAreaCorner(player, false);
                context.getSource().sendSuccess(() -> Component.literal("§aFengselets andre ytterhjørne er lagret."), true);
                return Command.SINGLE_SUCCESS;
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
                            if (data.getRank(target.getUUID()).isPresent() && context.getSource().getEntity() != target) {
                                context.getSource().sendFailure(Component.literal("Du kan ikke fengsle en politibetjent."));
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
        data.refreshPoliceTag(player);
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
            syncPrisonerInventory(player);
            player.displayClientMessage(Component.literal("§cDu kan ikke plassere blokker mens du sitter i fengsel."), true);
        }
    }

    @SubscribeEvent
    public static void onPrisonerBlockPlacementAttempt(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PoliceData.get(player.getServer()).isJailed(player.getUUID())) return;
        if (!(player.getItemInHand(event.getHand()).getItem() instanceof BlockItem)) return;

        // Cancel before vanilla consumes the item stack for block placement.
        event.setCanceled(true);
        syncPrisonerInventory(player);
        player.displayClientMessage(Component.literal("§cDu kan ikke plassere blokker mens du sitter i fengsel."), true);
    }

    @SubscribeEvent
    public static void onPrisonerDoorInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PoliceData.get(player.getServer()).isJailed(player.getUUID())) return;
        var block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("§cDu kan ikke åpne dører eller porter mens du sitter i fengsel."), true);
        }
    }

    private static void syncPrisonerInventory(ServerPlayer player) {
        // Client-side block placement prediction can make the held stack appear empty.
        // Container id -2 addresses the player's inventory directly; send authoritative
        // copies of both hand slots even when the server-side stack did not change.
        var inventory = player.getInventory();
        int selectedSlot = inventory.selected;
        player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, selectedSlot,
            inventory.getItem(selectedSlot).copy()));
        player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, 40,
            inventory.offhand.get(0).copy()));
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
        PoliceData data = PoliceData.get(player.getServer());
        if (!data.isJailed(player.getUUID())) return;
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal("§c" + data.remainingTimeMessage(player.getUUID())));
    }

}
