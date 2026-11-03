package cn.miaomc.catereconomy.core;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台无关的经济服务：货币与存储的路由、在线玩家余额缓存、异步执行。
 *
 * <p>缓存只用于显示（余额、变量、Vault 查询）；所有扣款都以数据库的带条件更新为准，缓存过期不会导致多扣或扣成负数。
 */
public final class EconomyService {
    public interface Platform {
        /** 在服务器主线程执行。 */
        void sync(Runnable task);

        /** 按名字查找当前在线玩家，找不到返回 null。 */
        Account online(String name);

        Collection<Account> onlineAccounts();
    }

    private final Platform platform;
    private final Logger logger;
    private final String server;
    private final Map<String, CurrencyDef> currencies = new LinkedHashMap<String, CurrencyDef>();
    private final Map<String, CurrencyDef> lookup = new HashMap<String, CurrencyDef>();
    private final Map<String, SqlStorage> storages = new LinkedHashMap<String, SqlStorage>();
    private final Set<String> failedStorages = new HashSet<String>();
    private final Map<UUID, Map<String, Long>> cache = new ConcurrentHashMap<UUID, Map<String, Long>>();
    private final Map<String, List<TopEntry>> topCache = new ConcurrentHashMap<String, List<TopEntry>>();
    private final CurrencyDef defaultCurrency;

    public EconomyService(
            Platform platform,
            Logger logger,
            String server,
            Collection<StorageDef> storageDefs,
            Collection<CurrencyDef> currencyDefs,
            String defaultCurrency) {
        this.platform = platform;
        this.logger = logger;
        this.server = server;
        for (StorageDef def : storageDefs) storages.put(def.id, new SqlStorage(def));
        for (CurrencyDef cur : currencyDefs) {
            if (!storages.containsKey(cur.storage))
                throw new IllegalArgumentException("货币 " + cur.id + " 使用了不存在的存储 " + cur.storage);
            currencies.put(cur.id, cur);
        }
        for (CurrencyDef cur : currencyDefs) register(cur.id, cur);
        for (CurrencyDef cur : currencyDefs) register(cur.name, cur);
        for (CurrencyDef cur : currencyDefs) for (String alias : cur.aliases) register(alias, cur);
        this.defaultCurrency = defaultCurrency == null ? null : lookup.get(defaultCurrency.toLowerCase(Locale.ROOT));
        if (defaultCurrency != null && !defaultCurrency.isEmpty() && this.defaultCurrency == null)
            throw new IllegalArgumentException("default-currency 指向不存在的货币 " + defaultCurrency);
    }

    /** 启动所有存储；某个存储失败时只停用它下面的货币，其他货币照常可用。 */
    public List<String> start() {
        List<String> errors = new ArrayList<String>();
        for (SqlStorage storage : storages.values()) {
            if (!used(storage.def().id)) continue;
            try {
                storage.start();
            } catch (SQLException | RuntimeException e) {
                failedStorages.add(storage.def().id);
                errors.add("存储 " + storage.def().id + " (" + storage.def().describe() + ") 启动失败：" + e.getMessage());
                logger.log(Level.FINE, "storage start failed", e);
            }
        }
        return errors;
    }

    public void close() {
        for (SqlStorage storage : storages.values()) storage.close();
        cache.clear();
    }

    // ---------------------------------------------------------------- 货币

    public String server() {
        return server;
    }

    /** 按 id、显示名或别名查找可用货币（不区分大小写）。 */
    public CurrencyDef currency(String key) {
        if (key == null) return null;
        CurrencyDef cur = lookup.get(key.toLowerCase(Locale.ROOT));
        return cur != null && available(cur) ? cur : null;
    }

    /** 已配置但存储不可用的货币也能查到，用于给出准确的错误提示。 */
    public CurrencyDef configured(String key) {
        return key == null ? null : lookup.get(key.toLowerCase(Locale.ROOT));
    }

    public CurrencyDef defaultCurrency() {
        return defaultCurrency != null && available(defaultCurrency) ? defaultCurrency : null;
    }

