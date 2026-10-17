package io.github.aconhol.endermancarry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path path;
    private final Consumer<String> validator;
    private volatile CarryConfig current = CarryConfig.defaults();

    public ConfigStore(Path path, Consumer<String> validator) {
        this.path = path;
        this.validator = validator;
    }

    public CarryConfig current() { return current; }

    public Path path() { return path; }

    public void initialize() throws IOException {
        if (Files.exists(path)) reload();
        else replace(CarryConfig.defaults());
    }

    public void reload() throws IOException {
        CarryConfig candidate = decode(Files.readString(path, StandardCharsets.UTF_8));
        validate(candidate);
        current = candidate;
    }

    public void replace(CarryConfig candidate) throws IOException {
        validate(candidate);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "enderman-carry-", ".tmp");
        try {
            Files.writeString(temporary, encode(candidate), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            current = candidate;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validate(CarryConfig config) {
        for (CarryConfig.Action action : CarryConfig.Action.values()) {
            config.rule(action).blocks().forEach(validator);
        }
    }

    public static String encode(CarryConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        for (CarryConfig.Action action : CarryConfig.Action.values()) {
            CarryConfig.Rule rule = config.rule(action);
            JsonObject object = new JsonObject();
            object.addProperty("mode", rule.mode().id());
            JsonArray blocks = new JsonArray();
            rule.blocks().forEach(blocks::add);
            object.add("blocks", blocks);
            root.add(action.id(), object);
        }
        return GSON.toJson(root) + "\n";
    }

    public static CarryConfig decode(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("JSON 结尾包含多余内容");
            JsonObject root = element.getAsJsonObject();
            requireKeys(root, Set.of("schemaVersion", "pickup", "place"));
            if (!root.get("schemaVersion").toString().equals("1")) {
                throw new IllegalArgumentException("schemaVersion 必须是 1");
            }
            return new CarryConfig(1, readRule(root.getAsJsonObject("pickup")), readRule(root.getAsJsonObject("place")));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("配置无效: " + e.getMessage(), e);
        }
    }

    private static CarryConfig.Rule readRule(JsonObject object) {
        requireKeys(object, Set.of("mode", "blocks"));
        CarryConfig.Mode mode = CarryConfig.Mode.parse(string(object.get("mode")));
        ArrayList<String> blocks = new ArrayList<>();
        for (JsonElement entry : object.getAsJsonArray("blocks")) blocks.add(string(entry));
        return new CarryConfig.Rule(mode, blocks);
    }

    private static String string(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("mode 和 blocks 条目必须是字符串");
        }
        return element.getAsString();
    }

    private static void requireKeys(JsonObject object, Set<String> keys) {
        if (object == null || !object.keySet().equals(keys)) {
            throw new IllegalArgumentException("字段必须恰好包含 " + keys);
        }
    }
}
