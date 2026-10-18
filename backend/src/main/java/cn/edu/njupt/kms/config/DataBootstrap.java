package cn.edu.njupt.kms.config;

import cn.edu.njupt.kms.module.cms.service.VersionPolicyService;
import cn.edu.njupt.kms.module.kb.service.DemoContentSeeder;
import cn.edu.njupt.kms.module.ontology.service.OntologySeeder;
import cn.edu.njupt.kms.module.rag.dto.RagDTO;
import cn.edu.njupt.kms.module.rag.service.RagIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * 启动引导：在数据库为空时导入本体与演示数据。
 *
 * <p>执行顺序有严格要求：</p>
 * <ol>
 *   <li>角色与用户 —— 后续内容的作者需要引用用户；</li>
 *   <li>本体（概念与关系）—— 知识条目需要引用概念 id 做标注；</li>
 *   <li>分类与标签 —— 条目需要引用分类 id；</li>
 *   <li>知识条目 —— 依赖以上全部；</li>
 *   <li>版本策略 —— 独立，可随时创建；</li>
 *   <li>向量索引 —— 依赖知识条目已就绪。</li>
 * </ol>
 *
 * <p>每一步都做了幂等判断，重复启动不会产生重复数据。
 * 向量索引重建是其中最耗时的一步（需对全部分块向量化），
 * 因此放在最后，且失败不影响系统启动 —— 管理员可稍后手动重建。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataBootstrap implements ApplicationRunner {

    private final AppProperties props;
    private final OntologySeeder ontologySeeder;
    private final DemoContentSeeder demoContentSeeder;
    private final VersionPolicyService versionPolicyService;
    private final RagIndexService ragIndexService;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getBootstrap().isSeedDemoData()) {
            log.info("已关闭演示数据初始化（kms.bootstrap.seed-demo-data=false）");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            // 1) 本体：必须先于知识条目，因为条目要引用概念 id
            if (ontologySeeder.isInitialized()) {
                log.info("知识本体已存在，跳过初始化");
            } else {
                log.info("检测到空本体，开始初始化知识本体……");
                ontologySeeder.seed();
            }

            // 2) 用户、分类、标签、知识条目
            if (demoContentSeeder.isInitialized()) {
                log.info("演示数据已存在，跳过初始化");
            } else {
                log.info("检测到空知识库，开始导入演示数据……");
                int items = demoContentSeeder.seed();
                log.info("演示知识条目导入完成，共 {} 条", items);
            }

            // 3) 版本策略兜底
            versionPolicyService.ensureDefaultPolicy();

            // 4) 向量索引：耗时较长，放在最后
            ensureSearchIndex();

        } catch (Exception e) {
            // 初始化失败不应阻止应用启动：管理员可以登录后手动处理
            log.error("演示数据初始化过程中出现异常，应用将继续启动。"
                    + "可在「系统管理 → 索引状态」中检查并手动重建索引。", e);
        }
        log.info("启动引导完成，耗时 {} ms", System.currentTimeMillis() - start);
    }

    /**
     * 为空的知识条目建立检索索引。
     *
     * <p>只对「尚无任何分块」的条目做索引，避免每次启动都全量重算。</p>
     */
    private void ensureSearchIndex() {
        try {
            RagDTO.IndexStatusDTO status = ragIndexService.status();
            if (status.getTotalChunks() > 0) {
                log.info("检索索引已存在：{} 个分块（向量模型 {}），跳过构建",
                        status.getTotalChunks(), status.getEmbeddingModel());
                return;
            }
            log.info("检索索引为空，开始构建向量索引（向量模型：{}）……", status.getEmbeddingModel());
            RagDTO.ReindexResult result = ragIndexService.reindexAll(null);
            log.info("向量索引构建完成：{} 条知识 → {} 个分块，耗时 {} ms{}",
                    result.getItemsProcessed(), result.getChunksCreated(), result.getCostMs(),
                    result.isDegraded() ? "（在线向量模型不可用，已使用本地模型）" : "");
        } catch (Exception e) {
            log.warn("构建向量索引失败，问答检索将仅使用关键字通道。"
                    + "管理员可在「系统管理 → 索引状态」中手动重建：{}", e.toString());
        }
    }
}
