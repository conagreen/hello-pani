-- KEYS[1] = stock:{productId}
-- KEYS[2] = hold:{checkoutId}
-- ARGV[1] = productId (string)
-- ARGV[2] = userId
-- ARGV[3] = holdTtlMillis (string)
-- Returns: 1 = acquired (or idempotent re-acquire), 0 = sold out / no stock counter

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end

local stock = redis.call('GET', KEYS[1])
if not stock then
    return 0
end

local remaining = tonumber(stock)
if remaining == nil or remaining <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[2], 'productId', ARGV[1], 'userId', ARGV[2])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return 1
