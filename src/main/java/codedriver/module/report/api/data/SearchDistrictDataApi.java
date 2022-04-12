/*
 * Copyright(c) 2022 TechSure Co., Ltd. All Rights Reserved.
 * 本内容仅限于深圳市赞悦科技有限公司内部传阅，禁止外泄以及用于其他的商业项目。
 */

package codedriver.module.report.api.data;

import codedriver.framework.auth.core.AuthAction;
import codedriver.framework.common.constvalue.ApiParamType;
import codedriver.framework.report.dto.data.DistrictVo;
import codedriver.framework.restful.annotation.*;
import codedriver.framework.restful.constvalue.OperationTypeEnum;
import codedriver.framework.restful.core.privateapi.PrivateApiComponentBase;
import codedriver.module.report.auth.label.REPORT_BASE;
import codedriver.module.report.dao.mapper.ReportDataMapper;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
@AuthAction(action = REPORT_BASE.class)
@OperationType(type = OperationTypeEnum.SEARCH)
public class SearchDistrictDataApi extends PrivateApiComponentBase {

    @Resource
    private ReportDataMapper reportDataMapper;

    @Override
    public String getToken() {
        return "report/data/district/search";
    }

    @Override
    public String getName() {
        return "搜索地理数据";
    }

    @Override
    public String getConfig() {
        return null;
    }

    @Input({@Param(name = "parent", type = ApiParamType.INTEGER, desc = "上级区域编码，例如中国编码：100000")})
    @Output({@Param(explode = DistrictVo[].class)})
    @Description(desc = "搜索地理数据接口")
    @Override
    public Object myDoService(JSONObject jsonObj) throws Exception {
        DistrictVo districtVo = JSONObject.toJavaObject(jsonObj, DistrictVo.class);
        if (districtVo.getParent() != null) {
            DistrictVo parentDistrictVo = reportDataMapper.getDistrictDataById(districtVo.getParent());
            districtVo.setLft(parentDistrictVo.getLft());
            districtVo.setRht(parentDistrictVo.getRht());
        }
        return reportDataMapper.searchDistrictData(districtVo);
    }

}
