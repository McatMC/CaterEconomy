package cn.miaomc.catereconomy.core;

/** 一次余额变动的结果。 */
public final class Change {
    public enum Status {
        OK,
        INSUFFICIENT,
        MAX_EXCEEDED,
        SELF,
        /** 收款人今日接收已达上限。 */
        RECEIVE_LIMIT,
        /** 同 IP 账号之间禁止转账。 */
        SAME_IP_BLOCKED,
        /** 同 IP 账号今日转账次数已用完。 */
        SAME_IP_COUNT,
        /** 同 IP 账号今日转账总额已达上限。 */
        SAME_IP_AMOUNT,
        ERROR
    }

    public final Status status;
    /** 主账户（转账时为付款人）变动后的余额。 */
    public final long balance;
    /** 转账收款人变动后的余额；非转账为 0。 */
    public final long otherBalance;
    /** 转账税。 */
    public final long fee;
    /** 触发每日限制时，当天还剩的额度（金额或次数）。 */
    public final long remaining;

    public Change(Status status, long balance, long otherBalance, long fee) {
        this(status, balance, otherBalance, fee, 0);
    }

    public Change(Status status, long balance, long otherBalance, long fee, long remaining) {
        this.status = status;
        this.balance = balance;
        this.otherBalance = otherBalance;
        this.fee = fee;
        this.remaining = remaining;
    }

    public static Change of(Status status) {
        return new Change(status, 0, 0, 0);
    }

    public static Change limit(Status status, long remaining) {
        return new Change(status, 0, 0, 0, Math.max(0, remaining));
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
