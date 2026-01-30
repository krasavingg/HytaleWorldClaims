package com.buuz135.simpleclaims.claim;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.party.PartyInvite;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.buuz135.simpleclaims.constants.ClaimOwnerType;
import com.buuz135.simpleclaims.files.*;
import com.buuz135.simpleclaims.guild.GuildClaimBridge;
import com.buuz135.simpleclaims.util.FileUtils;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.claim.player_name.PlayerNameTracker;
import com.buuz135.simpleclaims.claim.tracking.ModifiedTracking;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import ru.hytaleworld.guild.util.Guild;

import javax.annotation.Nullable;
import java.awt.*;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

import static com.buuz135.simpleclaims.constants.ClaimOwnerType.GUILD;
import static com.buuz135.simpleclaims.constants.ClaimOwnerType.PERSONAL;

public class ClaimManager {

    private static final ClaimManager INSTANCE = new ClaimManager();

    private final Map<UUID, UUID> adminUsageParty;
    private final Map<UUID, PartyInvite> partyInvites;
    private final Map<UUID, UUID> playerToParty;
    private final Map<UUID, Integer> partyClaimCounts;
    private Set<String> worldsNeedingUpdates;
    private HytaleLogger logger = HytaleLogger.getLogger().getSubLogger("SimpleClaims");
    private PlayerNameTracker playerNameTracker;
    private HashMap<String, PartyInfo> parties;
    private HashMap<String, HashMap<String, ChunkInfo>> chunks;
    private Set<UUID> adminOverrides;
    private DatabaseManager databaseManager;
    private HashMap<String, LongSet> mapUpdateQueue;

    private final Map<UUID, Integer> guildClaimCounts = new ConcurrentHashMap<>();

    public static ClaimManager getInstance() {
        return INSTANCE;
    }

    private ClaimManager() {
        this.adminUsageParty = new ConcurrentHashMap<>();
        this.worldsNeedingUpdates = new HashSet<>();
        this.partyInvites = new ConcurrentHashMap<>();
        this.playerToParty = new ConcurrentHashMap<>();
        this.partyClaimCounts = new ConcurrentHashMap<>();
        this.parties = new HashMap<>();
        this.chunks = new HashMap<>();
        this.playerNameTracker = new PlayerNameTracker();
        this.adminOverrides = new HashSet<>();
        this.databaseManager = new DatabaseManager(logger);
        this.mapUpdateQueue = new HashMap<>();

        FileUtils.ensureMainDirectory();

        logger.at(Level.INFO).log("Loading simple claims data...");

        if (this.databaseManager.isMigrationNecessary()) {
            logger.at(Level.INFO).log("Migration needed, loading JSON files...");
            PartyBlockingFile partyBlockingFile = new PartyBlockingFile();
            ClaimedChunkBlockingFile claimedChunkBlockingFile = new ClaimedChunkBlockingFile();
            PlayerNameTrackerBlockingFile playerNameTrackerBlockingFile = new PlayerNameTrackerBlockingFile();
            AdminOverridesBlockingFile adminOverridesBlockingFile = new AdminOverridesBlockingFile();

            if (new File(FileUtils.PARTY_PATH).exists()) {
                FileUtils.loadWithBackup(partyBlockingFile::syncLoad, FileUtils.PARTY_PATH, logger);
            }
            if (new File(FileUtils.CLAIM_PATH).exists()) {
                FileUtils.loadWithBackup(claimedChunkBlockingFile::syncLoad, FileUtils.CLAIM_PATH, logger);
            }
            if (new File(FileUtils.NAMES_CACHE_PATH).exists()) {
                FileUtils.loadWithBackup(playerNameTrackerBlockingFile::syncLoad, FileUtils.NAMES_CACHE_PATH, logger);
            }
            if (new File(FileUtils.ADMIN_OVERRIDES_PATH).exists()) {
                FileUtils.loadWithBackup(adminOverridesBlockingFile::syncLoad, FileUtils.ADMIN_OVERRIDES_PATH, logger);
            }

            this.databaseManager.migrate(partyBlockingFile, claimedChunkBlockingFile, playerNameTrackerBlockingFile, adminOverridesBlockingFile);
        }

        logger.at(Level.INFO).log("Loading party data from DB...");
        this.parties.putAll(this.databaseManager.loadParties());
        for (PartyInfo party : this.parties.values()) {
            for (UUID member : party.getMembers()) {
                playerToParty.put(member, party.getId());
            }
        }

        logger.at(Level.INFO).log("Loading chunk data from DB...");
        this.chunks.putAll(this.databaseManager.loadClaims());
        for (HashMap<String, ChunkInfo> dimensionChunks : this.chunks.values()) {
            for (ChunkInfo chunk : dimensionChunks.values()) {
                // Обновляем счетчик в зависимости от типа владельца
                if (chunk.getOwnerType() == ClaimOwnerType.GUILD) {
                    guildClaimCounts.merge(chunk.getOwnerId(), 1, Integer::sum);
                } else {
                    // Для PARTY и PERSONAL
                    partyClaimCounts.merge(chunk.getOwnerId(), 1, Integer::sum);
                }
            }
        }

        logger.at(Level.INFO).log("Loading name cache data from DB...");
        PlayerNameTracker tracker = this.databaseManager.loadNameCache();
        for (PlayerNameTracker.PlayerName name : tracker.getNames()) {
            this.playerNameTracker.setPlayerName(name.getUuid(), name.getName(), name.getLastSeen());
        }

        logger.at(Level.INFO).log("Loading admin overrides data from DB...");
        this.adminOverrides.addAll(this.databaseManager.loadAdminOverrides());

    }

