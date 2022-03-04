/*
 * Copyright(c) 2022 TechSure Co., Ltd. All Rights Reserved.
 * 本内容仅限于深圳市赞悦科技有限公司内部传阅，禁止外泄以及用于其他的商业项目。
 */

package codedriver.module.report.service;

import codedriver.framework.asynchronization.thread.CodeDriverThread;
import codedriver.framework.asynchronization.threadpool.CachedThreadPool;
import codedriver.framework.report.dto.*;
import codedriver.framework.report.enums.DatabaseVersion;
import codedriver.framework.report.enums.Mode;
import codedriver.framework.report.enums.Status;
import codedriver.framework.report.exception.DatabaseVersionNotFoundException;
import codedriver.framework.report.exception.ReportDataSourceIsSyncingException;
import codedriver.framework.report.exception.ReportDataSourceSyncException;
import codedriver.module.report.dao.mapper.ReportConnectionMapper;
import codedriver.module.report.dao.mapper.ReportDataSourceAuditMapper;
import codedriver.module.report.dao.mapper.ReportDataSourceDataMapper;
import codedriver.module.report.dao.mapper.ReportDataSourceMapper;
import codedriver.module.report.dto.ReportConnectionVo;
import codedriver.module.report.dto.SelectVo;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportDataSourceServiceImpl implements ReportDataSourceService {
    int FETCH_SIZE = 1000;
    static Logger logger = LoggerFactory.getLogger(ReportDataSourceServiceImpl.class);
    @Resource
    private DataSource dataSource;

    @Resource
    private ReportConnectionMapper reportConnectionMapper;

    @Resource
    private ReportDataSourceMapper reportDataSourceMapper;

    @Resource
    private ReportDataSourceDataMapper reportDataSourceDataMapper;

    @Resource
    private ReportDataSourceAuditMapper reportDataSourceAuditMapper;

    private static boolean evaluateExpression(String expression, Map<String, Object> paramMap) {
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine engine = mgr.getEngineByName("nashorn");
        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
            engine.put(entry.getKey(), entry.getValue());
        }
        try {
            return Boolean.parseBoolean(engine.eval(expression).toString());
        } catch (ScriptException e) {
            logger.error(e.getMessage(), e);
        }
        return false;
    }


    private SelectVo getSqlFromDataSource(ReportDataSourceVo reportDataSourceVo) throws DocumentException {
        Document document = DocumentHelper.parseText(reportDataSourceVo.getXml());
        Element root = document.getRootElement();
        Element selectElement = root.element("select");
        String regex_param = "#\\{([^}]+?)}";
        String regex_dollar_param = "\\$\\{([^}]+?)}";
        String[] replace_regex = new String[]{"<[/]?if[^>]*?>", "<[/]?select[^>]*?>", "<[/]?forEach[^>]*?>", "<[/]?ifNotNull[^>]*?>", "<[/]?ifNull[^>]*?>", "<[/]?forEach[^>]*?>", "\\<\\!\\[CDATA\\[", "\\]\\]\\>"};
        JSONObject paramMap = new JSONObject();
        if (CollectionUtils.isNotEmpty(reportDataSourceVo.getConditionList())) {
            for (ReportDataSourceConditionVo conditionVo : reportDataSourceVo.getConditionList()) {
                paramMap.put(conditionVo.getName(), conditionVo.getValue());
            }
        }
        SelectVo selectVo = new SelectVo();
        if (selectElement != null) {
            List<Element> ifElList = selectElement.elements("if");
            if (CollectionUtils.isNotEmpty(ifElList)) {
                List<Element> removeObj = new ArrayList<>();
                for (Element ifEl : ifElList) {
                    String testExp = ifEl.attributeValue("test");
                    if (!evaluateExpression(testExp, paramMap)) {
                        removeObj.add(ifEl);
                    }
                }
                for (Element i : removeObj) {
                    ifElList.remove(i);
                }
            }

            List<Element> forEachList = selectElement.elements("forEach");
            if (CollectionUtils.isNotEmpty(forEachList)) {
                List<Element> removeObj = new ArrayList<>();
                for (Element foreachEl : forEachList) {
                    boolean hasParam = false;
                    Object p = paramMap.get(foreachEl.attributeValue("parameter"));
                    if (p != null) {
                        hasParam = true;
                        String separator = foreachEl.attributeValue("separator");
                        String orgText = foreachEl.getText();
                        StringBuilder newText = new StringBuilder();
                        if (p instanceof String) {
                            newText = new StringBuilder(orgText);
                        } else if (p instanceof String[]) {
                            for (int pi = 0; pi < ((String[]) p).length; pi++) {
                                String replaceValue = orgText.replace("#{" + foreachEl.attributeValue("parameter") + "}", "#{" + foreachEl.attributeValue("parameter") + "#" + pi + "}");
                                if (!newText.toString().contains(replaceValue)) {//防止参数重复拼接
                                    newText.append(replaceValue).append(separator);
                                }
                            }
                        } else if (p instanceof List<?>) {
                            int i = 0;
                            for (String pi : (List<String>) p) {
                                String replaceValue = orgText.replace("#{" + foreachEl.attributeValue("parameter") + "}", "#{" + foreachEl.attributeValue("parameter") + "#" + pi + "}");
                                if (newText.toString().contains(replaceValue)) {//防止参数重复拼接
                                    newText.append(replaceValue).append(separator);
                                }
                                i++;
                            }
                        }
                        //统一处理
                        if (newText.toString().endsWith(separator)) {
                            newText = new StringBuilder(newText.substring(0, newText.length() - separator.length()));
                        }
                        foreachEl.setText(newText.toString());
                    }
                    if (!hasParam) {
                        removeObj.add(foreachEl);
                    }
                }
                for (Element i : removeObj) {
                    forEachList.remove(i);
                }
            }
            String result = selectElement.asXML();
            StringBuffer temp;
            Pattern pattern;
            Matcher matcher;
            for (String regex : replace_regex) {
                temp = new StringBuffer();
                pattern = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                matcher = pattern.matcher(result);
                while (matcher.find()) {
                    matcher.appendReplacement(temp, "");
                }
                matcher.appendTail(temp);
                result = temp.toString();
            }

            temp = new StringBuffer();
            pattern = Pattern.compile(regex_param, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(result);
            List<Object> paramList = new ArrayList<>();
            while (matcher.find()) {
                String key = matcher.group(1);
                if (key.contains("#")) {
                    key = key.substring(0, key.lastIndexOf("#"));
                }
                Object pp = paramMap.get(key);
                if (pp instanceof String) {
                    matcher.appendReplacement(temp, "?");
                    paramList.add(pp);
                } else if (pp instanceof String[]) {
                    if (matcher.group(1).contains("#")) {
                        int s = Integer.parseInt(matcher.group(1).substring(matcher.group(1).lastIndexOf("#") + 1));
                        String[] ps = (String[]) pp;
                        if (ps[s] != null) {
                            paramList.add(ps[s]);
                            matcher.appendReplacement(temp, "?");
                        }
                    } else {
                        matcher.appendReplacement(temp, "?");
                        paramList.add(((String[]) pp)[0]);
                    }
                } else if (pp instanceof List<?>) {
                    if (matcher.group(1).contains("#")) {
                        int s = Integer.parseInt(matcher.group(1).substring(matcher.group(1).lastIndexOf("#") + 1));
                        List<String> ps = (List<String>) pp;
                        if (ps.get(s) != null) {
                            paramList.add(ps.get(s));
                            matcher.appendReplacement(temp, "?");
                        }
                    } else {
                        matcher.appendReplacement(temp, "?");
                        paramList.add(((List<String>) pp).get(0));
                    }
                }
            }
            matcher.appendTail(temp);

            pattern = Pattern.compile(regex_dollar_param, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            result = temp.toString();
            temp = new StringBuffer();
            matcher = pattern.matcher(result);
            while (matcher.find()) {
                String key = matcher.group(1);
                Object pp = paramMap.get(key);
                if (pp instanceof String) {
                    matcher.appendReplacement(temp, (String) pp);
                } else if (pp instanceof String[]) {
                    if (matcher.group(1).contains("$")) {
                        int s = Integer.parseInt(matcher.group(1).substring(matcher.group(1).lastIndexOf("$") + 1));
                        String[] ps = (String[]) pp;
                        if (ps[s] != null) {
                            matcher.appendReplacement(temp, ps[s]);
                        }
                    } else {
                        matcher.appendReplacement(temp, ((String[]) pp)[0]);
                    }
                } else if (pp instanceof List<?>) {
                    if (matcher.group(1).contains("$")) {
                        int s = Integer.parseInt(matcher.group(1).substring(matcher.group(1).lastIndexOf("$") + 1));
                        List<String> ps = (List<String>) pp;
                        if (ps.get(s) != null) {
                            matcher.appendReplacement(temp, ps.get(s));
                        }
                    } else {
                        matcher.appendReplacement(temp, ((List<String>) pp).get(0));
                    }
                }
            }
            matcher.appendTail(temp);
            selectVo.setParamList(paramList);
            selectVo.setSql(temp.toString());
            selectVo.setSql(selectVo.getSql().replace("&gt;", ">").replace("&lt;", "<"));
            selectVo.setParamMap(paramMap);
        }
        return selectVo;
    }

    private Connection getConnection(ReportDataSourceVo reportDataSourceVo) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (reportDataSourceVo.getConnectionId() != null) {
            ReportConnectionVo connectionVo = reportConnectionMapper.getConnectionById(reportDataSourceVo.getConnectionId());
            if (connectionVo != null) {
                DatabaseVersion version = DatabaseVersion.getVersion(connectionVo.getDatabaseVersion());
                if (version == null) {
                    throw new DatabaseVersionNotFoundException(connectionVo.getDatabaseVersion());
                }
                Class<?> clazz = Class.forName(version.getDriver());
                Driver driver = ((Driver) clazz.newInstance());
                Properties props = new Properties();
                if (StringUtils.isNoneBlank(connectionVo.getUsername())) {
                    props.put("user", connectionVo.getUsername());
                }
                if (StringUtils.isNotBlank(connectionVo.getPasswordPlain())) {
                    props.put("password", connectionVo.getPasswordPlain());
                }
                return driver.connect(connectionVo.getUrl(), props);
            }
        }
        //什么都没则返回默认连接
        return dataSource.getConnection();
    }

    @Override
    public void executeReportDataSource(ReportDataSourceVo dataSourceVo) {

        if (dataSourceVo != null && CollectionUtils.isNotEmpty(dataSourceVo.getFieldList())) {
            if (Objects.equals(dataSourceVo.getStatus(), Status.DOING.getValue())) {
                throw new ReportDataSourceIsSyncingException(dataSourceVo);
            }
            //更新数据源状态，写入审计信息
            dataSourceVo.setStatus(Status.DOING.getValue());
            reportDataSourceMapper.updateReportDataSourceStatus(dataSourceVo);
            ReportDataSourceAuditVo reportDataSourceAuditVo = new ReportDataSourceAuditVo();
            reportDataSourceAuditVo.setDataSourceId(dataSourceVo.getId());
            reportDataSourceAuditMapper.insertReportDataSourceAudit(reportDataSourceAuditVo);
            CachedThreadPool.execute(new CodeDriverThread("REPORT-DATASOURCE-SYNC") {
                @Override
                protected void execute() {
                    //如果是替换模式，则需要先清理数据
                    if (StringUtils.isNotBlank(dataSourceVo.getMode()) && dataSourceVo.getMode().equals(Mode.REPLACE.getValue())) {
                        reportDataSourceDataMapper.truncateTable(dataSourceVo);
                    }

                    Connection conn = null;
                    PreparedStatement queryStatement = null;
                    ResultSet resultSet = null;

                    try {
                        SelectVo select = getSqlFromDataSource(dataSourceVo);
                        conn = getConnection(dataSourceVo);
                        String sqlText = select.getSql();
                        queryStatement = conn.prepareStatement(sqlText, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                        queryStatement.setFetchSize(FETCH_SIZE);
                        queryStatement.setFetchDirection(ResultSet.FETCH_FORWARD);
                        if (dataSourceVo.getQueryTimeout() != null && dataSourceVo.getQueryTimeout() > 0) {
                            queryStatement.setQueryTimeout(dataSourceVo.getQueryTimeout());
                        }

                        if (CollectionUtils.isNotEmpty(select.getParamList())) {
                            for (int p = 0; p < select.getParamList().size(); p++) {
                                if (select.getParamList().get(p) instanceof String) {
                                    queryStatement.setObject(p + 1, select.getParamList().get(p));
                                } else {
                                    // 数组参数有待处理
                                    queryStatement.setObject(p + 1, ((String[]) select.getParamList().get(p))[0]);
                                }
                            }
                        }
                    /*
                      新增日志记录
                     */
                        if (logger.isInfoEnabled()) {
                            logger.info("REPORT RUN SQL::" + sqlText);
                        }

                        resultSet = queryStatement.executeQuery();

                        ResultSetMetaData metaData = resultSet.getMetaData();
                        Map<String, Integer> fieldMap = new HashMap<>();
                        for (int i = 1; i <= metaData.getColumnCount(); i++) {
                            fieldMap.put(metaData.getColumnName(i), i);
                        }

                        while (resultSet.next()) {
                            ReportDataSourceDataVo reportDataSourceDataVo = new ReportDataSourceDataVo(dataSourceVo.getId());
                            for (ReportDataSourceFieldVo fieldVo : dataSourceVo.getFieldList()) {
                                if (fieldMap.containsKey(fieldVo.getName())) {
                                    fieldVo.setValue(resultSet.getObject(fieldMap.get(fieldVo.getName())));
                                }
                                reportDataSourceDataVo.addField(fieldVo);
                            }
                            reportDataSourceDataMapper.insertDataSourceData(reportDataSourceDataVo);
                            reportDataSourceAuditVo.addCount();
                        }
                    } catch (SQLException | DocumentException | InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                        logger.error(e.getMessage(), e);
                        reportDataSourceAuditVo.setError(e.getMessage());
                        throw new ReportDataSourceSyncException(dataSourceVo, e);
                    } finally {
                        try {
                            if (resultSet != null) {
                                resultSet.close();
                            }
                            if (queryStatement != null) {
                                queryStatement.close();
                            }
                            if (conn != null) {
                                conn.close();
                            }
                        } catch (SQLException e) {
                            logger.error(e.getMessage(), e);
                        }
                        dataSourceVo.setStatus(Status.DONE.getValue());
                        int dataCount = reportDataSourceDataMapper.getDataSourceDataCount(dataSourceVo);
                        dataSourceVo.setDataCount(dataCount);
                        reportDataSourceMapper.updateReportDataSourceStatus(dataSourceVo);
                        reportDataSourceAuditMapper.updateReportDataSourceAudit(reportDataSourceAuditVo);
                    }
                }
            });
        }
    }
}
