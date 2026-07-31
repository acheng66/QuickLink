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

import cn.hutool.core.lang.UUID;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_EXCHANGE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_ROUTING_KEY;

/**
 * 短链接监控状态保存消息队列生产者（RabbitMQ 实现）
 * 可靠性保障：
 *   1. Publisher Confirms：Broker 接收到消息后回调确认
 *   2. Publisher Returns：路由失败时触发回调（在 RabbitMQConfiguration 中统一配置）
 *   3. 消息持久化：配合 Queue/Exchange 持久化，保证 Broker 重启后消息不丢失
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickLinkStatsSaveProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送短链接统计消息
     *
     * @param statsRecord 短链接统计实体参数（会自动填充唯一 messageId 用于幂等）
     */
    public void send(QuickLinkStatsRecordDTO statsRecord) {
        // 生成唯一 messageId，作为幂等 Key
        String messageId = UUID.fastUUID().toString();
        statsRecord.setKeys(messageId);

        // CorrelationData 绑定 messageId，用于 Publisher Confirm 回调追踪
        CorrelationData correlationData = new CorrelationData(messageId);
        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null) {
                log.error("[RabbitMQ] 短链接统计消息发送异常 messageId={}", messageId, throwable);
            } else if (confirm != null && !confirm.isAck()) {
                log.error("[RabbitMQ] 短链接统计消息未被 Broker 确认 messageId={} reason={}",
                        messageId, confirm.getReason());
            } else {
                log.debug("[RabbitMQ] 短链接统计消息已确认 messageId={}", messageId);
            }
        });

        rabbitTemplate.convertAndSend(
                QUICK_LINK_STATS_EXCHANGE,
                QUICK_LINK_STATS_ROUTING_KEY,
                statsRecord,
                correlationData
        );
    }
}
