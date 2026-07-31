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

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;

import java.util.concurrent.TimeUnit;

import static quicklink.project.common.constant.QuickLinkConstant.AMAP_REMOTE_URL;
import static quicklink.project.common.constant.RedisKeyConstant.QUICK_LINK_STATS_LOCALE_KEY;

/**
 * 在进入数据库事务和分布式读锁前完成 IP 地理位置解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickLinkStatsLocationService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${quick-link.stats.locale.amap-key}")
    private String statsLocaleAmapKey;

    @Value("${quick-link.stats.locale.timeout-millis:1500}")
    private Integer timeoutMillis;

    public void enrich(QuickLinkStatsRecordDTO record) {
        setUnknown(record);
        if (StrUtil.isBlank(record.getRemoteAddr())) {
            return;
        }
        String cacheKey = String.format(QUICK_LINK_STATS_LOCALE_KEY, record.getRemoteAddr());
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                applyLocale(record, JSON.parseObject(cached));
                return;
            }
            String responseBody = HttpRequest.get(AMAP_REMOTE_URL)
                    .form("key", statsLocaleAmapKey)
                    .form("ip", record.getRemoteAddr())
                    .timeout(timeoutMillis)
                    .execute()
                    .body();
            JSONObject response = JSON.parseObject(responseBody);
            if (!StrUtil.equals("10000", response.getString("infocode"))) {
                return;
            }
            JSONObject locale = new JSONObject();
            String province = normalize(response.getString("province"));
            locale.put("country", "中国");
            locale.put("province", province);
            locale.put("city", normalize(response.getString("city")));
            locale.put("adcode", normalize(response.getString("adcode")));
            applyLocale(record, locale);
            stringRedisTemplate.opsForValue().set(cacheKey, locale.toJSONString(), 1, TimeUnit.DAYS);
        } catch (Throwable ex) {
            // 地理位置属于辅助维度，解析失败不能阻断统计消费。
            log.warn("解析访问 IP 地理位置失败，ip={}", record.getRemoteAddr(), ex);
        }
    }

    private void applyLocale(QuickLinkStatsRecordDTO record, JSONObject locale) {
        record.setCountry(StrUtil.blankToDefault(locale.getString("country"), "未知"));
        record.setProvince(normalize(locale.getString("province")));
        record.setCity(normalize(locale.getString("city")));
        record.setAdcode(normalize(locale.getString("adcode")));
    }

    private void setUnknown(QuickLinkStatsRecordDTO record) {
        record.setCountry("未知");
        record.setProvince("未知");
        record.setCity("未知");
        record.setAdcode("未知");
    }

    private String normalize(String value) {
        return StrUtil.isBlank(value) || StrUtil.equals(value, "[]") ? "未知" : value;
    }
}
