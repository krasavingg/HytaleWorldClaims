package com.buuz135.simpleclaims.commands.subcommand.chunk.guild;

import com.buuz135.simpleclaims.commands.CommandMessages;
import com.buuz135.simpleclaims.constants.ClaimMode;
import com.buuz135.simpleclaims.guild.GuildClaimBridge;
import com.buuz135.simpleclaims.services.ClaimModeManager;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import ru.hytaleworld.guild.util.Guild;

import java.awt.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Команда переключения режима клайма
 *
 * /sc mode              - показать текущий режим
 * /sc mode personal     - переключить на личные клаймы
 * /sc mode party        - переключить на клаймы Party
 * /sc mode guild        - переключить на клаймы гильдии
 * /sc mode toggle       - переключить режим по кругу
 */
public class ClaimModeCommand extends AbstractAsyncCommand {

    // Вариант БЕЗ аргумента - показать текущий режим
    private static class ShowModeVariant extends AbstractAsyncCommand {
        public ShowModeVariant() {
            super("Show current claim mode");
        }

        @NonNullDecl
        @Override
        protected CompletableFuture<Void> executeAsync(CommandContext commandContext) {
            CommandSender sender = commandContext.sender();

            if (!(sender instanceof Player player)) {
                return CompletableFuture.completedFuture(null);
            }

            ClaimModeManager modeManager = ClaimModeManager.getInstance();
            ClaimManager claimManager = ClaimManager.getInstance();

            return CompletableFuture.runAsync(() -> {
                showCurrentMode(player, modeManager, claimManager);
            });
        }
    }

    // Основная команда С аргументом
    private final com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg<String> modeArg =
            this.withRequiredArg(
                    "mode",
                    "Режим клайма (personal/party/guild/toggle)",
                    ArgTypes.STRING
            );

