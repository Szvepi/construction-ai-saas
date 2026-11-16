package com.buildassist.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

public final class PhoneNumberUtils {

    private static final String DEFAULT_REGION = "HU";
    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumberUtils() {
    }

    public static String normalizeOptionalHungarianPhoneNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        PhoneNumber parsedNumber;
        try {
            parsedNumber = PHONE_NUMBER_UTIL.parse(value.trim(), DEFAULT_REGION);
        } catch (NumberParseException exception) {
            throw invalidPhoneNumber(exception);
        }

        if (!PHONE_NUMBER_UTIL.isValidNumber(parsedNumber) || parsedNumber.hasExtension()) {
            throw invalidPhoneNumber(null);
        }
        return PHONE_NUMBER_UTIL.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    public static String formatForDisplay(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        try {
            PhoneNumber parsedNumber = PHONE_NUMBER_UTIL.parse(value.trim(), DEFAULT_REGION);
            return PHONE_NUMBER_UTIL.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
        } catch (NumberParseException exception) {
            return value;
        }
    }

    private static IllegalArgumentException invalidPhoneNumber(NumberParseException cause) {
        String message = "A telefonszám nem érvényes. Magyar számnál használj 06 vagy +36, külföldinél + országkódot.";
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
