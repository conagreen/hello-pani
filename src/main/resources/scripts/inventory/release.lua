-- KEYS[1] = stock:{productId}
-- KEYS[2] = hold:{checkoutId}
-- Returns: 1 = released (stock incremented, hold deleted), 0 = noop (no hold present)

if redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end

redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
return 1
