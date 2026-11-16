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
                            float returnYaw, float returnPitch) {
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
                    entry.getDouble("ReturnZ"), entry.getFloat("ReturnYaw"), entry.getFloat("ReturnPitch")));
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
        prisoners.put(player.getUUID(), new Prisoner(player.getGameProfile().getName(), reason, officer, releaseAt,
            returnDimension, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        setDirty();
        enforce(player);

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

        player.sendSystemMessage(Component.literal(message));
    }

    private void enforce(ServerPlayer player) {
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
