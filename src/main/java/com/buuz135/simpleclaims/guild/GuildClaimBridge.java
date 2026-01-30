package com.buuz135.simpleclaims.guild;

import ru.hytaleworld.guild.util.Guild;
import ru.hytaleworld.guild.util.GuildData;
import ru.hytaleworld.guild.util.GuildManager;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Мост между SimpleClaims и системой гильдий
 *
 * Предоставляет методы для проверки прав на клайм от имени гильдии
 */
public class GuildClaimBridge {

    /**
     * Получить гильдию игрока
     */
    @Nullable
    public static Guild getPlayerGuild(UUID playerId) {
        GuildManager guildManager = GuildManager.getInstance();
        if (guildManager == null) {
            return null;
        }
        return guildManager.getPlayerGuild(playerId);
    }

    /**
     * Получить гильдию по ID
     */
    @Nullable
    public static Guild getGuildById(UUID guildId) {
        GuildManager guildManager = GuildManager.getInstance();
        if (guildManager == null) {
            return null;
        }
        return guildManager.getGuild(guildId);
    }

    /**
     * Проверка: может ли игрок клаймить от имени гильдии?
     *
     * Права на клайм имеют:
     * - Глава гильдии (ROLE_OWNER)
     * - Модераторы (ROLE_MODERATOR)
     */
    public static boolean canManageClaims(Guild guild, UUID playerId) {
        if (guild == null) {
            return false;
        }

        GuildData data = guild.getData();

        // Проверка: владелец гильдии
        if (data.getOwner().equals(playerId)) {
            return true;
        }

        // Проверка: модератор гильдии
        String role = guild.getRole(playerId);
        if (role == null) {
            return false;
        }

        return Guild.ROLE_MODERATOR.equals(role) || Guild.ROLE_OWNER.equals(role);
    }

    /**
     * Проверка: является ли игрок членом гильдии?
     */
    public static boolean isMember(Guild guild, UUID playerId) {
        if (guild == null) {
            return false;
        }

        GuildData data = guild.getData();

        // Проверка владельца
        if (data.getOwner().equals(playerId)) {
            return true;
        }

        // Проверка участников
        return guild.hasMember(playerId);
    }

    /**
     * Получить название гильдии
     */
    public static String getGuildName(Guild guild) {
        if (guild == null) {
            return "Unknown Guild";
        }
        return guild.getData().getName();
    }

    /**
     * Получить тег гильдии
     */
    public static String getGuildTag(Guild guild) {
        if (guild == null) {
            return "";
        }
        return guild.getData().getTag();
    }

    /**
     * Получить цвет чата гильдии (для окраски клаймов на карте)
     */
    public static String getChatColor(Guild guild) {
        if (guild == null) {
            return "#FFFFFF";
        }
        return guild.getData().getChatColor();
    }

    /**
     * Проверка: включён ли friendly fire в гильдии?
     */
    public static boolean isFriendlyFireEnabled(Guild guild) {
        if (guild == null) {
            return false;
        }

        GuildData data = guild.getData();
        Boolean friendlyFire = data.getFriendlyFire();

        return friendlyFire != null && friendlyFire;
    }

    /**
     * Проверка: находится ли гильдия в статусе черновика?
     * Черновики не могут клаймить территорию
     */
    public static boolean isDraft(Guild guild) {
        if (guild == null) {
            return true;
        }

        GuildData data = guild.getData();
        Boolean isDraft = data.getIsDraft();

        return isDraft != null && isDraft;
    }

    /**
     * Получить роль игрока в гильдии (для отображения в GUI)
     */
    @Nullable
    public static String getPlayerRole(Guild guild, UUID playerId) {
        if (guild == null) {
            return null;
        }
        return guild.getRole(playerId);
    }
}