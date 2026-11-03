package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.Money;
import cn.miaomc.catereconomy.core.StorageDef;
import java.io.File;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** 从 config.yml 读取并校验的全部设置；任何错误都会以中文异常说明具体位置。 */
final class Settings {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    final String serverName;
    final boolean onlineUuid;
    final List<StorageDef> storages = new ArrayList<StorageDef>();
    final List<CurrencyDef> currencies = new ArrayList<CurrencyDef>();
    final String defaultCurrency;
    final boolean vaultEnabled;
    final String vaultCurrency;
    final int refreshSeconds;
    final int topRefreshSeconds;
    final int topSize;
    final int pageSize;
    final int keepDays;
    final long preloadTimeoutMillis;
    final long vaultTimeoutMillis;
    final boolean gemsPlaceholders;
    final boolean ecoPlaceholders;
    final int confirmSeconds;
    final Map<String, Long> confirmAbove = new HashMap<String, Long>();
    final ZoneId zone;
    final int ipWindowDays;
    final Set<String> ignoredIps = new HashSet<String>();
    final String payDefaultCurrency;
    final boolean guiEnabled;
    final boolean guiSkipSingleCurrency;
    final List<String> guiPresets = new ArrayList<String>();
    final int guiCustomTimeoutSeconds;
    final Set<String> guiCancelWords = new HashSet<String>();
    final Map<String, String> guiIcons = new HashMap<String, String>();