    public List<CurrencyDef> currencies() {
        List<CurrencyDef> out = new ArrayList<CurrencyDef>();
        for (CurrencyDef cur : currencies.values()) if (available(cur)) out.add(cur);
        return out;
    }

    public boolean available(CurrencyDef cur) {
        return !failedStorages.contains(cur.storage);
    }

    public Collection<SqlStorage> storages() {
        return Collections.unmodifiableCollection(storages.values());
    }

    public SqlStorage storage(String id) {
        return storages.get(id);
    }

    public boolean storageFailed(String id) {
        return failedStorages.contains(id);
    }

    // ---------------------------------------------------------------- 缓存

    /** 读缓存中的余额；玩家不在线或尚未加载时返回 null。 */
    public Long cached(UUID uuid, CurrencyDef cur) {
        Map<String, Long> m = cache.get(uuid);
        if (m == null) return null;
        Long v = m.get(cur.id);
        return v == null ? (m.containsKey("\0loaded") ? cur.startBalance : null) : v;
    }

    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /** 阻塞加载玩家全部余额，供 AsyncPlayerPreLoginEvent 等异步线程使用。 */
    public void loadBlocking(UUID uuid, long timeoutMillis) {
        try {
            loadAsync(uuid).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warning("预加载玩家 " + uuid + " 余额失败：" + rootMessage(e));
        }
    }

