package com.example.robotdelivery.liability;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "异常责任判定", description = "商品损坏/延迟按机器人等待、人工接力、用户取件三阶段认定责任")
@RestController
@RequestMapping("/api/deliveries/{deliveryId}/liability")
public class LiabilityController {

    private final LiabilityService liabilityService;

    public LiabilityController(LiabilityService liabilityService) {
        this.liabilityService = liabilityService;
    }

    @Operation(summary = "三阶段责任判定（只判定，不开工单）",
            description = "返回判定阶段、责任方、判定依据与三阶段时间线；stage 可显式指定，缺省按时间线推断")
    @RequireRole({Role.CS, Role.OPS, Role.PROPERTY, Role.ADMIN})
    @GetMapping
    public Map<String, Object> judge(@PathVariable Long deliveryId,
                                     @RequestParam LiabilityIssue issue,
                                     @RequestParam(required = false) LiabilityStage stage) {
        return liabilityService.judge(deliveryId, issue, stage);
    }

    public record LiabilityReportRequest(@NotNull LiabilityIssue issue,
                                         LiabilityStage stage,
                                         String note) {
    }

    @Operation(summary = "客服登记商品损坏/延迟并完成责任判定（自动开立工单）")
    @RequireRole({Role.CS, Role.ADMIN})
    @PostMapping("/report")
    public Map<String, Object> report(@PathVariable Long deliveryId,
                                      @Valid @RequestBody LiabilityReportRequest request) {
        return liabilityService.report(deliveryId, request.issue(), request.stage(), request.note());
    }
}
