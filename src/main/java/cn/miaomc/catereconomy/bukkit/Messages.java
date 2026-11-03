package cn.miaomc.catereconomy.bukkit;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

/** messages.yml；缺少的键自动使用 jar 内默认文本，旧配置升级后不会报错。 */
final class Messages {
    private final Map<String, String> values = new HashMap<String, String>();

    Messages(YamlConfiguration user, InputStream defaults) {
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8));
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) continue;
            String value = user.isString(key) ? user.getString(key) : def.getString(key);
            values.put(key, ChatColor.translateAlternateColorCodes('&', value.replace("\\n", "\n")));
        }
    }

    String raw(String key, Object... pairs) {
        String out = values.get(key);
        if (out == null) return key;
        for (int i = 0; i + 1 < pairs.length; i += 2)
            out = out.replace("{" + pairs[i] + "}", pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]));
        return out;
    }

    String get(String key, Object... pairs) {
        return values.get("prefix") + raw(key, pairs);
    }
}
