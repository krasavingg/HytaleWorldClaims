package com.buuz135.simpleclaims.constants;

/**
 * Тип владельца клайма
 *
 * PERSONAL - личный клайм игрока
 * PARTY - клайм принадлежит Party
 * GUILD - клайм принадлежит Guild
 */
public enum ClaimOwnerType {
    PERSONAL("Personal"),
    PARTY("Party"),
    GUILD("Guild");

    private final String displayName;

    ClaimOwnerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Получить тип по строковому значению (для десериализации)
     */
    public static ClaimOwnerType fromString(String str) {
        if (str == null) return PARTY; // дефолт для обратной совместимости

        try {
            return ClaimOwnerType.valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PARTY; // дефолт для старых записей
        }
    }
}