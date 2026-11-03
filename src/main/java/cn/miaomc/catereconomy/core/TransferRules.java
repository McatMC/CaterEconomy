package cn.miaomc.catereconomy.core;

/** 一次转账需要在数据库里原子检查的每日限制。 */
public final class TransferRules {
    /** 当天日期，如 20261103，由配置的时区决定零点。 */
    public final int day;
    /** 收款人当日最多接收金额，{@link CurrencyDef#UNLIMITED} 为不限。 */
    public final long receiveLimit;
    /** 付款人当前 IP；为 null 时不做同 IP 检查（未开启、被豁免或 IP 被忽略）。 */
    public final String senderIp;
    /** 只把这个时间之后出现过的 IP 视为共用。 */
    public final long ipSince;
    public final long ipMaxCount;
    public final long ipMaxAmount;

    public TransferRules(int day, long receiveLimit, String senderIp, long ipSince, long ipMaxCount, long ipMaxAmount) {
        this.day = day;
        this.receiveLimit = receiveLimit;
        this.senderIp = senderIp;
        this.ipSince = ipSince;
        this.ipMaxCount = ipMaxCount;
        this.ipMaxAmount = ipMaxAmount;
    }

    public static TransferRules none(int day) {
        return new TransferRules(day, CurrencyDef.UNLIMITED, null, 0, CurrencyDef.UNLIMITED, CurrencyDef.UNLIMITED);
    }
}
