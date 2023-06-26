/*
 * Copyright(c) 2023 NeatLogic Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package neatlogic.module.report.api;

import neatlogic.framework.auth.core.AuthAction;
import neatlogic.framework.restful.constvalue.OperationTypeEnum;
import neatlogic.framework.restful.annotation.*;
import neatlogic.module.report.auth.label.REPORT_TEMPLATE_MODIFY;
import neatlogic.module.report.dao.mapper.ReportInstanceMapper;
import neatlogic.module.report.dao.mapper.ReportMapper;
import neatlogic.module.report.dao.mapper.ReportSendJobMapper;
import neatlogic.framework.report.exception.ReportHasBeenQuotedByJobException;
import neatlogic.framework.report.exception.ReportHasInstanceException;
import neatlogic.framework.report.exception.ReportNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;

import neatlogic.framework.common.constvalue.ApiParamType;
import neatlogic.framework.restful.core.privateapi.PrivateApiComponentBase;
import neatlogic.module.report.dto.ReportVo;
import org.springframework.transaction.annotation.Transactional;

@AuthAction(action = REPORT_TEMPLATE_MODIFY.class)
@OperationType(type = OperationTypeEnum.DELETE)
@Service
@Transactional
public class DeleteReportApi extends PrivateApiComponentBase {

	@Autowired
	private ReportMapper reportMapper;

	@Autowired
	private ReportInstanceMapper reportInstanceMapper;

	@Autowired
	private ReportSendJobMapper reportSendJobMapper;

	@Override
	public String getToken() {
		return "report/delete";
	}

	@Override
	public String getName() {
		return "删除报表";
	}

	@Override
	public String getConfig() {
		return null;
	}

	@Input({ @Param(name = "id", type = ApiParamType.LONG, isRequired = true, desc = "报表id") })
	@Output({})
	@Description(desc = "删除报表接口")
	@Override
	public Object myDoService(JSONObject jsonObj) throws Exception {
		Long reportId = jsonObj.getLong("id");
		ReportVo report = reportMapper.getReportById(reportId);
		if(report == null){
			throw new ReportNotFoundException(reportId);
		}
		/* 检查是否被报表实例引用 **/
		if(reportInstanceMapper.checkReportInstanceExistsByReportId(reportId) > 0){
			throw new ReportHasInstanceException(report.getName());
		}
		/* 检查是否被报表发送计划引用 **/
		if(reportSendJobMapper.checkJobExistsByReportId(reportId) > 0){
			throw new ReportHasBeenQuotedByJobException(report.getName());
		}
		reportMapper.deleteReportAuthByReportId(reportId);
		reportMapper.deleteReportParamByReportId(reportId);
		reportMapper.deleteReportById(reportId);
		return null;
	}
}
