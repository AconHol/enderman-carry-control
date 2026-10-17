package io.github.aconhol.endermancarry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Immutable snapshots keep a failed edit or reload from partially changing live rules. */
public record CarryConfig(int schemaVersion, Rule pickup, Rule place) {
    public CarryConfig {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion 必须是 1");
        Objects.requireNonNull(pickup, "缺少 pickup");
        Objects.requireNonNull(place, "缺少 place");
    }

    public static CarryConfig defaults() {
        return new CarryConfig(1, new Rule(Mode.VANILLA, List.of()), new Rule(Mode.VANILLA, List.of()));
    }

    public Rule rule(Action action) {
        return action == Action.PICKUP ? pickup : place;
    }

    public CarryConfig withRule(Action action, Rule rule) {
        return action == Action.PICKUP
                ? new CarryConfig(1, rule, place) : new CarryConfig(1, pickup, rule);
    }

    public enum Action {
        PICKUP, PLACE;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Mode {
        VANILLA, WHITELIST, BLACKLIST, ALL, NONE;

        public String id() { return name().toLowerCase(Locale.ROOT); }

        public static Mode parse(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException e) {
                throw new IllegalArgumentException("模式必须是 vanilla、whitelist、blacklist、all 或 none");
            }
        }

        /** Blacklist narrows the vanilla set; whitelist can opt additional blocks in. */
        public boolean allows(boolean vanillaAllowed, boolean listed) {
            return switch (this) {
                case VANILLA -> vanillaAllowed;
                case WHITELIST -> listed;
                case BLACKLIST -> vanillaAllowed && !listed;
                case ALL -> true;
                case NONE -> false;
            };
        }
    }

    public record Rule(Mode mode, List<String> blocks) {
        public Rule {
            Objects.requireNonNull(mode, "缺少 mode");
            blocks = List.copyOf(Objects.requireNonNull(blocks, "缺少 blocks"));
            if (blocks.size() != blocks.stream().distinct().count()) {
                throw new IllegalArgumentException("blocks 中存在重复条目");
            }
        }

        public boolean allows(boolean vanillaAllowed, Predicate<String> matches) {
            boolean listed = (mode == Mode.WHITELIST || mode == Mode.BLACKLIST)
                    && blocks.stream().anyMatch(matches);
            return mode.allows(vanillaAllowed, listed);
        }
    }
}
