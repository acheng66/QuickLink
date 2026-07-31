/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quicklink.project.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import quicklink.project.dao.entity.QuickLinkDO;
import quicklink.project.dao.mapper.QuickLinkMapper;

import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static quicklink.project.common.constant.RedisKeyConstant.LOCK_QUICK_LINK_BLOOM_REBUILD_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_BLOOM_ACTIVE_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_BLOOM_LAST_REBUILD_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_BLOOM_READY_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_BLOOM_REBUILDING_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_BLOOM_RETIRED_ZSET_KEY;

/**
 * 短链接布隆过滤器双缓冲管理服务。
 *
 * <p>重建期间，新创建的短链接同时写入 active 和 rebuilding；全量加载完成后原子切换 active，
 * 旧过滤器延迟删除。过滤器未就绪时，跳转查询会放行到数据库，避免产生假阴性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickLinkBloomFilterService {

    /** 第一套短链接布隆过滤器；与 v2 轮流承担线上查询。 */
    private static final String BLOOM_FILTER_V1 = "quick-link:bloom:v1";

    /** 第二套短链接布隆过滤器；当 v1 在线时在后台重建 v2，反之亦然。 */
    private static final String BLOOM_FILTER_V2 = "quick-link:bloom:v2";

    /** 改造前使用的过滤器名称，仅用于首次升级时平滑迁移已有数据。 */
    private static final String LEGACY_BLOOM_FILTER = "shortUriCreateCachePenetrationBloomFilter";

    /**
     * 新过滤器全量加载成功后执行的原子切换脚本。
     *
     * <p>KEYS 映射：
     * <ol>
     *   <li>KEYS[1]：active，当前对外服务的过滤器名称；</li>
     *   <li>KEYS[2]：ready，已经完成全量加载的过滤器名称；</li>
     *   <li>KEYS[3]：rebuilding，当前正在重建的过滤器名称；</li>
     *   <li>KEYS[4]：last-rebuild，最近一次重建完成时间；</li>
     *   <li>KEYS[5]：retired，等待延迟删除的旧过滤器 ZSet。</li>
     * </ol>
     *
     * <p>ARGV 映射：
     * <ol>
     *   <li>ARGV[1]：新过滤器名称 targetName；</li>
     *   <li>ARGV[2]：旧过滤器名称 activeName；</li>
     *   <li>ARGV[3]：本次切换完成时间；</li>
     *   <li>ARGV[4]：旧过滤器允许删除的时间。</li>
     * </ol>
     *
     * <p>Lua 在 Redis 内一次完成 active/ready 切换、重建标记删除、时间记录和旧版本退役，
     * 避免 Java 分多条命令执行到一半时进程崩溃，留下互相矛盾的状态。
     */
    private static final DefaultRedisScript<Long> SWITCH_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1]); " +
                    "redis.call('SET', KEYS[2], ARGV[1]); " +
                    "redis.call('DEL', KEYS[3]); " +
                    "redis.call('SET', KEYS[4], ARGV[3]); " +
                    "if ARGV[2] ~= ARGV[1] then redis.call('ZADD', KEYS[5], ARGV[4], ARGV[2]); end; " +
                    "return 1;",
            Long.class);

    /**
     * 重建失败后的“比较并删除”脚本。
     *
     * <p>只有 Redis 中的 rebuilding 仍等于本任务的 targetName 时才删除，防止旧任务失败后
     * 把另一个实例刚写入的新重建状态误删。KEYS[1] 是 rebuilding，ARGV[1] 是 targetName。
     */
    private static final DefaultRedisScript<Long> CLEAR_REBUILDING_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]); end; return 0;",
            Long.class);

    /** 操作 Redisson 布隆过滤器和获取重建分布式锁。 */
    private final RedissonClient redissonClient;

    /** 读写 active/ready/rebuilding 等控制 Key，并负责执行 Redis Lua 脚本。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 分页读取数据库中的有效短链接，为新过滤器提供全量数据。 */
    private final QuickLinkMapper quickLinkMapper;

    /** 预计最多写入的短链接数量；Redisson 根据它计算位图大小。 */
    @Value("${quick-link.bloom-filter.expected-insertions:100000000}")
    private Long expectedInsertions;

    /** 允许的误判率；0.001 表示约 0.1%，只会假阳性，不会对已写入元素产生假阴性。 */
    @Value("${quick-link.bloom-filter.false-probability:0.001}")
    private Double falseProbability;

    /** 每次数据库查询加载的短链接数量，避免一次性把全部数据读进 JVM。 */
    @Value("${quick-link.bloom-filter.rebuild-batch-size:1000}")
    private Integer rebuildBatchSize;

    /** 两次有效重建之间的最短间隔，用于避免 project 与 aggregation 同时重复重建。 */
    @Value("${quick-link.bloom-filter.minimum-rebuild-interval-millis:43200000}")
    private Long minimumRebuildIntervalMillis;

    /** active 切换后旧过滤器继续保留的时间，让切换前已经读取旧名称的请求自然完成。 */
    @Value("${quick-link.bloom-filter.retire-delay-millis:300000}")
    private Long retireDelayMillis;

    @PostConstruct
    public void initializeActiveFilter() {
        String activeName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_ACTIVE_KEY);
        if (StrUtil.isNotBlank(activeName)) {
            RBloomFilter<String> activeFilter = getFilter(activeName);
            if (!activeFilter.isExists()) {
                activeFilter.tryInit(expectedInsertions, falseProbability);
                String readyName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_READY_KEY);
                if (Objects.equals(activeName, readyName)) {
                    // active 指针还在但过滤器数据已丢失时，先标记为未就绪，查询放行至数据库。
                    stringRedisTemplate.delete(QUICK_LINK_BLOOM_READY_KEY);
                }
            }
            return;
        }
        // 首次升级先沿用旧过滤器，等 v1 全量加载完成后再切换，避免迁移期间产生假阴性。
        RBloomFilter<String> legacyFilter = getFilter(LEGACY_BLOOM_FILTER);
        String initialName = legacyFilter.isExists() ? LEGACY_BLOOM_FILTER : BLOOM_FILTER_V1;
        Boolean initialized = stringRedisTemplate.opsForValue().setIfAbsent(QUICK_LINK_BLOOM_ACTIVE_KEY, initialName);
        if (Boolean.TRUE.equals(initialized)) {
            if (legacyFilter.isExists()) {
                stringRedisTemplate.opsForValue().set(QUICK_LINK_BLOOM_READY_KEY, LEGACY_BLOOM_FILTER);
            } else {
                // 空 v1 尚未装载数据库数据，因此暂时不能标记 ready。
                getFilter(BLOOM_FILTER_V1).tryInit(expectedInsertions, falseProbability);
            }
        }
    }

    /**
     * 跳转查询使用。未就绪或 Redis 异常时返回 true，让请求继续查询数据库。
     */
    public boolean mightContain(String fullShortUrl) {
        try {
            String activeName = getActiveName();
            String readyName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_READY_KEY);
            // 未完成全量加载时不能相信 false 结果，放行数据库可避免合法链接被误拦截。
            if (!Objects.equals(activeName, readyName)) {
                return true;
            }
            return getFilter(activeName).contains(fullShortUrl);
        } catch (Throwable ex) {
            log.warn("查询短链接布隆过滤器失败，放行至数据库，fullShortUrl={}", fullShortUrl, ex);
            return true;
        }
    }

    /**
     * 创建短码查重使用。过滤器未就绪时返回 false，最终仍由数据库唯一键兜底。
     */
    public boolean containsForCreation(String fullShortUrl) {
        try {
            String activeName = getActiveName();
            String readyName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_READY_KEY);
            return Objects.equals(activeName, readyName) && getFilter(activeName).contains(fullShortUrl);
        } catch (Throwable ex) {
            log.warn("创建短链接时查询布隆过滤器失败，交由数据库唯一键兜底，fullShortUrl={}", fullShortUrl, ex);
            return false;
        }
    }

    /**
     * 创建成功后写入当前过滤器；重建期间同步写入新过滤器，避免切换时遗漏增量数据。
     */
    public void add(String fullShortUrl) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String activeBefore = getActiveName();
            String rebuildingBefore = getRebuildingName();
            addToFilters(fullShortUrl, activeBefore, rebuildingBefore);

            // 写入期间如果刚好发生重建开始或 active 切换，再补写最新状态对应的过滤器。
            String activeAfter = getActiveName();
            String rebuildingAfter = getRebuildingName();
            if (Objects.equals(activeBefore, activeAfter)
                    && Objects.equals(rebuildingBefore, rebuildingAfter)) {
                return;
            }
            addToFilters(fullShortUrl, activeAfter, rebuildingAfter);
        }
    }

    /**
     * 数据库事务提交后再写布隆过滤器。这样重建任务要么能从数据库扫描到新记录，
     * 要么能通过 rebuilding 双写收到它，不会把尚未提交的数据遗漏在新过滤器之外。
     */
    public void addAfterCommit(String fullShortUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            add(fullShortUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    add(fullShortUrl);
                } catch (Throwable ex) {
                    // 数据库已经提交，布隆过滤器异常不能把创建接口变成“失败但实际已创建”。
                    log.error("事务提交后写入短链接布隆过滤器失败，等待下次全量重建修复，fullShortUrl={}", fullShortUrl, ex);
                    try {
                        // 暂停使用可能不完整的过滤器拦截请求，优先让查询回源数据库。
                        stringRedisTemplate.delete(QUICK_LINK_BLOOM_READY_KEY);
                    } catch (Throwable redisException) {
                        log.error("标记短链接布隆过滤器未就绪失败", redisException);
                    }
                }
            }
        });
    }

    /**
     * 服务启动 5 秒后执行一次，之后每天重建一次。多实例通过分布式锁和最后重建时间去重。
     */
    @Scheduled(
            initialDelayString = "${quick-link.bloom-filter.rebuild-initial-delay-millis:5000}",
            fixedDelayString = "${quick-link.bloom-filter.rebuild-interval-millis:86400000}")
    public void rebuild() {
        // 所有 project/aggregation 实例共用同一把 Redisson 分布式锁。
        RLock lock = redissonClient.getLock(LOCK_QUICK_LINK_BLOOM_REBUILD_KEY);
        if (!lock.tryLock()) {
            return;
        }
        String targetName = null;
        try {
            long nowMillis = System.currentTimeMillis();
            String lastRebuild = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_LAST_REBUILD_KEY);
            // 如果别的实例刚完成重建，本实例跳过，减少重复的全表扫描和 Redis 写入。
            if (StrUtil.isNotBlank(lastRebuild)
                    && nowMillis - Long.parseLong(lastRebuild) < minimumRebuildIntervalMillis) {
                return;
            }

            String activeName = getActiveName();
            // active 是 v1 时构建 v2；active 是 v2 或旧过滤器时构建 v1。
            targetName = Objects.equals(activeName, BLOOM_FILTER_V1) ? BLOOM_FILTER_V2 : BLOOM_FILTER_V1;
            RBloomFilter<String> targetFilter = getFilter(targetName);
            // 删除目标版本上一次留下的位图和配置，确保过期/删除数据不会残留。
            targetFilter.delete();
            targetFilter.tryInit(expectedInsertions, falseProbability);
            // 发布 rebuilding 名称；从这一刻起，新建短链接会同时写 active 和 target。
            stringRedisTemplate.opsForValue().set(QUICK_LINK_BLOOM_REBUILDING_KEY, targetName);

            long loadedCount = loadAllValidLinks(targetFilter);
            long switchTime = System.currentTimeMillis();
            // 通过 Lua 在一次 Redis 原子操作中完成状态切换和旧版本退役登记。
            stringRedisTemplate.execute(
                    SWITCH_SCRIPT,
                    List.of(
                            QUICK_LINK_BLOOM_ACTIVE_KEY,
                            QUICK_LINK_BLOOM_READY_KEY,
                            QUICK_LINK_BLOOM_REBUILDING_KEY,
                            QUICK_LINK_BLOOM_LAST_REBUILD_KEY,
                            QUICK_LINK_BLOOM_RETIRED_ZSET_KEY),
                    targetName,
                    activeName,
                    String.valueOf(switchTime),
                    String.valueOf(switchTime + retireDelayMillis));
            log.info("短链接布隆过滤器重建完成，active={} loadedCount={}", targetName, loadedCount);
        } catch (Throwable ex) {
            if (StrUtil.isNotBlank(targetName)) {
                // 使用比较并删除 Lua，避免误删另一实例后续写入的 rebuilding 值。
                stringRedisTemplate.execute(
                        CLEAR_REBUILDING_SCRIPT,
                        List.of(QUICK_LINK_BLOOM_REBUILDING_KEY),
                        targetName);
            }
            // 不切换 active；线上请求继续使用原过滤器，失败的半成品下次会先 delete 再重建。
            log.error("短链接布隆过滤器重建失败，继续使用原过滤器", ex);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 定期删除已切换下线且超过保留时间的旧过滤器。
     */
    @Scheduled(
            initialDelayString = "${quick-link.bloom-filter.cleanup-initial-delay-millis:60000}",
            fixedDelayString = "${quick-link.bloom-filter.cleanup-interval-millis:60000}")
    public void cleanupRetiredFilters() {
        long now = System.currentTimeMillis();
        // retired 的 score 是最早允许删除时间；每次最多清理 10 个，避免任务占用线程过久。
        Set<String> retiredNames = stringRedisTemplate.opsForZSet()
                .rangeByScore(QUICK_LINK_BLOOM_RETIRED_ZSET_KEY, 0, now, 0, 10);
        if (CollUtil.isEmpty(retiredNames)) {
            return;
        }
        String activeName = getActiveName();
        String rebuildingName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_REBUILDING_KEY);
        for (String retiredName : retiredNames) {
            // 只有既不是 active、也不是 rebuilding 的版本才可以物理删除。
            if (!Objects.equals(retiredName, activeName) && !Objects.equals(retiredName, rebuildingName)) {
                getFilter(retiredName).delete();
                stringRedisTemplate.opsForZSet().remove(QUICK_LINK_BLOOM_RETIRED_ZSET_KEY, retiredName);
                log.info("已删除退役短链接布隆过滤器，name={}", retiredName);
            }
        }
    }

    /**
     * 使用主键游标分页，把数据库里的有效短链接全量写入目标过滤器。
     *
     * @param targetFilter 当前离线构建的 v1 或 v2
     * @return 实际写入过滤器的有效短链接数量
     */
    private long loadAllValidLinks(RBloomFilter<String> targetFilter) {
        // 从 id=0 开始游标扫描；下一批使用上一批最大 id，避免 OFFSET 深分页。
        long lastId = 0L;
        long loadedCount = 0L;
        // 整轮重建共用同一个时间点，避免重建过程中有效期判断标准不断变化。
        Date now = new Date();
        // 固定扫描上界，重建期间新增记录由 addAfterCommit 双写，不需要循环持续追赶。
        Long maxId = quickLinkMapper.selectMaxIdForBloomRebuild();
        if (maxId == null || maxId <= 0L) {
            return 0L;
        }
        while (true) {
            List<QuickLinkDO> links = quickLinkMapper.selectValidLinksForBloomRebuild(
                    lastId, maxId, now, rebuildBatchSize);
            if (CollUtil.isEmpty(links)) {
                return loadedCount;
            }
            for (QuickLinkDO link : links) {
                if (StrUtil.isNotBlank(link.getFullShortUrl())) {
                    targetFilter.add(link.getFullShortUrl());
                    loadedCount++;
                }
            }
            lastId = links.get(links.size() - 1).getId();
            if (links.size() < rebuildBatchSize) {
                return loadedCount;
            }
        }
    }

    /**
     * 获取当前 active 名称；控制 Key 丢失时自动执行初始化。
     */
    private String getActiveName() {
        String activeName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_ACTIVE_KEY);
        if (StrUtil.isBlank(activeName)) {
            initializeActiveFilter();
            activeName = stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_ACTIVE_KEY);
        }
        return StrUtil.blankToDefault(activeName, BLOOM_FILTER_V1);
    }

    private String getRebuildingName() {
        return stringRedisTemplate.opsForValue().get(QUICK_LINK_BLOOM_REBUILDING_KEY);
    }

    private void addToFilters(String fullShortUrl, String activeName, String rebuildingName) {
        Set<String> filterNames = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(activeName)) {
            filterNames.add(activeName);
        }
        if (StrUtil.isNotBlank(rebuildingName)) {
            filterNames.add(rebuildingName);
        }
        filterNames.forEach(each -> getFilter(each).add(fullShortUrl));
    }

    /**
     * 根据 Redis 名称获取 Redisson 的布隆过滤器代理；调用本身不会全量加载位图到 JVM。
     */
    private RBloomFilter<String> getFilter(String name) {
        return redissonClient.getBloomFilter(name);
    }
}
