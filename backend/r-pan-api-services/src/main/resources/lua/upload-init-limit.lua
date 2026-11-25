-- Integer token bucket: one token costs interval milliseconds of credit.
-- Only bounded O(1) commands; Redis TIME avoids differences between app clocks.
-- 读取参数并校验
local capacity = tonumber(ARGV[1])
local interval = tonumber(ARGV[2])
if not capacity or capacity < 1 or capacity > 1000 or not interval or interval < 100 or interval > 3600000 then
    return redis.error_reply('invalid upload limiter parameters')
end
-- 获取时间，计算满桶额度
-- TIME 返回秒和微秒，两者合成当前毫秒时间戳
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local limit = capacity * interval
local values = redis.call('HMGET', KEYS[1], 'credit', 'updated')
local credit = tonumber(values[1])
local updated = tonumber(values[2])
-- 读取桶，不存在则初始化
if not credit or not updated then
    credit = limit
    updated = now
end
-- Clock rollback must not refill the bucket or move its logical clock backward.
-- 按经过的时间补充额度
local elapsed = math.max(0, now - updated)
credit = math.min(limit, credit + elapsed)
-- 判断能不能放行
local allowed = 0
local retry = 0
-- 额度足够时，扣除一次请求的成本
if credit >= interval then
    credit = credit - interval
    allowed = 1
else
    -- 额度不足时，不扣额度，计算等待时间
    retry = interval - credit + math.max(0, updated - now)
end
-- 保存结果，设置过期时间
redis.call('HSET', KEYS[1], 'credit', credit, 'updated', math.max(now, updated))
-- A missing bucket may start full only after it would have refilled completely.
redis.call('PEXPIRE', KEYS[1], limit + math.max(0, updated - now) + 1000)
return {allowed, retry}
