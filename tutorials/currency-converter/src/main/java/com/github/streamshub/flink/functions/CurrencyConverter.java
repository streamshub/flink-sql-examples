package com.github.streamshub.flink.functions;

import java.util.Map;

import org.apache.flink.table.functions.ScalarFunction;

public class CurrencyConverter extends ScalarFunction {
   // https://www.unicode.org/charts/nameslist/n_20A0.html
   // https://www.iso.org/iso-4217-currency-codes.html
   private static final Map<Character, String> CURRENCY_SYMBOL_ISO_MAP = Map.of(
      '€', "EUR",
      '₹', "INR",
      '₺', "TRY",
      '฿', "THB",
      '₴', "UAH",
      '₮', "MNT"
   );

   // e.g. currencyAmount = "€100"
   public String eval(String currencyAmount) {
      char currencySymbol = currencyAmount.charAt(0); // e.g. '€'
      String amount = currencyAmount.substring(1); // e.g. "100"

      String currencyIsoCode = CURRENCY_SYMBOL_ISO_MAP.get(currencySymbol); // e.g. '€' => "EUR"
      if (currencyIsoCode == null) {
         currencyIsoCode = "???";
      }

      return amount + " " + currencyIsoCode; // e.g. "100 EUR"
   }
}
