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

package quicklink.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import quicklink.admin.common.convention.result.Result;
import quicklink.admin.remote.QuickLinkActualRemoteService;
import quicklink.admin.remote.dto.req.QuickLinkGroupStatsAccessRecordReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkGroupStatsReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkStatsAccessRecordReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkStatsReqDTO;
import quicklink.admin.remote.dto.resp.QuickLinkStatsAccessRecordRespDTO;
import quicklink.admin.remote.dto.resp.QuickLinkStatsRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链接监控控制层
 */
@RestController(value = "quickLinkStatsControllerByAdmin")
@RequiredArgsConstructor
public class QuickLinkStatsController {

    private final QuickLinkActualRemoteService quickLinkActualRemoteService;

    /**
     * 访问单个短链接指定时间内监控数据
     */
    @GetMapping("/api/quick-link/admin/v1/stats")
    public Result<QuickLinkStatsRespDTO> quickLinkStats(QuickLinkStatsReqDTO requestParam) {
        return quickLinkActualRemoteService.oneQuickLinkStats(
                requestParam.getFullShortUrl(),
                requestParam.getGid(),
                requestParam.getEnableStatus(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    /**
     * 访问分组短链接指定时间内监控数据
     */
    @GetMapping("/api/quick-link/admin/v1/stats/group")
    public Result<QuickLinkStatsRespDTO> groupQuickLinkStats(QuickLinkGroupStatsReqDTO requestParam) {
        return quickLinkActualRemoteService.groupQuickLinkStats(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    /**
     * 访问单个短链接指定时间内访问记录监控数据
     */
    @GetMapping("/api/quick-link/admin/v1/stats/access-record")
    public Result<Page<QuickLinkStatsAccessRecordRespDTO>> quickLinkStatsAccessRecord(QuickLinkStatsAccessRecordReqDTO requestParam) {
        return quickLinkActualRemoteService.quickLinkStatsAccessRecord(
                requestParam.getFullShortUrl(),
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate(),
                requestParam.getEnableStatus(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }

    /**
     * 访问分组短链接指定时间内访问记录监控数据
     */
    @GetMapping("/api/quick-link/admin/v1/stats/access-record/group")
    public Result<Page<QuickLinkStatsAccessRecordRespDTO>> groupQuickLinkStatsAccessRecord(QuickLinkGroupStatsAccessRecordReqDTO requestParam) {
        return quickLinkActualRemoteService.groupQuickLinkStatsAccessRecord(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }
}
