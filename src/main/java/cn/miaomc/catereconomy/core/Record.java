package cn.miaomc.catereconomy.core;

import java.util.UUID;

/** 一条不可修改的流水记录。PAY 类型中 uuid 为付款人、otherUuid 为收款人。 */
public final class Record {
    public enum Type {
        PAY,
        GIVE,
        TAKE,
        SET,
        RESET,
        VAULT_DEPOSIT,
        VAULT_WITHDRAW,
        /** 其他插件通过 CaterEconomyAPI 加钱。 */
        PLUGIN_DEPOSIT,
        /** 其他插件通过 CaterEconomyAPI 扣钱。 */
        PLUGIN_WITHDRAW,
        IMPORT
    }

    public final long id;
    public final long time;
    public final String currency;
    public final Type type;
    public final UUID uuid;
    public final String name;
    public final UUID otherUuid;
    public final String otherName;
    public final long amount;
    public final long fee;
    public final long balanceAfter;
    public final long otherBalanceAfter;
    public final String server;
    public final String actor;

    public Record(
            long id,
            long time,
            String currency,
            Type type,
            UUID uuid,
            String name,
            UUID otherUuid,
            String otherName,
            long amount,
            long fee,
            long balanceAfter,
            long otherBalanceAfter,
            String server,
            String actor) {
        this.id = id;
        this.time = time;
        this.currency = currency;
        this.type = type;
        this.uuid = uuid;
        this.name = name;
        this.otherUuid = otherUuid;
        this.otherName = otherName;
        this.amount = amount;
        this.fee = fee;
        this.balanceAfter = balanceAfter;
        this.otherBalanceAfter = otherBalanceAfter;
        this.server = server;
        this.actor = actor;
    }
}
