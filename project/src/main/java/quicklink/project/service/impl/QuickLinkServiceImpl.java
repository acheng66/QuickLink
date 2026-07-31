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

package quicklink.project.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.text.StrBuilder;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import quicklink.project.common.convention.exception.ClientException;
import quicklink.project.common.convention.exception.ServiceException;
import quicklink.project.common.enums.VailDateTypeEnum;
import quicklink.project.config.GotoDomainWhiteListConfiguration;
import quicklink.project.dao.entity.QuickLinkDO;
import quicklink.project.dao.entity.QuickLinkGotoDO;
import quicklink.project.dao.mapper.QuickLinkGotoMapper;
import quicklink.project.dao.mapper.QuickLinkMapper;
import quicklink.project.dto.biz.QuickLinkStatsRecordDTO;
import quicklink.project.dto.req.QuickLinkBatchCreateReqDTO;
import quicklink.project.dto.req.QuickLinkCreateReqDTO;
import quicklink.project.dto.req.QuickLinkPageReqDTO;
import quicklink.project.dto.req.QuickLinkUpdateReqDTO;
import quicklink.project.dto.resp.*;
import quicklink.project.mq.producer.QuickLinkStatsSaveProducer;
import quicklink.project.service.QuickLinkService;
import quicklink.project.toolkit.HashUtil;
import quicklink.project.toolkit.LinkUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static quicklink.project.common.constant.RedisKeyConstant.*;

