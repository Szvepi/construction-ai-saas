package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisGmailRequestLimiter implements GmailRequestLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final long LEASE_MILLIS = 35_000;
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local cost = tonumber(ARGV[2])
            local userLimit = tonumber(ARGV[3])
            local projectLimit = tonumber(ARGV[4])
            local maxConcurrent = tonumber(ARGV[5])
            local requestId = ARGV[6]
            local leaseMillis = tonumber(ARGV[7])
            local windowMillis = tonumber(ARGV[8])
            local userKey = KEYS[1]
            local projectKey = KEYS[2]
            local concurrentKey = KEYS[3]

            local function usedUnits(key)
              redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMillis)
              local items = redis.call('ZRANGE', key, 0, -1)
              local total = 0
              for _, item in ipairs(items) do
                local units = string.match(item, '|(%d+)$')
                total = total + tonumber(units or '0')
              end
              return total
            end

            local userUsed = usedUnits(userKey)
            local projectUsed = usedUnits(projectKey)
            if userUsed + cost > userLimit or projectUsed + cost > projectLimit then
              local waitMillis = 1000
              local candidates = {}
              if userUsed + cost > userLimit then
                candidates[#candidates + 1] = userKey
              end
              if projectUsed + cost > projectLimit then
                candidates[#candidates + 1] = projectKey
              end
              local earliest = nil
              for _, key in ipairs(candidates) do
                local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                if first[2] then
                  local releaseAt = tonumber(first[2]) + windowMillis - now
                  if earliest == nil or releaseAt > earliest then
                    earliest = releaseAt
                  end
                end
              end
              if earliest and earliest > waitMillis then waitMillis = earliest end
              return -waitMillis
            end

            redis.call('ZREMRANGEBYSCORE', concurrentKey, '-inf', now)
            local concurrentCount = redis.call('ZCARD', concurrentKey)
            if concurrentCount >= maxConcurrent then
              local first = redis.call('ZRANGE', concurrentKey, 0, 0, 'WITHSCORES')
              local waitMillis = 1000
              if first[2] then
                waitMillis = math.max(1000, tonumber(first[2]) - now)
              end
              return -waitMillis
            end

            redis.call('ZADD', userKey, now, requestId .. '|' .. cost)
            redis.call('ZADD', projectKey, now, requestId .. '|' .. cost)
            redis.call('ZADD', concurrentKey, now + leaseMillis, requestId)
            redis.call('PEXPIRE', userKey, windowMillis + 5000)
            redis.call('PEXPIRE', projectKey, windowMillis + 5000)
            redis.call('PEXPIRE', concurrentKey, leaseMillis + 5000)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('ZREM', KEYS[1], ARGV[1])",
            Long.class);

    private final StringRedisTemplate redis;
    private final AppProperties.Gmail properties;

    public RedisGmailRequestLimiter(StringRedisTemplate redis, AppProperties appProperties) {
        this.redis = redis;
        this.properties = appProperties.getGmail();
        validateConfiguration();
    }

    @Override
    public Permit acquire(Long connectionId, GmailApiOperation operation) {
        long now = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        long projectLimit = effectiveLimit(properties.getProjectQuotaUnitsPerMinute());
        long userLimit = effectiveLimit(properties.getUserQuotaUnitsPerMinute());
        Long result = redis.execute(
                ACQUIRE_SCRIPT,
                List.of(
                        "gmail:quota:user:" + connectionId,
                        "gmail:quota:project:" + properties.getQuotaProjectKey(),
                        "gmail:concurrent:" + connectionId),
                Long.toString(now),
                Integer.toString(operation.quotaUnits(properties)),
                Long.toString(userLimit),
                Long.toString(projectLimit),
                Integer.toString(properties.getMaxConcurrentRequestsPerAccount()),
                requestId,
                Long.toString(LEASE_MILLIS),
                Long.toString(WINDOW_MILLIS));
        if (result == null) {
            throw new IllegalStateException("Redis did not return a Gmail request permit");
        }
        if (result < 1L) {
            throw new GmailRateLimitDeferredException(
                    Instant.ofEpochMilli(now + Math.max(1000, -result)),
                    "quota_or_concurrency");
        }
        return () -> redis.execute(RELEASE_SCRIPT,
                List.of("gmail:concurrent:" + connectionId), requestId);
    }

    private long effectiveLimit(long configuredLimit) {
        return Math.max(1, (long) Math.floor(configuredLimit * (1 - properties.getQuotaSafetyMargin())));
    }

    private void validateConfiguration() {
        if (properties.getProjectQuotaUnitsPerMinute() < 1
                || properties.getUserQuotaUnitsPerMinute() < 1
                || properties.getMaxConcurrentRequestsPerAccount() < 1
                || properties.getQuotaSafetyMargin() < 0
                || properties.getQuotaSafetyMargin() >= 1
                || properties.getQuotaProjectKey() == null
                || properties.getQuotaProjectKey().isBlank()) {
            throw new IllegalArgumentException("Invalid Gmail quota limiter configuration");
        }
    }
}
