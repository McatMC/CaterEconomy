package cn.miaomc.catereconomy.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlStorageTest {
    private static final int DAY = 20261103;
    private static final long U = CurrencyDef.UNLIMITED;

    @TempDir File dir;
    private SqlStorage storage;
    private final CurrencyDef coin = currency("coin", 0, 0, new BigDecimal("10"), true);
    private final CurrencyDef capped = currency("capped", 0, 1000, BigDecimal.ZERO, true);
    private final Account alice = new Account(Account.offlineUuid("Alice"), "Alice");
    private final Account bob = new Account(Account.offlineUuid("Bob"), "Bob");
    private final Account carol = new Account(Account.offlineUuid("Carol"), "Carol");

    @BeforeEach
    void open() throws Exception {
        storage = new SqlStorage(StorageDef.sqlite("test", new File(dir, "eco.db"), "t_"));
        storage.start();
    }

    @AfterEach
    void close() {
        storage.close();
    }

    private static CurrencyDef currency(String id, long start, long max, BigDecimal tax, boolean senderPays) {
        return new CurrencyDef(id, id, Collections.<String>emptyList(), "test", 0, start, max, "{amount}", true, true, 1, 0, tax, senderPays, 0, U, false, U, U);
    }

    private Change pay(Account from, Account to, long credit, long fee, TransferRules rules) throws Exception {
        return storage.transfer(coin, from, to, credit, fee, rules, "s");
    }

    private long balance(Account a, CurrencyDef c) throws Exception {
        Long v = storage.balances(a.uuid, Collections.singletonList(c.id)).get(c.id);
        return v == null ? 0 : v;
    }

    @Test
    void withdrawNeverGoesNegative() throws Exception {
        storage.deposit(coin, alice, 50, Record.Type.GIVE, "s", "op");
        Change fail = storage.withdraw(coin, alice, 80, Record.Type.TAKE, "s", "op");
        assertEquals(Change.Status.INSUFFICIENT, fail.status);
        assertEquals(50, fail.balance);
        assertEquals(0, storage.withdraw(coin, alice, 50, Record.Type.TAKE, "s", "op").balance);
    }

    @Test
    void taxSplitBySenderOrReceiver() {
        CurrencyDef sender = currency("a", 0, 0, new BigDecimal("5"), true);
        CurrencyDef receiver = currency("b", 0, 0, new BigDecimal("5"), false);
        assertArrayEquals(new long[] {105, 100, 5}, sender.split(100, false));
        assertArrayEquals(new long[] {100, 95, 5}, receiver.split(100, false));
        assertArrayEquals(new long[] {100, 100, 0}, receiver.split(100, true));
        assertArrayEquals(new long[] {1, 0, 1}, receiver.split(1, false));
    }

    @Test
    void transferChargesTaxAndTaxIsDestroyed() throws Exception {
        storage.deposit(coin, alice, 110, Record.Type.GIVE, "s", "op");
        Change c = pay(alice, bob, 100, 10, TransferRules.none(DAY));
        assertTrue(c.ok());
        assertEquals(0, c.balance);
        assertEquals(100, c.otherBalance);
        assertEquals(Change.Status.INSUFFICIENT, pay(alice, bob, 1, 0, TransferRules.none(DAY)).status);
        List<Record> log = storage.history(alice.uuid, Arrays.asList("coin"), true, 10, 0);
        assertEquals(10, log.get(0).fee);
    }

    @Test
    void transferRollsBackWhenReceiverAtMax() throws Exception {
        storage.deposit(capped, alice, 500, Record.Type.GIVE, "s", "op");
        storage.deposit(capped, bob, 900, Record.Type.GIVE, "s", "op");
        Change c = storage.transfer(capped, alice, bob, 200, 0, TransferRules.none(DAY), "s");
        assertEquals(Change.Status.MAX_EXCEEDED, c.status);
        assertEquals(500, balance(alice, capped));
        assertEquals(900, balance(bob, capped));
    }

    @Test
    void dailyReceiveLimitResetsNextDay() throws Exception {
        storage.deposit(coin, alice, 10000, Record.Type.GIVE, "s", "op");
        storage.deposit(coin, carol, 10000, Record.Type.GIVE, "s", "op");
        TransferRules today = new TransferRules(DAY, 300, null, 0, U, U);
        assertTrue(pay(alice, bob, 200, 0, today).ok());
        // 不同付款人共用收款人的额度
        Change over = pay(carol, bob, 150, 0, today);
        assertEquals(Change.Status.RECEIVE_LIMIT, over.status);
        assertEquals(100, over.remaining);
        assertEquals(10000, balance(carol, coin));
        assertTrue(pay(carol, bob, 100, 0, today).ok());
        assertEquals(Change.Status.RECEIVE_LIMIT, pay(alice, bob, 1, 0, today).status);
        assertTrue(pay(alice, bob, 300, 0, new TransferRules(DAY + 1, 300, null, 0, U, U)).ok());
        assertEquals(300, storage.daily("coin", "recv:" + bob.uuid, DAY)[0]);
        assertEquals(600, balance(bob, coin));
    }

    @Test
    void failedLimitDoesNotConsumeQuota() throws Exception {
        storage.deposit(coin, alice, 50, Record.Type.GIVE, "s", "op");
        TransferRules rules = new TransferRules(DAY, 300, null, 0, U, U);
        assertEquals(Change.Status.INSUFFICIENT, pay(alice, bob, 100, 0, rules).status);
        assertEquals(0, storage.daily("coin", "recv:" + bob.uuid, DAY)[0]);
    }

    @Test
    void sameIpCountAndAmountLimits() throws Exception {
        storage.deposit(coin, alice, 10000, Record.Type.GIVE, "s", "op");
        storage.recordIp(alice.uuid, "1.2.3.4");
        storage.recordIp(bob.uuid, "1.2.3.4");
        storage.recordIp(carol.uuid, "5.6.7.8");
        TransferRules rules = new TransferRules(DAY, U, "1.2.3.4", 0, 2, 500);
        assertTrue(pay(alice, bob, 300, 0, rules).ok());
        Change amount = pay(alice, bob, 300, 0, rules);
        assertEquals(Change.Status.SAME_IP_AMOUNT, amount.status);
        assertEquals(200, amount.remaining);
        assertTrue(pay(alice, bob, 200, 0, rules).ok());
        assertEquals(Change.Status.SAME_IP_COUNT, pay(alice, bob, 1, 0, rules).status);
        // 不同 IP 的玩家不受影响
        assertTrue(pay(alice, carol, 1000, 0, rules).ok());
        assertEquals(500, balance(bob, coin));
    }

    @Test
    void sameIpZeroCountBlocksAndOldIpExpires() throws Exception {
        storage.deposit(coin, alice, 1000, Record.Type.GIVE, "s", "op");
        storage.recordIp(alice.uuid, "1.2.3.4");
        storage.recordIp(bob.uuid, "1.2.3.4");
        assertEquals(Change.Status.SAME_IP_BLOCKED, pay(alice, bob, 10, 0, new TransferRules(DAY, U, "1.2.3.4", 0, 0, U)).status);
        long future = System.currentTimeMillis() + 60_000;
        // 共用 IP 的记录早于 window-days 时不算同 IP（付款人本次 IP 会被刷新，收款人的旧记录过期）
        assertTrue(pay(alice, bob, 10, 0, new TransferRules(DAY, U, "1.2.3.4", future, 0, U)).ok());
    }

    @Test
    void concurrentTransfersRespectReceiveLimit() throws Exception {
        storage.deposit(coin, alice, 100000, Record.Type.GIVE, "s", "op");
        TransferRules rules = new TransferRules(DAY, 1000, null, 0, U, U);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Change>> jobs = new ArrayList<Callable<Change>>();
        for (int i = 0; i < 40; i++) jobs.add(() -> pay(alice, bob, 70, 0, rules));
        int ok = 0;
        for (Future<Change> f : pool.invokeAll(jobs)) if (f.get().ok()) ok++;
        pool.shutdown();
        assertEquals(14, ok);
        assertEquals(980, balance(bob, coin));
    }

    @Test
    void concurrentWithdrawalsCannotOverspend() throws Exception {
        storage.deposit(coin, alice, 100, Record.Type.GIVE, "s", "op");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Change>> jobs = new ArrayList<Callable<Change>>();
        for (int i = 0; i < 50; i++) jobs.add(() -> storage.withdraw(coin, alice, 3, Record.Type.TAKE, "s", "op"));
        int ok = 0;
        for (Future<Change> f : pool.invokeAll(jobs)) if (f.get().ok()) ok++;
        pool.shutdown();
        assertEquals(33, ok);
        assertEquals(1, balance(alice, coin));
    }

    @Test
    void historyShowsBothSidesOfTransfer() throws Exception {
        storage.deposit(coin, alice, 200, Record.Type.GIVE, "s", "op");
        pay(alice, bob, 50, 5, TransferRules.none(DAY));
        List<Record> bobPays = storage.history(bob.uuid, Arrays.asList("coin"), true, 10, 0);
        assertEquals(1, bobPays.size());
        assertEquals("Alice", bobPays.get(0).name);
        assertEquals(50, bobPays.get(0).amount);
        assertEquals(2, storage.history(alice.uuid, Arrays.asList("coin"), false, 10, 0).size());
    }

    @Test
    void findByNameIsCaseInsensitiveAndTopIsOrdered() throws Exception {
        storage.deposit(coin, alice, 10, Record.Type.GIVE, "s", "op");
        storage.deposit(coin, bob, 30, Record.Type.GIVE, "s", "op");
        assertEquals(alice.uuid, storage.findByName("aLiCe").uuid);
        List<TopEntry> top = storage.top("coin", 10, 0);
        assertEquals("Bob", top.get(0).name);
        assertEquals("Alice", top.get(1).name);
    }

    @Test
    void setAndResetRespectStartBalance() throws Exception {
        CurrencyDef start = currency("st", 25, 0, BigDecimal.ZERO, true);
        assertEquals(500, storage.set(start, alice, 500, Record.Type.SET, "s", "op").balance);
        assertEquals(25, storage.set(start, alice, -1, Record.Type.RESET, "s", "op").balance);
        assertEquals(30, storage.deposit(start, bob, 5, Record.Type.GIVE, "s", "op").balance);
    }

    @Test
    void purgeDailyRemovesOldCounters() throws Exception {
        storage.deposit(coin, alice, 1000, Record.Type.GIVE, "s", "op");
        pay(alice, bob, 100, 0, new TransferRules(DAY, 500, null, 0, U, U));
        storage.purgeDaily(DAY + 1, 0);
        assertEquals(0, storage.daily("coin", "recv:" + bob.uuid, DAY)[0]);
    }
}
