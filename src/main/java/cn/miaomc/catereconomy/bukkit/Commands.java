package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.Change;
import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import cn.miaomc.catereconomy.core.Record;
import cn.miaomc.catereconomy.core.TopEntry;
import cn.miaomc.catereconomy.core.TransferRules;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * 全部命令。/eco 同时兼容两种旧格式：
 *
 * <ul>
 *   <li>Essentials：/eco give|take|set|reset &lt;玩家&gt; &lt;数量&gt;（使用默认货币）
 *   <li>GemsEconomy：/eco give|take|set &lt;玩家&gt; &lt;数量&gt; [货币]
 * </ul>
 *
 * 另外接受 /eco &lt;玩家&gt; give &lt;数量&gt;、货币写在数量前等常见写法，以及 "*" 表示全部在线玩家。
 */
final class Commands implements TabExecutor {
    private static final String[] ECO_ACTIONS = {"give", "add", "deposit", "take", "remove", "withdraw", "set", "reset"};
    private final CaterEconomyBukkit plugin;
    private final Map<UUID, Long> payCooldowns = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, PendingPay> pending = new ConcurrentHashMap<UUID, PendingPay>();

    Commands(CaterEconomyBukkit plugin) {
        this.plugin = plugin;
    }

    void forget(UUID uuid) {
        pending.remove(uuid);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("catereconomy")) return admin(sender, args);
        EconomyService s = plugin.serviceOrNull();
        if (s == null) {
            sender.sendMessage("§c[CaterEconomy] 插件未正常启动，请联系管理员查看后台。");
            return true;
        }
        Messages m = plugin.messages();
        switch (name) {
            case "money":
                money(sender, s, m, args);
                break;
            case "pay":
                pay(sender, s, m, args);
                break;
            case "eco":
                eco(sender, s, m, args);
                break;
            case "baltop":
                top(sender, s, m, args);
                break;
            case "paylog":
                log(sender, s, m, args);
                break;
            default:
                return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- /money

    private void money(CommandSender sender, EconomyService s, Messages m, String[] args) {
        if (!sender.hasPermission("catereconomy.balance")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        // /money [货币] [玩家]、/money [玩家] [货币]
        CurrencyDef only = null;
        String target = null;
        for (String a : args) {
            CurrencyDef c = s.currency(a);
            if (c != null && only == null) only = c;
            else if (target == null) target = a;
            else {
                sender.sendMessage(m.get("usage-money"));
                return;
            }
        }
        if (target != null && !sender.hasPermission("catereconomy.balance.others")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        if (target == null && !(sender instanceof Player)) {
            sender.sendMessage(m.get("usage-money"));
            return;
        }
        List<CurrencyDef> scope = only == null ? s.currencies() : Collections.singletonList(only);
        String lookup = target == null ? sender.getName() : target;
        s.onMain(
                s.resolve(lookup, false).thenCompose(acc -> acc == null ? CompletableFuture.completedFuture(null) : balances(s, acc, scope)),
                (result, error) -> {
                    if (error != null) {
                        dbError(sender, m, error);
                        return;
                    }
                    if (result == null) {
                        sender.sendMessage(m.get("player-not-found", "player", lookup));
                        return;
                    }
                    sender.sendMessage(m.get("balance-header", "player", result.account.name));
                    for (int i = 0; i < scope.size(); i++)
                        sender.sendMessage(m.raw("balance-line", "currency", scope.get(i).name, "balance", scope.get(i).format(result.values.get(i))));
                });
    }

    private static final class Balances {
        final Account account;
        final List<Long> values;

        Balances(Account account, List<Long> values) {
            this.account = account;
            this.values = values;
        }
    }

    private CompletableFuture<Balances> balances(EconomyService s, Account acc, List<CurrencyDef> scope) {
        List<CompletableFuture<Long>> parts = new ArrayList<CompletableFuture<Long>>();
        for (CurrencyDef c : scope) parts.add(s.balance(c, acc));
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .thenApply(
                        v -> {
                            List<Long> values = new ArrayList<Long>();
                            for (CompletableFuture<Long> f : parts) values.add(f.join());
                            return new Balances(acc, values);
                        });
    }

    // ---------------------------------------------------------------- /pay

    private void pay(CommandSender sender, EconomyService s, Messages m, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(m.get("player-only"));
            return;
        }
        Player player = (Player) sender;
        if (!sender.hasPermission("catereconomy.pay")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            PendingPay p = pending.remove(player.getUniqueId());
            if (p == null || p.expires < System.currentTimeMillis()) {
                sender.sendMessage(m.get("pay-confirm-none"));
                return;
            }
            doPay(player, s, m, p.currency, p.target, p.amount);
            return;
        }
        if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("limit")) {
            payLimit(player, s, m, args.length == 2 ? args[1] : null);
            return;
        }
        // /pay <玩家> <数量> [货币]、/pay <玩家> <货币> <数量>
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(m.get("usage-pay"));
            return;
        }
        String targetName = args[0];
        String amountText = args[1];
        String currencyText = args.length == 3 ? args[2] : null;
        if (currencyText != null && s.currency(args[1]) != null && s.currency(args[2]) == null) {
            currencyText = args[1];
            amountText = args[2];
        }
        CurrencyDef cur = currencyText == null ? s.defaultCurrency() : s.currency(currencyText);
        if (cur == null) {
            unknownCurrency(sender, s, m, currencyText);
            return;
        }
        // 每种货币可用 catereconomy.pay.<id> 单独禁止转账（显式设为 false 时生效）
        if (!cur.payable || denied(sender, "catereconomy.pay." + cur.id)) {
            sender.sendMessage(m.get("pay-disabled", "currency", cur.name));
            return;
        }
        long amount;
        try {
            amount = cur.parse(amountText);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(m.get("invalid-amount", "decimals", cur.decimals));
            return;
        }
        if (amount <= 0 || amount < cur.payMin) {
            sender.sendMessage(m.get("pay-too-small", "min", cur.format(Math.max(cur.payMin, 1))));
            return;
        }
        if (cur.payMax > 0 && amount > cur.payMax) {
            sender.sendMessage(m.get("pay-too-large", "max", cur.format(cur.payMax)));
            return;
        }
        if (targetName.equalsIgnoreCase(player.getName())) {
            sender.sendMessage(m.get("pay-self"));
            return;
        }
        long[] split = cur.split(amount, sender.hasPermission("catereconomy.bypass.tax"));
        if (split[1] <= 0) {
            sender.sendMessage(m.get("pay-too-small-after-tax", "tax", cur.taxPercent.stripTrailingZeros().toPlainString()));
            return;
        }
        if (cur.payCooldownSeconds > 0 && !sender.hasPermission("catereconomy.bypass.cooldown")) {
            Long last = payCooldowns.get(player.getUniqueId());
            long left = last == null ? 0 : last + cur.payCooldownSeconds * 1000L - System.currentTimeMillis();
            if (left > 0) {
                sender.sendMessage(m.get("pay-cooldown", "seconds", (left + 999) / 1000));
                return;
            }
        }
        long finalAmount = amount;
        s.onMain(
                s.resolve(targetName, false),
                (target, error) -> {
                    if (error != null) {
                        dbError(sender, m, error);
                        return;
                    }
                    if (target == null) {
                        sender.sendMessage(m.get("player-not-found", "player", targetName));
                        return;
                    }
                    if (target.uuid.equals(player.getUniqueId())) {
                        sender.sendMessage(m.get("pay-self"));
                        return;
                    }
                    Long threshold = plugin.settings().confirmAbove.get(cur.id);
                    if (threshold != null && threshold > 0 && finalAmount >= threshold) {
                        pending.put(
                                player.getUniqueId(),
                                new PendingPay(cur, target, finalAmount, System.currentTimeMillis() + plugin.settings().confirmSeconds * 1000L));
                        sender.sendMessage(
                                m.get(
                                        "pay-confirm",
                                        "player",
                                        target.name,
                                        "cost",
                                        cur.format(split[0]),
                                        "received",
                                        cur.format(split[1]),
                                        "tax",
                                        cur.format(split[2]),
                                        "seconds",
                                        plugin.settings().confirmSeconds));
                        return;
                    }
                    doPay(player, s, m, cur, target, finalAmount);
                });
    }

