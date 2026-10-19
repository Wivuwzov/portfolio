package dev.wivuwzov.telegramauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class Storage {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("telegramauth-data.json");
    final Map<String, Account> accounts = new HashMap<>();
    final Map<String, Long> adminBindings = new HashMap<>();
    final List<Visit> visits = new ArrayList<>();
    long lastUpdateId = 0;

    static final class Account {
        String player = "";
        String displayName = "";
        long telegramId = 0;
        String telegramUsername = "";
        String telegramDisplayName = "";
        boolean approved = false;
        boolean banned = false;
        String originalGameMode = "";
        long linkedAt = 0;
        long lastSeenAt = 0;
        String lastIp = "";
    }

    static final class Visit {
        String id = UUID.randomUUID().toString();
        long time = System.currentTimeMillis();
        String player = "";
        String displayName = "";
        String ip = "";
        String telegramUsername = "";
        String telegramDisplayName = "";
        long telegramId = 0;
        String status = "pending";
    }

    static Storage load() {
        try {
            if (!Files.exists(FILE)) return new Storage();
            Storage data = JSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Storage.class);
            if (data == null || data.accounts == null || data.visits == null || data.adminBindings == null) {
                throw new IOException("Unexpected storage format");
            }
            data.prune();
            return data;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read TelegramAuth storage; backup the damaged file before starting", exception);
        }
    }

    void save() {
        try {
            prune();
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, JSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(temp, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save TelegramAuth storage", exception);
        }
    }

    Account getOrCreate(String nickname) {
        return accounts.computeIfAbsent(key(nickname), k -> {
            Account entry = new Account();
            entry.player = nickname;
            return entry;
        });
    }

    Account find(String nickname) {
        return accounts.get(key(nickname));
    }

    void prune() {
        long oldest = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
        visits.removeIf(visit -> visit.time < oldest);
    }

    Visit newVisit(String nickname, String display, String ip, Account account) {
        Visit visit = new Visit();
        visit.player = nickname;
        visit.displayName = display;
        visit.ip = ip;
        visit.telegramId = account.telegramId;
        visit.telegramUsername = account.telegramUsername;
        visit.telegramDisplayName = account.telegramDisplayName;
        visits.add(visit);
        save();
        return visit;
    }

    void mark(Visit visit, String status, Account account) {
        if (visit == null) return;
        visit.status = status;
        if (account != null) {
            visit.telegramId = account.telegramId;
            visit.telegramUsername = account.telegramUsername;
            visit.telegramDisplayName = account.telegramDisplayName;
        }
        save();
    }

    List<Visit> history(int days) {
        long oldest = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        return visits.stream().filter(v -> v.time >= oldest)
            .sorted(Comparator.comparingLong((Visit item) -> item.time).reversed()).toList();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