    public CompletableFuture<Void> loadAsync(UUID uuid) {
        List<CompletableFuture<Map<String, Long>>> parts = new ArrayList<CompletableFuture<Map<String, Long>>>();
        for (Map.Entry<SqlStorage, List<String>> e : groupByStorage(currencies()).entrySet()) {
            SqlStorage storage = e.getKey();
            List<String> ids = e.getValue();
            parts.add(submit(storage, () -> storage.balances(uuid, ids)));
        }
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .thenRun(
                        () -> {
                            Map<String, Long> merged = new ConcurrentHashMap<String, Long>();
                            for (CompletableFuture<Map<String, Long>> f : parts) merged.putAll(f.join());
                            merged.put("\0loaded", 0L);
                            cache.put(uuid, merged);
                        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    /** 批量刷新在线玩家在共享存储（MySQL）中的余额，同步其他子服造成的变动。 */
    public void refreshOnline() {
        List<UUID> online = new ArrayList<UUID>();
        for (Account acc : platform.onlineAccounts()) if (cache.containsKey(acc.uuid)) online.add(acc.uuid);
        if (online.isEmpty()) return;
        for (Map.Entry<SqlStorage, List<String>> e : groupByStorage(currencies()).entrySet()) {
            SqlStorage storage = e.getKey();
            if (storage.def().type != StorageDef.Type.MYSQL) continue;
            List<String> ids = e.getValue();
            submit(storage, () -> storage.balances(online, ids))
                    .thenAccept(
                            result -> {
                                for (UUID uuid : online) {
                                    Map<String, Long> m = cache.get(uuid);
                                    if (m == null) continue;
                                    Map<String, Long> fresh = result.get(uuid);
                                    for (String id : ids) {
                                        Long v = fresh == null ? null : fresh.get(id);
                                        if (v == null) m.remove(id);
                                        else m.put(id, v);
                                    }
                                }
                            })
                    .exceptionally(
                            ex -> {
                                logger.fine("刷新余额失败：" + rootMessage(ex));
                                return null;
                            });
        }
    }

    public void refreshTop(int size) {
        for (CurrencyDef cur : currencies()) {
            SqlStorage storage = storages.get(cur.storage);
            submit(storage, () -> storage.top(cur.id, size, 0))
                    .thenAccept(list -> topCache.put(cur.id, list))
                    .exceptionally(ex -> null);
        }
    }

    /** 排行缓存中的第 rank 名（从 1 开始），没有返回 null。 */
    public TopEntry cachedTop(CurrencyDef cur, int rank) {
        List<TopEntry> list = topCache.get(cur.id);
        return list == null || rank < 1 || rank > list.size() ? null : list.get(rank - 1);
    }

    // ---------------------------------------------------------------- 账户

    /**
     * 按名字解析账户：先找在线玩家，再在所有可用存储中按名字查找。
     *
     * @param create 找不到时，是否按离线 UUID 创建（仅离线服的管理员命令使用）
     */
    public CompletableFuture<Account> resolve(String name, boolean create) {
        Account online = platform.online(name);
        if (online != null) return CompletableFuture.completedFuture(online);
        CompletableFuture<Account> chain = CompletableFuture.completedFuture(null);
        for (SqlStorage storage : storages.values()) {
            if (failedStorages.contains(storage.def().id) || !used(storage.def().id)) continue;
            chain = chain.thenCompose(found -> found != null ? CompletableFuture.completedFuture(found) : submit(storage, () -> storage.findByName(name)));
        }
        return chain.thenApply(found -> found != null || !create ? found : new Account(Account.offlineUuid(name), name));
    }

    public CompletableFuture<Long> balance(CurrencyDef cur, Account acc) {
        Long cachedValue = cached(acc.uuid, cur);
        if (cachedValue != null) return CompletableFuture.completedFuture(cachedValue);
        SqlStorage storage = storages.get(cur.storage);
        return submit(storage, () -> storage.balances(acc.uuid, Collections.singletonList(cur.id)))
                .thenApply(m -> m.containsKey(cur.id) ? m.get(cur.id) : cur.startBalance);
    }

    /** 玩家进服后同步名字。 */
    public void updateName(Account acc) {
        for (SqlStorage storage : storages.values()) {
            if (failedStorages.contains(storage.def().id) || !used(storage.def().id)) continue;
            submit(storage, () -> {
                        storage.updateName(acc);
                        return null;
                    })
                    .exceptionally(ex -> null);
        }
    }

    // ---------------------------------------------------------------- 变动

    public CompletableFuture<Change> deposit(CurrencyDef cur, Account acc, long amount, Record.Type type, String actor) {
        SqlStorage storage = storages.get(cur.storage);
        return track(cur, acc, null, submit(storage, () -> storage.deposit(cur, acc, amount, type, server, actor)));
    }

    public CompletableFuture<Change> withdraw(CurrencyDef cur, Account acc, long amount, Record.Type type, String actor) {
        SqlStorage storage = storages.get(cur.storage);
        return track(cur, acc, null, submit(storage, () -> storage.withdraw(cur, acc, amount, type, server, actor)));
    }

    /** amount 为 -1 时重置为初始余额。 */
    public CompletableFuture<Change> set(CurrencyDef cur, Account acc, long amount, String actor) {
        SqlStorage storage = storages.get(cur.storage);
        Record.Type type = amount < 0 ? Record.Type.RESET : Record.Type.SET;
        return track(cur, acc, null, submit(storage, () -> storage.set(cur, acc, amount, type, server, actor)));
    }

    /** credit 为对方到账金额，tax 为税；两者之和从付款人扣除。 */
    public CompletableFuture<Change> transfer(CurrencyDef cur, Account from, Account to, long credit, long tax, TransferRules rules) {
        SqlStorage storage = storages.get(cur.storage);
        return track(cur, from, to, submit(storage, () -> storage.transfer(cur, from, to, credit, tax, rules, server)));
    }

    /** 记录玩家 IP 到所有在用的存储，供同 IP 判断使用。 */
    public void recordIp(UUID uuid, String ip) {
        for (SqlStorage storage : storages.values()) {
            if (failedStorages.contains(storage.def().id) || !used(storage.def().id)) continue;
            submit(storage, () -> {
                        storage.recordIp(uuid, ip);
                        return null;
                    })
                    .exceptionally(ex -> null);
        }
    }

    /** 查询收款人今天已经收到的转账金额。 */
    public CompletableFuture<Long> receivedToday(CurrencyDef cur, UUID uuid, int day) {
        SqlStorage storage = storages.get(cur.storage);
        return submit(storage, () -> storage.daily(cur.id, "recv:" + uuid, day)[0]);
    }

    public void purgeDaily(int beforeDay, long ipBefore) {
        for (SqlStorage storage : storages.values()) {
            if (failedStorages.contains(storage.def().id) || !used(storage.def().id)) continue;
            submit(storage, () -> storage.purgeDaily(beforeDay, ipBefore)).exceptionally(ex -> null);
        }
    }

    public CompletableFuture<List<TopEntry>> top(CurrencyDef cur, int limit, int offset) {
        SqlStorage storage = storages.get(cur.storage);
        return submit(storage, () -> storage.top(cur.id, limit, offset));
    }

    /** 跨存储合并查询流水：每个存储取前 offset+limit 条，合并后按时间倒序再分页。 */
    public CompletableFuture<List<Record>> history(UUID uuid, Collection<CurrencyDef> scope, boolean payOnly, int limit, int offset) {
        List<CompletableFuture<List<Record>>> parts = new ArrayList<CompletableFuture<List<Record>>>();
        for (Map.Entry<SqlStorage, List<String>> e : groupByStorage(scope).entrySet()) {
            SqlStorage storage = e.getKey();
            List<String> ids = e.getValue();
            parts.add(submit(storage, () -> storage.history(uuid, ids, payOnly, offset + limit, 0)));
        }
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .thenApply(
                        v -> {
                            List<Record> all = new ArrayList<Record>();
                            for (CompletableFuture<List<Record>> f : parts) all.addAll(f.join());
                            all.sort(Comparator.comparingLong((Record r) -> r.time).reversed().thenComparing((Record r) -> -r.id));
                            int from = Math.min(offset, all.size());
                            return new ArrayList<Record>(all.subList(from, Math.min(all.size(), from + limit)));
                        });
    }

    public CompletableFuture<Integer> purgeRecords(long before) {
        List<CompletableFuture<Integer>> parts = new ArrayList<CompletableFuture<Integer>>();
        for (SqlStorage storage : storages.values()) {
            if (failedStorages.contains(storage.def().id) || !used(storage.def().id)) continue;
            parts.add(submit(storage, () -> storage.purgeRecords(before)));
        }
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .thenApply(
                        v -> {
                            int total = 0;
                            for (CompletableFuture<Integer> f : parts) total += f.join();
                            return total;
                        });
    }

    /** 在存储线程上执行任意数据库操作。 */
    public <T> CompletableFuture<T> submit(SqlStorage storage, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        try {
            storage.executor()
                    .execute(
                            () -> {
                                try {
                                    future.complete(task.call());
                                } catch (Throwable t) {
                                    future.completeExceptionally(t);
                                }
                            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public interface Callable<T> {
        T call() throws Exception;
    }

    /** 把异步结果切回主线程。 */
    public <T> void onMain(CompletableFuture<T> future, java.util.function.BiConsumer<T, Throwable> callback) {
        future.whenComplete((result, error) -> platform.sync(() -> callback.accept(result, error)));
    }

    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    // ---------------------------------------------------------------- 内部

    private CompletableFuture<Change> track(CurrencyDef cur, Account main, Account other, CompletableFuture<Change> future) {
        return future.thenApply(
                change -> {
                    if (change.ok()) {
                        Map<String, Long> m = cache.get(main.uuid);
                        if (m != null) m.put(cur.id, change.balance);
                        if (other != null) {
                            Map<String, Long> o = cache.get(other.uuid);
                            if (o != null) o.put(cur.id, change.otherBalance);
                        }
                    }
                    return change;
                });
    }

    private Map<SqlStorage, List<String>> groupByStorage(Collection<CurrencyDef> scope) {
        Map<SqlStorage, List<String>> out = new LinkedHashMap<SqlStorage, List<String>>();
        for (CurrencyDef cur : scope) {
            if (!available(cur)) continue;
            SqlStorage storage = storages.get(cur.storage);
            List<String> ids = out.get(storage);
            if (ids == null) out.put(storage, ids = new ArrayList<String>());
            ids.add(cur.id);
        }
        return out;
    }

    private boolean used(String storageId) {
        for (CurrencyDef cur : currencies.values()) if (cur.storage.equals(storageId)) return true;
        return false;
    }

    private void register(String key, CurrencyDef cur) {
        String k = key.toLowerCase(Locale.ROOT);
        if (!lookup.containsKey(k)) lookup.put(k, cur);
    }
}
