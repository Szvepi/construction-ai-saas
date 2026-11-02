package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisGmailRequestLimiterTest {

    @Test
    void limitsAreScopedToBothAccountAndConfiguredProjectAndChargeMethodUnits() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AppProperties properties = new AppProperties();
        properties.getGmail().setQuotaProjectKey("gcp-project-1");
        properties.getGmail().setProjectQuotaUnitsPerMinute(10_000);
        properties.getGmail().setUserQuotaUnitsPerMinute(1_000);
        properties.getGmail().setQuotaSafetyMargin(0.2);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisGmailRequestLimiter limiter = new RedisGmailRequestLimiter(redis, properties);

        limiter.acquire(41L, GmailApiOperation.MESSAGES_GET_METADATA);
        limiter.acquire(41L, GmailApiOperation.MESSAGES_GET_FULL);
        limiter.acquire(42L, GmailApiOperation.MESSAGES_LIST);

        org.mockito.ArgumentCaptor<List<String>> keys =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<Object[]> arguments =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(3))
                .execute(any(RedisScript.class), keys.capture(), arguments.capture());

        assertEquals("gmail:quota:user:41", keys.getAllValues().get(0).get(0));
        assertEquals("gmail:quota:project:gcp-project-1", keys.getAllValues().get(0).get(1));
        assertEquals("gmail:concurrent:41", keys.getAllValues().get(0).get(2));
        assertEquals("gmail:quota:user:41", keys.getAllValues().get(1).get(0));
        assertEquals("gmail:quota:user:42", keys.getAllValues().get(2).get(0));
        assertEquals(keys.getAllValues().get(0).get(1), keys.getAllValues().get(1).get(1));
        assertEquals(keys.getAllValues().get(0).get(1), keys.getAllValues().get(2).get(1));
        assertEquals("20", arguments.getAllValues().get(0)[1]);
        assertEquals("60", arguments.getAllValues().get(1)[1]);
        assertEquals("800", arguments.getAllValues().get(0)[2]);
        assertEquals("8000", arguments.getAllValues().get(0)[3]);
        assertEquals("5", arguments.getAllValues().get(2)[1]);
    }

    @Test
    void chargesFullFormatAndShowsFiftyMessageSavings() {
        AppProperties.Gmail properties = new AppProperties().getGmail();
        int previousMetadataAndFullCost = 50 * (GmailApiOperation.MESSAGES_GET_METADATA.quotaUnits(properties)
                + GmailApiOperation.MESSAGES_GET_FULL.quotaUnits(properties)) + 5;
        int optimizedFullCost = 50 * GmailApiOperation.MESSAGES_GET_FULL.quotaUnits(properties) + 5;

        assertEquals(4_005, previousMetadataAndFullCost);
        assertEquals(3_005, optimizedFullCost);
        assertEquals(1_000, previousMetadataAndFullCost - optimizedFullCost);
    }

    @Test
    void returnsDeferredRetryTimeWhenTheRedisBudgetIsExhausted() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-5_000L);
        RedisGmailRequestLimiter limiter = new RedisGmailRequestLimiter(redis, new AppProperties());

        GmailRateLimitDeferredException deferred = assertThrows(
                GmailRateLimitDeferredException.class,
                () -> limiter.acquire(41L, GmailApiOperation.MESSAGES_GET_FULL));

        assertTrue(deferred.getRetryAt().isAfter(java.time.Instant.now().plusSeconds(4)));
        assertEquals("quota_or_concurrency", deferred.getReason());
    }
}
