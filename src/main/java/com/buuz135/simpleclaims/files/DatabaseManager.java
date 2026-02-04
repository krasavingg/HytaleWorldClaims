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
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    /**
     * Конструктор для PostgreSQL
     * @param logger Логгер
     * @param host Хост PostgreSQL (например, "localhost")
     * @param port Порт PostgreSQL (обычно 5432)
     * @param database Имя базы данных
     * @param username Имя пользователя
     * @param password Пароль
     */
    public  DatabaseManager(HytaleLogger logger, String host, int port, String database, String username, String password) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;

        try {
            Class.forName("org.postgresql.Driver");
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Couldn't find PostgreSQL JDBC driver");
        }

        FileUtils.ensureMainDirectory();

        try {
            var jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?currentSchema=simpleclaimsguild", host, port, database);
            this.connection = DriverManager.getConnection(jdbcUrl, username, password);

            createTables();
            ensureOwnerTypeColumn();
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Error initializing database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Parties table
            statement.execute("CREATE TABLE IF NOT EXISTS parties (" +
                    "id VARCHAR(36) PRIMARY KEY," +
                    "owner VARCHAR(36)," +
                    "name TEXT," +
                    "description TEXT," +
                    "color INTEGER," +
                    "created_user_uuid VARCHAR(36)," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "modified_user_uuid VARCHAR(36)," +
                    "modified_user_name TEXT," +
                    "modified_date TEXT" +
                    ")");

            // Party members table
            statement.execute("CREATE TABLE IF NOT EXISTS party_members (" +
                    "party_id VARCHAR(36)," +
                    "member_uuid VARCHAR(36)," +
                    "PRIMARY KEY (party_id, member_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            // Party overrides table
            statement.execute("CREATE TABLE IF NOT EXISTS party_overrides (" +
                    "party_id VARCHAR(36)," +
                    "type TEXT," +
                    "value_type TEXT," +
                    "value TEXT," +
                    "PRIMARY KEY (party_id, type)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            // Party allies table
            statement.execute("CREATE TABLE IF NOT EXISTS party_allies (" +
                    "party_id VARCHAR(36)," +
                    "ally_party_id VARCHAR(36)," +
                    "PRIMARY KEY (party_id, ally_party_id)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            // Player allies table
            statement.execute("CREATE TABLE IF NOT EXISTS player_allies (" +
                    "party_id VARCHAR(36)," +
                    "player_uuid VARCHAR(36)," +
                    "PRIMARY KEY (party_id, player_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            // Claims table with owner_type support
            statement.execute("CREATE TABLE IF NOT EXISTS claims (" +
                    "dimension TEXT," +
                    "chunkX INTEGER," +
                    "chunkZ INTEGER," +
                    "owner_id VARCHAR(36)," +
                    "owner_type VARCHAR(20) DEFAULT 'PARTY'," +
                    "created_user_uuid VARCHAR(36)," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "PRIMARY KEY (dimension, chunkX, chunkZ)" +
                    ")");

            // Create index for faster queries
            statement.execute("CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner_id, owner_type)");

            // Name cache table
            statement.execute("CREATE TABLE IF NOT EXISTS name_cache (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "name TEXT," +
                    "last_seen BIGINT DEFAULT -1" +
                    ")");

            // Admin overrides table
            statement.execute("CREATE TABLE IF NOT EXISTS admin_overrides (" +
                    "uuid VARCHAR(36) PRIMARY KEY" +
                    ")");
        }
    }

    /**
     * Обеспечивает наличие колонки owner_type и мигрирует старые данные
     */
    private void ensureOwnerTypeColumn() {
        try {
            boolean hasOwnerType = false;
            boolean hasOwnerId = false;
            boolean hasPartyOwner = false;

            // Проверяем существующие колонки
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, null, "claims", null)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME").toLowerCase();
                    if ("owner_type".equals(columnName)) {
                        hasOwnerType = true;
                    }
                    if ("owner_id".equals(columnName)) {
                        hasOwnerId = true;
                    }
                    if ("party_owner".equals(columnName)) {
                        hasPartyOwner = true;
                    }
                }
            }

            // Миграция со старой схемы party_owner
            if (hasPartyOwner && !hasOwnerId) {
                logger.at(Level.INFO).log("============================================================");
                logger.at(Level.INFO).log("Starting claims table migration: party_owner → owner_id + owner_type");
                logger.at(Level.INFO).log("============================================================");

                try (Statement stmt = connection.createStatement()) {
                    // Получаем статистику до миграции
                    ResultSet countBefore = stmt.executeQuery("SELECT COUNT(*) FROM claims");
                    countBefore.next();
                    int totalClaims = countBefore.getInt(1);
                    logger.at(Level.INFO).log("Total claims to migrate: " + totalClaims);

                    // 1. Добавляем новые колонки
                    logger.at(Level.INFO).log("Step 1: Adding new columns...");
                    stmt.execute("ALTER TABLE claims ADD COLUMN owner_id VARCHAR(36)");
                    stmt.execute("ALTER TABLE claims ADD COLUMN owner_type VARCHAR(20) DEFAULT 'PARTY'");
                    logger.at(Level.INFO).log("  ✓ Columns added");

                    // 2. Копируем данные с умным определением типа
                    logger.at(Level.INFO).log("Step 2: Migrating data with smart type detection...");
                    stmt.execute(
                            "UPDATE claims c " +
                                    "SET owner_id = c.party_owner, " +
                                    "    owner_type = CASE " +
                                    "        WHEN EXISTS (SELECT 1 FROM parties p WHERE p.id = c.party_owner) " +
                                    "        THEN 'PARTY' " +
                                    "        ELSE 'PERSONAL' " +
                                    "    END"
                    );
                    logger.at(Level.INFO).log("  ✓ Data migrated");

                    // 3. Проверяем результаты
                    logger.at(Level.INFO).log("Step 3: Verifying migration results...");
                    ResultSet typeStats = stmt.executeQuery(
                            "SELECT owner_type, COUNT(*) as count " +
                                    "FROM claims " +
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

                    // 4. Удаляем старую колонку
                    logger.at(Level.INFO).log("Step 4: Removing old column...");
                    stmt.execute("ALTER TABLE claims DROP COLUMN party_owner");
                    logger.at(Level.INFO).log("  ✓ Old column removed");

                    // 5. Делаем owner_id NOT NULL
                    logger.at(Level.INFO).log("Step 5: Setting constraints...");
                    stmt.execute("ALTER TABLE claims ALTER COLUMN owner_id SET NOT NULL");
                    logger.at(Level.INFO).log("  ✓ Constraints set");

                    logger.at(Level.INFO).log("============================================================");
                    logger.at(Level.INFO).log("Migration completed successfully!");
                    logger.at(Level.INFO).log("  PARTY claims:    " + partyCount);
                    logger.at(Level.INFO).log("  PERSONAL claims: " + personalCount);
                    logger.at(Level.INFO).log("============================================================");
                }
            }
            // Если есть owner_id но нет owner_type
            else if (hasOwnerId && !hasOwnerType) {
                logger.at(Level.INFO).log("Adding owner_type column to claims table...");

                try (Statement stmt = connection.createStatement()) {
                    // Добавляем колонку
                    stmt.execute("ALTER TABLE claims ADD COLUMN owner_type VARCHAR(20) DEFAULT 'PARTY'");

                    // Умная миграция: проверяем какие клаймы должны быть PERSONAL
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

    public void migrate(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile,
                        PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        performMigration(partyFile, chunkFile, nameFile, adminFile);
    }

    private void performMigration(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile,
                                  PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        logger.at(Level.INFO).log("Starting migration to PostgreSQL...");

        // Backup
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
            logger.at(Level.INFO).log("Migration to PostgreSQL completed successfully.");
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
            // PostgreSQL использует INSERT ... ON CONFLICT для upsert
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO parties (id, owner, name, description, color, " +
                            "created_user_uuid, created_user_name, created_date, " +
                            "modified_user_uuid, modified_user_name, modified_date) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (id) DO UPDATE SET " +
                            "owner = EXCLUDED.owner, " +
                            "name = EXCLUDED.name, " +
                            "description = EXCLUDED.description, " +
                            "color = EXCLUDED.color, " +
                            "created_user_uuid = EXCLUDED.created_user_uuid, " +
                            "created_user_name = EXCLUDED.created_user_name, " +
                            "created_date = EXCLUDED.created_date, " +
                            "modified_user_uuid = EXCLUDED.modified_user_uuid, " +
                            "modified_user_name = EXCLUDED.modified_user_name, " +
                            "modified_date = EXCLUDED.modified_date"
            );
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
            try (PreparedStatement deleteMembers = connection.prepareStatement(
                    "DELETE FROM party_members WHERE party_id = ?")) {
                deleteMembers.setString(1, party.getId().toString());
                deleteMembers.executeUpdate();
            }
            try (PreparedStatement insertMember = connection.prepareStatement(
                    "INSERT INTO party_members (party_id, member_uuid) VALUES (?, ?)")) {
                for (UUID member : party.getMembers()) {
                    insertMember.setString(1, party.getId().toString());
                    insertMember.setString(2, member.toString());
                    insertMember.addBatch();
                }
                insertMember.executeBatch();
            }

            // Overrides
            try (PreparedStatement deleteOverrides = connection.prepareStatement(
                    "DELETE FROM party_overrides WHERE party_id = ?")) {
                deleteOverrides.setString(1, party.getId().toString());
                deleteOverrides.executeUpdate();
            }
            try (PreparedStatement insertOverride = connection.prepareStatement(
                    "INSERT INTO party_overrides (party_id, type, value_type, value) VALUES (?, ?, ?, ?)")) {
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
            try (PreparedStatement deleteAllies = connection.prepareStatement(
                    "DELETE FROM party_allies WHERE party_id = ?")) {
                deleteAllies.setString(1, party.getId().toString());
                deleteAllies.executeUpdate();
            }
            try (PreparedStatement insertAlly = connection.prepareStatement(
                    "INSERT INTO party_allies (party_id, ally_party_id) VALUES (?, ?)")) {
                for (UUID ally : party.getPartyAllies()) {
                    insertAlly.setString(1, party.getId().toString());
                    insertAlly.setString(2, ally.toString());
                    insertAlly.addBatch();
                }
                insertAlly.executeBatch();
            }

            // Player Allies
            try (PreparedStatement deletePAllies = connection.prepareStatement(
                    "DELETE FROM player_allies WHERE party_id = ?")) {
                deletePAllies.setString(1, party.getId().toString());
                deletePAllies.executeUpdate();
            }
            try (PreparedStatement insertPAlly = connection.prepareStatement(
                    "INSERT INTO player_allies (party_id, player_uuid) VALUES (?, ?)")) {
                for (UUID ally : party.getPlayerAllies()) {
                    insertPAlly.setString(1, party.getId().toString());
                    insertPAlly.setString(2, ally.toString());
                    insertPAlly.addBatch();
                }
                insertPAlly.executeBatch();
            }

        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error saving party: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void deleteParty(UUID partyId) {
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM parties WHERE id = ?")) {
                ps.setString(1, partyId.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error deleting party: " + e.getMessage());
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
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT member_uuid FROM party_members WHERE party_id = ?")) {
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
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM party_overrides WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsOverrides = ps.executeQuery()) {
                        while (rsOverrides.next()) {
                            party.setOverride(new PartyOverride(
                                    rsOverrides.getString("type"),
                                    new PartyOverride.PartyOverrideValue(
                                            rsOverrides.getString("value_type"),
                                            rsOverrides.getString("value"))
                            ));
                        }
                    }
                }

                // Load party allies
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT ally_party_id FROM party_allies WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsAllies = ps.executeQuery()) {
                        while (rsAllies.next()) {
                            party.addPartyAllies(UUID.fromString(rsAllies.getString("ally_party_id")));
                        }
                    }
                }

                // Load player allies
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT player_uuid FROM player_allies WHERE party_id = ?")) {
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
            logger.at(Level.SEVERE).log("Error loading parties: " + e.getMessage());
            e.printStackTrace();
        }
        return parties;
    }

    public void saveClaim(String dimension, ChunkInfo chunk) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO claims (dimension, chunkX, chunkZ, owner_id, owner_type, " +
                        "created_user_uuid, created_user_name, created_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (dimension, chunkX, chunkZ) DO UPDATE SET " +
                        "owner_id = EXCLUDED.owner_id, " +
                        "owner_type = EXCLUDED.owner_type, " +
                        "created_user_uuid = EXCLUDED.created_user_uuid, " +
                        "created_user_name = EXCLUDED.created_user_name, " +
                        "created_date = EXCLUDED.created_date")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunk.getChunkX());
            ps.setInt(3, chunk.getChunkZ());
            ps.setString(4, chunk.getOwnerId().toString());
            ps.setString(5, chunk.getOwnerType().name());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM claims WHERE dimension = ? AND chunkX = ? AND chunkZ = ?")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error deleting claim: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public HashMap<String, HashMap<String, ChunkInfo>> loadClaims() {
        HashMap<String, HashMap<String, ChunkInfo>> claims = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM claims")) {
            while (rs.next()) {
                String dimension = rs.getString("dimension");

                UUID ownerId = UUID.fromString(rs.getString("owner_id"));
                String ownerTypeStr = rs.getString("owner_type");
                ClaimOwnerType ownerType;

                if (ownerTypeStr == null || ownerTypeStr.isEmpty()) {
                    ownerType = ClaimOwnerType.PARTY;
                    logger.at(Level.WARNING).log("Chunk at " + rs.getInt("chunkX") + "," +
                            rs.getInt("chunkZ") + " in " + dimension +
                            " has no owner_type, defaulting to PARTY");
                } else {
                    try {
                        ownerType = ClaimOwnerType.valueOf(ownerTypeStr);
                    } catch (IllegalArgumentException e) {
                        logger.at(Level.WARNING).log("Invalid owner_type '" + ownerTypeStr +
                                "' for chunk at " + rs.getInt("chunkX") + "," + rs.getInt("chunkZ") +
                                ", defaulting to PARTY");
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
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO name_cache (uuid, name, last_seen) VALUES (?, ?, ?) " +
                        "ON CONFLICT (uuid) DO UPDATE SET name = EXCLUDED.name, last_seen = EXCLUDED.last_seen")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, lastSeen);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error saving name cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public PlayerNameTracker loadNameCache() {
        PlayerNameTracker tracker = new PlayerNameTracker();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM name_cache")) {
            while (rs.next()) {
                tracker.setPlayerName(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getLong("last_seen")
                );
            }
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error loading name cache: " + e.getMessage());
            e.printStackTrace();
        }
        return tracker;
    }

    public void saveAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO admin_overrides (uuid) VALUES (?) ON CONFLICT (uuid) DO NOTHING")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error saving admin override: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void deleteAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM admin_overrides WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Error deleting admin override: " + e.getMessage());
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
            logger.at(Level.SEVERE).log("Error loading admin overrides: " + e.getMessage());
            e.printStackTrace();
        }
        return overrides;
    }

    /**
     * Закрывает соединение с базой данных
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.at(Level.INFO).log("Database connection closed");
            } catch (SQLException e) {
                logger.at(Level.SEVERE).log("Error closing database connection: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}