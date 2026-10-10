package com.example.agent.model;

import org.springframework.ai.chat.model.ChatModel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModelRegistry —— LLM Provider 注册表
 *
 * 对应 pi-mono packages/ai/src/api-registry.ts + register-builtins.ts
 * 对应 Java DriverManager —— 根据协议前缀路由到对应的 Driver
 * 对应 Spring AI 的多 ChatModel Bean
 *
 * 关键设计：
 *   - 每个 provider 注册一个 ChatModel 实例
 *   - 切换 provider 只需选择不同的 ChatModel
 *   - 懒加载：ChatModel Bean 由 Spring 容器管理
 */
public class ModelRegistry {

    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    private String defaultProvider;

    public void register(String provider, ChatModel chatModel) {
        models.put(provider, chatModel);
        if (defaultProvider == null) {
            defaultProvider = provider;
        }
    }

    public ChatModel get(String provider) {
        ChatModel model = models.get(provider);
        if (model == null) {
            throw new IllegalArgumentException("Provider not registered: " + provider);
        }
        return model;
    }

    public ChatModel getDefault() {
        if (defaultProvider == null) {
            throw new IllegalStateException("No provider registered");
        }
        return models.get(defaultProvider);
    }

    public void setDefault(String provider) {
        if (!models.containsKey(provider)) {
            throw new IllegalArgumentException("Provider not registered: " + provider);
        }
        this.defaultProvider = provider;
    }

    public Set<String> availableProviders() {
        return Collections.unmodifiableSet(models.keySet());
    }
}
