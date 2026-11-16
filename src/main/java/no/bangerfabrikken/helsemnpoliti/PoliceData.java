package no.bangerfabrikken.helsemnpoliti;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;

public final class PoliceData extends SavedData {
    private static final String DATA_NAME = "helsemnpoliti_data";
    private static final String POLICE_TEAM = "helsemnpoliti_police";
    private final Map<UUID, PoliceRank> ranks = new HashMap<>();
    private final Map<UUID, Prisoner> prisoners = new HashMap<>();
    private String jailDimension = "";
    private double jailX, jailY, jailZ;
    private float jailYaw, jailPitch;
    private boolean jailConfigured;

    public record RankEntry(String name, PoliceRank rank) {}
    public record PrisonerInfo(String name, String reason, long remaining) {}
    private record Prisoner(String name, String reason, String officer, long releaseAt,
                            String returnDimension, double returnX, double returnY, double returnZ,
                            float returnYaw, float returnPitch, int cellId) {
        long remainingMillis() { return Math.max(0L, releaseAt - System.currentTimeMillis()); }
        long remainingMinutes() { return (remainingMillis() + 59_999L) / 60_000L; }
    }

    public static PoliceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(PoliceData::new, PoliceData::load, null), DATA_NAME);
    }

    public static PoliceData load(CompoundTag tag, HolderLookup.Provider registries) {
        PoliceData data = new PoliceData();
        ListTag rankList = tag.getList("Ranks", 10);
        for (int i = 0; i < rankList.size(); i++) {
            CompoundTag entry = rankList.getCompound(i);
            try {
                data.ranks.put(UUID.fromString(entry.getString("UUID")),
                    PoliceRank.byId(entry.getString("Rank")).orElseThrow());
            } catch (RuntimeException ignored) {}
        }
        ListTag jailList = tag.getList("Prisoners", 10);
        for (int i = 0; i < jailList.size(); i++) {
            CompoundTag entry = jailList.getCompound(i);
            try {
                UUID id = UUID.fromString(entry.getString("UUID"));
                data.prisoners.put(id, new Prisoner(entry.getString("Name"), entry.getString("Reason"),
                    entry.getString("Officer"), entry.getLong("ReleaseAt"),
                    entry.getString("ReturnDimension"), entry.getDouble("ReturnX"), entry.getDouble("ReturnY"),
                    entry.getDouble("ReturnZ"), entry.getFloat("ReturnYaw"), entry.getFloat("ReturnPitch"), entry.getInt("CellId")));
            } catch (RuntimeException ignored) {}
        }
        data.jailConfigured = tag.getBoolean("JailConfigured");
        data.jailDimension = tag.getString("JailDimension");
        data.jailX = tag.getDouble("JailX");
        data.jailY = tag.getDouble("JailY");
        data.jailZ = tag.getDouble("JailZ");
        data.jailYaw = tag.getFloat("JailYaw");
        data.jailPitch = tag.getFloat("JailPitch");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag rankList = new ListTag();
        ranks.forEach((uuid, rank) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("UUID", uuid.toString());
            entry.putString("Rank", rank.id());
            rankList.add(entry);
        });
        tag.put("Ranks", rankList);

        ListTag jailList = new ListTag();
        prisoners.forEach((uuid, prisoner) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("UUID", uuid.toString());
            entry.putString("Name", prisoner.name());
            entry.putString("Reason", prisoner.reason());
            entry.putString("Officer", prisoner.officer());
            entry.putLong("ReleaseAt", prisoner.releaseAt());
            entry.putString("ReturnDimension", prisoner.returnDimension());
            entry.putDouble("ReturnX", prisoner.returnX());
            entry.putDouble("ReturnY", prisoner.returnY());
            entry.putDouble("ReturnZ", prisoner.returnZ());
            entry.putFloat("ReturnYaw", prisoner.returnYaw());
            entry.putFloat("ReturnPitch", prisoner.returnPitch());
            entry.putInt("CellId", prisoner.cellId());
            jailList.add(entry);
        });
        tag.put("Prisoners", jailList);
        tag.putBoolean("JailConfigured", jailConfigured);
        tag.putString("JailDimension", jailDimension);
        tag.putDouble("JailX", jailX);
        tag.putDouble("JailY", jailY);
        tag.putDouble("JailZ", jailZ);
        tag.putFloat("JailYaw", jailYaw);
        tag.putFloat("JailPitch", jailPitch);
        return tag;
    }

    public void setRank(UUID id, PoliceRank rank) { ranks.put(id, rank); setDirty(); }

    public void refreshPoliceTag(ServerPlayer player) {
        Scoreboard scoreboard = player.getServer().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(POLICE_TEAM);
        if (team == null) team = scoreboard.addPlayerTeam(POLICE_TEAM);
        team.setPlayerPrefix(Component.literal("[Politi] ").withStyle(ChatFormatting.BLUE));
        if (ranks.containsKey(player.getUUID())) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        } else if (player.getTeam() == team) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), team);
        }
    }
    public boolean removeRank(UUID id) { boolean changed = ranks.remove(id) != null; if (changed) setDirty(); return changed; }
    public Optional<PoliceRank> getRank(UUID id) { return Optional.ofNullable(ranks.get(id)); }
    public boolean isPolice(net.minecraft.commands.CommandSourceStack source) {
        if (source.hasPermission(4)) return true;
        return source.getEntity() instanceof ServerPlayer player && ranks.containsKey(player.getUUID());
    }
    public List<RankEntry> listRanks() {
        List<RankEntry> result = new ArrayList<>();
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ranks.forEach((id, rank) -> {
            String name = server == null ? id.toString() : server.getProfileCache()
                .get(id).map(profile -> profile.getName()).orElse(id.toString());
            result.add(new RankEntry(name, rank));
        });
        result.sort(Comparator.comparing(RankEntry::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public JailCell getCell(int id) { return cells.computeIfAbsent(id, ignored -> new JailCell()); }
    public boolean isCellConfigured(int id) {
        JailCell c = cells.get(id);
        return c != null && c.spawnConfigured && c.areaConfigured && c.doorConfigured;
    }
    public boolean isCellOccupied(int id) {
        return prisoners.values().stream().anyMatch(p -> p.cellId() == id);
    }
    public int getCellId(UUID player) {
        Prisoner p = prisoners.get(player);
        return p == null ? 0 : p.cellId();
    }
    public void setCellSpawn(int id, ServerPlayer player) {
        JailCell c = getCell(id);
        c.dimension = player.level().dimension().location().toString();
        c.spawnX = player.getX(); c.spawnY = player.getY(); c.spawnZ = player.getZ();
        c.yaw = player.getYRot(); c.pitch = player.getXRot(); c.spawnConfigured = true; setDirty();
    }
    public void setCellDoor(int id, ServerLevel level, net.minecraft.core.BlockPos pos) {
        JailCell c = getCell(id);
        c.doorDimension = level.dimension().location().toString();
        c.doorX = pos.getX(); c.doorY = pos.getY(); c.doorZ = pos.getZ();
        c.doorConfigured = true; setDirty();
    }
    public void setCellCorner(int id, ServerPlayer player, boolean first) {
        JailCell c = getCell(id);
        if (!c.areaConfigured) {
            c.minX = c.maxX = player.blockPosition().getX();
            c.minY = c.maxY = player.blockPosition().getY();
            c.minZ = c.maxZ = player.blockPosition().getZ();
            c.dimension = player.level().dimension().location().toString();
            c.areaConfigured = true;
        }
        int x = player.blockPosition().getX(), y = player.blockPosition().getY(), z = player.blockPosition().getZ();
        if (first) { c.minX=x; c.minY=y; c.minZ=z; }
        else { c.maxX=x; c.maxY=y; c.maxZ=z; }
        c.areaConfigured = true; setDirty();
    }
    public void clearCell(int id) { cells.remove(id); setDirty(); }
    public void setJailAreaCorner(ServerPlayer player, boolean first) {
        int x=player.blockPosition().getX(), y=player.blockPosition().getY(), z=player.blockPosition().getZ();
        if (first || !jailAreaConfigured) {
            jailAreaMinX=jailAreaMaxX=x; jailAreaMinY=jailAreaMaxY=y; jailAreaMinZ=jailAreaMaxZ=z;
            jailAreaDimension=player.level().dimension().location().toString(); jailAreaConfigured=true;
        } else {
            jailAreaMaxX=x; jailAreaMaxY=y; jailAreaMaxZ=z;
        }
        if (jailAreaMinX > jailAreaMaxX) { int t=jailAreaMinX; jailAreaMinX=jailAreaMaxX; jailAreaMaxX=t; }
        if (jailAreaMinY > jailAreaMaxY) { int t=jailAreaMinY; jailAreaMinY=jailAreaMaxY; jailAreaMaxY=t; }
        if (jailAreaMinZ > jailAreaMaxZ) { int t=jailAreaMinZ; jailAreaMinZ=jailAreaMaxZ; jailAreaMaxZ=t; }
        setDirty();
    }
    public boolean hasJailArea() { return jailAreaConfigured; }
    public boolean isInsideJailArea(ServerPlayer player) {
        return jailAreaConfigured && player.level().dimension().location().toString().equals(jailAreaDimension)
            && player.getX() >= jailAreaMinX && player.getX() <= jailAreaMaxX + 1
            && player.getY() >= jailAreaMinY && player.getY() <= jailAreaMaxY + 1
            && player.getZ() >= jailAreaMinZ && player.getZ() <= jailAreaMaxZ + 1;
    }
    public boolean isNightLockdown() { return nightLockdown; }
    public boolean isConfiguredCellDoor(net.minecraft.resources.ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        return cells.values().stream().anyMatch(c -> c.doorConfigured
            && c.doorDimension.equals(dimension.location().toString())
            && c.doorX == pos.getX() && c.doorY == pos.getY() && c.doorZ == pos.getZ());
    }
    public boolean canUseCellDoor(UUID player, net.minecraft.resources.ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        if (nightLockdown) return false;
        int id = getCellId(player);
        JailCell c = cells.get(id);
        return c != null && c.doorConfigured && c.doorDimension.equals(dimension.location().toString())
            && c.doorX == pos.getX() && c.doorY == pos.getY() && c.doorZ == pos.getZ();
    }

    public void setJail(ServerPlayer player) {
        jailDimension = player.level().dimension().location().toString();
        jailX = player.getX(); jailY = player.getY(); jailZ = player.getZ();
        jailYaw = player.getYRot(); jailPitch = player.getXRot();
        jailConfigured = true;
        setDirty();
    }
    public boolean hasJail() { return jailConfigured; }
    public boolean isJailed(UUID id) { return prisoners.containsKey(id); }

    public void tickPrisoner(ServerPlayer player) {
        Prisoner prisoner = prisoners.get(player.getUUID());
        if (prisoner == null) return;
        if (prisoner.remainingMillis() <= 0) {
            releasePlayer(player, prisoner, "§aSoningen er ferdig. Du er automatisk løslatt.");
            return;
        }
        if (player.serverLevel().getGameTime() % 20L == 0L) {
            player.displayClientMessage(countdownComponent(prisoner), true);
        }
        updateDayNightSchedule(player.getServer());
        if (jailAreaConfigured && !isInsideJailArea(player)) {
            teleportToCell(player);
            player.sendSystemMessage(Component.literal("§cDu kan ikke forlate fengselsområdet."));
        }
        if (!jailConfigured && getCellId(player.getUUID()) == 0) return;
        if (!jailConfigured) return;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(jailDimension));
        ServerLevel jailLevel = player.getServer().getLevel(key);
        if (jailLevel == null) return;
        if (player.level() != jailLevel || player.distanceToSqr(jailX, jailY, jailZ) > 2500.0D) {
            enforce(player);
            player.sendSystemMessage(Component.literal("§cDu kan ikke forlate fengselsområdet."));
        }
    }

    public void jail(ServerPlayer player, int minutes, String reason, String officer) {
        long releaseAt = System.currentTimeMillis() + minutes * 60_000L;
        String returnDimension = player.level().dimension().location().toString();
        String prisonerName = player.getGameProfile().getName();
        int cellId = 0;
        for (int id = 1; id <= 6; id++) {
            if (isCellConfigured(id) && !isCellOccupied(id)) { cellId = id; break; }
        }
        prisoners.put(player.getUUID(), new Prisoner(prisonerName, reason, officer, releaseAt,
            returnDimension, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), cellId));
        setDirty();

        Component announcement = Component.empty()
            .append(Component.literal("[HMN Fengsel] ").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
            .append(Component.literal(prisonerName).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" ble satt i fengsel. Kom gjerne på besøk!").withStyle(ChatFormatting.GRAY));
        player.getServer().getPlayerList().broadcastSystemMessage(announcement, false);

        enforce(player);
        if (cellId == 0) player.sendSystemMessage(Component.literal("§eIngen ledig, fullstendig konfigurert celle. Du er satt i midlertidig fengselsplass."));
        else player.sendSystemMessage(Component.literal("§7Du er tildelt celle §e" + cellId + "§7."));

        player.sendSystemMessage(Component.literal("§c§lDU ER FENGSLET"));
        player.sendSystemMessage(Component.literal("§fGrunn: §e" + reason));
        player.sendSystemMessage(Component.literal("§fTid: §e" + minutes + (minutes == 1 ? " minutt" : " minutter")));
        player.sendSystemMessage(Component.literal("§fArrestert av: §e" + officer));
    }

    public boolean release(ServerPlayer player) {
        Prisoner removed = prisoners.get(player.getUUID());
        if (removed == null) return false;
        releasePlayer(player, removed, "§aDu er løslatt fra fengselet.");
        return true;
    }

    private Component countdownComponent(Prisoner prisoner) {
        long totalSeconds = Math.max(1L, (prisoner.remainingMillis() + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        String clock = hours > 0
            ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        return Component.literal("LØSLATES OM  " + clock)
            .withStyle(ChatFormatting.RED);
    }

    public String remainingTimeMessage(UUID id) {
        Prisoner prisoner = prisoners.get(id);
        if (prisoner == null) return "";
        long seconds = Math.max(1L, (prisoner.remainingMillis() + 999L) / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        String time = minutes > 0 ? minutes + " minutter" + (remainingSeconds > 0 ? " og " + remainingSeconds + " sekunder" : "") : remainingSeconds + " sekunder";
        return "Du er i fengsel i " + time + " til. Du kan ikke bruke kommandoer før den tid.";
    }

    public Optional<PrisonerInfo> getPrisoner(UUID id) {
        Prisoner p = prisoners.get(id);
        return p == null ? Optional.empty() : Optional.of(new PrisonerInfo(p.name(), p.reason(), p.remainingMinutes()));
    }
    public List<PrisonerInfo> listPrisoners() {
        return prisoners.values().stream().map(p -> new PrisonerInfo(p.name(), p.reason(), p.remainingMinutes()))
            .sorted(Comparator.comparing(PrisonerInfo::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void enforceOnLogin(ServerPlayer player) {
        Prisoner p = prisoners.get(player.getUUID());
        if (p == null) return;
        if (p.remainingMillis() <= 0) {
            releasePlayer(player, p, "§aSoningen er ferdig. Du er automatisk løslatt.");
            return;
        }
        enforce(player);
    }

    private void releasePlayer(ServerPlayer player, Prisoner prisoner, String message) {
        prisoners.remove(player.getUUID());
        setDirty();

        if (!prisoner.returnDimension().isEmpty()) {
            ResourceKey<Level> returnKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(prisoner.returnDimension()));
            ServerLevel returnLevel = player.getServer().getLevel(returnKey);
            if (returnLevel != null) {
                player.teleportTo(returnLevel, prisoner.returnX(), prisoner.returnY(), prisoner.returnZ(),
                    Set.of(), prisoner.returnYaw(), prisoner.returnPitch());
            }
        }

        player.displayClientMessage(Component.empty(), true);
        player.sendSystemMessage(Component.literal(message));
    }

    private void updateDayNightSchedule(MinecraftServer server) {
        long tick = server.overworld().getGameTime();
        if (tick == lastScheduleTick) return;
        lastScheduleTick = tick;
        long time = Math.floorMod(server.overworld().getDayTime(), 24000L);
        boolean shouldLock = nightLockdown ? !(time >= 23000L || time < 12000L) : time >= 12000L && time < 23000L;
        if (shouldLock == nightLockdown) return;
        nightLockdown = shouldLock;
        setDirty();
        for (ServerPlayer prisoner : server.getPlayerList().getPlayers()) {
            if (!prisoners.containsKey(prisoner.getUUID())) continue;
            if (nightLockdown) {
                teleportToCell(prisoner);
                prisoner.sendSystemMessage(Component.literal("§8§lSOLNEDGANG §7- Du er låst inne for natten."));
            } else {
                prisoner.sendSystemMessage(Component.literal("§e§lSOLOPPGANG §7- Fengselet er åpent. Du kan bevege deg fritt."));
            }
        }
        if (nightLockdown) closeAllCellDoors(server);
    }

    private void closeAllCellDoors(MinecraftServer server) {
        for (JailCell c : cells.values()) {
            if (!c.doorConfigured) continue;
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.doorDimension));
            ServerLevel level = server.getLevel(key);
            if (level == null) continue;
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(c.doorX, c.doorY, c.doorZ);
            var state = level.getBlockState(pos);
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
                level.setBlock(pos, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, false), 3);
            }
        }
    }

    private void teleportToCell(ServerPlayer player) {
        int id = getCellId(player.getUUID());
        JailCell c = cells.get(id);
        if (c != null && c.spawnConfigured) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.dimension));
            ServerLevel level = player.getServer().getLevel(key);
            if (level != null) player.teleportTo(level, c.spawnX, c.spawnY, c.spawnZ, Set.of(), c.yaw, c.pitch);
            return;
        }
        enforce(player);
    }

    private void enforce(ServerPlayer player) {
        int id = getCellId(player.getUUID());
        if (nightLockdown && id > 0) {
            JailCell c = cells.get(id);
            if (c != null && c.areaConfigured && player.level().dimension().location().toString().equals(c.dimension)) {
                if (player.getX() < c.minX || player.getX() > c.maxX + 1 || player.getY() < c.minY || player.getY() > c.maxY + 1 || player.getZ() < c.minZ || player.getZ() > c.maxZ + 1) {
                    teleportToCell(player);
                }
            }
        }
        if (!jailConfigured) return;
        MinecraftServer server = player.getServer();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(jailDimension));
        ServerLevel jailLevel = server.getLevel(key);
        if (jailLevel == null) return;
        if (player.level() != jailLevel) {
            player.teleportTo(jailLevel, jailX, jailY, jailZ, Set.of(), jailYaw, jailPitch);
        } else {
            player.teleportTo(jailX, jailY, jailZ);
            player.setYRot(jailYaw);
            player.setXRot(jailPitch);
        }
    }
}
