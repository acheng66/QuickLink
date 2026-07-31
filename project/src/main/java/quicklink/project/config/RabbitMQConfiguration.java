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

package quicklink.project.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_DLX_EXCHANGE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_DLX_QUEUE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_DLX_ROUTING_KEY;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_EXCHANGE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_QUEUE;
import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_ROUTING_KEY;

/**
 * RabbitMQ 配置
 * 声明 Exchange、Queue、Binding 及死信队列（DLX），并配置消息转换器和发送确认
 */
@Configuration
public class RabbitMQConfiguration {

    // ======================== 消息转换器 ========================

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ======================== RabbitTemplate ========================

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // 开启 mandatory，配合 publisher-returns 使用：路由失败时回调
        template.setMandatory(true);
        // Publisher Returns 回调：消息路由到队列失败时记录日志
        template.setReturnsCallback(returned -> {
            org.slf4j.LoggerFactory.getLogger(RabbitMQConfiguration.class)
                    .error("[RabbitMQ] 消息路由失败 exchange={} routingKey={} replyCode={} replyText={}",
                            returned.getExchange(), returned.getRoutingKey(),
                            returned.getReplyCode(), returned.getReplyText());
        });
        return template;
    }

    // ======================== 消费者容器工厂 ========================

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // 手动 ACK
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        // 每次拉取的消息数，防止消费者过载
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }

    // ======================== 死信 Exchange & Queue ========================

    @Bean
    public DirectExchange quickLinkStatsDlxExchange() {
        return new DirectExchange(QUICK_LINK_STATS_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue quickLinkStatsDlxQueue() {
        return QueueBuilder.durable(QUICK_LINK_STATS_DLX_QUEUE).build();
    }

    @Bean
    public Binding quickLinkStatsDlxBinding() {
        return BindingBuilder.bind(quickLinkStatsDlxQueue())
                .to(quickLinkStatsDlxExchange())
                .with(QUICK_LINK_STATS_DLX_ROUTING_KEY);
    }

    // ======================== 业务 Exchange & Queue ========================

    @Bean
    public DirectExchange quickLinkStatsExchange() {
        return new DirectExchange(QUICK_LINK_STATS_EXCHANGE, true, false);
    }

    @Bean
    public Queue quickLinkStatsQueue() {
        // 绑定死信 Exchange，消息超过重试次数后投递到死信队列
        return QueueBuilder.durable(QUICK_LINK_STATS_QUEUE)
                .withArgument("x-dead-letter-exchange", QUICK_LINK_STATS_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUICK_LINK_STATS_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding quickLinkStatsBinding() {
        return BindingBuilder.bind(quickLinkStatsQueue())
                .to(quickLinkStatsExchange())
                .with(QUICK_LINK_STATS_ROUTING_KEY);
    }
}
