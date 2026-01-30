package com.buuz135.simpleclaims.services;

import com.buuz135.simpleclaims.constants.ClaimMode;
import lombok.Getter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер режимов клайма для игроков
 *
 * Хранит текущий режим (PERSONAL/GUILD) для каждого игрока
 */
    public class ClaimModeManager {

    @Getter
    private static ClaimModeManager instance;

    // Карта: UUID игрока -> текущий режим
    private final ConcurrentHashMap<UUID, ClaimMode> playerModes;

    public ClaimModeManager() {
        this.playerModes = new ConcurrentHashMap<>();
        instance = this;
    }

    /**
     * Получить текущий режим игрока
     * По умолчанию: PERSONAL
     */
    public ClaimMode getMode(UUID playerId) {
        return playerModes.getOrDefault(playerId, ClaimMode.PERSONAL);
    }

    /**
     * Установить режим игрока
     */
    public void setMode(UUID playerId, ClaimMode mode) {
        playerModes.put(playerId, mode);
    }

    /**
     * Переключить режим игрока
     * PERSONAL <-> GUILD
     */
    public ClaimMode toggleMode(UUID playerId) {
        ClaimMode current = getMode(playerId);
        ClaimMode newMode = (current == ClaimMode.PERSONAL) ? ClaimMode.GUILD : ClaimMode.PERSONAL;
        setMode(playerId, newMode);
        return newMode;
    }

    /**
     * Сбросить режим игрока на PERSONAL
     */
    public void resetMode(UUID playerId) {
        playerModes.remove(playerId);
    }

    /**
     * Проверить, в режиме гильдии ли игрок
     */
    public boolean isGuildMode(UUID playerId) {
        return getMode(playerId) == ClaimMode.GUILD;
    }

    /**
     * Очистить все режимы (при перезагрузке)
     */
    public void clearAll() {
        playerModes.clear();
    }
}