    public ClaimModeCommand() {
        super("mode", "Switch between personal, party and guild claim mode");
        this.requirePermission(CommandMessages.BASE_PERM + "mode");

        // Регистрируем вариант без аргументов
        this.addUsageVariant(new ShowModeVariant());
    }

    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext commandContext) {
        CommandSender sender = commandContext.sender();

        if (!(sender instanceof Player player)) {
            return CompletableFuture.completedFuture(null);
        }

        UUID playerId = player.getUuid();
        ClaimModeManager modeManager = ClaimModeManager.getInstance();
        ClaimManager claimManager = ClaimManager.getInstance();

        // Получаем аргумент
        String modeStr = commandContext.get(modeArg);

        return CompletableFuture.runAsync(() -> {
            switch (modeStr.toLowerCase()) {
                case "personal" -> {
                    modeManager.setMode(playerId, ClaimMode.PERSONAL);
                    var packetHandler = player.getPlayerRef().getPacketHandler();
                    Message primaryMessage = Message.raw("Режим клайма изменён").color("#228B22");
                    Message secondaryMessage = Message.raw("Текущий режим: " + ClaimMode.PERSONAL.getDescription());
                    ItemWithAllMetadata icon = (new ItemStack("Survival_Trap_Spike_Wood", 1)).toPacket();
                    NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);
                }

                case "party" -> {
                    // Проверяем, что игрок в Party
                    PartyInfo party = claimManager.getPartyFromPlayer(playerId);
                    if (party == null) {
                        player.sendMessage(Message.raw("Вы не состоите в Party!"));
                        player.sendMessage(Message.raw("Создайте Party командой /party create"));
                        return;
                    }

                    // Проверяем права (только владелец может клаймить для Party)
                    if (!party.isOwner(playerId)) {
                        player.sendMessage(Message.raw("Только владелец Party может клаймить территорию!"));
                        player.sendMessage(Message.raw("Владелец: " + claimManager.getPlayerNameTracker().getPlayerName(party.getOwner())));
                        return;
                    }

                    modeManager.setMode(playerId, ClaimMode.PARTY);
                    var packetHandler = player.getPlayerRef().getPacketHandler();
                    Message primaryMessage = Message.raw("Режим клайма изменён").color("#228B22");
                    Message secondaryMessage = Message.raw("Текущий режим: " + ClaimMode.PARTY.getDescription());
                    ItemWithAllMetadata icon = (new ItemStack("Survival_Trap_Spike_Wood", 1)).toPacket();
                    NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);
                }

                case "guild" -> {
                    // Проверяем, что игрок в гильдии
                    Guild guild = GuildClaimBridge.getPlayerGuild(playerId);
                    if (guild == null) {
                        player.sendMessage(Message.raw("Вы не состоите в гильдии!"));
                        player.sendMessage(Message.raw("Вступите в гильдию или создайте свою"));
                        return;
                    }

                    // Проверяем: гильдия не в черновике
                    if (GuildClaimBridge.isDraft(guild)) {
                        player.sendMessage(Message.raw("Ваша гильдия ещё не одобрена!"));
                        player.sendMessage(Message.raw("Дождитесь одобрения администрацией"));
                        return;
                    }

                    // Проверяем права (владелец или модератор)
                    if (!claimManager.canGuildClaim(guild, playerId)) {
                        String role = GuildClaimBridge.getPlayerRole(guild, playerId);
                        player.sendMessage(Message.raw("Только глава и модераторы могут клаймить для гильдии!"));
                        player.sendMessage(Message.raw("Ваш ранг: " + (role != null ? role : "Участник")));
                        player.sendMessage(Message.raw("Используйте /sc mode personal для личного режима"));
                        return;
                    }

                    modeManager.setMode(playerId, ClaimMode.GUILD);
                    var packetHandler = player.getPlayerRef().getPacketHandler();
                    Message primaryMessage = Message.raw("Режим клайма изменён").color("#228B22");
                    Message secondaryMessage = Message.raw("Текущий режим: " + ClaimMode.GUILD.getDescription());
                    ItemWithAllMetadata icon = (new ItemStack("Survival_Trap_Spike_Wood", 1)).toPacket();
                    NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);
                }

                case "toggle" -> {
                    ClaimMode currentMode = modeManager.getMode(playerId);
                    ClaimMode newMode = currentMode.next();

                    // Пытаемся переключиться на новый режим
                    boolean switched = trySetMode(player, playerId, newMode, modeManager, claimManager);

                    if (!switched) {
                        // Если не удалось переключиться - пробуем следующий режим
                        newMode = newMode.next();
                        switched = trySetMode(player, playerId, newMode, modeManager, claimManager);

                        if (!switched) {
                            // Если и это не удалось - возвращаемся к PERSONAL
                            modeManager.setMode(playerId, ClaimMode.PERSONAL);
                            player.sendMessage(Message.raw(" Переключено на личный режим"));
                            player.sendMessage(Message.raw("Вы не состоите в Party или гильдии"));
                        }
                    }

                    showCurrentMode(player, modeManager, claimManager);
                }

                default -> {
                    player.sendMessage(Message.raw("Неизвестный режим: " + modeStr));
                    player.sendMessage(Message.raw(""));
                    player.sendMessage(Message.raw("Доступные режимы:"));
                    player.sendMessage(Message.raw("  /sc mode personal - личные клаймы"));
                    player.sendMessage(Message.raw("  /sc mode party - клаймы для Party"));
                    player.sendMessage(Message.raw("  /sc mode guild - клаймы для гильдии"));
                    player.sendMessage(Message.raw("  /sc mode toggle - переключить режим"));
                    player.sendMessage(Message.raw("  /sc mode - показать текущий режим"));
                }
            }
        });
    }

    /**
     * Попытка переключения на указанный режим
     * @return true если успешно, false если невозможно
     */
    private static boolean trySetMode(
            Player player,
            UUID playerId,
            ClaimMode mode,
            ClaimModeManager modeManager,
            ClaimManager claimManager
    ) {
        switch (mode) {
            case PERSONAL -> {
                modeManager.setMode(playerId, ClaimMode.PERSONAL);
                return true;
            }

            case PARTY -> {
                PartyInfo party = claimManager.getPartyFromPlayer(playerId);
                if (party != null && party.isOwner(playerId)) {
                    modeManager.setMode(playerId, ClaimMode.PARTY);
                    return true;
                }
                return false;
            }

            case GUILD -> {
                Guild guild = GuildClaimBridge.getPlayerGuild(playerId);
                if (guild != null &&
                        !GuildClaimBridge.isDraft(guild) &&
                        claimManager.canGuildClaim(guild, playerId)) {
                    modeManager.setMode(playerId, ClaimMode.GUILD);
                    return true;
                }
                return false;
            }

            default -> {
                return false;
            }
        }
    }

    /**
     * Показать текущий режим игрока
     */
    private static void showCurrentMode(Player player, ClaimModeManager modeManager, ClaimManager claimManager) {
        UUID playerId = player.getUuid();
        ClaimMode currentMode = modeManager.getMode(playerId);

        player.sendMessage(Message.raw(""));
        player.sendMessage(Message.raw("═══ Режим клайма ═══"));
        player.sendMessage(Message.raw(""));
        player.sendMessage(Message.raw("Текущий: " + currentMode.getDisplayName()));
        player.sendMessage(Message.raw(""));

        switch (currentMode) {
            case PERSONAL -> {
                player.sendMessage(Message.raw("Команда /sc claim клаймит лично для вас"));
                player.sendMessage(Message.raw(""));

                // Показываем доступные альтернативы
                PartyInfo party = claimManager.getPartyFromPlayer(playerId);
                if (party != null && party.isOwner(playerId)) {
                    player.sendMessage(Message.raw("Доступно: /sc mode party"));
                }

                Guild guild = GuildClaimBridge.getPlayerGuild(playerId);
                if (guild != null &&
                        !GuildClaimBridge.isDraft(guild) &&
                        claimManager.canGuildClaim(guild, playerId)) {
                    player.sendMessage(Message.raw("Доступно: /sc mode guild"));
                }
            }

            case PARTY -> {
                PartyInfo party = claimManager.getPartyFromPlayer(playerId);
                if (party != null) {
                    player.sendMessage(Message.raw("Команда /sc claim клаймит для Party"));
                    player.sendMessage(Message.raw("Party: " + party.getName()));
                    player.sendMessage(Message.raw(""));
                    player.sendMessage(Message.raw("Чтобы клаймить лично: /sc mode personal"));
                }
            }

            case GUILD -> {
                Guild guild = GuildClaimBridge.getPlayerGuild(playerId);
                if (guild != null) {
                    player.sendMessage(Message.raw("Команда /sc claim клаймит для гильдии"));
                    player.sendMessage(Message.raw("Гильдия: " + GuildClaimBridge.getGuildName(guild) +
                            " [" + GuildClaimBridge.getGuildTag(guild) + "]"));
                    player.sendMessage(Message.raw(""));
                    player.sendMessage(Message.raw("Чтобы клаймить лично: /sc mode personal"));
                }
            }
        }

        player.sendMessage(Message.raw(""));
    }
}