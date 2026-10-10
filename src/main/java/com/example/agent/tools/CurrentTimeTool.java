package com.example.agent.tools;

import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

import java.util.Map;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * CurrentTimeTool —— 自定义工具示例
 *
 * 对应 pi-mono examples/sdk/my-first-agent/01-custom-tool.ts 中的 currentTime 工具
 * 对应 Spring AI @Tool 注解的方法
 *
 * Spec 声明：工具名、描述、参数 Schema + execute 执行逻辑
 */
public class CurrentTimeTool implements AgentTool {

    @Override
    public String name() {
        return "currentTime";
    }

    @Override
    public String description() {
        return "获取当前日期和时间。当用户询问当前时间、日期、星期几时使用此工具。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "timezone": {
                  "type": "string",
                  "description": "时区，如 Asia/Shanghai, America/New_York"
                },
                "format": {
                  "type": "string",
                  "enum": ["full", "date", "time", "iso"],
                  "description": "输出格式"
                }
              }
            }
            """;
    }

    @Override
    public ToolResult execute(Object arguments) {
        @SuppressWarnings("unchecked")
        Map<String, String> args = (Map<String, String>) arguments;
        String tz = args.getOrDefault("timezone", "Asia/Shanghai");
        String fmt = args.getOrDefault("format", "full");

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(tz));

        String result = switch (fmt) {
            case "date" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA));
            case "time" -> now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA));
            case "iso" -> now.toInstant().toString();
            default -> now.format(DateTimeFormatter.ofPattern(
                "yyyy年MM月dd日 EEEE HH:mm:ss", Locale.CHINA
            ));
        };

        return ToolResult.success(result);
    }
}
