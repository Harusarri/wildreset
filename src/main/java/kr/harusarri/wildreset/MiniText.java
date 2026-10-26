package kr.harusarri.wildreset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.stream.Stream;

public final class MiniText {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static volatile HolderLookup.Provider globalLookup = HolderLookup.Provider.create(Stream.empty());

    private MiniText() {}

    public static void setGlobalLookup(HolderLookup.Provider lookup) {
        if (lookup != null) {
            globalLookup = lookup;
        }
    }

    public static Component parse(String miniMessage) {
        return format(miniMessage, Map.of(), globalLookup);
    }

    public static Component format(String miniMessage, Map<String, String> placeholders) {
        return format(miniMessage, placeholders, globalLookup);
    }

    public static Component format(String miniMessage, Map<String, String> placeholders, HolderLookup.Provider lookup) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return Component.empty();
        }
        String processed = miniMessage;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String key = e.getKey();
            String val = e.getValue() == null ? "" : e.getValue();
            processed = processed.replace("<" + key + ">", val);
        }
        try {
            HolderLookup.Provider provider = lookup != null ? lookup : globalLookup;
            TagResolver[] resolvers = placeholders.entrySet().stream()
                .map(e -> Placeholder.unparsed(e.getKey(), e.getValue() == null ? "" : e.getValue()))
                .toArray(TagResolver[]::new);
            net.kyori.adventure.text.Component adv = MINI.deserialize(processed, resolvers);
            String json = GSON.serialize(adv);
            JsonElement element = JsonParser.parseString(json);
            fixElementFor26_3(element);
            return ComponentSerialization.CODEC.parse(
                provider.createSerializationContext(JsonOps.INSTANCE),
                element
            ).getOrThrow();
        } catch (Exception ex) {
            return Component.literal(stripTags(processed));
        }
    }

    public static void sendChat(ServerPlayer player, String miniMessage, Map<String, String> placeholders) {
        if (player == null || miniMessage == null || miniMessage.isEmpty()) return;
        player.sendSystemMessage(format(miniMessage, placeholders, player.registryAccess()), false);
    }

    public static void sendActionBar(ServerPlayer player, String miniMessage, Map<String, String> placeholders) {
        if (player == null || miniMessage == null || miniMessage.isEmpty()) return;
        player.sendSystemMessage(format(miniMessage, placeholders, player.registryAccess()), true);
    }

    public static void sendCommandFeedback(CommandSourceStack source, String miniMessage, Map<String, String> placeholders) {
        if (source == null || miniMessage == null || miniMessage.isEmpty()) return;
        Component comp = format(miniMessage, placeholders, source.registryAccess());
        source.sendSuccess(() -> comp, false);
    }

    public static String stripTags(String input) {
        return input == null ? "" : input.replaceAll("<[^>]*>", "");
    }

    private static void fixElementFor26_3(JsonElement element) {
        if (element == null) return;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("clickEvent")) {
                JsonElement ce = obj.remove("clickEvent");
                if (ce.isJsonObject()) {
                    JsonObject ceObj = ce.getAsJsonObject();
                    if (ceObj.has("action") && ceObj.has("value")) {
                        String action = ceObj.get("action").getAsString();
                        String value = ceObj.get("value").getAsString();
                        JsonObject fixed = new JsonObject();
                        fixed.addProperty("action", action);
                        if ("run_command".equalsIgnoreCase(action) || "suggest_command".equalsIgnoreCase(action)) {
                            fixed.addProperty("command", value);
                        } else if ("open_url".equalsIgnoreCase(action)) {
                            fixed.addProperty("url", value);
                        } else {
                            fixed.addProperty("value", value);
                        }
                        obj.add("click_event", fixed);
                    }
                }
            }
            if (obj.has("hoverEvent")) {
                JsonElement he = obj.remove("hoverEvent");
                if (he.isJsonObject()) {
                    JsonObject heObj = he.getAsJsonObject();
                    JsonObject fixed = new JsonObject();
                    if (heObj.has("action")) {
                        fixed.addProperty("action", heObj.get("action").getAsString());
                    }
                    if (heObj.has("contents")) {
                        JsonElement contents = heObj.get("contents");
                        fixElementFor26_3(contents);
                        fixed.add("value", contents);
                    } else if (heObj.has("value")) {
                        JsonElement val = heObj.get("value");
                        fixElementFor26_3(val);
                        fixed.add("value", val);
                    }
                    obj.add("hover_event", fixed);
                }
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                fixElementFor26_3(entry.getValue());
            }
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (JsonElement child : arr) {
                fixElementFor26_3(child);
            }
        }
    }
}
