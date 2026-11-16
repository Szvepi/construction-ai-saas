package com.buildassist.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNumberUtilsTest {

    @Test
    void normalizesHungarianNationalAndInternationalInputsToE164() {
        assertEquals("+36301234567",
                PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("06 30 123 4567"));
        assertEquals("+36301234567",
                PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("0036 30 123 4567"));
        assertEquals("+36301234567",
                PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("+36 30 123 4567"));
        assertEquals("+16502530000",
                PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("+1 650 253 0000"));
    }

    @Test
    void rejectsInvalidPhoneNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("06 30 12"));
        assertThrows(IllegalArgumentException.class,
                () -> PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber("+36 30 123 4567 ext 12"));
    }

    @Test
    void formatsStoredE164NumberForDisplay() {
        assertEquals("+36 30 123 4567", PhoneNumberUtils.formatForDisplay("+36301234567"));
    }
}
