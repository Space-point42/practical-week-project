package cn.edu.njupt.kms.module.rag.chat;

import cn.edu.njupt.kms.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 生成模型服务：按配置选择在线大模型，并提供统一的可用性判断。
 *
 * <p>与向量模型不同，这里<b>不做自动降级替换</b>：
 * 在线模型调用失败时由 {@code RagService} 决定改用抽取式回答，
 * 因为「降级」在这里意味着回答形态的改变，需要显式告知用户。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private ChatModel chatModel;
    private boolean offlineMode;

    @PostConstruct
    void init() {
        AppProperties.Rag.Chat cfg = props.getRag().getChat();
        if ("none".equalsIgnoreCase(cfg.getProvider())) {
            this.offlineMode = true;
            log.info("生成模型：按配置使用离线抽取式回答");
            return;
        }
        OpenAiChatModel online = new OpenAiChatModel(cfg, objectMapper);
        if (online.available()) {
            this.chatModel = online;
            log.info("生成模型：{}（baseUrl={}）",
                    cfg.getOpenai().getModel(), cfg.getOpenai().getBaseUrl());
        } else {
            this.offlineMode = true;
            log.warn("配置了在线生成模型但未提供 API Key，RAG 将使用离线抽取式回答");
        }
    }

    /** 是否配置了可用的在线生成模型。 */
    public boolean available() {
        return !offlineMode && chatModel != null && chatModel.available();
    }

    /** 模型标识；离线时为 {@code extractive}。 */
    public String modelId() {
        return available() ? chatModel.modelId() : "extractive";
    }

    /** 是否处于离线模式。 */
    public boolean isOffline() {
        return !available();
    }

    /** 调用在线模型；不可用或调用失败时返回 {@code null}，由调用方决定降级行为。 */
    public ChatModel.Completion tryComplete(java.util.List<ChatModel.Message> messages) {
        if (!available()) {
            return null;
        }
        try {
            return chatModel.complete(messages);
        } catch (Exception e) {
            log.warn("在线生成模型调用失败：{}", e.getMessage());
            return null;
        }
    }

    /** 界面展示用的说明文字。 */
    public String description() {
        return available()
                ? "在线大模型 " + chatModel.modelId()
                : "离线抽取式回答（未配置生成模型 API Key）";
    }
}