    Settings(FileConfiguration c, File dataFolder) {
        serverName = c.getString("server-name", "lobby");
        String mode = c.getString("uuid-mode", "offline").toLowerCase(Locale.ROOT);
        if (!mode.equals("offline") && !mode.equals("online")) throw new IllegalArgumentException("uuid-mode 只能是 offline 或 online");
        onlineUuid = mode.equals("online");
        try {
            zone = ZoneId.of(c.getString("daily-reset.timezone", "Asia/Shanghai"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("daily-reset.timezone 不是有效时区：" + c.getString("daily-reset.timezone"));
        }
        ipWindowDays = Math.max(1, c.getInt("same-ip.window-days", 30));
        for (String ip : c.getStringList("same-ip.ignore-ips")) ignoredIps.add(ip.trim());

        ConfigurationSection ss = c.getConfigurationSection("storages");
        if (ss == null || ss.getKeys(false).isEmpty()) throw new IllegalArgumentException("storages 至少要配置一个存储");
        Set<String> storageIds = new HashSet<String>();
        for (String id : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(id);
            String where = "storages." + id;
            if (s == null) throw new IllegalArgumentException(where + " 格式错误");
            String prefix = s.getString("table-prefix", "catereco_");
            if (!prefix.matches("[A-Za-z0-9_]{0,32}")) throw new IllegalArgumentException(where + ".table-prefix 只能包含字母、数字和下划线");
            String type = s.getString("type", "").toLowerCase(Locale.ROOT);
            if (type.equals("mysql")) {
                storages.add(
                        StorageDef.mysql(
                                id,
                                required(s, "host", where),
                                s.getInt("port", 3306),
                                required(s, "database", where),
                                required(s, "username", where),
                                s.getString("password", ""),
                                s.getString("properties", "useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai"),
                                Math.max(1, Math.min(16, s.getInt("pool-size", 4))),
                                prefix));
            } else if (type.equals("sqlite") || type.equals("local")) {
                File file = new File(s.getString("file", "data.db"));
                if (!file.isAbsolute()) file = new File(dataFolder, file.getPath());
                storages.add(StorageDef.sqlite(id, file, prefix));
            } else {
                throw new IllegalArgumentException(where + ".type 只能是 mysql 或 sqlite");
            }
            storageIds.add(id);
        }

        ConfigurationSection cs = c.getConfigurationSection("currencies");
        if (cs == null || cs.getKeys(false).isEmpty()) throw new IllegalArgumentException("currencies 至少要配置一种货币");
        Set<String> names = new HashSet<String>();
        for (String id : cs.getKeys(false)) {
            String where = "currencies." + id;
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException(where + "：货币 id 只能用小写字母、数字和下划线，最长 32 位");
            ConfigurationSection s = cs.getConfigurationSection(id);
            if (s == null) throw new IllegalArgumentException(where + " 格式错误");
            String storage = required(s, "storage", where);
            if (!storageIds.contains(storage)) throw new IllegalArgumentException(where + ".storage 指向不存在的存储 " + storage);
            int decimals = s.getInt("decimals", 0);
            if (decimals < 0 || decimals > 4) throw new IllegalArgumentException(where + ".decimals 只能是 0~4");
            String name = s.getString("name", id);
            List<String> aliases = new ArrayList<String>();
            for (String a : s.getStringList("aliases")) if (!a.trim().isEmpty()) aliases.add(a.trim());
            Set<String> own = new HashSet<String>();
            for (String key : concat(id, name, aliases)) own.add(key.toLowerCase(Locale.ROOT));
            for (String key : own)
                if (names.contains(key)) throw new IllegalArgumentException(where + "：名称或别名 " + key + " 与其他货币重复");
            names.addAll(own);

            ConfigurationSection pay = s.getConfigurationSection("pay");
            String pw = where + ".pay";
            BigDecimal taxPercent = percent(pay == null ? "0" : String.valueOf(pay.get("tax.percent", "0")), pw + ".tax.percent");
            String taxMode = pay == null ? "sender" : pay.getString("tax.mode", "sender").toLowerCase(Locale.ROOT);
            if (!taxMode.equals("sender") && !taxMode.equals("receiver"))
                throw new IllegalArgumentException(pw + ".tax.mode 只能是 sender 或 receiver");
            long dailyReceive = pay == null ? 0 : amount(pay, "daily-receive-limit", decimals, pw);
            CurrencyDef def =
                    new CurrencyDef(
                            id,
                            name,
                            aliases,
                            storage,
                            decimals,
                            amount(s, "start-balance", decimals, where),
                            amount(s, "max-balance", decimals, where),
                            ChatColor.translateAlternateColorCodes('&', s.getString("format", "&e{amount} &f{name}")),
                            s.getBoolean("grouping", true),
                            pay != null && pay.getBoolean("enabled", false),
                            pay == null ? 0 : amount(pay, "min", decimals, pw),
                            pay == null ? 0 : amount(pay, "max", decimals, pw),
                            taxPercent,
                            taxMode.equals("sender"),
                            pay == null ? 0 : Math.max(0, pay.getInt("cooldown-seconds", 0)),
                            dailyReceive <= 0 ? CurrencyDef.UNLIMITED : dailyReceive,
                            pay != null && pay.getBoolean("same-ip.enabled", false),
                            pay == null ? CurrencyDef.UNLIMITED : count(pay, "same-ip.max-count", pw),
                            pay == null ? CurrencyDef.UNLIMITED : limit(pay, "same-ip.max-amount", decimals, pw));
            if (def.maxBalance > 0 && def.startBalance > def.maxBalance) throw new IllegalArgumentException(where + ".start-balance 大于 max-balance");
            currencies.add(def);
            String icon = s.getString("icon", "");
            if (!icon.trim().isEmpty()) guiIcons.put(id, icon.trim().toUpperCase(Locale.ROOT));
            if (pay != null) confirmAbove.put(id, amount(pay, "confirm-above", decimals, pw));
        }

        defaultCurrency = c.getString("default-currency", currencies.get(0).id);
        vaultEnabled = c.getBoolean("vault.enabled", true);
        vaultCurrency = c.getString("vault.currency", defaultCurrency);
        refreshSeconds = Math.max(1, c.getInt("sync.refresh-seconds", 5));
        preloadTimeoutMillis = Math.max(500, c.getLong("sync.preload-timeout-ms", 3000));
        vaultTimeoutMillis = Math.max(500, c.getLong("vault.timeout-ms", 3000));
        topRefreshSeconds = Math.max(10, c.getInt("top.refresh-seconds", 60));
        topSize = Math.max(1, Math.min(100, c.getInt("top.cache-size", 10)));
        pageSize = Math.max(1, Math.min(20, c.getInt("page-size", 8)));
        keepDays = Math.max(0, c.getInt("records.keep-days", 0));
        gemsPlaceholders = c.getBoolean("placeholders.gemseconomy-compat", true);
        ecoPlaceholders = c.getBoolean("placeholders.eco-identifier", true);
        confirmSeconds = Math.max(5, c.getInt("pay-confirm-seconds", 30));
        String payDefault = c.getString("pay-default-currency", "").trim();
        payDefaultCurrency = payDefault.isEmpty() ? null : payDefault;
        if (payDefaultCurrency != null && !names.contains(payDefaultCurrency.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("pay-default-currency 指向不存在的货币 " + payDefaultCurrency);
        guiEnabled = c.getBoolean("gui.enabled", true);
        guiSkipSingleCurrency = c.getBoolean("gui.skip-single-currency", false);
        List<?> presets = c.getList("gui.amounts");
        if (presets == null || presets.isEmpty()) presets = java.util.Arrays.asList(10, 50, 100, 200, 500, 1000, 2000, 3000, 5000);
        if (presets.size() > 21) throw new IllegalArgumentException("gui.amounts 最多 21 个");
        for (Object o : presets) guiPresets.add(String.valueOf(o).trim());
        guiCustomTimeoutSeconds = Math.max(5, c.getInt("gui.custom-timeout-seconds", 30));
        List<String> cancel = c.getStringList("gui.cancel-words");
        if (cancel.isEmpty()) cancel = java.util.Arrays.asList("取消", "cancel");
        for (String w : cancel) guiCancelWords.add(w.trim().toLowerCase(Locale.ROOT));
    }

    /** 按配置时区的当天日期，例如 20261103。 */
    int today() {
        return Integer.parseInt(LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    int daysAgo(int days) {
        return Integer.parseInt(LocalDate.now(zone).minusDays(days).format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    long ipSince() {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ipWindowDays);
    }

    private static String required(ConfigurationSection s, String key, String where) {
        String v = s.getString(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(where + "." + key + " 不能为空");
        return v.trim();
    }

    private static long amount(ConfigurationSection s, String key, int decimals, String where) {
        Object raw = s.get(key, 0);
        try {
            return Money.parse(String.valueOf(raw), decimals);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "." + key + " 不是有效金额（最多 " + decimals + " 位小数）：" + raw);
        }
    }

    /** -1 表示不限，其余同 amount。 */
    private static long limit(ConfigurationSection s, String key, int decimals, String where) {
        if ("-1".equals(String.valueOf(s.get(key, -1)).trim())) return CurrencyDef.UNLIMITED;
        return amount(s, key, decimals, where);
    }

    /** -1 表示不限，0 表示禁止。 */
    private static long count(ConfigurationSection s, String key, String where) {
        int v = s.getInt(key, -1);
        if (v < -1) throw new IllegalArgumentException(where + "." + key + " 只能是 -1（不限）、0（禁止）或正整数");
        return v;
    }

    private static BigDecimal percent(String raw, String where) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + " 不是数字：" + raw);
        }
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) >= 0) throw new IllegalArgumentException(where + " 必须在 0~100 之间（不含 100）");
        return v;
    }

    private static List<String> concat(String id, String name, List<String> aliases) {
        List<String> out = new ArrayList<String>();
        out.add(id);
        if (!name.equalsIgnoreCase(id)) out.add(name);
        out.addAll(aliases);
        return out;
    }
}
