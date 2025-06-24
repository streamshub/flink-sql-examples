package com.github.streamshub.flink.functions;

import org.apache.flink.table.functions.ScalarFunction;

import com.github.streamshub.flink.enums.Currency;

public class CurrencyConverter extends ScalarFunction {
   // e.g. unicodeAmount = "€100"
   public String eval(String unicodeAmount) {
      return Currency.unicodeAmountToIsoAmount(unicodeAmount); // "€100" -> "100 EUR"
   }
}
