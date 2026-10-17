package io.github.aconhol.endermancarry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.aconhol.endermancarry.CarryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test
    void defaultRulesAreAnIdentityForVanillaDecisions() {
        CarryConfig config = CarryConfig.defaults();
        for (Action action : Action.values()) {
            for (boolean vanilla : new boolean[]{true, false}) {
                assertEquals(vanilla, config.rule(action).allows(vanilla, entry -> true));
            }
        }
    }

    @Test
    void blacklistCanOnlyNarrowAndWhitelistCanExtendTheVanillaSet() {
        assertFalse(Mode.BLACKLIST.allows(false, false));
        assertFalse(Mode.BLACKLIST.allows(true, true));
        assertTrue(Mode.BLACKLIST.allows(true, false));
        assertTrue(Mode.WHITELIST.allows(false, true));
        assertFalse(Mode.WHITELIST.allows(true, false));
        assertTrue(Mode.ALL.allows(false, false));
        assertFalse(Mode.NONE.allows(true, true));
    }

    @Test
    void commandStyleEditsPersistAndActionsRemainIndependent() throws Exception {
        Path file = directory.resolve("config.json");
        ConfigStore store = new ConfigStore(file, entry -> {});
        store.initialize();
        CarryConfig expected = store.current().withRule(Action.PICKUP,
                new Rule(Mode.WHITELIST, List.of("minecraft:stone", "#minecraft:logs")));
        store.replace(expected);
        ConfigStore restarted = new ConfigStore(file, entry -> {});
        restarted.initialize();
        assertEquals(expected, restarted.current());
        assertEquals(CarryConfig.defaults().place(), restarted.current().place());
        assertEquals(expected, ConfigStore.decode(ConfigStore.encode(expected)));
    }

    @Test
    void invalidReloadLeavesLiveRulesAndFileIntact() throws Exception {
        Path file = directory.resolve("config.json");
        ConfigStore store = new ConfigStore(file, entry -> {});
        store.initialize();
        CarryConfig previous = store.current().withRule(Action.PLACE, new Rule(Mode.NONE, List.of()));
        store.replace(previous);
        String broken = "{this is invalid JSON}";
        Files.writeString(file, broken);
        assertThrows(IllegalArgumentException.class, store::reload);
        assertEquals(previous, store.current());
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void unknownBlocksCannotPartiallyChangeLiveRulesOrSavedConfig() throws Exception {
        Path file = directory.resolve("config.json");
        ConfigStore store = new ConfigStore(file, entry -> {
            if (entry.equals("test:missing")) throw new IllegalArgumentException("unknown block");
        });
        store.initialize();
        String before = Files.readString(file);
        CarryConfig invalid = store.current().withRule(Action.PICKUP,
                new Rule(Mode.WHITELIST, List.of("test:missing")));
        assertThrows(IllegalArgumentException.class, () -> store.replace(invalid));
        assertEquals(CarryConfig.defaults(), store.current());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void schemaTyposNullsWrongTypesAndTrailingGarbageAreRejected() {
        String valid = ConfigStore.encode(CarryConfig.defaults());
        for (String bad : List.of(valid.replace("vanilla", "vanila"),
                valid.replace("pickup", "pickupp"), valid.replace("[]", "null"),
                valid.replace("[]", "[false]"), valid.replace("\"vanilla\"", "null"),
                valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), valid + " {}")) {
            assertThrows(IllegalArgumentException.class, () -> ConfigStore.decode(bad), bad);
        }
    }

    @Test
    void fileWriteFailureDoesNotPublishUnsavedRules() throws Exception {
        Path blockedParent = directory.resolve("regular-file");
        Files.writeString(blockedParent, "not a directory");
        ConfigStore store = new ConfigStore(blockedParent.resolve("config.json"), entry -> {});
        assertThrows(java.io.IOException.class, () -> store.replace(CarryConfig.defaults().withRule(
                Action.PICKUP, new Rule(Mode.NONE, List.of()))));
        assertEquals(CarryConfig.defaults(), store.current());
    }
}
