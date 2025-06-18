package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   enum Currency {
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

      public String concatToAmount(String amount) {
         return amount + SEPARATOR + isoCode;
      }
   }

   // e.g. currencyAmount = "€100"
   public String eval(String currencyAmount) {
      String currencySymbol = currencyAmount.substring(0, 1); // e.g. "€"
      String amount = currencyAmount.substring(1); // e.g. "100"

      Currency currency;
      try {
         currency = Currency.valueOf(currencySymbol); // e.g. "€" => "EUR"
      } catch (Exception e) {
         currency = Currency.ERR; // e.g. ">" => "ERR"
      }

      return currency.concatToAmount(amount);  // e.g. "100 EUR"
   }
}
