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

package quicklink.project.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import quicklink.project.dao.entity.QuickLinkDO;
import quicklink.project.dto.req.QuickLinkPageReqDTO;
import quicklink.project.dto.req.QuickLinkRecycleBinPageReqDTO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 短链接持久层
 */
public interface QuickLinkMapper extends BaseMapper<QuickLinkDO> {

    /**
     * 按主键游标分页读取仍然有效的短链接，用于重建布隆过滤器。
     *
     * <p>字段说明：只查询 id 和 full_short_url，减少数据库网络传输和对象创建。
     * <p>过滤说明：del_flag=0 排除已删除，enable_status=0 排除已禁用，valid_date 条件排除已过期。
     * <p>分页说明：id &gt; lastId + ORDER BY id + LIMIT 使用游标分页，避免 OFFSET 深分页。
     *
     * @param lastId 上一批最后一条记录的主键，第一次传 0
     * @param maxId 本轮重建开始时的最大雪花 ID，固定扫描边界
     * @param now 本轮重建统一使用的有效期判断时间
     * @param batchSize 单次最多读取的记录数
     * @return 按 id 升序排列的有效短链接数据
     */
    @Select("SELECT id, full_short_url FROM t_link " +
            "WHERE id > #{lastId} AND id <= #{maxId} AND del_flag = 0 AND enable_status = 0 " +
            "AND (valid_date IS NULL OR valid_date > #{now}) " +
            "ORDER BY id ASC LIMIT #{batchSize}")
    List<QuickLinkDO> selectValidLinksForBloomRebuild(
            @Param("lastId") Long lastId,
            @Param("maxId") Long maxId,
            @Param("now") Date now,
            @Param("batchSize") Integer batchSize);

    /**
     * 获取重建开始时的最大雪花 ID，用作本轮扫描上界；重建期间新增数据由双写逻辑补入新过滤器。
     */
    @Select("SELECT COALESCE(MAX(id), 0) FROM t_link")
    Long selectMaxIdForBloomRebuild();

    /**
     * 短链接访问统计自增
     */
    void incrementStats(@Param("gid") String gid,
                        @Param("fullShortUrl") String fullShortUrl,
                        @Param("totalPv") Integer totalPv,
                        @Param("totalUv") Integer totalUv,
                        @Param("totalUip") Integer totalUip);

    /**
     * 分页统计短链接
     */
    IPage<QuickLinkDO> pageLink(QuickLinkPageReqDTO requestParam);

    /**
     * 分页统计回收站短链接
     */
    IPage<QuickLinkDO> pageRecycleBinLink(QuickLinkRecycleBinPageReqDTO requestParam);
}
