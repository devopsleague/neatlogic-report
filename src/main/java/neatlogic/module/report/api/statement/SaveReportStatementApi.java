/*Copyright (C) 2024  深圳极向量科技有限公司 All Rights Reserved.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.*/

package neatlogic.module.report.api.statement;

import neatlogic.framework.asynchronization.threadlocal.UserContext;
import neatlogic.framework.auth.core.AuthAction;
import neatlogic.framework.common.constvalue.ApiParamType;
import neatlogic.framework.report.dto.ReportStatementVo;
import neatlogic.framework.restful.annotation.Description;
import neatlogic.framework.restful.annotation.Input;
import neatlogic.framework.restful.annotation.OperationType;
import neatlogic.framework.restful.annotation.Param;
import neatlogic.framework.restful.constvalue.OperationTypeEnum;
import neatlogic.framework.restful.core.privateapi.PrivateApiComponentBase;
import neatlogic.module.report.auth.label.REPORT_STATEMENT_MODIFY;
import neatlogic.module.report.dao.mapper.ReportStatementMapper;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@AuthAction(action = REPORT_STATEMENT_MODIFY.class)
@OperationType(type = OperationTypeEnum.UPDATE)
@Transactional
public class SaveReportStatementApi extends PrivateApiComponentBase {
    @Resource
    private ReportStatementMapper reportStatementMapper;


    @Override
    public String getToken() {
        return "report/statement/save";
    }

    @Override
    public String getName() {
        return "保存报表";
    }

    @Override
    public String getConfig() {
        return null;
    }

    @Input({@Param(name = "id", type = ApiParamType.LONG, desc = "id，不存在代表添加"),
            @Param(name = "name", type = ApiParamType.STRING, desc = "名称", maxLength = 50, isRequired = true, xss = true),
            @Param(name = "description", type = ApiParamType.STRING, desc = "说明", xss = true, maxLength = 500),
            @Param(name = "isActive", type = ApiParamType.INTEGER, desc = "是否激活", defaultValue = "0"),
            @Param(name = "width", type = ApiParamType.INTEGER, desc = "画布宽度"),
            @Param(name = "config", type = ApiParamType.JSONOBJECT, desc = "额外配置"),
            @Param(name = "height", type = ApiParamType.INTEGER, desc = "画布高度"),
            @Param(name = "widgetList", type = ApiParamType.JSONARRAY, desc = "组件列表", isRequired = true)})
    @Description(desc = "保存报表接口")
    @Override
    public Object myDoService(JSONObject jsonObj) throws Exception {
        ReportStatementVo reportStatementVo = JSONObject.toJavaObject(jsonObj, ReportStatementVo.class);
        if (jsonObj.getLong("id") == null) {
            reportStatementVo.setFcu(UserContext.get().getUserUuid(true));
            reportStatementMapper.insertReportStatement(reportStatementVo);
        } else {
            reportStatementVo.setLcu(UserContext.get().getUserUuid(true));
            reportStatementMapper.updateReportStatement(reportStatementVo);
        }
        return reportStatementVo.getId();
    }

}
