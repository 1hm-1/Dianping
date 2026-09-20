# 黑马点评（hm-dianping）

一个基于 Spring Boot 的本地生活服务平台后端。功能覆盖短信登录、商户查询、优惠券秒杀、附近商铺、达人探店、点赞关注、用户签到等场景，并针对**分布式会话、缓存一致性、高并发秒杀、消息可靠投递**四类典型问题做了工程化实现。

配套前端为纯静态页面，由 Nginx 托管；后端、数据库、缓存、消息队列通过 Docker Compose 一键编排。

---

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 登录认证 | 手机号 + 验证码登录，Redis 保存登录态，双层拦截器校验并自动续期 |
| 商户查询 | Redis 缓存商户详情，按场景选用空值缓存与逻辑过期两种策略 |
| 优惠券秒杀 | Redis + Lua 原子校验库存与一人一单，异步落库 |
| 附近商铺 | Redis GEO 按距离检索并排序，支持分页 |
| 达人探店 | 笔记发布、详情、点赞排行榜 |
| 好友关注 | 关注 / 取关 / 共同关注 |
| 用户签到 | Redis BitMap 记录签到，统计连续签到天数 |
| 商户类型 | 商铺分类查询与缓存 |

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 8 |
| 框架 | Spring Boot 2.3.12 |
| 持久层 | MyBatis-Plus 3.4.3 + MySQL 8.0 |
| 缓存 / 分布式 | Redis（Lettuce 6.1.6）、Redisson 3.13.6 |
| 消息队列 | RabbitMQ（Spring AMQP） |
| 工具库 | Lombok、Hutool 5.7.17 |
| 前端 | 静态 HTML/JS + Nginx |
| 部署 | Docker / Docker Compose |

## 核心设计

四个方向的问题与解法，详细分析见 [`docs/`](docs/)。

### 1. 分布式会话

集群下 `HttpSession` 存在单个 JVM 内存中，请求被转发到其他节点就会掉线。项目用 **Redis + 随机 Token** 保存登录态，并用**双层拦截器**拆开职责：

- 第一层（`RefreshTokenInterceptor`，拦截全部请求）：解析 Token、查用户、写入 `ThreadLocal`、续期；
- 第二层（`LoginInterceptor`，仅需登录的路径）：判断是否已登录，未登录直接拦截。

续期放在覆盖全部请求的第一层，避免公开接口不续期导致用户提前过期；用户信息通过 `UserHolder` 传递，并在请求结束时清理 `ThreadLocal` 防止线程复用导致越权。

→ [登录认证与分布式会话](docs/01-登录与分布式会话.md)

### 2. 缓存与数据库一致性

采用 **Cache Aside**：读时先查缓存、未命中查库回填；写时**先更新数据库、再删除缓存**。

针对缓存三大问题分别处理：

- **穿透**：缓存空值（短 TTL 兜底）；
- **雪崩**：过期时间加随机扰动；
- **击穿**：提供**互斥锁**与**逻辑过期**两套实现，逻辑过期通过后台线程异步重建缓存，避免阻塞用户请求。

→ [缓存与数据库一致性](docs/02-缓存与一致性.md)

### 3. 高并发秒杀

- **防超卖**：把库存判断直接写进 SQL 条件（`where ... and stock > 0`），用乐观锁替代悲观锁；
- **一人一单**：集群下 JVM 锁失效，改用 Redis 分布式锁。既实现了基于 `SETNX` + Lua 释放的自研锁，也接入了 Redisson（可重入、看门狗续期、联锁）；
- **资格预检**：库存判断、用户去重、扣减、写待投递消息全部在**一个 Lua 脚本**中原子完成。

→ [秒杀：超卖、一人一单与分布式锁](docs/03-秒杀与分布式锁.md)

### 4. 异步下单与可靠消息

同步下单四步全走数据库，压测吞吐低。优化为"**Redis 快速判定 + 消息队列异步落库**"。

关键是解决"扣了库存但消息丢了"的风险，采用 **Outbox 模式**：

- Lua 脚本把订单写入 **Redis Stream**，与扣库存同一原子操作；
- 定时中继把 Stream 中的消息转发到 RabbitMQ，**仅在收到 broker confirm 后才 ACK Stream**，失败则留在 PEL 重试；
- RabbitMQ 侧为**正常消费 → 重试队列 → 失败兜底队列**三级链路，失败消息绝不静默丢弃；
- 消费者手动 ACK，事务提交后才确认，并以订单唯一索引 + 幂等校验抵御重复消费。

→ [异步下单与可靠消息投递](docs/04-异步下单与可靠消息.md)

### 5. 数据结构选型

点赞排行榜用 **ZSet**（时间戳作 score）、共同关注用 **Set**（求交集）、附近商铺用 **GEO**、签到用 **BitMap**、UV 估算可用 **HyperLogLog**。

→ [Redis 数据结构实战](docs/05-Redis数据结构实战.md)

## 架构

