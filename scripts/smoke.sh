#!/usr/bin/env bash
# 冒烟验证：主流程 + 全部异常场景（含电梯连续满载三策略/人工接力交接/三阶段责任判定）+ 三端视图
# 用法: BASE=http://localhost:8080 ./scripts/smoke.sh
# 依赖: curl, jq, date（GNU）
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
check "低电量接力后订单状态" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "RELAYING"
check "低电量原因免收接力附加费" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .relayFeeCents)" "0"
req POST "/api/relays/by-delivery/$O5/accept" "$OPS" '{"courierId":"C005","courierName":"骑手小王"}' >/dev/null
req POST "/api/relays/by-delivery/$O5/deliver" "$OPS" >/dev/null
req POST "/api/relays/by-delivery/$O5/sign" "$OPS" '{"signBy":"赵女士"}' >/dev/null
check "人工接力后签收" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O5" MANUAL_RELAY && ok "MANUAL_RELAY 事件" || bad "缺少 MANUAL_RELAY"
wait_event "$O5" RELAY_HANDOVER && ok "低电量接力 RELAY_HANDOVER 事件" || bad "缺少 RELAY_HANDOVER"

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

# 连续三次电梯满载的公用流程：返回第3次满载后的响应
three_full() { # orderId toFloor -> 第三次 /full 的响应（JSON）
  local did=$1 floor=$2 er
  er=$(req POST "/api/deliveries/$did/request-elevator" "$OPS" "{\"fromFloor\":1,\"toFloor\":$floor}" | jq -r .id)
  er=$(req POST "/api/elevator-requests/$er/full" "$OPS" | jq -r .id)
  er=$(req POST "/api/elevator-requests/$er/full" "$OPS" | jq -r .id)
  req POST "/api/elevator-requests/$er/full" "$OPS"
}

# 紧迫时效订单：预计送达时间设为1分钟前
URGENT_ETA=$(date -d '-1 minute' '+%Y-%m-%dT%H:%M:%S')
urgent_order() { # buildingId toRoom
  req POST /api/orders "$OPS" "{
    \"orderType\":\"FOOD\",\"communityId\":1,\"buildingId\":$1,
    \"unitNo\":\"2单元\",\"roomNo\":\"$2\",\"coldChain\":false,
    \"estimatedDeliveryAt\":\"$URGENT_ETA\",
    \"userName\":\"张三\",\"userPhone\":\"13800000001\"}" | jq -r .id
}

say "12. 电梯满载策略A：低楼层+时效宽松+电量充足 -> 继续等待"
O11=$(req POST /api/orders "$OPS" '{
    "orderType":"GROCERY","communityId":1,"buildingId":1,
    "unitNo":"2单元","roomNo":"601","coldChain":false,
    "estimatedDeliveryAt":"2026-12-31T23:59:00",
    "userName":"张三","userPhone":"13800000001"}' | jq -r .id)
req POST "/api/deliveries/$O11/assign" "$OPS" '{"robotId":1}' >/dev/null
AR11=$(req POST "/api/deliveries/$O11/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR11/approve" "$PROPERTY" >/dev/null
R11=$(three_full "$O11" 6)
check "策略A订单仍为等待电梯" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .status)" "ELEVATOR_WAIT"
check "策略A判定为继续等待" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .fullStrategy)" "CONTINUE_WAIT"
check "策略A后产生新的待派梯请求" "$(echo "$R11" | jq -r .status)" "PENDING"
wait_event "$O11" ELEVATOR_FULL_STRATEGY && ok "ELEVATOR_FULL_STRATEGY 事件" || bad "缺少 ELEVATOR_FULL_STRATEGY"
req GET "/api/deliveries/$O11/events" "$OPS" | jq -e '.[] | select(.eventType=="ELEVATOR_FULL_STRATEGY" and (.payload|test("CONTINUE_WAIT")))' >/dev/null \
  && ok "策略事件携带判定结果CONTINUE_WAIT" || bad "策略事件缺少CONTINUE_WAIT"
req GET "/api/deliveries/$O11/events" "$OPS" | jq -e '.[] | select(.eventType=="ELEVATOR_FULL_STRATEGY" and (.payload|test("\"highFloor\":false") and test("\"urgent\":false")))' >/dev/null \
  && ok "策略事件携带时效/楼层因子" || bad "策略事件缺少判定因子"
