package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   public enum Currency {
      €("EUR"),
      ₹("INR"),
      ₺("TRY"),
      ฿("THB"),
      ₴("UAH"),
      ₮("MNT"),
      ERR("ERR");

      public static final String SEPARATOR = " ";

      private final String isoCode;

      Currency(String isoCode) {
         this.isoCode = isoCode;
      }

      public String getIsoCode() {
         return isoCode;
      }

      public static Currency fromCurrencyAmount(String currencyAmount) {
         String currencySymbol = currencyAmount.substring(0, 1); // e.g. '€'
         try {
            return Currency.valueOf(currencySymbol); // e.g. "€" => "EUR"
         } catch (Exception e) {
            return Currency.ERR; // e.g. ">" => "ERR"
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

      return currency.concatToAmount(amount);  // e.g. "100 EUR"
   }
}
