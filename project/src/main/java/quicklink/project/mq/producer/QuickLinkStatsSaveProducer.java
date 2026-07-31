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

package quicklink.project.mq.producer;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.UUID;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_EXCHANGE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_RETRY_EXCHANGE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_RETRY_HEADER;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_RETRY_ROUTING_KEY;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_ROUTING_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.LOCK_QUICK_LINK_STATS_PENDING_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_STATS_PENDING_HASH_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_STATS_PENDING_ZSET_KEY;

/**
 * 统计消息生产者。
 * 消息在发送前保存到 Redis 待确认集合，Broker ACK 后删除；未确认消息由定时任务补发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickLinkStatsSaveProducer {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    @Value("${quick-link.stats.mq.publisher-retry-interval-millis:5000}")
    private Long publisherRetryIntervalMillis;

    @Value("${quick-link.stats.mq.retry-delay-millis:5000}")
    private Long consumerRetryDelayMillis;

    public void send(QuickLinkStatsRecordDTO statsRecord) {
        if (statsRecord.getKeys() == null) {
            statsRecord.setKeys(UUID.fastUUID().toString());
        }
        savePending(statsRecord);
        sendBusinessMessage(statsRecord);
    }

    /**
     * 消费失败后投递到延迟重试队列。只有 Broker Confirm 成功才返回 true。
     */
    public boolean sendRetry(QuickLinkStatsRecordDTO statsRecord, int retryCount) {
        CorrelationData correlationData = new CorrelationData(statsRecord.getKeys() + "-retry-" + retryCount);
        try {
            rabbitTemplate.convertAndSend(
                    QUICK_LINK_STATS_RETRY_EXCHANGE,
                    QUICK_LINK_STATS_RETRY_ROUTING_KEY,
                    statsRecord,
                    message -> {
                        message.getMessageProperties().setMessageId(statsRecord.getKeys());
                        message.getMessageProperties().setHeader(QUICK_LINK_STATS_RETRY_HEADER, retryCount);
                        message.getMessageProperties().setExpiration(String.valueOf(consumerRetryDelayMillis));
                        return message;
                    },
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            return confirm != null && confirm.isAck() && correlationData.getReturned() == null;
        } catch (Throwable ex) {
            log.error("[RabbitMQ] 统计消息投递重试队列失败 messageId={} retryCount={}",
                    statsRecord.getKeys(), retryCount, ex);
            return false;
        }
    }

    /**
     * 补偿未收到 Broker ACK 的生产消息。多实例下通过分布式锁避免重复扫描。
     */
    @Scheduled(fixedDelayString = "${quick-link.stats.mq.publisher-retry-interval-millis:5000}")
    public void retryPendingMessages() {
        RLock lock = redissonClient.getLock(LOCK_QUICK_LINK_STATS_PENDING_KEY);
        if (!lock.tryLock()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Set<String> messageIds = stringRedisTemplate.opsForZSet()
                    .rangeByScore(QUICK_LINK_STATS_PENDING_ZSET_KEY, 0, now, 0, 100);
            if (CollUtil.isEmpty(messageIds)) {
                return;
            }
            for (String messageId : messageIds) {
                Object payload = stringRedisTemplate.opsForHash()
                        .get(QUICK_LINK_STATS_PENDING_HASH_KEY, messageId);
                if (payload == null) {
                    removePending(messageId);
                    continue;
                }
                // 先推迟下一次扫描时间，防止发送期间被重复补偿。
                stringRedisTemplate.opsForZSet().add(
                        QUICK_LINK_STATS_PENDING_ZSET_KEY,
                        messageId,
                        now + publisherRetryIntervalMillis);
                QuickLinkStatsRecordDTO statsRecord =
                        JSON.parseObject(payload.toString(), QuickLinkStatsRecordDTO.class);
                sendBusinessMessage(statsRecord);
            }
        } finally {
            lock.unlock();
        }
    }

    private void savePending(QuickLinkStatsRecordDTO statsRecord) {
        stringRedisTemplate.opsForHash().put(
                QUICK_LINK_STATS_PENDING_HASH_KEY,
                statsRecord.getKeys(),
                JSON.toJSONString(statsRecord));
        stringRedisTemplate.opsForZSet().add(
                QUICK_LINK_STATS_PENDING_ZSET_KEY,
                statsRecord.getKeys(),
                System.currentTimeMillis() + publisherRetryIntervalMillis);
    }

    private void sendBusinessMessage(QuickLinkStatsRecordDTO statsRecord) {
        String messageId = statsRecord.getKeys();
        CorrelationData correlationData = new CorrelationData(messageId);
        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null || confirm == null || !confirm.isAck() || correlationData.getReturned() != null) {
                log.error("[RabbitMQ] 统计消息未被可靠接收，将等待补偿 messageId={} reason={}",
                        messageId, confirm == null ? "no-confirm" : confirm.getReason(), throwable);
                schedulePendingNow(messageId);
                return;
            }
            removePending(messageId);
        });
        try {
            rabbitTemplate.convertAndSend(
                    QUICK_LINK_STATS_EXCHANGE,
                    QUICK_LINK_STATS_ROUTING_KEY,
                    statsRecord,
                    message -> {
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    },
                    correlationData);
        } catch (Throwable ex) {
            schedulePendingNow(messageId);
            throw ex;
        }
    }

    private void schedulePendingNow(String messageId) {
        stringRedisTemplate.opsForZSet().add(
                QUICK_LINK_STATS_PENDING_ZSET_KEY,
                messageId,
                System.currentTimeMillis() + publisherRetryIntervalMillis);
    }

    private void removePending(String messageId) {
        stringRedisTemplate.opsForHash().delete(QUICK_LINK_STATS_PENDING_HASH_KEY, messageId);
        stringRedisTemplate.opsForZSet().remove(QUICK_LINK_STATS_PENDING_ZSET_KEY, messageId);
    }
}