ER11=$(echo "$R11" | jq -r .id)
req POST "/api/elevator-requests/$ER11/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$ER11/complete" "$OPS" >/dev/null
check "乘梯成功后满载计数清零" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .fullFailCount)" "0"
check "机器人配送费按楼层计价(500+30x5=650)" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .totalFeeCents)" "650"
req POST "/api/deliveries/$O11/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O11/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O11/sign" "$OPS" '{"signBy":"孙女士"}' >/dev/null
check "策略A最终签收" "$(req GET "/api/orders/$O11" "$OPS" | jq -r .status)" "SIGNED"
# 责任判定：无接力的损坏默认为机器人保管阶段
J11=$(req GET "/api/deliveries/$O11/liability?issue=GOODS_DAMAGED" "$CS")
check "损坏责任(机器人等待阶段)->ROBOT_VENDOR" "$(echo "$J11" | jq -r .responsibility)" "ROBOT_VENDOR"

say "13. 电梯满载策略B：高楼层+时效宽松+电量充足 -> 通知用户下楼取件"
O12=$(new_order 2 false GROCERY)
req POST "/api/deliveries/$O12/assign" "$OPS" '{"robotId":1}' >/dev/null
AR12=$(req POST "/api/deliveries/$O12/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR12/approve" "$PROPERTY" >/dev/null
three_full "$O12" 18 >/dev/null
check "策略B订单进入下楼等待" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .status)" "WAITING_DOWNSTAIRS"
check "策略B判定为用户下楼" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .fullStrategy)" "USER_DOWNSTAIRS"
check "策略B满载计数=3" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .fullFailCount)" "3"
check "策略B短信+小程序通知下楼" "$(req GET "/api/notifications?deliveryId=$O12" "$OPS" | jq 'length')" "2"
req GET "/api/deliveries/$O12/events" "$OPS" | jq -e '.[] | select(.eventType=="USER_NOTIFIED" and .reason=="DOWNSTAIRS")' >/dev/null \
  && ok "下楼取件 USER_NOTIFIED(reason=DOWNSTAIRS) 事件" || bad "缺少下楼通知事件"
PEND12=$(req GET "/api/elevator-requests?deliveryId=$O12" "$OPS" | jq '[.[] | select(.status=="PENDING")] | length')
check "策略B后未完成电梯请求已作废(无PENDING)" "$PEND12" "0"
J12=$(req GET "/api/deliveries/$O12/liability?issue=GOODS_DAMAGED&stage=USER_PICKUP" "$CS")
check "损坏责任(用户取件阶段)->USER" "$(echo "$J12" | jq -r .responsibility)" "USER"
# 用户下楼到大堂，机器人处直接开仓取件签收
req POST "/api/deliveries/$O12/arrive-door" "$OPS" >/dev/null
check "下楼后状态转为到门等待" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .status)" "WAITING_PICKUP"
req POST "/api/deliveries/$O12/open-compartment" "$OPS" >/dev/null
SIGN12=$(req POST "/api/deliveries/$O12/sign" "$OPS" '{"signBy":"冯先生"}')
check "下楼取件签收" "$(echo "$SIGN12" | jq -r .status)" "SIGNED"
req GET "/api/deliveries/$O12/events" "$OPS" | jq -e '.[] | select(.eventType=="SIGNED" and (.payload|test("\"downstairsPickup\":true")))' >/dev/null \
  && ok "签收事件标记下楼取件" || bad "缺少下楼取件标记"

say "14. 电梯满载策略C：时效紧迫 -> 自动人工接力（交接+费用重算+责任判定）"
O13=$(urgent_order 2 1801)
req POST "/api/deliveries/$O13/assign" "$OPS" '{"robotId":1}' >/dev/null
AR13=$(req POST "/api/deliveries/$O13/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR13/approve" "$PROPERTY" >/dev/null
three_full "$O13" 18 >/dev/null
check "策略C订单进入人工接力" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .status)" "RELAYING"
check "策略C判定为人工接力" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .fullStrategy)" "MANUAL_RELAY"
H13=$(req GET "/api/relays/by-delivery/$O13" "$OPS")
check "交接单已创建待配送员接单" "$(echo "$H13" | jq -r .status)" "DISPATCHED"
check "交接触发原因=电梯满载" "$(echo "$H13" | jq -r .triggerReason)" "ELEVATOR_FULL"
check "接力费重算-配送费(500+30x17=1010)" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .deliveryFeeCents)" "1010"
check "平台侧原因接力附加费免收" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .relayFeeCents)" "0"
check "接力费重算-合计" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .totalFeeCents)" "1010"
wait_event "$O13" FEE_RECALCULATED && ok "FEE_RECALCULATED 费用重算事件" || bad "缺少 FEE_RECALCULATED"
# 配送员现场核验身份、开启载物仓接货
ACC13=$(req POST "/api/relays/by-delivery/$O13/accept" "$OPS" '{"courierId":"C001","courierName":"接力员老赵","courierPhone":"13900001234"}')
check "接货后交接单状态" "$(echo "$ACC13" | jq -r .status)" "HANDED_OVER"
[ "$(echo "$ACC13" | jq -r .compartmentOpenedAt)" != "null" ] && [ "$(echo "$ACC13" | jq -r .handedAt)" != "null" ] \
  && ok "记录仓门开启时间与接货时间" || bad "缺少仓门开启/接货时间"
