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

package quicklink.project.common.constant;

/**
 * Redis Key 常量类
 */
public class RedisKeyConstant {

    /**
     * 短链接跳转前缀 Key
     */
    public static final String GOTO_QUICK_LINK_KEY = "quick-link:goto:%s";

    /**
     * 短链接空值跳转前缀 Key
     */
    public static final String GOTO_IS_NULL_QUICK_LINK_KEY = "quick-link:is-null:goto_%s";

    /**
     * 短链接跳转锁前缀 Key
     */
    public static final String LOCK_GOTO_QUICK_LINK_KEY = "quick-link:lock:goto:%s";

    /**
     * 短链接修改分组 ID 锁前缀 Key
     */
    public static final String LOCK_GID_UPDATE_KEY = "quick-link:lock:update-gid:%s";

    /**
     * 短链接延迟队列消费统计 Key
     */
    public static final String DELAY_QUEUE_STATS_KEY = "quick-link:delay-queue:stats";

    /**
     * 短链接统计判断是否新用户缓存标识
     */
    public static final String QUICK_LINK_STATS_UV_KEY = "quick-link:stats:uv:";

    /**
     * 短链接统计判断是否新 IP 缓存标识
     */
    public static final String QUICK_LINK_STATS_UIP_KEY = "quick-link:stats:uip:";

    /**
     * 短链接监控消息保存队列 Topic 缓存标识
     */
    public static final String QUICK_LINK_STATS_STREAM_TOPIC_KEY = "quick-link:stats-stream";

    /**
     * 短链接监控消息保存队列 Group 缓存标识
     */
    public static final String QUICK_LINK_STATS_STREAM_GROUP_KEY = "quick-link:stats-stream:only-group";

    /**
     * 创建短链接锁标识
     */
    public static final String QUICK_LINK_CREATE_LOCK_KEY = "quick-link:lock:create";

    /**
     * 尚未收到 Broker Confirm 的统计消息
     */
    public static final String QUICK_LINK_STATS_PENDING_HASH_KEY = "quick-link:stats:pending:payload";

    /**
     * 尚未收到 Broker Confirm 的统计消息重试时间
     */
    public static final String QUICK_LINK_STATS_PENDING_ZSET_KEY = "quick-link:stats:pending:schedule";

    /**
     * 生产者补偿任务分布式锁
     */
    public static final String LOCK_QUICK_LINK_STATS_PENDING_KEY = "quick-link:lock:stats:pending";

    /**
     * IP 地理位置短期缓存
     */
    public static final String QUICK_LINK_STATS_LOCALE_KEY = "quick-link:stats:locale:%s";

    /** String：当前提供查询服务的过滤器名称，例如 quick-link:bloom:v1。 */
    public static final String QUICK_LINK_BLOOM_ACTIVE_KEY = "quick-link:bloom:active";

    /** String：已经完成数据库全量装载的过滤器名称；只有 active == ready 才能安全返回“不存在”。 */
    public static final String QUICK_LINK_BLOOM_READY_KEY = "quick-link:bloom:ready";

    /** String：正在重建的过滤器名称；存在该值时，新建短链接需要同时写入 active 和 rebuilding。 */
    public static final String QUICK_LINK_BLOOM_REBUILDING_KEY = "quick-link:bloom:rebuilding";

    /** ZSet：成员是退役过滤器名称，分数是最早允许物理删除的毫秒时间戳。 */
    public static final String QUICK_LINK_BLOOM_RETIRED_ZSET_KEY = "quick-link:bloom:retired";

    /** String：最近一次成功切换的毫秒时间戳，用于避免多实例短时间重复重建。 */
    public static final String QUICK_LINK_BLOOM_LAST_REBUILD_KEY = "quick-link:bloom:last-rebuild";

    /** Redisson Lock：同一时刻只允许一个服务实例执行布隆过滤器全量重建。 */
    public static final String LOCK_QUICK_LINK_BLOOM_REBUILD_KEY = "quick-link:lock:bloom:rebuild";
}
