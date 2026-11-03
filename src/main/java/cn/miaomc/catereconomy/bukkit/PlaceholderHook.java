package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import cn.miaomc.catereconomy.core.Money;
import cn.miaomc.catereconomy.core.TopEntry;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 变量。只读缓存，解析变量时不会访问数据库。&lt;货币&gt; 可填 id、显示名或别名。
 *
 * <p>同一套变量注册在 catereconomy 与 eco 两个标识下，例如 %catereconomy_have_喵币% 与 %eco_have_喵币% 相同。
 * gemseconomy-compat 开启且未安装 GemsEconomy 时，另注册 %gemseconomy_balance_&lt;货币&gt;% 供旧菜单使用。
 */
final class PlaceholderHook {
    /** 按长度从长到短排列，保证 have_raw_ 先于 have_ 匹配。 */
    private static final String[] KINDS = {
        "top_balance_", "top_name_", "have_formatted_", "have_raw_", "receive_limit_", "balance_", "amount_", "have_", "raw_", "name_", "tax_"
    };

    private PlaceholderHook() {}

    static Object register(CaterEconomyBukkit plugin, boolean gemsCompat, boolean ecoIdentifier) {
        Expansion main = new Expansion(plugin, "catereconomy");
        main.register();
        Expansion eco = null;
        if (ecoIdentifier) {
            eco = new Expansion(plugin, "eco");
            eco.register();
        }
        Expansion gems = null;
        if (gemsCompat && plugin.getServer().getPluginManager().getPlugin("GemsEconomy") == null) {
            gems = new Expansion(plugin, "gemseconomy");
            gems.register();
        }
        return new Expansion[] {main, eco, gems};
    }

    static void unregister(Object handle) {
        for (Expansion e : (Expansion[]) handle) {
            if (e == null) continue;
            try {
                e.unregister();
            } catch (Throwable ignored) {
                // 旧版 PAPI 没有 unregister 方法
            }
        }
    }

    static final class Expansion extends PlaceholderExpansion {
        private final CaterEconomyBukkit plugin;
        private final String identifier;

        Expansion(CaterEconomyBukkit plugin, String identifier) {
            this.plugin = plugin;
            this.identifier = identifier;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public String getAuthor() {
            return "MiaoMC";
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            EconomyService s = plugin.serviceOrNull();
            if (s == null) return "";
            String lower = params.toLowerCase(Locale.ROOT);
            String kind = null;
            for (String k : KINDS)
                if (lower.startsWith(k)) {
                    kind = k.substring(0, k.length() - 1);
                    break;
                }
            if (kind == null) return null;
            String rest = params.substring(kind.length() + 1);

            if (kind.startsWith("top_")) {
                int cut = rest.lastIndexOf('_');
                if (cut < 0) return null;
                CurrencyDef cur = s.currency(rest.substring(0, cut));
                if (cur == null) return null;
                int rank;
                try {
                    rank = Integer.parseInt(rest.substring(cut + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                TopEntry entry = s.cachedTop(cur, rank);
                if (kind.equals("top_name")) return entry == null ? "-" : entry.name;
                return entry == null ? "0" : cur.amount(entry.balance);
            }

            CurrencyDef cur = s.currency(rest);
            if (cur == null) return null;
            switch (kind) {
                case "name":
                    return cur.name;
                case "tax":
                    return cur.taxPercent.stripTrailingZeros().toPlainString();
                case "receive_limit":
                    return cur.dailyReceiveLimit == CurrencyDef.UNLIMITED ? "不限" : cur.amount(cur.dailyReceiveLimit);
                default:
                    break;
            }
            if (player == null) return "";
            Long value = s.cached(player.getUniqueId(), cur);
            long v = value == null ? cur.startBalance : value;
            switch (kind) {
                case "have":
                case "amount":
                    return cur.amount(v);
                case "have_raw":
                case "raw":
                    return Money.plain(v, cur.decimals);
                case "have_formatted":
                    return cur.format(v);
                case "balance":
                    return identifier.equals("gemseconomy") ? Money.plain(v, cur.decimals) : cur.format(v);
                default:
                    return null;
            }
        }
    }
}
