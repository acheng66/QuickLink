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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import quicklink.project.dao.entity.QuickLinkDO;
import quicklink.project.dao.mapper.QuickLinkMapper;
import quicklink.project.dto.req.RecycleBinRecoverReqDTO;
import quicklink.project.dto.req.RecycleBinRemoveReqDTO;
import quicklink.project.dto.req.RecycleBinSaveReqDTO;
import quicklink.project.dto.req.QuickLinkRecycleBinPageReqDTO;
import quicklink.project.dto.resp.QuickLinkPageRespDTO;
import quicklink.project.service.RecycleBinService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static quicklink.project.common.constant.RedisKeyConstant.GOTO_IS_NULL_QUICK_LINK_KEY;
import static quicklink.project.common.constant.RedisKeyConstant.GOTO_QUICK_LINK_KEY;

/**
 * 回收站管理接口实现层
 */
@Service
@RequiredArgsConstructor
public class RecycleBinServiceImpl extends ServiceImpl<QuickLinkMapper, QuickLinkDO> implements RecycleBinService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void saveRecycleBin(RecycleBinSaveReqDTO requestParam) {
        LambdaUpdateWrapper<QuickLinkDO> updateWrapper = Wrappers.lambdaUpdate(QuickLinkDO.class)
                .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(QuickLinkDO::getGid, requestParam.getGid())
                .eq(QuickLinkDO::getEnableStatus, 0)
                .eq(QuickLinkDO::getDelFlag, 0);
        QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                .enableStatus(1)
                .build();
        baseMapper.update(quickLinkDO, updateWrapper);
        stringRedisTemplate.delete(String.format(GOTO_QUICK_LINK_KEY, requestParam.getFullShortUrl()));
    }

    @Override
    public IPage<QuickLinkPageRespDTO> pageQuickLink(QuickLinkRecycleBinPageReqDTO requestParam) {
        IPage<QuickLinkDO> resultPage = baseMapper.pageRecycleBinLink(requestParam);
        return resultPage.convert(each -> {
            QuickLinkPageRespDTO result = BeanUtil.toBean(each, QuickLinkPageRespDTO.class);
            result.setDomain("http://" + result.getDomain());
            return result;
        });
    }

    @Override
    public void recoverRecycleBin(RecycleBinRecoverReqDTO requestParam) {
        LambdaUpdateWrapper<QuickLinkDO> updateWrapper = Wrappers.lambdaUpdate(QuickLinkDO.class)
                .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(QuickLinkDO::getGid, requestParam.getGid())
                .eq(QuickLinkDO::getEnableStatus, 1)
                .eq(QuickLinkDO::getDelFlag, 0);
        QuickLinkDO quickLinkDO = QuickLinkDO.builder()
                .enableStatus(0)
                .build();
        baseMapper.update(quickLinkDO, updateWrapper);
        stringRedisTemplate.delete(String.format(GOTO_IS_NULL_QUICK_LINK_KEY, requestParam.getFullShortUrl()));
    }

    @Override
    public void removeRecycleBin(RecycleBinRemoveReqDTO requestParam) {
        LambdaUpdateWrapper<QuickLinkDO> updateWrapper = Wrappers.lambdaUpdate(QuickLinkDO.class)
                .eq(QuickLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(QuickLinkDO::getGid, requestParam.getGid())
                .eq(QuickLinkDO::getEnableStatus, 1)
                .eq(QuickLinkDO::getDelTime, 0L)
                .eq(QuickLinkDO::getDelFlag, 0);
        QuickLinkDO delQuickLinkDO = QuickLinkDO.builder()
                .delTime(System.currentTimeMillis())
                .build();
        delQuickLinkDO.setDelFlag(1);
        baseMapper.update(delQuickLinkDO, updateWrapper);
    }
}
