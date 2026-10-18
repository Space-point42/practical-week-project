package cn.edu.njupt.kms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code kms.*} 配置项绑定。
 *
 * <p>集中管理认证、存储、检索、RAG、版本管理等可调参数，
 * 便于在答辩演示时按需切换（例如把 RAG 生成模型从在线切换为离线）。</p>
 */
@Data
@ConfigurationProperties(prefix = "kms")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Storage storage = new Storage();
    private Search search = new Search();
    private Rag rag = new Rag();
    private Version version = new Version();
    private Bootstrap bootstrap = new Bootstrap();

    // ------------------------------------------------------------------

    @Data
    public static class Jwt {
        /** HS256 签名密钥，长度需 ≥ 32 字节。 */
        private String secret;
        /** 令牌有效期（分钟）。 */
        private long expireMinutes = 720;
        private String issuer = "njupt-kms";
    }

    @Data
    public static class Storage {
        /** 上传根目录。 */
        private String root = "../storage";
        /** 按媒体类型允许的扩展名。 */
        private Map<String, String> allowedExtensions = new LinkedHashMap<>();
        private long maxFileSizeMb = 300;

        public String allowedExtensionList() {
            return String.join(",", allowedExtensions.values());
        }
    }

    @Data
    public static class Search {
        private int maxPageSize = 100;
        private int snippetLength = 200;
        private String highlightPrefix = "<mark>";
        private String highlightSuffix = "</mark>";
        private int hotKeywordWindowDays = 30;
    }

    @Data
    public static class Rag {
        private boolean enabled = true;
        private int chunkSize = 420;
        private int chunkOverlap = 80;
        private int topK = 6;
        private int vectorTopK = 20;
        private int keywordTopK = 20;
        private int rrfK = 60;
        private boolean ontologyExpansion = true;
        private int ontologyExpandLimit = 6;
        private int historyRounds = 3;
        private Embedding embedding = new Embedding();
        private Chat chat = new Chat();

        @Data
        public static class Embedding {
            /** {@code local} 离线哈希向量 / {@code openai} OpenAI 兼容接口。 */
            private String provider = "local";
            private Local local = new Local();
            private OpenAi openai = new OpenAi();

            @Data
            public static class Local {
                private int dim = 512;
                private int ngramMin = 1;
                private int ngramMax = 2;
            }

            @Data
            public static class OpenAi {
                private String baseUrl;
                private String apiKey;
                private String model;
                private int batchSize = 16;
                private int timeoutSeconds = 60;
            }
        }

        @Data
        public static class Chat {
            /** {@code none} 离线抽取式 / {@code openai} OpenAI 兼容接口。 */
            private String provider = "openai";
            private OpenAi openai = new OpenAi();
            private boolean fallbackOnError = true;

            @Data
            public static class OpenAi {
                private String baseUrl;
                private String apiKey;
                private String model;
                private double temperature = 0.2;
                private int maxTokens = 1500;
                private int timeoutSeconds = 120;
                /**
                 * 推理强度（可选）：{@code low} / {@code high} / {@code max}，留空则不发送该参数。
                 *
                 * <p>部分推理类模型（如 DeepSeek 的 flash / pro）会先输出思考过程再输出答案，
                 * 两者**共同**占用 {@link #maxTokens}。思考长度是随机的，
                 * 偶尔会耗尽额度导致只想了没答。把推理强度调低可以显著减少思考开销，
                 * 对"依据资料归纳"这类任务几乎没有质量损失。</p>
                 */
                private String reasoningEffort;
            }
        }
    }

    @Data
    public static class Version {
        private int defaultIntervalDays = 1;
        private int defaultMinChangeCount = 5;
        private int defaultMaxVersions = 50;
        private boolean autoSnapshotEnabled = true;
    }

    @Data
    public static class Bootstrap {
        private boolean seedDemoData = true;
    }
}