    public void saveParty(PartyInfo partyInfo) {
        this.databaseManager.saveParty(partyInfo);
    }

    private void saveClaim(String dimension, ChunkInfo chunkInfo) {
        this.databaseManager.saveClaim(dimension, chunkInfo);
    }

    private void saveNameCache(UUID uuid, String name, long lastSeen) {
        this.databaseManager.saveNameCache(uuid, name, lastSeen);
    }

    private void saveAdminOverride(UUID uuid) {
        this.databaseManager.saveAdminOverride(uuid);
    }

    public void addParty(PartyInfo partyInfo){
        this.parties.put(partyInfo.getId().toString(), partyInfo);
        this.saveParty(partyInfo);
    }

    public boolean isAllowedToInteract(UUID playerUUID, String dimension, int chunkX, int chunkZ, Predicate<PartyInfo> interactMethod) {
        if (playerUUID != null && adminOverrides.contains(playerUUID)) return true;

        var chunkInfo = getChunkRawCoords(dimension, chunkX, chunkZ);
        if (chunkInfo == null) return !Arrays.asList(Main.CONFIG.get().getFullWorldProtection()).contains(dimension);

        var chunkParty = getPartyById(chunkInfo.getPartyOwner());
        if (chunkParty == null || interactMethod.test(chunkParty)) return true;

        // If playerUUID is null (e.g., explosion with unknown source), deny in claimed chunks
        if (playerUUID == null) return false;

        if (chunkParty.getPlayerAllies().contains(playerUUID)) return true;

        var partyId = playerToParty.get(playerUUID);
        if (partyId == null) return false;

        return chunkInfo.getPartyOwner().equals(partyId) || chunkParty.getPartyAllies().contains(partyId);
    }

    @Nullable
    public PartyInfo getPartyFromPlayer(UUID player) {
        UUID partyId = playerToParty.get(player);
        if (partyId == null) return null;
        return getPartyById(partyId);
    }

    @Nullable
    public PartyInfo getPartyById(UUID partyId){
        return this.parties.get(partyId.toString());
    }

    public PartyInfo createParty(Player owner, PlayerRef playerRef, boolean isAdmin) {
        var party = new PartyInfo(UUID.randomUUID(), playerRef.getUuid(), owner.getDisplayName() + "'s Party", owner.getDisplayName() + "'s Party Description", new UUID[0], Color.getHSBColor(new Random().nextFloat(), 1, 1).getRGB());
        party.addMember(playerRef.getUuid());
        party.setCreatedTracked(new ModifiedTracking(playerRef.getUuid(), owner.getDisplayName(), LocalDateTime.now().toString()));
        party.setModifiedTracked(new ModifiedTracking(playerRef.getUuid(), owner.getDisplayName(), LocalDateTime.now().toString()));
        this.parties.put(party.getId().toString(), party);
        if (!isAdmin) this.playerToParty.put(playerRef.getUuid(), party.getId());
        this.saveParty(party);
        return party;
    }

