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

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.Week;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import quicklink.project.common.convention.exception.ServiceException;
import quicklink.project.dao.entity.LinkAccessLogsDO;
import quicklink.project.dao.entity.LinkAccessStatsDO;
import quicklink.project.dao.entity.LinkBrowserStatsDO;
import quicklink.project.dao.entity.LinkDeviceStatsDO;
import quicklink.project.dao.entity.LinkLocaleStatsDO;
import quicklink.project.dao.entity.LinkNetworkStatsDO;
import quicklink.project.dao.entity.LinkOsStatsDO;
import quicklink.project.dao.entity.LinkStatsTodayDO;
import quicklink.project.dao.entity.QuickLinkGotoDO;
import quicklink.project.dao.mapper.LinkAccessLogsMapper;
import quicklink.project.dao.mapper.LinkAccessStatsMapper;
import quicklink.project.dao.mapper.LinkBrowserStatsMapper;
import quicklink.project.dao.mapper.LinkDeviceStatsMapper;
import quicklink.project.dao.mapper.LinkLocaleStatsMapper;
import quicklink.project.dao.mapper.LinkNetworkStatsMapper;
import quicklink.project.dao.mapper.LinkOsStatsMapper;
import quicklink.project.dao.mapper.LinkStatsTodayMapper;
import quicklink.project.dao.mapper.QuickLinkGotoMapper;
import quicklink.project.dao.mapper.QuickLinkMapper;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;
import quicklink.project.mq.idempotent.MessageQueueIdempotentHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static quicklink.project.common.constant.RabbitMQConstant.QUICK_LINK_STATS_QUEUE;
import static quicklink.project.common.constant.RedisKeyConstant.LOCK_GID_UPDATE_KEY;
import static quicklink.project.common.constant.QuickLinkConstant.AMAP_REMOTE_URL;

