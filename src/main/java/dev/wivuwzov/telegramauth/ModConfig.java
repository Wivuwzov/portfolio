package dev.wivuwzov.telegramauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

public final class ModConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("telegramauth.json");
    public String botToken = "";
    public String adminChatId = "";
    public String[] adminUsernames = new String[0];
    public long[] adminIds = new long[0];
    public String adminBootstrapSecret = "";
    public String timeZone = "Europe/Kyiv";
    public int registrationTimeoutSeconds = 600;
    public int loginTimeoutSeconds = 120;
    public int actionbarDurationSeconds = 3;
    public String actionbarText = "вы авторизованы!";
    public String actionbarColor = "#55FF88";
    public boolean showIpInAdminNotifications = false;
    public boolean showIpInLoginPrompts = true;
    public boolean allowMultipleAccountsPerTelegram = false;
    public String messageLink = "Для регистрации напишите боту в личные сообщения: /link {code}";
    public String messageWaitApproval = "Привязка выполнена. Ожидайте подтверждения администрации.";
    public String messageWaitLogin = "Подтвердите вход через Telegram.";
    public String messageBotNotReady = "Telegram-бот пока не настроен. Обратитесь к администрации.";
    public String messageRejected = "Авторизация отклонена.";
    public String messageTimedOut = "Время авторизации истекло.";
    public String messageBanned = "Ваш аккаунт заблокирован.";
    public String messageLoginTelegram = "Попытка входа:\nMinecraft: {player} ({display})\nВремя: {time}\nIP: {ip}\nПодтвердить подключение?";
    public String messageApprovalTelegram = "Новая регистрация:\nMinecraft: {player}\nDisplay Name: {display}\nTelegram: {tg_display} ({telegram})\nTelegram ID: {id}\nВремя: {time}";
    public String messageLoginSucceededTelegram = "Вход подтверждён: {player}.";
    public String messageRegisterSucceededTelegram = "Регистрация подтверждена: {player}.";
    public String messageRegisterRejectedTelegram = "Регистрация отклонена: {player}.";
    public String messageJoinNotification = "Игрок авторизовался:\nНик: {player}\nDisplay Name: {display}\nTelegram: {tg_display} ({telegram})\nTelegram ID: {id}\nВремя: {time}{ip_line}";
    public String messageBotWelcome = "Бот авторизации. Отправьте /link КОД из Minecraft. Для проверки своего Telegram ID: /myid";
    public String messageNoAccess = "Нет доступа.";
    public String messageWrongLinkCode = "Код не найден или срок действия истёк.";
    public String messageLinkOnlyPrivate = "Привязка доступна только в личных сообщениях боту.";
    public String messageAdminLinkRejected = "Привязка администратора отклонена.";
    public String messageAdminLinkSucceeded = "Администратор привязан к Telegram ID {id}.";
    public String messageManyLinkAttempts = "Слишком много попыток. Попробуйте через минуту.";
    public String messageTelegramInUse = "Этот Telegram уже привязан к другому аккаунту Minecraft.";
    public String messageCallbackExpired = "Запрос устарел.";
    public String messageCallbackDenied = "Это не ваша авторизация или у вас недостаточно прав.";
    public String messageCallbackComplete = "Готово.";
    public String messageGroupConfigured = "Административная группа настроена: {chat}.";
    public String messageSetChatOnlyGroup = "Добавьте бота в группу и используйте /setchat там.";
    public String messageHelp = "/online [страница] (или /list), /reset ник, /ban ник, /unban ник, /kick ник, /whois ник, /history 1..7 [страница], /setchat, /myid";
    public String messageOnlineHeader = "Игроки на сервере: {online} · страница {page}/{pages}";
    public String messageOnlineEntry = "{index}. {player} · {display} · {status} · TG: {telegram}";
    public String messageOnlinePending = "ожидает авторизацию";
    public String messageOnlineAuthorized = "авторизован";
    public String messageOnlineEmpty = "Сейчас на сервере никого нет.";
    public String messageOnlineUsage = "Формат: /online [страница] (или /list [страница])";
    public String messageOnlineInvalidPage = "Страница {page} не существует. Всего страниц: {pages}.";
    public String messageOnlineNextPage = "Следующая страница: /online {page}";
    public String buttonApprove = "✅ Одобрить";
    public String buttonReject = "❌ Отклонить";
    public String buttonAllowLogin = "✅ Войти";
    public String buttonDenyLogin = "❌ Это не я";

    public static ModConfig load() {
        try {
            Files.createDirectories(FILE.getParent());
            ModConfig config = Files.exists(FILE)
                ? JSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), ModConfig.class)
                : new ModConfig();
            if (config == null) config = new ModConfig();
            if (config.adminBootstrapSecret == null || config.adminBootstrapSecret.isBlank()) {
                byte[] bytes = new byte[18];
                new SecureRandom().nextBytes(bytes);
                config.adminBootstrapSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
            if (config.adminUsernames == null) config.adminUsernames = new String[0];
            if (config.adminIds == null) config.adminIds = new long[0];
            config.registrationTimeoutSeconds = Math.max(60, Math.min(config.registrationTimeoutSeconds, 3600));
            config.loginTimeoutSeconds = Math.max(30, Math.min(config.loginTimeoutSeconds, 600));
            config.actionbarDurationSeconds = Math.max(1, Math.min(config.actionbarDurationSeconds, 15));
            config.save();
            return config;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load telegramauth.json", exception);
        }
    }

    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(temp, JSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(temp, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public boolean adminIdConfigured(long id) {
        for (long entry : adminIds) if (entry == id) return true;
        return false;
    }

    public boolean adminUsernameConfigured(String username) {
        String normalized = username == null ? "" : username.replace("@", "").toLowerCase(Locale.ROOT);
        return Arrays.stream(adminUsernames)
            .filter(java.util.Objects::nonNull)
            .anyMatch(item -> item.replace("@", "").toLowerCase(Locale.ROOT).equals(normalized));
    }

    public String format(String template, Map<String, String> values) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> item : values.entrySet()) {
            result = result.replace("{" + item.getKey() + "}", item.getValue() == null ? "" : item.getValue());
        }
        return result;
    }
}
