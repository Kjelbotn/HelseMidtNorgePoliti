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
                            data.logAction("Rang tildelt", target.getUUID(), target.getGameProfile().getName(), context.getSource().getTextName(), rank.get().displayName());
                            data.refreshPoliceTag(target);
                            context.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName() + " er nå " + rank.get().displayName() + "."), true);
                            target.sendSystemMessage(Component.literal("§9[HMN Politi] §fDu er utnevnt til " + rank.get().displayName() + "."));
                            context.getSource().getServer().getPlayerList().broadcastSystemMessage(
                                Component.literal("§9[HMN Politi] §b" + target.getGameProfile().getName()
                                    + " §fhar blitt forfremmet til §9" + rank.get().displayName() + "§f! Gratulerer :D"),
                                false);
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

        dispatcher.register(Commands.literal("politi")
            .then(Commands.literal("hjelp").executes(context -> {
                var source = context.getSource();
                source.sendSuccess(() -> Component.literal("§9§lHMN POLITI – KOMMANDOOVERSIKT"), false);
                source.sendSuccess(() -> Component.literal("§b/politi hjelp §7– viser denne oversikten"), false);
                source.sendSuccess(() -> Component.literal("§b/politi status §7– viser pålogget politi og innsatte"), false);
                source.sendSuccess(() -> Component.literal("§b/politi radio <melding> §7– sender melding på politiets samband"), false);
                if (PoliceData.get(source.getServer()).isPolice(source)) {
                    source.sendSuccess(() -> Component.literal("§e/jail <spiller> <minutter> <grunn> §7– sett i fengsel"), false);
                    source.sendSuccess(() -> Component.literal("§e/unjail <spiller> §7– løslat en innsatt"), false);
                    source.sendSuccess(() -> Component.literal("§e/jaillist §7– oversikt over innsatte"), false);
                    source.sendSuccess(() -> Component.literal("§e/jailcheck <spiller> §7– sjekk fengselsstatus"), false);
                    source.sendSuccess(() -> Component.literal("§e/politi alarm status|test|historikk §7– fengselsalarm"), false);
                }
                if (source.hasPermission(4)) {
                    source.sendSuccess(() -> Component.literal("§c/politi gi|fjern|rang|liste §7– administrer politiranger"), false);
                    source.sendSuccess(() -> Component.literal("§c/jailset, /jailarea og /jailcell §7– konfigurer fengselet"), false);
                }
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("radio")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .then(Commands.argument("melding", StringArgumentType.greedyString())
                    .executes(context -> {
                        var source = context.getSource();
                        String message = StringArgumentType.getString(context, "melding").trim();
                        if (message.isEmpty()) {
                            source.sendFailure(Component.literal("Skriv en melding som skal sendes på sambandet."));
                            return 0;
                        }
                        PoliceData data = PoliceData.get(source.getServer());
                        String senderName = source.getTextName();
                        String rank = source.getEntity() instanceof ServerPlayer player
                            ? data.getRank(player.getUUID()).map(PoliceRank::displayName).orElse("Operatør")
                            : "Operatør";
                        Component radioMessage = Component.literal("§9§l[POLITIRADIO] §b" + rank + " " + senderName + " §8» §f" + message);
                        data.logAction("Radiosamband", null, "", senderName, message);
                        int recipients = 0;
                        for (ServerPlayer online : source.getServer().getPlayerList().getPlayers()) {
                            if (online.hasPermissions(2) || data.getRank(online.getUUID()).isPresent()) {
                                online.sendSystemMessage(radioMessage);
                                recipients++;
                            }
                        }
                        final int recipientCount = recipients;
                        source.sendSuccess(() -> Component.literal("§aMeldingen ble sendt på politiets samband (" + recipientCount + " mottakere)."), false);
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("status")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .executes(context -> {
                    var source = context.getSource();
                    PoliceData data = PoliceData.get(source.getServer());
                    var officers = source.getServer().getPlayerList().getPlayers().stream()
                        .filter(player -> data.getRank(player.getUUID()).isPresent()).toList();
                    var prisoners = data.listPrisoners();
                    source.sendSuccess(() -> Component.literal("§9§lHMN POLITI – STATUS"), false);
                    source.sendSuccess(() -> Component.literal("§bPålogget politi: §f" + officers.size()), false);
                    officers.forEach(player -> source.sendSuccess(() -> Component.literal("§7• §b" + player.getGameProfile().getName()
                        + " §8– §f" + data.getRank(player.getUUID()).map(PoliceRank::displayName).orElse("Politi")), false));
                    source.sendSuccess(() -> Component.literal("§cInnsatte: §f" + prisoners.size()), false);
                    prisoners.forEach(prisoner -> source.sendSuccess(() -> Component.literal("§7• §c" + prisoner.name()
                        + " §8– §f" + prisoner.remaining() + " min igjen"), false));
                    return Command.SINGLE_SUCCESS;
                })));

        dispatcher.register(Commands.literal("politi")
            .then(Commands.literal("vakt").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                PoliceData data = PoliceData.get(context.getSource().getServer());
                if (!data.isPolice(context.getSource()) || data.getRank(player.getUUID()).isEmpty()) {
                    context.getSource().sendFailure(Component.literal("Du må ha politirang for å kunne gå på vakt."));
                    return 0;
                }
                boolean onDuty = data.toggleDuty(player);
                if (onDuty) {
                    player.sendSystemMessage(Component.literal("§a§l[HMN POLITI] §fDu er nå §aPÅ VAKT§f. [Politi]-taggen er aktiv."));
                } else {
                    player.sendSystemMessage(Component.literal("§e§l[HMN POLITI] §fDu er nå §eAV VAKT§f. [Politi]-taggen er fjernet."));
                }
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("vaktliste")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .executes(context -> {
                    var names = PoliceData.get(context.getSource().getServer()).getOnDutyNames(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("§9§lHMN POLITI – PÅ VAKT §7(" + names.size() + ")"), false);
                    if (names.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("§7Ingen betjenter er på vakt akkurat nå."), false);
                    names.forEach(name -> context.getSource().sendSuccess(() -> Component.literal("§b• " + name), false));
                    return names.size();
                })));

        dispatcher.register(Commands.literal("jailcell")
            .then(Commands.literal("set")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("celle", IntegerArgumentType.integer(1, 6))
                    .then(Commands.literal("spawn").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int id = IntegerArgumentType.getInteger(context, "celle");
                        PoliceData.get(context.getSource().getServer()).setCellSpawn(id, player);
                        context.getSource().sendSuccess(() -> Component.literal("§aSpawn for celle " + id + " er lagret."), true);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("door")
                        .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                            .then(Commands.argument("pos2", BlockPosArgument.blockPos()).executes(context -> {
                                ServerPlayer player=context.getSource().getPlayerOrException();
                                int id=IntegerArgumentType.getInteger(context,"celle");
                                var p1=BlockPosArgument.getLoadedBlockPos(context,"pos1");
                                var p2=BlockPosArgument.getLoadedBlockPos(context,"pos2");
                                if (!PoliceData.get(context.getSource().getServer()).setCellDoor(id,player.serverLevel(),p1,p2)) {
                                    context.getSource().sendFailure(Component.literal("Velg de to nederste dørblokkene side ved side i en 2x2-dør."));
                                    return 0;
                                }
                                context.getSource().sendSuccess(() -> Component.literal("§a2x2-døren til celle "+id+" er registrert."),true);
                                return Command.SINGLE_SUCCESS;
                            }))))
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
                    final int cellId = id;
                    context.getSource().sendSuccess(() -> Component.literal("§9Celle " + cellId + " §7- " +
                        (data.isCellConfigured(cellId) ? "konfigurert" : "ufullstendig") + " / " +
                        (data.isCellOccupied(cellId) ? "§copptatt" : "§aledig")), false);
                }
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("assign")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
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
                            if (data.getRank(target.getUUID()).isPresent() && !context.getSource().hasPermission(4)) {
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

        dispatcher.register(Commands.literal("politi")
            .then(Commands.literal("alarm")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§cHMN fengselsalarm er aktiv. Rømningsforsøk varsles og innsatte returneres umiddelbart."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("test").executes(context -> {
                    ServerPlayer sender = context.getSource().getPlayerOrException();
                    PoliceData.get(context.getSource().getServer()).testAlarm(sender);
                    context.getSource().sendSuccess(() -> Component.literal("§aAlarmtest sendt til tilgjengelig politi og OP."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("historikk").executes(context -> {
                    var history = PoliceData.get(context.getSource().getServer()).getAlarmHistory();
                    if (history.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("Ingen registrerte fengselsalarmer."), false);
                    else history.stream().limit(10).forEach(line -> context.getSource().sendSuccess(() -> Component.literal("§c" + line), false));
                    return history.size();
                }))));

        dispatcher.register(Commands.literal("jailcheck")
            .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
            .then(Commands.argument("spiller", EntityArgument.player())
                .executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                    var prisoner = PoliceData.get(context.getSource().getServer()).getPrisoner(target.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal(prisoner.map(p -> target.getGameProfile().getName() + " er fengslet. Gjenstående: " + p.remaining() + " min. Grunn: " + p.reason()).orElse(target.getGameProfile().getName() + " sitter ikke i fengsel.")), false);
                    return Command.SINGLE_SUCCESS;
                })));
        dispatcher.register(Commands.literal("politi")
            .then(Commands.literal("logg")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .executes(context -> {
                    var entries = PoliceData.get(context.getSource().getServer()).getPoliceLog(null);
                    context.getSource().sendSuccess(() -> Component.literal("§9§lPOLITILOGG §7(siste " + entries.size() + ")"), false);
                    if (entries.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("§7Ingen hendelser er registrert ennå."), false);
                    entries.forEach(entry -> context.getSource().sendSuccess(
                        () -> Component.literal("§7" + PoliceData.formatLogEntry(entry)), false));
                    return entries.size();
                })
                .then(Commands.argument("spiller", EntityArgument.player()).executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                    var entries = PoliceData.get(context.getSource().getServer()).getPoliceLog(target.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("§9§lPOLITILOGG §7– " + target.getGameProfile().getName()), false);
                    if (entries.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("§7Ingen loggførte hendelser for spilleren."), false);
                    entries.forEach(entry -> context.getSource().sendSuccess(
                        () -> Component.literal("§7" + PoliceData.formatLogEntry(entry)), false));
                    return entries.size();
                })))
            .then(Commands.literal("arresthistorikk")
                .requires(source -> PoliceData.get(source.getServer()).isPolice(source))
                .then(Commands.argument("spiller", EntityArgument.player()).executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "spiller");
                    var entries = PoliceData.get(context.getSource().getServer()).getArrestHistory(target.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("§c§lARRESTHISTORIKK §7– " + target.getGameProfile().getName()), false);
                    if (entries.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("§7Ingen tidligere arrestasjoner eller løslatelser registrert."), false);
                    entries.forEach(entry -> context.getSource().sendSuccess(
                        () -> Component.literal("§7" + PoliceData.formatLogEntry(entry)), false));
                    return entries.size();
                })))));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PoliceData data = PoliceData.get(player.getServer());
        data.enforceOnLogin(player);
        data.refreshPoliceTag(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PoliceData.get(player.getServer()).endDuty(player);
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
            PoliceData.get(player.getServer()).triggerAlarm(player, "Forsøk på å ødelegge blokk i fengselet");
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

        event.setCanceled(true);
        syncPrisonerInventory(player);
        player.displayClientMessage(Component.literal("§cDu kan ikke plassere blokker mens du sitter i fengsel."), true);
    }

    @SubscribeEvent
    public static void onPrisonerDoorInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PoliceData data = PoliceData.get(player.getServer());
        if (!data.isJailed(player.getUUID())) return;
        var block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (block instanceof DoorBlock) {
            if (data.isConfiguredCellDoor(player.level().dimension(), event.getPos())) {
                if (!data.canUseCellDoor(player.getUUID(), player.level().dimension(), event.getPos())) {
                    event.setCanceled(true);
                    if (data.isNightLockdown()) data.triggerAlarm(player, "Forsøk på å åpne låst celledør");
                    player.displayClientMessage(Component.literal(data.isNightLockdown()
                        ? "§cCellen er låst for natten." : "§cDette er ikke din celle."), true);
                }
            } else if (data.isNightLockdown()) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("§cDu kan ikke åpne dører under nattinnlåsing."), true);
            }
        } else if (block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
            if (data.isNightLockdown()) {
                event.setCanceled(true);
                player.displayClientMessage(Component.literal("§cDu kan ikke åpne dører eller porter under nattinnlåsing."), true);
            }
        }
    }

    private static void syncPrisonerInventory(ServerPlayer player) {
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
