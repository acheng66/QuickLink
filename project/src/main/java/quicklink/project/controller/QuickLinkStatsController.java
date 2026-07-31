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

package quicklink.project.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import quicklink.project.common.convention.result.Result;
import quicklink.project.common.convention.result.Results;
import quicklink.project.dto.req.QuickLinkGroupStatsAccessRecordReqDTO;
import quicklink.project.dto.req.QuickLinkGroupStatsReqDTO;
import quicklink.project.dto.req.QuickLinkStatsAccessRecordReqDTO;
import quicklink.project.dto.req.QuickLinkStatsReqDTO;
import quicklink.project.dto.resp.QuickLinkStatsAccessRecordRespDTO;
import quicklink.project.dto.resp.QuickLinkStatsRespDTO;
import quicklink.project.service.QuickLinkStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链接监控控制层
 */
@RestController
@RequiredArgsConstructor
public class QuickLinkStatsController {

    private final QuickLinkStatsService quickLinkStatsService;

    /**
     * 访问单个短链接指定时间内监控数据
     */
    @GetMapping("/api/quick-link/v1/stats")
    public Result<QuickLinkStatsRespDTO> quickLinkStats(QuickLinkStatsReqDTO requestParam) {
        return Results.success(quickLinkStatsService.oneQuickLinkStats(requestParam));
    }

    /**
     * 访问分组短链接指定时间内监控数据
     */
    @GetMapping("/api/quick-link/v1/stats/group")
    public Result<QuickLinkStatsRespDTO> groupQuickLinkStats(QuickLinkGroupStatsReqDTO requestParam) {
        return Results.success(quickLinkStatsService.groupQuickLinkStats(requestParam));
    }

    /**
     * 访问单个短链接指定时间内访问记录监控数据
     */
    @GetMapping("/api/quick-link/v1/stats/access-record")
    public Result<IPage<QuickLinkStatsAccessRecordRespDTO>> quickLinkStatsAccessRecord(QuickLinkStatsAccessRecordReqDTO requestParam) {
        return Results.success(quickLinkStatsService.quickLinkStatsAccessRecord(requestParam));
    }

    /**
     * 访问分组短链接指定时间内访问记录监控数据
     */
    @GetMapping("/api/quick-link/v1/stats/access-record/group")
    public Result<IPage<QuickLinkStatsAccessRecordRespDTO>> groupQuickLinkStatsAccessRecord(QuickLinkGroupStatsAccessRecordReqDTO requestParam) {
        return Results.success(quickLinkStatsService.groupQuickLinkStatsAccessRecord(requestParam));
    }
}
