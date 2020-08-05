package codedriver.module.report.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;

import codedriver.framework.auth.core.AuthAction;
import codedriver.framework.common.constvalue.ApiParamType;
import codedriver.framework.reminder.core.OperationTypeEnum;
import codedriver.framework.restful.annotation.Description;
import codedriver.framework.restful.annotation.Input;
import codedriver.framework.restful.annotation.OperationType;
import codedriver.framework.restful.annotation.Param;
import codedriver.framework.restful.core.ApiComponentBase;
import codedriver.module.report.dao.mapper.ReportMapper;
import codedriver.module.report.dto.ReportVo;

@Service
@AuthAction(name = "REPORT_MODIFY")
@OperationType(type = OperationTypeEnum.UPDATE)
public class UpdateReportActiveApi extends ApiComponentBase {

	@Autowired
	private ReportMapper reportMapper;

	@Override
	public String getToken() {
		return "report/toggleactive";
	}

	@Override
	public String getName() {
		return "更改报表定义激活状态";
	}

	@Override
	public String getConfig() {
		return null;
	}

	@Input({ @Param(name = "id", type = ApiParamType.LONG, desc = "报表id"), @Param(name = "isActive", type = ApiParamType.INTEGER, desc = "是否激活") })
	@Description(desc = "更改报表定义激活状态")
	@Override
	public Object myDoService(JSONObject jsonObj) throws Exception {
		ReportVo reportVo = JSONObject.toJavaObject(jsonObj, ReportVo.class);
		reportMapper.updateReportActive(reportVo);
		return null;
	}
}
