/*
 * Copyright(c) 2022 TechSure Co., Ltd. All Rights Reserved.
 * 本内容仅限于深圳市赞悦科技有限公司内部传阅，禁止外泄以及用于其他的商业项目。
 */

package codedriver.module.report.api.datasource;

import codedriver.framework.auth.core.AuthAction;
import codedriver.framework.common.constvalue.ApiParamType;
import codedriver.framework.common.dto.BasePageVo;
import codedriver.framework.report.dto.ReportDataSourceVo;
import codedriver.framework.restful.annotation.*;
import codedriver.framework.restful.constvalue.OperationTypeEnum;
import codedriver.framework.restful.core.privateapi.PrivateApiComponentBase;
import codedriver.framework.util.TableResultUtil;
import codedriver.module.report.auth.label.REPORT_BASE;
import codedriver.module.report.dao.mapper.ReportDataSourceMapper;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
@AuthAction(action = REPORT_BASE.class)
@OperationType(type = OperationTypeEnum.SEARCH)
public class SearchReportDataSourceApi extends PrivateApiComponentBase {

    @Resource
    private ReportDataSourceMapper reportDataSourceMapper;

    @Override
    public String getToken() {
        return "report/datasource/search";
    }

    @Override
    public String getName() {
        return "查询报表数据源";
    }

    @Override
    public String getConfig() {
        return null;
    }

    @Input({@Param(name = "keyword", type = ApiParamType.STRING, desc = "关键字", xss = true)})
    @Output({@Param(explode = BasePageVo.class),
            @Param(name = "tbodyList", explode = ReportDataSourceVo[].class)})
    @Description(desc = "查询报表数据源接口")
    @Override
    public Object myDoService(JSONObject jsonObj) throws Exception {
        ReportDataSourceVo reportDataSourceVo = JSONObject.toJavaObject(jsonObj, ReportDataSourceVo.class);
        List<ReportDataSourceVo> reportDataSourceList = reportDataSourceMapper.searchReportDataSource(reportDataSourceVo);
        if (CollectionUtils.isNotEmpty(reportDataSourceList)) {
            reportDataSourceVo.setRowNum(reportDataSourceMapper.searchReportDataSourceCount(reportDataSourceVo));
        }
        return TableResultUtil.getResult(reportDataSourceList, reportDataSourceVo);
    }

}
