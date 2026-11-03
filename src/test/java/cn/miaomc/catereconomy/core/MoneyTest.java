package cn.miaomc.catereconomy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void parsesWithinAllowedDecimals() {
        assertEquals(1200, Money.parse("1200", 0));
        assertEquals(1250, Money.parse("12.5", 2));
        assertEquals(1200, Money.parse("12.00", 2));
        assertEquals(100000, Money.parse("1,000", 2));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1.5", 0));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("-5", 0));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1e5", 0));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("abc", 0));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("99999999999999999", 4));
    }

    @Test
    void formatsWithGrouping() {
        assertEquals("1,234,567", Money.grouped(1234567, 0));
        assertEquals("12,345.60", Money.grouped(1234560, 2));
        assertEquals("0.05", Money.grouped(5, 2));
        assertEquals("12.5", Money.plain(1250, 2).replaceAll("0+$", ""));
    }

    @Test
    void feeRoundsUp() {
        assertEquals(0, Money.fee(100, BigDecimal.ZERO));
        assertEquals(5, Money.fee(100, new BigDecimal("5")));
        assertEquals(1, Money.fee(3, new BigDecimal("5")));
    }

    @Test
    void doubleIsTruncated() {
        assertEquals(1999, Money.fromDouble(19.999, 2));
        assertEquals(10, Money.fromDouble(10.9, 0));
    }
}
