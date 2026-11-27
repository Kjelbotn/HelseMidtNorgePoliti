package no.bangerfabrikken.helsemnpoliti;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.item.ItemStack;
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
    private final Set<UUID> onDuty = new HashSet<>();
    private final Map<UUID, Prisoner> prisoners = new HashMap<>();
    private final Map<UUID, ListTag> savedInventories = new HashMap<>();
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private String jailDimension = "";
    private double jailX, jailY, jailZ;
    private float jailYaw, jailPitch;
    private boolean jailConfigured;
    private final Map<Integer, JailCell> cells = new HashMap<>();
    private String jailAreaDimension = "";
    private int jailAreaMinX, jailAreaMinY, jailAreaMinZ, jailAreaMaxX, jailAreaMaxY, jailAreaMaxZ;
    private boolean jailAreaConfigured;
    private boolean nightLockdown;
    private long lastScheduleTick = Long.MIN_VALUE;
    private final Set<String> activeAlarms = new HashSet<>();
    private final List<String> alarmHistory = new ArrayList<>();
    private final List<PoliceLogEntry> policeLog = new ArrayList<>();
    public record PoliceLogEntry(long timestamp, String action, UUID targetId, String targetName, String actor, String details) {}
    public static final class JailCell {
        public String dimension = "";
        public double spawnX, spawnY, spawnZ;
        public float yaw, pitch;
        public boolean spawnConfigured;
        public String doorDimension = "";
        public int doorX, doorY, doorZ, doorX2, doorZ2;
        public boolean doorConfigured;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public boolean areaConfigured;
    }

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
        ListTag inventoryList = tag.getList("SavedInventories", 10);
        for (int i = 0; i < inventoryList.size(); i++) {
            CompoundTag entry = inventoryList.getCompound(i);
            try {
                UUID id = UUID.fromString(entry.getString("UUID"));
                data.savedInventories.put(id, entry.getList("Items", 10).copy());
            } catch (RuntimeException ignored) {}
        }
        ListTag activityList = tag.getList("LastActive", 10);
        for (int i = 0; i < activityList.size(); i++) {
            CompoundTag entry = activityList.getCompound(i);
            try {
                data.lastActive.put(UUID.fromString(entry.getString("UUID")), entry.getLong("Timestamp"));
            } catch (RuntimeException ignored) {}
        }
        data.jailConfigured = tag.getBoolean("JailConfigured");
        data.jailDimension = tag.getString("JailDimension");
        data.jailX = tag.getDouble("JailX");
        data.jailY = tag.getDouble("JailY");
        data.jailZ = tag.getDouble("JailZ");
        data.jailYaw = tag.getFloat("JailYaw");
        data.jailPitch = tag.getFloat("JailPitch");
        data.jailAreaConfigured = tag.getBoolean("JailAreaConfigured");
        data.jailAreaDimension = tag.getString("JailAreaDimension");
        data.jailAreaMinX=tag.getInt("JailAreaMinX"); data.jailAreaMinY=tag.getInt("JailAreaMinY"); data.jailAreaMinZ=tag.getInt("JailAreaMinZ");
        data.jailAreaMaxX=tag.getInt("JailAreaMaxX"); data.jailAreaMaxY=tag.getInt("JailAreaMaxY"); data.jailAreaMaxZ=tag.getInt("JailAreaMaxZ");
        data.nightLockdown = tag.getBoolean("NightLockdown");
        ListTag alarms = tag.getList("AlarmHistory", 8);
        for (int i = 0; i < alarms.size(); i++) data.alarmHistory.add(alarms.getString(i));
        ListTag logList = tag.getList("PoliceLog", 10);
        for (int i = 0; i < logList.size(); i++) {
            CompoundTag entry = logList.getCompound(i);
            try {
                String rawId = entry.getString("TargetUUID");
                UUID targetId = rawId.isEmpty() ? null : UUID.fromString(rawId);
                data.policeLog.add(new PoliceLogEntry(entry.getLong("Timestamp"), entry.getString("Action"), targetId,
                    entry.getString("TargetName"), entry.getString("Actor"), entry.getString("Details")));
            } catch (RuntimeException ignored) {}
        }
        ListTag cellList = tag.getList("Cells", 10);
        for (int i=0; i<cellList.size(); i++) {
            CompoundTag c=cellList.getCompound(i); int id=c.getInt("Id");
            if (id<1 || id>6) continue;
            JailCell cell=new JailCell();
            cell.dimension=c.getString("Dimension");
            cell.spawnX=c.getDouble("SpawnX"); cell.spawnY=c.getDouble("SpawnY"); cell.spawnZ=c.getDouble("SpawnZ");
            cell.yaw=c.getFloat("Yaw"); cell.pitch=c.getFloat("Pitch"); cell.spawnConfigured=c.getBoolean("SpawnConfigured");
            cell.doorDimension=c.getString("DoorDimension"); cell.doorX=c.getInt("DoorX"); cell.doorY=c.getInt("DoorY"); cell.doorZ=c.getInt("DoorZ"); cell.doorX2=c.getInt("DoorX2"); cell.doorZ2=c.getInt("DoorZ2"); cell.doorConfigured=c.getBoolean("DoorConfigured");
            cell.minX=c.getInt("MinX"); cell.minY=c.getInt("MinY"); cell.minZ=c.getInt("MinZ"); cell.maxX=c.getInt("MaxX"); cell.maxY=c.getInt("MaxY"); cell.maxZ=c.getInt("MaxZ"); cell.areaConfigured=c.getBoolean("AreaConfigured");
            data.cells.put(id,cell);
        }
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
        ListTag inventoryList = new ListTag();
        savedInventories.forEach((uuid, items) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("UUID", uuid.toString());
            entry.put("Items", items.copy());
            inventoryList.add(entry);
        });
        tag.put("SavedInventories", inventoryList);
        ListTag activityList = new ListTag();
        lastActive.forEach((uuid, timestamp) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("UUID", uuid.toString());
            entry.putLong("Timestamp", timestamp);
            activityList.add(entry);
        });
        tag.put("LastActive", activityList);

        tag.putBoolean("JailConfigured", jailConfigured);
        tag.putString("JailDimension", jailDimension);
        tag.putDouble("JailX", jailX);
        tag.putDouble("JailY", jailY);
        tag.putDouble("JailZ", jailZ);
        tag.putFloat("JailYaw", jailYaw);
        tag.putFloat("JailPitch", jailPitch);
        tag.putBoolean("JailAreaConfigured",jailAreaConfigured); tag.putString("JailAreaDimension",jailAreaDimension);
        tag.putInt("JailAreaMinX",jailAreaMinX); tag.putInt("JailAreaMinY",jailAreaMinY); tag.putInt("JailAreaMinZ",jailAreaMinZ);
        tag.putInt("JailAreaMaxX",jailAreaMaxX); tag.putInt("JailAreaMaxY",jailAreaMaxY); tag.putInt("JailAreaMaxZ",jailAreaMaxZ);
        tag.putBoolean("NightLockdown",nightLockdown);
        ListTag alarms = new ListTag();
        for (String alarm : alarmHistory) alarms.add(net.minecraft.nbt.StringTag.valueOf(alarm));
        tag.put("AlarmHistory", alarms);
        ListTag logList = new ListTag();
        for (PoliceLogEntry log : policeLog) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Timestamp", log.timestamp());
            entry.putString("Action", log.action());
            entry.putString("TargetUUID", log.targetId() == null ? "" : log.targetId().toString());
            entry.putString("TargetName", log.targetName());
            entry.putString("Actor", log.actor());
            entry.putString("Details", log.details());
            logList.add(entry);
        }
        tag.put("PoliceLog", logList);
        ListTag cellList=new ListTag();
        cells.forEach((id,c)->{
            CompoundTag entry=new CompoundTag(); entry.putInt("Id",id); entry.putString("Dimension",c.dimension);
            entry.putDouble("SpawnX",c.spawnX); entry.putDouble("SpawnY",c.spawnY); entry.putDouble("SpawnZ",c.spawnZ);
            entry.putFloat("Yaw",c.yaw); entry.putFloat("Pitch",c.pitch); entry.putBoolean("SpawnConfigured",c.spawnConfigured);
            entry.putString("DoorDimension",c.doorDimension); entry.putInt("DoorX",c.doorX); entry.putInt("DoorY",c.doorY); entry.putInt("DoorZ",c.doorZ); entry.putInt("DoorX2",c.doorX2); entry.putInt("DoorZ2",c.doorZ2); entry.putBoolean("DoorConfigured",c.doorConfigured);
            entry.putInt("MinX",c.minX); entry.putInt("MinY",c.minY); entry.putInt("MinZ",c.minZ); entry.putInt("MaxX",c.maxX); entry.putInt("MaxY",c.maxY); entry.putInt("MaxZ",c.maxZ); entry.putBoolean("AreaConfigured",c.areaConfigured);
            cellList.add(entry);
        });
        tag.put("Cells",cellList);
        return tag;
    }

    public void recordLastActive(UUID id) {
        lastActive.put(id, System.currentTimeMillis());
        setDirty();
    }

    public OptionalLong getLastActive(UUID id) {
        Long timestamp = lastActive.get(id);
        return timestamp == null ? OptionalLong.empty() : OptionalLong.of(timestamp);
    }

    public void setRank(UUID id, PoliceRank rank) { ranks.put(id, rank); setDirty(); }

    public void refreshPoliceTag(ServerPlayer player) {
        Scoreboard scoreboard = player.getServer().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(POLICE_TEAM);
        if (team == null) team = scoreboard.addPlayerTeam(POLICE_TEAM);
        team.setPlayerPrefix(Component.literal("[Politi] ").withStyle(ChatFormatting.BLUE));
        if (ranks.containsKey(player.getUUID()) && onDuty.contains(player.getUUID())) {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        } else if (player.getTeam() == team) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), team);
        }
    }
    public boolean isOnDuty(UUID id) { return onDuty.contains(id); }

    public boolean toggleDuty(ServerPlayer player) {
        UUID id = player.getUUID();
        if (ranks.get(id) == null) return false;
        if (onDuty.remove(id)) {
            refreshPoliceTag(player);
            logAction("Gikk av vakt", id, player.getGameProfile().getName(), player.getGameProfile().getName(), "");
            return false;
        }
        onDuty.add(id);
        refreshPoliceTag(player);
        logAction("Gikk på vakt", id, player.getGameProfile().getName(), player.getGameProfile().getName(), "");
        return true;
    }

    public void endDuty(ServerPlayer player) {
        UUID id = player.getUUID();
        if (onDuty.remove(id)) {
            refreshPoliceTag(player);
            logAction("Vakt avsluttet ved frakobling", id, player.getGameProfile().getName(),
                player.getGameProfile().getName(), "Automatisk avlogging fra vakt");
        }
    }

    public List<String> getOnDutyNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (UUID id : onDuty) {
            String name = server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean removeRank(UUID id) { boolean changed = ranks.remove(id) != null; onDuty.remove(id); if (changed) setDirty(); return changed; }
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
    public boolean assignCell(ServerPlayer player, int id) {
        Prisoner p = prisoners.get(player.getUUID());
        if (p == null || !isCellConfigured(id) || (isCellOccupied(id) && p.cellId() != id)) return false;
        prisoners.put(player.getUUID(), new Prisoner(p.name(), p.reason(), p.officer(), p.releaseAt(),
            p.returnDimension(), p.returnX(), p.returnY(), p.returnZ(), p.returnYaw(), p.returnPitch(), id));
        setDirty();
        teleportToCell(player);
        return true;
    }
    public void setCellSpawn(int id, ServerPlayer player) {
        JailCell c = getCell(id);
        c.dimension = player.level().dimension().location().toString();
        c.spawnX = player.getX(); c.spawnY = player.getY(); c.spawnZ = player.getZ();
        c.yaw = player.getYRot(); c.pitch = player.getXRot(); c.spawnConfigured = true; setDirty();
    }
    public boolean setCellDoor(int id, ServerLevel level, net.minecraft.core.BlockPos first, net.minecraft.core.BlockPos second) {
        if (first.getY() != second.getY() || Math.abs(first.getX()-second.getX()) + Math.abs(first.getZ()-second.getZ()) != 1) return false;
        if (!(level.getBlockState(first).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
            || !(level.getBlockState(second).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
            || !level.getBlockState(first.above()).is(level.getBlockState(first).getBlock())
            || !level.getBlockState(second.above()).is(level.getBlockState(second).getBlock())) return false;
        JailCell c=getCell(id); c.doorDimension=level.dimension().location().toString();
        c.doorX=first.getX(); c.doorY=first.getY(); c.doorZ=first.getZ();
        c.doorX2=second.getX(); c.doorZ2=second.getZ(); c.doorConfigured=true; setDirty(); return true;
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
        int ax=c.minX, bx=c.maxX, ay=c.minY, by=c.maxY, az=c.minZ, bz=c.maxZ;
        c.minX=Math.min(ax,bx); c.maxX=Math.max(ax,bx);
        c.minY=Math.min(ay,by); c.maxY=Math.max(ay,by);
        c.minZ=Math.min(az,bz); c.maxZ=Math.max(az,bz);
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

    public void logAction(String action, UUID targetId, String targetName, String actor, String details) {
        policeLog.add(0, new PoliceLogEntry(System.currentTimeMillis(), action,
            targetId, targetName == null ? "" : targetName, actor == null ? "System" : actor,
            details == null ? "" : details));
        while (policeLog.size() > 200) policeLog.remove(policeLog.size() - 1);
        setDirty();
    }

    public List<PoliceLogEntry> getPoliceLog(UUID targetId) {
        return policeLog.stream().filter(entry -> targetId == null || targetId.equals(entry.targetId())).limit(20).toList();
    }

    public List<PoliceLogEntry> getArrestHistory(UUID targetId) {
        return policeLog.stream().filter(entry -> targetId != null && targetId.equals(entry.targetId())
            && (entry.action().equals("Arrestasjon") || entry.action().equals("Løslatelse"))).limit(20).toList();
    }

    public static String formatLogEntry(PoliceLogEntry entry) {
        String time = java.time.Instant.ofEpochMilli(entry.timestamp())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        return time + " | " + entry.action() + " | " +
            (entry.targetName().isBlank() ? "—" : entry.targetName()) + " | " +
            entry.actor() + (entry.details().isBlank() ? "" : " | " + entry.details());
    }

    public void triggerAlarm(ServerPlayer prisoner, String type) {
        String key = prisoner.getUUID() + ":" + type;
        if (!activeAlarms.add(key)) return;
        String message = "[HMN ALARM] " + prisoner.getGameProfile().getName() + " | "
            + type + " | Celle " + Math.max(0, getCellId(prisoner.getUUID()))
            + " | " + prisoner.blockPosition().toShortString();
        alarmHistory.add(0, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")) + " - " + message);
        while (alarmHistory.size() > 50) alarmHistory.remove(alarmHistory.size() - 1);
        setDirty();
        Component alert = Component.literal("§c§l" + message);
        for (ServerPlayer online : prisoner.getServer().getPlayerList().getPlayers()) {
            if (online.hasPermissions(2) || ranks.containsKey(online.getUUID())) online.sendSystemMessage(alert);
        }
    }

    public void clearAlarm(ServerPlayer prisoner, String type) {
        activeAlarms.remove(prisoner.getUUID() + ":" + type);
    }

    public List<String> getAlarmHistory() { return List.copyOf(alarmHistory); }

    public void testAlarm(ServerPlayer sender) {
        Component alert = Component.literal("§c§l[HMN ALARM] Alarmtest utført av " + sender.getGameProfile().getName());
        for (ServerPlayer online : sender.getServer().getPlayerList().getPlayers()) {
            if (online.hasPermissions(2) || ranks.containsKey(online.getUUID())) online.sendSystemMessage(alert);
        }
    }
    private boolean matchesDoor(JailCell c, net.minecraft.resources.ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        if (!c.doorConfigured || !c.doorDimension.equals(dimension.location().toString())) return false;
        boolean first = c.doorX == pos.getX() && c.doorZ == pos.getZ()
            && pos.getY() >= c.doorY && pos.getY() <= c.doorY + 1;
        boolean second = c.doorX2 == pos.getX() && c.doorZ2 == pos.getZ()
            && pos.getY() >= c.doorY && pos.getY() <= c.doorY + 1;
        return first || second;
    }
    public boolean isConfiguredCellDoor(net.minecraft.resources.ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        return cells.values().stream().anyMatch(c -> matchesDoor(c, dimension, pos));
    }
    public boolean canUseCellDoor(UUID player, net.minecraft.resources.ResourceKey<Level> dimension, net.minecraft.core.BlockPos pos) {
        if (nightLockdown) return false;
        JailCell c = cells.get(getCellId(player));
        return c != null && matchesDoor(c, dimension, pos);
    }

    public void setJail(ServerPlayer player) {
        jailDimension = player.level().dimension().location().toString();
        jailX = player.getX(); jailY = player.getY(); jailZ = player.getZ();
        jailYaw = player.getYRot(); jailPitch = player.getXRot();
        jailConfigured = true;
        setDirty();
    }
    public boolean hasJail() { return jailConfigured || (jailAreaConfigured && java.util.stream.IntStream.rangeClosed(1, 6).anyMatch(this::isCellConfigured)); }
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
        int cellId = getCellId(player.getUUID());
        JailCell cell = cells.get(cellId);
        boolean outsideCellAtNight = nightLockdown && cell != null && cell.areaConfigured
            && (!player.level().dimension().location().toString().equals(cell.dimension)
                || player.getX() < cell.minX || player.getX() > cell.maxX + 1
                || player.getY() < cell.minY || player.getY() > cell.maxY + 1
                || player.getZ() < cell.minZ || player.getZ() > cell.maxZ + 1);
        if (outsideCellAtNight) {
            triggerAlarm(player, "Rømningsforsøk fra celle under nattinnlåsing");
            teleportToCell(player);
        } else clearAlarm(player, "Rømningsforsøk fra celle under nattinnlåsing");

        boolean outsideJail = jailAreaConfigured && !isInsideJailArea(player);
        if (outsideJail) {
            triggerAlarm(player, "Rømningsforsøk utenfor fengselsområdet");
            teleportToCell(player);
            player.sendSystemMessage(Component.literal("§cDu kan ikke forlate fengselsområdet."));
        } else clearAlarm(player, "Rømningsforsøk utenfor fengselsområdet");
        if (jailAreaConfigured) return;
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
        if (prisoners.containsKey(player.getUUID())) return;

        // Save every inventory slot, including armor and offhand, before clearing it.
        ListTag inventorySnapshot = new ListTag();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            CompoundTag itemEntry = new CompoundTag();
            itemEntry.putInt("Slot", slot);
            itemEntry.put("Stack", stack.save(player.registryAccess()));
            inventorySnapshot.add(itemEntry);
        }
        savedInventories.put(player.getUUID(), inventorySnapshot);
        inventory.clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);

        prisoners.put(player.getUUID(), new Prisoner(prisonerName, reason, officer, releaseAt,
            returnDimension, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), cellId));
        setDirty();
        logAction("Arrestasjon", player.getUUID(), prisonerName, officer, minutes + " min | " + reason + " | celle " + cellId);

        Component announcement = Component.empty()
            .append(Component.literal("[HMN Fengsel] ").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
            .append(Component.literal(prisonerName).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" ble satt i fengsel. Kom gjerne på besøk!").withStyle(ChatFormatting.GRAY));
        player.getServer().getPlayerList().broadcastSystemMessage(announcement, false);

        if (cellId > 0) { teleportToCell(player); if (nightLockdown) closeAllCellDoors(player.getServer()); } else enforce(player);
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
        updateDayNightSchedule(player.getServer());
        if (p.remainingMillis() <= 0) {
            releasePlayer(player, p, "§aSoningen er ferdig. Du er automatisk løslatt.");
            return;
        }
        enforce(player);
    }

    private void releasePlayer(ServerPlayer player, Prisoner prisoner, String message) {
        prisoners.remove(player.getUUID());
        logAction("Løslatelse", player.getUUID(), prisoner.name(), "System/Politi", prisoner.reason());
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

        // Restore the exact inventory snapshot once the player is released.
        ListTag inventorySnapshot = savedInventories.remove(player.getUUID());
        if (inventorySnapshot != null) {
            var inventory = player.getInventory();
            inventory.clearContent();
            for (int i = 0; i < inventorySnapshot.size(); i++) {
                CompoundTag itemEntry = inventorySnapshot.getCompound(i);
                int slot = itemEntry.getInt("Slot");
                if (slot < 0 || slot >= inventory.getContainerSize()) continue;
                ItemStack.parse(player.registryAccess(), itemEntry.getCompound("Stack"))
                    .ifPresent(stack -> inventory.setItem(slot, stack));
            }
            inventory.setChanged();
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }
        setDirty();

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
            for (int n=0;n<2;n++) {
                int x=n==0?c.doorX:c.doorX2, z=n==0?c.doorZ:c.doorZ2;
                for (int y=c.doorY;y<=c.doorY+1;y++) {
                    net.minecraft.core.BlockPos pos=new net.minecraft.core.BlockPos(x,y,z);
                    var state=level.getBlockState(pos);
                    if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                        && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN))
                        level.setBlock(pos,state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN,false),3);
                }
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
        if (id > 0) {
            JailCell cell = cells.get(id);
            if (cell != null && cell.spawnConfigured) {
                ResourceKey<Level> cellKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(cell.dimension));
                ServerLevel cellLevel = player.getServer().getLevel(cellKey);
                if (cellLevel != null && (player.level() != cellLevel || nightLockdown || (jailAreaConfigured && !isInsideJailArea(player)))) {
                    player.teleportTo(cellLevel, cell.spawnX, cell.spawnY, cell.spawnZ, Set.of(), cell.yaw, cell.pitch);
                }
                return;
            }
        }
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
