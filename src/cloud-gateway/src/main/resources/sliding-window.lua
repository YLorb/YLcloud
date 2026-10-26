-- Exact (now - 60s, now] admission window. Redis TIME avoids cross-instance clock skew.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local window = 60000
-- A lost database must not reset everybody's allowance inside a live window.
local initialized = redis.call('GET', KEYS[2])
if not initialized then
    redis.call('SET', KEYS[2], now)
    return {-1, 60}
end
if now - tonumber(initialized) < window then return {-1, 60} end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= 60 then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {0, math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))}
end
redis.call('ZADD', KEYS[1], now, ARGV[1])
redis.call('PEXPIRE', KEYS[1], window + 1000)
return {1, 0}
