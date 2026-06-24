local field, readSeq = ARGV[1], tonumber(ARGV[2])
local current = redis.call('HGET', KEYS[1], field)
if not current then return 1 end
if readSeq == tonumber(current) then
    redis.call('HDEL', KEYS[1], field)
    return 1
end
if readSeq > tonumber(current) then return -1 end
return 0
