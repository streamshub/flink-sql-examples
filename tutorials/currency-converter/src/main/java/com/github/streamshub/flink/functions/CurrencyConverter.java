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

      public static Currency fromUnicodeAmount(String unicodeAmount) {
         String currencySymbol = unicodeAmount.substring(0, 1); // "€100" -> "€"
         try {
            return SYMBOL_TO_CURRENCY.getOrDefault(currencySymbol, ERR); // "€100" -> EUR
         } catch (Exception e) {
            return ERR; // "]100" -> ERR
         }
      }

      public String concatIsoCodeToAmount(String amount) {
         return amount + SEPARATOR + isoCode; // "100" + EUR -> "100 EUR"
      }

      public static String unicodeAmountToIsoAmount(String unicodeAmount) {
         String trimmedUnicodeAmount = unicodeAmount.trim();

         Currency currency = fromUnicodeAmount(trimmedUnicodeAmount); // "€100" -> EUR
         String amount = trimmedUnicodeAmount.substring(1); // "€100" -> "100"

         return currency.concatIsoCodeToAmount(amount); // "100" + EUR -> "100 EUR"
      }
   }

   // e.g. unicodeAmount = "€100"
   public String eval(String unicodeAmount) {
      return Currency.unicodeAmountToIsoAmount(unicodeAmount); // "€100" -> "100 EUR"
   }
}
