package dev.wivuwzov.telegramauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TelegramGateway {
    private static final Logger LOG = LogManager.getLogger("TelegramAuth");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService outgoing = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "telegramauth-outgoing");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final String token;
    private final AuthService service;
    private final MinecraftServer server;
    private Thread pollThread;

    TelegramGateway(String token, AuthService service, MinecraftServer server) {
        this.token = token.trim();
        this.service = service;
        this.server = server;
    }

    void start(long updateOffset) {
        if (token.isBlank()) {
            LOG.warn("TelegramAuth bot token is not configured");
            return;
        }
        pollThread = new Thread(() -> poll(updateOffset), "telegramauth-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    void stop() {
        active.set(false);
        if (pollThread != null) pollThread.interrupt();
        outgoing.shutdownNow();
        try { outgoing.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private JsonObject request(String method, JsonObject args, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot" + token + "/" + method))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(args.toString()))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200 || !data.has("ok") || !data.get("ok").getAsBoolean()) {
            String description = data.has("description") ? data.get("description").getAsString() : "No description";
            throw new IllegalStateException(method + " HTTP " + response.statusCode() + ": " + description);
        }
        return data;
    }

    private void poll(long lastUpdateId) {
        long next = Math.max(0, lastUpdateId + 1);
        while (active.get()) {
            try {
                JsonObject input = new JsonObject();
                input.addProperty("offset", next);
                input.addProperty("timeout", 20);
                JsonArray updates = new JsonArray();
                updates.add("message");
                updates.add("callback_query");
                input.add("allowed_updates", updates);
                JsonObject response = request("getUpdates", input, 35);
                for (JsonElement element : response.getAsJsonArray("result")) {
                    if (!active.get()) break;
                    JsonObject update = element.getAsJsonObject();
                    long id = update.get("update_id").getAsLong();
                    if (id < next) continue;
                    next = id + 1;
                    server.execute(() -> {
                        if (active.get()) service.onTelegramUpdate(update);
                    });
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                if (!active.get()) break;
                LOG.warn("TelegramAuth polling: {}", exception.getMessage());
                try { Thread.sleep(5000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    void send(String chat, String message) {
        send(chat, message, null);
    }

    void send(String chat, String message, JsonObject keyboard) {
        if (!active.get() || token.isBlank() || chat == null || chat.isBlank()) return;
        JsonObject args = new JsonObject();
        try { args.addProperty("chat_id", Long.parseLong(chat)); }
        catch (NumberFormatException exception) { args.addProperty("chat_id", chat); }
        args.addProperty("text", message == null ? "" : message);
        if (keyboard != null) args.add("reply_markup", keyboard);
        submit("sendMessage", args);
    }

    void answerCallback(String id, String message) {
        JsonObject args = new JsonObject();
        args.addProperty("callback_query_id", id);
        args.addProperty("text", message);
        submit("answerCallbackQuery", args);
    }

    void clearKeyboard(String chat, int messageId) {
        JsonObject args = new JsonObject();
        try { args.addProperty("chat_id", Long.parseLong(chat)); }
        catch (NumberFormatException exception) { args.addProperty("chat_id", chat); }
        args.addProperty("message_id", messageId);
        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", new JsonArray());
        args.add("reply_markup", markup);
        submit("editMessageReplyMarkup", args);
    }

    private void submit(String method, JsonObject args) {
        if (!active.get() || token.isBlank()) return;
        outgoing.submit(() -> {
            try { request(method, args, 20); }
            catch (Exception exception) { LOG.warn("TelegramAuth {}: {}", method, exception.getMessage()); }
        });
    }

    static JsonObject buttons(String leftText, String leftCode, String rightText, String rightCode) {
        JsonArray row = new JsonArray();
        JsonObject left = new JsonObject();
        left.addProperty("text", leftText);
        left.addProperty("callback_data", leftCode);
        row.add(left);
        JsonObject right = new JsonObject();
        right.addProperty("text", rightText);
        right.addProperty("callback_data", rightCode);
        row.add(right);
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", rows);
        return markup;
    }
}