    private void doPay(Player player, EconomyService s, Messages m, CurrencyDef cur, Account target, long amount) {
        long[] split = cur.split(amount, player.hasPermission("catereconomy.bypass.tax"));
        Settings st = plugin.settings();
        String ip =
                cur.sameIpEnabled && !player.hasPermission("catereconomy.bypass.sameip") ? plugin.ipOf(player) : null;
        TransferRules rules =
                new TransferRules(st.today(), cur.dailyReceiveLimit, ip, st.ipSince(), cur.sameIpMaxCount, cur.sameIpMaxAmount);
        payCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        Account from = new Account(player.getUniqueId(), player.getName());
        s.onMain(
                s.transfer(cur, from, target, split[1], split[2], rules),
                (change, error) -> {
                    if (error != null) {
                        payCooldowns.remove(player.getUniqueId());
                        dbError(player, m, error);
                        return;
                    }
                    if (change.status != Change.Status.OK) payCooldowns.remove(player.getUniqueId());
                    switch (change.status) {
                        case OK:
                            player.sendMessage(
                                    m.get(
                                            "pay-sent",
                                            "player",
                                            target.name,
                                            "cost",
                                            cur.format(split[0]),
                                            "received",
                                            cur.format(split[1]),
                                            "tax",
                                            cur.format(split[2]),
                                            "balance",
                                            cur.format(change.balance)));
                            Player receiver = Bukkit.getPlayer(target.uuid);
                            if (receiver != null)
                                receiver.sendMessage(
                                        m.get("pay-received", "player", player.getName(), "amount", cur.format(split[1]), "balance", cur.format(change.otherBalance)));
                            break;
                        case INSUFFICIENT:
                            player.sendMessage(
                                    m.get("insufficient", "currency", cur.name, "need", cur.format(split[0]), "balance", cur.format(change.balance)));
                            break;
                        case MAX_EXCEEDED:
                            player.sendMessage(m.get("target-max", "player", target.name, "currency", cur.name));
                            break;
                        case RECEIVE_LIMIT:
                            player.sendMessage(
                                    m.get(
                                            "pay-receive-limit",
                                            "player",
                                            target.name,
                                            "limit",
                                            cur.format(cur.dailyReceiveLimit),
                                            "remaining",
                                            cur.format(change.remaining)));
                            break;
                        case SAME_IP_BLOCKED:
                            player.sendMessage(m.get("pay-same-ip-blocked", "player", target.name));
                            break;
                        case SAME_IP_COUNT:
                            player.sendMessage(m.get("pay-same-ip-count", "player", target.name, "limit", cur.sameIpMaxCount));
                            break;
                        case SAME_IP_AMOUNT:
                            player.sendMessage(
                                    m.get(
                                            "pay-same-ip-amount",
                                            "player",
                                            target.name,
                                            "limit",
                                            cur.format(cur.sameIpMaxAmount),
                                            "remaining",
                                            cur.format(change.remaining)));
                            break;
                        default:
                            player.sendMessage(m.get("pay-self"));
                    }
                });
    }

