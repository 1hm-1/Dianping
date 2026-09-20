package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 订单服务：秒杀下单使用 Lua 校验库存与一人一单，
 * 校验通过后异步发送到 RabbitMQ 由监听器创建订单、扣减库存。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀校验脚本：判断库存、一人一单；通过后扣减 Redis 库存并记录下单人。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 应用启动时批量预热秒杀优惠券库存到 Redis。
     * 仅当缓存 key 不存在时才写入，避免覆盖 Redis 中已扣减的实时库存。
     */
    @PostConstruct
    public void preloadSeckillVouchers() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers == null || vouchers.isEmpty()) {
            log.info("秒杀优惠券缓存预热完成，数据库中没有秒杀优惠券数据");
            return;
        }
        int loaded = 0;
        for (SeckillVoucher voucher : vouchers) {
            String key = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
            Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(key, voucher.getStock().toString());
            if (Boolean.TRUE.equals(set)) {
                loaded++;
            }
        }
        log.info("秒杀优惠券缓存预热完成，共 {} 个，实际写入 {} 个", vouchers.size(), loaded);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Result timeCheck = checkTimeWindow(voucherId);
        if (timeCheck != null) {
            return timeCheck;
        }
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            if (r == 1) {
                return Result.fail("库存不足");
            }
            if (r == 2) {
                return Result.fail("不能重复下单");
            }
            return Result.fail("秒杀失败");
        }

        // 3. Lua 已原子写入 Redis Stream Outbox；由可靠中继投递 RabbitMQ。
        // 4.返回订单号给前端（实际下单异步处理）
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        if (getById(voucherOrder.getId()) != null || lambdaQuery()
                .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .count() > 0) {
            return;
        }
        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存不足，订单将回滚: " + voucherOrder.getId());
        }
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单创建失败，订单将回滚: " + voucherOrder.getId());
        }
    }

    /**
     * 校验秒杀是否在有效时间窗内。
     * @return 时间窗不合法时返回错误 Result，合法时返回 null
     */
    private Result checkTimeWindow(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime() != null && now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime() != null && now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已结束");
        }
        return null;
    }
}
