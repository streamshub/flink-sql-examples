package com.github.streamshub.flink.functions;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   public enum Currency {
      EUR("€", "EUR"),
      INR("₹", "INR"),
      TRY("₺", "TRY"),
      THB("฿", "THB"),
      UAH("₴", "UAH"),
      MNT("₮", "MNT"),
      ERR("?", "ERR");

      public static final String SEPARATOR = " ";
      private static final Map<String, Currency> SYMBOL_TO_CURRENCY = Stream.of(Currency.values())
            .collect(Collectors.toMap(Currency::getSymbol, c -> c));

      private final String symbol;
      private final String isoCode;

      Currency(String symbol, String isoCode) {
         this.symbol = symbol;
         this.isoCode = isoCode;
      }

      public String getSymbol() {
         return symbol;
      }

      public String getIsoCode() {
         return isoCode;
      }

      public static Currency fromCurrencyAmount(String currencyAmount) {
         String currencySymbol = currencyAmount.substring(0, 1); // e.g. '€'
         try {
            return SYMBOL_TO_CURRENCY.getOrDefault(currencySymbol, ERR);
         } catch (Exception e) {
            return ERR;
         }
      }

      public String concatToAmount(String amount) {
         return amount + SEPARATOR + isoCode;
      }
   }

   // e.g. currencyAmount = "€100"
   public String eval(String currencyAmount) {
      Currency currency = Currency.fromCurrencyAmount(currencyAmount);

      String amount = currencyAmount.substring(1); // e.g. "100"

      return currency.concatToAmount(amount); // e.g. "100 EUR"
   }
}
