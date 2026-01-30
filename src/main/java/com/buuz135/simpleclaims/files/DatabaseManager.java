package com.buuz135.simpleclaims.files;

import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.claim.party.PartyOverride;
import com.buuz135.simpleclaims.claim.player_name.PlayerNameTracker;
import com.buuz135.simpleclaims.claim.tracking.ModifiedTracking;
import com.buuz135.simpleclaims.constants.ClaimOwnerType;
import com.buuz135.simpleclaims.util.FileUtils;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final HytaleLogger logger;
    private Connection connection;

    public DatabaseManager(HytaleLogger logger) {
        this.logger = logger;

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Couldn't find relocated JDBC driver for SQLite");
        }
        FileUtils.ensureMainDirectory();
        try {
            var sqliteFile = new File(FileUtils.DATABASE_PATH);

            if (!sqliteFile.exists()) {
                sqliteFile.createNewFile();
            }

            this.connection = DriverManager.getConnection("jdbc:sqlite:" + FileUtils.DATABASE_PATH);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON;");
            }
            createTables();
            // ВАЖНО: Добавляем колонку owner_type если её нет
            ensureOwnerTypeColumn();
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS parties (" +
                    "id TEXT PRIMARY KEY," +
                    "owner TEXT," +
                    "name TEXT," +
                    "description TEXT," +
                    "color INTEGER," +
                    "created_user_uuid TEXT," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "modified_user_uuid TEXT," +
                    "modified_user_name TEXT," +
                    "modified_date TEXT" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_members (" +
                    "party_id TEXT," +
                    "member_uuid TEXT," +
                    "PRIMARY KEY (party_id, member_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_overrides (" +
                    "party_id TEXT," +
                    "type TEXT," +
                    "value_type TEXT," +
                    "value TEXT," +
                    "PRIMARY KEY (party_id, type)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_allies (" +
                    "party_id TEXT," +
                    "ally_party_id TEXT," +
                    "PRIMARY KEY (party_id, ally_party_id)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_allies (" +
                    "party_id TEXT," +
                    "player_uuid TEXT," +
                    "PRIMARY KEY (party_id, player_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            // ИСПРАВЛЕНО: Добавлена колонка owner_type (будет добавлена в ensureOwnerTypeColumn если таблица уже существует)
            statement.execute("CREATE TABLE IF NOT EXISTS claims (" +
                    "dimension TEXT," +
                    "chunkX INTEGER," +
                    "chunkZ INTEGER," +
                    "owner_id TEXT," +  // ПЕРЕИМЕНОВАНО: было party_owner
                    "owner_type TEXT DEFAULT 'PARTY'," + // ДОБАВЛЕНО: тип владельца
                    "created_user_uuid TEXT," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "PRIMARY KEY (dimension, chunkX, chunkZ)" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS name_cache (" +
                    "uuid TEXT PRIMARY KEY," +
                    "name TEXT," +
                    "last_seen INTEGER DEFAULT -1" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS admin_overrides (" +
                    "uuid TEXT PRIMARY KEY" +
                    ")");

            addColumnIfNotExists("name_cache", "last_seen", "INTEGER DEFAULT " + System.currentTimeMillis());
        }
    }

    /**
     * НОВЫЙ МЕТОД: Добавляет колонку owner_type если её нет и мигрирует party_owner -> owner_id
     */
    private void ensureOwnerTypeColumn() {
        try {
            boolean hasOwnerType = false;
            boolean hasOwnerId = false;
            boolean hasPartyOwner = false;

            // Проверяем какие колонки существуют
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, "claims", null)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    if ("owner_type".equalsIgnoreCase(columnName)) {
                        hasOwnerType = true;
                    }
                    if ("owner_id".equalsIgnoreCase(columnName)) {
                        hasOwnerId = true;
                    }
                    if ("party_owner".equalsIgnoreCase(columnName)) {
                        hasPartyOwner = true;
                    }
                }
            }

            // Если есть старая колонка party_owner, но нет новых - делаем УМНУЮ миграцию
            if (hasPartyOwner && !hasOwnerId) {
                logger.at(Level.INFO).log("============================================================");
                logger.at(Level.INFO).log("Starting claims table migration: party_owner → owner_id + owner_type");
                logger.at(Level.INFO).log("============================================================");

                try (Statement stmt = connection.createStatement()) {
                    // Получаем статистику ДО миграции
                    ResultSet countBefore = stmt.executeQuery("SELECT COUNT(*) FROM claims");
                    int totalClaims = countBefore.getInt(1);
                    logger.at(Level.INFO).log("Total claims to migrate: " + totalClaims);

                    // 1. Создаём новую таблицу
                    logger.at(Level.INFO).log("Step 1: Creating new table structure...");
                    stmt.execute(
                            "CREATE TABLE claims_new (" +
                                    "    dimension TEXT," +
                                    "    chunkX INTEGER," +
                                    "    chunkZ INTEGER," +
                                    "    owner_id TEXT," +
                                    "    owner_type TEXT DEFAULT 'PARTY'," +
                                    "    created_user_uuid TEXT," +
                                    "    created_user_name TEXT," +
                                    "    created_date TEXT," +
                                    "    PRIMARY KEY (dimension, chunkX, chunkZ)" +
                                    ")"
                    );
                    logger.at(Level.INFO).log("  ✓ Table created");

                    // 2. Копируем данные с УМНЫМ определением типа
                    logger.at(Level.INFO).log("Step 2: Migrating data with smart type detection...");
                    stmt.execute(
                            "INSERT INTO claims_new " +
                                    "    (dimension, chunkX, chunkZ, owner_id, owner_type, " +
                                    "     created_user_uuid, created_user_name, created_date) " +
                                    "SELECT " +
                                    "    c.dimension, " +
                                    "    c.chunkX, " +
                                    "    c.chunkZ, " +
                                    "    c.party_owner, " +
                                    "    CASE " +
                                    "        WHEN p.id IS NOT NULL THEN 'PARTY' " +
                                    "        ELSE 'PERSONAL' " +
                                    "    END as owner_type, " +
                                    "    c.created_user_uuid, " +
                                    "    c.created_user_name, " +
                                    "    c.created_date " +
                                    "FROM claims c " +
                                    "LEFT JOIN parties p ON c.party_owner = p.id"
                    );
                    logger.at(Level.INFO).log("  ✓ Data migrated");

                    // 3. Проверяем результаты миграции
                    logger.at(Level.INFO).log("Step 3: Verifying migration results...");
                    ResultSet typeStats = stmt.executeQuery(
                            "SELECT owner_type, COUNT(*) as count " +
                                    "FROM claims_new " +
                                    "GROUP BY owner_type"
                    );

                    int partyCount = 0;
                    int personalCount = 0;
                    while (typeStats.next()) {
                        String type = typeStats.getString("owner_type");
                        int count = typeStats.getInt("count");
                        if ("PARTY".equals(type)) {
                            partyCount = count;
                        } else if ("PERSONAL".equals(type)) {
                            personalCount = count;
                        }
                        logger.at(Level.INFO).log("  - " + type + ": " + count + " claims");
                    }

                    // Проверка целостности
                    int totalMigrated = partyCount + personalCount;
                    if (totalMigrated != totalClaims) {
                        logger.at(Level.WARNING).log("  ⚠ Warning: Claim count mismatch!");
                        logger.at(Level.WARNING).log("    Before: " + totalClaims + ", After: " + totalMigrated);
                    } else {
                        logger.at(Level.INFO).log("  ✓ All claims migrated successfully");
                    }

                    // 4. Заменяем таблицы
                    logger.at(Level.INFO).log("Step 4: Replacing old table...");
                    stmt.execute("DROP TABLE claims");
                    stmt.execute("ALTER TABLE claims_new RENAME TO claims");
                    logger.at(Level.INFO).log("  ✓ Table replaced");

                    logger.at(Level.INFO).log("============================================================");
                    logger.at(Level.INFO).log("Migration completed successfully!");
                    logger.at(Level.INFO).log("  PARTY claims:    " + partyCount);
                    logger.at(Level.INFO).log("  PERSONAL claims: " + personalCount);
                    logger.at(Level.INFO).log("============================================================");
                }
            }
            // Если есть owner_id но нет owner_type - добавляем колонку
            else if (hasOwnerId && !hasOwnerType) {
                logger.at(Level.INFO).log("Adding owner_type column to claims table...");

                try (Statement stmt = connection.createStatement()) {
                    // Добавляем колонку
                    stmt.execute("ALTER TABLE claims ADD COLUMN owner_type TEXT DEFAULT 'PARTY'");

                    // УМНАЯ МИГРАЦИЯ: проверяем какие клаймы должны быть PERSONAL
                    stmt.execute(
                            "UPDATE claims " +
                                    "SET owner_type = 'PERSONAL' " +
                                    "WHERE owner_id NOT IN (SELECT id FROM parties)"
                    );

                    // Логируем результаты
                    ResultSet typeStats = stmt.executeQuery(
                            "SELECT owner_type, COUNT(*) as count " +
                                    "FROM claims " +
                                    "GROUP BY owner_type"
                    );

                    logger.at(Level.INFO).log("Updated owner_type. Claim types:");
                    while (typeStats.next()) {
                        logger.at(Level.INFO).log("  " + typeStats.getString("owner_type") +
                                ": " + typeStats.getInt("count") + " claims");
                    }
                }

                logger.at(Level.INFO).log("Successfully added owner_type column");
            }
            else if (hasOwnerId && hasOwnerType) {
                logger.at(Level.INFO).log("Claims table already migrated (has owner_id and owner_type)");
            }

        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Failed to migrate claims table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            if (!rs.next()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                }
            }
        }
    }

    public boolean isMigrationNecessary() {
        if (!new File(FileUtils.MAIN_PATH + File.separator + ".migrated").exists()) {
            return hasAnyJsonFile();
        }
        return false;
    }

    private boolean hasAnyJsonFile() {
        return new File(FileUtils.PARTY_PATH).exists() ||
                new File(FileUtils.CLAIM_PATH).exists() ||
                new File(FileUtils.NAMES_CACHE_PATH).exists() ||
                new File(FileUtils.ADMIN_OVERRIDES_PATH).exists();
    }

    public void migrate(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile, PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        performMigration(partyFile, chunkFile, nameFile, adminFile);
    }

    private void performMigration(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile, PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        logger.at(Level.INFO).log("Starting migration to SQLite...");

        // Backup the entire folder
        try {
            Path source = Paths.get(FileUtils.MAIN_PATH);
            Path backup = Paths.get(FileUtils.MAIN_PATH + "_backup_" + System.currentTimeMillis());
            copyFolder(source, backup);
            logger.at(Level.INFO).log("Backup created at: " + backup.toAbsolutePath());
        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to create backup before migration: " + e.getMessage());
            return;
        }

        try {
            connection.setAutoCommit(false);

            // Migrate Parties
            for (PartyInfo party : partyFile.getParties().values()) {
                saveParty(party);
            }

            // Migrate Claims
            for (Map.Entry<String, HashMap<String, ChunkInfo>> dimEntry : chunkFile.getChunks().entrySet()) {
                String dimension = dimEntry.getKey();
                for (ChunkInfo chunk : dimEntry.getValue().values()) {
                    saveClaim(dimension, chunk);
                }
            }

            // Migrate Name Cache
            for (PlayerNameTracker.PlayerName name : nameFile.getTracker().getNames()) {
                saveNameCache(name.getUuid(), name.getName(), name.getLastSeen());
            }

            // Migrate Admin Overrides
            for (UUID uuid : adminFile.getAdminOverrides()) {
                saveAdminOverride(uuid);
            }

            connection.commit();
            connection.setAutoCommit(true);

            // Create migration marker
            new File(FileUtils.MAIN_PATH + File.separator + ".migrated").createNewFile();
            logger.at(Level.INFO).log("Migration to SQLite completed successfully.");
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            logger.at(Level.SEVERE).log("Migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void copyFolder(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    if (!Files.exists(dest)) Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void saveParty(PartyInfo party) {
        try {
            PreparedStatement ps = connection.prepareStatement("REPLACE INTO parties (id, owner, name, description, color, created_user_uuid, created_user_name, created_date, modified_user_uuid, modified_user_name, modified_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, party.getId().toString());
            ps.setString(2, party.getOwner().toString());
            ps.setString(3, party.getName());
            ps.setString(4, party.getDescription());
            ps.setInt(5, party.getColor());
            ps.setString(6, party.getCreatedTracked().getUserUUID().toString());
            ps.setString(7, party.getCreatedTracked().getUserName());
            ps.setString(8, party.getCreatedTracked().getDate());
            ps.setString(9, party.getModifiedTracked().getUserUUID().toString());
            ps.setString(10, party.getModifiedTracked().getUserName());
            ps.setString(11, party.getModifiedTracked().getDate());
            ps.executeUpdate();

            // Members
            try (PreparedStatement deleteMembers = connection.prepareStatement("DELETE FROM party_members WHERE party_id = ?")) {
                deleteMembers.setString(1, party.getId().toString());
                deleteMembers.executeUpdate();
            }
            try (PreparedStatement insertMember = connection.prepareStatement("INSERT INTO party_members (party_id, member_uuid) VALUES (?, ?)")) {
                for (UUID member : party.getMembers()) {
                    insertMember.setString(1, party.getId().toString());
                    insertMember.setString(2, member.toString());
                    insertMember.addBatch();
                }
                insertMember.executeBatch();
            }

            // Overrides
            try (PreparedStatement deleteOverrides = connection.prepareStatement("DELETE FROM party_overrides WHERE party_id = ?")) {
                deleteOverrides.setString(1, party.getId().toString());
                deleteOverrides.executeUpdate();
            }
            try (PreparedStatement insertOverride = connection.prepareStatement("INSERT INTO party_overrides (party_id, type, value_type, value) VALUES (?, ?, ?, ?)")) {
                for (PartyOverride override : party.getOverrides()) {
                    insertOverride.setString(1, party.getId().toString());
                    insertOverride.setString(2, override.getType());
                    insertOverride.setString(3, override.getValue().getType());
                    insertOverride.setString(4, override.getValue().getValue());
                    insertOverride.addBatch();
                }
                insertOverride.executeBatch();
            }

            // Party Allies
            try (PreparedStatement deleteAllies = connection.prepareStatement("DELETE FROM party_allies WHERE party_id = ?")) {
                deleteAllies.setString(1, party.getId().toString());
                deleteAllies.executeUpdate();
            }
            try (PreparedStatement insertAlly = connection.prepareStatement("INSERT INTO party_allies (party_id, ally_party_id) VALUES (?, ?)")) {
                for (UUID ally : party.getPartyAllies()) {
                    insertAlly.setString(1, party.getId().toString());
                    insertAlly.setString(2, ally.toString());
                    insertAlly.addBatch();
                }
                insertAlly.executeBatch();
            }

            // Player Allies
            try (PreparedStatement deletePAllies = connection.prepareStatement("DELETE FROM player_allies WHERE party_id = ?")) {
                deletePAllies.setString(1, party.getId().toString());
                deletePAllies.executeUpdate();
            }
            try (PreparedStatement insertPAlly = connection.prepareStatement("INSERT INTO player_allies (party_id, player_uuid) VALUES (?, ?)")) {
                for (UUID ally : party.getPlayerAllies()) {
                    insertPAlly.setString(1, party.getId().toString());
                    insertPAlly.setString(2, ally.toString());
                    insertPAlly.addBatch();
                }
                insertPAlly.executeBatch();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteParty(UUID partyId) {
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM parties WHERE id = ?")) {
                ps.setString(1, partyId.toString());
                ps.executeUpdate();
            }
            // Cascading deletes should handle others if foreign keys are working,
            // but SQLite requires PRAGMA foreign_keys = ON;
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, PartyInfo> loadParties() {
        Map<String, PartyInfo> parties = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM parties")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                PartyInfo party = new PartyInfo(
                        id,
                        UUID.fromString(rs.getString("owner")),
                        rs.getString("name"),
                        rs.getString("description"),
                        new UUID[0],
                        rs.getInt("color")
                );
                party.setCreatedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("created_user_uuid")),
                        rs.getString("created_user_name"),
                        rs.getString("created_date")
                ));
                party.setModifiedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("modified_user_uuid")),
                        rs.getString("modified_user_name"),
                        rs.getString("modified_date")
                ));

                // Load members
                try (PreparedStatement ps = connection.prepareStatement("SELECT member_uuid FROM party_members WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsMembers = ps.executeQuery()) {
                        List<UUID> members = new ArrayList<>();
                        while (rsMembers.next()) {
                            members.add(UUID.fromString(rsMembers.getString("member_uuid")));
                        }
                        party.setMembers(members.toArray(new UUID[0]));
                    }
                }

                // Load overrides
                try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM party_overrides WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsOverrides = ps.executeQuery()) {
                        while (rsOverrides.next()) {
                            party.setOverride(new PartyOverride(
                                    rsOverrides.getString("type"),
                                    new PartyOverride.PartyOverrideValue(rsOverrides.getString("value_type"), rsOverrides.getString("value"))
                            ));
                        }
                    }
                }

                // Load party allies
                try (PreparedStatement ps = connection.prepareStatement("SELECT ally_party_id FROM party_allies WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsAllies = ps.executeQuery()) {
                        while (rsAllies.next()) {
                            party.addPartyAllies(UUID.fromString(rsAllies.getString("ally_party_id")));
                        }
                    }
                }

                // Load player allies
                try (PreparedStatement ps = connection.prepareStatement("SELECT player_uuid FROM player_allies WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsAllies = ps.executeQuery()) {
                        while (rsAllies.next()) {
                            party.addPlayerAllies(UUID.fromString(rsAllies.getString("player_uuid")));
                        }
                    }
                }

                parties.put(id.toString(), party);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return parties;
    }

    // ИСПРАВЛЕНО: Теперь сохраняет owner_id и owner_type
    public void saveClaim(String dimension, ChunkInfo chunk) {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO claims (dimension, chunkX, chunkZ, owner_id, owner_type, created_user_uuid, created_user_name, created_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunk.getChunkX());
            ps.setInt(3, chunk.getChunkZ());
            ps.setString(4, chunk.getOwnerId().toString()); // ИСПРАВЛЕНО: используем getOwnerId()
            ps.setString(5, chunk.getOwnerType().name());   // ДОБАВЛЕНО: сохраняем тип владельца
            ps.setString(6, chunk.getCreatedTracked().getUserUUID().toString());
            ps.setString(7, chunk.getCreatedTracked().getUserName());
            ps.setString(8, chunk.getCreatedTracked().getDate());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error saving claim: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void deleteClaim(String dimension, int chunkX, int chunkZ) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE dimension = ? AND chunkX = ? AND chunkZ = ?")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ИСПРАВЛЕНО: Теперь загружает owner_id и owner_type
    public HashMap<String, HashMap<String, ChunkInfo>> loadClaims() {
        HashMap<String, HashMap<String, ChunkInfo>> claims = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM claims")) {
            while (rs.next()) {
                String dimension = rs.getString("dimension");

                // ИСПРАВЛЕНО: Правильно загружаем owner_id и owner_type
                UUID ownerId = UUID.fromString(rs.getString("owner_id"));
                String ownerTypeStr = rs.getString("owner_type");
                ClaimOwnerType ownerType;

                // Обрабатываем случай когда owner_type отсутствует (старые записи)
                if (ownerTypeStr == null || ownerTypeStr.isEmpty()) {
                    ownerType = ClaimOwnerType.PARTY; // Дефолтное значение для старых записей
                    logger.at(Level.WARNING).log("Chunk at " + rs.getInt("chunkX") + "," + rs.getInt("chunkZ") +
                            " in " + dimension + " has no owner_type, defaulting to PARTY");
                } else {
                    try {
                        ownerType = ClaimOwnerType.valueOf(ownerTypeStr);
                    } catch (IllegalArgumentException e) {
                        logger.at(Level.WARNING).log("Invalid owner_type '" + ownerTypeStr + "' for chunk at " +
                                rs.getInt("chunkX") + "," + rs.getInt("chunkZ") + ", defaulting to PARTY");
                        ownerType = ClaimOwnerType.PARTY;
                    }
                }

                ChunkInfo chunk = new ChunkInfo(
                        ownerId,
                        ownerType,
                        rs.getInt("chunkX"),
                        rs.getInt("chunkZ")
                );

                chunk.setCreatedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("created_user_uuid")),
                        rs.getString("created_user_name"),
                        rs.getString("created_date")
                ));

                claims.computeIfAbsent(dimension, k -> new HashMap<>())
                        .put(ChunkInfo.formatCoordinates(chunk.getChunkX(), chunk.getChunkZ()), chunk);
            }
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error loading claims: " + e.getMessage());
            e.printStackTrace();
        }
        return claims;
    }

    public void saveNameCache(UUID uuid, String name, long lastSeen) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO name_cache (uuid, name, last_seen) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, lastSeen);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerNameTracker loadNameCache() {
        PlayerNameTracker tracker = new PlayerNameTracker();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM name_cache")) {
            while (rs.next()) {
                tracker.setPlayerName(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("last_seen"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tracker;
    }

    public void saveAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO admin_overrides (uuid) VALUES (?)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM admin_overrides WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Set<UUID> loadAdminOverrides() {
        Set<UUID> overrides = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM admin_overrides")) {
            while (rs.next()) {
                overrides.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return overrides;
    }
}