```
                    ┌──────────────┐
  浏览器 ──────────▶│ Nginx :80    │  静态页面 + /api 反向代理
                    └──────┬───────┘
                           │ /api
                    ┌──────▼───────────────────────────────┐
                    │ Spring Boot 应用 :8081               │
                    │  ├ 双层拦截器（登录态 / 续期）        │
                    │  ├ CacheClient（穿透 / 逻辑过期）      │
                    │  ├ 秒杀入口 ── seckill.lua            │
                    │  ├ 定时中继（Stream → RabbitMQ）       │
                    │  └ MQ 消费者（幂等落库）               │
                    └──┬─────────┬──────────┬──────────────┘
                       │         │          │
                 ┌─────▼──┐ ┌────▼────┐ ┌───▼──────────────┐
                 │ MySQL  │ │  Redis  │ │ RabbitMQ         │
                 │  :3306 │ │  :6379  │ │ :5672 / :15672   │
                 └────────┘ └─────────┘ └──────────────────┘

秒杀链路：
Lua 原子校验 ─▶ Redis Stream(Outbox) ─▶ [中继] ─▶ RabbitMQ ─▶ 消费者
                    ▲                                  │
                    └──── confirm 后 ACK ◀─────────────┘
```

## 快速开始

### 方式一：Docker Compose（推荐）

前置条件：Docker Engine + Docker Compose v2。

```bash
cp .env.example .env
# 编辑 .env，把所有 replace-with-* 占位符换成强密码
docker compose up --build -d
docker compose ps
```

首次启动时 MySQL 会自动执行 `src/main/resources/db/hmdp.sql` 建库并导入数据。

访问地址：

| 服务 | 地址 |
| --- | --- |
| 前端页面 | http://localhost |
| 后端 API | http://localhost:8081 |
| RabbitMQ 管理台 | http://localhost:15672 |

详细维护命令见 [DEPLOYMENT.md](DEPLOYMENT.md)。

### 方式二：本地运行

1. 准备 MySQL 8、Redis、RabbitMQ 各一份；
2. 导入 `src/main/resources/db/hmdp.sql`；
3. 通过环境变量提供连接信息（见 `.env.example`），至少需要：
   `MYSQL_PASSWORD`、`RABBITMQ_PASSWORD`；
4. 启动：

```bash
mvn spring-boot:run
```

### 登录说明

项目未接入真实短信服务商，验证码会以日志形式输出：

```
短信验证码发送成功：123456
```

在应用日志中查看该验证码即可完成登录。

## 压测与故障验证

`bench/` 下为基于 Python 标准库的验证脚本，结果写入 `bench/results/`：

| 脚本 | 验证内容 |
| --- | --- |
| `seckill_correctness.py` | 并发下单不超卖、不重复：库存 100 / 1000 用户 / 200 并发 |
| `rabbitmq_fault_injection.py` | RabbitMQ 中断时消息停留在 Stream PEL，恢复后最终落库 |
| `mysql_fault_injection.py` | MySQL 中断时订单消息保留在失败队列，不丢失 |
| `cache_hot_benchmark.py` | 热点接口吞吐与延迟基准 |

实测参考数据（`bench/results/`）：

- 库存 100 / 1000 请求 / 200 并发：**QPS ≈ 561**，p50 ≈ 168ms，p99 ≈ 393ms，成功订单数恰为 **100**；
- RabbitMQ 中断后恢复：`pending_during_outage = 1`，`persisted_after_recovery = true`。

## 项目结构

```
.
├── src/main/java/com/hmdp/
│   ├── config/           # MVC、MyBatis-Plus、Redisson、RabbitMQ 队列声明
│   ├── controller/       # REST 接口
│   ├── interceptor/      # 双层登录拦截器
│   ├── listener/         # Stream→MQ 中继、订单消费者
│   ├── service/impl/     # 业务实现（秒杀、商户缓存、点赞关注、签到等）
│   └── utils/            # CacheClient、分布式锁、ID 生成、常量
├── src/main/resources/
│   ├── seckill.lua       # 秒杀原子预检 + Outbox 写入
│   ├── unlock.lua        # 分布式锁安全释放
│   ├── application.yaml
│   └── db/hmdp.sql       # 建库脚本
├── src/test/             # 单元与集成测试
├── docs/                 # 技术文档（本目录）
├── bench/                # 压测与故障注入脚本、结果
├── nginx/default.conf    # 前端静态托管 + /api 反向代理
├── frontend/             # 前端静态页面
├── compose.yaml          # 一键编排 MySQL / Redis / RabbitMQ / App / Nginx
├── Dockerfile
├── DEPLOYMENT.md
└── .env.example
```

## 文档

- [01 登录认证与分布式会话](docs/01-登录与分布式会话.md)
- [02 缓存与数据库一致性](docs/02-缓存与一致性.md)
- [03 秒杀：超卖、一人一单与分布式锁](docs/03-秒杀与分布式锁.md)
- [04 异步下单与可靠消息投递](docs/04-异步下单与可靠消息.md)
- [05 Redis 数据结构实战](docs/05-Redis数据结构实战.md)
