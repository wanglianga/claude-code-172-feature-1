#!/usr/bin/env bash
# 冒烟验证：主流程 + 全部异常场景 + 三端视图
# 用法: BASE=http://localhost:8080 ./scripts/smoke.sh
# 依赖: curl, jq
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
FAILS=0

say()  { echo ""; echo "== $* =="; }
ok()   { echo "  [PASS] $1"; }
bad()  { echo "  [FAIL] $1"; FAILS=$((FAILS+1)); }
check(){ # desc actual expected
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: 期望[$3] 实际[$2]"; fi
}

req() { # method path token [data]
  local method=$1 path=$2 token=${3:-} data=${4:-}
  local args=(-sS -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  curl "${args[@]}"
}

wait_event() { # deliveryId eventType tries
  local did=$1 type=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    if req GET "/api/deliveries/$did/events" "$OPS" | jq -e ".[] | select(.eventType==\"$type\")" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_status() { # deliveryId status tries
  local did=$1 st=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    local cur
    cur=$(req GET "/api/orders/$did" "$OPS" | jq -r '.status // empty')
    [ "$cur" = "$st" ] && return 0
    sleep 1
  done
  return 1
}

new_order() { # buildingId coldChain type -> order id
  req POST /api/orders "$OPS" "{
    \"orderType\":\"${3:-FOOD}\",\"communityId\":1,\"buildingId\":$1,
    \"unitNo\":\"2单元\",\"roomNo\":\"901\",\"coldChain\":$2,
    \"estimatedDeliveryAt\":\"2026-12-31T23:59:00\",
    \"userName\":\"张三\",\"userPhone\":\"13800000001\"}" | jq -r '.id'
}

new_order_eta() { # buildingId coldChain type etaIso -> order id
  req POST /api/orders "$OPS" "{
    \"orderType\":\"${3:-FOOD}\",\"communityId\":1,\"buildingId\":$1,
    \"unitNo\":\"2单元\",\"roomNo\":\"901\",\"coldChain\":$2,
    \"estimatedDeliveryAt\":\"$4\",
    \"userName\":\"张三\",\"userPhone\":\"13800000001\"}" | jq -r '.id'
}

# 连续三次电梯满载：ER1->full->ER2->full->ER3->full（触发等待策略）
three_full() { # orderId fromFloor toFloor -> 输出 "ER1 ER2 ER3 FINAL"
  local oid=$1 f=$2 t=$3
  local er1 er2 er3 final
  er1=$(req POST "/api/deliveries/$oid/request-elevator" "$OPS" "{\"fromFloor\":$f,\"toFloor\":$t}" | jq -r .id)
  er2=$(req POST "/api/elevator-requests/$er1/full" "$OPS" | jq -r .id)
  er3=$(req POST "/api/elevator-requests/$er2/full" "$OPS" | jq -r .id)
  final=$(req POST "/api/elevator-requests/$er3/full" "$OPS" | jq -r .id)
  echo "$er1 $er2 $er3 $final"
}

gate_approve() { # orderId gateName
  local g
  g=$(req POST "/api/deliveries/$1/arrive-gate" "$OPS" "{\"gateName\":\"${2:-南门}\"}" | jq -r .id)
  req POST "/api/access-requests/$g/approve" "$PROPERTY" >/dev/null
}

say "0. 认证与权限"
UNAUTH=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/orders")
check "未登录访问受保护接口返回401" "$UNAUTH" "401"
ADMIN=$(req POST /api/auth/login "" '{"username":"admin","password":"admin123"}'   | jq -r .token)
OPS=$(req POST /api/auth/login "" '{"username":"ops","password":"ops123"}'         | jq -r .token)
PROPERTY=$(req POST /api/auth/login "" '{"username":"property","password":"property123"}' | jq -r .token)
CS=$(req POST /api/auth/login "" '{"username":"cs","password":"cs123"}'            | jq -r .token)
[ -n "$OPS" ] && [ "$OPS" != "null" ] && ok "四个测试账号登录成功" || { bad "登录失败"; exit 1; }
FORBID=$(req POST /api/orders "$PROPERTY" '{"orderType":"FOOD","communityId":1,"buildingId":1,"userName":"x","userPhone":"1"}' | jq -r .status)
check "物业账号创建订单被拒绝(403)" "$FORBID" "403"

say "准备：关闭社区1夜间限制（避免测试时段落入夜间窗口）"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":false,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null

say "1. 主流程：创建->分配->门禁->电梯->避障->到门通知->开仓->签收"
O1=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O1/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE=$(req POST "/api/deliveries/$O1/arrive-gate" "$OPS" '{"gateName":"南门"}')
AR_ID=$(echo "$GATE" | jq -r .id)
check "门禁申请生成" "$(echo "$GATE" | jq -r .status)" "PENDING"
req POST "/api/access-requests/$AR_ID/approve" "$PROPERTY" >/dev/null
EV_REQ=$(req POST "/api/deliveries/$O1/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":9}')
ER_ID=$(echo "$EV_REQ" | jq -r .id)
req POST "/api/elevator-requests/$ER_ID/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$ER_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O1/obstacle" "$OPS" '{"obstacleType":"ELDERLY_CHILD","waitSeconds":30,"location":"1栋大堂"}' >/dev/null
req POST "/api/deliveries/$O1/arrive-door" "$OPS" >/dev/null
NOTIFY=$(req GET "/api/notifications?deliveryId=$O1" "$OPS" | jq 'length')
check "短信+小程序双通道通知" "$NOTIFY" "2"
req POST "/api/deliveries/$O1/open-compartment" "$OPS" >/dev/null
SIGN=$(req POST "/api/deliveries/$O1/sign" "$OPS" '{"signBy":"张三"}')
check "签收后状态" "$(echo "$SIGN" | jq -r .status)" "SIGNED"
ROBOT1=$(req GET "/api/robots?communityId=1" "$OPS" | jq -r '.[] | select(.id==1) | .status')
check "签收后机器人释放为空闲" "$ROBOT1" "IDLE"
if wait_event "$O1" SIGNED; then ok "统一事件流含 SIGNED"; else bad "统一事件流缺少 SIGNED"; fi
EV_COUNT=$(req GET "/api/deliveries/$O1/events" "$OPS" | jq 'length')
[ "$EV_COUNT" -ge 12 ] && ok "订单1事件时间线完整($EV_COUNT条)" || bad "订单1事件过少: $EV_COUNT"
for T in ORDER_CREATED ROBOT_ASSIGNED ACCESS_REQUESTED ACCESS_GRANTED ELEVATOR_REQUESTED ELEVATOR_ASSIGNED ELEVATOR_COMPLETED OBSTACLE_WAIT ARRIVED_AT_DOOR USER_NOTIFIED COMPARTMENT_OPENED SIGNED; do
  if wait_event "$O1" "$T" 3; then ok "事件 $T 已写入统一事件流"; else bad "缺少事件 $T"; fi
done

say "2. 异常：物业门禁拒绝 -> 客服介入 -> 取消"
O2=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O2/assign" "$OPS" '{"robotId":1}' >/dev/null
AR2=$(req POST "/api/deliveries/$O2/arrive-gate" "$OPS" '{"gateName":"北门"}' | jq -r .id)
req POST "/api/access-requests/$AR2/deny" "$PROPERTY" '{"reason":"外来设备未备案"}' >/dev/null
check "门禁拒绝后订单状态" "$(req GET "/api/orders/$O2" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O2" ACCESS_DENIED && ok "ACCESS_DENIED 事件" || bad "缺少 ACCESS_DENIED"
wait_event "$O2" CS_INTERVENTION && ok "客服介入事件 CS_INTERVENTION" || bad "缺少 CS_INTERVENTION"
req POST "/api/deliveries/$O2/cancel" "$OPS" '{"reason":"门禁被拒，取消配送"}' >/dev/null

say "3. 异常：电梯满载 -> 自动等待下一趟(路线调整)"
O3=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O3/assign" "$OPS" '{"robotId":1}' >/dev/null
AR3=$(req POST "/api/deliveries/$O3/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR3/approve" "$PROPERTY" >/dev/null
ER3=$(req POST "/api/deliveries/$O3/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":6}' | jq -r .id)
RETRY=$(req POST "/api/elevator-requests/$ER3/full" "$OPS")
RETRY_ID=$(echo "$RETRY" | jq -r .id)
[ "$RETRY_ID" != "$ER3" ] && [ "$RETRY_ID" != "null" ] && ok "满载后自动重新请求电梯(新请求#$RETRY_ID)" || bad "满载后未自动重新请求"
wait_event "$O3" ELEVATOR_FULL && ok "ELEVATOR_FULL 事件" || bad "缺少 ELEVATOR_FULL"
wait_event "$O3" ROUTE_ADJUSTED && ok "ROUTE_ADJUSTED(电梯满载) 事件" || bad "缺少 ROUTE_ADJUSTED"
req POST "/api/elevator-requests/$RETRY_ID/assign" "$OPS" '{"carId":"1栋-货梯B"}' >/dev/null
req POST "/api/elevator-requests/$RETRY_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/sign" "$OPS" '{"signBy":"李女士"}' >/dev/null
check "满载重试后签收" "$(req GET "/api/orders/$O3" "$OPS" | jq -r .status)" "SIGNED"

say "3.5 连续三次满载策略：下单基础配送费"
O_FEE=$(new_order 1 false FOOD)
check "新订单初始配送费=基础费5.00(分)" "$(req GET "/api/orders/$O_FEE" "$OPS" | jq -r '(.deliveryFee*100|floor)')" "500"
req POST "/api/deliveries/$O_FEE/cancel" "$OPS" '{"reason":"费用校验用单"}' >/dev/null

say "3.6 连续三次满载 -> 策略=继续等待（时效充裕+高楼层）-> 第四次派梯成功"
O3W=$(new_order 2 false FOOD)
req POST "/api/deliveries/$O3W/assign" "$OPS" '{"robotId":1}' >/dev/null
gate_approve "$O3W"
read -r W1 W2 W3 W4 <<< "$(three_full "$O3W" 1 13)"
[ "$W3" != "$W4" ] && ok "第三次满载决策继续等待后产生第4次电梯请求#$W4" || bad "继续等待未重新请求电梯"
check "连续满载计数=3" "$(req GET "/api/orders/$O3W" "$OPS" | jq -r .elevatorFullCount)" "3"
check "等待策略" "$(req GET "/api/orders/$O3W" "$OPS" | jq -r .waitStrategy)" "WAIT_ELEVATOR"
wait_event "$O3W" ELEVATOR_WAIT_STRATEGY && ok "ELEVATOR_WAIT_STRATEGY 事件" || bad "缺少策略决策事件"
req GET "/api/deliveries/$O3W/events" "$OPS" \
  | jq -e '[.[] | select(.eventType=="ELEVATOR_FULL")] | length == 3' >/dev/null \
  && ok "ELEVATOR_FULL 事件恰好3次" || bad "ELEVATOR_FULL 次数不为3"
req POST "/api/elevator-requests/$W4/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$W4/complete" "$OPS" >/dev/null
check "乘梯成功后满载计数清零" "$(req GET "/api/orders/$O3W" "$OPS" | jq -r .elevatorFullCount)" "0"
req POST "/api/deliveries/$O3W/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O3W/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O3W/sign" "$OPS" '{"signBy":"继续等待单用户"}' >/dev/null
check "继续等待策略订单签收" "$(req GET "/api/orders/$O3W" "$OPS" | jq -r .status)" "SIGNED"

say "3.7 连续三次满载 -> 策略=人工接力（低楼层）-> 身份/开仓/接货/签收/费用重算/分阶段责任"
PAST_ETA=$(date +%Y-%m-%dT%H:%M:%S)
O3R=$(new_order_eta 1 false MEDICINE "$PAST_ETA")
req POST "/api/deliveries/$O3R/assign" "$OPS" '{"robotId":1}' >/dev/null
gate_approve "$O3R"
read -r R1 R2 R3 RF <<< "$(three_full "$O3R" 1 6)"
[ "$RF" = "$R3" ] && ok "人工接力策略不再产生新电梯请求" || bad "人工接力策略仍在重试电梯"
check "人工接力策略" "$(req GET "/api/orders/$O3R" "$OPS" | jq -r .waitStrategy)" "MANUAL_RELAY"
check "策略后订单仍等待电梯(等派单)" "$(req GET "/api/orders/$O3R" "$OPS" | jq -r .status)" "ELEVATOR_WAIT"
HO=$(req POST /api/relays "$OPS" "{\"deliveryId\":$O3R,\"courierName\":\"接力员老赵\",\"courierPhone\":\"13900001111\",\"courierCompany\":\"物业便民配送队\",\"courierEmployeeNo\":\"EMP-88\"}")
HID=$(echo "$HO" | jq -r .id)
[ -n "$HID" ] && [ "$HID" != "null" ] && ok "生成接力交接单#$HID 并登记人员身份" || bad "接力派单失败"
check "派单后订单状态" "$(req GET "/api/orders/$O3R" "$OPS" | jq -r .status)" "RELAYING"
check "接力重算费用=5+8+0.5*5=15.50(分)" "$(echo "$HO" | jq -r '(.fee*100|floor)')" "1550"
check "订单费用同步重算(分)" "$(req GET "/api/orders/$O3R" "$OPS" | jq -r '(.deliveryFee*100|floor)')" "1550"
wait_event "$O3R" RELAY_DISPATCHED && ok "RELAY_DISPATCHED 事件(含身份)" || bad "缺少 RELAY_DISPATCHED"
wait_event "$O3R" RELAY_FEE_RECALCULATED && ok "RELAY_FEE_RECALCULATED 事件" || bad "缺少费用重算事件"
req POST "/api/relays/$HID/open-compartment" "$OPS" >/dev/null
wait_event "$O3R" RELAY_COMPARTMENT_OPENED && ok "仓门开启 RELAY_COMPARTMENT_OPENED 事件" || bad "缺少仓门开启事件"
# 接货时发现损坏：商品此前在机器人载物仓 -> 机器人等待阶段责任
req POST "/api/relays/$HID/handover" "$OPS" '{"damageReported":true,"damageNote":"汤品外包装有挤压破损"}' >/dev/null
wait_event "$O3R" RELAY_HANDOVER && ok "RELAY_HANDOVER 接货事件" || bad "缺少接货事件"
wait_event "$O3R" DAMAGE_REPORTED && ok "DAMAGE_REPORTED 事件" || bad "缺少损坏上报事件"
check "接货后机器人释放" "$(req GET "/api/robots?communityId=1" "$OPS" | jq -r '.[] | select(.id==1) | .status')" "IDLE"
ROBOT_DAMAGE_CASE=$(req GET "/api/cs/cases?caseType=RELAY_DAMAGE" "$CS" | jq -r "[.[] | select(.deliveryId==$O3R)] | .[0].id // empty")
[ -n "$ROBOT_DAMAGE_CASE" ] \
  && check "接货损坏责任=机器人等待阶段(ROBOT_VENDOR)" \
       "$(req GET "/api/cs/cases?caseType=RELAY_DAMAGE" "$CS" | jq -r ".[] | select(.id==$ROBOT_DAMAGE_CASE) | .responsibility")" "ROBOT_VENDOR" \
  || bad "未生成接货损坏工单"
# ETA 已过 -> 接力送达签收时自动按人工接力阶段延迟定责
req POST "/api/relays/$HID/sign" "$OPS" '{"signBy":"药品用户陈先生"}' >/dev/null
check "接力签收后订单状态" "$(req GET "/api/orders/$O3R" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O3R" RELAY_SIGNED && ok "RELAY_SIGNED 事件" || bad "缺少 RELAY_SIGNED"
DELAY_CASE=$(req GET "/api/cs/cases?caseType=RELAY_DELAY" "$CS" | jq -r "[.[] | select(.deliveryId==$O3R)] | .[0].id // empty")
[ -n "$DELAY_CASE" ] \
  && check "接力延迟责任=人工接力阶段(RELAY_COURIER)" \
       "$(req GET "/api/cs/cases?caseType=RELAY_DELAY" "$CS" | jq -r ".[] | select(.id==$DELAY_CASE) | .responsibility")" "RELAY_COURIER" \
  || bad "未生成接力延迟工单"

say "3.8 人工接力阶段商品损坏 -> RELAY_COURIER 责任 + 减免接力服务费并重算费用"
O3R2=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O3R2/assign" "$OPS" '{"robotId":1}' >/dev/null
gate_approve "$O3R2"
three_full "$O3R2" 1 6 >/dev/null
HID2=$(req POST /api/relays "$OPS" "{\"deliveryId\":$O3R2,\"courierName\":\"接力员小钱\",\"courierEmployeeNo\":\"EMP-99\"}" | jq -r .id)
req POST "/api/relays/$HID2/open-compartment" "$OPS" >/dev/null
req POST "/api/relays/$HID2/handover" "$OPS" >/dev/null
req POST "/api/relays/$HID2/sign" "$OPS" '{"signBy":"商超用户孙女士","damageReported":true,"damageNote":"鸡蛋破碎6枚"}' >/dev/null
check "接力损坏后费用减免=5+0.5*5=7.50(分)" "$(req GET "/api/orders/$O3R2" "$OPS" | jq -r '(.deliveryFee*100|floor)')" "750"
check "交接单记录损坏" "$(req GET "/api/relays/$HID2" "$CS" | jq -r .damageReported)" "true"
check "接力损坏责任" "$(req GET "/api/cs/cases?caseType=RELAY_DAMAGE" "$CS" | jq -r "[.[] | select(.deliveryId==$O3R2)][0].responsibility // empty")" "RELAY_COURIER"

say "3.9 连续三次满载 -> 策略=通知用户下楼取件（时效紧急+高楼层）-> 楼下签收"
O3U=$(req POST /api/orders "$OPS" '{
    "orderType":"FOOD","communityId":1,"buildingId":1,
    "unitNo":"2单元","roomNo":"1801","coldChain":false,
    "userName":"紧急用户","userPhone":"13800009999"}' | jq -r .id)
req POST "/api/deliveries/$O3U/assign" "$OPS" '{"robotId":1}' >/dev/null
gate_approve "$O3U"
three_full "$O3U" 1 18 >/dev/null
check "楼下取件策略" "$(req GET "/api/orders/$O3U" "$OPS" | jq -r .waitStrategy)" "USER_PICKUP_DOWNSTAIRS"
check "订单状态=DOWNSTAIRS_PICKUP" "$(req GET "/api/orders/$O3U" "$OPS" | jq -r .status)" "DOWNSTAIRS_PICKUP"
wait_event "$O3U" USER_DOWNSTAIRS_NOTIFIED && ok "USER_DOWNSTAIRS_NOTIFIED 通知事件" || bad "缺少下楼通知事件"
check "下楼短信+小程序双通道" "$(req GET "/api/notifications?deliveryId=$O3U" "$OPS" | jq 'length')" "2"
check "楼下取件未开仓不能普通签收(409)" "$(req POST "/api/deliveries/$O3U/sign" "$OPS" '{"signBy":"x"}' | jq -r .status)" "409"
req POST "/api/deliveries/$O3U/downstairs-open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O3U/downstairs-sign" "$OPS" '{"signBy":"紧急用户本人","damageReported":true,"damageNote":"餐盒已被打开过"}' >/dev/null
check "楼下签收后状态" "$(req GET "/api/orders/$O3U" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O3U" DOWNSTAIRS_SIGNED && ok "DOWNSTAIRS_SIGNED 事件" || bad "缺少 DOWNSTAIRS_SIGNED"
check "取件阶段损坏责任=USER" "$(req GET "/api/cs/cases?caseType=PICKUP_DAMAGE" "$CS" | jq -r "[.[] | select(.deliveryId==$O3U)][0].responsibility // empty")" "USER"

say "3.10 通知下楼取件后用户超时未取 -> 自动退回（等待巡检，DOWNSTAIRS_PICKUP_TIMEOUT_SECONDS 需较短）"
O3T=$(req POST /api/orders "$OPS" '{
    "orderType":"FOOD","communityId":1,"buildingId":1,
    "unitNo":"3单元","roomNo":"1701","coldChain":false,
    "userName":"超时下楼用户","userPhone":"13800007777"}' | jq -r .id)
req POST "/api/deliveries/$O3T/assign" "$OPS" '{"robotId":1}' >/dev/null
gate_approve "$O3T"
three_full "$O3T" 1 18 >/dev/null
if wait_status "$O3T" RETURNING 90; then ok "下楼超时未取自动进入退回"; else bad "下楼取件未进入退回流程"; fi
req POST "/api/deliveries/$O3T/confirm-return" "$OPS" >/dev/null
check "下楼超时退回完成" "$(req GET "/api/orders/$O3T" "$OPS" | jq -r .status)" "RETURNED"

say "4. 异常：楼道施工 -> 避障停留+路线调整 -> 投诉送错楼栋 -> 客服结案"
O4=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O4/assign" "$OPS" '{"robotId":1}' >/dev/null
AR4=$(req POST "/api/deliveries/$O4/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR4/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O4/obstacle" "$OPS" '{"obstacleType":"CONSTRUCTION","waitSeconds":120,"location":"1栋3层楼道"}' >/dev/null
wait_event "$O4" OBSTACLE_WAIT && ok "OBSTACLE_WAIT(施工) 事件" || bad "缺少 OBSTACLE_WAIT"
req GET "/api/deliveries/$O4/events" "$OPS" | jq -e '.[] | select(.eventType=="ROUTE_ADJUSTED" and .reason=="CONSTRUCTION")' >/dev/null \
  && ok "ROUTE_ADJUSTED(reason=CONSTRUCTION) 事件" || bad "缺少施工路线调整事件"
req POST "/api/deliveries/$O4/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/sign" "$OPS" '{"signBy":"王先生"}' >/dev/null
CMP=$(req POST /api/complaints "$CS" "{\"deliveryId\":$O4,\"complaintType\":\"WRONG_BUILDING\",\"content\":\"用户称应送2栋而非1栋\"}")
CMP_CASE=$(req GET "/api/cs/cases?status=OPEN" "$CS" | jq -r ".[] | select(.deliveryId==$O4) | .id")
[ -n "$CMP_CASE" ] && [ "$CMP_CASE" != "null" ] && ok "投诉自动生成客服工单#$CMP_CASE" || bad "投诉未生成工单"
req POST "/api/cs/cases/$CMP_CASE/resolve" "$CS" '{"responsibility":"PLATFORM","note":"分拣贴错楼栋标签，已致歉"}' >/dev/null
check "结案后投诉状态" "$(req GET /api/complaints "$CS" | jq -r ".[] | select(.delivery.id==$O4) | .status")" "RESOLVED"
wait_event "$O4" CS_CASE_CLOSED && ok "CS_CASE_CLOSED 事件" || bad "缺少 CS_CASE_CLOSED"

say "5. 异常：低电量 -> 分配被拒 / 配送途中低电量 -> 人工接力"
O5=$(new_order 1 false MEDICINE)
LOW=$(req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}')
check "低电量机器人(RB-002,15%)分配被拒(409)" "$(echo "$LOW" | jq -r .status)" "409"
req POST /api/robots/2/status "$OPS" '{"battery":90}' >/dev/null
req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}' >/dev/null
check "充电后分配成功" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "ASSIGNED"
AR5=$(req POST "/api/deliveries/$O5/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR5/approve" "$PROPERTY" >/dev/null
req POST /api/robots/2/status "$OPS" '{"battery":8}' >/dev/null
wait_event "$O5" LOW_BATTERY && ok "配送途中 LOW_BATTERY 事件" || bad "缺少 LOW_BATTERY"
req POST "/api/deliveries/$O5/manual-relay" "$OPS" '{"courierName":"骑手小王"}' >/dev/null
req POST "/api/deliveries/$O5/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/sign" "$OPS" '{"signBy":"赵女士"}' >/dev/null
check "人工接力后签收" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O5" MANUAL_RELAY && ok "MANUAL_RELAY 事件" || bad "缺少 MANUAL_RELAY"

say "6. 异常：载物仓打不开 -> 客服介入 -> 改派楼栋(送错纠正) -> 签收"
O6=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O6/assign" "$OPS" '{"robotId":1}' >/dev/null
AR6=$(req POST "/api/deliveries/$O6/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR6/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/compartment-fault" "$OPS" >/dev/null
check "仓门故障后状态" "$(req GET "/api/orders/$O6" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O6" COMPARTMENT_OPEN_FAILED && ok "COMPARTMENT_OPEN_FAILED 事件" || bad "缺少 COMPARTMENT_OPEN_FAILED"
req POST "/api/deliveries/$O6/redeliver" "$CS" '{"buildingId":2}' >/dev/null
wait_event "$O6" ROUTE_ADJUSTED && ok "改派楼栋 ROUTE_ADJUSTED(WRONG_BUILDING) 事件" || bad "缺少改派事件"
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/sign" "$OPS" '{"signBy":"周先生"}' >/dev/null
check "改派2栋后签收" "$(req GET "/api/orders/$O6" "$OPS" | jq -r '".building=" + (.building.name|tostring) + " " + .status')" ".building=2栋 SIGNED"

say "7. 异常：用户超时不取 -> 自动退回（等待巡检，PICKUP_TIMEOUT_SECONDS 需较短）"
O7=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O7/assign" "$OPS" '{"robotId":1}' >/dev/null
AR7=$(req POST "/api/deliveries/$O7/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR7/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O7/arrive-door" "$OPS" >/dev/null
if wait_status "$O7" RETURNING 60; then ok "超时未取自动进入退回"; else bad "未进入退回流程"; fi
wait_event "$O7" PICKUP_TIMEOUT && ok "PICKUP_TIMEOUT 事件" || bad "缺少 PICKUP_TIMEOUT"
req POST "/api/deliveries/$O7/confirm-return" "$OPS" >/dev/null
check "退回完成" "$(req GET "/api/orders/$O7" "$OPS" | jq -r .status)" "RETURNED"
wait_event "$O7" RETURNED && ok "RETURNED 事件" || bad "缺少 RETURNED"

say "8. 异常：冷藏订单超时（等待巡检，COLDCHAIN_TIMEOUT_SECONDS 需较短）"
O8=$(new_order 1 true MEDICINE)
if wait_event "$O8" COLDCHAIN_TIMEOUT 90; then ok "COLDCHAIN_TIMEOUT 事件"; else bad "缺少 COLDCHAIN_TIMEOUT"; fi
check "冷藏超时标记" "$(req GET "/api/orders/$O8" "$OPS" | jq -r .coldChainTimedOut)" "true"
req POST "/api/deliveries/$O8/cancel" "$OPS" '{"reason":"冷藏超时，订单取消"}' >/dev/null

say "9. 异常：电梯请求超时（等待巡检，ELEVATOR_TIMEOUT_SECONDS 需较短）"
O9=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O9/assign" "$OPS" '{"robotId":1}' >/dev/null
AR9=$(req POST "/api/deliveries/$O9/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR9/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O9/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":11}' >/dev/null
if wait_event "$O9" ELEVATOR_TIMEOUT 60; then ok "ELEVATOR_TIMEOUT 事件"; else bad "缺少 ELEVATOR_TIMEOUT"; fi
req POST "/api/deliveries/$O9/cancel" "$OPS" '{"reason":"电梯长时间不可用，取消"}' >/dev/null

say "10. 异常：物业夜间限制运行 -> 门禁自动拒绝"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"00:00","nightEnd":"23:59"}' >/dev/null
O10=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O10/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE10=$(req POST "/api/deliveries/$O10/arrive-gate" "$OPS" '{"gateName":"南门"}')
check "夜间门禁申请自动拒绝" "$(echo "$GATE10" | jq -r .status)" "DENIED"
wait_event "$O10" NIGHT_RESTRICTED && ok "NIGHT_RESTRICTED 事件" || bad "缺少 NIGHT_RESTRICTED"
wait_event "$O10" ACCESS_DENIED && ok "夜间 ACCESS_DENIED 事件" || bad "缺少夜间 ACCESS_DENIED"
req POST "/api/deliveries/$O10/cancel" "$OPS" '{"reason":"夜间限制，改期配送"}' >/dev/null
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null
ok "已恢复社区1夜间规则(22:00-07:00 限制)"

say "11. 三端视图"
IMPACT=$(req GET "/api/analytics/property/passage-impact?communityId=1" "$PROPERTY")
check "物业端-避障停留次数>=2" "$(echo "$IMPACT" | jq '.obstacleStopCount >= 2')" "true"
check "物业端-施工绕行>=1" "$(echo "$IMPACT" | jq '.constructionDetours >= 1')" "true"
check "物业端-电梯满载>=1" "$(echo "$IMPACT" | jq '.elevatorFullCount >= 1')" "true"
check "物业端-夜间限制>=1" "$(echo "$IMPACT" | jq '.nightRestrictedCount >= 1')" "true"
check "物业端-投诉数>=1" "$(echo "$IMPACT" | jq '.complaintCount >= 1')" "true"
echo "  物业端通行影响: $(echo "$IMPACT" | jq -c '{impactLevel,affectsResidents,suggestion}')"
EFF=$(req GET "/api/analytics/ops/efficiency?communityId=1" "$OPS")
check "运营端-效率报表有数据" "$(echo "$EFF" | jq 'length > 0')" "true"
echo "  运营端效率(1栋FOOD): $(echo "$EFF" | jq -c '.[] | select(.buildingId==1 and .orderType=="FOOD") | {total,signed,returned,successRate}')"
EFF_MED=$(req GET "/api/analytics/ops/efficiency?communityId=1&orderType=MEDICINE" "$OPS")
check "运营端-按订单类型过滤(药品)" "$(echo "$EFF_MED" | jq 'all(.orderType=="MEDICINE")')" "true"
SUM=$(req GET "/api/cs/cases/summary" "$CS")
check "客服端-工单总数>=8" "$(echo "$SUM" | jq '.total >= 8')" "true"
echo "  客服端责任分布: $(echo "$SUM" | jq -c '.byResponsibility')"
EV_ALL=$(req GET "/api/events?communityId=1&eventType=ROUTE_ADJUSTED" "$OPS" | jq 'length')
check "统一事件流可按社区+类型检索" "$([ "$EV_ALL" -ge 2 ] && echo true || echo false)" "true"

say "结果"
if [ "$FAILS" -eq 0 ]; then
  echo "全部冒烟用例通过 ✅"
  exit 0
else
  echo "存在 $FAILS 个失败用例 ❌"
  exit 1
fi