check "接货后接力人员身份登记" "$(echo "$ACC13" | jq -r '.courierId+" "+.courierName+" "+.courierPhone')" "C001 接力员老赵 13900001234"
wait_event "$O13" RELAY_COMPARTMENT_OPENED && ok "RELAY_COMPARTMENT_OPENED 仓门交接事件" || bad "缺少仓门交接事件"
wait_event "$O13" RELAY_HANDOVER && ok "RELAY_HANDOVER 接货事件" || bad "缺少接货事件"
check "接货后机器人释放" "$(req GET "/api/robots?communityId=1" "$OPS" | jq -r '.[] | select(.id==1) | .status')" "IDLE"
# 配送员送达 -> 通知 -> 用户签收
req POST "/api/relays/by-delivery/$O13/deliver" "$OPS" >/dev/null
check "送达后交接单状态" "$(req GET "/api/relays/by-delivery/$O13" "$OPS" | jq -r .status)" "DELIVERED"
wait_event "$O13" RELAY_DELIVERED && ok "RELAY_DELIVERED 送达事件" || bad "缺少接力送达事件"
S13=$(req POST "/api/relays/by-delivery/$O13/sign" "$OPS" '{"signBy":"陈女士"}')
check "接力签收后交接单状态" "$(echo "$S13" | jq -r .status)" "SIGNED"
check "接力签收记录签收人" "$(echo "$S13" | jq -r .signBy)" "陈女士"
[ "$(echo "$S13" | jq -r .signedAt)" != "null" ] && ok "记录用户签收时间" || bad "缺少签收时间"
check "接力订单最终签收" "$(req GET "/api/orders/$O13" "$OPS" | jq -r .status)" "SIGNED"
# 责任判定：延迟由机器人等待阶段（连续满载）造成 -> 电梯系统；损坏在接力阶段 -> 平台
J13D=$(req GET "/api/deliveries/$O13/liability?issue=DELIVERY_DELAY" "$CS")
check "延迟责任(机器人等待阶段)->ELEVATOR_SYSTEM" "$(echo "$J13D" | jq -r .responsibility)" "ELEVATOR_SYSTEM"
check "延迟判定阶段=ROBOT_WAIT" "$(echo "$J13D" | jq -r .stage)" "ROBOT_WAIT"
R13=$(req POST "/api/deliveries/$O13/liability/report" "$CS" '{"issue":"GOODS_DAMAGED","note":"用户反映餐品撒漏"}')
check "损坏责任(人工接力阶段)->PLATFORM" "$(echo "$R13" | jq -r .responsibility)" "PLATFORM"
check "损坏判定阶段=RELAY" "$(echo "$R13" | jq -r .stage)" "RELAY"
[ "$(echo "$R13" | jq -r .caseNo)" != "null" ] && ok "责任判定自动开立客服工单#$(echo "$R13" | jq -r .caseNo)" || bad "责任判定未开工单"
wait_event "$O13" LIABILITY_JUDGED && ok "LIABILITY_JUDGED 责任判定事件" || bad "缺少 LIABILITY_JUDGED"

