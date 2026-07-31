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

import com.baomidou.mybatisplus.core.metadata.IPage;
import quicklink.project.dto.req.QuickLinkGroupStatsAccessRecordReqDTO;
import quicklink.project.dto.req.QuickLinkGroupStatsReqDTO;
import quicklink.project.dto.req.QuickLinkStatsAccessRecordReqDTO;
import quicklink.project.dto.req.QuickLinkStatsReqDTO;
import quicklink.project.dto.resp.QuickLinkStatsAccessRecordRespDTO;
import quicklink.project.dto.resp.QuickLinkStatsRespDTO;

/**
 * 短链接监控接口层
 */
public interface QuickLinkStatsService {

    /**
     * 获取单个短链接监控数据
     *
     * @param requestParam 获取短链接监控数据入参
     * @return 短链接监控数据
     */
    QuickLinkStatsRespDTO oneQuickLinkStats(QuickLinkStatsReqDTO requestParam);

    /**
     * 获取分组短链接监控数据
     *
     * @param requestParam 获取分组短链接监控数据入参
     * @return 分组短链接监控数据
     */
    QuickLinkStatsRespDTO groupQuickLinkStats(QuickLinkGroupStatsReqDTO requestParam);

    /**
     * 访问单个短链接指定时间内访问记录监控数据
     *
     * @param requestParam 获取短链接监控访问记录数据入参
     * @return 访问记录监控数据
     */
    IPage<QuickLinkStatsAccessRecordRespDTO> quickLinkStatsAccessRecord(QuickLinkStatsAccessRecordReqDTO requestParam);

    /**
     * 访问分组短链接指定时间内访问记录监控数据
     *
     * @param requestParam 获取分组短链接监控访问记录数据入参
     * @return 分组访问记录监控数据
     */
    IPage<QuickLinkStatsAccessRecordRespDTO> groupQuickLinkStatsAccessRecord(QuickLinkGroupStatsAccessRecordReqDTO requestParam);
}
