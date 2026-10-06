package com.example.robotdelivery.elevator;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "电梯协同", description = "电梯系统回调接口（演示环境由运营角色模拟电梯系统）")
@RestController
@RequestMapping("/api/elevator-requests")
public class ElevatorController {

    private final ElevatorRequestRepository elevatorRepository;
    private final ElevatorService elevatorService;

    public ElevatorController(ElevatorRequestRepository elevatorRepository, ElevatorService elevatorService) {
        this.elevatorRepository = elevatorRepository;
        this.elevatorService = elevatorService;
    }

    @Operation(summary = "电梯请求列表")
    @RequireRole({Role.OPS, Role.PROPERTY, Role.ADMIN})
    @GetMapping
    public List<ElevatorRequest> list(@RequestParam(required = false) ElevatorStatus status,
                                      @RequestParam(required = false) Long deliveryId) {
        if (deliveryId != null) {
            return elevatorRepository.findByDeliveryIdOrderByIdDesc(deliveryId);
        }
        if (status != null) {
            return elevatorRepository.findByStatus(status);
        }
        return elevatorRepository.findAll();
    }

    public record AssignRequest(@NotBlank String carId) {
    }

    @Operation(summary = "电梯系统派梯")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/assign")
    public ElevatorRequest assign(@PathVariable Long id, @RequestBody AssignRequest request) {
        return elevatorService.assign(id, request.carId());
    }

    @Operation(summary = "电梯系统上报满载",
            description = "前两次满载：机器人自动等待下一趟并重新请求；"
                    + "连续第三次满载：按订单时效/电量/楼层高度决策继续等待、人工接力或通知用户下楼取件，"
                    + "返回值为人工接力/下楼取件场景下最后一次满载请求（无新重试请求）")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/full")
    public ElevatorRequest full(@PathVariable Long id) {
        return elevatorService.full(id);
    }

    @Operation(summary = "乘梯完成，到达目标楼层")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/complete")
    public ElevatorRequest complete(@PathVariable Long id) {
        return elevatorService.complete(id);
    }
}
