package com.example.robotdelivery.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeliveryOrderRepository
        extends JpaRepository<DeliveryOrder, Long>, JpaSpecificationExecutor<DeliveryOrder> {

    Optional<DeliveryOrder> findByOrderNo(String orderNo);

    Optional<DeliveryOrder> findFirstByRobot_IdAndStatusInOrderByIdDesc(
            Long robotId, Collection<DeliveryStatus> statuses);

    List<DeliveryOrder> findByStatusInAndNotifiedAtBefore(
            Collection<DeliveryStatus> statuses, LocalDateTime notifiedBefore);

    List<DeliveryOrder> findByColdChainTrueAndColdChainTimedOutFalseAndStatusNotInAndColdChainDeadlineBefore(
            Collection<DeliveryStatus> excludedStatuses, LocalDateTime deadlineBefore);

    /** 电梯连续满载通知下楼后，超过下楼取件时限仍未取件 */
    @Query("select o from DeliveryOrder o where o.waitStrategy = "
            + "com.example.robotdelivery.elevator.ElevatorWaitStrategy.USER_PICKUP_DOWNSTAIRS "
            + "and o.status in :statuses and o.downstairsNotifiedAt < :before")
    List<DeliveryOrder> findDownstairsPickupExpired(
            Collection<DeliveryStatus> statuses, LocalDateTime before);

    long countByCommunityId(Long communityId);
}
