package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import cn.miaomc.catereconomy.core.Money;
import cn.miaomc.catereconomy.core.Record;
import cn.miaomc.catereconomy.core.SqlStorage;
import cn.miaomc.catereconomy.core.StorageDef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 从 GemsEconomy 的 MySQL 数据导入余额：
 * /catereconomy import gemseconomy &lt;GemsEconomy货币名&gt; &lt;目标货币&gt; [confirm]
 *
 * <p>不带 confirm 时只统计不写入；带 confirm 时把目标货币的余额覆盖为 GemsEconomy 中的值，并写入 IMPORT 流水。
 */
final class Importer {
    private Importer() {}

    static void run(CaterEconomyBukkit plugin, CommandSender sender, String[] args) {
        EconomyService s = plugin.serviceOrNull();
        if (s == null) {
            sender.sendMessage("§c[CaterEconomy] 插件未正常启动。");
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("gemseconomy")) {
            sender.sendMessage("§c用法：/catereconomy import gemseconomy <GemsEconomy货币名> <目标货币> [confirm]");
            return;
        }
        String gemsCurrency = args[2];
        CurrencyDef target = s.currency(args[3]);
        boolean write = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
        if (target == null) {
            sender.sendMessage("§c目标货币 " + args[3] + " 不存在或不可用。");
            return;
        }
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("import.gemseconomy");
        if (c == null) {
            sender.sendMessage("§c请先在 config.yml 的 import.gemseconomy 填写 GemsEconomy 数据库信息。");
            return;
        }
        String prefix = c.getString("table-prefix", "gemseconomy");
        if (!prefix.matches("[A-Za-z0-9_]{1,32}")) {
            sender.sendMessage("§cimport.gemseconomy.table-prefix 格式错误。");
            return;
        }
        StorageDef def =
                StorageDef.mysql(
                        "gemseconomy-import",
                        c.getString("host", "127.0.0.1"),
                        c.getInt("port", 3306),
                        c.getString("database", ""),
                        c.getString("username", ""),
                        c.getString("password", ""),
                        c.getString("properties", "useSSL=false&characterEncoding=utf8"),
                        1,
                        "unused_");
        SqlStorage source = new SqlStorage(def);
        SqlStorage dest = s.storage(target.storage);
        sender.sendMessage("§e[CaterEconomy] 正在读取 GemsEconomy 数据" + (write ? "并写入 " + target.name : "（预览，不写入）") + "……");
        s.submit(
                        source,
                        () -> {
                            source.connect();
                            return read(source, prefix, gemsCurrency, target);
                        })
                .thenCompose(
                        rows -> {
                            if (!write) return java.util.concurrent.CompletableFuture.completedFuture(new long[] {rows.size(), sum(rows), 0});
                            return s.submit(
                                    dest,
                                    () -> {
                                        long written = 0;
                                        for (Row row : rows) {
                                            dest.set(target, row.account, row.amount, Record.Type.IMPORT, s.server(), "IMPORT");
                                            written++;
                                        }
                                        return new long[] {rows.size(), sum(rows), written};
                                    });
                        })
                .whenComplete(
                        (result, error) ->
                                plugin.getServer()
                                        .getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    // 来源线程此时已空闲，关闭不会阻塞主线程
                                                    source.close();
                                                    if (error != null) {
                                                        sender.sendMessage("§c[CaterEconomy] 导入失败：" + EconomyService.rootMessage(error));
                                                        return;
                                                    }
                                                    sender.sendMessage(
                                                            "§a[CaterEconomy] 找到 " + result[0] + " 个账户，合计 " + target.format(result[1])
                                                                    + (write ? "，已写入 " + result[2] + " 个。" : "。确认无误后在命令末尾加 confirm 执行写入。"));
                                                }));
    }

    private static final class Row {
        final Account account;
        final long amount;

        Row(Account account, long amount) {
            this.account = account;
            this.amount = amount;
        }
    }

    private static List<Row> read(SqlStorage source, String prefix, String gemsCurrency, CurrencyDef target) throws Exception {
        return source.work(
                c -> {
                    String currencyUuid = null;
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT uuid FROM `" + prefix + "_currencies` WHERE LOWER(name_singular) = LOWER(?) OR LOWER(name_plural) = LOWER(?) LIMIT 1")) {
                        ps.setString(1, gemsCurrency);
                        ps.setString(2, gemsCurrency);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) currencyUuid = rs.getString(1);
                        }
                    }
                    if (currencyUuid == null) throw new java.sql.SQLException("GemsEconomy 中找不到货币 " + gemsCurrency);
                    List<Row> rows = new ArrayList<Row>();
                    try (PreparedStatement ps = c.prepareStatement("SELECT nickname, uuid, balance_data FROM `" + prefix + "_accounts`");
                            ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String name = rs.getString(1);
                            String json = rs.getString(3);
                            if (name == null || json == null || json.isEmpty()) continue;
                            JsonElement parsed = new JsonParser().parse(json);
                            if (!parsed.isJsonObject()) continue;
                            JsonObject obj = parsed.getAsJsonObject();
                            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                                if (!e.getKey().equalsIgnoreCase(currencyUuid)) continue;
                                double value = e.getValue().getAsDouble();
                                if (value <= 0) break;
                                UUID uuid;
                                try {
                                    uuid = UUID.fromString(rs.getString(2));
                                } catch (IllegalArgumentException ex) {
                                    uuid = Account.offlineUuid(name);
                                }
                                rows.add(new Row(new Account(uuid, name), Money.fromDouble(value, target.decimals)));
                                break;
                            }
                        }
                    }
                    return rows;
                });
    }

    private static long sum(List<Row> rows) {
        long total = 0;
        for (Row r : rows) total += r.amount;
        return total;
    }
}
