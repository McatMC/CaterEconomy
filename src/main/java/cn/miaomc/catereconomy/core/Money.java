package cn.miaomc.catereconomy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** 金额以最小单位的 long 存储；decimals 为该货币允许的小数位数（0~4）。 */
public final class Money {
    private static final Pattern NUMBER = Pattern.compile("\\d{1,18}(\\.\\d{1,18})?");

    private Money() {}

    /** 解析玩家输入的非负金额；格式错误、小数位过多或溢出时抛出 IllegalArgumentException。 */
    public static long parse(String input, int decimals) {
        String value = input == null ? "" : input.trim().replace(",", "");
        if (!NUMBER.matcher(value).matches()) throw new IllegalArgumentException(input);
        BigDecimal number = new BigDecimal(value);
        if (number.stripTrailingZeros().scale() > decimals) throw new IllegalArgumentException(input);
        return toMinor(number, decimals);
    }

    /** Vault 等外部接口传入的 double，向下截断到允许的小数位。 */
    public static long fromDouble(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) throw new IllegalArgumentException(String.valueOf(value));
        return toMinor(BigDecimal.valueOf(value).setScale(decimals, RoundingMode.DOWN), decimals);
    }

    public static double toDouble(long minor, int decimals) {
        return BigDecimal.valueOf(minor, decimals).doubleValue();
    }

    public static String plain(long minor, int decimals) {
        return BigDecimal.valueOf(minor, decimals).toPlainString();
    }

    public static String grouped(long minor, int decimals) {
        BigDecimal value = BigDecimal.valueOf(minor, decimals);
        String integer = value.setScale(0, RoundingMode.DOWN).abs().toPlainString();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) out.append(',');
            out.append(integer.charAt(i));
        }
        if (decimals > 0) out.append('.').append(value.abs().toPlainString().substring(value.abs().toPlainString().indexOf('.') + 1));
        return (minor < 0 ? "-" : "") + out;
    }

    /** 按百分比计算手续费，向上取整到最小单位。 */
    public static long fee(long amount, BigDecimal percent) {
        if (percent.signum() <= 0 || amount <= 0) return 0;
        return BigDecimal.valueOf(amount).multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
    }

    private static long toMinor(BigDecimal number, int decimals) {
        try {
            return number.movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(number.toPlainString());
        }
    }
}
