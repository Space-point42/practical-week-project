package cn.edu.njupt.kms.module.rag.chat;

import java.util.List;

/**
 * 生成模型抽象（RAG 的「G」环节）。
 *
 * <p>两种实现：在线大模型（OpenAI 兼容接口）与离线抽取式回答。
 * 后者不是真正的大模型，而是把检索到的原文片段按相关度拼接、
 * 辅以要点归纳与引用标注 —— 它保证系统在没有 API Key 或断网时
 * 仍然完整可用、可演示。</p>
 */
public interface ChatModel {

    /** 消息角色。 */
    record Message(String role, String content) {

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** 生成结果。 */
    record Completion(String content, String model, boolean degraded, String note) {

        public static Completion of(String content, String model) {
            return new Completion(content, model, false, null);
        }

        public static Completion degraded(String content, String note) {
            return new Completion(content, "extractive", true, note);
        }
    }

    /** 模型标识。 */
    String modelId();

    /** 是否可用（缺少 API Key 时为 false）。 */
    boolean available();

    /**
     * 生成回答。
     *
     * @param messages 对话消息序列（含 system 提示与历史）
     */
    Completion complete(List<Message> messages);
}
