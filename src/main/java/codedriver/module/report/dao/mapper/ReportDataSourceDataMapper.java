/*
 * Copyright(c) 2022 TechSure Co., Ltd. All Rights Reserved.
 * 本内容仅限于深圳市赞悦科技有限公司内部传阅，禁止外泄以及用于其他的商业项目。
 */

package codedriver.module.report.dao.mapper;

import codedriver.framework.report.dto.ReportDataSourceDataVo;
import codedriver.framework.report.dto.ReportDataSourceVo;

import java.util.HashMap;
import java.util.List;

public interface ReportDataSourceDataMapper {
    int getDataSourceDataCount(ReportDataSourceDataVo reportDataSourceDataVo);

    int searchDataSourceDataCount(ReportDataSourceDataVo reportDataSourceDataVo);

    List<HashMap<String, Object>> searchDataSourceData(ReportDataSourceDataVo reportDataSourceDataVo);

    void insertDataSourceData(ReportDataSourceDataVo reportDataSourceDataVo);

    void truncateTable(ReportDataSourceVo reportDataSourceVo);

    //需要返回删除行数
    int clearExpiredData(ReportDataSourceDataVo reportDataSourceDataVo);
}
