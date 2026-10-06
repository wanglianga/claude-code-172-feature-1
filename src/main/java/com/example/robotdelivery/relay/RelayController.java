package com.example.robotdelivery.relay;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "人工接力", description = "电梯连续满载/低电量等场景的人工接力交接：身份核验、仓门开启、接货、送达与用户签收")
@RestController
@RequestMapping("/api/relays")
public class RelayController {

    private final RelayService relayService;

    public RelayController(RelayService relayService) {
        this.relayService = relayService;
    }

    @Operation(summary = "接力交接单列表（可按状态过滤）")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping
    public List<RelayHandover> list(@RequestParam(required = false) String status) {
        return relayService.list(status);
    }

    @Operation(summary = "按配送单查询接力交接单")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping("/by-delivery/{deliveryId}")
    public RelayHandover byDelivery(@PathVariable Long deliveryId) {
        return relayService.getByDelivery(deliveryId);
    }

    public record AcceptHandoverRequest(@NotBlank String courierId,
                                        @NotBlank String courierName,
                                        String courierPhone) {
    }

    @Operation(summary = "配送员现场接货：核验身份、开启机器人载物仓并记录接货时间",
            description = "自动派单的接力必须现场登记工号/姓名；预指派配送员的工号须与派单一致，否则禁止开仓")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/by-delivery/{deliveryId}/accept")
    public RelayHandover accept(@PathVariable Long deliveryId,
                                @Valid @RequestBody AcceptHandoverRequest request) {
        return relayService.acceptHandover(deliveryId, request.courierId(),
                request.courierName(), request.courierPhone());
    }

    @Operation(summary = "配送员送达用户门口（接力完成最后一段配送，通知用户取件）")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/by-delivery/{deliveryId}/deliver")
    public RelayHandover deliver(@PathVariable Long deliveryId) {
        return relayService.deliver(deliveryId);
    }

    public record RelaySignRequest(@NotBlank String signBy) {
    }

    @Operation(summary = "接力配送的用户签收：记录签收人与时间")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/by-delivery/{deliveryId}/sign")
    public RelayHandover sign(@PathVariable Long deliveryId,
                              @Valid @RequestBody RelaySignRequest request) {
        return relayService.sign(deliveryId, request.signBy());
    }
}
