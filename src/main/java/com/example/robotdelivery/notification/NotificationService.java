package com.example.robotdelivery.notification;

import com.example.robotdelivery.order.DeliveryOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通知服务：模拟短信与小程序推送（真实环境对接短信网关/微信开放平台）。
 */
@Slf4j
@Service
public class NotificationService {

    private static final Map<com.example.robotdelivery.order.OrderType, String> TYPE_CN = Map.of(
            com.example.robotdelivery.order.OrderType.FOOD, "外卖",
            com.example.robotdelivery.order.OrderType.MEDICINE, "药品",
            com.example.robotdelivery.order.OrderType.GROCERY, "商超");

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 到达门口后通过短信 + 小程序通知用户取件。
     */
    public List<Notification> notifyArrival(DeliveryOrder order) {
        String content = String.format(
                "【社区配送】您的%s订单%s已送达%s%s栋%s%s，载物仓%s号仓，请尽快取件。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                order.getCommunity().getName(),
                order.getBuilding().getName(),
                order.getUnitNo() == null ? "" : order.getUnitNo(),
                order.getRoomNo() == null ? "" : order.getRoomNo(),
                order.getCompartmentNo());
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户取件: deliveryId={} phone={} channels=[SMS, MINI_PROGRAM]",
                order.getId(), order.getUserPhone());
        return List.of(sms, mini);
    }

    /**
     * 电梯连续满载策略要求用户下楼取件：通知用户到楼栋大堂找机器人取件。
     */
    public List<Notification> notifyDownstairs(DeliveryOrder order, Integer fromFloor, int fullFailCount) {
        String content = String.format(
                "【社区配送】您的%s订单%s所在%s栋客梯连续%d次满载，机器人无法乘梯上楼，"
                        + "请您移步至%d层大堂找机器人（载物仓%s号仓）取件，感谢配合。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                order.getBuilding().getName(),
                fullFailCount,
                fromFloor == null ? 1 : fromFloor,
                order.getCompartmentNo());
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户下楼取件: deliveryId={} phone={} fullFailCount={}",
                order.getId(), order.getUserPhone(), fullFailCount);
        return List.of(sms, mini);
    }

    /**
     * 改人工接力后通知用户：配送员将代为送达。
     *
     * @param surchargeWaived 接力附加费是否由平台承担（平台侧原因触发时免收）
     */
    public List<Notification> notifyRelay(DeliveryOrder order, String courierName,
                                          String triggerReasonText, boolean surchargeWaived) {
        String feeText = surchargeWaived ? "接力附加费已由平台承担，您无需额外支付，" : "";
        String content = String.format(
                "【社区配送】您的%s订单%s因%s，已由人工配送员%s接力送上楼，%s请注意接听电话并取件。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                triggerReasonText,
                courierName,
                feeText);
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户人工接力: deliveryId={} courier={}", order.getId(), courierName);
        return List.of(sms, mini);
    }

    private Notification save(Long deliveryId, NotifyChannel channel, String target, String content) {
        Notification notification = new Notification();
        notification.setDeliveryId(deliveryId);
        notification.setChannel(channel);
        notification.setTarget(target);
        notification.setContent(content);
        notification.setStatus("SENT");
        return notificationRepository.save(notification);
    }
}
