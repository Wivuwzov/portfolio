package dev.wivuwzov.telegramauth;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class AuthService {
    private static final Logger LOG = LogManager.getLogger("TelegramAuth");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final MinecraftServer server;
    private final Storage storage;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> actionbar = new HashMap<>();
    private final Map<Long, AttemptCounter> linkAttempts = new HashMap<>();
    private ModConfig config;
    private TelegramGateway gateway;
    private long tick = 0;

    private enum Stage { LINK, APPROVAL, LOGIN }

    private static final class Session {
        String nickname;
        String token;
        String code;
        UUID uuid;
        Stage stage;
        long expires;
        double x;
        double y;
        double z;
        String dimension;
        Storage.Visit visit;
    }

    private static final class AttemptCounter {
        long start;
        int count;
    }

    AuthService(MinecraftServer server) {
        this.server = server;
        this.config = ModConfig.load();
        this.storage = Storage.load();
        startGateway();
    }

    void stop() {
        for (Session session : new ArrayList<>(sessions.values())) {
            ServerPlayer p = online(session.nickname);
            if (p != null) restoreMode(p, false);
        }
        sessions.clear();
        storage.save();
        if (gateway != null) gateway.stop();
    }

    private void startGateway() {
        if (gateway != null) gateway.stop();
        gateway = new TelegramGateway(config.botToken == null ? "" : config.botToken, this, server);
        gateway.start(storage.lastUpdateId);
    }

    String reload() {
        config = ModConfig.load();
        startGateway();
        return "Конфигурация TelegramAuth перезагружена.";
    }

    void join(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        String key = Storage.key(name);
        Session old = sessions.remove(key);
        if (old != null) storage.mark(old.visit, "reconnected", storage.find(name));
        Storage.Account account = storage.getOrCreate(name);
        account.player = name;
        account.displayName = clean(player.getDisplayName().getString());
        account.lastSeenAt = System.currentTimeMillis();
        account.lastIp = clean(player.getIpAddress());
        if (account.originalGameMode == null || account.originalGameMode.isBlank()) {
            account.originalGameMode = player.gameMode.getGameModeForPlayer().getName();
        }
        Storage.Visit visit = storage.newVisit(name, account.displayName, account.lastIp, account);
        if (account.banned) {
            storage.mark(visit, "banned", account);
            player.connection.disconnect(Component.literal(config.messageBanned));
            return;
        }
        Session session = new Session();
        session.nickname = name;
        session.uuid = player.getUUID();
        session.token = randomToken();
        session.code = randomCode();
        session.x = player.getX();
        session.y = player.getY();
        session.z = player.getZ();
        session.dimension = player.level().dimension().location().toString();
        session.visit = visit;
        session.expires = System.currentTimeMillis() + 1000L * (account.telegramId > 0 && account.approved
            ? config.loginTimeoutSeconds : config.registrationTimeoutSeconds);
        sessions.put(key, session);
        player.setGameMode(GameType.SPECTATOR);
        player.setDeltaMovement(Vec3.ZERO);
        if (config.botToken == null || config.botToken.isBlank()) {
            player.sendSystemMessage(Component.literal(config.messageBotNotReady).withStyle(ChatFormatting.RED));
        }
        if (account.telegramId == 0) {
            session.stage = Stage.LINK;
            player.sendSystemMessage(Component.literal(config.format(config.messageLink, values(account, session.code))));
        } else if (!account.approved) {
            session.stage = Stage.APPROVAL;
            player.sendSystemMessage(Component.literal(config.messageWaitApproval));
            requestAdminApproval(session, account);
        } else {
            session.stage = Stage.LOGIN;
            player.sendSystemMessage(Component.literal(config.messageWaitLogin));
            gateway.send(Long.toString(account.telegramId), config.format(config.messageLoginTelegram, values(account, "")),
                TelegramGateway.buttons(config.buttonAllowLogin, "login_yes:" + session.token,
                    config.buttonDenyLogin, "login_no:" + session.token));
        }
        storage.save();
    }

    void leave(ServerPlayer player) {
        Session session = sessions.remove(Storage.key(player.getGameProfile().getName()));
        if (session != null) {
            storage.mark(session.visit, "disconnected", storage.find(session.nickname));
            restoreMode(player, false);
        }
        actionbar.remove(player.getUUID());
    }

    boolean blocked(ServerPlayer player) {
        Session session = sessions.get(Storage.key(player.getGameProfile().getName()));
        return session != null && session.uuid.equals(player.getUUID());
    }

    void tick() {
        tick++;
        if (tick % 20 != 0) return;
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.expires > now) continue;
            Storage.Account account = storage.find(session.nickname);
            storage.mark(session.visit, "timeout", account);
            sessions.remove(Storage.key(session.nickname));
            ServerPlayer player = online(session.nickname);
            if (player != null) {
                restoreMode(player, false);
                player.connection.disconnect(Component.literal(config.messageTimedOut));
            }
        }
        if (tick % 1200 == 0) {
            storage.prune();
            storage.save();
        }
    }

    void playerTick(ServerPlayer player) {
        Session session = sessions.get(Storage.key(player.getGameProfile().getName()));
        if (session != null && session.uuid.equals(player.getUUID())) {
            if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) player.setGameMode(GameType.SPECTATOR);
            player.setDeltaMovement(Vec3.ZERO);
            if (!player.level().dimension().location().toString().equals(session.dimension)) {
                Storage.Account account = storage.find(session.nickname);
                storage.mark(session.visit, "invalid_dimension", account);
                sessions.remove(Storage.key(session.nickname));
                restoreMode(player, false);
                player.connection.disconnect(Component.literal(config.messageRejected));
                return;
            }
            double distanceSq = player.distanceToSqr(session.x, session.y, session.z);
            if (distanceSq > 0.01) {
                player.connection.teleport(session.x, session.y, session.z, player.getYRot(), player.getXRot());
            }
            player.fallDistance = 0;
        }
        Integer ticks = actionbar.get(player.getUUID());
        if (ticks != null) {
            if (ticks % 10 == 0) player.displayClientMessage(actionbarComponent(), true);
            if (ticks <= 1) actionbar.remove(player.getUUID());
            else actionbar.put(player.getUUID(), ticks - 1);
        }
    }

    private Component actionbarComponent() {
        TextColor color;
        try {
            String hex = config.actionbarColor.trim().replace("#", "");
            color = TextColor.fromRgb(Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (Exception exception) {
            color = TextColor.fromRgb(0x55FF88);
        }
        return Component.literal(config.actionbarText).setStyle(Style.EMPTY.withColor(color));
    }

    private void restoreMode(ServerPlayer player, boolean clearSavedMode) {
        Storage.Account account = storage.find(player.getGameProfile().getName());
        if (account == null) return;
        GameType mode = GameType.SURVIVAL;
        for (GameType candidate : GameType.values()) {
            if (candidate.getName().equals(account.originalGameMode)) mode = candidate;
        }
        player.setGameMode(mode);
        if (clearSavedMode) account.originalGameMode = "";
        storage.save();
    }

    private void authenticated(Session session) {
        Session actual = sessions.get(Storage.key(session.nickname));
        if (actual != session) return;
        Storage.Account account = storage.find(session.nickname);
        if (account == null || account.banned || !account.approved) return;
        ServerPlayer player = online(session.nickname);
        if (player == null || !session.uuid.equals(player.getUUID())) return;
        sessions.remove(Storage.key(session.nickname));
        storage.mark(session.visit, "authorized", account);
        restoreMode(player, true);
        account.lastSeenAt = System.currentTimeMillis();
        account.displayName = clean(player.getDisplayName().getString());
        actionbar.put(player.getUUID(), config.actionbarDurationSeconds * 20);
        player.displayClientMessage(actionbarComponent(), true);
        storage.save();
        gateway.send(config.adminChatId, config.format(config.messageJoinNotification, values(account, "")));
    }

    private void requestAdminApproval(Session session, Storage.Account account) {
        gateway.send(config.adminChatId, config.format(config.messageApprovalTelegram, values(account, "")),
            TelegramGateway.buttons(config.buttonApprove, "reg_yes:" + session.token,
                config.buttonReject, "reg_no:" + session.token));
    }

    void onTelegramUpdate(JsonObject update) {
        try {
            long updateId = number(update, "update_id");
            if (updateId <= storage.lastUpdateId) return;
            storage.lastUpdateId = updateId;
            if (update.has("callback_query")) handleCallback(update.getAsJsonObject("callback_query"));
            if (update.has("message")) handleMessage(update.getAsJsonObject("message"));
            storage.save();
        } catch (Exception exception) {
            LOG.warn("TelegramAuth update processing: {}", exception.getMessage());
        }
    }

    private void handleMessage(JsonObject message) {
        if (!message.has("text") || !message.has("from")) return;
        JsonObject from = message.getAsJsonObject("from");
        JsonObject chat = message.getAsJsonObject("chat");
        String chatId = Long.toString(number(chat, "id"));
        String type = str(chat, "type");
        long fromId = number(from, "id");
        String username = str(from, "username");
        String text = str(message, "text").trim();
        if (!text.startsWith("/")) return;
        String[] parts = text.split("\\s+", 4);
        String command = parts[0].substring(1).split("@")[0].toLowerCase(Locale.ROOT);
        String arg = parts.length >= 2 ? parts[1] : "";
        if (command.equals("start")) {
            gateway.send(chatId, config.messageBotWelcome);
            return;
        }
        if (command.equals("myid")) {
            gateway.send(chatId, "Ваш Telegram ID: " + fromId + "\nChat ID: " + chatId);
            return;
        }
        if (command.equals("link")) {
            if (!"private".equals(type)) {
                gateway.send(chatId, config.messageLinkOnlyPrivate);
                return;
            }
            handleLink(chatId, from, arg);
            return;
        }
        if (command.equals("adminlink")) {
            if (!"private".equals(type)) return;
            if (!config.adminUsernameConfigured(username) || !constantEquals(config.adminBootstrapSecret, arg)) {
                gateway.send(chatId, config.messageAdminLinkRejected);
                return;
            }
            storage.adminBindings.put(Storage.key(username), fromId);
            storage.save();
            gateway.send(chatId, config.messageAdminLinkSucceeded.replace("{id}", Long.toString(fromId)));
            return;
        }
        if (!isAdmin(fromId, username)) {
            if (isAdministrativeCommand(command)) gateway.send(chatId, config.messageNoAccess);
            return;
        }
        if (!"private".equals(type) && !"setchat".equals(command)
                && !chatId.equals(config.adminChatId)) return;
        if (command.equals("setchat")) {
            if ("private".equals(type)) {
                gateway.send(chatId, config.messageSetChatOnlyGroup);
                return;
            }
            config.adminChatId = chatId;
            try { config.save(); gateway.send(chatId, config.messageGroupConfigured.replace("{chat}", chatId)); }
            catch (Exception exception) { gateway.send(chatId, "Ошибка сохранения конфигурации."); }
            return;
        }
        if (command.equals("help")) {
            gateway.send(chatId, config.messageHelp);
            return;
        }
        if (command.equals("online") || command.equals("list")) {
            int page = arg.isBlank() ? 1 : parseBounded(arg, 1, 100000);
            if (page < 0) gateway.send(chatId, config.messageOnlineUsage);
            else gateway.send(chatId, onlinePlayers(page));
            return;
        }
        if (command.equals("history")) {
            int days = parseBounded(arg, 1, 7);
            int page = parseBounded(parts.length >= 3 ? parts[2] : "1", 1, 100000);
            if (days < 0 || page < 0) gateway.send(chatId, "Формат: /history 1..7 [страница]");
            else gateway.send(chatId, history(days, page));
            return;
        }
        if (command.equals("whois")) {
            gateway.send(chatId, whois(arg));
            return;
        }
        if (command.equals("reset") || command.equals("ban") || command.equals("unban") || command.equals("kick")) {
            gateway.send(chatId, adminAction(command, arg));
        }
    }

    private void handleLink(String chat, JsonObject from, String code) {
        long tgId = number(from, "id");
        AttemptCounter rate = linkAttempts.computeIfAbsent(tgId, k -> new AttemptCounter());
        if (System.currentTimeMillis() - rate.start > 60000) {
            rate.start = System.currentTimeMillis();
            rate.count = 0;
        }
        if (++rate.count > 6) {
            gateway.send(chat, config.messageManyLinkAttempts);
            return;
        }
        Session found = null;
        for (Session session : sessions.values()) {
            if (session.stage == Stage.LINK && session.code.equalsIgnoreCase(code)
                    && session.expires > System.currentTimeMillis()) {
                found = session;
                break;
            }
        }
        if (found == null) {
            gateway.send(chat, config.messageWrongLinkCode);
            return;
        }
        if (!config.allowMultipleAccountsPerTelegram) {
            for (Storage.Account candidate : storage.accounts.values()) {
                if (candidate.telegramId == tgId && !Storage.key(candidate.player).equals(Storage.key(found.nickname))) {
                    gateway.send(chat, config.messageTelegramInUse);
                    return;
                }
            }
        }
        Storage.Account account = storage.getOrCreate(found.nickname);
        account.telegramId = tgId;
        account.telegramUsername = str(from, "username");
        account.telegramDisplayName = display(from);
        account.linkedAt = System.currentTimeMillis();
        account.approved = false;
        found.stage = Stage.APPROVAL;
        found.expires = System.currentTimeMillis() + config.registrationTimeoutSeconds * 1000L;
        storage.save();
        gateway.send(chat, config.messageWaitApproval);
        ServerPlayer player = online(found.nickname);
        if (player != null) player.sendSystemMessage(Component.literal(config.messageWaitApproval));
        requestAdminApproval(found, account);
    }

    private void handleCallback(JsonObject callback) {
        String id = str(callback, "id");
        JsonObject from = callback.getAsJsonObject("from");
        if (from == null) return;
        long userId = number(from, "id");
        String action = str(callback, "data");
        int pos = action.indexOf(':');
        if (pos == -1) { gateway.answerCallback(id, "Некорректная кнопка."); return; }
        String operation = action.substring(0, pos);
        String token = action.substring(pos + 1);
        Session target = sessions.values().stream().filter(session -> session.token.equals(token)).findFirst().orElse(null);
        if (target == null || target.expires < System.currentTimeMillis()) {
            gateway.answerCallback(id, config.messageCallbackExpired);
            return;
        }
        Storage.Account account = storage.find(target.nickname);
        if (account == null) return;
        boolean registration = operation.equals("reg_yes") || operation.equals("reg_no");
        boolean login = operation.equals("login_yes") || operation.equals("login_no");
        if (!registration && !login) return;
        if (registration && (target.stage != Stage.APPROVAL || !isAdmin(userId, str(from, "username")))) {
            gateway.answerCallback(id, config.messageCallbackDenied);
            return;
        }
        if (login && (target.stage != Stage.LOGIN || account.telegramId != userId)) {
            gateway.answerCallback(id, config.messageCallbackDenied);
            return;
        }
        if (login) {
            account.telegramUsername = str(from, "username");
            account.telegramDisplayName = display(from);
        }
        if (operation.equals("reg_yes")) {
            account.approved = true;
            gateway.send(Long.toString(account.telegramId), config.format(config.messageRegisterSucceededTelegram, values(account, "")));
            authenticated(target);
        } else if (operation.equals("login_yes")) {
            authenticated(target);
            gateway.send(Long.toString(account.telegramId), config.format(config.messageLoginSucceededTelegram, values(account, "")));
        } else {
            gateway.send(Long.toString(account.telegramId), config.format(
                registration ? config.messageRegisterRejectedTelegram : config.messageRejected, values(account, "")));
            reject(target);
        }
        gateway.answerCallback(id, config.messageCallbackComplete);
        if (callback.has("message")) {
            JsonObject msg = callback.getAsJsonObject("message");
            if (msg.has("chat")) {
                gateway.clearKeyboard(Long.toString(number(msg.getAsJsonObject("chat"), "id")), (int) number(msg, "message_id"));
            }
        }
        storage.save();
    }

    private void reject(Session session) {
        sessions.remove(Storage.key(session.nickname));
        Storage.Account account = storage.find(session.nickname);
        storage.mark(session.visit, "rejected", account);
        ServerPlayer player = online(session.nickname);
        if (player != null) {
            restoreMode(player, false);
            player.connection.disconnect(Component.literal(config.messageRejected));
        }
    }

    boolean isAdmin(long id, String username) {
        if (config.adminIdConfigured(id)) return true;
        if (!config.adminUsernameConfigured(username)) return false;
        return storage.adminBindings.getOrDefault(Storage.key(username), 0L) == id;
    }

    private static boolean isAdministrativeCommand(String command) {
        return switch (command) {
            case "reset", "ban", "unban", "kick", "whois", "history", "online", "list", "setchat", "help" -> true;
            default -> false;
        };
    }

    String adminAction(String operation, String nickname) {
        if (!nickname.matches("[A-Za-z0-9_]{3,16}")) return "Укажите корректный Minecraft-ник.";
        Storage.Account account = storage.getOrCreate(nickname);
        ServerPlayer player = online(nickname);
        switch (operation) {
            case "reset" -> {
                account.telegramId = 0;
                account.telegramUsername = "";
                account.telegramDisplayName = "";
                account.approved = false;
                account.linkedAt = 0;
                terminateSession(nickname, "Аккаунт сброшен администратором.", "reset");
                if (player != null) player.connection.disconnect(Component.literal("Аккаунт сброшен администратором."));
                storage.save();
                return "Привязка аккаунта " + nickname + " сброшена.";
            }
            case "ban" -> {
                account.banned = true;
                terminateSession(nickname, config.messageBanned, "banned");
                if (player != null) player.connection.disconnect(Component.literal(config.messageBanned));
                storage.save();
                return "Аккаунт " + nickname + " заблокирован.";
            }
            case "unban" -> {
                account.banned = false;
                storage.save();
                return "Блокировка " + nickname + " снята.";
            }
            case "kick" -> {
                if (player == null) return nickname + " не в сети.";
                terminateSession(nickname, "Отключён администратором.", "kicked");
                player.connection.disconnect(Component.literal("Отключён администратором."));
                return nickname + " отключён от сервера.";
            }
            default -> { return "Неизвестное действие."; }
        }
    }

    private void terminateSession(String nickname, String reason, String status) {
        Session session = sessions.remove(Storage.key(nickname));
        if (session != null) {
            storage.mark(session.visit, status, storage.find(nickname));
            ServerPlayer player = online(nickname);
            if (player != null) restoreMode(player, false);
        }
        if (online(nickname) != null) actionbar.remove(online(nickname).getUUID());
    }

    String whois(String nickname) {
        if (!nickname.matches("[A-Za-z0-9_]{3,16}")) return "Формат: /whois ник";
        Storage.Account account = storage.find(nickname);
        if (account == null) return "Аккаунт " + nickname + " не найден.";
        return "Minecraft: " + account.player
            + "\nDisplay Name: " + account.displayName
            + "\nTelegram: " + (account.telegramUsername == null || account.telegramUsername.isBlank() ? "нет username" : "@" + account.telegramUsername)
            + "\nTelegram Display Name: " + account.telegramDisplayName
            + "\nTelegram ID: " + (account.telegramId == 0 ? "не привязан" : account.telegramId)
            + "\nРегистрация: " + (account.approved ? "одобрена" : "не одобрена")
            + "\nБлокировка: " + (account.banned ? "да" : "нет")
            + "\nНа сервере: " + (online(nickname) != null ? "да" : "нет");
    }

    String onlinePlayers(int page) {
        if (page < 1) return config.messageOnlineUsage;
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        players.sort(Comparator.comparing(p -> p.getGameProfile().getName().toLowerCase(Locale.ROOT)));
        int perPage = 15;
        int total = players.size();
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        if (page > pages) return config.format(config.messageOnlineInvalidPage,
            Map.of("page", Integer.toString(page), "pages", Integer.toString(pages)));
        Map<String, String> header = Map.of(
            "online", Integer.toString(total),
            "page", Integer.toString(page),
            "pages", Integer.toString(pages)
        );
        StringBuilder output = new StringBuilder(config.format(config.messageOnlineHeader, header));
        if (players.isEmpty()) return output.append("\n").append(config.messageOnlineEmpty).toString();
        int from = (page - 1) * perPage;
        for (int i = from; i < Math.min(from + perPage, total); i++) {
            ServerPlayer player = players.get(i);
            String nickname = clean(player.getGameProfile().getName());
            String display = clean(player.getDisplayName().getString());
            String status = blocked(player) ? config.messageOnlinePending : config.messageOnlineAuthorized;
            Storage.Account account = storage.find(nickname);
            String username = account == null || account.telegramUsername == null || account.telegramUsername.isBlank()
                ? "нет @username" : "@" + clean(account.telegramUsername);
            String tgDisplay = account == null ? "" : clean(account.telegramDisplayName);
            Map<String, String> values = Map.of(
                "index", Integer.toString(i + 1),
                "player", nickname,
                "display", display,
                "status", status,
                "telegram", username,
                "tg_display", tgDisplay
            );
            output.append("\n").append(config.format(config.messageOnlineEntry, values));
        }
        if (page < pages) output.append("\n").append(config.format(config.messageOnlineNextPage,
            Map.of("page", Integer.toString(page + 1), "pages", Integer.toString(pages))));
        return output.toString();
    }

    String history(int days, int page) {
        if (days < 1 || days > 7 || page < 1) return "Укажите 1–7 дней и номер страницы от 1.";
        List<Storage.Visit> list = storage.history(days);
        int perPage = 12;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        if (page > pages) return "Такой страницы нет. Всего страниц: " + pages;
        StringBuilder sb = new StringBuilder("Входы за ").append(days).append(" дн. (всего ")
            .append(list.size()).append(") · страница ").append(page).append('/').append(pages);
        int from = (page - 1) * perPage;
        for (int i = from; i < Math.min(from + perPage, list.size()); i++) {
            Storage.Visit visit = list.get(i);
            sb.append("\n\n").append(i + 1).append(". ").append(time(visit.time))
                .append(" · ").append(visit.player);
            if (visit.displayName != null && !visit.displayName.equals(visit.player)) {
                sb.append(" (").append(visit.displayName).append(")");
            }
            if (visit.telegramDisplayName != null && !visit.telegramDisplayName.isBlank()) {
                sb.append(" · TG: ").append(visit.telegramDisplayName);
            }
            if (visit.telegramUsername != null && !visit.telegramUsername.isBlank()) {
                sb.append(" · @").append(visit.telegramUsername);
            }
            sb.append(" · ").append(status(visit.status));
        }
        return sb.toString();
    }

    private static String status(String status) {
        if (status == null) return "неизвестно";
        return switch (status) {
            case "authorized" -> "авторизован";
            case "pending" -> "ожидает";
            case "timeout" -> "тайм-аут";
            case "rejected" -> "отклонён";
            case "banned" -> "заблокирован";
            case "kicked" -> "кикнут";
            case "reset" -> "сброшен";
            case "reconnected" -> "переподключился";
            case "invalid_dimension" -> "отключён";
            default -> "вышел без авторизации";
        };
    }

    private ServerPlayer online(String nickname) {
        return server.getPlayerList().getPlayerByName(nickname);
    }

    private Map<String, String> values(Storage.Account account, String code) {
        Map<String, String> values = new HashMap<>();
        values.put("player", clean(account.player));
        values.put("display", clean(account.displayName));
        values.put("telegram", account.telegramUsername == null || account.telegramUsername.isBlank() ? "нет @username" : "@" + clean(account.telegramUsername));
        values.put("tg_display", clean(account.telegramDisplayName));
        values.put("id", Long.toString(account.telegramId));
        values.put("time", time(System.currentTimeMillis()));
        values.put("code", code);
        values.put("ip", config.showIpInLoginPrompts || config.showIpInAdminNotifications ? clean(account.lastIp) : "скрыт");
        values.put("ip_line", config.showIpInAdminNotifications ? "\nIP: " + clean(account.lastIp) : "");
        return values;
    }

    private String time(long epochMillis) {
        ZoneId zone;
        try { zone = ZoneId.of(config.timeZone); }
        catch (Exception exception) { zone = ZoneId.of("UTC"); }
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMillis));
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }

    private String randomToken() {
        byte[] data = new byte[18];
        random.nextBytes(data);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static int parseBounded(String text, int low, int high) {
        try {
            int value = Integer.parseInt(text);
            return value >= low && value <= high ? value : -1;
        } catch (Exception ignored) { return -1; }
    }

    private static String clean(String input) {
        if (input == null) return "";
        String value = input.replaceAll("[\\r\\n\\t]", " ").trim();
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private static String str(JsonObject obj, String key) {
        return obj == null || !obj.has(key) || obj.get(key).isJsonNull() ? "" : obj.get(key).getAsString();
    }

    private static long number(JsonObject obj, String key) {
        return obj == null || !obj.has(key) || obj.get(key).isJsonNull() ? 0 : obj.get(key).getAsLong();
    }

    private static String display(JsonObject from) {
        String name = (str(from, "first_name") + " " + str(from, "last_name")).trim();
        return clean(name.isBlank() ? str(from, "username") : name);
    }

    private static boolean constantEquals(String expected, String received) {
        if (expected == null || received == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            received.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