/**
 * 短链接接口实现层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickLinkServiceImpl extends ServiceImpl<QuickLinkMapper, QuickLinkDO> implements QuickLinkService {

    private final RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;
    private final QuickLinkGotoMapper quickLinkGotoMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final QuickLinkStatsSaveProducer quickLinkStatsSaveProducer;
    private final GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;

    @Value("${quick-link.domain.default}")
    private String createQuickLinkDefaultDomain;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public QuickLinkCreateRespDTO createQuickLink(QuickLinkCreateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        String quickLinkSuffix = generateSuffix(requestParam);
        String fullShortUrl = StrBuilder.create(createQuickLinkDefaultDomain)
                .append("/")
                .append(quickLinkSuffix)
                .toString();
        QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                .domain(createQuickLinkDefaultDomain)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .createdType(requestParam.getCreatedType())
                .validDateType(requestParam.getValidDateType())
                .validDate(requestParam.getValidDate())
                .describe(requestParam.getDescribe())
                .shortUri(quickLinkSuffix)
                .enableStatus(0)
                .totalPv(0)
                .totalUv(0)
                .totalUip(0)
                .delTime(0L)
                .fullShortUrl(fullShortUrl)
                .favicon(getFavicon(requestParam.getOriginUrl()))
                .build();
        QuickLinkGotoDO linkGotoDO = QuickLinkGotoDO.builder()
                .fullShortUrl(fullShortUrl)
                .gid(requestParam.getGid())
                .build();
        try {
            baseMapper.insert(quickLinkDO);
            quickLinkGotoMapper.insert(linkGotoDO);
        } catch (DuplicateKeyException ex) {
            // 首先判断是否存在布隆过滤器，如果不存在直接新增
            if (!shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl)) {
                shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
            }
            throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
        }
        stringRedisTemplate.opsForValue().set(
                String.format(GOTO_QUICK_LINK_KEY, fullShortUrl),
                requestParam.getOriginUrl(),
                LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), TimeUnit.MILLISECONDS
        );
        shortUriCreateCachePenetrationBloomFilter.add(fullShortUrl);
        return QuickLinkCreateRespDTO.builder()
                .fullShortUrl("http://" + quickLinkDO.getFullShortUrl())
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .build();
    }

    @Override
    public QuickLinkCreateRespDTO createQuickLinkByLock(QuickLinkCreateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        String fullShortUrl;
        RLock lock = redissonClient.getLock(QUICK_LINK_CREATE_LOCK_KEY);
        lock.lock();
        try {
            String quickLinkSuffix = generateSuffixByLock(requestParam);
            fullShortUrl = StrBuilder.create(createQuickLinkDefaultDomain)
                    .append("/")
                    .append(quickLinkSuffix)
                    .toString();
            QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                    .domain(createQuickLinkDefaultDomain)
                    .originUrl(requestParam.getOriginUrl())
                    .gid(requestParam.getGid())
                    .createdType(requestParam.getCreatedType())
                    .validDateType(requestParam.getValidDateType())
                    .validDate(requestParam.getValidDate())
                    .describe(requestParam.getDescribe())
                    .shortUri(quickLinkSuffix)
                    .enableStatus(0)
                    .totalPv(0)
                    .totalUv(0)
                    .totalUip(0)
                    .delTime(0L)
                    .fullShortUrl(fullShortUrl)
                    .favicon(getFavicon(requestParam.getOriginUrl()))
                    .build();
            QuickLinkGotoDO linkGotoDO = QuickLinkGotoDO.builder()
                    .fullShortUrl(fullShortUrl)
                    .gid(requestParam.getGid())
                    .build();
            try {
                baseMapper.insert(quickLinkDO);
                quickLinkGotoMapper.insert(linkGotoDO);
            } catch (DuplicateKeyException ex) {
                throw new ServiceException(String.format("短链接：%s 生成重复", fullShortUrl));
            }
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_QUICK_LINK_KEY, fullShortUrl),
                    requestParam.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()), TimeUnit.MILLISECONDS
            );
        } finally {
            lock.unlock();
        }
        return QuickLinkCreateRespDTO.builder()
                .fullShortUrl("http://" + fullShortUrl)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .build();
    }

    @Override
    public QuickLinkBatchCreateRespDTO batchCreateQuickLink(QuickLinkBatchCreateReqDTO requestParam) {
        List<String> originUrls = requestParam.getOriginUrls();
        List<String> describes = requestParam.getDescribes();
        List<QuickLinkBaseInfoRespDTO> result = new ArrayList<>();
        for (int i = 0; i < originUrls.size(); i++) {
            QuickLinkCreateReqDTO quickLinkCreateReqDTO = BeanUtil.toBean(requestParam, QuickLinkCreateReqDTO.class);
            quickLinkCreateReqDTO.setOriginUrl(originUrls.get(i));
            quickLinkCreateReqDTO.setDescribe(describes.get(i));
            try {
                QuickLinkCreateRespDTO quickLink = createQuickLink(quickLinkCreateReqDTO);
                QuickLinkBaseInfoRespDTO linkBaseInfoRespDTO = QuickLinkBaseInfoRespDTO.builder()
                        .fullShortUrl(quickLink.getFullShortUrl())
                        .originUrl(quickLink.getOriginUrl())
                        .describe(describes.get(i))
                        .build();
                result.add(linkBaseInfoRespDTO);
            } catch (Throwable ex) {
                log.error("批量创建短链接失败，原始参数：{}", originUrls.get(i));
            }
        }
        return QuickLinkBatchCreateRespDTO.builder()
                .total(result.size())
                .baseLinkInfos(result)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateQuickLink(QuickLinkUpdateReqDTO requestParam) {
        verificationWhitelist(requestParam.getOriginUrl());
        LambdaQueryWrapper<QuickLinkDO> queryWrapper = Wrappers.lambdaQuery(QuickLinkDO.class)
                .eq(QuickLinkDO::getGid, requestParam.getOriginGid())
                .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(QuickLinkDO::getDelFlag, 0)
                .eq(QuickLinkDO::getEnableStatus, 0);
        QuickLinkDO hasQuickLinkDO = baseMapper.selectOne(queryWrapper);
        if (hasQuickLinkDO == null) {
            throw new ClientException("短链接记录不存在");
        }
        if (Objects.equals(hasQuickLinkDO.getGid(), requestParam.getGid())) {
            LambdaUpdateWrapper<QuickLinkDO> updateWrapper = Wrappers.lambdaUpdate(QuickLinkDO.class)
                    .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                    .eq(QuickLinkDO::getGid, requestParam.getGid())
                    .eq(QuickLinkDO::getDelFlag, 0)
                    .eq(QuickLinkDO::getEnableStatus, 0)
                    .set(Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()), QuickLinkDO::getValidDate, null);
            QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                    .domain(hasQuickLinkDO.getDomain())
                    .shortUri(hasQuickLinkDO.getShortUri())
                    .favicon(Objects.equals(requestParam.getOriginUrl(), hasQuickLinkDO.getOriginUrl()) ? hasQuickLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                    .createdType(hasQuickLinkDO.getCreatedType())
                    .gid(requestParam.getGid())
                    .originUrl(requestParam.getOriginUrl())
                    .describe(requestParam.getDescribe())
                    .validDateType(requestParam.getValidDateType())
                    .validDate(requestParam.getValidDate())
                    .build();
            baseMapper.update(quickLinkDO, updateWrapper);
        } else {
            RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY, requestParam.getFullShortUrl()));
            RLock rLock = readWriteLock.writeLock();
            rLock.lock();
            try {
                LambdaUpdateWrapper<QuickLinkDO> linkUpdateWrapper = Wrappers.lambdaUpdate(QuickLinkDO.class)
                        .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(QuickLinkDO::getGid, hasQuickLinkDO.getGid())
                        .eq(QuickLinkDO::getDelFlag, 0)
                        .eq(QuickLinkDO::getDelTime, 0L)
                        .eq(QuickLinkDO::getEnableStatus, 0);
                QuickLinkDO delQuickLinkDO = QuickLinkDO.builder()
                        .delTime(System.currentTimeMillis())
                        .build();
                delQuickLinkDO.setDelFlag(1);
                baseMapper.update(delQuickLinkDO, linkUpdateWrapper);
                QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                        .domain(createQuickLinkDefaultDomain)
                        .originUrl(requestParam.getOriginUrl())
                        .gid(requestParam.getGid())
                        .createdType(hasQuickLinkDO.getCreatedType())
                        .validDateType(requestParam.getValidDateType())
                        .validDate(requestParam.getValidDate())
                        .describe(requestParam.getDescribe())
                        .shortUri(hasQuickLinkDO.getShortUri())
                        .enableStatus(hasQuickLinkDO.getEnableStatus())
                        .totalPv(hasQuickLinkDO.getTotalPv())
                        .totalUv(hasQuickLinkDO.getTotalUv())
                        .totalUip(hasQuickLinkDO.getTotalUip())
                        .fullShortUrl(hasQuickLinkDO.getFullShortUrl())
                        .favicon(Objects.equals(requestParam.getOriginUrl(), hasQuickLinkDO.getOriginUrl()) ? hasQuickLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                        .delTime(0L)
                        .build();
                baseMapper.insert(quickLinkDO);
                LambdaQueryWrapper<QuickLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(QuickLinkGotoDO.class)
                        .eq(QuickLinkGotoDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(QuickLinkGotoDO::getGid, hasQuickLinkDO.getGid());
                QuickLinkGotoDO quickLinkGotoDO = quickLinkGotoMapper.selectOne(linkGotoQueryWrapper);
                quickLinkGotoMapper.delete(linkGotoQueryWrapper);
                quickLinkGotoDO.setGid(requestParam.getGid());
                quickLinkGotoMapper.insert(quickLinkGotoDO);
            } finally {
                rLock.unlock();
            }
        }
        if (!Objects.equals(hasQuickLinkDO.getValidDateType(), requestParam.getValidDateType())
                || !Objects.equals(hasQuickLinkDO.getValidDate(), requestParam.getValidDate())
                || !Objects.equals(hasQuickLinkDO.getOriginUrl(), requestParam.getOriginUrl())) {
            stringRedisTemplate.delete(String.format(GOTO_QUICK_LINK_KEY, requestParam.getFullShortUrl()));
            Date currentDate = new Date();
            if (hasQuickLinkDO.getValidDate() != null && hasQuickLinkDO.getValidDate().before(currentDate)) {
                if (Objects.equals(requestParam.getValidDateType(), VailDateTypeEnum.PERMANENT.getType()) || requestParam.getValidDate().after(currentDate)) {
                    stringRedisTemplate.delete(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, requestParam.getFullShortUrl()));
                }
            }
        }
    }

    @Override
    public IPage<QuickLinkPageRespDTO> pageQuickLink(QuickLinkPageReqDTO requestParam) {
        IPage<QuickLinkDO> resultPage = baseMapper.pageLink(requestParam);
        return resultPage.convert(each -> {
            QuickLinkPageRespDTO result = BeanUtil.toBean(each, QuickLinkPageRespDTO.class);
            result.setDomain("http://" + result.getDomain());
            return result;
        });
    }

    @Override
    public List<QuickLinkGroupCountQueryRespDTO> listGroupQuickLinkCount(List<String> requestParam) {
        QueryWrapper<QuickLinkDO> queryWrapper = Wrappers.query(new QuickLinkDO())
                .select("gid as gid, count(*) as quickLinkCount")
                .in("gid", requestParam)
                .eq("enable_status", 0)
                .eq("del_flag", 0)
                .eq("del_time", 0L)
                .groupBy("gid");
        List<Map<String, Object>> quickLinkDOList = baseMapper.selectMaps(queryWrapper);
        return BeanUtil.copyToList(quickLinkDOList, QuickLinkGroupCountQueryRespDTO.class);
    }

    @SneakyThrows
    @Override
    public void restoreUrl(String shortUri, ServletRequest request, ServletResponse response) {
        String serverName = request.getServerName();
        String serverPort = Optional.of(request.getServerPort())
                .filter(each -> !Objects.equals(each, 80))
                .map(String::valueOf)
                .map(each -> ":" + each)
                .orElse("");
        String fullShortUrl = serverName + serverPort + "/" + shortUri;
        String originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_QUICK_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(originalLink)) {
            quickLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
            ((HttpServletResponse) response).sendRedirect(originalLink);
            return;
        }
        boolean contains = shortUriCreateCachePenetrationBloomFilter.contains(fullShortUrl);
        if (!contains) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        String gotoIsNullQuickLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(gotoIsNullQuickLink)) {
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        RLock lock = redissonClient.getLock(String.format(LOCK_GOTO_QUICK_LINK_KEY, fullShortUrl));
        lock.lock();
        try {
            originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_QUICK_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(originalLink)) {
                quickLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
                ((HttpServletResponse) response).sendRedirect(originalLink);
                return;
            }
            gotoIsNullQuickLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(gotoIsNullQuickLink)) {
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<QuickLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(QuickLinkGotoDO.class)
                    .eq(QuickLinkGotoDO::getFullShortUrl, fullShortUrl);
            QuickLinkGotoDO quickLinkGotoDO = quickLinkGotoMapper.selectOne(linkGotoQueryWrapper);
            if (quickLinkGotoDO == null) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<QuickLinkDO> queryWrapper = Wrappers.lambdaQuery(QuickLinkDO.class)
                    .eq(QuickLinkDO::getGid, quickLinkGotoDO.getGid())
                    .eq(QuickLinkDO::getFullShortUrl, fullShortUrl)
                    .eq(QuickLinkDO::getDelFlag, 0)
                    .eq(QuickLinkDO::getEnableStatus, 0);
            QuickLinkDO quickLinkDO = baseMapper.selectOne(queryWrapper);
            if (quickLinkDO == null || (quickLinkDO.getValidDate() != null && quickLinkDO.getValidDate().before(new Date()))) {
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_QUICK_LINK_KEY, fullShortUrl),
                    quickLinkDO.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(quickLinkDO.getValidDate()), TimeUnit.MILLISECONDS
            );
            quickLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
            ((HttpServletResponse) response).sendRedirect(quickLinkDO.getOriginUrl());
        } finally {
            lock.unlock();
        }
    }

    private QuickLinkStatsRecordDTO buildLinkStatsRecordAndSetUser(String fullShortUrl, ServletRequest request, ServletResponse response) {
        AtomicBoolean uvFirstFlag = new AtomicBoolean();
        Cookie[] cookies = ((HttpServletRequest) request).getCookies();
        AtomicReference<String> uv = new AtomicReference<>();
        Runnable addResponseCookieTask = () -> {
            uv.set(UUID.fastUUID().toString());
            Cookie uvCookie = new Cookie("uv", uv.get());
            uvCookie.setMaxAge(60 * 60 * 24 * 30);
            uvCookie.setPath(StrUtil.sub(fullShortUrl, fullShortUrl.indexOf("/"), fullShortUrl.length()));
            ((HttpServletResponse) response).addCookie(uvCookie);
            uvFirstFlag.set(Boolean.TRUE);
            stringRedisTemplate.opsForSet().add(QUICK_LINK_STATS_UV_KEY + fullShortUrl, uv.get());
        };
        if (ArrayUtil.isNotEmpty(cookies)) {
            Arrays.stream(cookies)
                    .filter(each -> Objects.equals(each.getName(), "uv"))
                    .findFirst()
                    .map(Cookie::getValue)
                    .ifPresentOrElse(each -> {
                        uv.set(each);
                        Long uvAdded = stringRedisTemplate.opsForSet().add(QUICK_LINK_STATS_UV_KEY + fullShortUrl, each);
                        uvFirstFlag.set(uvAdded != null && uvAdded > 0L);
                    }, addResponseCookieTask);
        } else {
            addResponseCookieTask.run();
        }
        String remoteAddr = LinkUtil.getActualIp(((HttpServletRequest) request));
        String os = LinkUtil.getOs(((HttpServletRequest) request));
        String browser = LinkUtil.getBrowser(((HttpServletRequest) request));
        String device = LinkUtil.getDevice(((HttpServletRequest) request));
        String network = LinkUtil.getNetwork(((HttpServletRequest) request));
        Long uipAdded = stringRedisTemplate.opsForSet().add(QUICK_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);
        boolean uipFirstFlag = uipAdded != null && uipAdded > 0L;
        return QuickLinkStatsRecordDTO.builder()
                .fullShortUrl(fullShortUrl)
                .uv(uv.get())
                .uvFirstFlag(uvFirstFlag.get())
                .uipFirstFlag(uipFirstFlag)
                .remoteAddr(remoteAddr)
                .os(os)
                .browser(browser)
                .device(device)
                .network(network)
                .currentDate(new Date())
                .build();
    }

    @Override
    public void quickLinkStats(QuickLinkStatsRecordDTO statsRecord) {
        try {
            // 访问统计属于旁路能力，RabbitMQ 或补偿缓存异常不能阻断核心 302 跳转。
            quickLinkStatsSaveProducer.send(statsRecord);
        } catch (Throwable ex) {
            log.error("发送短链接统计消息失败，不影响跳转，fullShortUrl={}",
                    statsRecord.getFullShortUrl(), ex);
        }
    }

    private String generateSuffix(QuickLinkCreateReqDTO requestParam) {
        int customGenerateCount = 0;
        String shorUri;
        while (true) {
            if (customGenerateCount > 10) {
                throw new ServiceException("短链接频繁生成，请稍后再试");
            }
            String originUrl = requestParam.getOriginUrl();
            originUrl += UUID.randomUUID().toString();
            shorUri = HashUtil.hashToBase62(originUrl);
            if (!shortUriCreateCachePenetrationBloomFilter.contains(createQuickLinkDefaultDomain + "/" + shorUri)) {
                break;
            }
            customGenerateCount++;
        }
        return shorUri;
    }

    private String generateSuffixByLock(QuickLinkCreateReqDTO requestParam) {
        int customGenerateCount = 0;
        String shorUri;
        while (true) {
            if (customGenerateCount > 10) {
                throw new ServiceException("短链接频繁生成，请稍后再试");
            }
            String originUrl = requestParam.getOriginUrl();
            originUrl += UUID.randomUUID().toString();
            shorUri = HashUtil.hashToBase62(originUrl);
            LambdaQueryWrapper<QuickLinkDO> queryWrapper = Wrappers.lambdaQuery(QuickLinkDO.class)
                    .eq(QuickLinkDO::getGid, requestParam.getGid())
                    .eq(QuickLinkDO::getFullShortUrl, createQuickLinkDefaultDomain + "/" + shorUri)
                    .eq(QuickLinkDO::getDelFlag, 0);
            QuickLinkDO quickLinkDO = baseMapper.selectOne(queryWrapper);
            if (quickLinkDO == null) {
                break;
            }
            customGenerateCount++;
        }
        return shorUri;
    }

    @SneakyThrows
    private String getFavicon(String url) {
        URL targetUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (HttpURLConnection.HTTP_OK == responseCode) {
            Document document = Jsoup.connect(url).get();
            Element faviconLink = document.select("link[rel~=(?i)^(shortcut )?icon]").first();
            if (faviconLink != null) {
                return faviconLink.attr("abs:href");
            }
        }
        return null;
    }

    private void verificationWhitelist(String originUrl) {
        Boolean enable = gotoDomainWhiteListConfiguration.getEnable();
        if (enable == null || !enable) {
            return;
        }
        String domain = LinkUtil.extractDomain(originUrl);
        if (StrUtil.isBlank(domain)) {
            throw new ClientException("跳转链接填写错误");
        }
        List<String> details = gotoDomainWhiteListConfiguration.getDetails();
        if (!details.contains(domain)) {
            throw new ClientException("演示环境为避免恶意攻击，请生成以下网站跳转链接：" + gotoDomainWhiteListConfiguration.getNames());
        }
    }
}
