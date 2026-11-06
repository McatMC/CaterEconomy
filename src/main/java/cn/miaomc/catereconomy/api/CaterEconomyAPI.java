package cn.miaomc.catereconomy.api;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.Change;
import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import cn.miaomc.catereconomy.core.Record;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 供其他插件调用的公开接口。所有金额都是货币的最小单位（decimals=2 时 1 元 = 100）。
 *
 * <p>读余额优先用 {@link #cached}：只读内存，不碰数据库，适合在主线程频繁调用。加钱、扣钱返回
 * CompletableFuture，在存储线程完成；回调里需要操作 Bukkit 时自己切回主线程。
 */
public final class CaterEconomyAPI {
    private static volatile EconomyService service;

    private CaterEconomyAPI() {}

    /** 由 CaterEconomy 在启动、重载、关闭时调用，其他插件不要调用。 */
    public static void bind(EconomyService s) {
        service = s;
    }

    /** 经济服务已启动，且这种货币存在、它的存储可用。 */
    public static boolean ready(String currency) {
        EconomyService s = service;
        if (s == null) return false;
        CurrencyDef cur = s.currency(currency);
        return cur != null && s.available(cur);
    }

    /** 货币定义；不存在返回 null。可以写 id、显示名或别名。 */
    public static CurrencyDef currency(String currency) {
        EconomyService s = service;
        return s == null ? null : s.currency(currency);
    }

    /** 缓存中的余额（在线玩家进服时加载）；未加载或货币不可用返回 null。 */
    public static Long cached(UUID uuid, String currency) {
        EconomyService s = service;
        CurrencyDef cur = s == null ? null : s.currency(currency);
        return cur == null ? null : s.cached(uuid, cur);
    }

    /** 余额：有缓存直接返回，否则查数据库。 */
    public static CompletableFuture<Long> balance(UUID uuid, String name, String currency) {
        EconomyService s = service;
        CurrencyDef cur = s == null ? null : s.currency(currency);
        if (cur == null || !s.available(cur)) return failed("货币不可用：" + currency);
        return s.balance(cur, new Account(uuid, name == null ? "" : name));
    }

    /** 加钱。actor 写进流水，用来说明来源（如 "NoahArk:任务"）。 */
    public static CompletableFuture<Change> deposit(UUID uuid, String name, String currency, long amount, String actor) {
        EconomyService s = service;
        CurrencyDef cur = s == null ? null : s.currency(currency);
        if (cur == null || !s.available(cur)) return failed("货币不可用：" + currency);
        if (amount < 0) return failed("金额不能为负数");
        return s.deposit(cur, new Account(uuid, name == null ? "" : name), amount, Record.Type.PLUGIN_DEPOSIT, actor);
    }

    /** 扣钱；余额不足时返回 status=INSUFFICIENT，不会扣成负数。 */
    public static CompletableFuture<Change> withdraw(UUID uuid, String name, String currency, long amount, String actor) {
        EconomyService s = service;
        CurrencyDef cur = s == null ? null : s.currency(currency);
        if (cur == null || !s.available(cur)) return failed("货币不可用：" + currency);
        if (amount < 0) return failed("金额不能为负数");
        return s.withdraw(cur, new Account(uuid, name == null ? "" : name), amount, Record.Type.PLUGIN_WITHDRAW, actor);
    }

    /** 按货币的 format 显示金额（带颜色代码 &）。 */
    public static String format(String currency, long amount) {
        CurrencyDef cur = currency(currency);
        return cur == null ? String.valueOf(amount) : cur.format(amount);
    }

    private static <T> CompletableFuture<T> failed(String msg) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(new IllegalStateException(msg));
        return f;
    }
}
