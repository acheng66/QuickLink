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
 * RabbitMQ 常量类
 */
public class RabbitMQConstant {

    /**
     * 短链接统计业务 Exchange
     */
    public static final String QUICK_LINK_STATS_EXCHANGE = "quick-link.stats.exchange";

    /**
     * 短链接统计业务队列
     */
    public static final String QUICK_LINK_STATS_QUEUE = "quick-link.stats.queue";

    /**
     * 短链接统计业务路由键
     */
    public static final String QUICK_LINK_STATS_ROUTING_KEY = "quick-link.stats.routing-key";

    /**
     * 死信 Exchange
     */
    public static final String QUICK_LINK_STATS_DLX_EXCHANGE = "quick-link.stats.dlx.exchange";

    /**
     * 死信队列
     */
    public static final String QUICK_LINK_STATS_DLX_QUEUE = "quick-link.stats.dlx.queue";

    /**
     * 死信路由键
     */
    public static final String QUICK_LINK_STATS_DLX_ROUTING_KEY = "quick-link.stats.dlx.routing-key";
}
