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

package quicklink.project.mq.consumer;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;
import quicklink.project.mq.producer.QuickLinkStatsSaveProducer;
import quicklink.project.service.QuickLinkStatsLocationService;
import quicklink.project.service.QuickLinkStatsPersistService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_QUEUE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_RETRY_HEADER;

/**
 * 短链接统计批量消费者。
 *
 * <p>可靠性策略：
 * 1. 数据库消费记录唯一键提供最终幂等；
 * 2. 消费记录和全部统计写入处于同一事务；
 * 3. 批量失败后降级为逐条处理，隔离毒消息；
 * 4. 有限延迟重试，超过上限后拒绝并进入 DLX。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickLinkStatsSaveConsumer {

    private final QuickLinkStatsPersistService quickLinkStatsPersistService;
    private final QuickLinkStatsLocationService quickLinkStatsLocationService;
    private final QuickLinkStatsSaveProducer quickLinkStatsSaveProducer;
    private final Jackson2JsonMessageConverter messageConverter;

    @Value("${quick-link.stats.mq.max-retry-count:3}")
    private Integer maxRetryCount;

    @RabbitListener(queues = QUICK_LINK_STATS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(List<Message> messages, Channel channel) throws IOException {
        List<Message> validMessages = new ArrayList<>(messages.size());
        List<QuickLinkStatsRecordDTO> records = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                QuickLinkStatsRecordDTO record = convertAndEnrich(message);
                validMessages.add(message);
                records.add(record);
            } catch (Throwable ex) {
                log.error("[RabbitMQ] 统计消息格式非法，直接投入死信队列 deliveryTag={}",
                        message.getMessageProperties().getDeliveryTag(), ex);
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            }
        }
        if (records.isEmpty()) {
            return;
        }
        try {
            quickLinkStatsPersistService.saveBatch(records);
            acknowledgeBatch(validMessages, channel);
        } catch (Throwable batchException) {
            log.warn("[RabbitMQ] 批量统计写入失败，降级为逐条处理，batchSize={}", records.size(), batchException);
            processIndividually(validMessages, records, channel);
        }
    }

    private QuickLinkStatsRecordDTO convertAndEnrich(Message message) {
        Object converted = messageConverter.fromMessage(message);
        if (!(converted instanceof QuickLinkStatsRecordDTO record)) {
            throw new IllegalArgumentException("无法解析短链接统计消息");
        }
        // 外部地理位置请求在事务和读锁之外执行，失败时使用“未知”兜底。
        quickLinkStatsLocationService.enrich(record);
        return record;
    }

    private void processIndividually(
            List<Message> messages,
            List<QuickLinkStatsRecordDTO> records,
            Channel channel) throws IOException {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            QuickLinkStatsRecordDTO record = records.get(i);
            long deliveryTag = message.getMessageProperties().getDeliveryTag();
            try {
                quickLinkStatsPersistService.saveBatch(List.of(record));
                channel.basicAck(deliveryTag, false);
            } catch (Throwable ex) {
                handleFailure(message, record, channel, ex);
            }
        }
    }

    private void handleFailure(
            Message message,
            QuickLinkStatsRecordDTO record,
            Channel channel,
            Throwable exception) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int currentRetryCount = getRetryCount(message);
        if (currentRetryCount >= maxRetryCount) {
            log.error("[RabbitMQ] 统计消息超过最大重试次数，投入死信队列 messageId={} retryCount={}",
                    record.getKeys(), currentRetryCount, exception);
            // 业务队列绑定了 DLX，requeue=false 后消息会进入死信队列。
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        int nextRetryCount = currentRetryCount + 1;
        boolean retryPublished = quickLinkStatsSaveProducer.sendRetry(record, nextRetryCount);
        if (retryPublished) {
            // 重试副本已被 Broker 确认，安全确认原消息。
            channel.basicAck(deliveryTag, false);
            log.warn("[RabbitMQ] 统计消息已进入延迟重试队列 messageId={} retryCount={}",
                    record.getKeys(), nextRetryCount, exception);
        } else {
            // 重试消息未被确认，保留原消息，避免丢失。
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private int getRetryCount(Message message) {
        Object retryCount = message.getMessageProperties().getHeaders().get(QUICK_LINK_STATS_RETRY_HEADER);
        return retryCount instanceof Number number ? number.intValue() : 0;
    }

    private void acknowledgeBatch(List<Message> messages, Channel channel) throws IOException {
        // 逐条确认，避免同批中的非法消息已 Nack 后又被 multiple=true 重复确认。
        for (Message message : messages) {
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
