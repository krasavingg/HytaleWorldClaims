package com.buuz135.simpleclaims.gui;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.buuz135.simpleclaims.constants.ClaimMode;
import com.buuz135.simpleclaims.constants.ClaimOwnerType;
import com.buuz135.simpleclaims.guild.GuildClaimBridge;
import com.buuz135.simpleclaims.services.ClaimModeManager;
import com.buuz135.simpleclaims.util.MessageHelper;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import ru.hytaleworld.guild.util.Guild;

import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class ChunkInfoGui extends InteractiveCustomUIPage<ChunkInfoGui.ChunkInfoData> {

    private final int chunkX;
    private final int chunkZ;
    private final String dimension;
    private boolean isOp;

    private CompletableFuture<ChunkInfoMapAsset> mapAsset = null;

    public ChunkInfoGui(@NonNullDecl PlayerRef playerRef, String dimension, int chunkX, int chunkZ, boolean isOp) {
        super(playerRef, CustomPageLifetime.CanDismiss, ChunkInfoData.CODEC);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.isOp = isOp;
    }

    @Override
    public void handleDataEvent(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl Store<EntityStore> store, @NonNullDecl ChunkInfoData data) {
        super.handleDataEvent(ref, store, data);
        if (data.action != null){
            var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            var playerInstance = store.getComponent(ref, Player.getComponentType());

            var actions = data.action.split(":");
            var button = actions[0];
            var x = Integer.parseInt(actions[1]);
            var z = Integer.parseInt(actions[2]);

            if (button.equals("LeftClicking")) {
                handleClaim(playerRef, playerInstance, x, z);
            }

            if (button.equals("RightClicking")) {
                handleUnclaim(playerRef, playerInstance, x, z);
            }

            UICommandBuilder commandBuilder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            this.build(ref, commandBuilder, eventBuilder, store);
            this.sendUpdate(commandBuilder, eventBuilder, true);
            return;
        }
        this.sendUpdate();
    }

    /**
     * Обработка клайма чанка
     */
    private void handleClaim(PlayerRef playerRef, Player playerInstance, int x, int z) {
        ClaimManager claimManager = ClaimManager.getInstance();
        ClaimModeManager modeManager = ClaimModeManager.getInstance();

        var chunk = claimManager.getChunk(dimension, x, z);

        // Проверка: чанк уже заклаймлен
        if (chunk != null) {
            // Проверяем валидность владельца
            boolean hasValidOwner = false;

            switch (chunk.getOwnerType()) {
                case PARTY -> hasValidOwner = claimManager.getPartyById(chunk.getOwnerId()) != null;
                case GUILD -> hasValidOwner = claimManager.getGuildById(chunk.getOwnerId()) != null;
                case PERSONAL -> hasValidOwner = true; // личные клаймы всегда валидны
            }

            if (hasValidOwner) {
                playerInstance.sendMessage(Message.raw("Территория занята!").color(Color.RED));
                return;
            }
        }

        // Админ режим
        if (isOp) {
            handleAdminClaim(playerRef, playerInstance, x, z);
            return;
        }

        // Обычный режим - определяем по текущему режиму клайма
        ClaimMode mode = modeManager.getMode(playerRef.getUuid());

        switch (mode) {
            case PERSONAL -> {
                claimManager.claimChunkBy(
                        dimension,
                        x,
                        z,
                        playerRef.getUuid(),        // ownerId
                        ClaimOwnerType.PERSONAL,
                        playerRef.getUuid(),        // claimerUUID
                        playerInstance.getDisplayName()
                );
                claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);
                playerInstance.sendMessage(Message.raw("Территория зарезервирована").color(Color.GREEN));
            }

            case PARTY -> {
                var playerParty = claimManager.getPartyFromPlayer(playerRef.getUuid());
                if (playerParty == null) {
                    playerInstance.sendMessage(Message.raw("Вы не состоите в Party!"));
                    return;
                }

                if (!claimManager.hasEnoughClaimsLeft(playerParty)) {
                    playerInstance.sendMessage(Message.raw("У вашей Party закончились клаймы!"));
                    return;
                }

                claimManager.claimChunkBy(dimension,  x, z, playerParty.getId(), ClaimOwnerType.PARTY, playerRef.getUuid(), playerRef.getUsername());
                claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);

                playerInstance.sendMessage(Message.raw(" Чанк заклаймлен для Party " + playerParty.getName()));
            }

            case GUILD -> {
                Guild guild = claimManager.getGuildFromPlayer(playerRef.getUuid());
                if (guild == null) {
                    playerInstance.sendMessage(Message.raw("Вы не состоите в гильдии!"));
                    return;
                }

                if (!claimManager.canGuildClaim(guild, playerRef.getUuid())) {
                    playerInstance.sendMessage(Message.raw("У вас нет прав на клайм для гильдии!"));
                    return;
                }

                if (!claimManager.hasGuildEnoughClaimsLeft(guild)) {
                    playerInstance.sendMessage(Message.raw("У вашей гильдии закончились клаймы!"));
                    return;
                }

                claimManager.claimChunkByGuild(
                        dimension,
                        x,
                        z,
                        guild,
                        playerRef.getUuid(),
                        playerInstance.getDisplayName()
                );
                claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);

                playerInstance.sendMessage(Message.raw(" Чанк заклаймлен для гильдии " +
                        GuildClaimBridge.getGuildName(guild)));
            }
        }
    }

    /**
     * Обработка админского клайма
     */
    private void handleAdminClaim(PlayerRef playerRef, Player playerInstance, int x, int z) {
        ClaimManager claimManager = ClaimManager.getInstance();

        var selectedPartyID = claimManager.getAdminUsageParty().get(playerRef.getUuid());
        if (selectedPartyID == null) {
            playerInstance.sendMessage(CommandMessages.ADMIN_PARTY_NOT_SELECTED);
            return;
        }

        var chunk = claimManager.getChunk(dimension, x, z);
        var selectedParty = claimManager.getPartyById(selectedPartyID);

        if ((chunk == null || claimManager.getPartyById(chunk.getOwnerId()) == null) &&
                selectedParty != null &&
                claimManager.hasEnoughClaimsLeft(selectedParty)) {

            claimManager.claimChunkBy(dimension, x, z, playerRef.getUuid(), ClaimOwnerType.PERSONAL, playerRef.getUuid(),playerRef.getUsername());
            claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);
        }
    }

    /**
     * Обработка снятия клайма
     */
    private void handleUnclaim(PlayerRef playerRef, Player playerInstance, int x, int z) {
        ClaimManager claimManager = ClaimManager.getInstance();
        ClaimModeManager modeManager = ClaimModeManager.getInstance();

        var chunk = claimManager.getChunk(dimension, x, z);
        if (chunk == null) {
            playerInstance.sendMessage(Message.raw("Этот чанк не заклаймлен!"));
            return;
        }

        // Админ режим - может снимать любые клаймы
        if (isOp) {
            if (claimManager.unclaimByAdmin(dimension, x, z)) {
                claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);
                playerInstance.sendMessage(Message.raw("Клайм снят (админ)").color(Color.ORANGE));
            } else {
                playerInstance.sendMessage(Message.raw("Ошибка при снятии клайма!").color(Color.RED));
            }
            return;
        }

        // Обычный режим - проверяем права
        ClaimMode mode = modeManager.getMode(playerRef.getUuid());
        boolean canUnclaim = false;

        switch (chunk.getOwnerType()) {
            case PERSONAL -> {
                canUnclaim = chunk.getOwnerId().equals(playerRef.getUuid());
            }

            case PARTY -> {
                var party = claimManager.getPartyFromPlayer(playerRef.getUuid());
                canUnclaim = party != null && chunk.getOwnerId().equals(party.getId());
            }

            case GUILD -> {
                Guild guild = claimManager.getGuildFromPlayer(playerRef.getUuid());
                canUnclaim = guild != null &&
                        chunk.getOwnerId().equals(guild.getData().getId()) &&
                        claimManager.canGuildClaim(guild, playerRef.getUuid());
            }
        }

        if (canUnclaim) {
            claimManager.unclaim(dimension, x, z);
            claimManager.queueMapUpdate(playerInstance.getWorld(), x, z);
            playerInstance.sendMessage(Message.raw("Клайм снят").color(Color.GREEN));
        } else {
            playerInstance.sendMessage(Message.raw("Вы не можете снять этот клайм!").color(Color.RED));
        }
    }

    @Override
    public void build(@NonNullDecl Ref<EntityStore> ref, @NonNullDecl UICommandBuilder uiCommandBuilder, @NonNullDecl UIEventBuilder uiEventBuilder, @NonNullDecl Store<EntityStore> store) {
        uiCommandBuilder.append("Pages/Buuz135_SimpleClaims_ChunkVisualizer.ui");

        if (isOp) {
            uiCommandBuilder.set("#TitleText.Text", "Nearby Claimed Chunks - Admin Mode");
        }

        var player = store.getComponent(ref, PlayerRef.getComponentType());
        var claimManager = ClaimManager.getInstance();
        var modeManager = ClaimModeManager.getInstance();

        // Определяем, для кого показывать счётчик клаймов
        String claimCountText = "";
        String maxClaimsText = "";

        if (isOp) {
            var selectedPartyID = claimManager.getAdminUsageParty().get(playerRef.getUuid());
            if (selectedPartyID != null) {
                var party = claimManager.getPartyById(selectedPartyID);
                if (party != null) {
                    claimCountText = claimManager.getAmountOfClaims(party) + "";
                    maxClaimsText = party.getMaxClaimAmount() + "";
                }
            }
        } else {
            ClaimMode mode = modeManager.getMode(player.getUuid());

            switch (mode) {
                case PARTY -> {
                    var party = claimManager.getPartyFromPlayer(player.getUuid());
                    if (party != null) {
                        claimCountText = claimManager.getAmountOfClaims(party) + "";
                        maxClaimsText = party.getMaxClaimAmount() + "";
                    }
                }

                case GUILD -> {
                    Guild guild = claimManager.getGuildFromPlayer(player.getUuid());
                    if (guild != null) {
                        claimCountText = claimManager.getGuildClaimCount(guild.getData().getId()) + "";
                        maxClaimsText = guild.getData().getChunksAvailable() + "";
                    }
                }

                case PERSONAL -> {
                    // Считаем личные чанки игрока
                    int personalClaimCount = 0;
                    for (var dimensionChunks : claimManager.getChunks().values()) {
                        for (ChunkInfo chunk : dimensionChunks.values()) {
                            if (chunk.getOwnerType() == ClaimOwnerType.PERSONAL &&
                                    chunk.getOwnerId().equals(player.getUuid())) {
                                personalClaimCount++;
                            }
                        }
                    }
                    claimCountText = personalClaimCount + "";
                    // Лимит для личных клаймов из конфига
                    maxClaimsText = Main.CONFIG.get().getDefaultPartyClaimsAmount() + "";
                }
            }
        }

        uiCommandBuilder.set("#ClaimedChunksInfo #ClaimedChunksCount.Text", claimCountText);
        uiCommandBuilder.set("#ClaimedChunksInfo #MaxChunksCount.Text", maxClaimsText);

        // Генерация карты (если включено в конфиге)
        if (this.mapAsset == null && Main.CONFIG.get().isRenderMapInClaimUI()) {
            ChunkInfoMapAsset.sendToPlayer(this.playerRef.getPacketHandler(), ChunkInfoMapAsset.empty());

            this.mapAsset = ChunkInfoMapAsset.generate(this.playerRef, chunkX - 8, chunkZ - 8, chunkX + 8, chunkZ + 8);

            if (this.mapAsset != null) {
                this.mapAsset.thenAccept(asset -> {
                    if (asset == null) return;
                    ChunkInfoMapAsset.sendToPlayer(this.playerRef.getPacketHandler(), asset);
                    this.sendUpdate();
                });
            }
        }


        // Отрисовка сетки чанков
        for (int z = 0; z <= 8*2; z++) {
            uiCommandBuilder.appendInline("#ChunkCards", "Group { LayoutMode: Left; Anchor: (Bottom: 0); }");
            for (int x = 0; x <= 8*2; x++) {
                uiCommandBuilder.append("#ChunkCards[" + z  + "]", "Pages/Buuz135_SimpleClaims_ChunkEntry.ui");
                var chunk = claimManager.getChunk(dimension, chunkX + x - 8, chunkZ + z - 8);

                // Маркер текущей позиции
                if ((z - 8) == 0 && (x - 8) == 0) {
                    uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].Text", "+");
                }

                if (chunk != null) {
                    buildChunkDisplay(uiCommandBuilder, uiEventBuilder, chunk, z, x, player);
                } else {
                    buildWildernessDisplay(uiCommandBuilder, uiEventBuilder, z, x, player);
                }
            }
        }
    }

    /**
     * Отрисовка заклаймленного чанка
     */
    private void buildChunkDisplay(
            UICommandBuilder uiCommandBuilder,
            UIEventBuilder uiEventBuilder,
            ChunkInfo chunk,
            int z,
            int x,
            PlayerRef player
    ) {
        ClaimManager claimManager = ClaimManager.getInstance();
        String ownerName = "Unknown";
        String ownerDescription = "";
        Color color = Color.GRAY;
        boolean canUnclaim = false;

        switch (chunk.getOwnerType()) {
            case PARTY -> {
                var party = claimManager.getPartyById(chunk.getOwnerId());
                if (party != null) {
                    ownerName = party.getName();
                    ownerDescription = party.getDescription();
                    color = new Color(party.getColor());

                    if (isOp) {
                        canUnclaim = true; // ИЗМЕНЕНО: Админ может снять любой клайм
                    } else {
                        var playerParty = claimManager.getPartyFromPlayer(player.getUuid());
                        canUnclaim = playerParty != null && playerParty.getId().equals(party.getId());
                    }
                } else {
                    ownerName = "Deleted Party";
                    canUnclaim = isOp; // ДОБАВЛЕНО: Админ может снять клайм удалённой party
                }
            }

            case GUILD -> {
                Guild guild = claimManager.getGuildById(chunk.getOwnerId());
                if (guild != null) {
                    ownerName = GuildClaimBridge.getGuildName(guild) + " [" + GuildClaimBridge.getGuildTag(guild) + "]";
                    ownerDescription = "Гильдия";

                    try {
                        String colorStr = GuildClaimBridge.getChatColor(guild);
                        if (colorStr != null && colorStr.startsWith("#")) {
                            color = Color.decode(colorStr);
                        }
                    } catch (Exception e) {
                        color = Color.ORANGE;
                    }

                    if (isOp) {
                        canUnclaim = true; // ИЗМЕНЕНО: Админ может снять любой клайм
                    } else {
                        canUnclaim = claimManager.canGuildClaim(guild, player.getUuid());
                    }
                } else {
                    ownerName = "Deleted Guild";
                    canUnclaim = isOp; // ДОБАВЛЕНО: Админ может снять клайм удалённой гильдии
                }
            }

            case PERSONAL -> {
                ownerName = claimManager.getPlayerNameTracker().getPlayerName(chunk.getOwnerId());
                ownerDescription = "Личный клайм";
                color = Color.CYAN;

                if (isOp) {
                    canUnclaim = true; // ИЗМЕНЕНО: Админ может снять любой личный клайм
                } else {
                    canUnclaim = chunk.getOwnerId().equals(player.getUuid());
                }
            }
        }

        // Применяем цвет с прозрачностью
        color = new Color(color.getRed(), color.getGreen(), color.getBlue(), 128);
        uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].Background.Color", ColorParseUtil.colorToHexAlpha(color));
        uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].OutlineColor", ColorParseUtil.colorToHexAlpha(color));
        uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].OutlineSize", 1);

        // Tooltip
        var tooltip = MessageHelper.multiLine()
                .append(Message.raw("Owner: ").bold(true).color(Color.YELLOW))
                .append(Message.raw(ownerName)).nl()
                .append(Message.raw("Type: ").bold(true).color(Color.YELLOW))
                .append(Message.raw(chunk.getOwnerType().getDisplayName()));

        if (!ownerDescription.isEmpty()) {
            tooltip = tooltip.nl()
                    .append(Message.raw("Description: ").bold(true).color(Color.YELLOW))
                    .append(Message.raw(ownerDescription));
        }

        if (canUnclaim) {
            String unclaimText = isOp ? "*Right Click to Unclaim (ADMIN)*" : "*Right Click to Unclaim*";
            Color unclaimColor = isOp ? Color.ORANGE : Color.RED.darker().darker();
            tooltip = tooltip.nl().nl().append(Message.raw(unclaimText).bold(true).color(unclaimColor));
        }

        uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].TooltipTextSpans", tooltip.build());

        if (canUnclaim) {
            uiEventBuilder.addEventBinding(CustomUIEventBindingType.RightClicking, "#ChunkCards[" + z + "][" + x + "]",
                    EventData.of("Action", "RightClicking:" + (chunkX + x - 8) + ":" + (chunkZ + z - 8)));
        }
    }

    /**
     * Отрисовка незаклаймленного чанка (wilderness)
     */
    private void buildWildernessDisplay(
            UICommandBuilder uiCommandBuilder,
            UIEventBuilder uiEventBuilder,
            int z,
            int x,
            PlayerRef player
    ) {
        ClaimManager claimManager = ClaimManager.getInstance();
        ClaimModeManager modeManager = ClaimModeManager.getInstance();

        var tooltip = MessageHelper.multiLine().append(Message.raw("Wilderness").bold(true).color(Color.GREEN.darker()));

        boolean canClaim = false;

        if (!isOp) {
            ClaimMode mode = modeManager.getMode(player.getUuid());

            switch (mode) {
                case PERSONAL -> canClaim = true;
                case PARTY -> {
                    var party = claimManager.getPartyFromPlayer(player.getUuid());
                    canClaim = party != null && claimManager.hasEnoughClaimsLeft(party);
                }
                case GUILD -> {
                    Guild guild = claimManager.getGuildFromPlayer(player.getUuid());
                    canClaim = guild != null &&
                            claimManager.canGuildClaim(guild, player.getUuid()) &&
                            claimManager.hasGuildEnoughClaimsLeft(guild);
                }
            }
        } else {
            canClaim = true;
        }

        if (canClaim) {
            tooltip = tooltip.nl().nl().append(Message.raw("*Left Click to claim*").bold(true).color(Color.GRAY));
            uiEventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkCards[" + z + "][" + x + "]",
                    EventData.of("Action", "LeftClicking:" + (chunkX + x - 8) + ":" + (chunkZ + z - 8)));
        } else {
            tooltip = tooltip.nl().nl().append(Message.raw("*Can't claim*").bold(true).color(Color.GRAY));
        }

        uiCommandBuilder.set("#ChunkCards[" + z + "][" + x + "].TooltipTextSpans", tooltip.build());
    }

    public static class ChunkInfoData {
        static final String KEY_ACTION = "Action";

        public static final BuilderCodec<ChunkInfoData> CODEC = BuilderCodec.<ChunkInfoData>builder(ChunkInfoData.class, ChunkInfoData::new)
                .addField(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (searchGuiData, s) -> searchGuiData.action = s, searchGuiData -> searchGuiData.action)
                .build();

        private String action;
    }
}