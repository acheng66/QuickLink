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

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.baomidou.mybatisplus.core.metadata.IPage;
import quicklink.project.common.convention.result.Result;
import quicklink.project.common.convention.result.Results;
import quicklink.project.dto.req.QuickLinkBatchCreateReqDTO;
import quicklink.project.dto.req.QuickLinkCreateReqDTO;
import quicklink.project.dto.req.QuickLinkPageReqDTO;
import quicklink.project.dto.req.QuickLinkUpdateReqDTO;
import quicklink.project.dto.resp.QuickLinkBatchCreateRespDTO;
import quicklink.project.dto.resp.QuickLinkCreateRespDTO;
import quicklink.project.dto.resp.QuickLinkGroupCountQueryRespDTO;
import quicklink.project.dto.resp.QuickLinkPageRespDTO;
import quicklink.project.handler.CustomBlockHandler;
import quicklink.project.service.QuickLinkService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 短链接控制层
 */
@RestController
@RequiredArgsConstructor
public class QuickLinkController {

    private final QuickLinkService quickLinkService;

    /**
     * 短链接跳转原始链接
     */
    @GetMapping("/{short-uri}")
    public void restoreUrl(@PathVariable("short-uri") String shortUri, ServletRequest request, ServletResponse response) {
        quickLinkService.restoreUrl(shortUri, request, response);
    }

    /**
     * 创建短链接
     */
    @PostMapping("/api/quick-link/v1/create")
    @SentinelResource(
            value = "create_quick-link",
            blockHandler = "createQuickLinkBlockHandlerMethod",
            blockHandlerClass = CustomBlockHandler.class
    )
    public Result<QuickLinkCreateRespDTO> createQuickLink(@RequestBody QuickLinkCreateReqDTO requestParam) {
        return Results.success(quickLinkService.createQuickLink(requestParam));
    }

    /**
     * 通过分布式锁创建短链接
     */
    @PostMapping("/api/quick-link/v1/create/by-lock")
    public Result<QuickLinkCreateRespDTO> createQuickLinkByLock(@RequestBody QuickLinkCreateReqDTO requestParam) {
        return Results.success(quickLinkService.createQuickLinkByLock(requestParam));
    }

    /**
     * 批量创建短链接
     */
    @PostMapping("/api/quick-link/v1/create/batch")
    public Result<QuickLinkBatchCreateRespDTO> batchCreateQuickLink(@RequestBody QuickLinkBatchCreateReqDTO requestParam) {
        return Results.success(quickLinkService.batchCreateQuickLink(requestParam));
    }

    /**
     * 修改短链接
     */
    @PostMapping("/api/quick-link/v1/update")
    public Result<Void> updateQuickLink(@RequestBody QuickLinkUpdateReqDTO requestParam) {
        quickLinkService.updateQuickLink(requestParam);
        return Results.success();
    }

    /**
     * 分页查询短链接
     */
    @GetMapping("/api/quick-link/v1/page")
    public Result<IPage<QuickLinkPageRespDTO>> pageQuickLink(QuickLinkPageReqDTO requestParam) {
        return Results.success(quickLinkService.pageQuickLink(requestParam));
    }

    /**
     * 查询短链接分组内数量
     */
    @GetMapping("/api/quick-link/v1/count")
    public Result<List<QuickLinkGroupCountQueryRespDTO>> listGroupQuickLinkCount(@RequestParam("requestParam") List<String> requestParam) {
        return Results.success(quickLinkService.listGroupQuickLinkCount(requestParam));
    }
}