say "15. 策略补充：低电量 -> 人工接力；预指派配送员身份核验；人工发起收取接力附加费"
O14=$(new_order 1 false MEDICINE)
req POST /api/robots/2/status "$OPS" '{"battery":90}' >/dev/null
req POST "/api/deliveries/$O14/assign" "$OPS" '{"robotId":2}' >/dev/null
AR14=$(req POST "/api/deliveries/$O14/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR14/approve" "$PROPERTY" >/dev/null
ER14=$(req POST "/api/deliveries/$O14/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":6}' | jq -r .id)
ER14=$(req POST "/api/elevator-requests/$ER14/full" "$OPS" | jq -r .id)
ER14=$(req POST "/api/elevator-requests/$ER14/full" "$OPS" | jq -r .id)
req POST /api/robots/2/status "$OPS" '{"battery":8}' >/dev/null
req POST "/api/elevator-requests/$ER14/full" "$OPS" >/dev/null
check "低电量触发人工接力" "$(req GET "/api/orders/$O14" "$OPS" | jq -r .status)" "RELAYING"
req GET "/api/deliveries/$O14/events" "$OPS" | jq -e '.[] | select(.eventType=="ELEVATOR_FULL_STRATEGY" and (.payload|test("\"lowBattery\":true")))' >/dev/null \
  && ok "策略事件携带低电量因子" || bad "缺少低电量因子"
# 自动派单（未预指派）现场不登记身份禁止接货
BADACC=$(req POST "/api/relays/by-delivery/$O14/accept" "$OPS" '{"courierId":"","courierName":""}')
check "未登记身份接货被拒(400)" "$(echo "$BADACC" | jq -r .status)" "400"
req POST "/api/relays/by-delivery/$O14/accept" "$OPS" '{"courierId":"C002","courierName":"接力员小钱"}' >/dev/null
req POST "/api/relays/by-delivery/$O14/deliver" "$OPS" >/dev/null
req POST "/api/relays/by-delivery/$O14/sign" "$OPS" '{"signBy":"褚女士"}' >/dev/null
check "低电量接力订单签收" "$(req GET "/api/orders/$O14" "$OPS" | jq -r .status)" "SIGNED"
# 运营人工发起接力并预指派配送员：身份不匹配禁止开仓；人工原因收取接力附加费
O15=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O15/assign" "$OPS" '{"robotId":1}' >/dev/null
AR15=$(req POST "/api/deliveries/$O15/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR15/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O15/manual-relay" "$OPS" '{"courierId":"K009","courierName":"特派员老孙","triggerReason":"MANUAL"}' >/dev/null
check "预指派交接单待接单" "$(req GET "/api/relays/by-delivery/$O15" "$OPS" | jq -r .status)" "DISPATCHED"
WRONG=$(req POST "/api/relays/by-delivery/$O15/accept" "$OPS" '{"courierId":"K001","courierName":"冒名者"}')
check "身份与派单不一致禁止开仓(409)" "$(echo "$WRONG" | jq -r .status)" "409"
check "人工发起接力收取附加费300" "$(req GET "/api/orders/$O15" "$OPS" | jq -r .relayFeeCents)" "300"
check "人工发起接力合计(500+30x8+300=1040)" "$(req GET "/api/orders/$O15" "$OPS" | jq -r .totalFeeCents)" "1040"
req POST "/api/relays/by-delivery/$O15/accept" "$OPS" '{"courierId":"K009","courierName":"特派员老孙"}' >/dev/null
req POST "/api/relays/by-delivery/$O15/deliver" "$OPS" >/dev/null
req POST "/api/relays/by-delivery/$O15/sign" "$OPS" '{"signBy":"卫先生"}' >/dev/null
check "预指派接力签收" "$(req GET "/api/orders/$O15" "$OPS" | jq -r .status)" "SIGNED"

say "16. 满载策略统计与三端视图补充"
IMPACT2=$(req GET "/api/analytics/property/passage-impact?communityId=1" "$PROPERTY")
check "物业端-满载策略判定>=3次" "$(echo "$IMPACT2" | jq '.elevatorFullStrategyCount >= 3')" "true"
check "物业端-满载转人工接力>=1次" "$(echo "$IMPACT2" | jq '.relayByElevatorFullCount >= 1')" "true"
EFF2=$(req GET "/api/analytics/ops/efficiency?communityId=1&orderType=MEDICINE" "$OPS")
echo "  运营端接力统计(MEDICINE): $(echo "$EFF2" | jq -c '.[] | {building,relayed,relaySurchargeTotalCents}')"
RELAYS=$(req GET "/api/relays?status=SIGNED" "$OPS" | jq 'length')
check "接力交接单可按状态查询(已签收>=4)" "$([ "$RELAYS" -ge 4 ] && echo true || echo false)" "true"


say "结果"
if [ "$FAILS" -eq 0 ]; then
  echo "全部冒烟用例通过 ✅"
  exit 0
else
  echo "存在 $FAILS 个失败用例 ❌"
  exit 1
fi
