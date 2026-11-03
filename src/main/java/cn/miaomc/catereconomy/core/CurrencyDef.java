package cn.miaomc.catereconomy.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** 一种货币的全部配置；金额字段均为最小单位。 */
public final class CurrencyDef {
    /** 不限制。 */
    public static final long UNLIMITED = -1;

    public final String id;
    public final String name;
    public final List<String> aliases;
    public final String storage;
    public final int decimals;
    public final long startBalance;
    /** 0 表示不限制。 */
    public final long maxBalance;
    public final String format;
    public final boolean grouping;
    public final boolean payable;
    public final long payMin;
    /** 0 表示不限制。 */
    public final long payMax;
    /** 转账税率（百分比）。 */
    public final BigDecimal taxPercent;
    /** true：税由付款人额外支付；false：从对方到账金额中扣除。 */
    public final boolean taxPaidBySender;
    public final int payCooldownSeconds;
    /** 单人每日最多接收的转账金额，{@link #UNLIMITED} 为不限。 */
    public final long dailyReceiveLimit;
    public final boolean sameIpEnabled;
    /** 同 IP 账号之间每日转账次数，{@link #UNLIMITED} 为不限，0 为禁止。 */
    public final long sameIpMaxCount;
    /** 同 IP 账号之间每日转账总额，{@link #UNLIMITED} 为不限。 */
    public final long sameIpMaxAmount;

    public CurrencyDef(
            String id,
            String name,
            List<String> aliases,
            String storage,
            int decimals,
            long startBalance,
            long maxBalance,
            String format,
            boolean grouping,
            boolean payable,
            long payMin,
            long payMax,
            BigDecimal taxPercent,
            boolean taxPaidBySender,
            int payCooldownSeconds,
            long dailyReceiveLimit,
            boolean sameIpEnabled,
            long sameIpMaxCount,
            long sameIpMaxAmount) {
        this.id = id;
        this.name = name;
        this.aliases = Collections.unmodifiableList(aliases);
        this.storage = storage;
        this.decimals = decimals;
        this.startBalance = startBalance;
        this.maxBalance = maxBalance;
        this.format = format;
        this.grouping = grouping;
        this.payable = payable;
        this.payMin = payMin;
        this.payMax = payMax;
        this.taxPercent = taxPercent;
        this.taxPaidBySender = taxPaidBySender;
        this.payCooldownSeconds = payCooldownSeconds;
        this.dailyReceiveLimit = dailyReceiveLimit;
        this.sameIpEnabled = sameIpEnabled;
        this.sameIpMaxCount = sameIpMaxCount;
        this.sameIpMaxAmount = sameIpMaxAmount;
    }

    public long parse(String input) {
        return Money.parse(input, decimals);
    }

    public String amount(long minor) {
        return grouping ? Money.grouped(minor, decimals) : Money.plain(minor, decimals);
    }

    /** 按 format 输出，例如 "&d1,200 &f喵币"。 */
    public String format(long minor) {
        return format.replace("{amount}", amount(minor)).replace("{name}", name);
    }

    /** 可存入的上限余额，未限制时为 Long.MAX_VALUE。 */
    public long effectiveMax() {
        return maxBalance <= 0 ? Long.MAX_VALUE : maxBalance;
    }

    /** 计算一笔转账：输入玩家填写的金额，返回 [付款人扣除, 对方到账, 税]。exempt 为免税。 */
    public long[] split(long amount, boolean exempt) {
        long tax = exempt ? 0 : Money.fee(amount, taxPercent);
        if (taxPaidBySender) return new long[] {amount + tax, amount, tax};
        return new long[] {amount, amount - tax, tax};
    }
}
