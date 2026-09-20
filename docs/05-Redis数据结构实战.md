# Redis 数据结构实战

除了当缓存，Redis 的几种数据结构在本项目里各自解决了一类具体问题。选型的核心思路是：**先想清楚数据的访问模式，再挑最贴合的数据结构，而不是一律用 String 存 JSON。**

## ZSet：点赞与点赞排行榜

**需求**：一个用户对一篇笔记只能点一次赞；还要展示"最早点赞的前 N 个用户"。

**为什么不用 MySQL**：点赞是高频写操作，直接打 MySQL 既慢又会影响其他业务。

**为什么用 ZSet 而不是 Set**：Set 只能判重，无法排序；而"点赞排行榜"需要按点赞时间排序。ZSet 在 Set 的基础上多了一个 `score`，用**点赞时间戳作为 score**，天然按时间有序。

```java
// 点赞：时间戳作为 score
stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
// 取消点赞
stringRedisTemplate.opsForZSet().remove(key, userId.toString());
// 判断是否点过赞（score 为 null 表示没点过）
Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
```

要点：

- ZSet 底层是**跳表 + 哈希表**，哈希表保证 `score` 查询是 O(1)，跳表保证范围查询和排序高效；
- 元素唯一，同一用户重复点赞不会产生多条记录；
- 点赞时同步更新数据库的点赞计数（`liked = liked + 1`），并依据数据库更新是否成功来决定是否写 Redis，保证两边不脱节；
- 排行榜用 `range(key, 0, 4)` 取 score 最小的 5 个（即最早点赞的 5 人）。

对应代码：`service/impl/BlogServiceImpl.java`。

## Set：关注、取关与共同关注

**需求**：关注/取关某个用户；查询"我和他共同关注了谁"。

Set 天然去重、且支持集合运算，正好匹配：

```java
// 关注：写入数据库成功后，同步写入 Set
stringRedisTemplate.opsForSet().add("follows:" + userId, followUserId.toString());
// 取关
stringRedisTemplate.opsForSet().remove("follows:" + userId, followUserId.toString());
// 共同关注：两个 Set 求交集
stringRedisTemplate.opsForSet().intersect("follows:" + a, "follows:" + b);
```

交集在 Redis 内部完成，只需一次网络往返；如果放在应用层做，就得先把两个用户的关注列表全量拉到内存再求交，数据量大时非常浪费。

对应代码：`service/impl/FollowServiceImpl.java`。

## GEO：附近商铺

**需求**：按距离查找我附近 5km 内的商铺，并按距离由近到远排序，还要做分页。

Redis 的 GEO 底层就是 **ZSet**：把经纬度按 geohash 编码成 `score`，因此天然具备有序范围查询能力。

本项目用 Spring Data Redis 的 `opsForGeo().search(...)`：

```java
GeoResults<...> results = stringRedisTemplate.opsForGeo().search(
        SHOP_GEO_KEY + typeId,
        GeoReference.fromCoordinate(x, y),
        new Distance(5000),                       // 5000 米
        GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance()                // 返回距离
                .limit(end)                       // 取到 end 条，用于分页
);
```

实现细节：

- **按商铺类型分 key**（`shop:geo:{typeId}`），避免所有类型混在一个集合里互相干扰；
- 先取到 `end = current * pageSize` 条，再 `skip(from)` 做内存分页；
- **距离排序由 Redis 完成**，但按 id 批量查商铺详情只能用 `in` 查询，MySQL 的 `in` 不保证顺序，因此用 `order by field(id, ...)` 显式维持 Redis 返回的距离顺序，再把距离回填到结果里。

对应代码：`service/impl/ShopServiceImpl.java#queryShopByType`。

## BitMap：用户签到

**需求**：记录用户每天是否签到，并统计连续签到天数。

**选型**：签到本质是"二值状态"（签了 / 没签），一个月最多 31 个状态。如果用 String 存 `"1,0,1..."` 或用 Set 存已签日期，都很浪费空间。BitMap 用**每个 bit 表示一天**，一个用户一个月的签到记录只需 4 个字节。

```java
// key 形如 sign:{userId}:202609，offset 为"本月第几天 - 1"
stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
```

统计连续签到天数的做法比较巧妙：

1. 用 `BITFIELD key GET u{dayOfMonth} 0` 一次性取出"本月截至今天的签到位"，得到一个十进制数；
2. 从这个数的最低位开始，循环判断末位是否为 1（`num & 1`），是则计数并右移一位（`num >>>= 1`），遇到 0 即中断。

因为 bit 的排列顺序是"低位对应早的日期"，从低位往高位扫描恰好就是"从今天往前数连续签到"，无需逐天去查 Redis。

对应代码：`service/impl/UserServiceImpl.java#sign`、`signCount`，常量见 `utils/RedisConstants.java`。

## HyperLogLog：UV 统计（选型笔记）

UV（独立访客数）需要统计"有多少个不同的用户来过"。

- 用 Set 精确存储所有用户 ID，100 万用户约需几十 MB，且并发写入开销大；
- **HyperLogLog** 是概率型数据结构，用**固定 ≤ 16KB** 的内存估算基数，标准误差约 0.81%，且支持多个 HLL 合并（`PFMERGE`）。对 UV 这种"数量级正确即可"的指标非常划算。

命令为 `PFADD key userId` 添加、`PFCOUNT key` 估算。需要注意：

- 它是估算值，不是精确值，不能用于对账类场景；
- 单个用户重复 `PFADD` 不会重复计数；
- 该方案属于本项目的**数据结构选型记录，尚未接入主业务流程**，这里仅作说明，避免与已实现功能混淆。

## 小结：什么场景选什么结构

| 场景 | 结构 | 关键理由 |
| --- | --- | --- |
| 登录态 | String | 简单的 key-value，带过期时间 |
| 点赞 / 排行榜 | ZSet | 需要按时间排序 + 去重 |
| 关注 / 共同关注 | Set | 去重 + 集合运算（交集） |
| 附近商铺 | GEO（底层 ZSet） | 地理范围查询 + 距离排序 |
| 签到 | BitMap | 海量二值状态，极致省内存 |
| UV 估算 | HyperLogLog | 允许误差时内存最优 |