    /** /pay limit [货币]：查看自己今天已接收的转账和剩余额度，以及税率。 */
    private void payLimit(Player player, EconomyService s, Messages m, String key) {
        CurrencyDef cur = key == null ? s.defaultCurrency() : s.currency(key);
        if (cur == null) {
            unknownCurrency(player, s, m, key);
            return;
        }
        String tax = cur.taxPercent.stripTrailingZeros().toPlainString();
        String mode = m.raw(cur.taxPaidBySender ? "tax-mode-sender" : "tax-mode-receiver");
        if (cur.dailyReceiveLimit == CurrencyDef.UNLIMITED) {
            player.sendMessage(m.get("pay-limit-unlimited", "currency", cur.name, "tax", tax, "mode", mode));
            return;
        }
        s.onMain(
                s.receivedToday(cur, player.getUniqueId(), plugin.settings().today()),
                (received, error) -> {
                    if (error != null) {
                        dbError(player, m, error);
                        return;
                    }
                    player.sendMessage(
                            m.get(
                                    "pay-limit",
                                    "currency",
                                    cur.name,
                                    "received",
                                    cur.format(received),
                                    "limit",
                                    cur.format(cur.dailyReceiveLimit),
                                    "remaining",
                                    cur.format(Math.max(0, cur.dailyReceiveLimit - received)),
                                    "tax",
                                    tax,
                                    "mode",
                                    mode));
                });
    }

