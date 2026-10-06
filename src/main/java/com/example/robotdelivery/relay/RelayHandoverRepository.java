package com.example.robotdelivery.relay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RelayHandoverRepository extends JpaRepository<RelayHandover, Long> {

    Optional<RelayHandover> findByDeliveryId(Long deliveryId);

    List<RelayHandover> findByStatus(RelayStatus status);
}
