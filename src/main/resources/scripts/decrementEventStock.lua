-- src/main/resources/scripts/decrement_stock.lua

-- KEYS[1]: 재고 키 (예: remaining_stock:{eventId})
-- ARGV[1]: 차감할 수량 (보통 1)

local stockKey = KEYS[1]
local quantity = tonumber(ARGV[1])

-- 1. 현재 재고 조회
local currentStock = redis.call('GET', stockKey)

-- 2. 재고가 없거나 품절인지 확인
if not currentStock then
    return -1 -- 에러: 존재하지 않는 이벤트 키
end

currentStock = tonumber(currentStock)

if currentStock < quantity then
    return 0 -- 실패: 재고 부족 (SOLD_OUT)
end

-- 3. 재고 차감 후 남은 재고 반환
redis.call('DECRBY', stockKey, quantity)
return 1 -- 성공