-- 电梯满载等待策略 + 人工接力交接 + 配送费用重算

-- 配送费用（以“分”为单位，避免浮点误差）
alter table delivery_order add column delivery_fee_cents   int not null default 0;
alter table delivery_order add column relay_fee_cents      int not null default 0;
alter table delivery_order add column total_fee_cents      int not null default 0;
alter table delivery_order add column fee_detail           varchar(255);
-- 连续满载触发的等待策略：CONTINUE_WAIT / MANUAL_RELAY / USER_DOWNSTAIRS
alter table delivery_order add column full_strategy        varchar(20);
alter table delivery_order add column full_fail_count      int not null default 0;
-- 下楼取件/人工接力的通知时间，供超时巡检使用（notified_at 仍用于到门通知）
alter table delivery_order add column downstairs_notified_at timestamp;

-- 人工接力交接记录：人员身份、接货时间、仓门开启、用户签收
create table relay_handover (
    id                    bigserial primary key,
    delivery_id           bigint       not null unique references delivery_order (id),
    courier_id            varchar(64)  not null,
    courier_name          varchar(64)  not null,
    courier_phone         varchar(32),
    handover_location     varchar(128) not null,
    compartment_opened_at timestamp,
    handed_at             timestamp,
    delivered_at          timestamp,
    compartment_no        varchar(10),
    sign_by               varchar(64),
    signed_at             timestamp,
    status                varchar(20)  not null,
    trigger_reason        varchar(40)  not null,
    original_fee_cents    int          not null default 0,
    relay_fee_cents       int          not null default 0,
    total_fee_cents       int          not null default 0,
    created_at            timestamp    not null,
    updated_at            timestamp    not null
);
create index idx_relay_status on relay_handover (status);
