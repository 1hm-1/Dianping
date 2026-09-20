package com.hmdp.listener;

import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.Resource;
import java.io.IOException;

@Component
@Slf4j
public class SeckillVoucherListener {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private MessageConverter messageConverter;

    /**
     * 正常队列消费者
     */
    @RabbitListener(queues = QueueConfig.QUEUE_A)
    public void receivedA(Message message, Channel channel) {
        handleOrder(message, channel);
    }

    /**
     * 死信队列消费者（延迟重试后的补偿处理）
     */
    @RabbitListener(queues = QueueConfig.DEAD_LETTER_QUEUE_D)
    public void receivedD(Message message, Channel channel) {
        handleOrder(message, channel);
    }

    /**
     * 只有订单与库存事务提交后才 ACK。首次失败进入重试队列，二次失败进入
     * 无消费者的失败队列，保留消息供人工诊断和重放，绝不静默丢弃。
     */
    private void handleOrder(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            VoucherOrder voucherOrder = (VoucherOrder) messageConverter.fromMessage(message);
            voucherOrderService.createVoucherOrder(voucherOrder);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                acknowledge(channel, deliveryTag);
                return;
            }
            log.error("订单持久化失败，消息将进入下一补偿阶段，deliveryTag={}", deliveryTag, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioEx) {
                log.error("basicNack 失败，deliveryTag: {}", deliveryTag, ioEx);
            }
        }
    }

    private boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DuplicateKeyException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void acknowledge(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("重复订单消息确认失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
