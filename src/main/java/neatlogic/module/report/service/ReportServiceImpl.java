package neatlogic.module.report.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import neatlogic.framework.common.dto.BasePageVo;
import neatlogic.framework.common.util.PageUtil;
import neatlogic.framework.dao.plugin.PageRowBounds;
import neatlogic.framework.dto.RestVo;
import neatlogic.framework.report.exception.ReportParamNameRepeatsException;
import neatlogic.framework.report.exception.TableNotFoundInReportException;
import neatlogic.framework.sqlrunner.SqlInfo;
import neatlogic.framework.sqlrunner.SqlRunner;
import neatlogic.framework.util.RestUtil;
import neatlogic.framework.util.excel.ExcelBuilder;
import neatlogic.framework.util.excel.SheetBuilder;
import neatlogic.module.report.config.ReportConfig;
import neatlogic.module.report.dao.mapper.ReportInstanceMapper;
import neatlogic.module.report.dao.mapper.ReportMapper;
import neatlogic.module.report.dto.*;
import neatlogic.module.report.util.ReportXmlUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFCell;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {
    Logger logger = LoggerFactory.getLogger(ReportServiceImpl.class);

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private ReportInstanceMapper reportInstanceMapper;

    @Autowired
    private DataSource dataSource;

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public ReportVo getReportDetailById(Long reportId) {
        ReportVo reportVo = reportMapper.getReportById(reportId);
        if (reportVo != null) {
            reportVo.setParamList(reportMapper.getReportParamByReportId(reportId));
            List<ReportAuthVo> reportAuthList = reportMapper.getReportAuthByReportId(reportVo.getId());
            reportVo.setReportAuthList(reportAuthList);
        }
        return reportVo;
    }

    private Object getRemoteResult(RestVo restVo) {
        String result = RestUtil.sendPostRequest(restVo);
        try {
            return JSON.parse(result);
        } catch (Exception ex) {
            return result;
        }
    }

    /**
     * 返回所有数据源结果
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> getQueryResult(Long reportId, JSONObject paramMap, Map<String, Long> timeMap,
                                              boolean isFirst, Map<String, List<String>> showColumnsMap) throws Exception {
        boolean needPage = true;
        if (paramMap.containsKey("NOPAGE")) {
            needPage = false;
        }
        ReportVo reportConfig = getReportDetailById(reportId);
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet resultSet = null;
        Map<String, Object> returnResultMap = new HashMap<>();
        List<SelectVo> selectList;
        List<RestVo> restList;
        Map<String, JSONObject> pageMap = new HashMap<>();
        try {
            Map<String, Object> analyseMap = ReportXmlUtil.analyseSql(reportConfig.getSql(), paramMap);
            restList = (List<RestVo>) analyseMap.get("rest");
            selectList = (List<SelectVo>) analyseMap.get("select");
            for (RestVo rest : restList) {
                if (isFirst && rest.isLazyLoad()) {
                    continue;
                }
                returnResultMap.put(rest.getId(), getRemoteResult(rest));
            }

            for (SelectVo select : selectList) {
                try {
                    conn = getConnection();
                    // 如果SQL设置了延迟加载，第一次访问时不主动获取数据
                    if (isFirst && select.isLazyLoad()) {
                        continue;
                    }
                    String sqlText = select.getSql();
                    stmt = conn.prepareStatement(sqlText);
                    stmt.setQueryTimeout(select.getQueryTimeout());
                    StringBuilder sbParam = new StringBuilder();
                    if (select.getParamList().size() > 0) {
                        sbParam.append("(");
                        for (int p = 0; p < select.getParamList().size(); p++) {
                            if (select.getParamList().get(p) instanceof String) {
                                stmt.setObject(p + 1, select.getParamList().get(p));
                                sbParam.append(select.getParamList().get(p)).append(",");
                            } else {
                                // 数组参数有待处理
                                stmt.setObject(p + 1, ((String[]) select.getParamList().get(p))[0]);
                                sbParam.append(((String[]) select.getParamList().get(p))[0]).append(",");
                            }
                        }
                        sbParam.deleteCharAt(sbParam.toString().length() - 1);
                        sbParam.append(")");
                    }
                    /*
                      新增日志记录
                     */
                    if (logger.isDebugEnabled() || logger.isInfoEnabled()) {
                        logger.debug("REPORT RUN SQL::" + sqlText);
                        logger.debug("REPORT RUN SQL PARAM::" + sbParam.toString());
                    }

                    resultSet = stmt.executeQuery();
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int count = metaData.getColumnCount();
                    /*
                    String[] columns = new String[count];
                    Integer[] columnTypes = new Integer[count];

                    for (int i = 1; i <= count; i++) {
                        columnTypes[i - 1] = metaData.getColumnType(i);
                        columns[i - 1] = metaData.getColumnLabel(i);
                    }*/

                    List<Map<String, Object>> resultList = new ArrayList<>();
                    Map<String, Map<String, Object>> checkMap = new HashMap<>();
                    Map<String, List> returnMap = new HashMap<>();
                    int start = -1, end = -1;
                    int index = 0;
                    int currentPage = 1;
                    int pageCount = 0;
                    if (needPage && select.isNeedPage() && select.getPageSize() > 0) {

                        if (paramMap.containsKey(select.getId() + ".currentpage")) {
                            currentPage = Integer.parseInt(paramMap.get(select.getId() + ".currentpage").toString());
                        }

                        if (paramMap.containsKey(select.getId() + ".pagesize")) {
                            select.setPageSize(Integer.parseInt(paramMap.get(select.getId() + ".pagesize").toString()));
                        }

                        start = Math.max((currentPage - 1) * select.getPageSize(), 0);
                        end = start + select.getPageSize();
                    }
                    while (resultSet.next()) {
                        ResultMapVo tmpResultMapVo = select.getResultMap();
                        Map<String, Object> resultMap = new HashMap<>();
                        for (int i = 1; i <= count; i++) {
                            resultMap.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                        }
                        tmpResultMapVo.setIndex(index);
                        if (select.getResultType() == SelectVo.RSEULT_TYPE_LIST) {
                            resultList = resultMapRecursion(tmpResultMapVo, resultList, resultMap, checkMap);
                        } else {
                            returnMap = wrapResultMapToMap(tmpResultMapVo, resultMap, returnMap);
                        }
                        index = tmpResultMapVo.getIndex();
                    }
                    if (needPage && select.isNeedPage() && select.getPageSize() > 0) {
                        pageCount = PageUtil.getPageCount(index, select.getPageSize());
                        if (pageCount < currentPage) {//异常处理
                            start = 1;
                            end = start + select.getPageSize();
                            currentPage = 1;
                        }
                        JSONObject pageObj = new JSONObject();
                        pageObj.put("rowNum", index);
                        pageObj.put("currentPage", currentPage);
                        pageObj.put("pageSize", select.getPageSize());
                        pageObj.put("pageCount", pageCount);
                        pageObj.put("needPage", true);
                        pageMap.put(select.getId(), pageObj);
                    }
                    returnResultMap.put(ReportConfig.REPORT_PAGE_MAP_KEY, pageMap);
                    /* 如果存在表格且存在表格显示列的配置，则筛选显示列并排序
                      showColumnMap:key->表格ID;value->配置的表格显示列
                     */
                    if (MapUtils.isNotEmpty(showColumnsMap) && showColumnsMap.containsKey(select.getId())) {
                        List<Map<String, Object>> sqList = selectTableColumns(showColumnsMap, select, resultList);
                        resultList = sqList;
                    }

                    if (select.getResultType() == SelectVo.RSEULT_TYPE_LIST) {
                        if (needPage && select.isNeedPage()) {
                            resultList = resultList.subList(start, (pageCount == currentPage) ? resultList.size() : end);
                        }
                        returnResultMap.put(select.getId(), resultList);
                    } else {
                        returnResultMap.put(select.getId(), returnMap);
                    }

                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                    throw e;
                } finally {
                    try {
                        if (resultSet != null)
                            resultSet.close();
                        if (stmt != null)
                            stmt.close();
                        if (conn != null)
                            conn.close();
                    } catch (SQLException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
        return returnResultMap;
    }

    /**
     * 查询报表实例的表格显示列配置
     *
     * @param reportInstanceId 报表实例id
     * @return 结果集
     */
    @Override
    public Map<String, List<String>> getShowColumnsMap(Long reportInstanceId) {
        Map<String, List<String>> showColumnsMap = new HashMap<>();
        /* 查询表格显示列配置 */
        List<ReportInstanceTableColumnVo> columnList = reportInstanceMapper.getReportInstanceTableColumnList(reportInstanceId);
        if (CollectionUtils.isNotEmpty(columnList)) {
            /* 根据tableId分组 */
            Map<String, List<ReportInstanceTableColumnVo>> columnMap = columnList.stream().collect(Collectors.groupingBy(ReportInstanceTableColumnVo::getTableId));
            /* 根据sort排序并取出字段名，组装成tableId与字段列表的map */
            for (Map.Entry<String, List<ReportInstanceTableColumnVo>> entry : columnMap.entrySet()) {
                List<String> columns = entry.getValue().stream().sorted(Comparator.comparing(ReportInstanceTableColumnVo::getSort)).map(ReportInstanceTableColumnVo::getColumn).collect(Collectors.toList());
                showColumnsMap.put(entry.getKey(), columns);
            }
        }
        return showColumnsMap;
    }

    private List<Map<String, Object>> selectTableColumns(Map<String, List<String>> showColumnsMap, SelectVo select, List<Map<String, Object>> tmpList) {
        List<String> showColumnList = showColumnsMap.get(select.getId());
        /* 筛选表格显示列 */
        for (Map<String, Object> map : tmpList) {
            map.entrySet().removeIf(stringObjectEntry -> !showColumnList.contains(stringObjectEntry.getKey()));
        }
        /* 排序 */
        List<Map<String, Object>> sqList = new ArrayList<>();
        for (Map<String, Object> map : tmpList) {
            Map<String, Object> _map = new LinkedHashMap<>();
            for (String s : showColumnList) {
                _map.put(s, map.get(s));
            }
            sqList.add(_map);
        }
        return sqList;
    }

    private List<Map<String, Object>> selectTableColumns(List<String> showColumnList, List<Map<String, Object>> tmpList) {
        /* 筛选表格显示列 */
        for (Map<String, Object> map : tmpList) {
            map.entrySet().removeIf(stringObjectEntry -> !showColumnList.contains(stringObjectEntry.getKey()));
        }
        /* 排序 */
        List<Map<String, Object>> sqList = new ArrayList<>();
        for (Map<String, Object> map : tmpList) {
            Map<String, Object> _map = new LinkedHashMap<>();
            for (String s : showColumnList) {
                _map.put(s, map.get(s));
            }
            sqList.add(_map);
        }
        return sqList;
    }

    private Map<String, List> wrapResultMapToMap(ResultMapVo resultMapVo, Map<String, Object> result, Map<String, List> returnMap) {
        StringBuilder key = new StringBuilder();
        List<Map<String, Object>> resultList = null;
        if (resultMapVo.getGroupByList() != null && resultMapVo.getGroupByList().size() > 0) {
            for (int i = 0; i < resultMapVo.getGroupByList().size(); i++) {
                key.append(result.get(resultMapVo.getGroupByList().get(i)));
                if (i < resultMapVo.getGroupByList().size() - 1) {
                    key.append("-");
                }
            }
        } else {
            return null;
        }
        Map<String, Object> newResult = new HashMap<>();
        if (!key.toString().equals("") && returnMap.containsKey(key.toString())) {
            resultList = returnMap.get(key.toString());
        } else {
            resultList = new ArrayList<>();
            returnMap.put(key.toString(), resultList);
        }

        // Iterator<Map.Entry<String, Object>> iter =
        // result.entrySet().iterator();
        for (String str : resultMapVo.getPropertyList()) {
            newResult.put(str, result.get(str));
        }
        resultList.add(newResult);
        return returnMap;
    }

    /**
     * 判断这一条数据是否已经存在
     *
     * @param resultMapVo 结果数据
     * @param resultList  结果数据
     * @param result      结果数据
     * @param checkMap    中途结果
     * @return 结果
     */
    private Boolean isExists(ResultMapVo resultMapVo, List<Map<String, Object>> resultList, Map<String, Object> result, Map<String, Map<String, Object>> checkMap) {
        boolean isExists = false;
        StringBuilder key = new StringBuilder();
        if (resultList == null) {
            resultList = new ArrayList<Map<String, Object>>();
        }
        if (resultMapVo.getGroupByList() != null && resultMapVo.getGroupByList().size() > 0) {
            for (int i = 0; i < resultMapVo.getGroupByList().size(); i++) {
                key.append(result.get(resultMapVo.getGroupByList().get(i)));
                if (i < resultMapVo.getGroupByList().size() - 1) {
                    key.append("-");
                }
            }
        } else if (resultMapVo.getPropertyList() != null && resultMapVo.getPropertyList().size() > 0) {
            for (int i = 0; i < resultMapVo.getPropertyList().size(); i++) {
                key.append(result.get(resultMapVo.getPropertyList().get(i)));
                if (i < resultMapVo.getPropertyList().size() - 1) {
                    key.append("-");
                }
            }
        }

        //System.out.println(key);
        if (!key.toString().equals("") && checkMap.containsKey(key.toString())) {
            isExists = true;
        }
        resultMapVo.setKey(key.toString());
        return isExists;
    }

    private List<Map<String, Object>> resultMapRecursion(ResultMapVo resultMapVo, List<Map<String, Object>> resultList,
                                                         Map<String, Object> result, Map<String, Map<String, Object>> checkMap) {
        boolean isExists = isExists(resultMapVo, resultList, result, checkMap);
        String key = resultMapVo.getKey();
        Map<String, Object> newResult = null;
        if (!isExists) {
            newResult = new HashMap<>();
            newResult.put("UUID", key);
            checkMap.put(key, newResult);
            boolean isAllColumnEmpty = true;
            for (String str : resultMapVo.getPropertyList()) {
                boolean needReadFile = false, needEncode = false;
                String tmp = str;
                if (str.contains("CONTENT_PATH:")) {// 读取文件内容
                    str = str.replace("CONTENT_PATH:", "");
                    needReadFile = true;
                }
                if (str.contains("ENCODE_HTML:")) {// 转义
                    str = str.replace("ENCODE_HTML:", "");
                    needEncode = true;
                }
                // FIXME 读取文件内容的字段需要补充实现，建议改成策略模式，不要用if else
                if (!needReadFile) {
                    if (needEncode) {
                        // newResult.put(str, encodeHtml(HtmlUtil.clearStringHTML((result.get(tmp) ==
                        // null ? "" : result.get(tmp).toString()))));
                    } else {
                        newResult.put(str, result.get(tmp));
                    }
                } else {
                    if (needEncode) {
                        // newResult.put(str,
                        // encodeHtml(Toolkit.clearStringHTML((FileWorker.readContent(result.get(tmp) ==
                        // null ? "" : result.get(tmp).toString())))));
                    } else {
                        // newResult.put(str,
                        // Toolkit.clearStringHTML(FileWorker.readContent(result.get(tmp) == null ? "" :
                        // result.get(tmp).toString())));
                    }
                }
                if (result.get(tmp) != null) {
                    isAllColumnEmpty = false;
                }
            }
            if (resultMapVo.getResultMap() != null) {
                for (Map.Entry<String, ResultMapVo> entry : resultMapVo.getResultMap().entrySet()) {
                    Map<String, Map<String, Object>> subCheckMap = new HashMap<>();
                    newResult.put("CHECKMAP-" + entry.getKey(), subCheckMap);
                    newResult.put(entry.getKey(), resultMapRecursion(entry.getValue(),
                            new ArrayList<>(), result, subCheckMap));
                }
            }
            if (!isAllColumnEmpty) {
                resultList.add(newResult);
            }
            resultMapVo.setIndex(resultMapVo.getIndex() + 1);
        } else {
            newResult = checkMap.get(key);
            if (resultMapVo.getResultMap() != null) {
                for (Map.Entry<String, ResultMapVo> entry : resultMapVo.getResultMap().entrySet()) {
                    resultMapRecursion(entry.getValue(), (List<Map<String, Object>>) newResult.get(entry.getKey()),
                            result, (Map<String, Map<String, Object>>) newResult.get("CHECKMAP-" + entry.getKey()));
                }
            }
        }
        return resultList;
    }

    public int deleteReportById(Long reportId) {
        reportMapper.deleteReportParamByReportId(reportId);
        reportMapper.deleteReportAuthByReportId(reportId);
        reportMapper.deleteReportById(reportId);
        return 1;
    }

    @Override
    public Map<String, Object> getQuerySqlResult(ReportVo reportVo, JSONObject paramMap, Map<String, List<String>> showColumnsMap) {
        return getQuerySqlResult(reportVo, paramMap, showColumnsMap, new ArrayList<>());
    }

    @Override
    public Map<String, Object> getQuerySqlResult(ReportVo reportVo, JSONObject paramMap, Map<String, List<String>> showColumnsMap, List<SqlInfo> tableList) {
        Map<String, Object> resultMap = new HashMap<>();
        if (StringUtils.isBlank(reportVo.getSql())) {
            return resultMap;
        }
        SqlRunner sqlRunner = new SqlRunner(reportVo.getSql(), "reportId_" + reportVo.getId());
        List<SqlInfo> sqlInfoList = sqlRunner.getAllSqlInfoList(paramMap);
        for (SqlInfo sqlInfo : sqlInfoList) {
            for (SqlInfo needPageTable : tableList) {
                if (Objects.equals(sqlInfo.getId(), needPageTable.getId())) {
                    sqlInfo.setNeedPage(needPageTable.getNeedPage());
                    sqlInfo.setPageSize(needPageTable.getPageSize());
                }
            }
        }
        Map<String, Object> pageMap = new HashMap<>();
        Map<String, Long> timeMap = new HashMap<>();
        BasePageVo basePageVo = new BasePageVo();
        for (SqlInfo sqlInfo : sqlInfoList) {
            // 如果SQL设置了延迟加载，第一次访问时不主动获取数据
//            if (isFirst) {
//                continue;
//            }
            long sqlTimeStart = System.currentTimeMillis();
            if (sqlInfo.getNeedPage()) {
                basePageVo.setPageSize(sqlInfo.getPageSize());
                PageRowBounds rowBounds = new PageRowBounds(basePageVo.getStartNum(), basePageVo.getPageSize());
                List list = sqlRunner.runSqlById(sqlInfo, paramMap, rowBounds);
                if (CollectionUtils.isNotEmpty(list)) {
                    resultMap.put(sqlInfo.getId(), list);
                    timeMap.put("SQL_" + sqlInfo.getId() + "_SIZE", (long) list.size());
                }
                basePageVo.setRowNum(rowBounds.getRowNum());
                JSONObject pageObj = new JSONObject();
                pageObj.put("rowNum", basePageVo.getRowNum());
                pageObj.put("currentPage", basePageVo.getCurrentPage());
                pageObj.put("pageSize", basePageVo.getPageSize());
                pageObj.put("pageCount", basePageVo.getPageCount());
                pageObj.put("tableId", sqlInfo.getId());
                pageMap.put(sqlInfo.getId(), pageObj);
            } else {
                List list = sqlRunner.runSqlById(sqlInfo, paramMap);
                if (CollectionUtils.isNotEmpty(list)) {
                    resultMap.put(sqlInfo.getId(), list);
                    timeMap.put("SQL_" + sqlInfo.getId() + "_SIZE", (long) list.size());
                }
            }
            timeMap.put("SQL_" + sqlInfo.getId(), System.currentTimeMillis() - sqlTimeStart);
        }
        resultMap.put(ReportConfig.REPORT_PAGE_MAP_KEY, pageMap);
        for (SqlInfo sqlInfo : sqlInfoList) {
            Object object = resultMap.get(sqlInfo.getId());
            if (object == null) {
                continue;
            }
            if (object instanceof List) {
                List<Map<String, Object>> resultList = new ArrayList<>();
                List list = (List) object;
                for (Object obj : list) {
                    if (obj instanceof Map) {
                        Map<String, Object> hashMap = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entity : ((Map<?, ?>) obj).entrySet()) {
                            hashMap.put((String) entity.getKey(), entity.getValue());
                        }
                        resultList.add(hashMap);
                    }
                }
                /* 如果存在表格且存在表格显示列的配置，则筛选显示列并排序
                   showColumnMap:key->表格ID;value->配置的表格显示列
                */
                if (MapUtils.isNotEmpty(showColumnsMap)) {
                    List<String> showColumnList = showColumnsMap.get(sqlInfo.getId());
                    if (showColumnList != null) {
                        List<Map<String, Object>> sqList = selectTableColumns(showColumnList, resultList);
                        resultList = sqList;
                    }
                }
                resultMap.put(sqlInfo.getId(), resultList);
                timeMap.put("SQL_" + sqlInfo.getId() + "_SIZE", (long) resultList.size());
            }
        }
        resultMap.put(ReportConfig.REPORT_TIME_MAP_KEY, timeMap);
        return resultMap;
    }

    @Override
    public Map<String, Object> getQuerySqlResultById(String id, ReportVo reportVo, JSONObject paramMap, Map<String, List<String>> showColumnsMap) {
        Map<String, Object> resultMap = new HashMap<>();
        SqlRunner sqlRunner = new SqlRunner(reportVo.getSql(), "reportId_" + reportVo.getId());
        List<SqlInfo> sqlInfoList = sqlRunner.getAllSqlInfoList(paramMap);
        Map<String, Object> pageMap = new HashMap<>();
        Map<String, Long> timeMap = new HashMap<>();
        for (SqlInfo sqlInfo : sqlInfoList) {
            if (Objects.equals(sqlInfo.getId(), id)) {
                long sqlTimeStart = System.currentTimeMillis();
                BasePageVo basePageVo = new BasePageVo();
                Integer currentPage = paramMap.getInteger("currentPage");
                if (currentPage != null) {
                    basePageVo.setCurrentPage(currentPage);
                }
                Integer pageSize = paramMap.getInteger("pageSize");
                if (pageSize != null) {
                    basePageVo.setPageSize(pageSize);
                }
                PageRowBounds rowBounds = new PageRowBounds(basePageVo.getStartNum(), basePageVo.getPageSize());
                List list = sqlRunner.runSqlById(sqlInfo, paramMap, rowBounds);
                if (CollectionUtils.isNotEmpty(list)) {
                    resultMap.put(sqlInfo.getId(), list);
                    timeMap.put("SQL_" + sqlInfo.getId() + "_SIZE", (long) list.size());
                }
                basePageVo.setRowNum(rowBounds.getRowNum());
                JSONObject pageObj = new JSONObject();
                pageObj.put("rowNum", basePageVo.getRowNum());
                pageObj.put("currentPage", basePageVo.getCurrentPage());
                pageObj.put("pageSize", basePageVo.getPageSize());
                pageObj.put("pageCount", basePageVo.getPageCount());
                pageObj.put("tableId", sqlInfo.getId());
                pageMap.put(sqlInfo.getId(), pageObj);
                timeMap.put("SQL_" + sqlInfo.getId(), System.currentTimeMillis() - sqlTimeStart);
            }
        }
        resultMap.put(ReportConfig.REPORT_PAGE_MAP_KEY, pageMap);
        Object object = resultMap.get(id);
        if (object == null) {
            return resultMap;
        }
        if (object instanceof List) {
            List<Map<String, Object>> resultList = new ArrayList<>();
            List list = (List) object;
            for (Object obj : list) {
                if (obj instanceof Map) {
                    Map<String, Object> hashMap = new HashMap<>();
                    for (Map.Entry<?, ?> entity : ((Map<?, ?>) obj).entrySet()) {
                        hashMap.put((String) entity.getKey(), entity.getValue());
                    }
                    resultList.add(hashMap);
                }
            }
                /* 如果存在表格且存在表格显示列的配置，则筛选显示列并排序
                   showColumnMap:key->表格ID;value->配置的表格显示列
                */
            if (MapUtils.isNotEmpty(showColumnsMap)) {
                List<String> showColumnList = showColumnsMap.get(id);
                if (showColumnList != null) {
                    List<Map<String, Object>> sqList = selectTableColumns(showColumnList, resultList);
                    resultList = sqList;
                }
            }
            resultMap.put(id, resultList);
            timeMap.put("SQL_" + id + "_SIZE", (long) resultList.size());
        }
        resultMap.put(ReportConfig.REPORT_TIME_MAP_KEY, timeMap);
        return resultMap;
    }

    @Override
    public void validateReportParamList(List<ReportParamVo> paramList) {
        if (CollectionUtils.isNotEmpty(paramList)) {
            Set<String> keySet = new HashSet<>();
            for (int i = 0; i < paramList.size(); i++) {
                ReportParamVo paramVo = paramList.get(i);
                String key = paramVo.getName();
                if (StringUtils.isBlank(key)) {
                    throw new ReportParamNameRepeatsException(i);
                }
                if (keySet.contains(key)) {
                    throw new ReportParamNameRepeatsException(i);
                } else {
                    keySet.add(key);
                }
            }
        }
    }

    @Override
    public Workbook getReportWorkbook(String content) {
        Map<String, List<Map<String, Object>>> tableMap = getTableListByHtml(content);
        if (MapUtils.isNotEmpty(tableMap)) {
            ExcelBuilder builder = new ExcelBuilder(SXSSFWorkbook.class);
            for (Map.Entry<String, List<Map<String, Object>>> entry : tableMap.entrySet()) {
                String tableName = entry.getKey().trim();
                List<Map<String, Object>> tableBody = entry.getValue();
                Map<String, Object> map = tableBody.get(0);
                List<String> headerList = new ArrayList<>();
                List<String> columnList = new ArrayList<>();
                for (String key : map.keySet()) {
                    headerList.add(key);
                    columnList.add(key);
                }
                SheetBuilder sheetBuilder = builder.withBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT)
                        .withHeadFontColor(HSSFColor.HSSFColorPredefined.WHITE)
                        .withHeadBgColor(HSSFColor.HSSFColorPredefined.DARK_BLUE)
                        .withColumnWidth(30)
                        .addSheet(tableName)
                        .withHeaderList(headerList)
                        .withColumnList(columnList);
                sheetBuilder.addDataList(tableBody);
            }
            return builder.build();
        } else {
            // 考虑报表内容配置有自定义表格
            return getReportWorkbookByTemplateTable(content);
        }
    }

    /**
     * 带有tableName属性的table标签才会被识别为表格
     * table标签遵守严格的DOM规范
     * e.g:
     * <table tableName="按月统计">
     *     <thead>
     *         <tr>
     *             <th>月</th>
     *             <th>工单数量</th>
     *         </tr>
     *     </thead>
     *     <tbody>
     *         <tr>
     *             <td>2022-06</td>
     *             <td>22</td>
     *         </tr>
     *         <tr>
     *             <td>2022-05</td>
     *             <td>26</td>
     *         </tr>
     *         <tr>
     *             <td>2022-04</td>
     *             <td>3</td>
     *         </tr>
     *     </tbody>
     * </table>
     *
     * @param content
     * @return
     */
    private Map<String, List<Map<String, Object>>> getTableListByHtml(String content) {
        Map<String, List<Map<String, Object>>> tableMap = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(content)) {
            Document doc = Jsoup.parse(content);
            /** 抽取所有带有tableName属性的元素 */
            Elements elements = doc.getElementsByAttribute("tableName");
            if (CollectionUtils.isNotEmpty(elements)) {
                for (Element element : elements) {
                    String tableName = element.attr("tableName");
                    if (StringUtils.isNotBlank(tableName)) {
                        Elements ths = element.select("[tableName]>thead>tr>th");
                        Elements tbodys = element.select("[tableName]>tbody");
                        if (CollectionUtils.isNotEmpty(ths) && CollectionUtils.isNotEmpty(tbodys)) {
                            Iterator<Element> thIterator = ths.iterator();
                            List<String> thValueList = new ArrayList<>();
                            /** 抽取表头数据 */
                            while (thIterator.hasNext()) {
                                String text = thIterator.next().ownText();
                                thValueList.add(text);
                            }
                            Element tbody = tbodys.first();
                            Elements trs = tbody.children();
                            if (CollectionUtils.isNotEmpty(trs) && CollectionUtils.isNotEmpty(thValueList)) {
                                Iterator<Element> trIterator = trs.iterator();
                                List<Map<String, Object>> valueList = new ArrayList<>();
                                /** 抽取表格内容数据，与表头key-value化存储 */
                                while (trIterator.hasNext()) {
                                    Element tds = trIterator.next();
                                    Elements tdEls = tds.children();
                                    List<Element> tdList = tdEls.subList(0, tdEls.size());
                                    Map<String, Object> map = new LinkedHashMap<>();
                                    for (int i = 0; i < tdList.size(); i++) {
                                        map.put(thValueList.get(i), tdList.get(i).text()); // text()返回剥离HTML标签的内容
                                    }
                                    valueList.add(map);
                                }
                                if (tableMap.containsKey(tableName)) {
                                    // 存在同名表格，增加空格区分存进Map
                                    do {
                                        tableName += " ";
                                    } while (tableMap.containsKey(tableName));
                                }
                                tableMap.put(tableName, valueList);
                            }
                        }
                    }
                }
            }
        }
        return tableMap;
    }

    /**
     * 解析内容配置里的不规范表格
     * 循环填充方式生成excel，兼容存在rowspan、colspan的表格
     *
     * @param content
     * @return
     */
    private Workbook getReportWorkbookByTemplateTable(String content) {
        SXSSFWorkbook workbook = new SXSSFWorkbook();
        if (StringUtils.isNotBlank(content)) {
            Document doc = Jsoup.parse(content);
            Elements tableElements = doc.getElementsByTag("table");
            if (CollectionUtils.isEmpty(tableElements)) {
                // 没有table标签
                throw new TableNotFoundInReportException();
            }

            List<Map<Integer, Map<Integer, String>>> sheetList = new ArrayList<>();
            List<JSONArray> mergeList = new ArrayList<>();
            Map<Integer, Map<Integer, String>> rowList = null;
            Map<Integer, String> columnList = null;
            JSONArray mergeJsonArray = null;
            JSONObject mergeJsonObj = null;
            boolean hasTableHead = false;
            for (Element t : tableElements) {
                // i是sheet号
                Elements trList = t.select("tr");

                mergeJsonArray = new JSONArray();
                rowList = new HashMap<>();

                for (int j = 0; j < trList.size(); j++) {
                    // 遍历表格内所有行
                    Element r = trList.get(j);

                    columnList = rowList.computeIfAbsent(j, k -> new HashMap<>());

                    List<Element> tdList = new ArrayList<>();
                    tdList.addAll(r.select("th,td"));

                    for (int k = 0; k < tdList.size(); k++) {
                        // 遍历此行内所有列
                        Element d = tdList.get(k);
                        if (j == 0 && k == 0 && "th".equals(d.tag().normalName())) {
                            // 存在表头标题行
                            hasTableHead = true;
                        }

                        Element element = d.clone();
                        int columnNum = getRightColumnIndex(columnList, k);
                        // Excel 最大的 cell size 为 32767
                        if (element.text().length() >= 32000) {
                            columnList.put(columnNum, element.text().toString().substring(0, 32000));
                        } else {
                            String trimStr = null;
                            Elements childrenEle = element.children();
                            // 存在换行符
                            for (Element o : childrenEle) {
                                if ("br".equals(o.tag().normalName())) {
                                    if (trimStr == null) {
                                        trimStr = element.html().trim();
                                    }
                                    trimStr = trimStr.replaceAll("\\s*" + o + "+\\s*", "\n");
                                }
                            }
                            if (trimStr == null) {
                                trimStr = element.text().trim();
                            }
                            columnList.put(columnNum, trimStr);
                        }

                        // 为了合并单元格 填充空值 以防重合
                        int colspan = 1;
                        int rowspan = 1;
                        if (StringUtils.isNotBlank(d.attr("colspan"))) {
                            colspan = Integer.parseInt(d.attr("colspan").trim());
                        }
                        if (StringUtils.isNotBlank(d.attr("rowspan"))) {
                            rowspan = Integer.parseInt(d.attr("rowspan").trim());
                        }

                        // 填充单元格
                        // 先填充本行
                        if (colspan > 1 || rowspan > 1) {
                            mergeJsonObj = new JSONObject();
                            mergeJsonObj.put("firstRow", j);
                            mergeJsonObj.put("lastRow", j + rowspan - 1);
                            mergeJsonObj.put("firstCol", k);
                            mergeJsonObj.put("lastCol", k + colspan - 1);
                            //合并列
                            if (colspan > 1) {
                                for (int m = k + 1; m < colspan + k; m++) {
                                    mergeJsonObj = new JSONObject();
                                    mergeJsonObj.put("firstRow", j);
                                    mergeJsonObj.put("lastRow", j + rowspan - 1);
                                    mergeJsonObj.put("firstCol", columnList.size() - 1);
                                    mergeJsonObj.put("lastCol", columnList.size() + colspan - 2);
                                    if (CollectionUtils.isEmpty(mergeJsonArray) || mergeJsonArray.getJSONObject(mergeJsonArray.size() - 1).getInteger("lastCol") < columnList.size() - 1) {
                                        mergeJsonArray.add(mergeJsonObj);
                                    }
                                    columnList.put(columnList.size(), "");
                                }
                            } else {
                                mergeJsonArray.add(mergeJsonObj);
                            }

                            // 合并行
                            // 再填充后面行
                            if (rowspan > 1) {
                                Map<Integer, String> nextColumnList = null;
                                for (int p = j + 1; p < rowspan + j; p++) {
                                    // 下一个行号为 p
                                    nextColumnList = rowList.computeIfAbsent(p, k1 -> new HashMap<>());
                                    for (int m = k; m < colspan + k; m++) {
                                        nextColumnList.put(m, "");
                                    }
                                }
                            }

                        }
                    }
                }
                mergeList.add(mergeJsonArray);
                sheetList.add(rowList);
            }

            // 生成sheet及填充数据
            CellStyle headStyle = getDefualtHeadCellStyle(workbook);
            CellStyle style = getDefualtCellStyle(workbook);
            if (CollectionUtils.isNotEmpty(sheetList)) {
                SXSSFSheet sheet = null;
                SXSSFRow row = null;
                SXSSFCell cell = null;
                for (int index = 0; index < sheetList.size(); index++) {
                    Map<Integer, Map<Integer, String>> table = sheetList.get(index);

                    sheet = workbook.createSheet();
                    for (int i = 0; i < table.size(); i++) {
                        Map<Integer, String> tr = table.get(i);
                        row = sheet.createRow(i);
                        for (int j = 0; j < tr.size(); j++) {
                            String cellValue = tr.get(j);

                            cell = row.createCell((short) j);
                            if (i == 0 && hasTableHead) {
                                cell.setCellStyle(headStyle);
                            } else {
                                cell.setCellStyle(style);
                            }
                            cell.setCellValue(cellValue);
                        }
                    }
                    // 合并单元格
                    mergeJsonArray = mergeList.get(index);
                    for (int p = 0; p < mergeJsonArray.size(); p++) {
                        mergeJsonObj = mergeJsonArray.getJSONObject(p);
                        int firstRow = mergeJsonObj.getInteger("firstRow");
                        int lastRow = mergeJsonObj.getInteger("lastRow");
                        int firstCol = mergeJsonObj.getInteger("firstCol");
                        int lastCol = mergeJsonObj.getInteger("lastCol");

                        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
                    }
                }
            }
        }
        return workbook;
    }

    private CellStyle getDefualtCellStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBottomBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setTopBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setLeftBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setRightBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());

        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    private CellStyle getDefualtHeadCellStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFillBackgroundColor(HSSFColor.HSSFColorPredefined.DARK_BLUE.getIndex());
        style.setFillForegroundColor(HSSFColor.HSSFColorPredefined.DARK_BLUE.getIndex());

        Font font = wb.createFont();
        font.setColor(HSSFColor.HSSFColorPredefined.WHITE.getIndex());
        style.setFont(font);
        style.setBottomBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setTopBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setLeftBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());
        style.setRightBorderColor(HSSFColor.HSSFColorPredefined.GREY_40_PERCENT.getIndex());

        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    private int getRightColumnIndex(Map<Integer, String> columnList, int k) {
        int columnNum;
        if (!columnList.containsKey(k)) {
            columnNum = k;
        } else {
            columnNum = getRightColumnIndex(columnList, ++k);
        }
        return columnNum;
    }
}
