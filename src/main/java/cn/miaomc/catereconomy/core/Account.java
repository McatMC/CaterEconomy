package cn.miaomc.catereconomy.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class Account {
    public final UUID uuid;
    public final String name;

    public Account(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String lowerName() {
        return name.toLowerCase(Locale.ROOT);
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
