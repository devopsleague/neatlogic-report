/*
 * Copyright(c) 2022 TechSure Co., Ltd. All Rights Reserved.
 * 本内容仅限于深圳市赞悦科技有限公司内部传阅，禁止外泄以及用于其他的商业项目。
 */

package codedriver.module.report.service;

import codedriver.framework.report.dto.ReportDataSourceVo;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

public interface ReportDataSourceService {
    void deleteReportDataSource(ReportDataSourceVo reportDataSourceVo);

    @Transactional
    void executeReportDataSource(ReportDataSourceVo dataSourceVo) throws SQLException;
}
