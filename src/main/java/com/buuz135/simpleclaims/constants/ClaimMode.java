package com.buuz135.simpleclaims.constants;

/**
 * Режим клайма для игрока
 *
 * PERSONAL - игрок клаймит на своё имя
 * PARTY - игрок клаймит для своей Party
 * GUILD - игрок клаймит для своей Guild
 */
public enum ClaimMode {
    PERSONAL("Personal", "Личные клаймы"),
    PARTY("Party", "Клаймы для группы"),
    GUILD("Guild", "Клаймы для гильдии");

    private final String displayName;
    private final String description;

    ClaimMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Получить следующий режим для toggle
     */
    public ClaimMode next() {
        return switch (this) {
            case PERSONAL -> PARTY;
            case PARTY -> GUILD;
            case GUILD -> PERSONAL;
        };
    }
}