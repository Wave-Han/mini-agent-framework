package com.example.agent;

import com.example.agent.interceptor.AuditInterceptor;
import com.example.agent.llm.LlmAgentLoop;
import com.example.agent.llm.OllamaClient;
import com.example.agent.spec.AgentTool;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.CurrentTimeTool;

import java.util.List;
import java.util.Scanner;

/**
 * LlmAgentDemo - 真实 LLM 端到端测试
 *
 * 完整流程: 用户输入 -> LLM 推理 -> Tool Calling -> 工具执行 -> 结果返回 -> LLM 总结
 * 无需 API Key，使用本地 Ollama + deepseek-r1:8b
 */
public class LlmAgentDemo {

    public static void main(String[] args) {
        System.out.println("=== LLM Agent Demo ===");
        System.out.println("真实 LLM + Tool Calling 端到端测试\n");

        OllamaClient client = new OllamaClient();
        if (!client.isServerRunning()) {
            System.err.println("Ollama 服务未运行！请先启动 Ollama。");
            return;
        }
        System.out.println("[OK] Ollama 服务已连接");

        List<AgentTool> tools = List.of(new CurrentTimeTool(), new CalculatorTool());
        System.out.println("[OK] 已注册工具: " + tools.stream().map(AgentTool::name).toList());

        String model = "qwen2.5:7b";
        LlmAgentLoop agent = new LlmAgentLoop(client, model, tools, List.of(new AuditInterceptor()));
        System.out.println("[OK] Agent Loop 已创建，模型: " + model);

        System.out.println("\n" + "============================================================");
        System.out.println("  可以输入问题，输入 quit 退出");
        System.out.println("  试试: 现在几点了? 或 计算 123 * 456 + 789");
        System.out.println("============================================================");

        if (args.length > 0 && args[0].equals("--auto")) {
            String[] testCases = {"你好，请自我介绍一下", "现在几点了？", "请帮我计算 (123 + 456) * 7 等于多少"};
            System.out.println("\n[自动模式] 运行预设测试用例...\n");
            for (String question : testCases) {
                System.out.println("============================================================");
                System.out.println("用户: " + question);
                String answer = agent.run(question);
                System.out.println("\n[最终回复] " + answer);
                System.out.println("============================================================\n");
            }
        } else {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\n用户> ");
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                    System.out.println("再见！");
                    break;
                }
                if (input.isEmpty()) continue;
                try {
                    String answer = agent.run(input);
                    System.out.println("\n[最终回复] " + answer);
                } catch (Exception e) {
                    System.err.println("[错误] " + e.getMessage());
                }
            }
            scanner.close();
        }
        agent.shutdown();
    }
}