    private static final class PendingPay {
        final CurrencyDef currency;
        final Account target;
        final long amount;
        final long expires;

        PendingPay(CurrencyDef currency, Account target, long amount, long expires) {
            this.currency = currency;
            this.target = target;
            this.amount = amount;
            this.expires = expires;
        }
    }

    // ---------------------------------------------------------------- /eco

    private void eco(CommandSender sender, EconomyService s, Messages m, String[] args) {
        if (!sender.hasPermission("catereconomy.admin.eco")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(m.get("usage-eco"));
            return;
        }
        String action;
        String target;
        List<String> rest = new ArrayList<String>();
        if (isEcoAction(args[0])) {
            action = args[0];
            target = args[1];
            for (int i = 2; i < args.length; i++) rest.add(args[i]);
        } else if (isEcoAction(args[1])) {
            // /eco <玩家> give <数量> [货币]
            action = args[1];
            target = args[0];
            for (int i = 2; i < args.length; i++) rest.add(args[i]);
        } else {
            sender.sendMessage(m.get("usage-eco"));
            return;
        }
        action = normalize(action);
        CurrencyDef cur = null;
        String amountText = null;
        String currencyText = null;
        for (String part : rest) {
            CurrencyDef c = s.currency(part);
            if (c != null && cur == null && (amountText != null || !looksLikeNumber(part))) {
                cur = c;
                currencyText = part;
            } else if (amountText == null) amountText = part;
            else if (currencyText == null) currencyText = part;
            else {
                sender.sendMessage(m.get("usage-eco"));
                return;
            }
        }
        if (cur == null && currencyText != null) {
            unknownCurrency(sender, s, m, currencyText);
            return;
        }
        if (cur == null) cur = s.defaultCurrency();
        if (cur == null) {
            sender.sendMessage(m.get("no-default-currency"));
            return;
        }
        long amount = -1;
        if (!action.equals("reset")) {
            if (amountText == null) {
                sender.sendMessage(m.get("usage-eco"));
                return;
            }
            try {
                amount = cur.parse(amountText);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(m.get("invalid-amount", "decimals", cur.decimals));
                return;
            }
            if (amount <= 0 && !action.equals("set")) {
                sender.sendMessage(m.get("invalid-amount", "decimals", cur.decimals));
                return;
            }
        }
        if (denied(sender, "catereconomy.admin.eco." + cur.id)) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        boolean create = !plugin.settings().onlineUuid;
        List<String> targets = new ArrayList<String>();
        if (target.equals("*") || target.equals("**")) {
            for (Player p : Bukkit.getOnlinePlayers()) targets.add(p.getName());
        } else targets.add(target);
        boolean quiet = targets.size() > 1;
        if (quiet) sender.sendMessage(m.get("eco-bulk", "count", targets.size(), "action", action, "currency", cur.name));
        for (String t : targets) ecoOne(sender, s, m, action, t, cur, amount, create, quiet);
    }