/**
 * 短链接监控状态保存消息队列消费者（RabbitMQ 实现）
 * 可靠性保障：
 *   1. 手动 ACK：消费成功后才 ack，异常时 nack 重回队列
 *   2. 幂等性：基于 Redis 记录 messageId 状态，防止重复消费
 *   3. 死信队列：消息多次 nack 后投递到 DLX，避免无限重试
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickLinkStatsSaveConsumer {

    private final QuickLinkMapper quickLinkMapper;
    private final QuickLinkGotoMapper quickLinkGotoMapper;
    private final RedissonClient redissonClient;
    private final LinkAccessStatsMapper linkAccessStatsMapper;
    private final LinkLocaleStatsMapper linkLocaleStatsMapper;
    private final LinkOsStatsMapper linkOsStatsMapper;
    private final LinkBrowserStatsMapper linkBrowserStatsMapper;
    private final LinkAccessLogsMapper linkAccessLogsMapper;
    private final LinkDeviceStatsMapper linkDeviceStatsMapper;
    private final LinkNetworkStatsMapper linkNetworkStatsMapper;
    private final LinkStatsTodayMapper linkStatsTodayMapper;
    private final MessageQueueIdempotentHandler messageQueueIdempotentHandler;

    @Value("${quick-link.stats.locale.amap-key}")
    private String statsLocaleAmapKey;

    /**
     * 消费短链接统计消息
     * containerFactory 与 RabbitMQConfiguration 中声明的 Bean 名称一致
     */
    @RabbitListener(queues = QUICK_LINK_STATS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(QuickLinkStatsRecordDTO statsRecord, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = statsRecord.getKeys();

        // ===== 幂等校验 =====
        if (messageQueueIdempotentHandler.isMessageBeingConsumed(messageId)) {
            // Key 已存在：要么正在处理，要么已完成
            if (messageQueueIdempotentHandler.isAccomplish(messageId)) {
                // 已消费完成，直接 ack 丢弃重复消息
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 未完成（可能上次处理到一半宕机），nack 让消息重回队列重试
            channel.basicNack(deliveryTag, false, true);
            throw new ServiceException("消息未完成流程，需要消息队列重试");
        }

        // ===== 正常消费 =====
        try {
            actualSaveQuickLinkStats(statsRecord);
        } catch (Throwable ex) {
            // 删除幂等 Key，允许消息重试
            messageQueueIdempotentHandler.delMessageProcessed(messageId);
            log.error("[RabbitMQ] 记录短链接监控消费异常 messageId={}", messageId, ex);
            // nack，requeue=true 重回队列；达到死信阈值后会进入 DLX
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        // 标记幂等完成
        messageQueueIdempotentHandler.setAccomplish(messageId);
        // 手动 ack
        channel.basicAck(deliveryTag, false);
    }

    public void actualSaveQuickLinkStats(QuickLinkStatsRecordDTO statsRecord) {
        String fullShortUrl = statsRecord.getFullShortUrl();
        RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, fullShortUrl));
        RLock rLock = readWriteLock.readLock();
        rLock.lock();
        try {
            LambdaQueryWrapper<QuickLinkGotoDO> queryWrapper = Wrappers.lambdaQuery(QuickLinkGotoDO.class)
                    .eq(QuickLinkGotoDO::getFullShortUrl, fullShortUrl);
            QuickLinkGotoDO quickLinkGotoDO = quickLinkGotoMapper.selectOne(queryWrapper);
            String gid = quickLinkGotoDO.getGid();
            Date currentDate = statsRecord.getCurrentDate();
            int hour = DateUtil.hour(currentDate, true);
            Week week = DateUtil.dayOfWeekEnum(currentDate);
            int weekValue = week.getIso8601Value();
            LinkAccessStatsDO linkAccessStatsDO = LinkAccessStatsDO.builder()
                    .pv(1)
                    .uv(statsRecord.getUvFirstFlag() ? 1 : 0)
                    .uip(statsRecord.getUipFirstFlag() ? 1 : 0)
                    .hour(hour)
                    .weekday(weekValue)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkAccessStatsMapper.quickLinkStats(linkAccessStatsDO);
            Map<String, Object> localeParamMap = new HashMap<>();
            localeParamMap.put("key", statsLocaleAmapKey);
            localeParamMap.put("ip", statsRecord.getRemoteAddr());
            String localeResultStr = HttpUtil.get(AMAP_REMOTE_URL, localeParamMap);
            JSONObject localeResultObj = JSON.parseObject(localeResultStr);
            String infoCode = localeResultObj.getString("infocode");
            String actualProvince = "未知";
            String actualCity = "未知";
            if (StrUtil.isNotBlank(infoCode) && StrUtil.equals(infoCode, "10000")) {
                String province = localeResultObj.getString("province");
                boolean unknownFlag = StrUtil.equals(province, "[]");
                LinkLocaleStatsDO linkLocaleStatsDO = LinkLocaleStatsDO.builder()
                        .province(actualProvince = unknownFlag ? actualProvince : province)
                        .city(actualCity = unknownFlag ? actualCity : localeResultObj.getString("city"))
                        .adcode(unknownFlag ? "未知" : localeResultObj.getString("adcode"))
                        .cnt(1)
                        .fullShortUrl(fullShortUrl)
                        .country("中国")
                        .date(currentDate)
                        .build();
                linkLocaleStatsMapper.quickLinkLocaleState(linkLocaleStatsDO);
            }
            LinkOsStatsDO linkOsStatsDO = LinkOsStatsDO.builder()
                    .os(statsRecord.getOs())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkOsStatsMapper.quickLinkOsState(linkOsStatsDO);
            LinkBrowserStatsDO linkBrowserStatsDO = LinkBrowserStatsDO.builder()
                    .browser(statsRecord.getBrowser())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkBrowserStatsMapper.quickLinkBrowserState(linkBrowserStatsDO);
            LinkDeviceStatsDO linkDeviceStatsDO = LinkDeviceStatsDO.builder()
                    .device(statsRecord.getDevice())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkDeviceStatsMapper.quickLinkDeviceState(linkDeviceStatsDO);
            LinkNetworkStatsDO linkNetworkStatsDO = LinkNetworkStatsDO.builder()
                    .network(statsRecord.getNetwork())
                    .cnt(1)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkNetworkStatsMapper.quickLinkNetworkState(linkNetworkStatsDO);
            LinkAccessLogsDO linkAccessLogsDO = LinkAccessLogsDO.builder()
                    .user(statsRecord.getUv())
                    .ip(statsRecord.getRemoteAddr())
                    .browser(statsRecord.getBrowser())
                    .os(statsRecord.getOs())
                    .network(statsRecord.getNetwork())
                    .device(statsRecord.getDevice())
                    .locale(StrUtil.join("-", "中国", actualProvince, actualCity))
                    .fullShortUrl(fullShortUrl)
                    .build();
            linkAccessLogsMapper.insert(linkAccessLogsDO);
            quickLinkMapper.incrementStats(gid, fullShortUrl, 1,
                    statsRecord.getUvFirstFlag() ? 1 : 0,
                    statsRecord.getUipFirstFlag() ? 1 : 0);
            LinkStatsTodayDO linkStatsTodayDO = LinkStatsTodayDO.builder()
                    .todayPv(1)
                    .todayUv(statsRecord.getUvFirstFlag() ? 1 : 0)
                    .todayUip(statsRecord.getUipFirstFlag() ? 1 : 0)
                    .fullShortUrl(fullShortUrl)
                    .date(currentDate)
                    .build();
            linkStatsTodayMapper.quickLinkTodayState(linkStatsTodayDO);
        } finally {
            rLock.unlock();
        }
    }
}
