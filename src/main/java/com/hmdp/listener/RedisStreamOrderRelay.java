package com.hmdp.listener;

import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 是 Lua 与 RabbitMQ 之间的可靠 Outbox。只有 RabbitMQ broker 确认收到后
 * 才 ACK Stream；发送失败或进程重启时，pending 消息会被再次投递。
 */
@Component
@Slf4j
public class RedisStreamOrderRelay {
    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP = "g1";
    private static final String CONSUMER = "rabbit-relay";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Scheduled(initialDelay = 1000, fixedDelay = 500)
    public void relayOrders() {
        List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from("0"));
        if (!pending.isEmpty()) {
            relay(pending);
            return;
        }
        relay(read(ReadOffset.lastConsumed()));
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
                    StreamOffset.create(STREAM_KEY, offset));
            return records == null ? Collections.emptyList() : records;
        } catch (Exception e) {
            // 首笔秒杀前 Stream/Group 尚不存在，Lua 写入首笔消息时会创建它。
            return Collections.emptyList();
        }
    }

    private void relay(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            try {
                VoucherOrder order = new VoucherOrder();
                order.setId(Long.valueOf(record.getValue().get("id").toString()));
                order.setUserId(Long.valueOf(record.getValue().get("userId").toString()));
                order.setVoucherId(Long.valueOf(record.getValue().get("voucherId").toString()));
                CorrelationData correlation = new CorrelationData(String.valueOf(order.getId()));
                rabbitTemplate.convertAndSend(QueueConfig.X_EXCHANGE, QueueConfig.QUEUE_A_BINDING_KEY, order, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (confirm == null || !confirm.isAck()) {
                    throw new IllegalStateException("RabbitMQ 未确认消息");
                }
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            } catch (Exception e) {
                // 不 ACK，消息留在 Pending Entries List，下一轮继续尝试。
                log.error("Outbox 投递 RabbitMQ 失败，保留待重试消息 id={}", record.getId(), e);
                return;
            }
        }
    }
}