    private void ecoOne(
            CommandSender sender,
            EconomyService s,
            Messages m,
            String action,
            String targetName,
            CurrencyDef cur,
            long amount,
            boolean create,
            boolean quiet) {
        String actor = sender instanceof ConsoleCommandSender ? "CONSOLE" : sender.getName();
        s.onMain(
                s.resolve(targetName, create).thenCompose(acc -> acc == null ? CompletableFuture.completedFuture(null) : run(s, action, cur, acc, amount, actor).thenApply(c -> new Object[] {acc, c})),
                (result, error) -> {
                    if (error != null) {
                        dbError(sender, m, error);
                        return;
                    }
                    if (result == null) {
                        sender.sendMessage(m.get("player-not-found", "player", targetName));
                        return;
                    }
                    Account acc = (Account) result[0];
                    Change change = (Change) result[1];
                    if (change.status == Change.Status.INSUFFICIENT) {
                        sender.sendMessage(m.get("eco-insufficient", "player", acc.name, "balance", cur.format(change.balance)));
                        return;
                    }
                    if (change.status == Change.Status.MAX_EXCEEDED) {
                        sender.sendMessage(m.get("target-max", "player", acc.name, "currency", cur.name));
                        return;
                    }
                    if (!quiet)
                        sender.sendMessage(
                                    m.get(
                                            "eco-" + action,
                                            "player",
                                            acc.name,
                                            "amount",
                                            cur.format(Math.max(amount, 0)),
                                            "balance",
                                            cur.format(change.balance)));
                    Player online = Bukkit.getPlayer(acc.uuid);
                    if (online != null && online != sender && (action.equals("give") || action.equals("take")))
                        online.sendMessage(m.get("notify-" + action, "amount", cur.format(amount), "balance", cur.format(change.balance)));
                });
    }

    private CompletableFuture<Change> run(EconomyService s, String action, CurrencyDef cur, Account acc, long amount, String actor) {
        switch (action) {
            case "give":
                return s.deposit(cur, acc, amount, Record.Type.GIVE, actor);
            case "take":
                return s.withdraw(cur, acc, amount, Record.Type.TAKE, actor);
            case "set":
                return s.set(cur, acc, amount, actor);
            default:
                return s.set(cur, acc, -1, actor);
        }
    }

