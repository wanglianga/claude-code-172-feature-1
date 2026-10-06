-- 电梯满载等待策略：连续三次满载后按订单时效/电量/楼层高度决策
--   继续等待(WAIT_ELEVATOR) / 人工接力(MANUAL_RELAY) / 通知用户下楼取件(USER_PICKUP_DOWNSTAIRS)
-- 同时新增：配送费用重算、人工接力交接记录（身份/接货时间/仓门开启/用户签收）

alter table delivery_order add column elevator_full_count      int          not null default 0;
alter table delivery_order add column wait_strategy            varchar(28);
alter table delivery_order add column wait_strategy_reason     varchar(255);
alter table delivery_order add column wait_strategy_decided_at timestamp;

-- 配送费用（选择人工接力时重算：基础配送费 + 人工接力费，分阶段责任可能产生减免）
alter table delivery_order add column delivery_fee       numeric(10, 2);
alter table delivery_order add column delivery_fee_detail text;

-- 接力 / 楼下取件关键时间点
alter table delivery_order add column relay_started_at          timestamp;
alter table delivery_order add column handover_at               timestamp;
alter table delivery_order add column downstairs_notified_at    timestamp;
alter table delivery_order add column downstairs_pickup_at      timestamp;

-- 人工接力交接记录：接力人员身份、接货时间、仓门开启、用户签收
create table relay_handover (
    id                    bigserial primary key,
    handover_no           varchar(40)  not null unique,
    delivery_id           bigint       not null references delivery_order (id),
    handover_type         varchar(24)  not null,   -- UPSTAIRS_RELAY=人工接力上楼; DOWNSTAIRS_HANDOVER=楼下车库/大堂交接
    status                varchar(24)  not null,   -- DISPATCHED/HANDED_OVER/COMPARTMENT_OPENED/SIGNED
    courier_name          varchar(64)  not null,
    courier_phone         varchar(32),
    courier_company       varchar(64),
    courier_employee_no   varchar(40),
    compartment_no        varchar(10),
    dispatched_at         timestamp    not null,
    handed_over_at        timestamp,
    compartment_opened_at timestamp,
    signed_at             timestamp,
    sign_by               varchar(64),
    damage_reported       boolean      not null default false,
    damage_note           text,
    fee                   numeric(10, 2),
    note                  text,
    created_at            timestamp    not null,
    updated_at            timestamp    not null
);
create index idx_relay_delivery on relay_handover (delivery_id);
create index idx_relay_status on relay_handover (status);
