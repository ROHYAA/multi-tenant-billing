package com.mtbs.business.invoice.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Converts a monetary amount to words using the Indian numbering system
 * (lakh/crore) — matches how amounts are conventionally written on Indian
 * invoices/cheques. Only used when ShopSettings.showAmountInWords is true.
 */
final class AmountToWordsConverter {

    /**
     * Reads naturally in a words sentence ("...Rupees Only") instead of the
     * raw ISO code ("...INR Only"). Unrecognized codes fall back to printing
     * the code itself, unchanged from the original behavior.
     */
    private static final Map<String, String> CURRENCY_NAMES = Map.of(
            "INR", "Rupees",
            "USD", "Dollars",
            "EUR", "Euros",
            "GBP", "Pounds"
    );

    private static final String[] UNDER_TWENTY = {
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountToWordsConverter() {
    }

    static String convert(BigDecimal amount, String currencyCode) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        long rupees = rounded.longValue();
        int paise = rounded.subtract(BigDecimal.valueOf(rupees)).movePointRight(2).intValue();

        String currencyName = CURRENCY_NAMES.getOrDefault(currencyCode, currencyCode);

        StringBuilder result = new StringBuilder();
        result.append(convertWholeNumber(rupees)).append(" ").append(currencyName);
        if (paise > 0) {
            result.append(" and ").append(convertWholeNumber(paise)).append(" Paise");
        }
        result.append(" Only");
        return result.toString();
    }

    private static String convertWholeNumber(long number) {
        if (number == 0) {
            return "Zero";
        }

        StringBuilder words = new StringBuilder();
        long crore = number / 10000000;
        number %= 10000000;
        long lakh = number / 100000;
        number %= 100000;
        long thousand = number / 1000;
        number %= 1000;
        long hundred = number / 100;
        number %= 100;

        if (crore > 0) words.append(convertUnderThousand(crore)).append(" Crore ");
        if (lakh > 0) words.append(convertUnderThousand(lakh)).append(" Lakh ");
        if (thousand > 0) words.append(convertUnderThousand(thousand)).append(" Thousand ");
        if (hundred > 0) words.append(UNDER_TWENTY[(int) hundred]).append(" Hundred ");
        if (number > 0) {
            if (!words.isEmpty()) words.append("and ");
            words.append(convertUnderHundred((int) number));
        }

        return words.toString().trim();
    }

    private static String convertUnderThousand(long number) {
        if (number < 100) {
            return convertUnderHundred((int) number);
        }
        long hundred = number / 100;
        long rest = number % 100;
        String result = UNDER_TWENTY[(int) hundred] + " Hundred";
        if (rest > 0) {
            result += " " + convertUnderHundred((int) rest);
        }
        return result;
    }

    private static String convertUnderHundred(int number) {
        if (number < 20) {
            return UNDER_TWENTY[number];
        }
        int tens = number / 10;
        int units = number % 10;
        return units == 0 ? TENS[tens] : TENS[tens] + "-" + UNDER_TWENTY[units];
    }
}