    private static boolean isEcoAction(String s) {
        for (String a : ECO_ACTIONS) if (a.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static String normalize(String action) {
        String a = action.toLowerCase(Locale.ROOT);
        if (a.equals("add") || a.equals("deposit")) return "give";
        if (a.equals("remove") || a.equals("withdraw")) return "take";
        return a;
    }

    private static boolean looksLikeNumber(String s) {
        return s.matches("[0-9.,]+");
    }

    // ---------------------------------------------------------------- /baltop

    private void top(CommandSender sender, EconomyService s, Messages m, String[] args) {
        if (!sender.hasPermission("catereconomy.top")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        CurrencyDef cur = s.defaultCurrency();
        int page = 1;
        for (String a : args) {
            if (a.matches("\\d{1,4}")) page = Math.max(1, Integer.parseInt(a));
            else {
                cur = s.currency(a);
                if (cur == null) {
                    unknownCurrency(sender, s, m, a);
                    return;
                }
            }
        }
        if (cur == null) {
            sender.sendMessage(m.get("no-default-currency"));
            return;
        }
        int size = plugin.settings().pageSize;
        int offset = (page - 1) * size;
        CurrencyDef c = cur;
        int p = page;
        s.onMain(
                s.top(cur, size, offset),
                (list, error) -> {
                    if (error != null) {
                        dbError(sender, m, error);
                        return;
                    }
                    sender.sendMessage(m.get("top-header", "currency", c.name, "page", p));
                    if (list.isEmpty()) sender.sendMessage(m.raw("top-empty"));
                    for (int i = 0; i < list.size(); i++) {
                        TopEntry e = list.get(i);
                        sender.sendMessage(m.raw("top-line", "rank", offset + i + 1, "player", e.name, "balance", c.format(e.balance)));
                    }
                });
    }

    // ---------------------------------------------------------------- /paylog

    private void log(CommandSender sender, EconomyService s, Messages m, String[] args) {
        // /paylog [玩家] [货币|all] [页码]；all 表示包含管理员调整在内的全部流水
        if (!sender.hasPermission("catereconomy.log")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        String target = null;
        CurrencyDef only = null;
        boolean all = false;
        int page = 1;
        for (String a : args) {
            if (a.matches("\\d{1,4}")) page = Math.max(1, Integer.parseInt(a));
            else if (a.equalsIgnoreCase("all")) all = true;
            else if (s.currency(a) != null && only == null) only = s.currency(a);
            else if (target == null) target = a;
            else {
                sender.sendMessage(m.get("usage-paylog"));
                return;
            }
        }
        if (target != null && !target.equalsIgnoreCase(sender.getName()) && !sender.hasPermission("catereconomy.log.others")) {
            sender.sendMessage(m.get("no-permission"));
            return;
        }
        if (target == null && !(sender instanceof Player)) {
            sender.sendMessage(m.get("usage-paylog"));
            return;
        }
        boolean payOnly = !all;
        List<CurrencyDef> scope = only == null ? s.currencies() : Collections.singletonList(only);
        int size = plugin.settings().pageSize;
        int offset = (page - 1) * size;
        String lookup = target == null ? sender.getName() : target;
        int p = page;
        s.onMain(
                s.resolve(lookup, false).thenCompose(acc -> acc == null ? CompletableFuture.completedFuture(null) : s.history(acc.uuid, scope, payOnly, size, offset).thenApply(list -> new Object[] {acc, list})),
                (result, error) -> {
                    if (error != null) {
                        dbError(sender, m, error);
                        return;
                    }
                    if (result == null) {
                        sender.sendMessage(m.get("player-not-found", "player", lookup));
                        return;
                    }
                    Account acc = (Account) result[0];
                    @SuppressWarnings("unchecked")
                    List<Record> list = (List<Record>) result[1];
                    sender.sendMessage(m.get(payOnly ? "log-header-pay" : "log-header-all", "player", acc.name, "page", p));
                    if (list.isEmpty()) sender.sendMessage(m.raw("log-empty"));
                    SimpleDateFormat fmt = new SimpleDateFormat(m.raw("date-format"));
                    for (Record r : list) sender.sendMessage(describe(s, m, acc, r, fmt));
                });
    }

    private String describe(EconomyService s, Messages m, Account viewer, Record r, SimpleDateFormat fmt) {
        CurrencyDef cur = s.configured(r.currency);
        String amount = cur == null ? String.valueOf(r.amount) : cur.format(r.amount);
        String time = fmt.format(new Date(r.time));
        if (r.type == Record.Type.PAY) {
            boolean outgoing = viewer.uuid.equals(r.uuid);
            String fee = cur == null || r.fee == 0 ? "" : m.raw("log-fee", "fee", cur.format(r.fee));
            long balance = outgoing ? r.balanceAfter : r.otherBalanceAfter;
            String cost = cur == null ? String.valueOf(r.amount + r.fee) : cur.format(r.amount + r.fee);
            return m.raw(
                    outgoing ? "log-pay-out" : "log-pay-in",
                    "time",
                    time,
                    "player",
                    outgoing ? r.otherName : r.name,
                    "amount",
                    amount,
                    "cost",
                    cost,
                    "fee",
                    fee,
                    "balance",
                    cur == null ? String.valueOf(balance) : cur.format(balance),
                    "server",
                    r.server);
        }
        return m.raw(
                "log-" + r.type.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                "time",
                time,
                "amount",
                amount,
                "balance",
                cur == null ? String.valueOf(r.balanceAfter) : cur.format(r.balanceAfter),
                "actor",
                r.actor,
                "server",
                r.server);
    }

    // ---------------------------------------------------------------- /catereconomy

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("catereconomy.admin")) {
            Messages m = plugin.messages();
            sender.sendMessage(m == null ? "§c你没有权限。" : m.get("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            boolean ok = plugin.load();
            sender.sendMessage(ok ? plugin.messages().get("reload-success") : "§c[CaterEconomy] 重载失败，请查看后台错误信息。");
            return true;
        }
        if (sub.equals("info")) {
            EconomyService s = plugin.serviceOrNull();
            if (s == null) {
                sender.sendMessage("§c[CaterEconomy] 插件未正常启动。");
                return true;
            }
            sender.sendMessage("§6[CaterEconomy] 服务器名：§f" + s.server());
            for (cn.miaomc.catereconomy.core.SqlStorage st : s.storages())
                sender.sendMessage(
                        "§7- 存储 §f" + st.def().id + " §7" + st.def().type.name().toLowerCase(Locale.ROOT) + " "
                                + (s.storageFailed(st.def().id) ? "§c不可用" : "§a正常"));
            for (CurrencyDef c : plugin.settings().currencies)
                sender.sendMessage(
                        "§7- 货币 §f" + c.id + " §7(" + c.name + ") 存储 §f" + c.storage + " §7小数 " + c.decimals
                                + " 转账 " + (c.payable ? "§a开" : "§c关") + "§7 税 " + c.taxPercent.stripTrailingZeros().toPlainString()
                                + "% 每日接收上限 " + (c.dailyReceiveLimit == CurrencyDef.UNLIMITED ? "不限" : c.amount(c.dailyReceiveLimit))
                                + " 同IP " + (c.sameIpEnabled ? "§a开" : "§c关") + (s.available(c) ? "" : " §c[不可用]"));
            return true;
        }
        if (sub.equals("import")) {
            Importer.run(plugin, sender, args);
            return true;
        }
        sender.sendMessage("§6[CaterEconomy] §f/catereconomy reload §7重载配置");
        sender.sendMessage("§6[CaterEconomy] §f/catereconomy info §7查看货币与存储状态");
        sender.sendMessage("§6[CaterEconomy] §f/catereconomy import gemseconomy <GemsEconomy货币名> <目标货币> §7从 GemsEconomy 数据库导入");
        return true;
    }

    // ---------------------------------------------------------------- 工具

    private static boolean denied(CommandSender sender, String node) {
        return sender.isPermissionSet(node) && !sender.hasPermission(node);
    }

    private void unknownCurrency(CommandSender sender, EconomyService s, Messages m, String key) {
        CurrencyDef c = s.configured(key);
        if (c != null) sender.sendMessage(m.get("currency-unavailable", "currency", c.name));
        else {
            StringBuilder names = new StringBuilder();
            for (CurrencyDef d : s.currencies()) names.append(names.length() == 0 ? "" : "、").append(d.name);
            sender.sendMessage(m.get("unknown-currency", "currency", key == null ? "" : key, "list", names));
        }
    }

    private void dbError(CommandSender sender, Messages m, Throwable error) {
        plugin.getLogger().warning("数据库操作失败：" + EconomyService.rootMessage(error));
        sender.sendMessage(m.get("database-error"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        EconomyService s = plugin.serviceOrNull();
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String name = command.getName().toLowerCase(Locale.ROOT);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<String>();
        if (name.equals("eco") && args.length == 1) Collections.addAll(options, "give", "take", "set", "reset");
        else if (name.equals("catereconomy") && args.length == 1) Collections.addAll(options, "reload", "info", "import");
        else if ((name.equals("eco") && args.length == 2) || (name.equals("pay") && args.length == 1)) {
            if (name.equals("pay")) Collections.addAll(options, "limit", "confirm");
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if ((name.equals("eco") && args.length == 4) || (name.equals("pay") && args.length == 3) || name.equals("money") || name.equals("baltop") || name.equals("paylog")) {
            for (CurrencyDef c : s.currencies()) {
                if (name.equals("pay") && !c.payable) continue;
                options.add(c.name);
            }
        }
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
