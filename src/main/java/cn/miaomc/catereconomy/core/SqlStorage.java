package cn.miaomc.catereconomy.core;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个存储后端。所有方法都是阻塞的，只能在 {@link #executor()} 线程中调用。
 *
 * <p>余额变动全部使用带条件的 UPDATE（如 balance &gt;= ?），由数据库保证多个子服同时操作时不会扣成负数、不会超过上限。
 * SQL 只使用 MySQL 与 2010 年以来所有 SQLite 版本都支持的语法，以兼容 1.8.8 服务端自带的旧 SQLite 驱动。
 */
public final class SqlStorage {
    private static final String[] MYSQL_DRIVERS = {"cn.miaomc.catereconomy.lib.mysql.cj.jdbc.Driver", "com.mysql.cj.jdbc.Driver"};
    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final int MAX_RETRIES = 3;

    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final StorageDef def;
    private final String balances;
    private final String records;
    /** 每日计数：收款人当日接收额、同 IP 当日转账次数和总额。日期变化即自动重置。 */
    private final String daily;
    /** 玩家用过的 IP，用于判断两个账号是否同 IP。 */
    private final String ips;
    private final ExecutorService executor;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private Driver driver;
    private volatile boolean closed;

    public SqlStorage(StorageDef def) {
        this.def = def;
        this.balances = def.tablePrefix + "balances";
        this.records = def.tablePrefix + "records";
        this.daily = def.tablePrefix + "daily";
        this.ips = def.tablePrefix + "ips";
        int threads = def.type == StorageDef.Type.SQLITE ? 1 : Math.max(1, def.poolSize);
        AtomicInteger counter = new AtomicInteger();
        this.executor =
                Executors.newFixedThreadPool(
                        threads,
                        r -> {
                            Thread t = new Thread(r, "CaterEconomy-" + def.id + '-' + counter.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });
        this.idle = new ArrayBlockingQueue<Connection>(threads);
    }

    public StorageDef def() {
        return def;
    }

    public ExecutorService executor() {
        return executor;
    }

    /** 只加载驱动并测试连接，不建表（用于读取其他插件的数据库）。 */
    public void connect() throws SQLException {
        driver = loadDriver();
        work(c -> null);
    }

    /** 加载驱动并建表；失败时抛出异常，调用方应将该存储下的货币标记为不可用。 */
    public void start() throws SQLException {
        driver = loadDriver();
        if (def.type == StorageDef.Type.SQLITE) {
            java.io.File parent = def.file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new SQLException("无法创建目录 " + parent);
        }
        work(
                c -> {
                    try (Statement s = c.createStatement()) {
                        if (def.type == StorageDef.Type.MYSQL) {
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS `" + balances + "` (currency VARCHAR(32) NOT NULL, uuid CHAR(36) NOT NULL,"
                                            + " name VARCHAR(32) NOT NULL, name_lower VARCHAR(32) NOT NULL, balance BIGINT NOT NULL,"
                                            + " updated BIGINT NOT NULL, PRIMARY KEY (currency, uuid), KEY idx_name (name_lower),"
                                            + " KEY idx_top (currency, balance)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS `" + records + "` (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                                            + " time BIGINT NOT NULL, currency VARCHAR(32) NOT NULL, type VARCHAR(16) NOT NULL,"
                                            + " uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, other_uuid CHAR(36) NULL,"
                                            + " other_name VARCHAR(32) NULL, amount BIGINT NOT NULL, fee BIGINT NOT NULL,"
                                            + " balance_after BIGINT NOT NULL, other_balance_after BIGINT NOT NULL,"
                                            + " server VARCHAR(32) NOT NULL, actor VARCHAR(32) NOT NULL,"
                                            + " KEY idx_uuid (uuid, id), KEY idx_other (other_uuid, id), KEY idx_time (time))"
                                            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS `" + daily + "` (currency VARCHAR(32) NOT NULL, scope VARCHAR(80) NOT NULL,"
                                            + " day INT NOT NULL, total BIGINT NOT NULL, times INT NOT NULL,"
                                            + " PRIMARY KEY (currency, scope, day), KEY idx_day (day)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS `" + ips + "` (uuid CHAR(36) NOT NULL, ip VARCHAR(45) NOT NULL,"
                                            + " seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip), KEY idx_ip (ip)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                        } else {
                            s.execute("PRAGMA journal_mode=WAL");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS " + balances + " (currency VARCHAR(32) NOT NULL, uuid CHAR(36) NOT NULL,"
                                            + " name VARCHAR(32) NOT NULL, name_lower VARCHAR(32) NOT NULL, balance BIGINT NOT NULL,"
                                            + " updated BIGINT NOT NULL, PRIMARY KEY (currency, uuid))");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + balances + "_name ON " + balances + " (name_lower)");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + balances + "_top ON " + balances + " (currency, balance)");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS " + records + " (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                            + " time BIGINT NOT NULL, currency VARCHAR(32) NOT NULL, type VARCHAR(16) NOT NULL,"
                                            + " uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, other_uuid CHAR(36) NULL,"
                                            + " other_name VARCHAR(32) NULL, amount BIGINT NOT NULL, fee BIGINT NOT NULL,"
                                            + " balance_after BIGINT NOT NULL, other_balance_after BIGINT NOT NULL,"
                                            + " server VARCHAR(32) NOT NULL, actor VARCHAR(32) NOT NULL)");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + records + "_uuid ON " + records + " (uuid, id)");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + records + "_other ON " + records + " (other_uuid, id)");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + records + "_time ON " + records + " (time)");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS " + daily + " (currency VARCHAR(32) NOT NULL, scope VARCHAR(80) NOT NULL,"
                                            + " day INT NOT NULL, total BIGINT NOT NULL, times INT NOT NULL, PRIMARY KEY (currency, scope, day))");
                            s.executeUpdate(
                                    "CREATE TABLE IF NOT EXISTS " + ips + " (uuid CHAR(36) NOT NULL, ip VARCHAR(45) NOT NULL,"
                                            + " seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip))");
                            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + ips + "_ip ON " + ips + " (ip)");
                        }
                    }
                    return null;
                });
    }

    public void close() {
        closed = true;
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Connection c;
        while ((c = idle.poll()) != null) closeQuietly(c);
    }

    // ---------------------------------------------------------------- 查询

    /** 返回该玩家在指定货币中的余额；没有账户的货币不出现在结果中。 */
    public Map<String, Long> balances(UUID uuid, Collection<String> currencies) throws SQLException {
        Map<UUID, Map<String, Long>> all = balances(java.util.Collections.singletonList(uuid), currencies);
        Map<String, Long> out = all.get(uuid);
        return out == null ? new HashMap<String, Long>() : out;
    }

    public Map<UUID, Map<String, Long>> balances(Collection<UUID> uuids, Collection<String> currencies) throws SQLException {
        Map<UUID, Map<String, Long>> out = new HashMap<UUID, Map<String, Long>>();
        if (uuids.isEmpty() || currencies.isEmpty()) return out;
        List<UUID> list = new ArrayList<UUID>(uuids);
        for (int from = 0; from < list.size(); from += 200) {
            List<UUID> part = list.subList(from, Math.min(list.size(), from + 200));
            String sql =
                    "SELECT uuid, currency, balance FROM " + q(balances) + " WHERE uuid IN (" + marks(part.size())
                            + ") AND currency IN (" + marks(currencies.size()) + ")";
            work(
                    c -> {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            int i = 1;
                            for (UUID u : part) ps.setString(i++, u.toString());
                            for (String cur : currencies) ps.setString(i++, cur);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    UUID u = UUID.fromString(rs.getString(1));
                                    Map<String, Long> m = out.get(u);
                                    if (m == null) out.put(u, m = new HashMap<String, Long>());
                                    m.put(rs.getString(2), rs.getLong(3));
                                }
                            }
                        }
                        return null;
                    });
        }
        return out;
    }

    /** 按名字（不区分大小写）查找最近更新的账户 UUID，找不到返回 null。 */
    public Account findByName(String name) throws SQLException {
        return work(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT uuid, name FROM " + q(balances) + " WHERE name_lower = ? ORDER BY updated DESC LIMIT 1")) {
                        ps.setString(1, name.toLowerCase(java.util.Locale.ROOT));
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? new Account(UUID.fromString(rs.getString(1)), rs.getString(2)) : null;
                        }
                    }
                });
    }

    /** 玩家进服时同步名字，便于离线查找与排行显示。 */
    public void updateName(Account account) throws SQLException {
        work(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE " + q(balances) + " SET name = ?, name_lower = ? WHERE uuid = ? AND name <> ?")) {
                        ps.setString(1, account.name);
                        ps.setString(2, account.lowerName());
                        ps.setString(3, account.uuid.toString());
                        ps.setString(4, account.name);
                        ps.executeUpdate();
                    }
                    return null;
                });
    }

    public List<TopEntry> top(String currency, int limit, int offset) throws SQLException {
        return work(
                c -> {
                    List<TopEntry> out = new ArrayList<TopEntry>();
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT name, balance FROM " + q(balances)
                                            + " WHERE currency = ? AND balance > 0 ORDER BY balance DESC, name_lower ASC LIMIT ? OFFSET ?")) {
                        ps.setString(1, currency);
                        ps.setInt(2, limit);
                        ps.setInt(3, offset);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) out.add(new TopEntry(rs.getString(1), rs.getLong(2)));
                        }
                    }
                    return out;
                });
    }

    /**
     * 查询与该玩家相关的流水，按 id 倒序。currencies 限定货币；payOnly 时只返回转账。
     */
    public List<Record> history(UUID uuid, Collection<String> currencies, boolean payOnly, int limit, int offset)
            throws SQLException {
        if (currencies.isEmpty()) return new ArrayList<Record>();
        String filter = " AND currency IN (" + marks(currencies.size()) + ")" + (payOnly ? " AND type = 'PAY'" : "");
        String sql =
                "SELECT * FROM (SELECT * FROM " + q(records) + " WHERE uuid = ?" + filter + " UNION ALL SELECT * FROM "
                        + q(records) + " WHERE other_uuid = ?" + filter + ") t ORDER BY id DESC LIMIT ? OFFSET ?";
        return work(
                c -> {
                    List<Record> out = new ArrayList<Record>();
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        int i = 1;
                        for (int round = 0; round < 2; round++) {
                            ps.setString(i++, uuid.toString());
                            for (String cur : currencies) ps.setString(i++, cur);
                        }
                        ps.setInt(i++, limit);
                        ps.setInt(i, offset);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) out.add(readRecord(rs));
                        }
                    }
                    return out;
                });
    }

    public int purgeRecords(long before) throws SQLException {
        return work(
                c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + q(records) + " WHERE time < ?")) {
                        ps.setLong(1, before);
                        return ps.executeUpdate();
                    }
                });
    }

    // ---------------------------------------------------------------- 变动

    public Change deposit(CurrencyDef cur, Account acc, long amount, Record.Type type, String server, String actor)
            throws SQLException {
        return transaction(
                c -> {
                    ensure(c, cur, acc);
                    if (!add(c, cur, acc, amount)) return Change.of(Change.Status.MAX_EXCEEDED);
                    long after = read(c, cur.id, acc.uuid);
                    log(c, cur.id, type, acc, null, amount, 0, after, 0, server, actor);
                    return new Change(Change.Status.OK, after, 0, 0);
                });
    }

    public Change withdraw(CurrencyDef cur, Account acc, long amount, Record.Type type, String server, String actor)
            throws SQLException {
        return transaction(
                c -> {
                    ensure(c, cur, acc);
                    if (!subtract(c, cur, acc, amount)) return new Change(Change.Status.INSUFFICIENT, read(c, cur.id, acc.uuid), 0, 0);
                    long after = read(c, cur.id, acc.uuid);
                    log(c, cur.id, type, acc, null, amount, 0, after, 0, server, actor);
                    return new Change(Change.Status.OK, after, 0, 0);
                });
    }

    /** 直接设置余额；amount 为 -1 时重置为初始余额。 */
    public Change set(CurrencyDef cur, Account acc, long amount, Record.Type type, String server, String actor)
            throws SQLException {
        long target = amount < 0 ? cur.startBalance : amount;
        if (target > cur.effectiveMax()) return Change.of(Change.Status.MAX_EXCEEDED);
        return transaction(
                c -> {
                    ensure(c, cur, acc);
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE " + q(balances) + " SET balance = ?, name = ?, name_lower = ?, updated = ? WHERE currency = ? AND uuid = ?")) {
                        ps.setLong(1, target);
                        ps.setString(2, acc.name);
                        ps.setString(3, acc.lowerName());
                        ps.setLong(4, System.currentTimeMillis());
                        ps.setString(5, cur.id);
                        ps.setString(6, acc.uuid.toString());
                        ps.executeUpdate();
                    }
                    log(c, cur.id, type, acc, null, target, 0, target, 0, server, actor);
                    return new Change(Change.Status.OK, target, 0, 0);
                });
    }

    /**
     * 转账：付款人扣除 credit + fee，收款人收到 credit，fee 为税（销毁）。
     *
     * <p>每日接收上限与同 IP 限制都在同一个事务里用带条件的计数更新检查；任一项不满足则整体回滚，计数也不会增加。
     */
    public Change transfer(CurrencyDef cur, Account from, Account to, long credit, long fee, TransferRules rules, String server)
            throws SQLException {
        if (from.uuid.equals(to.uuid)) return Change.of(Change.Status.SELF);
        long cost;
        try {
            cost = Math.addExact(credit, fee);
        } catch (ArithmeticException e) {
            return Change.of(Change.Status.INSUFFICIENT);
        }
        return transaction(
                c -> {
                    ensure(c, cur, from);
                    ensure(c, cur, to);
                    // 固定按 UUID 顺序锁行，避免两人互相转账时 MySQL 死锁。
                    boolean senderFirst = from.uuid.toString().compareTo(to.uuid.toString()) < 0;
                    if (senderFirst) {
                        if (!subtract(c, cur, from, cost)) return new Change(Change.Status.INSUFFICIENT, read(c, cur.id, from.uuid), 0, fee);
                        if (!add(c, cur, to, credit)) return Change.of(Change.Status.MAX_EXCEEDED);
                    } else {
                        if (!add(c, cur, to, credit)) return Change.of(Change.Status.MAX_EXCEEDED);
                        if (!subtract(c, cur, from, cost)) return new Change(Change.Status.INSUFFICIENT, read(c, cur.id, from.uuid), 0, fee);
                    }
                    // 双方余额行已加锁，同一收款人的并发转账在这里排队，计数不会被同时越过。
                    if (rules.receiveLimit != CurrencyDef.UNLIMITED
                            && !count(c, cur.id, "recv:" + to.uuid, rules.day, credit, rules.receiveLimit, CurrencyDef.UNLIMITED))
                        return Change.limit(Change.Status.RECEIVE_LIMIT, rules.receiveLimit - dailyTotal(c, cur.id, "recv:" + to.uuid, rules.day)[0]);
                    if (rules.senderIp != null) {
                        remember(c, from.uuid, rules.senderIp);
                        if (sharesIp(c, from.uuid, to.uuid, rules.ipSince)) {
                            if (rules.ipMaxCount == 0) return Change.of(Change.Status.SAME_IP_BLOCKED);
                            String scope = "ip:" + rules.senderIp;
                            if (!count(c, cur.id, scope, rules.day, credit, rules.ipMaxAmount, rules.ipMaxCount)) {
                                long[] used = dailyTotal(c, cur.id, scope, rules.day);
                                if (rules.ipMaxCount != CurrencyDef.UNLIMITED && used[1] >= rules.ipMaxCount)
                                    return Change.limit(Change.Status.SAME_IP_COUNT, 0);
                                return Change.limit(Change.Status.SAME_IP_AMOUNT, rules.ipMaxAmount - used[0]);
                            }
                        }
                    }
                    long fromAfter = read(c, cur.id, from.uuid);
                    long toAfter = read(c, cur.id, to.uuid);
                    log(c, cur.id, Record.Type.PAY, from, to, credit, fee, fromAfter, toAfter, server, from.name);
                    return new Change(Change.Status.OK, fromAfter, toAfter, fee);
                });
    }

    /** 记录玩家使用的 IP（进服时调用）。 */
    public void recordIp(java.util.UUID uuid, String ip) throws SQLException {
        work(
                c -> {
                    remember(c, uuid, ip);
                    return null;
                });
    }

    /** 查询某个计数当天已用的 [金额, 次数]。 */
    public long[] daily(String currency, String scope, int day) throws SQLException {
        return work(c -> dailyTotal(c, currency, scope, day));
    }

    /** 清理早于 day 的每日计数和早于 ipBefore 的 IP 记录。 */
    public int purgeDaily(int day, long ipBefore) throws SQLException {
        return work(
                c -> {
                    int n;
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + q(daily) + " WHERE day < ?")) {
                        ps.setInt(1, day);
                        n = ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + q(ips) + " WHERE seen < ?")) {
                        ps.setLong(1, ipBefore);
                        n += ps.executeUpdate();
                    }
                    return n;
                });
    }

    /**
     * 当日计数原子加一：total + amount 不超过 maxTotal 且 times + 1 不超过 maxTimes 时才更新。
     * 两个上限都可为 {@link CurrencyDef#UNLIMITED}。
     */
    private boolean count(Connection c, String currency, String scope, int day, long amount, long maxTotal, long maxTimes)
            throws SQLException {
        long totalCeiling = maxTotal == CurrencyDef.UNLIMITED ? Long.MAX_VALUE - amount : maxTotal - amount;
        long timesCeiling = maxTimes == CurrencyDef.UNLIMITED ? Integer.MAX_VALUE - 1 : maxTimes - 1;
        if (totalCeiling < 0 || timesCeiling < 0) return false;
        String insert = def.type == StorageDef.Type.MYSQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
        try (PreparedStatement ps = c.prepareStatement(insert + q(daily) + " (currency, scope, day, total, times) VALUES (?, ?, ?, 0, 0)")) {
            ps.setString(1, currency);
            ps.setString(2, scope);
            ps.setInt(3, day);
            ps.executeUpdate();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE " + q(daily) + " SET total = total + ?, times = times + 1"
                                + " WHERE currency = ? AND scope = ? AND day = ? AND total <= ? AND times <= ?")) {
            ps.setLong(1, amount);
            ps.setString(2, currency);
            ps.setString(3, scope);
            ps.setInt(4, day);
            ps.setLong(5, totalCeiling);
            ps.setLong(6, timesCeiling);
            return ps.executeUpdate() == 1;
        }
    }

    private long[] dailyTotal(Connection c, String currency, String scope, int day) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT total, times FROM " + q(daily) + " WHERE currency = ? AND scope = ? AND day = ?")) {
            ps.setString(1, currency);
            ps.setString(2, scope);
            ps.setInt(3, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : new long[] {0, 0};
            }
        }
    }

    private void remember(Connection c, java.util.UUID uuid, String ip) throws SQLException {
        String sql =
                def.type == StorageDef.Type.MYSQL
                        ? "INSERT INTO " + q(ips) + " (uuid, ip, seen) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE seen = VALUES(seen)"
                        : "INSERT OR REPLACE INTO " + q(ips) + " (uuid, ip, seen) VALUES (?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** 两个账号在 since 之后是否用过同一个 IP。 */
    private boolean sharesIp(Connection c, java.util.UUID a, java.util.UUID b, long since) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT 1 FROM " + q(ips) + " x JOIN " + q(ips) + " y ON x.ip = y.ip"
                                + " WHERE x.uuid = ? AND y.uuid = ? AND x.seen >= ? AND y.seen >= ? LIMIT 1")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setLong(3, since);
            ps.setLong(4, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 供导入工具等直接访问数据库。 */
    public <T> T work(SqlWork<T> body) throws SQLException {
        Connection c = borrow();
        boolean broken = true;
        try {
            T result = body.run(c);
            broken = false;
            return result;
        } finally {
            release(c, broken);
        }
    }

    // ---------------------------------------------------------------- 内部

    private Change transaction(SqlWork<Change> body) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Connection c = borrow();
            boolean broken = true;
            try {
                c.setAutoCommit(false);
                Change result;
                try {
                    result = body.run(c);
                    if (result.ok()) c.commit();
                    else c.rollback();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
                broken = false;
                return result;
            } catch (SQLException e) {
                if (!retryable(e)) throw e;
                last = e;
                broken = false;
            } finally {
                release(c, broken);
            }
        }
        throw last;
    }

    private void ensure(Connection c, CurrencyDef cur, Account acc) throws SQLException {
        String insert = def.type == StorageDef.Type.MYSQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
        try (PreparedStatement ps =
                c.prepareStatement(insert + q(balances) + " (currency, uuid, name, name_lower, balance, updated) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, cur.id);
            ps.setString(2, acc.uuid.toString());
            ps.setString(3, acc.name);
            ps.setString(4, acc.lowerName());
            ps.setLong(5, cur.startBalance);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private boolean add(Connection c, CurrencyDef cur, Account acc, long amount) throws SQLException {
        long max = cur.effectiveMax();
        if (amount > max) return false;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE " + q(balances) + " SET balance = balance + ?, name = ?, name_lower = ?, updated = ?"
                                + " WHERE currency = ? AND uuid = ? AND balance <= ?")) {
            ps.setLong(1, amount);
            ps.setString(2, acc.name);
            ps.setString(3, acc.lowerName());
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, cur.id);
            ps.setString(6, acc.uuid.toString());
            ps.setLong(7, max - amount);
            return ps.executeUpdate() == 1;
        }
    }

    private boolean subtract(Connection c, CurrencyDef cur, Account acc, long amount) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE " + q(balances) + " SET balance = balance - ?, name = ?, name_lower = ?, updated = ?"
                                + " WHERE currency = ? AND uuid = ? AND balance >= ?")) {
            ps.setLong(1, amount);
            ps.setString(2, acc.name);
            ps.setString(3, acc.lowerName());
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, cur.id);
            ps.setString(6, acc.uuid.toString());
            ps.setLong(7, amount);
            return ps.executeUpdate() == 1;
        }
    }

    private long read(Connection c, String currency, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + q(balances) + " WHERE currency = ? AND uuid = ?")) {
            ps.setString(1, currency);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void log(
            Connection c,
            String currency,
            Record.Type type,
            Account acc,
            Account other,
            long amount,
            long fee,
            long after,
            long otherAfter,
            String server,
            String actor)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO " + q(records) + " (time, currency, type, uuid, name, other_uuid, other_name, amount, fee,"
                                + " balance_after, other_balance_after, server, actor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, currency);
            ps.setString(3, type.name());
            ps.setString(4, acc.uuid.toString());
            ps.setString(5, acc.name);
            ps.setString(6, other == null ? null : other.uuid.toString());
            ps.setString(7, other == null ? null : other.name);
            ps.setLong(8, amount);
            ps.setLong(9, fee);
            ps.setLong(10, after);
            ps.setLong(11, otherAfter);
            ps.setString(12, cut(server));
            ps.setString(13, cut(actor));
            ps.executeUpdate();
        }
    }

    private static Record readRecord(ResultSet rs) throws SQLException {
        String other = rs.getString("other_uuid");
        Record.Type type;
        try {
            type = Record.Type.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException e) {
            type = Record.Type.SET;
        }
        return new Record(
                rs.getLong("id"),
                rs.getLong("time"),
                rs.getString("currency"),
                type,
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                other == null ? null : UUID.fromString(other),
                rs.getString("other_name"),
                rs.getLong("amount"),
                rs.getLong("fee"),
                rs.getLong("balance_after"),
                rs.getLong("other_balance_after"),
                rs.getString("server"),
                rs.getString("actor"));
    }

    private Connection borrow() throws SQLException {
        if (closed) throw new SQLException("存储 " + def.id + " 已关闭");
        Connection c = idle.poll();
        while (c != null) {
            if (alive(c)) return c;
            closeQuietly(c);
            created.decrementAndGet();
            c = idle.poll();
        }
        if (created.incrementAndGet() <= idleCapacity()) {
            try {
                return open();
            } catch (SQLException e) {
                created.decrementAndGet();
                throw e;
            }
        }
        created.decrementAndGet();
        try {
            c = idle.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("等待数据库连接时被中断");
        }
        if (c == null) throw new SQLException("等待数据库连接超时");
        return c;
    }

    private void release(Connection c, boolean broken) {
        if (broken || closed || !idle.offer(c)) {
            closeQuietly(c);
            created.decrementAndGet();
        }
    }

    private int idleCapacity() {
        return idle.remainingCapacity() + idle.size();
    }

    private boolean alive(Connection c) {
        try {
            if (c.isClosed()) return false;
            return def.type != StorageDef.Type.MYSQL || c.isValid(2);
        } catch (SQLException | AbstractMethodError e) {
            return def.type != StorageDef.Type.MYSQL;
        }
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        if (def.type == StorageDef.Type.MYSQL) {
            props.setProperty("user", def.username);
            props.setProperty("password", def.password == null ? "" : def.password);
            props.setProperty("connectTimeout", "5000");
            props.setProperty("socketTimeout", "15000");
        }
        Connection c = driver.connect(def.jdbcUrl(), props);
        if (c == null) throw new SQLException("驱动不接受地址 " + def.describe());
        if (def.type == StorageDef.Type.SQLITE) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
            }
        }
        return c;
    }

    private Driver loadDriver() throws SQLException {
        String[] names = def.type == StorageDef.Type.MYSQL ? MYSQL_DRIVERS : new String[] {SQLITE_DRIVER};
        for (String name : names) {
            try {
                return (Driver) Class.forName(name).newInstance();
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个
            } catch (ReflectiveOperationException e) {
                throw new SQLException("无法加载数据库驱动 " + name, e);
            }
        }
        throw new SQLException(
                def.type == StorageDef.Type.SQLITE ? "服务端没有自带 SQLite 驱动（org.sqlite.JDBC），请改用 mysql 存储" : "找不到 MySQL 驱动");
    }

    private static boolean retryable(SQLException e) {
        return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
    }

    private String q(String table) {
        return def.type == StorageDef.Type.MYSQL ? '`' + table + '`' : table;
    }

    private static String marks(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(i == 0 ? "?" : ", ?");
        return out.toString();
    }

    private static String cut(String value) {
        String v = value == null ? "" : value;
        return v.length() > 32 ? v.substring(0, 32) : v;
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // 已无法使用
        }
    }
}
