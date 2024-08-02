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

package neatlogic.module.report.util;

import neatlogic.framework.util.javascript.JavascriptUtil;
import neatlogic.module.report.config.ReportConfig;
import neatlogic.module.report.constvalue.ActionType;
import neatlogic.module.report.widget.*;
import com.alibaba.fastjson.JSONObject;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

public class ReportFreemarkerUtil {
    private static final Log logger = LogFactory.getLog(ReportFreemarkerUtil.class);

    public static boolean evaluateExpression(String expression, Map<String, Object> paramMap) {
        ScriptEngine engine = JavascriptUtil.getEngine();
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


    public static void getFreemarkerContent(Map<String, Object> paramMap, Map<String, Object> reportMap, JSONObject filter, String content, Writer out) throws Exception {
        if (StringUtils.isNotBlank(content)) {
            long start = System.currentTimeMillis();
            Object timeMapObj = reportMap.remove(ReportConfig.REPORT_TIME_MAP_KEY);
            Object pageMapObj = reportMap.remove(ReportConfig.REPORT_PAGE_MAP_KEY);
            Map<String, Map<String, Object>> pageMap = null;
            if (pageMapObj instanceof Map) {
                pageMap = (Map<String, Map<String, Object>>) pageMapObj;
            }

            Configuration cfg = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
            cfg.setNumberFormat("0.##");
            cfg.setClassicCompatible(true);
            StringTemplateLoader stringLoader = new StringTemplateLoader();
            stringLoader.putTemplate("template", content);
            cfg.setTemplateLoader(stringLoader);
            Template temp;
            paramMap.put("drawTable", new DrawTable(reportMap, pageMap, filter));
            paramMap.put("drawBar", new DrawBar(reportMap, ActionType.VIEW.getValue()));
            paramMap.put("drawBarH", new DrawBarH(reportMap, ActionType.VIEW.getValue()));
            paramMap.put("drawLine", new DrawLine(reportMap, ActionType.VIEW.getValue()));
            paramMap.put("drawPie", new DrawPie(reportMap, ActionType.VIEW.getValue()));
            paramMap.put("drawStackedBar", new DrawStackedBar(reportMap, ActionType.VIEW.getValue()));
            paramMap.put("drawStackedBarH", new DrawStackedBarH(reportMap, ActionType.VIEW.getValue()));
//			paramMap.put("drawStackedBarLineH", new DrawStackedBarLineH(reportMap, ActionType.VIEW.getValue()));
//			paramMap.put("drawPagination", new DrawPagination(reportMap, true));

            try {
                temp = cfg.getTemplate("template", "utf-8");
                temp.process(paramMap, out);
            } catch (IOException | TemplateException e) {
                logger.error("freeMarker Code：" + content);
                logger.error("JSON Code：" + JSONObject.toJSONString(paramMap));
                logger.error(e.getMessage(), e);
                throw e;
            }

            // 统计报表执行耗时
            StringBuilder sqlTime = new StringBuilder();
            if (timeMapObj instanceof Map) {
                Map<String, Long> timeMap = (Map<String, Long>) timeMapObj;
                if (MapUtils.isNotEmpty(timeMap)) {
                    for (Map.Entry<String, Long> entry : timeMap.entrySet()) {
                        if (entry.getKey().startsWith("SQL_") && !entry.getKey().endsWith("_SIZE")) {
                            String sqlId = entry.getKey().replace("SQL_", "");
                            sqlTime.append(sqlId);
                            if (timeMap.containsKey(entry.getKey() + "_SIZE")) {
                                sqlTime.append("(").append(timeMap.get(entry.getKey() + "_SIZE")).append("条)");
                            }
                            sqlTime.append("(").append(entry.getValue()).append("ms); ");
                        }
                    }
                }
            }
            out.write("<div id=\"footTip\" style=\"margin-top: 5px; padding-top: 10px; padding-bottom: 5px; border-top: 1px solid #ddd; color: #999; text-align: right;\">");
            if (sqlTime.length() > 0) {
                out.write("数据库执行耗时：" + sqlTime);
            }
            out.write("模板渲染耗时：" + (System.currentTimeMillis() - start) + "ms; ");
            out.write("</div>");
        }
    }

    /*
     * "string":获取htm "print":下载
     */
    public static String getFreemarkerExportContent(Map<String, Object> paramMap, Map<String, Object> reportMap, JSONObject filter, String content, String actionType) throws IOException {
        StringWriter out = new StringWriter();
        out.write("<html xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">\n");
        out.write("<head>\n");
        out.write("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></meta>\n");
        out.write("<style type=\"text/css\">\n");
        out.write("html {font-family: \"PingFang SC\", \"Helvetica Neue\", \"思源黑体\", \"Microsoft YaHei\", \"黑体\", Helvetica;line-height: 1.42857143; color: #666666;font-size: 14px;}\n");
        out.write("table{width: 100%; max-width: 100%; margin-bottom: 10px; margin-top: 0;border-collapse:collapse;border-spacing:0;border-top:1px solid #ddd;}\n");
        out.write("th,td{padding: 8px; line-height: 1.42857143;  vertical-align: top; border-top: 1px solid #dddddd;}\n");
        out.write("th{text-align: left;color: #999999;}\n");
        out.write(".table-condensed th,.table-condensed td{padding: 5px;}\n");
        out.write("div.well {  min-height: 20px; padding: 19px; line-height: 1.8; border-radius: 4px;  background: #fffdf2; border: 1px solid #ffd821;box-shadow: 0 0 5px 0 rgba(0,0,0,0.10); border-radius: 5px;}\n");
        out.write(".text-primary { color: #336eff;}\n");
        out.write("</style>\n");
        out.write("</head>\n");
        out.write("<body>\n");
        try {
            if (StringUtils.isNotBlank(content)) {
                Object pageMapObj = reportMap.remove(ReportConfig.REPORT_PAGE_MAP_KEY);
                Map<String, Map<String, Object>> pageMap = null;
                if (pageMapObj instanceof Map) {
                    pageMap = (Map<String, Map<String, Object>>) pageMapObj;
                }

                Configuration cfg = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
                cfg.setNumberFormat("0.##");
                cfg.setClassicCompatible(true);
                StringTemplateLoader stringLoader = new StringTemplateLoader();
                stringLoader.putTemplate("template", content);
                cfg.setTemplateLoader(stringLoader);
                Template temp;
                paramMap.put("drawTable", new DrawTable(reportMap, pageMap, filter));
                paramMap.put("drawBar", new DrawBar(reportMap, actionType));
                paramMap.put("drawBarH", new DrawBarH(reportMap, actionType));
                paramMap.put("drawLine", new DrawLine(reportMap, actionType));
                paramMap.put("drawPie", new DrawPie(reportMap, actionType));
                paramMap.put("drawStackedBar", new DrawStackedBar(reportMap, actionType));
                paramMap.put("drawStackedBarH", new DrawStackedBarH(reportMap, actionType));
//				paramMap.put("drawStackedBarLineH", new DrawStackedBarLineH(reportMap, actionType));
//				paramMap.put("drawPagination", new DrawPagination(reportMap, false));
                try {
                    temp = cfg.getTemplate("template", "utf-8");
                    temp.process(paramMap, out);
                } catch (IOException | TemplateException e) {
                    logger.error("freeMarker Code：" + content);
                    logger.error("JSON Code：" + JSONObject.toJSONString(paramMap));
                    logger.error(e.getMessage(), e);
                    throw e;
                }
            }
        } catch (Exception ex) {
            out.write("<div class=\"ivu-alert ivu-alert-error ivu-alert-with-icon ivu-alert-with-desc\">" + "<span class=\"ivu-alert-icon\"><i class=\"ivu-icon ivu-icon-ios-close-circle-outline\"></i></span>" + "<span class=\"ivu-alert-message\">异常：</span> <span class=\"ivu-alert-desc\"><span>" + ex.getMessage() + "</span></span></div>");
        }
        out.write("\n</body>\n</html>");
        out.flush();
        out.close();
        return out.toString();
    }

}
