package kr.harusarri.wildreset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-language localization manager for WildReset (`en_us` / `ko_kr` / `auto`).
 * In `auto` mode, detects each online player's client language (`player.clientInformation().language()`)
 * and falls back to the server JVM locale for console command execution.
 */
public final class WildResetI18n {
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> KO = new HashMap<>();
    public static final WildResetConfig.Messages DEFAULT_KO_MESSAGES = new WildResetConfig.Messages();

    static {
        loadLang("/assets/wildreset/lang/en_us.json", EN);
        loadLang("/assets/wildreset/lang/ko_kr.json", KO);
    }

    private WildResetI18n() {}

    private static void loadLang(String resourcePath, Map<String, String> target) {
        try (InputStream is = WildResetI18n.class.getResourceAsStream(resourcePath)) {
            if (is == null) return;
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    target.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String normalizeMode(String rawMode) {
        if (rawMode == null) return "auto";
        String m = rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "ko", "ko_kr", "kr" -> "ko_kr";
            case "en", "en_us", "us" -> "en_us";
            default -> "auto";
        };
    }

    public static String getMode() {
        return normalizeMode(WildResetConfig.get().language);
    }

    public static void setMode(String newMode) {
        String normalized = normalizeMode(newMode);
        WildResetConfig.get().language = normalized;
        WildResetConfig.save();
    }

    public static String resolveLang(ServerPlayer player) {
        String mode = getMode();
        if (!"auto".equals(mode)) {
            return mode;
        }
        if (player != null) {
            try {
                String clientLang = player.clientInformation().language();
                if (clientLang != null && clientLang.toLowerCase(Locale.ROOT).startsWith("ko")) {
                    return "ko_kr";
                }
                return "en_us";
            } catch (Throwable ignored) {
            }
        }
        String sys = Locale.getDefault().getLanguage();
        return (sys != null && sys.toLowerCase(Locale.ROOT).startsWith("ko")) ? "ko_kr" : "en_us";
    }

    public static String resolveLang(CommandSourceStack source) {
        if (source != null && source.getEntity() instanceof ServerPlayer player) {
            return resolveLang(player);
        }
        return resolveLang((ServerPlayer) null);
    }

    public static String raw(String lang, String key) {
        Map<String, String> primary = "ko_kr".equals(lang) ? KO : EN;
        String val = primary.get(key);
        if (val == null) {
            val = EN.getOrDefault(key, key);
        }
        return val;
    }

    /**
     * Resolves a MiniMessage template for a specific language (`en_us` or `ko_kr`).
     * If the server admin explicitly customized the message in `config/wildreset.json`
     * (differs from default `ko_kr`), that custom override is respected.
     */
    public static String resolveLangTemplate(String lang, String key, String configValue, String defaultKoValue) {
        if (configValue != null && defaultKoValue != null && !configValue.equals(defaultKoValue)) {
            return configValue;
        }
        return raw(lang, key);
    }

    public static String resolvePlayerTemplate(ServerPlayer player, String key, String configValue, String defaultKoValue) {
        return resolveLangTemplate(resolveLang(player), key, configValue, defaultKoValue);
    }

    public static String resolveSourceTemplate(CommandSourceStack source, String key, String configValue, String defaultKoValue) {
        return resolveLangTemplate(resolveLang(source), key, configValue, defaultKoValue);
    }

    public static String formatRemainingTime(long remainingMs, String lang) {
        long totalSec = Math.max(1L, remainingMs / 1000L);
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) {
            return raw(lang, "msg.time.hours_minutes")
                .replace("<hours>", String.valueOf(hours))
                .replace("<minutes>", String.valueOf(minutes));
        } else if (minutes > 0) {
            return raw(lang, "msg.time.minutes_seconds")
                .replace("<minutes>", String.valueOf(minutes))
                .replace("<seconds>", String.valueOf(seconds));
        } else {
            return raw(lang, "msg.time.seconds")
                .replace("<seconds>", String.valueOf(seconds));
        }
    }
}
