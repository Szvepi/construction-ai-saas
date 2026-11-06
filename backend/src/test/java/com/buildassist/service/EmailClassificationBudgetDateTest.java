package com.buildassist.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.buildassist.config.AppProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class EmailClassificationBudgetDateTest {

    @Test
    void utcBudgetPeriodRollsAtMidnightUtc() {
        AppProperties properties = new AppProperties();
        properties.getEmailClassification().setDailyBudgetTimeZone("UTC");
        properties.getEmailClassification().setMaxPerDay(1_000);
        properties.getEmailClassification().setMaxPerUserPerDay(150);
        properties.getEmailClassification().setMaxCostUsdPerDay(new BigDecimal("10.00"));
        properties.getEmailClassification().setInputPriceUsdPerMillionTokens(new BigDecimal("0.15"));
        properties.getEmailClassification().setOutputPriceUsdPerMillionTokens(new BigDecimal("0.60"));
        properties.getEmailClassification().setMaxInputTokens(4_000);
        properties.getEmailClassification().setMaxOutputTokens(256);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T23:59:59Z"), ZoneOffset.UTC);
        EmailClassificationBudgetService budgets = new EmailClassificationBudgetService(
                org.mockito.Mockito.mock(JdbcTemplate.class),
                properties,
                clock,
                org.mockito.Mockito.mock(PlatformTransactionManager.class));

        assertEquals(LocalDate.of(2026, 10, 22), budgets.currentBudgetDate());
        assertEquals(Instant.parse("2026-10-23T00:00:00Z"), budgets.nextBudgetPeriod());
    }

    @Test
    void budgetDayAndNextBoundaryUseConfiguredZoneAcrossDaylightSavingTransition() {
        AppProperties properties = new AppProperties();
        properties.getEmailClassification().setDailyBudgetTimeZone("America/New_York");
        properties.getEmailClassification().setMaxPerDay(1_000);
        properties.getEmailClassification().setMaxPerUserPerDay(150);
        properties.getEmailClassification().setMaxCostUsdPerDay(new BigDecimal("10.00"));
        properties.getEmailClassification().setInputPriceUsdPerMillionTokens(new BigDecimal("0.15"));
        properties.getEmailClassification().setOutputPriceUsdPerMillionTokens(new BigDecimal("0.60"));
        properties.getEmailClassification().setMaxInputTokens(4_000);
        properties.getEmailClassification().setMaxOutputTokens(256);
        Clock clock = Clock.fixed(Instant.parse("2026-03-08T04:30:00Z"), ZoneId.of("UTC"));
        EmailClassificationBudgetService budgets = new EmailClassificationBudgetService(
                org.mockito.Mockito.mock(JdbcTemplate.class),
                properties,
                clock,
                org.mockito.Mockito.mock(PlatformTransactionManager.class));

        assertEquals(LocalDate.of(2026, 3, 7), budgets.currentBudgetDate());
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), budgets.nextBudgetPeriod());
    }
}
