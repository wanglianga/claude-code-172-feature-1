package com.example.robotdelivery.relay;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "人工接力",
        description = "电梯连续满载改人工接力：记录接力人员身份、接货时间、仓门开启、用户签收；"
                + "商品损坏/延迟按机器人等待、人工接力、用户取件三个阶段判定责任并重算费用")
@RestController
@RequestMapping("/api/relays")
public class RelayController {

    private final RelayService relayService;
    private final RelayHandoverRepository handoverRepository;

    public RelayController(RelayService relayService, RelayHandoverRepository handoverRepository) {
        this.relayService = relayService;
        this.handoverRepository = handoverRepository;
    }

    public record DispatchRequest(@NotNull Long deliveryId,
                                  @NotBlank String courierName,
                                  String courierPhone,
                                  String courierCompany,
                                  String courierEmployeeNo) {
    }

    @Operation(summary = "接力派单",
            description = "登记接力人员身份（姓名/手机号/公司/工号），订单进入 RELAYING，"
                    + "按目标楼层重算配送费用（基础配送费+接力服务费+楼层费），写入事件流")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping
    public RelayHandover dispatch(@Valid @RequestBody DispatchRequest request) {
        return relayService.dispatch(request.deliveryId(), request.courierName(),
                request.courierPhone(), request.courierCompany(), request.courierEmployeeNo());
    }

    @Operation(summary = "接力交接单列表（可按配送单过滤）")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping
    public List<RelayHandover> list(@RequestParam(required = false) Long deliveryId,
                                    @RequestParam(required = false) RelayStatus status) {
        if (deliveryId != null) {
            return relayService.listByDelivery(deliveryId);
        }
        if (status != null) {
            return handoverRepository.findByStatus(status);
        }
        return handoverRepository.findAll();
    }

    @Operation(summary = "接力交接单详情")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping("/{id}")
    public RelayHandover detail(@PathVariable Long id) {
        return handoverRepository.findById(id)
                .orElseThrow(() -> new com.example.robotdelivery.config.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "接力交接单不存在: " + id));
    }

    @Operation(summary = "接力：机器人为接力人员打开载物仓（记录仓门开启时间）")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/open-compartment")
    public RelayHandover openCompartment(@PathVariable Long id) {
        return relayService.openCompartment(id);
    }

    public record HandoverRequest(Boolean damageReported, String damageNote) {
    }

    @Operation(summary = "接力：接力人员确认接货（记录接货时间，释放机器人与载物仓）",
            description = "开仓时发现商品损坏可上报；商品此前处于机器人载物仓，按机器人等待阶段判定责任")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/handover")
    public RelayHandover handover(@PathVariable Long id, @RequestBody(required = false) HandoverRequest request) {
        boolean damage = request != null && Boolean.TRUE.equals(request.damageReported());
        return relayService.handover(id, damage, request == null ? null : request.damageNote());
    }

    public record SignRequest(@NotBlank String signBy,
                              Boolean damageReported,
                              String damageNote) {
    }

    @Operation(summary = "接力：送达后用户签收（记录签收人与时间）",
            description = "接力阶段上报商品损坏按接力人员责任判定，并自动减免接力服务费、重算费用；"
                    + "晚于预计送达时间自动按接力阶段延迟开单")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/sign")
    public RelayHandover sign(@PathVariable Long id, @Valid @RequestBody SignRequest request) {
        boolean damage = Boolean.TRUE.equals(request.damageReported());
        return relayService.sign(id, request.signBy(), damage, request.damageNote());
    }

    public record IssueRequest(@NotNull RelayService.IssueType issueType, String note) {
    }

    @Operation(summary = "接力异常上报：商品损坏/延迟",
            description = "服务按交接单进度自动定位阶段（机器人等待/人工接力/用户取件）并判定责任方")
    @RequireRole({Role.CS, Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/issue")
    public Map<String, Object> reportIssue(@PathVariable Long id, @Valid @RequestBody IssueRequest request) {
        return relayService.reportIssue(id, request.issueType(), request.note());
    }
}
