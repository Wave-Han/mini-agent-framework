package com.example.agent.tools;

import com.example.agent.spec.AgentTool;
import com.example.agent.spec.ToolResult;

import java.util.Map;

/**
 * CalculatorTool —— 计算器工具
 *
 * 对应 pi-mono 01-custom-tool.ts 中的 calculate 工具
 * 演示参数验证 + 错误处理
 */
public class CalculatorTool implements AgentTool {

    @Override
    public String name() { return "calculate"; }

    @Override
    public String description() { return "执行数学表达式计算。支持加减乘除、幂运算和括号。"; }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "expression": {
                  "type": "string",
                  "description": "数学表达式，如 '2 + 3 * 4' 或 '(10 - 3) / 7'"
                }
              },
              "required": ["expression"]
            }
            """;
    }

    @Override
    public ToolResult execute(Object arguments) {
        @SuppressWarnings("unchecked")
        Map<String, String> args = (Map<String, String>) arguments;
        String expr = args.get("expression");

        if (expr == null || expr.isBlank()) {
            return ToolResult.error("表达式不能为空");
        }

        if (!expr.matches("^[\\d+\\-*/().%\\s^]+$")) {
            return ToolResult.error("表达式包含不允许的字符: " + expr);
        }

        try {
            double result = evalSimple(expr);
            if (result == (long) result) {
                return ToolResult.success(expr + " = " + (long) result);
            }
            return ToolResult.success(expr + " = " + result);
        } catch (Exception e) {
            return ToolResult.error("计算错误: " + e.getMessage());
        }
    }

    /** 简单的表达式求值（仅支持基本运算） */
    private double evalSimple(String expr) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char)ch);
                return x;
            }
            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else if (eat('%')) x %= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = pos;
                if (eat('(')) { x = parseExpression(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos, pos));
                } else throw new RuntimeException("Unexpected: " + (char)ch);
                if (eat('^')) x = Math.pow(x, parseFactor());
                return x;
            }
        }.parse();
    }
}