    public boolean canClaimInDimension(World world){
        if (world.getWorldConfig().isDeleteOnRemove()) return false;
        if (world.getName().contains("Gaia_Temple")) return false;
        if (Arrays.asList(Main.CONFIG.get().getWorldNameBlacklistForClaiming()).contains(world.getName())) return false;
        return true;
    }

    @Nullable
    public ChunkInfo getChunk(String dimension, int chunkX, int chunkZ){
        var chunkInfo = this.chunks.computeIfAbsent(dimension, k -> new HashMap<>());
        var formattedChunk = ChunkInfo.formatCoordinates(chunkX, chunkZ);
        return chunkInfo.getOrDefault(formattedChunk, null);
    }

    @Nullable
    public ChunkInfo getChunkRawCoords(String dimension, int blockX, int blockZ){
        return this.getChunk(dimension, ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ));
    }

    public ChunkInfo claimChunkBy(
            String dimension,
            int chunkX,
            int chunkZ,
            UUID ownerId,           // ИЗМЕНЕНО: передаём UUID владельца напрямую
            ClaimOwnerType ownerType,
            UUID claimerUUID,       // ДОБАВЛЕНО: кто создал клайм
            String claimerName      // ДОБАВЛЕНО: имя создателя
    ) {
        var chunkInfo = new ChunkInfo(ownerId, ownerType, chunkX, chunkZ);
        var chunkDimension = this.chunks.computeIfAbsent(dimension, k -> new HashMap<>());
        chunkDimension.put(ChunkInfo.formatCoordinates(chunkX, chunkZ), chunkInfo);

        // Трекинг создания
        chunkInfo.setCreatedTracked(new ModifiedTracking(
                claimerUUID,
                claimerName,
                LocalDateTime.now().toString()
        ));

        // Обновляем правильный счетчик
        if (ownerType == ClaimOwnerType.GUILD) {
            guildClaimCounts.merge(ownerId, 1, Integer::sum);
        } else {
            partyClaimCounts.merge(ownerId, 1, Integer::sum);
        }

        this.databaseManager.saveClaim(dimension, chunkInfo);
        return chunkInfo;
    }

    public ChunkInfo claimChunkByParty(
            String dimension,
            int chunkX,
            int chunkZ,
            PartyInfo partyInfo,
            Player claimer,
            PlayerRef claimerRef
    ) {
        return claimChunkBy(
                dimension,
                chunkX,
                chunkZ,
                partyInfo.getId(),          // Owner = Party ID
                ClaimOwnerType.PARTY,
                claimerRef.getUuid(),
                claimer.getDisplayName()
        );
    }

    /**
     * Заклаймить чанк как Personal (на имя игрока)
     */
    public ChunkInfo claimChunkByPersonal(
            String dimension,
            int chunkX,
            int chunkZ,
            Player owner,
            PlayerRef ownerRef
    ) {
        return claimChunkBy(
                dimension,
                chunkX,
                chunkZ,
                ownerRef.getUuid(),         // Owner = Player UUID
                ClaimOwnerType.PERSONAL,
                ownerRef.getUuid(),
                owner.getDisplayName()
        );
    }

    /**
     * Заклаймить чанк для Guild
     */
    public ChunkInfo claimChunkByGuild(
            String dimension,
            int chunkX,
            int chunkZ,
            Guild guild,
            UUID claimerPlayerId,
            String claimerPlayerName
    ) {
        if (guild == null) {
            return null;
        }

        return claimChunkBy(
                dimension,
                chunkX,
                chunkZ,
                guild.getData().getId(),    // Owner = Guild ID
                ClaimOwnerType.GUILD,
                claimerPlayerId,
                claimerPlayerName
        );
    }

    @Deprecated
    public ChunkInfo claimChunkByRawCoords(String dimension, int blockX, int blockZ, PartyInfo partyInfo, Player owner, PlayerRef playerRef) {
        return claimChunkByParty(
                dimension,
                ChunkUtil.chunkCoordinate(blockX),
                ChunkUtil.chunkCoordinate(blockZ),
                partyInfo,
                owner,
                playerRef
        );
    }

    public boolean hasEnoughClaimsLeft(PartyInfo partyInfo) {
        int maxAmount = partyInfo.getMaxClaimAmount();
        int currentAmount = partyClaimCounts.getOrDefault(partyInfo.getId(), 0);
        return currentAmount < maxAmount;
    }

    public int getAmountOfClaims(PartyInfo partyInfo) {
        return partyClaimCounts.getOrDefault(partyInfo.getId(), 0);
    }

    /**
     * Проверка: может ли игрок снять этот клайм?
     */
    public boolean canPlayerUnclaimChunk(UUID playerUUID, ChunkInfo chunkInfo) {
        if (chunkInfo == null) {
            return false;
        }

        // Админ-овверрайд
        if (adminOverrides.contains(playerUUID)) {
            return true;
        }

        switch (chunkInfo.getOwnerType()) {
            case PERSONAL -> {
                // Только владелец может снять личный клайм
                return chunkInfo.getOwnerId().equals(playerUUID);
            }
            case PARTY -> {
                // Владелец party или член party
                PartyInfo party = getPartyById(chunkInfo.getOwnerId());
                if (party == null) {
                    return false;
                }
                return party.isOwner(playerUUID) || party.isMember(playerUUID);
            }
            case GUILD -> {
                // Проверка прав в гильдии
                Guild guild = getGuildById(chunkInfo.getOwnerId());
                if (guild == null) {
                    return false;
                }
                return canGuildClaim(guild, playerUUID);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Снять клайм с чанка
     * @param dimension измерение
     * @param chunkX координата X чанка
     * @param chunkZ координата Z чанка
     * @return true если клайм был снят, false если клайма не было
     */
    public boolean unclaim(String dimension, int chunkX, int chunkZ) {
        var chunkMap = this.chunks.get(dimension);
        if (chunkMap != null) {
            ChunkInfo removed = chunkMap.remove(ChunkInfo.formatCoordinates(chunkX, chunkZ));
            if (removed != null) {
                // Обновляем счетчик в зависимости от типа владельца
                if (removed.getOwnerType() == ClaimOwnerType.GUILD) {
                    guildClaimCounts.computeIfPresent(removed.getOwnerId(), (k, v) -> v > 1 ? v - 1 : null);
                } else {
                    // Для PARTY и PERSONAL используем старый счетчик
                    partyClaimCounts.computeIfPresent(removed.getPartyOwner(), (k, v) -> v > 1 ? v - 1 : null);
                }
                databaseManager.deleteClaim(dimension, chunkX, chunkZ);
                return true;
            }
        }
        return false;
    }

    /**
     * Снять клайм с чанка с проверкой прав игрока
     * @param dimension измерение
     * @param chunkX координата X чанка
     * @param chunkZ координата Z чанка
     * @param playerUUID UUID игрока
     * @return true если клайм был снят успешно, false если нет прав или клайма не существует
     */
    public boolean unclaimWithPermissionCheck(String dimension, int chunkX, int chunkZ, UUID playerUUID) {
        ChunkInfo chunkInfo = getChunk(dimension, chunkX, chunkZ);

        // Проверяем существование клайма
        if (chunkInfo == null) {
            return false;
        }

        // Проверяем права игрока
        if (!canPlayerUnclaimChunk(playerUUID, chunkInfo)) {
            return false;
        }

        // Снимаем клайм
        return unclaim(dimension, chunkX, chunkZ);
    }

    /**
     * Снять клайм с чанка по координатам блока с проверкой прав
     * @param dimension измерение
     * @param blockX координата X блока
     * @param blockZ координата Z блока
     * @param playerUUID UUID игрока
     * @return true если клайм был снят успешно, false если нет прав или клайма не существует
     */
    public boolean unclaimRawCoordsWithPermissionCheck(String dimension, int blockX, int blockZ, UUID playerUUID) {
        return unclaimWithPermissionCheck(
                dimension,
                ChunkUtil.chunkCoordinate(blockX),
                ChunkUtil.chunkCoordinate(blockZ),
                playerUUID
        );
    }

    public void unclaimRawCoords(String dimension, int blockX, int blockZ){
        this.unclaim(dimension, ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ));
    }

    public Set<String> getWorldsNeedingUpdates() {
        return worldsNeedingUpdates;
    }

    public void setNeedsMapUpdate(String world) {
        this.worldsNeedingUpdates.add(world);
    }

    public void setPlayerName(UUID uuid, String name, long lastSeen) {
        this.playerNameTracker.setPlayerName(uuid, name, lastSeen);
        this.databaseManager.saveNameCache(uuid, name, lastSeen);
    }

    public PlayerNameTracker getPlayerNameTracker() {
        return playerNameTracker;
    }

    public HashMap<String, PartyInfo> getParties() {
        return parties;
    }

    public HashMap<String, HashMap<String, ChunkInfo>> getChunks() {
        return this.chunks;
    }

    public Map<UUID, UUID> getAdminUsageParty() {
        return adminUsageParty;
    }

    /**
     * Снять клайм админом (игнорирует проверки владельца)
     * @param dimension измерение
     * @param chunkX координата X чанка
     * @param chunkZ координата Z чанка
     * @return true если клайм был снят, false если клайма не было
     */
    public boolean unclaimByAdmin(String dimension, int chunkX, int chunkZ) {
        return unclaim(dimension, chunkX, chunkZ);
    }

    public void invitePlayerToParty(PlayerRef recipient, PartyInfo partyInfo, PlayerRef sender) {
        this.partyInvites.put(recipient.getUuid(), new PartyInvite(recipient.getUuid(), sender.getUuid(), partyInfo.getId()));
    }

    public PartyInvite acceptInvite(PlayerRef player) {
        var invite = this.partyInvites.get(player.getUuid());
        if (invite == null) return null;
        var party = this.getPartyById(invite.party());
        if (party == null) return null;
        if (Main.CONFIG.get().getMaxPartyMembers() != -1 && party.getMembers().length >= Main.CONFIG.get().getMaxPartyMembers()) {
            this.partyInvites.remove(player.getUuid());
            return null;
        }
        party.addMember(player.getUuid());
        this.playerToParty.put(player.getUuid(), party.getId());
        this.partyInvites.remove(player.getUuid());
        databaseManager.saveParty(party);
        return invite;
    }

    public Map<UUID, PartyInvite> getPartyInvites() {
        return partyInvites;
    }

    public void leaveParty(PlayerRef player, PartyInfo partyInfo) {
        this.playerToParty.remove(player.getUuid());

        if (partyInfo.isOwner(player.getUuid())) {
            disbandParty(partyInfo);
            player.sendMessage(CommandMessages.PARTY_DISBANDED);
            return;
        } else {
            partyInfo.removeMember(player.getUuid());
            playerToParty.remove(player.getUuid());
            player.sendMessage(CommandMessages.PARTY_LEFT);
        }
        databaseManager.saveParty(partyInfo);
    }

    public void disbandParty(PartyInfo partyInfo) {
        for (UUID member : partyInfo.getMembers()) {
            playerToParty.remove(member);
        }
        queueMapUpdateForParty(partyInfo);
        this.chunks.forEach((dimension, chunkInfos) -> chunkInfos.values().removeIf(chunkInfo -> {
            boolean matches = chunkInfo.getPartyOwner().equals(partyInfo.getId());
            if (matches) {
                databaseManager.deleteClaim(dimension, chunkInfo.getChunkX(), chunkInfo.getChunkZ());
            }
            return matches;
        }));
        partyClaimCounts.remove(partyInfo.getId());

        this.parties.remove(partyInfo.getId().toString());
        databaseManager.deleteParty(partyInfo.getId());
    }

    public void removeAdminOverride(UUID uuid) {
        if (this.adminOverrides.remove(uuid)) {
            databaseManager.deleteAdminOverride(uuid);
        }
    }

    public void addAdminOverride(UUID uuid) {
        if (this.adminOverrides.add(uuid)) {
            databaseManager.saveAdminOverride(uuid);
        }
    }

    public Set<UUID> getAdminClaimOverrides() {
        return adminOverrides;
    }

    public void queueMapUpdateForParty(PartyInfo partyInfo) {
        this.getChunks().forEach((dimension, chunkInfos) -> {
            var world = Universe.get().getWorlds().get(dimension);
            if (world != null) {
                for (ChunkInfo value : chunkInfos.values()) {
                    if (value.getPartyOwner().equals(partyInfo.getId())) {
                        queueMapUpdate(world, value.getChunkX(), value.getChunkZ());
                    }
                }
            }
        });
    }

    public void queueMapUpdate(World world, int chunkX, int chunkZ) {
        if (!mapUpdateQueue.containsKey(world.getName())) {
            mapUpdateQueue.put(world.getName(), new LongOpenHashSet());
        }
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX, chunkZ));
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX + 1, chunkZ));
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX - 1, chunkZ));
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX, chunkZ + 1));
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX, chunkZ - 1));
        this.setNeedsMapUpdate(world.getName());
    }

    public HashMap<String, LongSet> getMapUpdateQueue() {
        return mapUpdateQueue;
    }

    public Map<UUID, UUID> getPlayerToParty() {
        return playerToParty;
    }

    public void disbandInactiveParties() {
        int inactivityHours = Main.CONFIG.get().getPartyInactivityHours();
        if (inactivityHours < 0) return;
        long inactivityMillis = inactivityHours * 60L * 60L * 1000L;
        long currentTime = System.currentTimeMillis();
        List<PartyInfo> toDisband = new ArrayList<>();
        for (PartyInfo party : parties.values()) {
            if (party.getOwner() == null && (party.getMembers() == null || party.getMembers().length == 0)) {
                continue; // Ignore if no owner and no members
            }
            boolean allInactive = true;
            if (party.getOwner() != null) {
                var playerName = playerNameTracker.getNamesMap().get(party.getOwner());
                if (playerName == null || playerName.getLastSeen() <= 0 || Universe.get().getPlayer(party.getOwner()) != null) { //Check if online also
                    allInactive = false; // Ignore if lastSeen is missing
                } else if (currentTime - playerName.getLastSeen() < inactivityMillis) {
                    allInactive = false;
                }
            }
            if (allInactive && party.getMembers() != null) {
                for (UUID member : party.getMembers()) {
                    var playerName = playerNameTracker.getNamesMap().get(member);
                    if (playerName == null || playerName.getLastSeen() <= 0 || Universe.get().getPlayer(member) != null) { //Check if online also
                        allInactive = false; // Ignore if lastSeen is missing
                        break;
                    } else if (currentTime - playerName.getLastSeen() < inactivityMillis) {
                        allInactive = false;
                        break;
                    }
                }
            }
            if (allInactive) {
                toDisband.add(party);
            }
        }
        for (PartyInfo party : toDisband) {
            logger.at(Level.INFO).log("Disbanding inactive party: " + party.getName() + " (" + party.getId() + ")");
            disbandParty(party);
        }
    }

    @Nullable
    public Guild getGuildFromPlayer(UUID playerId) {
        return GuildClaimBridge.getPlayerGuild(playerId);
    }

    /**
     * Получить гильдию по ID
     */
    @Nullable
    public Guild getGuildById(UUID guildId) {
        return GuildClaimBridge.getGuildById(guildId);
    }

    /**
     * Проверка: может ли игрок клаймить от имени гильдии?
     */
    public boolean canGuildClaim(Guild guild, UUID playerId) {
        if (guild == null) {
            return false;
        }

        // Проверка: гильдия не в черновике
        if (GuildClaimBridge.isDraft(guild)) {
            return false;
        }

        // Проверка прав (владелец или модератор)
        return GuildClaimBridge.canManageClaims(guild, playerId);
    }


    /**
     * Проверка прав на взаимодействие с Guild-чанком
     */
    private boolean isAllowedToInteractGuild(
            UUID playerUUID,
            ChunkInfo chunkInfo,
            java.util.function.Predicate<Guild> interactMethod
    ) {
        // Админ-овверрайд
        if (playerUUID != null && adminOverrides.contains(playerUUID)) {
            return true;
        }

        // Получаем гильдию-владельца
        Guild guild = getGuildById(chunkInfo.getOwnerId());
        if (guild == null) {
            // Гильдия не найдена (удалена?) - разрешаем взаимодействие
            return true;
        }

        // Проверка через predicate (например, friendlyFire)
        if (interactMethod.test(guild)) {
            return true;
        }

        // Если playerUUID == null (например, взрыв) - запрещаем
        if (playerUUID == null) {
            return false;
        }

        // Проверка: игрок - член гильдии
        return GuildClaimBridge.isMember(guild, playerUUID);
    }

    /**
     * Расширенный метод isAllowedToInteract с поддержкой Guild
     *
     * ВАЖНО: Этот метод должен ЗАМЕНИТЬ существующий isAllowedToInteract в ClaimManager
     */
    public boolean isAllowedToInteractWithOwnerType(
            UUID playerUUID,
            String dimension,
            int chunkX,
            int chunkZ,
            java.util.function.Predicate<PartyInfo> partyInteractMethod,
            java.util.function.Predicate<Guild> guildInteractMethod
    ) {
        // Админ-овверрайд
        if (playerUUID != null && adminOverrides.contains(playerUUID)) {
            return true;
        }

        // Получаем чанк
        var chunkInfo = getChunkRawCoords(dimension, chunkX, chunkZ);
        if (chunkInfo == null) {
            // Нет клайма - проверяем полную защиту мира
            return !Arrays.asList(Main.CONFIG.get().getFullWorldProtection()).contains(dimension);
        }

        // Определяем тип владельца
        ClaimOwnerType ownerType = chunkInfo.getOwnerType();

        switch (ownerType) {
            case PARTY -> {
                // Старая логика для Party
                var chunkParty = getPartyById(chunkInfo.getOwnerId());
                if (chunkParty == null || partyInteractMethod.test(chunkParty)) {
                    return true;
                }

                if (playerUUID == null) {
                    return false;
                }

                if (chunkParty.getPlayerAllies().contains(playerUUID)) {
                    return true;
                }

                var partyId = playerToParty.get(playerUUID);
                if (partyId == null) {
                    return false;
                }

                return chunkInfo.getOwnerId().equals(partyId) ||
                        chunkParty.getPartyAllies().contains(partyId);
            }

            case GUILD -> {
                // Новая логика для Guild
                return isAllowedToInteractGuild(playerUUID, chunkInfo, guildInteractMethod);
            }

            case PERSONAL -> {
                // Личный клайм
                if (playerUUID == null) {
                    return false;
                }
                return chunkInfo.getOwnerId().equals(playerUUID);
            }

            default -> {
                return false;
            }
        }
    }
    /**
     * Получить количество клаймов у гильдии
     */
    public int getGuildClaimCount(UUID guildId) {
        return guildClaimCounts.getOrDefault(guildId, 0);
    }

    /**
     * Проверка: может ли гильдия заклаймить ещё один чанк?
     *
     * ВАЖНО: Пока используем дефолтное значение из конфига
     * В будущем можно добавить систему разрешений для гильдий
     */
    public boolean hasGuildEnoughClaimsLeft(Guild guild) {
        if (guild == null) {
            return false;
        }

        int currentAmount = getGuildClaimCount(guild.getData().getId());
        int maxAmount = guild.getData().getChunksAvailable(); // TODO: добавить отдельный лимит для гильдий в конфиг

        return currentAmount < maxAmount;
    }

    /**
     * Удалить все клаймы гильдии
     * Вызывается при расформировании гильдии
     */
    public void unclaimAllByGuild(UUID guildId) {
        this.chunks.forEach((dimension, chunkInfos) -> {
            chunkInfos.values().removeIf(chunkInfo -> {
                boolean matches = chunkInfo.getOwnerType() == ClaimOwnerType.GUILD &&
                        chunkInfo.getOwnerId().equals(guildId);

                if (matches) {
                    databaseManager.deleteClaim(dimension, chunkInfo.getChunkX(), chunkInfo.getChunkZ());
                }

                return matches;
            });
        });

        guildClaimCounts.remove(guildId);
    }

    /**
     * Очистка "осиротевших" Guild-клаймов
     * Вызывается при старте сервера или периодически
     */
    public void cleanupOrphanedGuildClaims() {
        logger.at(Level.INFO).log("Checking for orphaned guild claims...");

        int removedCount = 0;

        for (String dimension : this.chunks.keySet()) {
            var chunkMap = this.chunks.get(dimension);
            var iterator = chunkMap.entrySet().iterator();

            while (iterator.hasNext()) {
                var entry = iterator.next();
                ChunkInfo chunkInfo = entry.getValue();

                // Проверяем только Guild-клаймы
                if (chunkInfo.getOwnerType() != ClaimOwnerType.GUILD) {
                    continue;
                }

                // Проверяем существование гильдии
                Guild guild = getGuildById(chunkInfo.getOwnerId());
                if (guild == null) {
                    // Гильдия не найдена - удаляем клайм
                    iterator.remove();
                    databaseManager.deleteClaim(dimension, chunkInfo.getChunkX(), chunkInfo.getChunkZ());
                    removedCount++;
                }
            }
        }

        if (removedCount > 0) {
            logger.at(Level.INFO).log("Removed " + removedCount + " orphaned guild claims");
        }
    }

}
