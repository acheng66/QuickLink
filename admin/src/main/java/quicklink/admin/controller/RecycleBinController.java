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
import quicklink.admin.dto.req.RecycleBinRecoverReqDTO;
import quicklink.admin.dto.req.RecycleBinRemoveReqDTO;
import quicklink.admin.dto.req.RecycleBinSaveReqDTO;
import quicklink.admin.remote.QuickLinkActualRemoteService;
import quicklink.admin.remote.dto.req.QuickLinkRecycleBinPageReqDTO;
import quicklink.admin.remote.dto.resp.QuickLinkPageRespDTO;
import quicklink.admin.service.RecycleBinService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站管理控制层
 */
@RestController(value = "recycleBinControllerByAdmin")
@RequiredArgsConstructor
public class RecycleBinController {

    private final RecycleBinService recycleBinService;
    private final QuickLinkActualRemoteService quickLinkActualRemoteService;

    /**
     * 保存回收站
     */
    @PostMapping("/api/quick-link/admin/v1/recycle-bin/save")
    public Result<Void> saveRecycleBin(@RequestBody RecycleBinSaveReqDTO requestParam) {
        quickLinkActualRemoteService.saveRecycleBin(requestParam);
        return Results.success();
    }

    /**
     * 分页查询回收站短链接
     */
    @GetMapping("/api/quick-link/admin/v1/recycle-bin/page")
    public Result<Page<QuickLinkPageRespDTO>> pageQuickLink(QuickLinkRecycleBinPageReqDTO requestParam) {
        return recycleBinService.pageRecycleBinQuickLink(requestParam);
    }

    /**
     * 恢复短链接
     */
    @PostMapping("/api/quick-link/admin/v1/recycle-bin/recover")
    public Result<Void> recoverRecycleBin(@RequestBody RecycleBinRecoverReqDTO requestParam) {
        quickLinkActualRemoteService.recoverRecycleBin(requestParam);
        return Results.success();
    }

    /**
     * 移除短链接
     */
    @PostMapping("/api/quick-link/admin/v1/recycle-bin/remove")
    public Result<Void> removeRecycleBin(@RequestBody RecycleBinRemoveReqDTO requestParam) {
        quickLinkActualRemoteService.removeRecycleBin(requestParam);
        return Results.success();
    }
}
