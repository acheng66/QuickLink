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

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.Week;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import quicklink.project.dao.mapper.MqConsumeRecordMapper;
import quicklink.project.dao.mapper.QuickLinkGotoMapper;
import quicklink.project.dao.mapper.QuickLinkMapper;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static quicklink.project.common.constant.RedisKeyConstant.LOCK_GID_UPDATE_KEY;

/**
 * 统计消息事务化、幂等化和批量聚合持久层。
 */
@Service
@RequiredArgsConstructor
public class QuickLinkStatsPersistService {

    private final QuickLinkMapper quickLinkMapper;
    private final QuickLinkGotoMapper quickLinkGotoMapper;
    private final LinkAccessStatsMapper linkAccessStatsMapper;
    private final LinkLocaleStatsMapper linkLocaleStatsMapper;
    private final LinkOsStatsMapper linkOsStatsMapper;
    private final LinkBrowserStatsMapper linkBrowserStatsMapper;
    private final LinkAccessLogsMapper linkAccessLogsMapper;
    private final LinkDeviceStatsMapper linkDeviceStatsMapper;
    private final LinkNetworkStatsMapper linkNetworkStatsMapper;
    private final LinkStatsTodayMapper linkStatsTodayMapper;
    private final MqConsumeRecordMapper mqConsumeRecordMapper;
    private final RedissonClient redissonClient;

