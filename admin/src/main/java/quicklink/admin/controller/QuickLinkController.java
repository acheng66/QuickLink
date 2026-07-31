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
import quicklink.admin.common.convention.result.Results;
import quicklink.admin.remote.QuickLinkActualRemoteService;
import quicklink.admin.remote.dto.req.QuickLinkBatchCreateReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkCreateReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkPageReqDTO;
import quicklink.admin.remote.dto.req.QuickLinkUpdateReqDTO;
import quicklink.admin.remote.dto.resp.QuickLinkBaseInfoRespDTO;
import quicklink.admin.remote.dto.resp.QuickLinkBatchCreateRespDTO;
import quicklink.admin.remote.dto.resp.QuickLinkCreateRespDTO;
import quicklink.admin.remote.dto.resp.QuickLinkPageRespDTO;
import quicklink.admin.toolkit.EasyExcelWebUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 短链接后管控制层
 */
@RestController(value = "quickLinkControllerByAdmin")
@RequiredArgsConstructor
public class QuickLinkController {

    private final QuickLinkActualRemoteService quickLinkActualRemoteService;

    /**
     * 创建短链接
     */
    @PostMapping("/api/quick-link/admin/v1/create")
    public Result<QuickLinkCreateRespDTO> createQuickLink(@RequestBody QuickLinkCreateReqDTO requestParam) {
        return quickLinkActualRemoteService.createQuickLink(requestParam);
    }

    /**
     * 批量创建短链接
     */
    @SneakyThrows
    @PostMapping("/api/quick-link/admin/v1/create/batch")
    public void batchCreateQuickLink(@RequestBody QuickLinkBatchCreateReqDTO requestParam, HttpServletResponse response) {
        Result<QuickLinkBatchCreateRespDTO> quickLinkBatchCreateRespDTOResult = quickLinkActualRemoteService.batchCreateQuickLink(requestParam);
        if (quickLinkBatchCreateRespDTOResult.isSuccess()) {
            List<QuickLinkBaseInfoRespDTO> baseLinkInfos = quickLinkBatchCreateRespDTOResult.getData().getBaseLinkInfos();
            EasyExcelWebUtil.write(response, "批量创建短链接-SaaS短链接系统", QuickLinkBaseInfoRespDTO.class, baseLinkInfos);
        }
    }

    /**
     * 修改短链接
     */
    @PostMapping("/api/quick-link/admin/v1/update")
    public Result<Void> updateQuickLink(@RequestBody QuickLinkUpdateReqDTO requestParam) {
        quickLinkActualRemoteService.updateQuickLink(requestParam);
        return Results.success();
    }

    /**
     * 分页查询短链接
     */
    @GetMapping("/api/quick-link/admin/v1/page")
    public Result<Page<QuickLinkPageRespDTO>> pageQuickLink(QuickLinkPageReqDTO requestParam) {
        return quickLinkActualRemoteService.pageQuickLink(requestParam.getGid(), requestParam.getOrderTag(), requestParam.getCurrent(), requestParam.getSize());
    }
}
