package neatlogic.module.report.service;

import neatlogic.module.report.dao.mapper.ReportInstanceMapper;
import neatlogic.module.report.dto.ReportInstanceVo;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class ReportInstanceServiceImpl implements ReportInstanceService {

    @Resource
    private ReportInstanceMapper reportInstanceMapper;

    @Resource
    private ReportService reportService;

    @Override
    public ReportInstanceVo getReportInstanceDetailById(Long reportInstanceId) {
        ReportInstanceVo reportInstanceVo = reportInstanceMapper.getReportInstanceById(reportInstanceId);
        reportInstanceVo.setReportInstanceAuthList(reportInstanceMapper.getReportInstanceAuthByReportInstanceId(reportInstanceId));
        reportInstanceVo.setTableColumnsMap(reportService.getShowColumnsMap(reportInstanceId));
        return reportInstanceVo;
    }

}
