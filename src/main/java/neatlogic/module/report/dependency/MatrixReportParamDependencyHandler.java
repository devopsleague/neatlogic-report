/*Copyright (C) $today.year  深圳极向量科技有限公司 All Rights Reserved.

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

package neatlogic.module.report.dependency;

import com.alibaba.fastjson.JSONObject;
import neatlogic.framework.asynchronization.threadlocal.TenantContext;
import neatlogic.framework.dependency.constvalue.FrameworkFromType;
import neatlogic.framework.dependency.core.CustomDependencyHandlerBase;
import neatlogic.framework.dependency.core.IFromType;
import neatlogic.framework.dependency.dto.DependencyInfoVo;
import neatlogic.module.report.dao.mapper.ReportMapper;
import neatlogic.module.report.dto.ReportParamVo;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表引用矩阵处理器
 *
 * @author: laiwt
 * @since: 2021/11/3 11:42
 **/
@Service
public class MatrixReportParamDependencyHandler extends CustomDependencyHandlerBase {

    @Resource
    private ReportMapper reportMapper;

    /**
     * 表名
     *
     * @return
     */
    @Override
    protected String getTableName() {
        return null;
    }

    /**
     * 被引用者（上游）字段
     *
     * @return
     */
    @Override
    protected String getFromField() {
        return null;
    }

    /**
     * 引用者（下游）字段
     *
     * @return
     */
    @Override
    protected String getToField() {
        return null;
    }

    @Override
    protected List<String> getToFieldList() {
        return null;
    }

    /**
     * 报表参数与矩阵之间的引用关系有自己的添加和删除方式
     *
     * @param from 被引用者（上游）值（如：服务时间窗口uuid）
     * @param to 引用者（下游）值（如：服务uuid）
     * @return
     */
    @Override
    public int insert(Object from, Object to) {
        return 0;
    }

    /**
     * 报表参数与矩阵之间的引用关系有自己的添加和删除方式
     *
     * @param to 引用者（下游）值（如：服务uuid）
     * @return
     */
    @Override
    public int delete(Object to) {
        return 0;
    }

    /**
     * 解析数据，拼装跳转url，返回引用下拉列表一个选项数据结构
     *
     * @param dependencyObj 引用关系数据
     * @return
     */
    @Override
    protected DependencyInfoVo parse(Object dependencyObj) {
        if (dependencyObj == null) {
            return null;
        }
        if (dependencyObj instanceof ReportParamVo) {
            ReportParamVo reportParamVo = (ReportParamVo) dependencyObj;
            JSONObject dependencyInfoConfig = new JSONObject();
            dependencyInfoConfig.put("reportId", reportParamVo.getReportId());
//            dependencyInfoConfig.put("reportName", reportParamVo.getReportName());
//            dependencyInfoConfig.put("paramName", reportParamVo.getName());
            List<String> pathList = new ArrayList<>();
            pathList.add("报表模板管理");
            pathList.add(reportParamVo.getReportName());
            pathList.add("编辑");
            pathList.add("条件配置");
            String lastName = reportParamVo.getLabel();
//            String pathFormat = "报表-${DATA.reportName}-${DATA.paramName}";
            String urlFormat = "/" + TenantContext.get().getTenantUuid() + "/report.html#/report-manage";
            return new DependencyInfoVo(reportParamVo.getReportId(), dependencyInfoConfig, lastName, pathList, urlFormat, this.getGroupName());
        }
        return null;
    }

    /**
     * 被引用者（上游）类型
     *
     * @return
     */
    @Override
    public IFromType getFromType() {
        return FrameworkFromType.MATRIX;
    }

    /**
     * 查询引用列表数据
     *
     * @param from   被引用者（上游）值（如：服务时间窗口uuid）
     * @param startNum 开始行号
     * @param pageSize 每页条数
     * @return
     */
    @Override
    public List<DependencyInfoVo> getDependencyList(Object from, int startNum, int pageSize) {
        List<DependencyInfoVo> resultList = new ArrayList<>();
        List<ReportParamVo> callerList = reportMapper.getReportParamByMatrixUuid((String) from, startNum, pageSize);
        for (ReportParamVo caller : callerList) {
            DependencyInfoVo dependencyInfoVo = parse(caller);
            if (dependencyInfoVo != null) {
                resultList.add(dependencyInfoVo);
            }
        }
        return resultList;
    }

    @Override
    public int getDependencyCount(Object from) {
        return reportMapper.getReportParamCountByMatrixUuid((String) from);
    }
}