    /**
     * 消费记录、所有聚合表和访问明细在同一个本地事务中提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveBatch(List<QuickLinkStatsRecordDTO> sourceRecords) {
        List<QuickLinkStatsRecordDTO> records = sourceRecords.stream()
                .filter(each -> StrUtil.isNotBlank(each.getKeys()))
                .filter(each -> mqConsumeRecordMapper.insertIgnore(each.getKeys()) > 0)
                .toList();
        if (records.isEmpty()) {
            return;
        }

        List<RLock> readLocks = records.stream()
                .map(QuickLinkStatsRecordDTO::getFullShortUrl)
                .distinct()
                .sorted()
                .map(each -> redissonClient
                        .getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, each))
                        .readLock())
                .toList();
        readLocks.forEach(RLock::lock);
        try {
            actualSaveBatch(records);
            records.forEach(each -> mqConsumeRecordMapper.markCompleted(each.getKeys()));
        } finally {
            for (int i = readLocks.size() - 1; i >= 0; i--) {
                readLocks.get(i).unlock();
            }
        }
    }

    private void actualSaveBatch(List<QuickLinkStatsRecordDTO> records) {
        Set<String> fullShortUrls = records.stream()
                .map(QuickLinkStatsRecordDTO::getFullShortUrl)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, QuickLinkGotoDO> gotoMap = quickLinkGotoMapper.selectList(
                        Wrappers.lambdaQuery(QuickLinkGotoDO.class)
                                .in(QuickLinkGotoDO::getFullShortUrl, fullShortUrls))
                .stream()
                .collect(Collectors.toMap(QuickLinkGotoDO::getFullShortUrl, Function.identity()));
        if (gotoMap.size() != fullShortUrls.size()) {
            throw new ServiceException("短链接路由记录不存在，统计消息稍后重试");
        }

        Map<LinkKey, Counter> linkCounters = new HashMap<>();
        Map<AccessKey, Counter> accessCounters = new HashMap<>();
        Map<DimensionKey, Integer> osCounters = new HashMap<>();
        Map<DimensionKey, Integer> browserCounters = new HashMap<>();
        Map<DimensionKey, Integer> deviceCounters = new HashMap<>();
        Map<DimensionKey, Integer> networkCounters = new HashMap<>();
        Map<LocaleKey, Integer> localeCounters = new HashMap<>();
        Map<TodayKey, Counter> todayCounters = new HashMap<>();
        List<LinkAccessLogsDO> accessLogs = new ArrayList<>(records.size());

        for (QuickLinkStatsRecordDTO record : records) {
            String fullShortUrl = record.getFullShortUrl();
            String gid = gotoMap.get(fullShortUrl).getGid();
            Date eventTime = record.getCurrentDate() == null ? new Date() : record.getCurrentDate();
            Date eventDate = DateUtil.beginOfDay(eventTime).toJdkDate();
            int hour = DateUtil.hour(eventTime, true);
            Week week = DateUtil.dayOfWeekEnum(eventTime);
            int uv = Boolean.TRUE.equals(record.getUvFirstFlag()) ? 1 : 0;
            int uip = Boolean.TRUE.equals(record.getUipFirstFlag()) ? 1 : 0;

            linkCounters.computeIfAbsent(new LinkKey(fullShortUrl, gid), ignored -> new Counter())
                    .add(1, uv, uip);
            accessCounters.computeIfAbsent(
                            new AccessKey(fullShortUrl, eventDate.getTime(), hour, week.getIso8601Value()),
                            ignored -> new Counter())
                    .add(1, uv, uip);
            todayCounters.computeIfAbsent(
                            new TodayKey(fullShortUrl, eventDate.getTime()),
                            ignored -> new Counter())
                    .add(1, uv, uip);
            merge(osCounters, new DimensionKey(fullShortUrl, eventDate.getTime(), record.getOs()));
            merge(browserCounters, new DimensionKey(fullShortUrl, eventDate.getTime(), record.getBrowser()));
            merge(deviceCounters, new DimensionKey(fullShortUrl, eventDate.getTime(), record.getDevice()));
            merge(networkCounters, new DimensionKey(fullShortUrl, eventDate.getTime(), record.getNetwork()));
            merge(localeCounters, new LocaleKey(
                    fullShortUrl,
                    eventDate.getTime(),
                    record.getCountry(),
                    record.getProvince(),
                    record.getCity(),
                    record.getAdcode()));

            LinkAccessLogsDO accessLog = LinkAccessLogsDO.builder()
                    .user(record.getUv())
                    .ip(record.getRemoteAddr())
                    .browser(record.getBrowser())
                    .os(record.getOs())
                    .network(record.getNetwork())
                    .device(record.getDevice())
                    .locale(StrUtil.join("-", record.getCountry(), record.getProvince(), record.getCity()))
                    .fullShortUrl(fullShortUrl)
                    .build();
            accessLog.setCreateTime(eventTime);
            accessLog.setUpdateTime(eventTime);
            accessLog.setDelFlag(0);
            accessLogs.add(accessLog);
        }

        accessCounters.forEach((key, counter) -> linkAccessStatsMapper.quickLinkStats(
                LinkAccessStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .pv(counter.pv)
                        .uv(counter.uv)
                        .uip(counter.uip)
                        .hour(key.hour())
                        .weekday(key.weekday())
                        .build()));
        localeCounters.forEach((key, count) -> linkLocaleStatsMapper.quickLinkLocaleState(
                LinkLocaleStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .country(key.country())
                        .province(key.province())
                        .city(key.city())
                        .adcode(key.adcode())
                        .cnt(count)
                        .build()));
        osCounters.forEach((key, count) -> linkOsStatsMapper.quickLinkOsState(
                LinkOsStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .os(key.dimension())
                        .cnt(count)
                        .build()));
        browserCounters.forEach((key, count) -> linkBrowserStatsMapper.quickLinkBrowserState(
                LinkBrowserStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .browser(key.dimension())
                        .cnt(count)
                        .build()));
        deviceCounters.forEach((key, count) -> linkDeviceStatsMapper.quickLinkDeviceState(
                LinkDeviceStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .device(key.dimension())
                        .cnt(count)
                        .build()));
        networkCounters.forEach((key, count) -> linkNetworkStatsMapper.quickLinkNetworkState(
                LinkNetworkStatsDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .network(key.dimension())
                        .cnt(count)
                        .build()));
        linkCounters.forEach((key, counter) -> quickLinkMapper.incrementStats(
                key.gid(), key.fullShortUrl(), counter.pv, counter.uv, counter.uip));
        todayCounters.forEach((key, counter) -> linkStatsTodayMapper.quickLinkTodayState(
                LinkStatsTodayDO.builder()
                        .fullShortUrl(key.fullShortUrl())
                        .date(new Date(key.date()))
                        .todayPv(counter.pv)
                        .todayUv(counter.uv)
                        .todayUip(counter.uip)
                        .build()));
        if (!accessLogs.isEmpty()) {
            linkAccessLogsMapper.insertBatch(accessLogs);
        }
    }

    private <T> void merge(Map<T, Integer> counters, T key) {
        counters.merge(key, 1, Integer::sum);
    }

    private record LinkKey(String fullShortUrl, String gid) {
    }

    private record AccessKey(String fullShortUrl, long date, int hour, int weekday) {
    }

    private record DimensionKey(String fullShortUrl, long date, String dimension) {
    }

    private record LocaleKey(
            String fullShortUrl,
            long date,
            String country,
            String province,
            String city,
            String adcode) {
    }

    private record TodayKey(String fullShortUrl, long date) {
    }

    private static final class Counter {

        private int pv;
        private int uv;
        private int uip;

        private void add(int pv, int uv, int uip) {
            this.pv += pv;
            this.uv += uv;
            this.uip += uip;
        }
    }
}
