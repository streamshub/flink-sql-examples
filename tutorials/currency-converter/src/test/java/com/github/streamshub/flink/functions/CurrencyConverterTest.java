package com.github.streamshub.flink.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.github.streamshub.flink.enums.Currency;

public class CurrencyConverterTest {
    public static final String VALID_UNICODE_AMOUNT = " €100 ";
    public static final String VALID_ISO_AMOUNT = "100" + Currency.SEPARATOR + Currency.EUR.getIsoCode();

    public static final String INVALID_UNICODE_AMOUNT = " ]100 ";
    public static final String INVALID_ISO_AMOUNT = "100" + Currency.SEPARATOR + Currency.ERR.getIsoCode();

    @Test
    public void shouldConvertValidUnicodeAmount() throws Exception {
        CurrencyConverter currencyConverter = new CurrencyConverter();

        assertEquals(VALID_ISO_AMOUNT, currencyConverter.eval(VALID_UNICODE_AMOUNT));
    }

    @Test
    public void shouldConvertInvalidUnicodeAmount() throws Exception {
        CurrencyConverter currencyConverter = new CurrencyConverter();

        assertEquals(INVALID_ISO_AMOUNT, currencyConverter.eval(INVALID_UNICODE_AMOUNT));
    }
}
