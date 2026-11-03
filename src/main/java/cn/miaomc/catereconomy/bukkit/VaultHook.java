package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.Change;
import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import cn.miaomc.catereconomy.core.Money;
import cn.miaomc.catereconomy.core.Record;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * Vault 只支持一种货币，这里把 vault.currency 指定的货币提供给 Essentials、商店等插件。
 *
 * <p>Vault 接口是同步的。在线玩家的查询直接读缓存；加减款必须等数据库确认，在主线程上最多阻塞 vault.timeout-ms。
 */
final class VaultHook implements Economy {
    private final CaterEconomyBukkit plugin;
    private final String currencyKey;

    private VaultHook(CaterEconomyBukkit plugin, String currencyKey) {
        this.plugin = plugin;
        this.currencyKey = currencyKey;
    }

    static VaultHook register(CaterEconomyBukkit plugin, String currency) {
        VaultHook hook = new VaultHook(plugin, currency);
        if (hook.currency() == null) {
            plugin.getLogger().warning("vault.currency 指向的货币 " + currency + " 不存在或不可用，未注册 Vault 经济");
            return null;
        }
        Bukkit.getServicesManager().register(Economy.class, hook, plugin, ServicePriority.Highest);
        plugin.getLogger().info("已向 Vault 提供货币 " + hook.currency().name);
        return hook;
    }

    void unregister() {
        Bukkit.getServicesManager().unregister(Economy.class, this);
    }

    private CurrencyDef currency() {
        EconomyService s = plugin.serviceOrNull();
        return s == null ? null : s.currency(currencyKey);
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(plugin.settings().vaultTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private Account account(OfflinePlayer player) {
        return new Account(player.getUniqueId(), player.getName() == null ? player.getUniqueId().toString().substring(0, 16) : player.getName());
    }

    @SuppressWarnings("deprecation")
    private Account account(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return account(online);
        EconomyService s = plugin.serviceOrNull();
        if (s == null) return null;
        try {
            return await(s.resolve(name, !plugin.settings().onlineUuid));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 基本信息

    @Override
    public boolean isEnabled() {
        return currency() != null;
    }

    @Override
    public String getName() {
        return "CaterEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        CurrencyDef c = currency();
        return c == null ? 0 : c.decimals;
    }

    @Override
    public String format(double amount) {
        CurrencyDef c = currency();
        if (c == null) return String.valueOf(amount);
        try {
            return c.format(Money.fromDouble(Math.abs(amount), c.decimals));
        } catch (IllegalArgumentException e) {
            return String.valueOf(amount);
        }
    }

    @Override
    public String currencyNamePlural() {
        CurrencyDef c = currency();
        return c == null ? "" : c.name;
    }

    @Override
    public String currencyNameSingular() {
        return currencyNamePlural();
    }

    // ---------------------------------------------------------------- 账户

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String name) {
        return account(name) != null;
    }

    @Override
    public boolean hasAccount(String name, String world) {
        return hasAccount(name);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String name) {
        return account(name) != null;
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        return createPlayerAccount(name);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return true;
    }

    // ---------------------------------------------------------------- 余额

    @Override
    public double getBalance(OfflinePlayer player) {
        CurrencyDef c = currency();
        EconomyService s = plugin.serviceOrNull();
        if (c == null || s == null) return 0;
        Long cached = s.cached(player.getUniqueId(), c);
        if (cached != null) return Money.toDouble(cached, c.decimals);
        try {
            return Money.toDouble(await(s.balance(c, account(player))), c.decimals);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public double getBalance(String name) {
        Account acc = account(name);
        return acc == null ? 0 : getBalance(Bukkit.getOfflinePlayer(acc.uuid));
    }

    @Override
    public double getBalance(String name, String world) {
        return getBalance(name);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String name, double amount) {
        return getBalance(name) >= amount;
    }

    @Override
    public boolean has(String name, String world, double amount) {
        return has(name, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    // ---------------------------------------------------------------- 变动

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return change(account(player), amount, false);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return change(account(name), amount, false);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return withdrawPlayer(name, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return change(account(player), amount, true);
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        return change(account(name), amount, true);
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return depositPlayer(name, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse change(Account acc, double amount, boolean deposit) {
        CurrencyDef c = currency();
        EconomyService s = plugin.serviceOrNull();
        if (c == null || s == null) return fail(0, "经济服务不可用");
        if (acc == null) return fail(0, "找不到玩家");
        long minor;
        try {
            minor = Money.fromDouble(amount, c.decimals);
        } catch (IllegalArgumentException e) {
            return fail(0, "金额不能为负数");
        }
        if (minor == 0) return new EconomyResponse(0, getBalance(Bukkit.getOfflinePlayer(acc.uuid)), EconomyResponse.ResponseType.SUCCESS, null);
        try {
            Change result =
                    await(
                            deposit
                                    ? s.deposit(c, acc, minor, Record.Type.VAULT_DEPOSIT, "Vault")
                                    : s.withdraw(c, acc, minor, Record.Type.VAULT_WITHDRAW, "Vault"));
            double balance = Money.toDouble(result.balance, c.decimals);
            if (result.ok()) return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
            return fail(balance, result.status == Change.Status.INSUFFICIENT ? "余额不足" : "超过余额上限");
        } catch (Exception e) {
            plugin.getLogger().warning("Vault " + (deposit ? "存款" : "扣款") + "失败：" + EconomyService.rootMessage(e));
            return fail(0, "数据库繁忙，请稍后再试");
        }
    }

    private static EconomyResponse fail(double balance, String message) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    // ---------------------------------------------------------------- 银行（不支持）

    private static EconomyResponse noBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "CaterEconomy 不支持银行");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBank();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBank();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBank();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBank();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }
}
