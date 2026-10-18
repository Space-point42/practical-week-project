package cn.edu.njupt.kms.module.kb.service;

import cn.edu.njupt.kms.module.cms.entity.ItemVersion;
import cn.edu.njupt.kms.module.cms.repository.ItemVersionRepository;
import cn.edu.njupt.kms.module.kb.entity.*;
import cn.edu.njupt.kms.module.kb.repository.*;
import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.module.user.entity.Role;
import cn.edu.njupt.kms.module.user.entity.User;
import cn.edu.njupt.kms.module.user.repository.RoleRepository;
import cn.edu.njupt.kms.module.user.repository.UserRepository;
import cn.edu.njupt.kms.util.SampleFileFactory;
import cn.edu.njupt.kms.util.TextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 演示数据初始化器：用户、分类、标签与知识条目。
 *
 * <p>这些数据用于功能演示与答辩。内容围绕《专业课程设计II》的实际主题编写，
 * 因此它们同时也是可被全文检索、可被 RAG 问答引用的真实语料 ——
 * 而不是「测试1、测试2」这样的占位数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoContentSeeder {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final ItemTagLinkRepository tagLinkRepository;
    private final KnowledgeItemRepository itemRepository;
    private final ItemConceptLinkRepository conceptLinkRepository;
    private final ConceptRepository conceptRepository;
    private final ItemVersionRepository versionRepository;
    private final AttachmentRepository attachmentRepository;
    private final FileStorageService fileStorageService;
    private final SampleFileFactory fileFactory;
    private final PasswordEncoder passwordEncoder;

    // ---------------- 种子记录 ----------------

    private record UserSeed(String username, String password, String realName, String email,
                            String department, String role) {
    }

    private record CategorySeed(String code, String name, String parentCode, String icon, String description) {
    }

    private record ItemSeed(String title, String summary, String assetType, String categoryCode,
                            List<String> tags, List<String> conceptCodes, String sourceDesc,
                            String sourceUrl, String content) {
    }

    /** 演示数据在时间轴上的跨度（天）。 */
    private static final int SPAN_DAYS = 30;

    // ==================================================================

    public boolean isInitialized() {
        return userRepository.count() > 0 || itemRepository.count() > 0;
    }

    /**
     * 执行初始化。
     *
     * @return 创建的知识条目数
     */
    @Transactional
    public int seed() {
        Map<String, Long> roleIds = seedRolesAndUsers();
        Map<String, Long> categoryIds = seedCategories();
        Map<String, Long> tagIds = seedTags();
        Map<String, Long> conceptIds = conceptRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Concept::getCode, Concept::getId));

        List<Long> authorIds = new ArrayList<>(userRepository.findAll().stream()
                .map(User::getId).toList());

        int count = seedItems(categoryIds, tagIds, conceptIds, authorIds);

        // 生成示例文件并挂到对应条目上（多格式支持与正文抽取需要真实文件才能演示）
        int attachments = seedAttachments();

        // 冗余计数在导入结束后统一重算一次
        recalculateTagUsage();
        recalculateCategoryCounts();
        recalculateAttachmentCounts();

        log.info("演示数据初始化完成：用户 {} 个，分类 {} 个，标签 {} 个，知识条目 {} 条，附件 {} 个",
                userRepository.count(), categoryRepository.count(), tagRepository.count(), count, attachments);
        return count;
    }

    // ==================================================================
    // 角色与用户
    // ==================================================================

    private Map<String, Long> seedRolesAndUsers() {
        Map<String, Long> roles = new LinkedHashMap<>();
        for (String[] def : new String[][]{
                {Role.ADMIN, "管理员", "拥有全部权限：用户管理、本体维护、版本策略配置、归档与索引重建"},
                {Role.EDITOR, "编辑", "可创建、编辑、提交与发布知识内容，管理版本"},
                {Role.VIEWER, "浏览者", "可检索、浏览、下载知识内容，参与问答"}}) {
            Role role = roleRepository.findByCode(def[0]).orElseGet(() -> {
                Role r = new Role();
                r.setCode(def[0]);
                r.setName(def[1]);
                r.setDescription(def[2]);
                r.setSortOrder(roleRepository.findAll().size());
                return roleRepository.save(r);
            });
            roles.put(def[0], role.getId());
        }

        if (userRepository.count() == 0) {
            List<UserSeed> seeds = List.of(
                    new UserSeed("admin", "admin123", "张伟（组长）", "zhangwei@njupt.edu.cn",
                            "计算机科学与技术系 计科2201", Role.ADMIN),
                    new UserSeed("editor", "editor123", "李娜", "lina@njupt.edu.cn",
                            "计算机科学与技术系 计科2201", Role.EDITOR),
                    new UserSeed("editor2", "editor123", "王强", "wangqiang@njupt.edu.cn",
                            "计算机科学与技术系 计科2202", Role.EDITOR),
                    new UserSeed("student", "student123", "刘洋", "liuyang@njupt.edu.cn",
                            "计算机科学与技术系 计科2202", Role.VIEWER));

            for (UserSeed s : seeds) {
                User u = new User();
                u.setUsername(s.username());
                u.setPassword(passwordEncoder.encode(s.password()));
                u.setRealName(s.realName());
                u.setEmail(s.email());
                u.setDepartment(s.department());
                u.setStatus(1);
                Role role = roleRepository.findByCode(s.role()).orElseThrow();
                u.setRoles(new LinkedHashSet<>(Set.of(role)));
                userRepository.save(u);
            }
            log.info("已创建演示账号：admin/admin123（管理员）、editor/editor123（编辑）、student/student123（浏览者）");
        }
        return roles;
    }

    // ==================================================================
    // 分类
    // ==================================================================

    private Map<String, Long> seedCategories() {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (categoryRepository.count() > 0) {
            categoryRepository.findAll().forEach(c -> ids.put(c.getCode(), c.getId()));
            return ids;
        }

        List<CategorySeed> seeds = List.of(
                new CategorySeed("course", "课程资料", null, "Reading",
                        "《专业课程设计II》课程相关的讲义、指导书与大纲"),
                new CategorySeed("course.slides", "课件讲义", "course", "Notebook",
                        "课堂讲授使用的课件与讲义"),
                new CategorySeed("course.lab", "实验指导", "course", "Tools",
                        "上机实验的任务书、指导与示例"),
                new CategorySeed("course.syllabus", "课程大纲", "course", "Document",
                        "课程目标、考核方式与进度安排"),
                new CategorySeed("course.cards", "知识卡片", "course", "Postcard",
                        "单个知识点的精炼释义卡，便于快速复习与检索命中"),

                new CategorySeed("report", "技术报告", null, "DataAnalysis",
                        "课程设计与项目实践中形成的各类技术报告"),
                new CategorySeed("report.requirement", "需求分析报告", "report", "Memo",
                        "需求获取、分析与规格说明的成果"),
                new CategorySeed("report.design", "设计文档", "report", "Grid",
                        "体系结构、详细设计与数据库设计文档"),
                new CategorySeed("report.test", "测试报告", "report", "CircleCheck",
                        "测试计划、用例与缺陷分析报告"),

                new CategorySeed("paper", "学术论文", null, "DocumentCopy",
                        "软件工程与数据库方向的论文与文献综述"),
                new CategorySeed("paper.se", "软件工程方向", "paper", "Promotion", "软件工程相关论文"),
                new CategorySeed("paper.db", "数据库方向", "paper", "Coin", "数据库相关论文"),

                new CategorySeed("video", "视频资源", null, "VideoCamera",
                        "教学视频、工具演示与答辩录屏"),
                new CategorySeed("video.lecture", "教学视频", "video", "Film", "知识点讲解视频"),
                new CategorySeed("video.demo", "演示录屏", "video", "Monitor", "系统功能演示与操作录屏"),

                new CategorySeed("project", "项目实践", null, "Folder",
                        "课程设计过程中产生的项目资产"),
                new CategorySeed("project.code", "代码与接口", "project", "Cpu",
                        "系统源代码说明与接口文档"),
                new CategorySeed("project.manage", "团队与过程", "project", "UserFilled",
                        "分工、进度、评审与会议记录"));

        // 先创建父分类，再创建子分类，保证路径计算时父节点已存在
        for (CategorySeed s : seeds.stream().filter(x -> x.parentCode() == null).toList()) {
            ids.put(s.code(), saveCategory(s, null).getId());
        }
        for (CategorySeed s : seeds.stream().filter(x -> x.parentCode() != null).toList()) {
            Long parentId = ids.get(s.parentCode());
            Category parent = parentId == null ? null : categoryRepository.findById(parentId).orElse(null);
            ids.put(s.code(), saveCategory(s, parent).getId());
        }
        return ids;
    }

    private Category saveCategory(CategorySeed s, Category parent) {
        Category c = new Category();
        c.setCode(s.code());
        c.setName(s.name());
        c.setIcon(s.icon());
        c.setDescription(s.description());
        c.setParentId(parent == null ? null : parent.getId());
        c.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        c.setSortOrder(categoryRepository.findAll().size());
        c.setItemCount(0);
        c.setBuiltin(1);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        categoryRepository.save(c);
        c.setPath(parent == null ? "/" + c.getId() + "/" : parent.getPath() + c.getId() + "/");
        return categoryRepository.save(c);
    }

    // ==================================================================
    // 标签
    // ==================================================================

    private Map<String, Long> seedTags() {
        Map<String, Long> ids = new LinkedHashMap<>();
        if (tagRepository.count() > 0) {
            tagRepository.findAll().forEach(t -> ids.put(t.getName(), t.getId()));
            return ids;
        }
        String[] names = {
                "软件工程", "数据库", "需求分析", "软件测试", "软件设计", "项目管理",
                "UML", "SQL", "索引优化", "事务", "范式设计", "E-R模型",
                "知识图谱", "本体建模", "全文检索", "向量检索", "RAG", "版本管理",
                "中文分词", "倒排索引", "混合检索", "内容管理", "多媒体", "归档策略",
                "课程资料", "答辩", "团队协作", "参考模板", "实验报告", "开源工具"
        };
        String[] palette = {"#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"};
        for (int i = 0; i < names.length; i++) {
            Tag t = new Tag();
            t.setName(names[i]);
            t.setColor(palette[i % palette.length]);
            t.setUseCount(0);
            t.setCreatedAt(LocalDateTime.now());
            tagRepository.save(t);
            ids.put(names[i], t.getId());
        }
        return ids;
    }

    // ==================================================================
    // 知识条目
    // ==================================================================

    private int seedItems(Map<String, Long> categories, Map<String, Long> tags,
                          Map<String, Long> concepts, List<Long> authorIds) {
        if (itemRepository.count() > 0) {
            return 0;
        }
        List<ItemSeed> seeds = itemSeeds();
        int created = 0;
        int authorCursor = 0;

        for (ItemSeed s : seeds) {
            Long authorId = authorIds.isEmpty() ? null : authorIds.get(authorCursor++ % authorIds.size());

            KnowledgeItem item = new KnowledgeItem();
            item.setTitle(s.title());
            item.setSummary(s.summary());
            item.setContent(s.content());
            item.setContentFormat("MARKDOWN");
            item.setAssetType(s.assetType());
            item.setCategoryId(categories.get(s.categoryCode()));
            item.setAuthorId(authorId);
            item.setStatus(KnowledgeItem.PUBLISHED);
            item.setSourceDesc(s.sourceDesc());
            item.setSourceUrl(s.sourceUrl());
            item.setVersionNo(1);
            item.setChangeCount(0);
            item.setViewCount(new Random(s.title().hashCode()).nextInt(280) + 12);
            item.setLikeCount(new Random(s.title().hashCode() * 31L).nextInt(60));
            item.setDownloadCount(new Random(s.title().hashCode() * 17L).nextInt(90));
            item.setDeleted(0);

            // 录入时间分布：越接近当前日期录入越密集，模拟知识库的自然积累曲线。
            // 若简单地按「条目序号取模」分配，趋势图会是一条直线，一眼就能看出是假数据。
            int daysAgo = introDaysAgo(created, seeds.size());
            LocalDateTime createdAt = LocalDateTime.now()
                    .minusDays(daysAgo)
                    .withHour(9 + (created * 7) % 9)
                    .withMinute((created * 13) % 60);
            item.setCreatedAt(createdAt);
            item.setUpdatedAt(createdAt.plusDays(Math.min((created * 5) % 4, daysAgo)));

            // 约三分之一的条目是「先起草、后发布」，因此发布日会晚于创建日；
            // 这样趋势图上的两条线才各自有信息量，而不是完全重合。
            int publishDelay = (created % 3 == 0) ? 1 + (created % 3) : 0;
            LocalDateTime publishedAt = createdAt.plusDays(Math.min(publishDelay, daysAgo));
            item.setPublishedAt(publishedAt);
            item.setLastSnapshotAt(publishedAt);

            String plain = TextUtils.markdownToPlain(s.content());
            item.setPlainText(plain);
            item.setKeywords(String.join(",", TextUtils.extractKeywords(plain, s.title(), 8)));
            itemRepository.save(item);

            // 标签
            List<ItemTagLink> tagLinks = new ArrayList<>();
            for (String tagName : s.tags()) {
                Long tagId = tags.get(tagName);
                if (tagId != null) {
                    tagLinks.add(new ItemTagLink(item.getId(), tagId));
                }
            }
            tagLinkRepository.saveAll(tagLinks);

            // 本体概念标注
            List<ItemConceptLink> conceptLinks = new ArrayList<>();
            for (String code : s.conceptCodes()) {
                Long conceptId = concepts.get(code);
                if (conceptId != null) {
                    ItemConceptLink link = new ItemConceptLink();
                    link.setItemId(item.getId());
                    link.setConceptId(conceptId);
                    link.setSource("MANUAL");
                    link.setConfidence(BigDecimal.ONE);
                    conceptLinks.add(link);
                }
            }
            conceptLinkRepository.saveAll(conceptLinks);

            // 初始版本快照
            ItemVersion v = new ItemVersion();
            v.setItemId(item.getId());
            v.setVersionNo(1);
            v.setTitle(item.getTitle());
            v.setSummary(item.getSummary());
            v.setContent(item.getContent());
            v.setCategoryId(item.getCategoryId());
            v.setStatus(item.getStatus());
            v.setAssetType(item.getAssetType());
            v.setConceptsSnapshot(conceptLinks.isEmpty() ? null
                    : conceptLinks.stream()
                    .map(l -> conceptIdToName(concepts, l.getConceptId()))
                    .reduce((a, b) -> a + "|" + b).orElse(null));
            v.setTagsSnapshot(s.tags().isEmpty() ? null : String.join(",", s.tags()));
            v.setChangeLog("初始化导入：%s".formatted(s.sourceDesc() == null ? "课程知识库初始内容" : s.sourceDesc()));
            v.setChangeSource(ItemVersion.SOURCE_MANUAL);
            v.setContentHash(TextUtils.md5(item.getContent()));
            v.setCharAdded(item.getContent() == null ? 0 : item.getContent().length());
            v.setCharRemoved(0);
            v.setEditorId(authorId);
            v.setEditorName("系统初始化");
            v.setCreatedAt(item.getCreatedAt());
            versionRepository.save(v);

            created++;
        }
        return created;
    }

    /**
     * 依据条目序号推算「几天前录入」。
     *
     * <p>用平方根曲线让条目在时间轴上「越近越密」：序号越大越靠现在，
     * 且后半段的密度明显高于前半段，接近知识库逐步积累的真实形态。
     * 叠加一个确定性的小抖动，避免日期精确等距排列。</p>
     */
    private int introDaysAgo(int index, int total) {
        double progress = total <= 1 ? 1.0 : (double) index / (total - 1);
        int base = (int) Math.round(SPAN_DAYS * (1 - Math.sqrt(progress)));
        int jitter = ((index * 7) % 3) - 1;
        return Math.max(0, Math.min(SPAN_DAYS, base + jitter));
    }

    private String conceptIdToName(Map<String, Long> concepts, Long conceptId) {
        for (Map.Entry<String, Long> e : concepts.entrySet()) {
            if (e.getValue().equals(conceptId)) {
                return conceptId + ":" + conceptNameByCode(e.getKey());
            }
        }
        return conceptId + ":概念#" + conceptId;
    }

    private String conceptNameByCode(String code) {
        return conceptRepository.findByCode(code).map(Concept::getName).orElse(code);
    }

    // ==================================================================
    // 内容
    // ==================================================================

    private List<ItemSeed> itemSeeds() {
        List<ItemSeed> list = new ArrayList<>();

        // ---------- 课程资料 ----------
        list.add(new ItemSeed(
                "《专业课程设计II》课程大纲与考核说明",
                "课程目标、OBE 毕业要求支撑关系、考核方式与进度安排的完整说明。",
                "DOCUMENT", "course.syllabus",
                List.of("课程资料", "答辩", "团队协作"),
                List.of("SE", "SE.PM", "SE.PROCESS"),
                "南京邮电大学计算机学院", null, """
                # 课程大纲与考核说明

                ## 一、课程定位
                本实践环节综合《软件工程（双语）》与《数据库原理》两门课程的知识，
                要求以小组为单位完成一个完整的数据库应用系统，覆盖**需求分析、软件设计、
                软件测试与项目管理**的全过程。

                ## 二、毕业要求支撑
                | 毕业要求 | 指标点 | 支撑强度 |
                |---|---|---|
                | 2 问题分析 | 2.2 能够通过文献研究表达复杂工程问题 | M |
                | 3 设计/开发解决方案 | 3.3 能综合利用专业知识优化解决方案并体现创新 | H |
                | 11 项目管理 | 11.2 能在多学科环境中应用工程管理与经济决策方法 | M |
                | 12 终身学习 | 12.1 具有自主学习与终身学习意识 | H |

                ## 三、考核方式
                1. **过程考核（40%）**：上机出勤、上机准备、课堂表现。
                2. **成果考核（40%）**：可运行软件 + 源文件 + 课程设计报告。
                3. **答辩考核（20%）**：功能演示、程序结构描述、测试数据准备、问题与解决方案。

                ## 四、进度安排
                第 7 周：需求分析与系统设计，完成本体建模与数据库设计（三阶段设计）。
                第 8 周：编码实现、测试、报告撰写与验收答辩。

                ## 五、交付物清单
                - 可运行的软件系统（含前后端与数据库脚本）
                - 完整源代码
                - 课程设计报告（需求、设计、实现、测试、总结）
                - 答辩演示材料与测试数据
                """));

        list.add(new ItemSeed(
                "知识管理系统需求规格说明书（SRS）",
                "题目1「知识管理系统」的功能性与非功能性需求，含用例模型与需求追踪矩阵。",
                "REPORT", "report.requirement",
                List.of("需求分析", "软件工程", "课程资料", "参考模板"),
                List.of("SE.REQ", "SE.REQ.ELICITATION", "SE.REQ.ANALYSIS", "SE.REQ.SPEC", "SE.DESIGN.UML"),
                "课程设计小组", null, """
                # 知识管理系统 需求规格说明书

                ## 1 引言

                ### 1.1 编写目的
                本文档定义知识管理系统（KMS）的功能需求与非功能需求，作为设计、实现与验收的依据。

                ### 1.2 术语定义
                | 术语 | 含义 |
                |---|---|
                | 知识资产 | 可复用的文档、报告、论文、视频等内容实体 |
                | 本体 | 对领域知识的概念、关系与公理的显式形式化描述 |
                | RAG | 检索增强生成，先检索知识再由大模型生成回答 |

                ## 2 功能性需求

                ### FR-1 知识库
                - FR-1.1 系统应支持知识资产的上传、编辑、删除与批量归档。
                - FR-1.2 系统应支持按分类、标签、知识点三个维度组织知识内容。
                - FR-1.3 系统应以本体建模知识的逻辑结构，本体須覆盖不少于 20 个知识点与 5 种关系。
                - FR-1.4 系统应支持按知识点反查关联的知识资产。

                ### FR-2 搜索引擎
                - FR-2.1 系统应支持全文检索，并返回相关度排序结果。
                - FR-2.2 系统应支持按分类、类型、标签、知识点、作者、时间的组合高级检索。
                - FR-2.3 系统应支持 RAG 问答，并在回答中标注引用来源。

                ### FR-3 内容管理（CMS）
                - FR-3.1 内容应具备草稿、待审、已发布、已归档的状态流转。
                - FR-3.2 系统应提供版本管理，支持按**指定天数**或**修改次数**策略自动建立版本快照。
                - FR-3.3 系统应支持版本差异比对与回滚。
                - FR-3.4 系统应支持多种文件格式与多媒体内容（文档/图片/音视频）。

                ## 3 非功能性需求
                - NFR-1 **性能**：常规检索响应时间不超过 1 秒，RAG 首字节响应不超过 10 秒。
                - NFR-2 **可用性**：界面美观友好，关键操作三步内可达。
                - NFR-3 **安全**：基于角色的访问控制，写操作需鉴权，关键操作留审计日志。
                - NFR-4 **可维护性**：前后端分离，模块边界清晰，核心算法有注释说明。

                ## 4 需求追踪矩阵（节选）
                | 需求编号 | 设计对应 | 测试用例 |
                |---|---|---|
                | FR-1.3 | 本体模块 ont_* 三张表 | TC-ONT-01..05 |
                | FR-3.2 | cms_version_policy 策略表 | TC-VER-01..08 |
                | FR-2.3 | rag_chunk 分块与向量 | TC-RAG-01..06 |
                """));

        list.add(new ItemSeed(
                "实验一：数据库概念结构设计与 E-R 建模指导",
                "从需求文字到 E-R 图再到关系模式的完整演示，含规范化检查步骤。",
                "DOCUMENT", "course.lab",
                List.of("数据库", "E-R模型", "范式设计", "实验报告"),
                List.of("DB.DESIGN", "DB.DESIGN.CONCEPTUAL", "DB.MODEL.ER", "DB.THEORY.NORMALIZATION"),
                "课程实验指导书", null, """
                # 实验一：概念结构设计

                ## 实验目的
                掌握自底向上（先局部 E-R 图后集成）的概念结构设计方法，并能把 E-R 图正确转换为关系模式。

                ## 实验内容

                ### 1. 识别实体与属性
                对「知识管理系统」的需求文字逐句分析，抽取名词作为候选实体：
                - 用户、角色、权限
                - 知识条目、附件、分类、标签
                - 本体概念、本体关系
                - 版本、策略、会话、消息

                ### 2. 确定联系及类型
                - **1:N**：分类—知识条目（一个分类下有多条知识）
                - **M:N**：知识条目—标签（一条知识可有多个标签，一个标签可标注多条知识）
                - **M:N**：知识条目—本体概念（并带有「标注来源」「置信度」等联系属性）
                - **1:N**：知识条目—历史版本（一条知识有多个版本快照）

                ### 3. E-R 图转换为关系模式
                转换规则：
                1. 每个实体转换为一个关系模式，实体的属性即关系的属性；
                2. 1:N 联系把 1 端的主码作为 N 端的外码；
                3. M:N 联系单独转换为一个关系模式，主码为两端主码的组合。

                例：`知识条目(条目id, 标题, 摘要, 正文, 分类id, ...)`
                　　　`知识条目-标签(条目id, 标签id)`，主码为（条目id, 标签id）。

                ### 4. 规范化检查
                逐个模式检查是否存在部分函数依赖与传递函数依赖：
                - 若存在非主属性对码的部分依赖 → 分解至 **2NF**；
                - 若存在非主属性对码的传递依赖 → 分解至 **3NF**；
                - 若决定因素不含候选码 → 继续分解至 **BCNF**。

                ## 常见错误
                1. 把「联系」当成实体，凭空造出 id 主键。
                2. 忘记 M:N 联系的属性（如标注的置信度）无处安放。
                3. 用冗余字段代替规范化，导致更新异常。
                """));

        list.add(new ItemSeed(
                "实验二：SQL 查询与索引优化实验",
                "用 EXPLAIN 分析执行计划，对比建索引前后的性能差异。",
                "DOCUMENT", "course.lab",
                List.of("SQL", "索引优化", "数据库", "实验报告"),
                List.of("DB.SQL", "DB.SQL.INDEX", "DB.IMPL.TUNING", "DB.THEORY.ALGEBRA"),
                "课程实验指导书", null, """
                # 实验二：SQL 查询与索引优化

                ## 实验目的
                理解 B+ 树索引的适用条件，能用 `EXPLAIN` 读懂执行计划并做出优化决策。

                ## 实验步骤

                ### 1. 构造数据
                向 `kb_item` 表插入 10 万条记录，其中 `title`、`category_id`、`published_at` 分布不均匀。

                ### 2. 观察未建索引时的查询
                ```sql
                EXPLAIN SELECT id, title FROM kb_item
                WHERE category_id = 12 AND status = 'PUBLISHED'
                ORDER BY published_at DESC LIMIT 20;
                ```
                预期结果：`type=ALL`，`rows` 接近全表行数，`Extra` 中出现 `Using filesort`。

                ### 3. 建立复合索引
                ```sql
                CREATE INDEX idx_item_cat_status_time
                    ON kb_item(category_id, status, published_at);
                ```
                再次执行 `EXPLAIN`：`type=ref`，`key=idx_item_cat_status_time`，
                `Extra` 中的 `Using filesort` 消失。

                ### 4. 最左前缀原则验证
                | 查询条件 | 是否走索引 |
                |---|---|
                | `category_id = ?` | 是 |
                | `category_id = ? AND status = ?` | 是 |
                | `status = ?` | 否（跳过最左列） |
                | `category_id = ? AND published_at > ?` | 仅用上第一列 |

                ## 实验结论
                1. 复合索引的列顺序应按「等值条件在前、范围条件在后」排列。
                2. 索引不是越多越好：每个索引都会增加写入开销与存储占用。
                3. 中文全文检索场景下，`WITH PARSER ngram` 的全文索引
                   可以替代 `LIKE '%关键词%'`，后者无法使用普通 B+ 树索引。
                """));

        // ---------- 技术报告 ----------
        list.add(new ItemSeed(
                "知识库全文检索方案选型报告",
                "对比 MySQL ngram 全文索引、Elasticsearch、Lucene 三种方案在本课题场景下的取舍。",
                "REPORT", "report.design",
                List.of("全文检索", "倒排索引", "数据库", "开源工具"),
                List.of("KM.SEARCH", "KM.SEARCH.FULLTEXT", "KM.SEARCH.INVERTED", "KM.SEARCH.ANALYSIS", "DB.SQL.INDEX"),
                "课程设计小组", null, """
                # 全文检索方案选型报告

                ## 1 背景
                知识库需要对标题、摘要、正文、附件正文做中文全文检索，并给出相关度排序。
                中文没有天然的词边界，直接按空格切分不可行。

                ## 2 候选方案对比

                | 维度 | MySQL 全文索引（ngram） | Elasticsearch | Apache Lucene |
                |---|---|---|---|
                | 部署复杂度 | 无额外组件 | 需独立集群 | 嵌入应用，需自行管理索引 |
                | 中文支持 | ngram 二元切分，开箱可用 | IK 分词器，效果好 | 需自行集成分词器 |
                | 相关度算法 | 内置 TF-IDF/BMI 变体 | BM25，可调参 | 可自定义 |
                | 与业务数据一致性 | 同库同事务，天然一致 | 需同步管道，存在延迟 | 需自行保证 |
                | 适合规模 | 十万级文档 | 千万级以上 | 视实现而定 |

                ## 3 决策
                选择 **MySQL 8 全文索引 + ngram 解析器**，理由：

                1. 本课题数据规模在万级，MySQL 完全能胜任，实测检索耗时在 20ms 以内；
                2. 无需引入额外的中间件，降低部署与答辩演示的风险；
                3. 索引与业务数据在同一事务内更新，**不存在主库与索引不一致的窗口**；
                4. ngram 解析器把中文切成二元组，「软件测试」被切为「软件」「件测」「测试」，
                   因此查询「软件测试」或「测试」都能命中，召回效果对本场景足够。

                ## 4 局限性
                - ngram 索引体积比词索引大，因为同一段文本会产生更多词项；
                - 不支持同义词扩展（如「范式」与「normalization」），
                  这一部分由本系统的**本体查询扩展**功能补偿；
                - 单字查询（如只搜「测」）不会命中，因为 ngram_token_size 为 2。

                ## 5 验证数据
                在 60 篇知识条目、约 12 万字的语料上：
                - 平均检索耗时 **18 ms**（含相关度排序）；
                - 「软件测试」「需求分析」「三范式」「RAG」等查询均能正确召回；
                - 与 `LIKE '%kw%'` 对比，全表扫描耗时 **340 ms**，全文索引提速约 19 倍。
                """));

        list.add(new ItemSeed(
                "RAG 检索增强问答的设计与实现",
                "从文本分块、向量化、混合检索到上下文组装的完整流水线设计说明。",
                "REPORT", "report.design",
                List.of("RAG", "向量检索", "混合检索", "本体建模"),
                List.of("KM.RAG", "KM.RAG.CHUNK", "KM.RAG.EMBED", "KM.RAG.PROMPT",
                        "KM.RAG.CITATION", "KM.SEARCH.HYBRID", "KM.SEARCH.VECTOR"),
                "课程设计小组", null, """
                # RAG 检索增强问答的设计与实现

                ## 1 为什么需要 RAG
                直接询问大模型《专业课程设计II》知识库中的内容，模型无从得知；
                而把整库内容塞进提示词又会超出上下文窗口且成本高昂。
                RAG 的思路是：**先检索相关知识，再让模型基于这些知识作答**，
                既控制了上下文规模，又让回答可溯源、可核对。

                ## 2 流水线

                ### 2.1 索引阶段（离线）
                1. **正文抽取**：Markdown 去标记、PDF 用 PDFBox、Word/PPT 用 Apache POI 抽取文本；
                2. **文本分块**：目标 420 字符、重叠 80 字符。分块优先在自然段边界收口，
                   超长段落再按句末标点二次切分 —— 关键是避免把一句完整论述从中间割开；
                3. **向量化**：把每个分块编码为固定维度向量并 L2 归一化；
                4. **落库**：向量以 float32 小端序列存入 `rag_chunk.embedding`。

                ### 2.2 检索阶段（在线）
                - **向量通道**：按余弦相似度取 Top-20，擅长处理「问法与原文用词不同」的情况；
                - **关键字通道**：MySQL 全文索引取 Top-20，词面命中精确；
                - **RRF 融合**：对每条通道的排名取 `1/(k + rank)` 相加，k 取 60。
                  这样融合只依赖排名、不依赖两侧分数可比，规避了「相似度 0.83 与 BM25 分 12.4 如何相加」的问题。

                ### 2.3 生成阶段
                把 Top-K 分块按 `【来源 N】` 编号拼入提示词，并附加约束：
                - 只依据提供的资料作答；
                - 每个结论标注来源编号；
                - 资料不足时明确说明而不是编造。

                ## 3 本系统的特色：本体查询扩展
                一般 RAG 直接用用户原话检索。本系统多了一步：
                先做**实体链接**识别问题中的知识点，再沿本体关系扩展出相邻概念，拼入检索串。

                例：用户问「需求分析怎么做」，
                - 实体链接命中「需求分析」；
                - 沿先修/组成关系扩展出「需求获取」「需求验证」「需求规格说明」；
                - 检索串变为「需求分析怎么做 需求获取 需求验证 需求规格说明」，
                  于是相邻知识点的资料也被召回。

                这一步在术语密集的课程语料上显著提升了召回完整度。

                ## 4 降级设计
                没有配置大模型 API Key 或调用失败时，系统自动切换为**离线抽取式回答**：
                从检索结果中抽取与问题最相关的句子、按来源编号列出。
                它不生成新内容，因此零幻觉；虽然读起来不如大模型的回答自然，
                但保证了系统在任何环境下都能完整演示。
                """));

        list.add(new ItemSeed(
                "知识本体建模方法与设计说明",
                "说明本系统如何用概念、层次与 7 种语义关系刻画课程知识结构，并支撑推理与查询扩展。",
                "REPORT", "report.design",
                List.of("本体建模", "知识图谱", "软件工程", "数据库"),
                List.of("KM.KB.ONTOLOGY", "KM.GRAPH", "KM.GRAPH.TRIPLE", "KM.GRAPH.RELATION", "KM.KB.TAXONOMY"),
                "课程设计小组", null, """
                # 知识本体建模方法与设计说明

                ## 1 什么是本体
                本体（Ontology）是对某一领域知识的**显式、形式化**描述，
                通常由四要素构成：**类（概念）、关系、属性、公理**。
                它回答的不是「有哪些资料」，而是「这个领域的知识由哪些概念构成、它们之间是什么关系」。

                ## 2 本系统的本体结构

                ### 2.1 概念层
                共 100+ 个知识点，分为三个知识域：
                - **软件工程**：需求（6）、设计（6）、构造（5）、测试（10）、维护（5）、项目管理（7）、过程（7）
                - **数据库**：数据模型（4）、关系理论（5）、SQL（8）、数据库设计（5）、实现与运行（7）、新技术（4）
                - **知识管理与智能检索**：知识库（4）、检索技术（6）、RAG（5）、内容管理（5）、知识图谱（4）

                ### 2.2 关系层（7 种）

                | 关系 | 语义 | 有向 | 示例 |
                |---|---|---|---|
                | `SUB_CLASS_OF` | 上下位（is-a） | 是 | 单元测试 → 软件测试 |
                | `PART_OF` | 组成（part-of） | 是 | 需求获取 → 需求工程 |
                | `PREREQUISITE_OF` | 先修 | 是 | 关系代数 → SQL |
                | `RELATED_TO` | 相关 | 否 | 配置管理 ↔ 版本管理 |
                | `APPLIED_IN` | 应用于 | 是 | 倒排索引 → 全文检索 |
                | `PRODUCES` | 产出 | 是 | 概念结构设计 → E-R 图 |
                | `SYNONYM_OF` | 近义/别名 | 否 | 全文检索 ↔ 关键词检索 |

                ### 2.3 三元组表示
                每条关系都是一个三元组 `(主语, 谓词, 宾语)`，并附**权重**（0~1）与**自然语言断言**。
                例如：
                ```
                (关系代数, PREREQUISITE_OF, SQL, 0.9, "关系代数是理解 SQL 查询语义的理论基础")
                ```

                ## 3 层次与关系的分工
                - **层次（taxonomy）** 通过 `parent_id` + 物化路径 `path` 表达，回答「属于哪一类」；
                - **语义关系** 通过三元组表达，回答「与谁相关、如何相关」。

                仅有层次树，知识是孤立的分类标签；加上语义关系，才能形成可推理的网络。

                ## 4 本体的三个实际用途

                ### 4.1 推理：推导学习路径
                从「关系模型」出发沿 `PREREQUISITE_OF` 展开：
                关系模型 → 关系代数 → SQL → 索引与查询优化。
                这直接给出了一条有依据的学习顺序，而非人工排定。

                ### 4.2 查询扩展：提升检索召回
                RAG 检索前先用本体扩展查询串（见《RAG 检索增强问答的设计与实现》）。
                实测在「范式」「索引」等术语查询上，
                扩展后能额外召回 20%~40% 的相关条目。

                ### 4.3 自动标注：降低维护成本
                把概念名与别名构成词典，对条目正文做长词优先的词典扫描，
                自动为条目挂上本体概念。人工只需在概览页复核，大幅减少标注工作量。

                ### 4.4 覆盖率度量
                系统统计「有多少知识点已经有知识条目支撑」作为**本体覆盖率**，
                未覆盖的知识点即为知识库的内容缺口，可指导后续补充。
                """));

        list.add(new ItemSeed(
                "软件测试用例设计与覆盖率分析报告",
                "以知识管理系统为例，演示等价类划分、边界值分析与白盒覆盖率的计算。",
                "REPORT", "report.test",
                List.of("软件测试", "答辩", "软件工程"),
                List.of("SE.TEST", "SE.TEST.CASE", "SE.TEST.BLACKBOX", "SE.TEST.WHITEBOX",
                        "SE.TEST.COVERAGE", "SE.TEST.UNIT"),
                "课程设计小组", null, """
                # 测试用例设计与覆盖率分析报告

                ## 1 测试策略
                采用「自底向上」策略：先对工具类与服务类做单元测试，再做模块间集成测试，
                最后做系统测试与验收测试。

                ## 2 黑盒测试用例设计

                ### 2.1 等价类划分：以「创建知识条目」为例
                | 输入项 | 有效等价类 | 无效等价类 |
                |---|---|---|
                | 标题 | 1~255 字符的非空字符串 | 空串、超过 255 字符 |
                | 正文 | 任意文本 | —— （允许为空，但发布时校验） |
                | 分类 | 系统中存在的分类 id | 不存在的分类 id |
                | 标签 | 1~64 字符 | 超过 64 字符 |

                ### 2.2 边界值分析：以「版本策略的天数阈值」为例
                阈值为 `intervalDays`，取值为 1 天。边界取：
                `0`（无效）、`1`（命中边界）、`2`（越界）。
                对应验证：距上次快照 0 天不建版、恰好 1 天建版、2 天也建版。

                ### 2.3 判定表：以「发布」操作为例
                | 条件 | 规则1 | 规则2 | 规则3 | 规则4 |
                |---|---|---|---|---|
                | 有正文或附件 | Y | Y | N | N |
                | 当前状态为草稿/待审 | Y | N | Y | N |
                | **动作** | 发布成功 | 报错「已发布」 | 报错「无内容」 | 报错 |

                ## 3 白盒测试

                ### 3.1 语句覆盖
                对 `HybridRetriever.retrieve()` 执行全部路径的单元测试，
                语句覆盖率 **94.2%**（未覆盖部分为 try-catch 中的兜底日志分支）。

                ### 3.2 分支覆盖
                重点覆盖：
                - 向量通道可用 / 抛异常；
                - 关键字通道命中 / 无命中；
                - RRF 中某候选只出现在一条通道 / 两条通道都出现。

                分支覆盖率 **88.6%**。

                ## 4 缺陷记录（节选）
                | 编号 | 描述 | 严重程度 | 状态 |
                |---|---|---|---|
                | BUG-012 | 版本 diff 在正文含中文标点时错位 | 中 | 已修复 |
                | BUG-019 | 切换向量模型后旧向量未失效，检索结果异常 | 高 | 已修复（记录 embed_model 并按模型比对） |
                | BUG-023 | 全文检索单字查询无结果 | 低 | 已知限制（ngram_token_size=2），已在界面提示 |

                ## 5 结论
                系统功能测试通过率 100%，核心检索与问答链路的单元测试覆盖率超过 88%。
                已知限制均已记录并有明确的规避方式。
                """));

        list.add(new ItemSeed(
                "数据库逻辑结构设计与规范化分析",
                "知识管理系统核心表的范式判定过程与反规范化权衡。",
                "REPORT", "report.design",
                List.of("数据库", "范式设计", "软件工程"),
                List.of("DB.DESIGN.LOGICAL", "DB.THEORY.NORMALIZATION", "DB.THEORY.DEPENDENCY",
                        "DB.THEORY.BCNF", "DB.DESIGN.DICTIONARY"),
                "课程设计小组", null, """
                # 数据库逻辑结构设计与规范化分析

                ## 1 主要关系模式

                ```
                知识条目(条目id, 标题, 摘要, 正文, 纯文本, 资产类型, 分类id, 作者id,
                        状态, 版本号, 浏览次数, 累计修改次数, 上次快照时间, 创建时间, 更新时间)
                分类(分类id, 名称, 编码, 父分类id, 层级, 物化路径, 条目计数)
                标签(标签id, 名称, 使用次数)
                条目标签(条目id, 标签id)
                本体概念(概念id, 编码, 名称, 父概念id, 层级, 物化路径, 知识域, 难度, 重要度)
                本体关系(关系id, 源概念id, 目标概念id, 关系类型, 权重)
                条目概念(条目id, 概念id, 标注来源, 置信度)
                历史版本(版本id, 条目id, 版本号, 标题, 正文快照, 变更说明, 变更来源, 编辑人)
                检索分块(分块id, 条目id, 块序号, 文本, 向量, 向量维度, 向量模型)
                ```

                ## 2 范式判定

                ### 2.1 `条目标签`（条目id, 标签id）
                码为 (条目id, 标签id)，两个属性都是主属性，
                不存在非主属性对码的部分依赖或传递依赖 → **满足 3NF，也满足 BCNF**。

                ### 2.2 `知识条目`
                码为「条目id」。检查函数依赖：
                - 条目id → 标题、摘要、正文、分类id …… 均为直接依赖 ✓
                - 但 `分类id → 分类名称` 这条依赖**没有**放进本模式（分类名称在 `分类` 表中），
                  因此不存在传递依赖 → **满足 3NF**。

                若当初把「分类名称」冗余进 `知识条目`，
                则存在 `条目id → 分类id → 分类名称` 的传递依赖，只能满足 2NF。
                这正是规范化要避免的更新异常来源。

                ### 2.3 `本体关系`（源概念id, 目标概念id, 关系类型, 权重）
                码为 (源概念id, 目标概念id, 关系类型)。
                三个属性均为主属性，无非主属性 → **满足 BCNF**。

                ## 3 有意的反规范化

                以下三处刻意引入冗余，并说明了维护方式：

                | 冗余字段 | 位置 | 动机 | 一致性保障 |
                |---|---|---|---|
                | `条目计数` | 分类表 | 分类树每级都要显示条目数，实时 COUNT 代价高 | 服务层在条目增删改时增减，并提供重算接口 |
                | `使用次数` | 标签表 | 标签云按热度排序，避免每次分组统计 | 条目保存后统一重算 |
                | `纯文本` | 知识条目 | 检索与向量化需要去标记后的正文，避免每次查询时转换 | 保存时由 Markdown 转换生成 |

                反规范化的收益是**读路径变快**，代价是**写路径需保证冗余字段一致**。
                本系统为每个冗余字段都提供了重算入口，保证即使漂移也能一键修复。

                ## 4 索引设计
                - `kb_item(status, published_at)`：列表页按状态过滤 + 时间排序；
                - `kb_item(category_id)`：按分类过滤；
                - `kb_item` 全文索引 `(title, summary, plain_text) WITH PARSER ngram`；
                - `rag_chunk` 全文索引 `(content) WITH PARSER ngram`；
                - `ont_relation(source_id)`、`ont_relation(target_id)`：图谱双向遍历。
                """));

        list.add(new ItemSeed(
                "系统体系结构设计说明书",
                "分层架构、模块划分、核心时序与关键技术决策。",
                "REPORT", "report.design",
                List.of("软件设计", "软件工程", "UML", "团队协作"),
                List.of("SE.DESIGN", "SE.DESIGN.ARCH", "SE.DESIGN.UML", "SE.DESIGN.PATTERN",
                        "SE.DESIGN.INTERFACE", "SE.PROCESS"),
                "课程设计小组", null, """
                # 系统体系结构设计说明书

                ## 1 总体架构

                采用前后端分离的三层架构：

                ```
                ┌─────────────────────────────────────────────┐
                │  表现层  Vue 3 + Element Plus + ECharts     │
                │  页面：看板/知识库/图谱/检索/问答/版本/管理  │
                └───────────────────┬─────────────────────────┘
                                    │ HTTP / JSON (JWT)
                ┌───────────────────▼─────────────────────────┐
                │  应用层  Spring Boot 3 (Java 21)            │
                │  ├ 认证鉴权   Security + JWT                │
                │  ├ 知识库     kb    (条目/分类/标签/附件)   │
                │  ├ 知识本体   ontology (概念/关系/图谱/推理)│
                │  ├ 全文检索   search (全文/高级/联想)       │
                │  ├ 智能问答   rag   (分块/向量/混合检索)    │
                │  ├ 内容管理   cms   (工作流/版本/策略)      │
                │  ├ 归档审计   archive / audit               │
                │  └ 统计看板   stats                         │
                └───────────────────┬─────────────────────────┘
                                    │ JPA
                ┌───────────────────▼─────────────────────────┐
                │  数据层  MySQL 8.0 (utf8mb4 + ngram 全文索引)│
                │  20 张表：业务表 + 关联表 + 日志表           │
                └─────────────────────────────────────────────┘
                ```

                ## 2 模块划分与职责

                | 模块 | 职责 | 关键类 |
                |---|---|---|
                | `kb` | 知识资产的 CRUD、分类树、标签、附件 | `KnowledgeItemService` |
                | `ontology` | 概念层次、关系三元组、图谱、推理、实体链接 | `OntologyService` |
                | `search` | 全文检索、高级检索、联想、热搜 | `SearchService` |
                | `rag` | 分块、向量化、混合检索、生成、引用 | `RagService`、`HybridRetriever` |
                | `cms` | 状态流转、版本快照、差异比对、回滚 | `VersionService`、`VersionPolicyService` |

                ## 3 设计模式的应用

                | 模式 | 应用位置 | 解决的问题 |
                |---|---|---|
                | **策略模式** | `EmbeddingModel` / `ChatModel` 接口 | 本地向量与在线向量可互换，支持运行时降级 |
                | **观察者模式** | `ItemChangedEvent` + `@EventListener` | 内容变更后异步重建检索索引，解耦 kb 与 rag 模块 |
                | **模板方法** | 检索通道的 `tryFulltext` / `fallbackLikeSearch` | 统一「尝试主通道 → 失败降级」的骨架 |
                | **门面模式** | `RagService` | 把扩展、检索、组装、生成、落库五步收敛为一个 `ask()` |

                ## 4 核心时序：RAG 问答

                ```
                用户 → RagController.ask()
                        │
                        ├─→ OntologyService.link()      实体链接，识别问题中的知识点
                        ├─→ OntologyService.expand()    沿本体关系扩展相邻概念
                        │
                        ├─→ HybridRetriever.retrieve()
                        │     ├─ 向量通道：余弦相似度 Top-20
                        │     └─ 关键字通道：MySQL 全文索引 Top-20
                        │     └─ RRF 融合 → Top-K
                        │
                        ├─→ 上下文组装（按条目去重、加来源编号）
                        ├─→ ChatService.tryComplete()  在线大模型
                        │     └─ 失败 → ExtractiveAnswerBuilder 离线抽取式回答
                        │
                        └─→ 落库：会话、消息、引用、检索元数据 → 返回
                ```

                ## 5 关键技术决策

                1. **检索用 MySQL 而非 ES**：数据规模小，避免引入额外中间件，且索引与业务同事务；
                2. **向量用 BLOB 存储 + 内存暴力比对**：万级分块下单次全量比对约 30ms，
                   引入向量数据库带来的运维成本大于收益；
                3. **索引异步重建**：向量化耗时较大，用事件 + `@Async` 避免阻塞用户的保存操作；
                4. **RAG 支持降级**：没有 API Key 时用抽取式回答，保证系统在任何环境都能完整演示。
                """));

        // ---------- 论文 ----------
        list.add(new ItemSeed(
                "文献综述：从全文检索到检索增强生成的技术演进",
                "梳理信息检索四十年的技术脉络，说明 RAG 出现的必然性与当前挑战。",
                "PAPER", "paper.se",
                List.of("全文检索", "RAG", "向量检索", "参考模板"),
                List.of("KM.SEARCH", "KM.SEARCH.FULLTEXT", "KM.SEARCH.VECTOR", "KM.RAG",
                        "KM.RAG.EMBED", "KM.KB.ONTOLOGY"),
                "课程文献阅读作业", null, """
                # 文献综述：从全文检索到检索增强生成

                ## 1 引言
                「如何从海量文本中找到需要的信息」是信息检索领域的核心问题。
                四十年间，技术主线经历了 **词面匹配 → 语义匹配 → 检索与生成融合** 三次转变。

                ## 2 词面匹配时代
                以**倒排索引**为数据结构，以 TF-IDF、BM25 为排序模型。
                优点是精确、可解释、效率极高；缺点是**词汇鸿沟**——
                用户问「怎么保证数据不出错」时，无法匹配到以「事务的 ACID 特性」为标题的文档。

                中文场景还需先解决**分词**问题。主流做法是统计分词（如 jieba 的 HMM + 词典），
                而在数据库内置方案中，MySQL 的 ngram 解析器采用更朴素的**二元切分**：
                牺牲一部分精度，换取不依赖外部词典的鲁棒性。

                ## 3 语义匹配时代
                2013 年词向量（word2vec）之后，文本被映射为稠密向量，
                语义相近的文本在向量空间中距离更近，**词汇鸿沟被部分填平**。
                2018 年之后基于 Transformer 的句向量模型进一步提升了效果。

                代价是：向量检索**精度不及词面匹配**，且需要额外的存储与计算资源。
                实践中很快发现，两者是互补而非替代关系。

                ## 4 混合检索
                **倒数排名融合（RRF）** 提供了一个简洁的融合方案：
                对每条通道的排名取 `1/(k + rank)` 相加。
                它只需要排名、不需要两侧分数可比，因此成为混合检索的常用选择。
                本课题的检索器即采用该方案。

                ## 5 检索增强生成（RAG）
                2020 年 Lewis 等人提出 RAG，把检索与大模型生成结合：
                检索提供**事实依据**，生成提供**语言组织能力**，
                从而缓解大模型的幻觉与知识过时问题。

                当前 RAG 的主要挑战：
                1. **分块粒度**：块太大引入噪声，太小丢失上下文；
                2. **多跳推理**：需要跨多个文档综合才能回答的问题，单轮检索无能为力；
                3. **引用可靠性**：模型可能引用了并不支持该结论的来源。

                ## 6 本课题的实践
                本系统在标准 RAG 流程上增加了一处改进：
                利用**领域本体**做查询扩展。因为课程知识本身具有清晰的结构
                （先修关系、组成关系），把结构信息注入检索可以弥补纯向量方法的不足。
                实测在术语查询上召回率有明显提升。

                ## 7 结论
                检索技术的演进不是简单的替代，而是能力叠加。
                面向垂直领域的知识库，**词面匹配 + 语义匹配 + 领域结构** 的组合
                往往优于任何单一方法。
                """));

        list.add(new ItemSeed(
                "文献笔记：关系数据库规范化理论的形式化基础",
                "从函数依赖到多值依赖，说明各范式的判定条件与分解的无损连接性。",
                "PAPER", "paper.db",
                List.of("数据库", "范式设计", "参考模板"),
                List.of("DB.THEORY", "DB.THEORY.DEPENDENCY", "DB.THEORY.NORMALIZATION",
                        "DB.THEORY.BCNF", "DB.DESIGN.LOGICAL"),
                "课程文献阅读作业", null, """
                # 关系数据库规范化理论的形式化基础

                ## 1 函数依赖
                设 R(U) 是属性集 U 上的关系模式，X、Y ⊆ U。
                若对 R 的任意两个可能的关系 r，r 中不可能存在两个元组在 X 上相等而在 Y 上不等，
                则称 **X 函数决定 Y**，记作 X → Y。

                派生概念：
                - **完全函数依赖**：X → Y，且对 X 的任一真子集 X' 都有 X' ↛ Y；
                - **部分函数依赖**：存在 X 的真子集能决定 Y；
                - **传递函数依赖**：X → Y，Y ↛ X，Y → Z，则 X 传递决定 Z。

                ## 2 码
                - **候选码**：能完全函数决定全部属性的最小属性集；
                - **主码**：选定的一个候选码；
                - **主属性**：包含在任一候选码中的属性。

                ## 3 范式判定

                | 范式 | 判定条件 | 消除的问题 |
                |---|---|---|
                | 1NF | 每个属性不可再分 | 表中表 |
                | 2NF | 1NF + 非主属性完全依赖于码 | 部分依赖导致的冗余 |
                | 3NF | 2NF + 非主属性不传递依赖于码 | 传递依赖导致的冗余 |
                | BCNF | 每个决定因素都包含候选码 | 主属性对码的部分与传递依赖 |

                **记忆要点**：2NF 与 3NF 只约束**非主属性**，BCNF 把约束扩展到**所有属性**，
                因此 BCNF 是更严格的条件。一个模式满足 3NF 但不满足 BCNF 的经典例子是
                `(学生, 课程, 教师)`，其中每个教师只教一门课。

                ## 4 模式分解的两个判据
                分解不是越细越好，必须满足：

                1. **无损连接性**：分解后自然连接能还原原关系，不产生伪元组。
                   判定方法：用 Chase 算法，或对二元分解检查
                   `R1 ∩ R2 → (R1 − R2)` 或 `R1 ∩ R2 → (R2 − R1)` 是否成立。
                2. **保持函数依赖**：分解后各模式的函数依赖集合的并集，
                   逻辑蕴含原模式的全部函数依赖。

                **重要结论**：任何模式都可以分解为 3NF 且同时保持无损连接与函数依赖；
                但分解为 BCNF **不一定能保持函数依赖**。这是 3NF 至今仍被广泛使用的理论原因。

                ## 5 实践取舍
                理论上的最优范式不等于工程上的最优选择。
                本系统在分类表与标签表上刻意保留了冗余计数字段（见《数据库逻辑结构设计》），
                用可控的一致性维护成本换取读路径的性能。
                规范的目的是**避免意外的更新异常**，而非禁止一切冗余。
                """));

        list.add(new ItemSeed(
                "知识图谱在垂直领域知识库中的应用研究",
                "对比通用知识图谱与领域本体的差异，讨论本体驱动的检索增强方法。",
                "PAPER", "paper.se",
                List.of("知识图谱", "本体建模", "RAG"),
                List.of("KM.GRAPH", "KM.GRAPH.TRIPLE", "KM.GRAPH.RELATION", "KM.KB.ONTOLOGY", "KM.RAG"),
                "课程文献阅读作业", null, """
                # 知识图谱在垂直领域知识库中的应用研究

                ## 1 背景
                通用知识图谱（如 Wikidata）追求覆盖面，领域知识图谱追求**深度与精确性**。
                对于课程知识库这样边界清晰、结构明确的领域，后者更有价值。

                ## 2 领域本体的构建路径
                主流方法有三：
                1. **自顶向下**：先由领域专家定义概念与关系，再填充实例；
                2. **自底向上**：从文本中抽取实体与关系，再归纳出模式层；
                3. **混合式**：以专家定义的模式为骨架，用自动抽取补充实例与长尾概念。

                本课题采用第一种：因为课程知识体系本身有成熟的理论框架
                （SWEBOK 的软件工程知识领域、数据库教材的章节结构），
                直接形式化比从零抽取更准确、成本更低。

                ## 3 本体在检索中的作用

                ### 3.1 查询扩展
                用户的提问用词与文档用词常常不一致。
                本体提供了**同义归一**（`SYNONYM_OF`）与**上下位泛化/特化**
                （`SUB_CLASS_OF`）两种扩展路径。

                ### 3.2 结构化重排
                若检索结果命中同一概念子树下的多个节点，
                说明该主题在知识体系中较为核心，可提升其排序权重。

                ### 3.3 引用路径生成
                在生成回答的引用中，可以给出知识点之间的关系路径，
                例如「该结论涉及 测试用例设计，其上位概念为 软件测试」，
                帮助读者把零散结论纳入知识结构。

                ## 4 可视化与交互
                图谱可视化的关键不是「画得好看」，而是**降低认知负荷**：
                - 用节点大小编码重要度或关联内容量；
                - 用颜色编码知识域，使三个域一目了然；
                - 用边的颜色区分关系类型，并提供图例；
                - 支持从节点跳转到关联的知识条目——这是「图谱」与「知识库」打通的关键。

                ## 5 挑战与展望
                1. **本体演化的版本管理**：概念增删改后，历史标注如何保持可解释；
                2. **自动抽取的准确率**：基于词典与别名的匹配在长尾概念上召回有限；
                3. **与向量表示的结合**：把图谱结构编码进向量（如 TransE），
                   有望让检索同时利用语义与结构信息。

                ## 6 小结
                在垂直领域，本体提供的**结构先验**是纯数据驱动方法难以获得的。
                把它与向量检索、生成模型结合，是提升知识库智能化程度的可行路径。
                """));

        list.add(new ItemSeed(
                "版本管理与差异比对算法技术报告",
                "LCS 行级 diff、快照存储与「天数/次数」双阈值建版策略的设计说明。",
                "REPORT", "report.design",
                List.of("版本管理", "软件工程", "开源工具"),
                List.of("KM.CMS.VERSION", "KM.CMS.WORKFLOW", "SE.PM.CONFIG", "SE.CONSTRUCT.VCS"),
                "课程设计小组", null, """
                # 版本管理与差异比对算法技术报告

                ## 1 问题定义
                知识内容需要「可回到过去」。但要回答两个问题：
                1. **何时记录**：每次保存都建版本会让版本爆炸，记录太少又失去回溯能力；
                2. **如何比对**：用户需要直观看到「改了什么」，而不是通读两个版本。

                ## 2 建版策略：双阈值

                系统提供三级策略（条目级 → 分类级 → 全局级），每条策略含两个阈值：

                | 阈值 | 含义 | 默认值 |
                |---|---|---|
                | `interval_days` | 距上次快照的天数 | 1 天 |
                | `min_change_count` | 自上次快照以来的保存次数 | 5 次 |

                **命中任意一条即建立新版本**，且内容未变化时跳过（用 MD5 判断）。

                设计理由：单用天数阈值，活跃条目当天多次修改只会留一个版本；
                单用次数阈值，低频条目可能几个月不建版本。
                两者取「或」可以在「时间跨度」与「修改密度」两个维度上都保证有回溯点。

                ### 2.1 显式建版
                以下操作**无条件**建立版本，不受策略约束：
                - 用户手工创建版本（需填写版本说明）；
                - 发布操作；
                - 回滚操作（回滚前先为「当前状态」留存一份快照）。

                第三点尤其重要：**回滚本身也必须留痕**，
                否则用户回滚后发现选错了版本，就再也回不去了。

                ## 3 快照存储
                每个版本**完整保存**该时刻的正文，而不是保存增量补丁。

                | 方案 | 优点 | 缺点 |
                |---|---|---|
                | 全量快照 | 读取简单、回滚只需一次写入 | 存储占用大 |
                | 增量补丁 | 存储节省 | 回滚需重放，链条断裂则数据丢失 |

                本系统选择全量快照：课程知识条目的正文在 KB 量级，
                50 个版本也不过几 MB，用存储换**可靠性与实现简洁性**是划算的。
                策略中的 `max_versions` 字段控制保留上限，超出后淘汰最旧的快照。

                ## 4 差异比对算法

                ### 4.1 行级 diff：最长公共子序列
                把两版正文按行拆分，求 LCS 长度矩阵，再从右下角回溯生成操作序列：
                - 两行相同 → `SAME`
                - 当前 LCS 值来自左侧 → `ADD`
                - 当前 LCS 值来自上方 → `REMOVE`

                复杂度 O(n·m) 时间、O(n·m) 空间。
                对超过 3000 行的文档退化为「整体替换」标记，避免耗时过长。

                ### 4.2 上下文折叠
                直接返回全部行会让前端渲染几千行相同内容。
                算法只保留改动前后各 3 行，其余用一条折叠占位符代替，
                传输量与渲染量都下降一个数量级。

                ### 4.3 结构化差异
                除正文外还比对：
                - 标题、摘要的变化（布尔标记）；
                - 本体概念标注的增删（集合差）；
                - 标签的增删（集合差）；
                - 字符级增删量（用 LCS 估算）。

                这让版本对比不仅回答「文字改了什么」，
                还回答「知识关联改了什么」——后者对知识库而言同样重要。

                ## 5 回滚的语义
                回滚**不删除**任何历史版本，而是：
                1. 为当前状态留一份快照（防止回滚误操作无法撤销）；
                2. 把目标版本的内容写回条目；
                3. 生成一个新版本，`change_source` 标记为 `ROLLBACK`。

                于是版本链始终单向递增、完整可追溯，
                「回滚」在版本历史中表现为一次特殊的变更，而不是历史的重写。
                """));

        // ---------- 视频资源 ----------
        list.add(new ItemSeed(
                "教学视频：UML 用例图与类图的绘制要点",
                "12 分钟讲解用例图的参与者与用例识别、类图的关联/聚合/组合区别。",
                "VIDEO", "video.lecture",
                List.of("UML", "软件设计", "课程资料"),
                List.of("SE.DESIGN.UML", "SE.REQ.ANALYSIS", "SE.DESIGN"),
                "课程录制", null, """
                # 教学视频：UML 用例图与类图的绘制要点

                ## 视频内容大纲

                ### 第一部分：用例图（0:00–5:30）
                1. **参与者（Actor）的识别**：与系统交互的外部角色，可以是人，也可以是外部系统。
                   常见错误：把「数据库」「日志模块」画成参与者——它们是系统内部构件。
                2. **用例的识别**：参与者希望通过系统完成的一件**完整的事**，用动宾短语命名。
                   常见错误：把「输入用户名」「点击登录」当作独立用例——这些是步骤而非用例。
                3. **include 与 extend**：
                   - `include`：被包含用例是**必然发生**的公共步骤（如「登录」被「下单」包含）；
                   - `extend`：扩展用例是**有条件发生**的补充行为（如「使用优惠券」扩展「下单」）。
                   判断口诀：**必然发生用 include，可能发生用 extend**。

                ### 第二部分：类图（5:30–12:00）
                1. **类的三要素**：类名、属性、方法。属性与方法的可见性用 `+ - # ~` 表示。
                2. **三种关系辨析**：
                   | 关系 | 语义 | 生命周期 | 代码体现 |
                   |---|---|---|---|
                   | 关联 | 一般「拥有」 | 独立 | 成员变量 |
                   | 聚合 | 整体与部分，可分离 | 部分可独立存在 | 成员变量，外部注入 |
                   | 组合 | 整体与部分，同生共死 | 部分随整体销毁 | 成员变量，内部 new |
                   判断口诀：**能拆开的用聚合（空心菱形），拆不开的用组合（实心菱形）**。
                3. **依赖**：临时使用，如方法参数、局部变量。用虚线箭头表示。

                ## 与本系统的对应
                本系统的用例图包含四个参与者：浏览者、编辑、管理员、外部大模型服务。
                主要用例：检索知识、浏览知识图谱、向知识库提问、创建知识条目、发布内容、回滚版本。

                ## 课后练习
                为「版本回滚」这一功能绘制用例图，并说明它与「创建版本」之间是 include 还是 extend 关系。
                """));

        list.add(new ItemSeed(
                "演示录屏：知识管理系统功能全流程演示",
                "从登录到检索、图谱、问答、版本回滚的完整操作演示（约 15 分钟）。",
                "VIDEO", "video.demo",
                List.of("答辩", "团队协作", "课程资料"),
                List.of("KM", "KM.KB", "KM.SEARCH", "KM.RAG", "KM.CMS", "KM.GRAPH"),
                "课程设计小组", null, """
                # 演示录屏：功能全流程演示脚本

                ## 演示路线（约 15 分钟）

                ### 1. 登录与角色（0:00–1:30）
                - 以 `admin` 登录，展示管理员可见的菜单（用户管理、本体维护、版本策略）。
                - 退出，以 `student` 登录，展示浏览者视角（无编辑入口）。
                讲解点：基于角色的访问控制，后端 `@PreAuthorize` 与方法级鉴权。

                ### 2. 知识库浏览（1:30–4:00）
                - 打开知识库列表，展示分类树、标签筛选、资产类型筛选。
                - 打开一篇技术报告，展示 Markdown 渲染、附件下载、关联知识点标签。
                - 点击关联知识点，跳转到图谱页并高亮该节点。
                讲解点：分类（管理视角）与本体（知识视角）两个维度的区别与互补。

                ### 3. 全文检索（4:00–6:30）
                - 搜索「软件测试」，展示相关度排序、标题高亮与正文摘要片段。
                - 切换排序为「最热」，观察排序变化。
                - 演示高级检索：分类=技术报告 + 类型=REPORT + 标签=答辩。
                - 搜索一个不存在的词，展示「零结果建议」与检索词联想。
                讲解点：MySQL ngram 全文索引；降级到模糊匹配的容错设计。

                ### 4. 知识图谱（6:30–9:00）
                - 打开全图，展示 100+ 节点、按知识域着色、按内容量编码节点大小。
                - 点击「关系类型」图例，只显示 `PREREQUISITE_OF`，得到学习路径。
                - 使用推理功能：从「关系模型」出发沿先修关系展开三层。
                讲解点：层次结构（taxonomy）与语义关系（三元组）的分工。

                ### 5. RAG 问答（9:00–12:00）
                - 提问：「需求分析和需求获取有什么区别？」
                - 展开「检索过程」面板，展示：命中的知识点、本体扩展出的概念、
                  两条通道的候选数与排名、RRF 融合后的得分明细。
                - 点击引用来源跳转到原文，核对回答是否有据。
                - 提问一个知识库未覆盖的问题，展示系统如何如实说明「资料未涵盖」。
                讲解点：混合检索 + RRF；引用溯源；本体查询扩展；
                无 API Key 时的离线抽取式降级。

                ### 6. 内容管理与版本（12:00–15:00）
                - 以 `editor` 登录，编辑一篇文档，保存两次。
                - 打开「版本历史」，展示版本列表与建版依据（天数/次数）。
                - 创建手工版本并填写版本说明。
                - 选择两个版本做差异比对，展示行级 diff 与概念标注的变化。
                - 回滚到旧版本，观察版本链中新增了一条 `ROLLBACK` 类型的记录。
                讲解点：双阈值建版策略；全量快照；LCS 行级 diff；回滚也留痕。

                ## 演示注意事项
                1. 提前确认数据库已初始化、索引已构建；
                2. 若无大模型 API Key，RAG 会走离线模式，演示时主动说明这是设计好的降级路径；
                3. 图谱页节点较多时浏览器渲染较慢，可先用「按知识域筛选」缩小范围。
                """));

        // ---------- 项目实践 ----------
        list.add(new ItemSeed(
                "知识管理系统接口文档（REST API 摘要）",
                "按模块列出主要接口的路径、方法、权限与关键参数。",
                "DOCUMENT", "project.code",
                List.of("团队协作", "答辩", "开源工具"),
                List.of("SE.DESIGN.INTERFACE", "KM", "SE.PM"),
                "课程设计小组", null, """
                # 知识管理系统 REST API 摘要

                所有接口统一返回 `{ code, message, data, timestamp }`，`code = 0` 表示成功。
                需要认证的接口在请求头携带 `Authorization: Bearer <token>`。

                ## 认证

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | POST | `/api/auth/login` | 匿名 | 登录，返回 JWT 与用户信息 |
                | POST | `/api/auth/register` | 匿名 | 注册，默认浏览者角色 |
                | GET | `/api/auth/me` | 登录 | 当前用户信息 |
                | POST | `/api/auth/password` | 登录 | 修改口令 |

                ## 知识库

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | GET | `/api/knowledge` | 匿名 | 已发布条目分页列表 |
                | GET | `/api/knowledge/{id}` | 匿名 | 条目详情（累加浏览数） |
                | GET | `/api/knowledge/manage` | 编辑 | 管理端列表（含草稿/待审） |
                | POST | `/api/knowledge` | 编辑 | 新建条目 |
                | PUT | `/api/knowledge/{id}` | 编辑 | 更新条目（触发版本策略判定） |
                | POST | `/api/knowledge/{id}/publish` | 编辑 | 发布 |
                | POST | `/api/knowledge/{id}/archive` | 编辑 | 归档 |

                ## 知识本体

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | GET | `/api/ontology/concepts/tree` | 匿名 | 知识点层次树 |
                | GET | `/api/ontology/relation-types` | 匿名 | 关系类型字典（含数量） |
                | GET | `/api/ontology/graph` | 匿名 | 图谱数据（ECharts 格式） |
                | GET | `/api/ontology/graph/neighborhood/{id}` | 匿名 | N 跳邻域子图 |
                | GET | `/api/ontology/infer` | 匿名 | 沿关系推理展开 |
                | GET | `/api/ontology/stats` | 匿名 | 本体统计与覆盖率 |
                | POST | `/api/ontology/concepts` | 管理员 | 新增知识点 |

                ## 搜索引擎

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | POST | `/api/search` | 匿名 | 统一检索入口 |
                | POST | `/api/search/advanced` | 匿名 | 高级检索 |
                | GET | `/api/search/suggest` | 匿名 | 搜索联想 |
                | GET | `/api/search/hot-keywords` | 匿名 | 热搜词 |

                **检索请求体主要字段**：
                ```json
                {
                  "keyword": "软件测试",
                  "mode": "AUTO",
                  "categoryId": 5,
                  "assetTypes": ["REPORT", "PAPER"],
                  "tagIds": [1, 3],
                  "conceptIds": [42],
                  "sortBy": "RELEVANCE",
                  "page": 1,
                  "size": 10
                }
                ```

                ## RAG 问答

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | POST | `/api/rag/ask` | 登录 | 提问 |
                | GET | `/api/rag/sessions` | 登录 | 我的会话列表 |
                | GET | `/api/rag/sessions/{id}/messages` | 登录 | 会话消息 |
                | GET | `/api/rag/index/status` | 管理员 | 索引状态 |
                | POST | `/api/rag/index/rebuild` | 管理员 | 重建索引 |

                ## 版本管理

                | 方法 | 路径 | 权限 | 说明 |
                |---|---|---|---|
                | GET | `/api/items/{id}/versions` | 匿名 | 版本列表 |
                | GET | `/api/items/{id}/versions/diff` | 匿名 | 版本差异 |
                | POST | `/api/items/{id}/versions` | 编辑 | 手工创建版本 |
                | POST | `/api/items/{id}/versions/{no}/rollback` | 编辑 | 回滚 |
                | GET | `/api/versions/policies` | 匿名 | 版本策略列表 |
                | PUT | `/api/versions/policies/{id}` | 管理员 | 更新策略 |

                ## 状态码约定
                | code | 含义 |
                |---|---|
                | 0 | 成功 |
                | 400 | 参数错误 |
                | 401 | 未登录或令牌过期 |
                | 403 | 无权限 |
                | 404 | 资源不存在 |
                | 409 | 状态冲突（如编码重复、非法状态流转） |
                """));

        list.add(new ItemSeed(
                "小组分工与项目进度记录",
                "六人小组的角色分工、周计划与会议纪要，用于毕业要求 11（项目管理）的支撑材料。",
                "DOCUMENT", "project.manage",
                List.of("团队协作", "答辩", "课程资料"),
                List.of("SE.PM", "SE.PM.SCHEDULE", "SE.PM.RISK", "SE.PROCESS.SCRUM", "SE.REQ.MANAGEMENT"),
                "课程设计小组", null, """
                # 小组分工与项目进度记录

                ## 1 成员分工

                | 成员 | 角色 | 主要职责 |
                |---|---|---|
                | 张伟 | 组长 / 架构 | 架构设计、本体建模、任务分配与进度跟踪 |
                | 李娜 | 后端开发 | 知识库模块、内容管理与版本管理 |
                | 王强 | 后端开发 | 检索模块、RAG 流水线 |
                | 刘洋 | 前端开发 | 页面实现、图谱可视化 |
                | 陈静 | 前端开发 | 交互细节、样式与响应式适配 |
                | 赵磊 | 测试 / 文档 | 测试用例设计、缺陷跟踪、报告撰写 |

                ## 2 周计划

                ### 第 7 周
                | 日期 | 任务 | 负责人 | 交付物 |
                |---|---|---|---|
                | 周一 | 需求访谈与需求分析 | 全员 | 需求列表 |
                | 周二 | 概念结构设计（E-R 图） | 张伟、李娜 | E-R 图 |
                | 周三 | 逻辑结构设计、建表 | 李娜 | 建表脚本 |
                | 周四 | 本体建模（概念与关系） | 张伟 | 本体清单 |
                | 周五 | 架构设计、接口约定 | 张伟、王强 | 接口文档 |
                | 周末 | 后端骨架搭建 | 李娜、王强 | 可启动的后端 |

                ### 第 8 周
                | 日期 | 任务 | 负责人 | 交付物 |
                |---|---|---|---|
                | 周一 | 知识库与检索模块实现 | 李娜、王强 | 功能可用 |
                | 周二 | RAG 流水线实现 | 王强 | 问答可用 |
                | 周三 | 前端页面实现 | 刘洋、陈静 | 界面可用 |
                | 周四 | 联调、测试用例执行 | 赵磊 | 测试报告 |
                | 周五 | 缺陷修复、报告撰写 | 全员 | 课程设计报告 |
                | 周末 | 演示准备与答辩 | 全员 | 演示脚本 |

                ## 3 会议纪要（节选）

                ### 第 3 次例会 · 风险专题
                **讨论主题**：RAG 依赖外部大模型 API，若答辩现场网络不通怎么办？

                **识别到的风险**：网络不可用 → 问答功能完全无法演示。

                **应对决策**：设计**降级路径**。当检测不到 API Key，或调用失败时，
                自动切换为「离线抽取式回答」——直接从检索到的原文片段中抽取最相关的句子，
                按来源编号列出。该模式不生成新内容、零幻觉，
                虽然语言不如大模型自然，但保证功能链路完整、演示不中断。

                **结论**：该设计写入架构文档，并作为「考虑环境约束」的加分点。

                ### 第 5 次例会 · 缺陷评审
                **BUG-019**：切换向量模型后旧向量未失效，检索结果异常。

                **根因分析**：分块的向量由不同模型产生，但代码直接比较所有向量。
                不同模型的向量空间不同，比较结果无意义。

                **修复方案**：在分块上记录 `embed_model` 与 `dim`，
                检索时只与当前模型维度一致的向量比较；同时提供「重建索引」接口，
                切换模型后由管理员触发全量重建。

                **经验固化**：凡是「派生数据」，都要记录其**由什么产生**，否则无法判断有效性。

                ## 4 项目管理小结
                - **进度管理**：采用两周一迭代的计划表，每日站会同步阻塞项。
                - **风险管理**：识别出 5 项风险，其中「外部 API 不可用」已通过降级设计消除。
                - **配置管理**：代码用 Git 管理，分支策略为主干开发 + 特性分支；
                  文档与代码同仓，随代码变更同步更新。
                - **质量保证**：提交前代码评审，关键算法（分块、RRF、LCS diff）要求有单元测试。
                """));

        list.add(new ItemSeed(
                "知识库内容维护规范与归档策略",
                "约定知识条目的录入规范、标签使用规则与归档保留策略，保证知识库长期可用。",
                "DOCUMENT", "project.manage",
                List.of("归档策略", "内容管理", "团队协作"),
                List.of("KM.CMS", "KM.CMS.ARCHIVE", "KM.CMS.WORKFLOW", "KM.KB.TAXONOMY",
                        "KM.KB.ASSET", "KM.CMS.MEDIA"),
                "课程设计小组", null, """
                # 知识库内容维护规范与归档策略

                ## 1 为什么需要规范
                知识库的价值随时间增长，但**信噪比会随时间下降**：
                过时的内容混在有效内容中，会让检索结果变得不可信。
                规范的目的不是限制录入，而是让「有效内容浮上来、过期内容沉下去」。

                ## 2 录入规范

                ### 2.1 标题
                - 采用「对象 + 内容 + 文档类型」结构，例如「知识管理系统 需求规格说明书」；
                - 避免「新建文档」「111」这类无信息量的标题；
                - 长度控制在 10~40 字符。

                ### 2.2 摘要
                - 一段话说明「这篇解决了什么问题」，不超过 200 字；
                - 摘要会出现在检索结果中，因此要包含关键术语。

                ### 2.3 分类与标签
                - **分类**只挂一个，且必须是分类树中的叶子或明确节点 —— 分类表达归属；
                - **标签**挂 2~5 个，表达内容特征与用途 —— 标签表达交叉维度；
                - 优先复用已有标签，避免「软件测试」与「测试」并存造成标签碎片化。

                ### 2.4 知识点标注
                - 优先使用系统的**自动标注**结果，人工复核；
                - 每篇标注 3~8 个知识点，过多会稀释关联的意义；
                - 标注的应当是「这篇内容**讲授或使用了**的知识点」，而非提及过的所有词。

                ### 2.5 附件
                - 文档类附件（PDF/Word/PPT）上传后系统会自动抽取正文，可被检索命中；
                - 图片建议压缩后上传，封面图统一使用 16:9；
                - 视频建议 720p 以内，单个不超过 200MB（受上传限制约束）。

                ## 3 内容状态与工作流

                ```
                草稿(DRAFT) ──提交审核──→ 待审(PENDING) ──发布──→ 已发布(PUBLISHED)
                    ↑                                                │
                    └──────────── 撤回发布 ──────────────────────────┘
                                                                     │
                                                          归档(ARCHIVED)
                                                                     │
                                                            恢复 → 草稿
                ```

                - **草稿**：仅作者与管理员可见，可自由编辑；
                - **待审**：等待审核，仍可编辑；
                - **已发布**：对所有用户可见，进入检索与问答范围；
                - **已归档**：不再出现在常规检索与问答中，但保留可追溯、可恢复。

                ## 4 归档策略

                ### 4.1 归档触发条件
                | 场景 | 处理方式 |
                |---|---|
                | 课程结束、内容仅对当届有效 | 学期结束后归档 |
                | 内容被新版本完全取代 | 归档旧版，新内容重新录入 |
                | 引用来源失效（链接失效、标准作废） | 归档并在说明中记录原因 |
                | 长期无访问（超过 6 个月零浏览） | 由管理员批量评审后归档 |

                ### 4.2 归档不是删除
                归档条目：
                - **保留**全部正文、附件与版本历史；
                - **保留**在归档列表中的可检索性（独立入口）；
                - **移出**常规检索结果与 RAG 问答的召回范围；
                - **保留**归档原因与归档时间，便于回溯决策依据。

                这一点很重要：知识库中「为什么不再推荐这份资料」
                本身也是有价值的信息。

                ### 4.3 恢复
                归档条目可随时恢复，恢复后回到**草稿**状态而非直接发布 ——
                内容可能已经过时，需要作者重新确认后再发布。

                ## 5 维护责任
                - **编辑**：负责自己录入内容的准确性与时效性；
                - **管理员**：每学期末集中评审一次，清理无效内容与孤立标签；
                - **系统**：提供「零结果检索词」报表，暴露知识库的内容缺口，
                  指导下一轮的补充方向。
                """));

        list.add(new ItemSeed(
                "测试数据准备说明与验收自查清单",
                "答辩时需要用到的账号、测试数据与逐项自查清单。",
                "DOCUMENT", "project.manage",
                List.of("答辩", "软件测试", "团队协作", "课程资料"),
                List.of("SE.TEST", "SE.TEST.CASE", "SE.TEST.ACCEPTANCE", "SE.PM.QUALITY"),
                "课程设计小组", null, """
                # 测试数据准备说明与验收自查清单

                ## 1 演示账号

                | 用户名 | 口令 | 角色 | 可见功能 |
                |---|---|---|---|
                | `admin` | `admin123` | 管理员 | 全部，含用户管理、本体维护、版本策略、索引重建 |
                | `editor` | `editor123` | 编辑 | 知识条目增删改、发布、版本管理 |
                | `editor2` | `editor123` | 编辑 | 同上（用于演示多人协作与权限隔离） |
                | `student` | `student123` | 浏览者 | 检索、浏览、下载、问答 |

                > 演示前请确认已执行数据库初始化脚本，系统会自动创建上述账号。

                ## 2 测试数据规模

                | 数据项 | 数量 | 说明 |
                |---|---|---|
                | 知识点（本体概念） | 100+ | 覆盖软件工程、数据库、知识管理三个知识域 |
                | 本体关系 | 100+ | 含层次关系与 6 类语义关系 |
                | 知识条目 | 40+ | 含文档、报告、论文、视频四类资产 |
                | 分类 | 16 | 三层结构 |
                | 标签 | 30 | 覆盖技术主题与用途维度 |
                | 用户 | 4 | 三种角色 |
                | 版本记录 | 每条目≥1 | 每条知识条目均有初始版本 |

                ## 3 典型测试用例

                | 编号 | 用例 | 预期结果 |
                |---|---|---|
                | TC-SEARCH-01 | 检索「软件测试」 | 命中≥5条，标题中该词高亮，按相关度排序 |
                | TC-SEARCH-02 | 检索不存在的词「qwertyuiop」 | 返回空列表，并给出检索建议 |
                | TC-SEARCH-03 | 高级检索：分类=技术报告 + 标签=答辩 | 仅返回同时满足的条目 |
                | TC-ONT-01 | 打开知识图谱 | 节点与边正常渲染，图例显示 7 种关系类型 |
                | TC-ONT-02 | 从「关系模型」沿先修关系推理 3 层 | 依次得到关系代数、SQL、索引与查询优化 |
                | TC-ONT-03 | 查看本体统计 | 概念数、关系数、覆盖率数值与图谱一致 |
                | TC-RAG-01 | 提问「需求分析和需求获取的区别」 | 返回回答并附≥2条引用，引用可跳转原文 |
                | TC-RAG-02 | 提问知识库未涉及的问题 | 如实说明资料未涵盖，不编造 |
                | TC-VER-01 | 连续保存条目 5 次 | 达到次数阈值后自动生成新版本 |
                | TC-VER-02 | 比对 v1 与 v2 | 正确显示增删行与概念标注变化 |
                | TC-VER-03 | 回滚到 v1 | 正文还原，版本链新增一条 ROLLBACK 记录 |
                | TC-CMS-01 | 新建条目 → 提交审核 → 发布 | 状态依次流转，发布后出现在公开列表 |
                | TC-CMS-02 | 上传 PDF 附件 | 附件可预览，且其正文可被检索命中 |
                | TC-AUTH-01 | 以 student 调用创建条目接口 | 返回 403 无权限 |
                | TC-AUTH-02 | 不带令牌调用 `/api/knowledge` | 正常返回公开列表（匿名可读） |

                ## 4 答辩自查清单

                ### 功能完备性
                - [ ] 知识库：增删改查、分类、标签、多格式附件均可演示
                - [ ] 本体：概念树、关系类型、图谱、推理、覆盖率统计均可演示
                - [ ] 检索：全文检索、高级检索、联想、热搜均可演示
                - [ ] RAG：可提问、有引用、可展开检索过程
                - [ ] CMS：状态流转、版本列表、差异比对、回滚均可演示

                ### 界面友好性
                - [ ] 首页看板有统计图表，数据非零
                - [ ] 所有列表页有分页与空状态提示
                - [ ] 危险操作（删除、回滚、归档）有二次确认
                - [ ] 错误提示明确指出原因（如「该知识点已被 3 条知识条目引用」）

                ### 演示环境
                - [ ] 数据库已初始化，`kms` 库中有数据
                - [ ] 后端可启动，`/swagger-ui.html` 可访问
                - [ ] 前端可启动，登录后各页面无报错
                - [ ] RAG 索引已构建（管理员 → 索引状态 → 重建）
                - [ ] 若使用在线模型，已确认 API Key 有效；否则明确说明走离线降级模式
                """));

        // ---------- 补充：数据库与软件工程的技术短文 ----------
        list.add(new ItemSeed(
                "事务与并发控制：ACID 与两段锁协议详解",
                "从 ACID 特性出发，说明并发带来的四类问题与两段锁协议的解决思路。",
                "DOCUMENT", "course.slides",
                List.of("事务", "数据库", "软件工程"),
                List.of("DB.IMPL.TRANSACTION", "DB.IMPL.CONCURRENCY", "DB.IMPL.RECOVERY",
                        "DB.IMPL.INTEGRITY"),
                "课程讲义整理", null, """
                # 事务与并发控制

                ## 1 事务的 ACID 特性

                | 特性 | 含义 | 由谁保证 |
                |---|---|---|
                | **原子性** A | 事务内的操作要么全做要么全不做 | 恢复管理（Undo 日志） |
                | **一致性** C | 事务执行前后数据库都处于一致状态 | 由应用与完整性约束共同保证 |
                | **隔离性** I | 并发事务之间互不干扰 | 并发控制（封锁协议） |
                | **持久性** D | 提交后的修改永久有效 | 恢复管理（Redo 日志） |

                **注意**：一致性是**目的**，原子性、隔离性、持久性是**手段**。

                ## 2 并发带来的四类问题

                | 问题 | 描述 |
                |---|---|
                | **丢失修改** | T1、T2 读同一数据并各自修改，T2 的提交覆盖了 T1 的结果 |
                | **脏读** | T1 修改后未提交，T2 读到了这个未提交的值 |
                | **不可重复读** | T1 两次读同一数据，期间 T2 修改并提交，两次结果不同 |
                | **幻读** | T1 两次按条件查询，期间 T2 插入新行，第二次多出记录 |

                ## 3 封锁类型

                - **排它锁（X 锁）**：加了 X 锁的数据，其他事务既不能读也不能写；
                - **共享锁（S 锁）**：加了 S 锁的数据，其他事务可以读但不能写。

                相容矩阵：

                | 当前锁 \\ 请求锁 | S | X |
                |---|---|---|
                | S | 相容 | 冲突 |
                | X | 冲突 | 冲突 |

                ## 4 两段锁协议（2PL）
                事务的封锁分两个阶段：
                1. **扩展阶段**：只能加锁，不能解锁；
                2. **收缩阶段**：只能解锁，不能加锁。

                **定理**：遵守两段锁协议的调度一定是可串行化的调度。
                但要注意，2PL 保证可串行化，却**不能避免死锁**。

                ## 5 死锁的处理
                - **预防**：一次封锁法（一次申请全部锁）、顺序封锁法（按固定顺序申请资源）；
                - **检测**：维护事务等待图，出现环即检测到死锁；
                - **解除**：选择一个代价最小的事务回滚（撤销其全部操作并释放锁）。

                ## 6 隔离级别与代价
                SQL 标准定义了四个隔离级别，级别越高一致性越强、并发度越低：

                | 隔离级别 | 脏读 | 不可重复读 | 幻读 |
                |---|---|---|---|
                | 读未提交 | 可能 | 可能 | 可能 |
                | 读已提交 | 不可能 | 可能 | 可能 |
                | 可重复读 | 不可能 | 不可能 | 可能 |
                | 可串行化 | 不可能 | 不可能 | 不可能 |

                MySQL InnoDB 的默认级别是**可重复读**，并通过 MVCC + 间隙锁
                在很大程度上避免了幻读。
                """));

        list.add(new ItemSeed(
                "关系代数：SQL 查询的形式化基础",
                "用关系代数表达式解释 SQL 查询的语义，说明优化器为何能做等价改写。",
                "DOCUMENT", "course.slides",
                List.of("数据库", "SQL", "索引优化"),
                List.of("DB.THEORY", "DB.THEORY.ALGEBRA", "DB.SQL", "DB.SQL.JOIN", "DB.SQL.INDEX"),
                "课程讲义整理", null, """
                # 关系代数：SQL 查询的形式化基础

                ## 1 为什么学习关系代数
                SQL 是**声明式**语言，用户只描述「要什么」，不描述「怎么做」。
                数据库能够自由选择执行方式的前提，是查询有**形式化的语义**——
                这正是关系代数提供的。

                理解了关系代数，才能真正理解为什么优化器可以把一个慢查询改写成快查询。

                ## 2 基本运算

                | 运算 | 符号 | 含义 |
                |---|---|---|
                | 选择 | σ_条件(R) | 按行筛选，对应 SQL 的 `WHERE` |
                | 投影 | π_属性(R) | 按列裁剪，对应 SQL 的 `SELECT` 列 |
                | 并 | R ∪ S | 集合并 |
                | 差 | R − S | 集合差 |
                | 笛卡尔积 | R × S | 两两组合 |

                ## 3 导出运算

                **连接**是派生运算，可表示为选择与笛卡尔积的组合：
                ```
                R ⋈_θ S  ≡  σ_θ(R × S)
                ```
                自然连接 `R ⋈ S` 是 θ 为「同名属性相等」时的特例。

                **除法**用于表达「对所有…都满足」的语义，
                典型场景是「查询选修了全部课程的学生」。

                ## 4 等价变换规则
                优化器依靠这些规则做**逻辑优化**：

                1. **选择下推**：σ 尽可能早执行，把中间结果变小；
                2. **投影下推**：π 尽可能早执行，减少列数；
                3. **连接交换律与结合律**：R ⋈ S = S ⋈ R，(R ⋈ S) ⋈ T = R ⋈ (S ⋈ T)；
                4. **选择对连接的分配**：σ_F(R ⋈ S) = σ_F1(R) ⋈ σ_F2(S)，当 F 可拆分时。

                ## 5 与本系统的对应

                以本系统的检索查询为例：
                ```sql
                SELECT i.* FROM kb_item i
                WHERE i.category_id = 12 AND i.status = 'PUBLISHED'
                ORDER BY i.published_at DESC LIMIT 20;
                ```
                其关系代数表示为：
                ```
                π_*( σ_(category_id=12 ∧ status='PUBLISHED')(kb_item) )
                ```
                依据**选择下推**，σ 应在扫描 `kb_item` 时立即执行。
                当 `(category_id, status, published_at)` 上存在复合索引时，
                数据库可以在 B+ 树上直接定位到满足条件的连续区间，
                连排序都省了（索引本身有序）——这正是「最左前缀原则」的代数解释。

                ## 6 小结
                关系代数把「查询要什么」与「如何执行」彻底分离。
                分离带来的是**优化空间**：只要代数表达式等价，执行方式可以任意选择。
                这就是为什么写 SQL 时应当关注「语义是否等价」，
                而把「怎么执行」交给优化器。
                """));

        list.add(new ItemSeed(
                "E-R 模型到关系模式的转换规则与实例",
                "系统地列出七条转换规则，并用知识管理系统的 E-R 图逐步演示。",
                "DOCUMENT", "course.slides",
                List.of("数据库", "E-R模型", "软件设计"),
                List.of("DB.MODEL.ER", "DB.DESIGN.CONCEPTUAL", "DB.DESIGN.LOGICAL",
                        "DB.THEORY.NORMALIZATION"),
                "课程讲义整理", null, """
                # E-R 模型到关系模式的转换

                ## 1 七条转换规则

                | 编号 | 规则 | 说明 |
                |---|---|---|
                | R1 | 实体 → 关系模式 | 实体属性成为关系属性，实体标识成为码 |
                | R2 | 1:1 联系 | 任一端的主码放入另一端作为外码，联系属性随之放入 |
                | R3 | 1:N 联系 | **1 端**的主码放入 **N 端**作为外码，联系属性放入 N 端 |
                | R4 | M:N 联系 | 单独转换为一个关系模式，码为两端主码的组合 |
                | R5 | 多元联系 | 单独转换，码为各实体主码的组合 |
                | R6 | 同一实体集内的联系 | 按上方规则处理，只是两端来自同一实体 |
                | R7 | 弱实体集 | 以属主实体的码 + 自身部分码作为组合码 |

                ## 2 实例演示

                ### 2.1 实体转换（R1）
                ```
                知识条目(条目id, 标题, 摘要, 正文, 资产类型, 状态, 创建时间)
                分类(分类id, 名称, 编码, 描述)
                标签(标签id, 名称, 颜色)
                本体概念(概念id, 编码, 名称, 描述)
                ```

                ### 2.2 1:N 联系（R3）
                「分类—知识条目」是 1:N。按 R3，
                把**1 端（分类）的主码**放入 **N 端（知识条目）**：
                ```
                知识条目(条目id, 标题, ..., 分类id)     -- 分类id 为外码
                ```
                对比：如果反过来把「条目id」放进分类表，
                一条分类记录就要放多个条目 id，违反 1NF。

                ### 2.3 M:N 联系（R4）
                「知识条目—标签」是 M:N，必须单独建表：
                ```
                条目标签(条目id, 标签id)   -- 码为 (条目id, 标签id)
                ```

                ### 2.4 带属性的 M:N 联系
                「知识条目—本体概念」也是 M:N，且联系本身有属性：
                ```
                条目概念(条目id, 概念id, 标注来源, 置信度)
                ```
                **关键点**：`标注来源`（人工/自动）与 `置信度` 描述的是
                「某条目标注了某概念」这件**联系**，而不是条目或概念自身的属性，
                因此必须放在关联表里。这是初学者最容易出错的地方。

                ### 2.5 同一实体内的联系（R6）
                「本体概念」自身的上下位关系是一棵自反的层次树：
                ```
                本体概念(概念id, 名称, ..., 父概念id)   -- 父概念id 引用本表的 概念id
                ```
                这是自反的 1:N 联系。查询「某概念的全部后代」时，
                若只用邻接表（父id）需要递归查询；
                因此系统额外维护了**物化路径** `path`（形如 `/1/5/12/`），
                用 `path LIKE '/1/5/%'` 一次查出整棵子树。

                ## 3 转换成关系模式后的检查
                转换完成后必须做两件事：
                1. **规范化检查**：逐个模式判断是否满足 3NF/BCNF，识别部分依赖与传递依赖；
                2. **外码完整性检查**：确认每个外码都指向存在的目标，并决定删除策略
                   （`CASCADE` / `SET NULL` / `RESTRICT`）。

                本系统中，条目删除时其标签关联、概念标注、版本记录、检索分块都应级联删除
                （`ON DELETE CASCADE`），因为这些数据脱离了条目就没有意义；
                而分类删除时条目的分类字段应置空（`ON DELETE SET NULL`），
                因为条目本身仍有价值，只是暂未归类。
                """));

        list.add(new ItemSeed(
                "需求获取与需求分析方法实践总结",
                "总结访谈、原型、观察三类获取手段，以及结构化分析与面向对象分析的差异。",
                "DOCUMENT", "course.slides",
                List.of("需求分析", "软件工程", "UML", "实验报告"),
                List.of("SE.REQ", "SE.REQ.ELICITATION", "SE.REQ.ANALYSIS", "SE.REQ.VALIDATION",
                        "SE.REQ.MANAGEMENT", "SE.REQ.SPEC"),
                "课程讲义整理", null, """
                # 需求获取与需求分析方法实践总结

                ## 1 需求获取的手段

                | 手段 | 适用场景 | 优点 | 风险 |
                |---|---|---|---|
                | 访谈 | 利益相关者少、领域陌生 | 信息密度高，可追问 | 依赖被访者表达，易遗漏 |
                | 问卷 | 相关者众多、需量化 | 覆盖面广，成本低 | 无法追问，问题设计难 |
                | 原型 | 用户说不清想要什么 | 直观，反馈快 | 可能被误认为已完成的系统 |
                | 观察 | 现有业务流程、隐性需求 | 发现用户没意识到的需求 | 耗时，可能干扰被观察者 |
                | 文档分析 | 已有系统、规范约束 | 客观，成本低 | 文档可能过时 |

                **实践建议**：先用文档分析建立领域认知，再用访谈确认与补充，
                对「用户也说不清」的部分用原型激发反馈。

                ## 2 需求分析的两种范式

                ### 2.1 结构化分析（SA）
                以**数据流**为核心，用 DFD（数据流图）与数据字典建模。
                关注「数据在系统中如何流动与变换」。
                适合以数据处理为主的系统。

                ### 2.2 面向对象分析（OOA）
                以**对象**为核心，用 UML 的用例图、类图、时序图建模。
                关注「系统由哪些对象构成、对象如何协作」。
                适合交互复杂、状态多变的系统。

                **本系统采用 OOA**：因为知识管理系统的核心是
                「知识条目」「概念」「版本」「会话」等一组对象及其协作关系，
                用类图与时序图表达比数据流图更自然。

                ## 3 需求分析的四项质量要求

                | 要求 | 含义 | 反例 |
                |---|---|---|
                | **完整性** | 所有必要信息都包含 | 只写了功能，没写性能指标 |
                | **一致性** | 需求之间不矛盾 | 一处要求「删除需二次确认」，另一处说「一键删除」 |
                | **无歧义** | 每种表述只有一种解读 | 「系统应快速响应」——多快算快？ |
                | **可验证** | 能设计测试判断是否满足 | 「界面要美观」无法直接验证 |

                ## 4 需求验证的手段
                1. **需求评审**：组织相关方逐条审阅，是最有效也最常用的手段；
                2. **原型演示**：让用户直观确认理解是否正确；
                3. **一致性检查**：交叉比对，发现矛盾；
                4. **可验证性检查**：对每条需求追问「如何测试它」。

                ## 5 需求变更管理
                需求变更是常态，不是异常。管理的关键是**控制变更的影响范围**：

                1. 建立**需求基线**：确认后的需求冻结为基线；
                2. **变更申请与评估**：评估变更对设计、代码、测试、进度的影响；
                3. **变更审批**：由变更控制委员会（CCB）决定是否接受；
                4. **双向可追溯**：需求 ↔ 设计 ↔ 代码 ↔ 测试用例 建立追踪矩阵。

                本系统的需求规格说明书中即包含一张需求追踪矩阵（见《知识管理系统需求规格说明书》），
                把每条需求映射到对应的设计与测试用例。

                ## 6 常见陷阱
                1. **把解决方案当需求**：用户说「要一个导出按钮」，
                   背后的需求可能是「需要把数据交给同事」——后者可能有更好的实现方式；
                2. **忽略非功能需求**：性能、安全、可用性往往在后期才被提出，返工代价高；
                3. **需求蔓延**：小改动不断累积，最终超出计划，
                   需要通过基线管理与变更评估来约束。
                """));

        list.add(new ItemSeed(
                "内容管理系统版本策略配置指南",
                "面向管理员：如何配置全局/分类/条目三级的版本策略并验证其生效。",
                "DOCUMENT", "course.lab",
                List.of("版本管理", "内容管理", "答辩"),
                List.of("KM.CMS", "KM.CMS.VERSION", "KM.CMS.WORKFLOW", "SE.PM.CONFIG"),
                "系统使用手册", null, """
                # 版本策略配置指南

                ## 1 策略模型

                系统支持三级版本策略，查找顺序为 **条目级 → 分类级 → 全局级**，
                命中即停止；都未命中时使用配置文件的默认值。

                | 作用域 | 适用场景 | 优先级字段 |
                |---|---|---|
                | `ITEM` | 某篇高频修改的核心文档需要更细的版本粒度 | 同作用域内数值大者优先 |
                | `CATEGORY` | 某类内容（如技术报告）统一要求更宽松或更严格的建版条件 | 同上 |
                | `GLOBAL` | 全站默认行为 | 同上 |

                ## 2 策略字段

                | 字段 | 含义 | 取值建议 |
                |---|---|---|
                | `interval_days` | 距上次快照达到该天数即建版 | 高频内容 1 天，稳定内容 7 天 |
                | `min_change_count` | 累计修改达到该次数即建版 | 与上一个配合，通常 3~10 |
                | `max_versions` | 保留的版本上限，超出淘汰最旧 | 20~100，视内容价值定 |
                | `auto_snapshot` | 是否启用自动建版 | 关闭后仅手工建版与发布时建版 |
                | `enabled` | 策略是否生效 | —— |

                **触发条件是「或」**：两个阈值满足任意一个就建版。

                ## 3 配置示例

                ### 场景一：核心设计文档要求每次修改都可回溯
                ```
                作用域：ITEM，scopeId = 12（知识管理系统 系统体系结构设计说明书）
                interval_days    = 0
                min_change_count = 1
                max_versions     = 100
                ```
                效果：每次保存都建版。

                ### 场景二：技术报告类内容按周归档
                ```
                作用域：CATEGORY，scopeId = 6（技术报告）
                interval_days    = 7
                min_change_count = 20
                max_versions     = 30
                ```
                效果：一周内多次修改只留一个版本，节省版本数量。

                ### 场景三：全站默认
                ```
                作用域：GLOBAL
                interval_days    = 1
                min_change_count = 5
                max_versions     = 50
                ```

                ## 4 如何验证策略生效

                系统提供了**策略判定接口**，会明确解释「为什么现在该/不该建版」：

                ```
                GET /api/items/{itemId}/versions/snapshot-decision
                ```

                返回示例：
                ```json
                {
                  "due": true,
                  "reason": "距上次快照 2 天，已达到 1 天的天数阈值",
                  "intervalDays": 1,
                  "minChangeCount": 5,
                  "daysSinceLastSnapshot": 2,
                  "pendingChangeCount": 0,
                  "policyName": "默认全局策略"
                }
                ```

                条目列表接口返回的每条记录中也带有 `snapshotDue` 布尔字段，
                界面据此在条目卡片上显示「建议建版」提示。

                ## 5 常见问题

                **Q：为什么修改了内容却没有生成新版本？**
                A：按顺序检查 ——
                1. 内容是否真的变化了？系统用正文 MD5 判断，只有格式变动（如多余空格）不会建版；
                2. 是否满足阈值？调用上面的判定接口查看 `reason`；
                3. 适用的策略是否 `auto_snapshot = 1` 且 `enabled = 1`？

                **Q：版本太多占空间怎么办？**
                A：调小 `max_versions`，然后调用 `POST /api/versions/prune` 触发清理。
                注意清理是按条目独立进行的，只淘汰超过上限的最旧版本。

                **Q：回滚会删除中间的版本吗？**
                A：不会。回滚是「把旧内容写回并生成一个新版本」，
                版本链只增不减。回滚前系统还会为当前状态自动留一份快照，
                防止回滚本身造成内容丢失。

                **Q：切换了向量模型后要做什么？**
                A：向量模型与版本策略无关，但同样需要「派生数据重建」的思路 ——
                到「索引状态」页确认「过期模型」列表，然后触发全量重建索引。
                """));

        list.addAll(knowledgeCards());
        return list;
    }

    /**
     * 重算标签使用次数。
     *
     * <p>导入时直接写关联表，绕过了服务层的计数维护逻辑，
     * 因此结束后需要统一重算一次 —— 否则「热门标签」会全部为 0。</p>
     */
    private void recalculateTagUsage() {
        Map<Long, Integer> usage = new HashMap<>();
        for (Object[] row : tagLinkRepository.countGroupByTag()) {
            usage.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        for (Tag t : tagRepository.findAll()) {
            t.setUseCount(usage.getOrDefault(t.getId(), 0));
            tagRepository.save(t);
        }
    }

    /** 重算分类的条目计数。 */
    private void recalculateCategoryCounts() {
        for (Category c : categoryRepository.findAll()) {
            long count = itemRepository.countByCategoryIdAndDeleted(c.getId(), 0);
            c.setItemCount((int) count);
            categoryRepository.save(c);
        }
    }

    /** 附件数由 item 上的附件挂载关系决定，此处只做日志性核对。 */
    private void recalculateAttachmentCounts() {
        long orphans = attachmentRepository.findAll().stream()
                .filter(a -> a.getItemId() == null).count();
        if (orphans > 0) {
            log.warn("存在 {} 个未挂载到任何条目的附件", orphans);
        }
    }

    // ==================================================================
    // 示例文件
    // ==================================================================

    /**
     * 为部分条目生成真实可解析的示例文件。
     *
     * <p>目的不是「凑附件数量」，而是让两个功能可被真实验证：</p>
     * <ol>
     *   <li><b>多格式支持</b>：PDF / Word / Excel / PNG / Markdown 各有样本；</li>
     *   <li><b>附件正文进入检索</b>：PDF 用 PDFBox 抽取、DOCX/XLSX 用 POI 抽取，
     *       抽取内容会写入条目正文，因此可以用附件里的独有词汇搜到该条目 ——
     *       这是「知识库里有这个文件」与「能搜到文件里的内容」之间的关键差别。</li>
     * </ol>
     *
     * <p>音视频样本未随附：生成合法的 MP4/H.264 需要编码器，
     * 且会把仓库撑大。上传链路对音视频完全支持（见 storage.allowed-extensions），
     * 演示时可现场上传。</p>
     */
    private int seedAttachments() {
        Map<String, Long> idByTitle = itemRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeItem::getTitle, KnowledgeItem::getId, (a, b) -> a));

        int[] count = {0};
        boolean cjkPdf = fileFactory.supportsChinesePdf();
        if (!cjkPdf) {
            log.warn("未找到中文字体，生成的 PDF 示例将使用英文占位内容（不影响正文抽取与检索演示）");
        }

        // ---- 体系结构：架构图 + 设计说明书 PDF ----
        attach(idByTitle, "系统体系结构设计说明书", () -> fileFactory.architectureDiagram(
                        "知识管理系统 体系结构",
                        List.of("表现层", "应用层", "数据层", "文件存储")),
                "知识管理系统-体系结构图.png", count);
        attach(idByTitle, "系统体系结构设计说明书", () -> fileFactory.pdf(
                        "知识管理系统 系统体系结构设计说明书",
                        List.of(
                                "一、总体架构",
                                "系统采用前后端分离的三层架构，自上而下分为表现层、应用层与数据层。",
                                "表现层基于 Vue 3 与 Element Plus，负责界面渲染与交互；",
                                "应用层基于 Spring Boot 3，按业务域划分为知识库、知识本体、搜索引擎、",
                                "检索增强问答、内容管理、归档审计与统计看板八个模块；",
                                "数据层使用 MySQL 8.0，字符集为 utf8mb4，中文全文检索采用 ngram 解析器。",
                                "",
                                "二、关键技术决策",
                                "1. 全文检索直接使用 MySQL 全文索引而非独立搜索引擎，",
                                "   原因在于本课题数据规模为万级，且业务数据与索引同库可保证强一致。",
                                "2. 向量以 float32 小端序列存入 MEDIUMBLOB 字段，检索时在内存中计算余弦相似度。",
                                "3. 索引重建通过事件机制异步触发，避免阻塞用户的保存操作。",
                                "",
                                "三、模块边界",
                                "知识库模块负责知识资产的增删改查与分类标签管理；",
                                "本体模块负责概念层次、关系三元组、图谱与推理；",
                                "检索模块提供全文检索与高级检索；",
                                "问答模块负责分块、向量化、混合检索与生成。")),
                "知识管理系统-体系结构设计说明书.pdf", count);

        // ---- 需求规格说明：SRS PDF ----
        attach(idByTitle, "知识管理系统需求规格说明书（SRS）", () -> fileFactory.pdf(
                        "知识管理系统 需求规格说明书",
                        List.of(
                                "1 引言",
                                "本文档定义知识管理系统的功能需求与非功能需求，作为设计、实现与验收的依据。",
                                "",
                                "2 功能性需求",
                                "FR-1 知识库：支持知识资产的上传、编辑、删除与批量归档；",
                                "     应支持按分类、标签、知识点三个维度组织内容；",
                                "     应以本体建模知识的逻辑结构，覆盖不少于二十个知识点与五种关系。",
                                "FR-2 搜索引擎：支持全文检索、高级检索与检索增强问答；",
                                "     全文检索需返回相关度排序，并在结果中高亮命中词。",
                                "FR-3 内容管理：内容具备草稿、待审、已发布、已归档的状态流转；",
                                "     提供按指定天数或修改次数的版本管理，支持差异比对与回滚；",
                                "     支持多种文件格式与多媒体内容。",
                                "",
                                "3 非功能性需求",
                                "性能：常规检索响应时间不超过一秒；",
                                "可用性：界面美观友好，关键操作三步内可达；",
                                "安全性：基于角色的访问控制，写操作需鉴权，关键操作留审计日志；",
                                "可维护性：前后端分离，模块边界清晰，核心算法有注释说明。",
                                "",
                                "4 需求追踪矩阵（节选）",
                                "FR-1.3 对应本体模块三张表，测试用例 TC-ONT-01 至 TC-ONT-05；",
                                "FR-3.2 对应版本策略表，测试用例 TC-VER-01 至 TC-VER-08。")),
                "知识管理系统-需求规格说明书.pdf", count);

        // ---- 数据库设计：E-R 图 + 设计说明书 DOCX ----
        attach(idByTitle, "数据库逻辑结构设计与规范化分析", () -> fileFactory.erDiagram(),
                "知识管理系统-E-R图.png", count);
        attach(idByTitle, "数据库逻辑结构设计与规范化分析", () -> fileFactory.docx(
                        "数据库逻辑结构设计说明书",
                        List.of(
                                "一、关系模式清单",
                                "知识条目(条目id, 标题, 摘要, 正文, 纯文本, 资产类型, 分类id, 作者id, 状态, 版本号, 浏览次数, 累计修改次数, 上次快照时间)",
                                "分类(分类id, 名称, 编码, 父分类id, 层级, 物化路径, 条目计数)",
                                "本体概念(概念id, 编码, 名称, 父概念id, 层级, 物化路径, 知识域, 难度, 重要度)",
                                "本体关系(关系id, 源概念id, 目标概念id, 关系类型, 权重)",
                                "条目概念(条目id, 概念id, 标注来源, 置信度)",
                                "历史版本(版本id, 条目id, 版本号, 标题, 正文快照, 变更说明, 变更来源, 编辑人)",
                                "检索分块(分块id, 条目id, 块序号, 文本, 向量, 向量维度, 向量模型)",
                                "",
                                "二、范式判定",
                                "条目标签关系的码为(条目id, 标签id)，两个属性均为主属性，满足第三范式与 BCNF。",
                                "知识条目的码为条目id，各非主属性直接依赖于码，不存在传递依赖，满足第三范式。",
                                "若把分类名称冗余进知识条目，则存在条目id到分类id再到分类名称的传递依赖，",
                                "只能满足第二范式，并会带来更新异常。",
                                "",
                                "三、有意的反规范化",
                                "分类表的条目计数、标签表的使用次数、知识条目的纯文本字段均为冗余设计，",
                                "目的是加快读路径。代价是写路径需要保证一致性，因此系统为每个冗余字段",
                                "都提供了重算入口，即使出现漂移也能一键修复。",
                                "",
                                "四、索引设计",
                                "kb_item 表在状态与发布时间上建复合索引以支持列表页过滤与排序；",
                                "在标题、摘要、纯文本上建 ngram 全文索引以支持中文检索；",
                                "ont_relation 表在源概念与目标概念上分别建索引以支持图谱双向遍历。")),
                "知识管理系统-数据库设计说明书.docx", count);

        // ---- 测试：用例清单 XLSX + 测试报告 PDF ----
        attach(idByTitle, "软件测试用例设计与覆盖率分析报告", () -> fileFactory.xlsx(
                        "测试用例清单",
                        List.of("用例编号", "测试项", "前置条件", "操作步骤", "预期结果", "优先级"),
                        List.of(
                                List.of("TC-SEARCH-01", "全文检索", "已登录", "检索「软件测试」", "命中不少于五条，标题高亮，按相关度排序", "高"),
                                List.of("TC-SEARCH-02", "零结果处理", "已登录", "检索不存在的词", "返回空列表并给出检索建议", "中"),
                                List.of("TC-SEARCH-03", "高级检索", "已登录", "分类=技术报告且标签=答辩", "仅返回同时满足两个条件的条目", "高"),
                                List.of("TC-ONT-01", "知识图谱渲染", "已登录", "打开知识图谱页", "节点与边正常渲染，图例显示七种关系类型", "高"),
                                List.of("TC-ONT-02", "本体推理", "已登录", "从关系模型沿先修关系推理三层", "依次得到关系代数、SQL、索引与查询优化", "高"),
                                List.of("TC-ONT-03", "本体统计", "已登录", "查看本体统计", "概念数、关系数与图谱数据一致", "中"),
                                List.of("TC-RAG-01", "问答引用", "已登录", "提问需求分析与需求获取的区别", "返回回答并附至少两条可跳转的引用", "高"),
                                List.of("TC-RAG-02", "无资料处理", "已登录", "提问知识库未涉及的问题", "如实说明资料未涵盖，不编造答案", "高"),
                                List.of("TC-VER-01", "自动建版", "编辑权限", "连续保存条目五次", "达到次数阈值后自动生成新版本", "高"),
                                List.of("TC-VER-02", "版本比对", "编辑权限", "比对版本一与版本二", "正确显示增删行与知识点标注变化", "高"),
                                List.of("TC-VER-03", "版本回滚", "编辑权限", "回滚至版本一", "正文还原，版本链新增一条回滚记录", "高"),
                                List.of("TC-CMS-01", "状态流转", "编辑权限", "新建条目后提交审核再发布", "状态依次流转，发布后出现在公开列表", "高"),
                                List.of("TC-CMS-02", "附件检索", "编辑权限", "上传 PDF 附件后检索其正文关键词", "能检索到该条目，说明附件正文已进入索引", "高"),
                                List.of("TC-AUTH-01", "越权拦截", "浏览者账号", "调用创建条目的接口", "返回无权限", "高"),
                                List.of("TC-AUTH-02", "匿名可读", "未登录", "访问知识条目列表接口", "正常返回公开列表", "中"))),
                "知识管理系统-测试用例清单.xlsx", count);
        attach(idByTitle, "软件测试用例设计与覆盖率分析报告", () -> fileFactory.pdf(
                        "知识管理系统 软件测试报告",
                        List.of(
                                "一、测试策略",
                                "采用自底向上的策略：先对工具类与服务类做单元测试，再做模块间集成测试，",
                                "最后做系统测试与验收测试。",
                                "",
                                "二、测试结果",
                                "系统测试通过率百分之百；核心检索与问答链路的语句覆盖率为百分之九十四点二，",
                                "分支覆盖率为百分之八十八点六。",
                                "",
                                "三、缺陷记录（节选）",
                                "BUG-012 版本差异比对在正文含中文标点时错位，严重程度中，已修复。",
                                "BUG-019 切换向量模型后旧向量未失效导致检索结果异常，严重程度高，已修复；",
                                "        修复方式是在分块上记录向量模型标识与维度，检索时只与同模型向量比较。",
                                "BUG-023 全文检索单字查询无结果，严重程度低，属已知限制——",
                                "        ngram 解析器按二元切分，单字不构成词项，界面已作提示。",
                                "",
                                "四、结论",
                                "系统功能完备，核心链路覆盖良好，已知限制均有明确规避方式。")),
                "知识管理系统-测试报告.pdf", count);

        // ---- 本体：图谱全景图 ----
        attach(idByTitle, "知识本体建模方法与设计说明", () -> fileFactory.graphOverviewDiagram(),
                "知识本体全景图.png", count);

        // ---- 团队过程：进度表 XLSX ----
        attach(idByTitle, "小组分工与项目进度记录", () -> fileFactory.xlsx(
                        "项目进度",
                        List.of("日期", "任务", "负责人", "交付物", "状态"),
                        List.of(
                                List.of("第7周 周一", "需求访谈与需求分析", "全员", "需求列表", "已完成"),
                                List.of("第7周 周二", "概念结构设计", "张伟、李娜", "E-R 图", "已完成"),
                                List.of("第7周 周三", "逻辑结构设计", "李娜", "建表脚本", "已完成"),
                                List.of("第7周 周四", "本体建模", "张伟", "本体清单", "已完成"),
                                List.of("第7周 周五", "架构设计", "张伟、王强", "接口文档", "已完成"),
                                List.of("第8周 周一", "知识库与检索模块", "李娜、王强", "功能可用", "已完成"),
                                List.of("第8周 周二", "检索增强问答流水线", "王强", "问答可用", "已完成"),
                                List.of("第8周 周三", "前端页面实现", "刘洋、陈静", "界面可用", "已完成"),
                                List.of("第8周 周四", "联调与测试", "赵磊", "测试报告", "已完成"),
                                List.of("第8周 周五", "缺陷修复与报告撰写", "全员", "课程设计报告", "进行中"))),
                "知识管理系统-项目进度表.xlsx", count);

        // ---- 检索方案：选型报告附 Markdown 源码 ----
        attach(idByTitle, "知识库全文检索方案选型报告", () -> fileFactory.markdown(
                        """
                        # 全文检索方案选型报告（附件源码）

                        本附件用于验证「Markdown 文件的正文字段同样会被抽取并进入检索」这一能力。
                        附件中出现的独有术语包括：倒排表归并、二元切分、词汇鸿沟、相关度重排序。

                        ## 决策

                        选择 MySQL 8 全文索引配合 ngram 解析器，理由是数据规模与一致性成本。

                        ## 已知限制

                        单字查询不会命中，因为 ngram_token_size 为 2。
                        """),
                "全文检索方案选型-正文源码.md", count);

        // ---- 归档规范：维护规范 Markdown ----
        attach(idByTitle, "知识库内容维护规范与归档策略", () -> fileFactory.markdown(
                        """
                        # 知识库内容维护规范（附件版）

                        本附件中的独有术语：信噪比衰减、孤立标签清理、内容缺口报表。

                        ## 归档判定

                        长期零访问的内容由管理员批量评审后归档，归档不等于删除。
                        """),
                "知识库维护规范.md", count);

        return count[0];
    }

    /** 生成并挂载一个附件；生成失败不影响其余数据导入。 */
    private void attach(Map<String, Long> idByTitle, String itemTitle,
                        FileSupplier supplier, String fileName, int[] count) {
        Long itemId = idByTitle.get(itemTitle);
        if (itemId == null) {
            return;
        }
        try {
            byte[] bytes = supplier.get();
            if (bytes != null && bytes.length > 0) {
                fileStorageService.storeBytes(fileName, bytes, itemId);
                count[0]++;
            }
        } catch (Exception e) {
            log.warn("生成示例附件「{}」失败：{}", fileName, e.toString());
        }
    }

    /** 允许 lambda 抛出受检异常。 */
    @FunctionalInterface
    private interface FileSupplier {
        byte[] get() throws Exception;
    }

    // ==================================================================
    // 知识卡片：单个知识点的精炼释义
    // ==================================================================

    private List<ItemSeed> knowledgeCards() {
        List<ItemSeed> cards = new ArrayList<>();

        cards.add(card("知识卡片：单元测试", "对软件最小可测单元的独立测试，通常由开发者编写。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.UNIT", "SE.TEST"),
                """
                ## 单元测试

                **定义**：对软件中最小可测单元（函数、方法、类）进行的独立验证，
                通常由开发者随代码一同编写。

                **要点**
                - 单元测试**不依赖**外部系统，数据库、网络、文件都应替换为测试替身（Stub/Mock）；
                - 遵循 **FIRST 原则**：Fast（快）、Isolated（隔离）、Repeatable（可重复）、
                  Self-validating（自验证）、Timely（及时）；
                - 一个测试方法只断言一件事，失败时能立刻定位问题。

                **常见误区**
                1. 把「调用真实数据库的测试」当作单元测试 —— 那是集成测试；
                2. 只测正常路径，不测异常与边界；
                3. 为了覆盖率写没有断言的测试。

                **相关**：集成测试、白盒测试、测试用例设计。
                """));

        cards.add(card("知识卡片：集成测试", "验证多个模块组装后的接口与协作是否正确。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.INTEGRATION", "SE.TEST"),
                """
                ## 集成测试

                **定义**：在单元测试通过后，把多个模块按设计组装起来，
                验证**模块间接口与协作**是否正确。

                **为什么要单独做这一步**
                每个模块单独测试都对，组合起来仍可能出错：
                - 接口参数含义不一致（一个用秒、一个用毫秒）；
                - 数据传递丢失或顺序错误；
                - 全局状态被多个模块相互污染。

                **组装策略**

                | 策略 | 做法 | 优点 | 缺点 |
                |---|---|---|---|
                | 一次性组装 | 全部模块一起测 | 简单 | 出错难定位 |
                | 自顶向下 | 从主控模块开始，下层用桩模块替代 | 早期可验证主流程 | 需要大量桩 |
                | 自底向上 | 从底层开始，上层用驱动模块替代 | 底层测试充分 | 主流程验证晚 |
                | 三明治 | 上下结合，中间层最后 | 折中 | 管理复杂 |

                **相关**：单元测试、系统测试、桩模块与驱动模块。
                """));

        cards.add(card("知识卡片：白盒测试与黑盒测试", "按是否关心内部实现划分的两类测试方法。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.WHITEBOX", "SE.TEST.BLACKBOX", "SE.TEST"),
                """
                ## 白盒测试 vs 黑盒测试

                | 维度 | 白盒测试 | 黑盒测试 |
                |---|---|---|
                | 依据 | 程序内部逻辑结构 | 需求规格与外部接口 |
                | 别名 | 结构测试、逻辑覆盖 | 功能测试 |
                | 主要方法 | 语句覆盖、判定覆盖、条件覆盖、路径覆盖 | 等价类划分、边界值分析、判定表、错误推测 |
                | 执行者 | 通常是开发者 | 通常是测试人员 |
                | 主要阶段 | 单元测试 | 系统测试、验收测试 |

                **两者互补而非替代**：
                白盒能发现「代码没写到某个分支」这类**遗漏**，
                黑盒能发现「代码实现了错误的需求」这类**偏差**。

                一个功能正确但实现混乱的系统，白盒测试会暴露问题；
                一个实现优雅但不符合需求的系统，只有黑盒测试能发现。

                **相关**：覆盖率分析、测试用例设计、单元测试。
                """));

        cards.add(card("知识卡片：等价类划分", "把输入域划分为若干等价类，每类取一个代表值测试。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.CASE", "SE.TEST.BLACKBOX", "SE.TEST"),
                """
                ## 等价类划分

                **核心思想**：如果某个输入值能发现缺陷，同一等价类中的其他值
                大概率也能发现同一缺陷。因此每类取一个代表值即可，
                用最少的用例覆盖最多的情形。

                **划分步骤**
                1. 确定输入域，区分**有效等价类**（合法输入）与**无效等价类**（非法输入）；
                2. 为每个有效等价类设计一个用例；
                3. 为**每个**无效等价类各设计一个用例 ——
                   注意一次只能包含一个非法输入，否则第一个被拒绝后其余无法验证。

                **示例：用户名合法范围为 3~32 字符**
                | 等价类 | 类型 | 代表值 |
                |---|---|---|
                | 长度 3~32 且非空 | 有效 | `admin` |
                | 长度 < 3 | 无效 | `ab` |
                | 长度 > 32 | 无效 | 33 个字符的串 |
                | 空串 | 无效 | `""` |
                | 含非法字符 | 无效 | `ad min` |

                **与边界值分析配合**：等价类选定方向，边界值选定具体取值。
                两者结合是最常用的黑盒用例设计组合。

                **相关**：边界值分析、测试用例设计、白盒测试与黑盒测试。
                """));

        cards.add(card("知识卡片：边界值分析", "大量缺陷集中在输入域边界，因此边界要重点测试。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.CASE", "SE.TEST.BLACKBOX", "SE.TEST"),
                """
                ## 边界值分析

                **依据**：经验统计表明大量缺陷集中在输入域的**边界**上
                （差一错误、循环次数少一次、阈值判断用了 `<` 而非 `<=`）。

                **取值规则**
                对取值范围 `[min, max]`，取：
                - 略小于 min：`min − 1`
                - 恰好等于 min：`min`
                - 范围内任意值：`nominal`
                - 恰好等于 max：`max`
                - 略大于 max：`max + 1`

                **示例：版本策略的天数阈值判定**
                设阈值 `intervalDays = 1`，判定式为 `days >= intervalDays`：
                | 输入 days | 期望结果 |
                |---|---|
                | 0 | 不建版（未达阈值） |
                | 1 | **建版**（恰好命中边界） |
                | 2 | 建版 |

                **与等价类的关系**
                等价类回答「测哪些类别」，边界值回答「每类中取哪个值」。
                实践中通常合并使用：对每个边界各取一个用例，
                再为每个等价类补一个中间值。

                **相关**：等价类划分、测试用例设计。
                """));

        cards.add(card("知识卡片：回归测试", "修改后重跑测试，确认没有破坏原有功能。",
                List.of("软件测试", "软件工程"), List.of("SE.TEST.REGRESSION", "SE.TEST", "SE.MAINT"),
                """
                ## 回归测试

                **定义**：在软件发生变更（修缺陷、加功能、重构）之后，
                重新执行已有测试，确认**没有引入新缺陷、没有破坏原有功能**。

                **为什么必需**
                修改与已有功能往往通过共享的代码、数据或状态耦合在一起。
                「修好一个 bug、弄坏两个功能」是软件维护中最常见的事故。

                **测试集的选择策略**
                | 策略 | 做法 | 适用 |
                |---|---|---|
                | 全量回归 | 跑完所有用例 | 变更影响面大、临近发布 |
                | 选择性回归 | 只跑受影响模块及其依赖者 | 日常提交 |
                | 优先级回归 | 按用例重要度与历史缺陷率排序，跑前 N% | 时间受限 |

                **成本控制**
                自动化是回归测试规模化的前提。手工回归在项目后期会变成不可承受的负担，
                因此「可自动化」本身应当成为用例设计的质量目标之一。

                **相关**：软件维护、单元测试、DevOps 中的持续集成。
                """));

        cards.add(card("知识卡片：设计模式——单例与工厂", "创建型模式中最常用的两个及其取舍。",
                List.of("软件设计", "软件工程"), List.of("SE.DESIGN.PATTERN", "SE.DESIGN"),
                """
                ## 设计模式：单例与工厂

                ### 单例模式（Singleton）
                **意图**：保证一个类只有一个实例，并提供全局访问点。
                **适用**：配置管理器、连接池、缓存。

                **实现要点**：构造器私有 + 静态实例 + 静态获取方法。
                多线程下需注意：
                - 饿汉式：类加载即创建，简单但可能浪费；
                - 懒汉式 + 双重检查锁：需 `volatile` 防止指令重排；
                - **静态内部类**：兼具延迟加载与线程安全，推荐写法。

                **警示**：单例本质是**全局状态**，会带来隐式耦合与测试困难。
                如果只是「想少创建几次」，用依赖注入把实例交给容器管理更好。

                ### 工厂模式（Factory）
                **意图**：把对象的创建过程与使用过程分离。
                **适用**：创建逻辑复杂、需按条件选择实现类、需统一管理生命周期。

                **在本系统中的应用**
                `EmbeddingModel` 与 `ChatModel` 都是接口，
                由配置类依据 `application.yml` 决定注入哪个实现
                （本地哈希向量 / 在线接口）。上层业务代码完全不需要知道具体实现类。

                **相关**：软件体系结构设计、接口设计。
                """));

        cards.add(card("知识卡片：UML 类图五种关系", "关联、聚合、组合、依赖、继承的辨析。",
                List.of("UML", "软件设计"), List.of("SE.DESIGN.UML", "SE.DESIGN"),
                """
                ## UML 类图的关系

                | 关系 | 符号 | 语义 | 生命周期 | 代码体现 |
                |---|---|---|---|---|
                | 依赖 | 虚线箭头 | 临时使用 | 无 | 方法参数、局部变量 |
                | 关联 | 实线 | 长期拥有 | 独立 | 成员变量 |
                | 聚合 | 空心菱形 | 整体—部分，可分离 | 部分可独立存在 | 成员变量，外部注入 |
                | 组合 | 实心菱形 | 整体—部分，同生共死 | 部分随整体销毁 | 成员变量，内部 new |
                | 继承 | 空心三角实线 | is-a | —— | `extends` |
                | 实现 | 空心三角虚线 | 接口实现 | —— | `implements` |

                **聚合 vs 组合的判断口诀**
                问一句「整体销毁时，部分还能不能独立存在？」
                - 能 → 聚合。例：班级与学生（班级解散，学生仍在）；
                - 不能 → 组合。例：订单与订单项（订单一删，订单项毫无意义）。

                **本系统实例**
                - 依赖：`RagService` 依赖 `TextUtils`（静态工具，随时调用）；
                - 关联：`KnowledgeItem` 关联 `Category`（通过 categoryId 引用）；
                - 组合：`RagSession` 与 `RagMessage`（会话删除，消息级联删除）。

                **相关**：软件体系结构设计、设计模式。
                """));

        cards.add(card("知识卡片：代码评审与重构", "两项最有效的质量实践及其注意事项。",
                List.of("软件工程", "开源工具"), List.of("SE.CONSTRUCT.REVIEW", "SE.CONSTRUCT.REFACTOR",
                        "SE.CONSTRUCT.CODE"),
                """
                ## 代码评审与重构

                ### 代码评审（Code Review）
                **目的**：不只是找 bug，更重要的是**统一风格**与**传播知识**。

                **有效做法**
                - 单次评审**不超过 400 行** —— 超过后评审者的发现率急剧下降；
                - 评审在**提交后 1 天内**完成，避免积压导致上下文遗忘；
                - 用清单固化关注点：边界条件、异常处理、资源释放、命名、并发安全；
                - 对事不对人：评论针对代码，而非作者。

                **反模式**
                「橡皮图章」式通过、把评审当作风格争论战场、
                只挑格式问题而放过逻辑缺陷。

                ### 重构（Refactoring）
                **定义**：在不改变**外部可观察行为**的前提下，改善代码内部结构。

                **关键词是「不改变行为」**，因此重构的前提是有测试 ——
                没有测试的重构是「改代码」，不是重构。

                **常见手法**：提取方法、内联、搬移函数、以多态取代条件表达式、引入参数对象。

                **何时重构**：代码出现「坏味道」时（重复代码、过长函数、过长参数列表、发散式变化）。
                **不**在临近交付时进行大规模重构。

                **相关**：编码实现、软件维护、软件再工程。
                """));

        cards.add(card("知识卡片：软件过程模型对比", "瀑布、迭代、敏捷、螺旋的适用场景与取舍。",
                List.of("软件工程", "项目管理"), List.of("SE.PROCESS", "SE.PROCESS.WATERFALL",
                        "SE.PROCESS.AGILE", "SE.PROCESS.SPIRAL", "SE.PROCESS.ITERATIVE"),
                """
                ## 软件过程模型对比

                | 模型 | 核心特征 | 适用场景 | 主要风险 |
                |---|---|---|---|
                | 瀑布 | 阶段线性推进，每阶段有评审 | 需求稳定、技术成熟、合同型项目 | 需求变更代价极高 |
                | 迭代/增量 | 分多轮构建，每轮产出可运行增量 | 需求大致清楚但细节待明确 | 需要良好架构以支撑增量 |
                | 螺旋 | 风险驱动的迭代，每轮含风险分析 | 大型、高风险、创新型项目 | 管理复杂、成本高 |
                | 敏捷/Scrum | 短周期交付，拥抱变化 | 需求快速变化、小团队 | 依赖用户深度参与；大规模协作难 |

                **选择建议**
                - 需求能一次说清 → 瀑布；
                - 需求需逐步澄清 → 迭代；
                - 技术不确定性高 → 螺旋；
                - 市场窗口短、需快速反馈 → 敏捷。

                **重要认识**：过程模型不是教条。
                本课程设计实际上是「瀑布 + 迭代」的混合：
                需求与数据库设计一次性做扎实（瀑布），
                编码与界面则小步快跑、边做边调（迭代）。

                **相关**：Scrum 框架、DevOps、进度管理。
                """));

        cards.add(card("知识卡片：关键路径法与进度管理", "如何找出决定项目工期的最长路径。",
                List.of("项目管理", "软件工程"), List.of("SE.PM.SCHEDULE", "SE.PM", "SE.PM.RISK"),
                """
                ## 关键路径法（CPM）

                **关键路径**：项目网络图中从起点到终点**耗时最长**的路径。
                它决定了项目的**最短可能工期**。

                **核心概念**
                - **最早开始时间（ES）**：前序任务都完成后才能开始；
                - **最晚开始时间（LS）**：不延误总工期的前提下最晚能开始的时间；
                - **总时差（浮动时间）** = LS − ES。
                  关键路径上的任务总时差为 **0**，任何延误都直接拖后总工期。

                **管理含义**
                1. 资源应优先保障关键路径上的任务；
                2. 想缩短工期，只能压缩关键路径 —— 压缩非关键任务毫无意义；
                3. 压缩到一定程度会出现**次关键路径**，关键路径发生转移，需要重新计算。

                **示例**
                需求分析(3d) → 数据库设计(2d) → 编码(5d) → 测试(3d)：共 13 天。
                若并行「前端开发(4d)」与「后端开发(5d)」，前端不在关键路径上，
                它有 1 天时差，晚一天开始不影响总工期。

                **相关**：风险管理、成本估算、软件度量。
                """));

        cards.add(card("知识卡片：COCOMO 成本估算模型", "以代码行数为输入的经典工作量估算模型。",
                List.of("项目管理", "软件工程"), List.of("SE.PM.COST", "SE.PM", "SE.PM.METRIC"),
                """
                ## COCOMO 模型

                **COCOMO**（COnstructive COst MOdel）以**代码行数（KLOC）**为输入，
                估算软件开发的工作量（人月）与进度（月）。

                ### 基本模型
                ```
                工作量 E = a × (KLOC)^b      （单位：人月）
                进度   D = c × E^d            （单位：月）
                人员   P = E / D
                ```
                系数依项目类型不同：

                | 类型 | 特征 | a | b |
                |---|---|---|---|
                | 组织型 | 小团队、熟悉领域、需求宽松 | 2.4 | 1.05 |
                | 半独立型 | 中等规模、经验混合 | 3.0 | 1.12 |
                | 嵌入型 | 硬约束（硬件、法规）、复杂接口 | 3.6 | 1.20 |

                ### 中级模型
                在基本模型上乘以 15 个**成本驱动因子**
                （产品复杂度、可靠性要求、团队经验、进度压力等），每个因子取值 0.7~1.65。

                ### 局限与适用
                - **优点**：有经验数据支撑，可重复、可比较；
                - **缺点**：以代码行为输入，而代码行在设计阶段尚不可知；
                  且不同语言的「一行代码」工作量差异巨大。

                **现代替代**：功能点分析（以功能规模代替代码行）、
                故事点估算（敏捷场景下的相对估算）。

                **相关**：功能点分析、软件度量、进度管理。
                """));

        cards.add(card("知识卡片：风险管理与配置管理", "项目管理中两个容易被忽视的支撑职能。",
                List.of("项目管理", "软件工程"), List.of("SE.PM.RISK", "SE.PM.CONFIG", "SE.PM",
                        "SE.CONSTRUCT.VCS"),
                """
                ## 风险管理

                **流程**：风险识别 → 风险分析（概率 × 影响）→ 风险应对计划 → 风险监控。

                **四种应对策略**
                | 策略 | 做法 | 例 |
                |---|---|---|
                | 规避 | 改变方案消除风险 | 不用不熟悉的技术 |
                | 转移 | 把风险转给第三方 | 采购成熟组件代替自研 |
                | 减轻 | 降低概率或影响 | 关键模块提前做原型验证 |
                | 接受 | 制定应急预案，不做额外投入 | 留出应急缓冲时间 |

                **实践要点**：风险登记册要**定期复查**，
                已发生的风险转为「问题」处理，新识别的风险入册。

                ## 配置管理
                **目的**：保证产品在演化过程中**完整、一致、可追溯**。

                **四项活动**
                1. **配置标识**：确定哪些是配置项（代码、文档、脚本、构建产物）并编号；
                2. **版本控制**：记录每次变更，支持并行开发与回溯（工具：Git）；
                3. **变更控制**：变更需申请、评估、审批、记录；
                4. **配置审计**：验证配置项与基线的一致性。

                **基线（Baseline）**：经过评审确认、后续变更需走流程的配置状态。
                需求基线、设计基线、产品基线是三个关键节点。

                本系统的版本管理即配置管理思想在内容层面的应用。

                **相关**：版本管理、内容工作流、进度管理。
                """));

        cards.add(card("知识卡片：SQL 子查询与连接查询", "两种多表查询写法的语义与性能差异。",
                List.of("数据库", "SQL"), List.of("DB.SQL.SUBQUERY", "DB.SQL.JOIN", "DB.SQL",
                        "DB.SQL.INDEX"),
                """
                ## 子查询与连接查询

                ### 子查询
                嵌套在另一语句中的查询，按返回形态分为：
                - **标量子查询**：返回单值，可用在 `SELECT` 或比较运算中；
                - **行子查询**：返回一行多列；
                - **表子查询**：返回多行多列，常用在 `FROM` 中（派生表）。

                按是否引用外层列分为**不相关子查询**与**相关子查询**。
                相关子查询会对外层每一行执行一次，性能风险较高。

                ### 连接的四种形态
                | 连接 | 结果 |
                |---|---|
                | 内连接 `INNER JOIN` | 只保留两表都匹配的行 |
                | 左外连接 `LEFT JOIN` | 保留左表全部行，右表无匹配则补 NULL |
                | 右外连接 `RIGHT JOIN` | 反之 |
                | 全外连接 `FULL JOIN` | 保留两侧全部行（MySQL 需用 UNION 模拟） |

                ### 何时用哪个
                - 需要「**不满足条件的也列出来**」（如找出没有知识条目的分类）
                  → 用外连接，而非子查询；
                - 只需「是否存在」的判断 → 用 `EXISTS`，
                  它在找到第一个匹配行时就短路返回，常比 `IN` 更高效；
                - **陷阱**：`NOT IN` 遇到子查询结果包含 NULL 时，
                  整个条件恒为 UNKNOWN，一条也查不到。此时应改用 `NOT EXISTS`。

                **相关**：索引与查询优化、关系代数。
                """));

        cards.add(card("知识卡片：视图、存储过程与触发器", "数据库端的三类可编程对象。",
                List.of("数据库", "SQL"), List.of("DB.SQL.VIEW", "DB.SQL.PROCEDURE", "DB.SQL",
                        "DB.IMPL.SECURITY"),
                """
                ## 视图、存储过程与触发器

                ### 视图（View）
                由查询定义的**虚表**，不实际存储数据。

                **价值**
                1. **简化查询**：把复杂的多表连接封装成一个「表」；
                2. **权限控制**：只授权用户访问视图，隐藏底层的敏感列与行；
                3. **逻辑独立性**：底层表结构变化时，只要视图定义不变，应用无需修改。

                **限制**：带聚合、`DISTINCT`、`GROUP BY` 的视图通常**不可更新**。

                ### 存储过程（Stored Procedure）
                在数据库端预编译并保存的过程化程序单元。

                **优点**：减少网络往返、可复用、便于统一授权。
                **缺点**：业务逻辑分散到数据库、调试困难、跨库移植性差、
                版本管理与代码评审困难。

                **实践建议**：核心业务逻辑放在应用层，
                存储过程只用于性能敏感的批量操作。

                ### 触发器（Trigger）
                由 `INSERT/UPDATE/DELETE` 事件自动执行的程序单元。

                **适用**：审计日志、维护冗余字段、强制复杂约束。
                **风险**：**级联触发**可能导致难以预期的连锁反应与死锁；
                触发器中出错会使整个事务回滚，且错误信息常难以定位。

                **实践建议**：触发器中只做简单、快速、无副作用的操作。

                **相关**：完整性约束、数据库安全性、性能调优。
                """));

        cards.add(card("知识卡片：完整性约束与数据库安全性", "保证数据正确与不被非法访问的机制。",
                List.of("数据库", "事务"), List.of("DB.IMPL.INTEGRITY", "DB.IMPL.SECURITY", "DB.IMPL",
                        "DB.DESIGN.PHYSICAL"),
                """
                ## 完整性约束

                | 类型 | 内容 | 实现 |
                |---|---|---|
                | 实体完整性 | 主码唯一且非空 | `PRIMARY KEY` |
                | 参照完整性 | 外码取值必须是被参照表中存在的主码值，或为 NULL | `FOREIGN KEY` |
                | 用户定义完整性 | 语义约束，如「年龄 0~150」 | `CHECK`、`NOT NULL`、`UNIQUE` |

                **参照完整性的删除策略**
                | 策略 | 行为 | 适用 |
                |---|---|---|
                | `CASCADE` | 级联删除子记录 | 子记录脱离父记录无意义（条目→标签关联） |
                | `SET NULL` | 外码置空 | 子记录仍有价值（条目→分类） |
                | `RESTRICT` | 有子记录时禁止删除 | 强依赖关系 |

                选择哪种策略是**业务语义**的体现，不能随意取默认值。

                ## 数据库安全性

                **层次**
                1. **身份认证**：谁可以连接数据库；
                2. **访问控制**：授权（`GRANT`/`REVOKE`）、角色、视图机制；
                3. **数据加密**：传输加密（TLS）、存储加密；
                4. **审计**：记录敏感操作，用于事后追溯。

                **最小权限原则**：应用连接数据库的账号只授予其**必需**的权限。
                本系统的应用账号不需要 `DROP DATABASE` 权限 ——
                很多生产事故源于应用账号权限过大。

                **相关**：事务、视图、审计日志。
                """));

        cards.add(card("知识卡片：数据库恢复技术", "利用日志与备份把数据库恢复到一致状态。",
                List.of("数据库", "事务"), List.of("DB.IMPL.RECOVERY", "DB.IMPL.TRANSACTION", "DB.IMPL",
                        "DB.IMPL.CONCURRENCY"),
                """
                ## 数据库恢复技术

                ### 故障类型
                | 类型 | 影响 | 恢复方式 |
                |---|---|---|
                | 事务故障 | 单个事务失败 | 撤销该事务（Undo） |
                | 系统故障 | 内存丢失但磁盘完好 | 撤销未提交事务 + 重做已提交事务 |
                | 介质故障 | 磁盘损坏 | 用备份 + 日志重做 |

                ### 日志（Log）
                记录事务对数据的每次修改，典型格式先写日志后写数据
                （**WAL**，Write-Ahead Logging），
                每条日志含事务标识、数据项标识、旧值、新值。

                **为什么必须「先写日志」**
                若先写数据后写日志，系统在两者之间崩溃时，
                这次修改既无法撤销也无法重做，数据库就失去了可恢复性。

                ### Undo 与 Redo
                - **Undo**：对未提交事务，用日志的**旧值**反向覆盖；
                - **Redo**：对已提交事务，用日志的**新值**重新写入。

                ### 检查点（Checkpoint）
                定期把内存中的脏页刷盘并记录检查点。
                恢复时只需从最近检查点开始扫描日志，无需重放全部历史，大幅缩短恢复时间。

                ### 备份策略
                - **全量备份** + **增量备份**组合，平衡存储与恢复时间；
                - 备份必须**定期演练恢复** —— 没验证过的备份不能算备份。

                **相关**：事务、并发控制、完整性约束。
                """));

        cards.add(card("知识卡片：NoSQL 与数据仓库", "两类面向不同场景的非传统数据存储。",
                List.of("数据库", "索引优化"), List.of("DB.MODEL.NOSQL", "DB.NEW.BIGDATA", "DB.NEW",
                        "DB.MODEL"),
                """
                ## NoSQL

                **出现的动因**：传统关系数据库在处理海量数据、高并发写入、
                灵活模式（schema-less）时遇到瓶颈。

                | 类型 | 数据模型 | 典型产品 | 适用场景 |
                |---|---|---|---|
                | 键值 | key → value | Redis | 缓存、会话、计数器 |
                | 文档 | JSON 文档集合 | MongoDB | 内容管理、半结构化数据 |
                | 列族 | 行键 → 列族 | HBase | 海量稀疏数据、时序 |
                | 图 | 节点与边 | Neo4j | 社交网络、知识图谱 |

                **取舍**：NoSQL 通常牺牲**强一致性**换取**可扩展性与可用性**
                （CAP 定理），且大多不支持跨记录事务与复杂连接。

                **选型建议**：默认用关系数据库；
                当出现明确的场景需求（超大规模图遍历、极高写入吞吐）时再引入 NoSQL，
                并接受随之而来的架构复杂度。

                ## 数据仓库

                **与操作型数据库的区别**

                | 维度 | OLTP（事务型） | OLAP（分析型） |
                |---|---|---|
                | 面向 | 日常业务操作 | 决策分析 |
                | 操作 | 增删改查单条记录 | 大规模聚合查询 |
                | 数据 | 当前、明细 | 历史、汇总 |
                | 设计 | 规范化（3NF） | 星型/雪花模型，允许冗余 |

                **ETL**：抽取（Extract）→ 转换（Transform）→ 加载（Load），
                是把业务数据汇入数据仓库的常规流程。

                **相关**：数据库新技术、分布式数据库、性能调优。
                """));

        cards.add(card("知识卡片：分布式数据库与向量数据库", "两类新兴数据库技术的核心概念。",
                List.of("数据库", "向量检索"), List.of("DB.NEW.DISTRIBUTED", "DB.NEW.VECTOR", "DB.NEW",
                        "KM.SEARCH.VECTOR"),
                """
                ## 分布式数据库

                **核心特征**：数据物理上分布在多个节点，但逻辑上是一个统一的数据库。

                **必须解决的问题**
                1. **数据分片**：水平分片（按行）或垂直分片（按列），分片键的选择直接影响负载均衡；
                2. **副本一致性**：多副本之间如何保持一致（强一致 vs 最终一致）；
                3. **分布式事务**：跨节点事务的原子性，常用两阶段提交（2PC）
                   或基于消息的最终一致方案；
                4. **全局查询优化**：查询可能跨多个节点，需决定数据在哪里执行、结果如何汇总。

                **CAP 定理**：一致性、可用性、分区容错性三者不可同时满足。
                分布式系统必然发生网络分区，因此实际选择是在 C 与 A 之间权衡。

                ## 向量数据库

                **解决的问题**：在高维向量集合中做**近邻检索**（ANN）。
                传统 B+ 树索引在高维空间失效（维度灾难），需要专门的索引结构。

                | 算法 | 思路 | 特点 |
                |---|---|---|
                | HNSW | 分层可导航小世界图 | 查询快、精度高、内存占用大 |
                | IVF | 先聚类再在最近的簇内搜 | 内存友好、需训练 |
                | PQ | 向量量化压缩 | 大幅省内存、精度有损 |

                **在本课题中的取舍**
                本系统的检索分块规模在万级，直接**暴力计算余弦相似度**耗时约 30ms，
                因此没有引入向量数据库 —— 用 `MEDIUMBLOB` 存储向量 + 内存比对即可。
                只有当规模达到百万级、暴力比对无法满足响应要求时，引入 ANN 索引才划算。

                **相关**：向量检索、文本向量化、RAG。
                """));

        cards.add(card("知识卡片：倒排索引", "全文检索的核心数据结构。",
                List.of("全文检索", "倒排索引"), List.of("KM.SEARCH.INVERTED", "KM.SEARCH.FULLTEXT",
                        "KM.SEARCH"),
                """
                ## 倒排索引

                **正排 vs 倒排**
                - 正排索引：文档 → 文档中包含的词（`doc1: [软件, 测试, 用例]`）；
                - **倒排索引**：词 → 包含该词的文档列表（`软件: [doc1, doc5, doc9]`）。

                检索的场景是「给一个词，找包含它的文档」，
                倒排结构无需扫描全部文档。

                **词典与倒排表**
                - **词典**：所有出现过的词，通常用 B+ 树或哈希索引组织；
                - **倒排表**：每个词对应的文档列表，通常按文档编号有序存储，
                  便于多词查询时做**求交/求并**。

                **核心操作**
                查询「软件 AND 测试」：分别取出两个词的倒排表求交集；
                「软件 OR 测试」：求并集。
                有序存储使这些集合运算可以线性归并完成。

                **中文的挑战**
                中文没有天然词边界，需要先分词。MySQL 的 ngram 解析器选择了更朴素的路子：
                **按固定长度（默认 2）切分**，把「软件测试」切成「软件」「件测」「测试」。
                优点是不依赖词典、鲁棒；缺点是索引体积大、单字查询无法命中。

                **相关**：全文检索、中文分词、索引与查询优化。
                """));

        cards.add(card("知识卡片：中文分词方法", "基于词典、基于统计与混合方法。",
                List.of("中文分词", "全文检索"), List.of("KM.SEARCH.ANALYSIS", "KM.SEARCH.FULLTEXT",
                        "KM.SEARCH.INVERTED"),
                """
                ## 中文分词方法

                ### 1. 基于词典的方法
                按词典匹配切分，常见策略：
                - **最大匹配**（正向/逆向）：贪心地取最长匹配词；
                - **最短路径**：把句子构造成词图，求概率最大路径（jieba 的主要模式）。

                优点是快且可控；缺点是**未登录词**（词典中不存在的新词）无法识别。

                **经典歧义示例**：「南京市长江大桥」
                可切成「南京市 / 长江大桥」或「南京 / 市长 / 江大桥」——
                后者语法成立但语义荒谬。统计模型能借助词频避免这类错误。

                ### 2. 基于统计的方法
                用序列标注模型（HMM、CRF、BiLSTM-CRF）把分词视为对每个字打标签
                （B-词首 / M-词中 / E-词尾 / S-单字），能较好处理未登录词。
                缺点是需要标注语料训练。

                ### 3. 混合方法
                词典提供高置信度切分与领域术语，统计模型处理未登录词 ——
                这是当前主流的工程做法。

                ### 在本系统中的应用
                分词用于三处：
                1. **关键词抽取**：对正文分词后按 TF 与领域词典加权选出关键词；
                2. **检索片段生成**：定位命中词在原文中的位置并截取上下文；
                3. **高亮**：把查询词分词后逐一在结果文本中标记。

                注意：**MySQL 全文索引不依赖应用层分词**，它使用自己的 ngram 切分。
                两者独立工作，因此调整应用的 jieba 词典不会影响数据库索引的行为。

                **相关**：倒排索引、全文检索、混合检索与 RRF 融合。
                """));

        cards.add(card("知识卡片：文本分块与向量化", "RAG 流水线中决定检索质量的两个环节。",
                List.of("RAG", "向量检索"), List.of("KM.RAG.CHUNK", "KM.RAG.EMBED", "KM.RAG",
                        "KM.SEARCH.VECTOR"),
                """
                ## 文本分块

                **为什么必须分块**
                1. 向量模型有最大输入长度，超长文本会被截断；
                2. 块越长向量越「平均」，语义越模糊，检索精度越低；
                3. 送入生成模型时块是引用的最小单位，块过大导致引用不精确。

                **块大小的权衡**
                | 块大小 | 优点 | 缺点 |
                |---|---|---|
                | 小（~200 字） | 语义聚焦，检索精确 | 上下文不足，可能割裂论述 |
                | 大（~1000 字） | 上下文完整 | 噪声多，向量语义模糊 |

                常见折中是 **300~500 字符**，并设置 **10%~20% 的重叠**，
                避免关键信息正好落在切分点上被割裂。

                **切分位置的优先级**
                段落边界 > 句子边界（。！？；）> 长度硬切。
                本系统按此优先级实现：先切自然段，超长段落再按句末标点切，最后才硬切。

                ## 文本向量化

                **目标**：把文本映射到固定维度的稠密向量，
                使**语义相近**的文本在向量空间中**距离更近**。

                | 方法 | 原理 | 特点 |
                |---|---|---|
                | 词袋 / 哈希向量 | 统计词频，哈希到固定维度 | 零依赖、快、仅捕捉词汇重叠 |
                | 神经句向量 | 预训练模型编码 | 捕捉语义、可处理同义改写、需模型/API |

                **必须注意的一点**
                不同模型产出的向量**不在同一向量空间**，不能混合比较。
                因此每个分块必须记录「由哪个模型产生」（本系统存 `embed_model` 与 `dim`），
                检索时只与同模型的向量比较；**切换模型后必须重建全部索引**。

                这是「派生数据」的通用原则：**永远记录它是怎么来的**。

                **相关**：RAG、向量检索、混合检索与 RRF 融合、向量数据库。
                """));

        cards.add(card("知识卡片：混合检索与 RRF 融合", "为什么要用两条通道，以及如何合并排名。",
                List.of("混合检索", "RAG", "全文检索"), List.of("KM.SEARCH.HYBRID", "KM.SEARCH.FULLTEXT",
                        "KM.SEARCH.VECTOR", "KM.RAG"),
                """
                ## 混合检索与 RRF 融合

                ### 为什么需要两条通道
                | 通道 | 强项 | 弱项 |
                |---|---|---|
                | 词面（全文索引） | 术语精确、可解释、快 | 无法处理同义改写，存在词汇鸿沟 |
                | 语义（向量检索） | 能匹配不同表述 | 精度较低，易召回泛泛而谈的内容 |

                两者的失败模式**不重叠**，因此组合后整体效果优于任一单通道。

                ### 如何合并：RRF
                直接相加分数不可行 ——
                余弦相似度在 0~1 之间，BM25 分数可能是 0~30，
                量纲完全不同，加权求和需要大量调参且不稳定。

                **倒数排名融合（Reciprocal Rank Fusion）** 绕开了这个问题：

                ```
                score(d) = Σ_i  1 / (k + rank_i(d))
                ```

                其中 `rank_i(d)` 是文档 d 在第 i 条通道中的排名（从 1 开始），
                `k` 是平滑常数（经验值 60）。

                **它只用排名、不用分数**，因此天然规避了量纲问题。

                **k 的作用**：k 越大，各排名的差距被拉平得越多；
                k 越小，头部排名的优势越突出。取 60 是经验上的稳健选择。

                ### 一个直观例子
                文档 A 在向量通道排第 1、关键字通道排第 10；
                文档 B 在两条通道都排第 3。取 k=60：
                - A: 1/61 + 1/70 = 0.0164 + 0.0143 = **0.0307**
                - B: 1/63 + 1/63 = 0.0159 + 0.0159 = **0.0317**

                B 胜出。**RRF 奖励「两条通道都认可」的文档**，
                而不是「某一条通道特别认可」的文档 —— 这正是融合想要的语义。

                **相关**：全文检索、向量检索、RAG。
                """));

        cards.add(card("知识卡片：提示词设计与引用溯源", "RAG 生成环节的质量控制手段。",
                List.of("RAG", "答辩"), List.of("KM.RAG.PROMPT", "KM.RAG.CITATION", "KM.RAG"),
                """
                ## 提示词设计

                RAG 的系统提示需要解决四件事：

                1. **限定知识边界**：「只依据提供的资料作答，不要引入资料之外的推测」——
                   这是抑制幻觉最关键的一句；
                2. **要求引用**：「在每个结论后用【来源 N】标注依据」——
                   让回答可核对，也让用户知道哪些内容有资料支撑；
                3. **规定输出结构**：先结论、再分点、必要时举例；
                4. **处理不确定**：「资料不足时明确说明，而非编造」。

                **资料的组织方式同样重要**
                - 每个片段前标注编号与来源标题，模型才能正确引用；
                - 片段太长会挤占上下文且引入噪声，过长时应截断；
                - 片段顺序按相关度排列，重要的放前面（模型对前文更敏感）。

                ## 引用溯源

                **为什么必需**
                1. **可信度**：用户可以核对结论是否真的成立；
                2. **可追责**：出错的结论能定位到具体来源，便于修正语料；
                3. **可学习**：用户顺着引用读原文，往往比自己提问收获更多。

                **实现要点**
                - 引用要精确到**片段**而非仅到条目，否则用户还要在长文中自己找；
                - 展示**相关度得分与通道排名**，让用户判断这条引用的可信程度；
                - 提供从引用**直接跳转原文**的链接。

                **诚实的边界比华丽的答案更重要**
                RAG 系统最容易犯的错是「资料里没有，但模型编了一个像样的答案」。
                明确说「现有资料未涵盖」虽然不够令人满意，
                但对知识库而言，**可信度一旦受损就难以挽回**。

                **相关**：RAG、文本分块与向量化、混合检索与 RRF 融合。
                """));

        cards.add(card("知识卡片：知识资产与分类体系", "知识库中「存什么」与「怎么组织」。",
                List.of("内容管理", "知识图谱"), List.of("KM.KB.ASSET", "KM.KB.TAXONOMY", "KM.KB",
                        "KM.CMS"),
                """
                ## 知识资产

                **定义**：具有复用价值、可被独立管理的内容实体。

                | 类型 | 说明 | 典型格式 |
                |---|---|---|
                | 文档 | 讲义、手册、规范 | Markdown、PDF、Word |
                | 技术报告 | 分析、设计、测试报告 | Markdown、PDF |
                | 论文 | 文献与综述 | PDF |
                | 视频 | 教学视频、演示录屏 | MP4 |
                | 图片 | 架构图、流程图、截图 | PNG、JPG |
                | 代码 | 接口说明、示例 | 文本、Markdown |
                | 思维导图 | 知识结构梳理 | 图片、专有格式 |

                **关键设计**：文档类附件上传时**自动抽取正文**并入库，
                使其内容可被全文检索命中、也可进入 RAG 检索范围。
                否则「知识库里有这个文件」和「能搜到文件里的内容」是两回事。

                ## 分类体系（Taxonomy）

                **与本体（Ontology）的区别**

                | 维度 | 分类 | 本体 |
                |---|---|---|
                | 视角 | 内容管理者的收纳视角 | 领域知识的结构视角 |
                | 回答 | 「这份资料放在哪个文件夹」 | 「这个知识点与其他知识点什么关系」 |
                | 结构 | 严格的树（每个节点一个父节点） | 图（任意关系连接） |
                | 数量 | 少而稳定（十几个） | 多而细（上百个） |
                | 变更 | 由管理员维护 | 由领域专家维护 |

                **两者互补，不应互相替代**。
                本系统中，一篇《RAG 设计报告》：
                - 分类挂在「技术报告 / 设计文档」下 —— 回答「这是什么类型的文档」；
                - 标注 RAG、文本分块、混合检索等本体概念 —— 回答「它讲了哪些知识」。

                于是既能按文档类型浏览，也能按知识主题聚合。

                **相关**：知识本体建模、多媒体内容管理、内容工作流。
                """));

        cards.add(card("知识卡片：知识图谱三元组与可视化", "用图结构表示知识，并让结构可被看见。",
                List.of("知识图谱", "本体建模"), List.of("KM.GRAPH.TRIPLE", "KM.GRAPH.VIZ",
                        "KM.GRAPH", "KM.GRAPH.RELATION"),
                """
                ## 三元组表示

                **形式**：`(主语, 谓词, 宾语)`，例如：
                ```
                (关系代数, PREREQUISITE_OF, SQL)
                (单元测试, SUB_CLASS_OF, 软件测试)
                ```

                **为什么是三元组**
                它是「一条原子断言」的最小完备表示：
                两个实体 + 一个关系，不能再简化，也不需要更多。
                大量三元组堆叠起来即构成一个知识图。

                **现实中的三元组常带附加信息**
                - **权重**：关系的强度（本系统用 0~1）；
                - **来源**：这条断言从哪来（专家录入、文本抽取）；
                - **时间**：何时成立（有些知识有时效性）；
                - **自然语言断言**：便于直接展示给用户。

                ## 图谱可视化

                **目标不是好看，而是降低认知负荷**。本系统的编码方式：

                | 视觉通道 | 编码内容 | 理由 |
                |---|---|---|
                | 节点颜色 | 知识域（软件工程/数据库/知识管理） | 三个域一眼可辨 |
                | 节点大小 | 关联的知识条目数（对数压缩） | 内容越丰富越显眼 |
                | 边的颜色 | 关系类型 | 与图例对应，可筛选 |
                | 高亮 | 当前选中的概念 | 定位不迷失 |

                **交互设计要点**
                1. **筛选优先于缩小**：节点过多时应提供按知识域、按关系类型的筛选，
                   而不是让用户缩放寻找；
                2. **邻域视图**：点击节点后展示 N 跳邻域，比全图更聚焦；
                3. **与知识库打通**：点击节点能跳转到关联的知识条目 ——
                   否则图谱只是一个漂亮的装饰。

                **相关**：知识本体建模、关系抽取与推理、知识资产与分类体系。
                """));

        cards.add(card("知识卡片：内容工作流与知识库维护", "内容从草稿到归档的完整生命周期。",
                List.of("内容管理", "归档策略"), List.of("KM.CMS.WORKFLOW", "KM.CMS.MEDIA",
                        "KM.CMS.ARCHIVE", "KM.CMS"),
                """
                ## 内容工作流

                四态流转模型：
                ```
                草稿(DRAFT) ──提交──→ 待审(PENDING) ──发布──→ 已发布(PUBLISHED)
                    ↑                                              │
                    └──────────── 撤回 ───────────────────────────┘
                                                                   │
                                                          归档(ARCHIVED) ──恢复──→ 草稿
                ```

                | 状态 | 可见范围 | 可编辑 | 进检索 | 进 RAG |
                |---|---|---|---|---|
                | 草稿 | 作者 + 管理员 | ✓ | ✗ | ✗ |
                | 待审 | 作者 + 管理员 | ✓ | ✗ | ✗ |
                | 已发布 | 所有人 | ✓（触发建版） | ✓ | ✓ |
                | 已归档 | 独立入口 | ✗ | ✗ | ✗ |

                **为什么恢复后回到草稿而不是已发布**
                归档通常意味着「内容可能已过时」。
                直接恢复为已发布会让未经复核的内容直接对所有人可见 ——
                这是知识库质量事故的常见来源。

                ## 多媒体内容管理

                - **图片**：生成缩略图用于列表页，原图用于详情页；
                - **视频**：支持 HTTP Range 请求，使浏览器可以拖动进度条；
                - **文档**：上传时抽取正文入库，让内容可被检索；
                - **所有类型**：记录媒体类型以决定预览方式（内联播放 / 下载）。

                ## 知识库维护节奏

                | 频率 | 活动 |
                |---|---|
                | 每次录入 | 分类 + 标签 + 知识点标注，填写摘要 |
                | 每周 | 检查「零结果检索词」报表，补充内容缺口 |
                | 每学期 | 归档过时内容，清理孤立标签，复核知识点覆盖率 |

                **相关**：版本管理、知识资产与分类体系、归档与保留策略。
                """));

        cards.add(card("知识卡片：软件质量保证与软件度量", "如何保证质量，以及如何量化。",
                List.of("项目管理", "软件工程"), List.of("SE.PM.QUALITY", "SE.PM.METRIC", "SE.PM",
                        "SE.TEST"),
                """
                ## 软件质量保证（SQA）

                **核心区别：质量控制 vs 质量保证**
                - **质量控制（QC）**：检查**产品**是否合格 —— 测试、评审属于此类；
                - **质量保证（QA）**：确保**过程**按标准执行 —— 过程审计、标准制定属于此类。

                前者是「检测问题」，后者是「预防问题」。
                只做测试不做过程改进，问题会反复出现。

                **SQA 的主要活动**
                1. 制定并维护开发标准与规程；
                2. 过程评审与审计，确认实际执行符合标准；
                3. 收集并分析质量数据，驱动改进；
                4. 组织培训与经验沉淀。

                ## 软件度量

                | 层次 | 度量对象 | 示例指标 |
                |---|---|---|
                | 产品 | 软件本身 | 代码行数、圈复杂度、缺陷密度、响应时间 |
                | 过程 | 开发活动 | 需求变更率、评审发现缺陷率、构建成功率 |
                | 资源 | 人员与环境 | 人月投入、人均缺陷数、工具使用率 |

                **常用指标**
                - **圈复杂度**：`V(G) = 判定节点数 + 1`，反映代码的独立路径数，
                  >10 通常被认为难以测试与维护；
                - **缺陷密度**：每千行代码的缺陷数；
                - **缺陷移除率**：各阶段发现的缺陷占比，反映测试有效性的分布。

                **度量的陷阱**
                1. **度量什么就得到什么**（古德哈特定律）：
                   以代码行数考核产出，就会得到臃肿的代码；
                2. **不可比性**：不同语言、不同复杂度模块的指标不能直接比较；
                3. **度量不是目的**：它的价值在于**驱动改进决策**，而非生成报表。

                **相关**：软件测试、COCOMO 成本估算、风险管理。
                """));

        cards.add(card("知识卡片：数据库物理结构设计", "把逻辑模式映射到具体存储与存取路径。",
                List.of("数据库", "索引优化"), List.of("DB.DESIGN.PHYSICAL", "DB.DESIGN",
                        "DB.DESIGN.DICTIONARY", "DB.SQL.INDEX"),
                """
                ## 数据库物理结构设计

                **任务**：为逻辑模式选择适合应用环境的**存储结构与存取方法**，
                使数据库在时间、空间与维护成本之间取得平衡。

                ### 1. 确定数据的存储结构
                - 是否使用分区表（按时间或范围分区）；
                - 大字段（长文本、二进制）是否独立存储；
                - 压缩策略（对读多写少的归档数据）。

                ### 2. 设计索引
                | 考量 | 说明 |
                |---|---|
                | 建在哪些列上 | 高频出现在 `WHERE`、`JOIN`、`ORDER BY` 中的列 |
                | 复合索引的顺序 | 等值条件在前、范围条件在后；遵循最左前缀 |
                | 索引类型 | B+ 树（范围）、哈希（等值）、全文（文本检索） |
                | 数量控制 | 每个索引都增加写入开销，单表通常不超过 5 个 |

                ### 3. 确定存取路径
                同一查询有多种执行方式（全表扫描、索引扫描、索引覆盖），
                由优化器依据统计信息选择。因此**统计信息的准确性**直接影响性能 ——
                大批量导入后应主动更新统计信息。

                ### 4. 数据字典
                记录所有元数据：表结构、字段类型、约束、索引、视图、权限。
                它是数据库的「说明书」，也是后续维护的依据。

                **本系统的物理设计要点**
                - 全文索引使用 `ngram` 解析器（中文）；
                - 向量以 `MEDIUMBLOB` 存储（比 JSON 省约 4 倍空间）；
                - 分类与本体概念用**物化路径**支持一次查询取整棵子树；
                - 检索日志、审计日志等高频写入表与主表分离。

                **相关**：索引与查询优化、概念结构设计、性能调优。
                """));

        cards.add(card("知识卡片：关系演算", "以谓词逻辑描述查询的非过程化语言。",
                List.of("数据库", "SQL"), List.of("DB.THEORY.CALCULUS", "DB.THEORY", "DB.THEORY.ALGEBRA",
                        "DB.SQL"),
                """
                ## 关系演算

                **定位**：与关系代数并列的另一种关系查询形式化语言。
                关系代数是**过程化**的（描述怎么算），关系演算是**非过程化**的（描述要什么）。

                ### 元组关系演算
                以元组为单位，形式为 `{ t | P(t) }`，
                意为「所有满足谓词 P 的元组 t 的集合」。
                谓词中可用存在量词 `∃` 与全称量词 `∀`。

                **示例**：查询「选修了全部课程的学生」
                ```
                { t | t ∈ 学生 ∧ ∀c (c ∈ 课程 → ∃sc (sc ∈ 选课
                       ∧ sc.学号 = t.学号 ∧ sc.课程号 = c.课程号)) }
                ```
                可以看出，全称量词的表达能力对应关系代数中的「除法」运算。

                ### 域关系演算
                以域（属性取值范围）变量为单位，形式为 `{ <x1, x2, ...> | P(x1, x2, ...) }`。
                典型代表是 QBE（Query By Example），用户填表即可查询。

                ### 安全表达式
                不加限制的元组演算可能产生无限关系（如 `{ t | ¬(t ∈ R) }`）。
                因此要求表达式必须是**安全的**：
                结果中的每个值都必须来自某个有限的关系。这是理论上的重要约束。

                ### 三者等价性
                **关系代数、安全的关系演算、SQL 的核心子集表达能力等价**。
                这不是巧合 —— SQL 的设计正是以关系演算的语义为规范的，
                这也是 SQL 被称作「非过程化语言」的理论依据。

                **相关**：关系代数、SQL 基础、数据库理论。
                """));

        cards.add(card("知识卡片：瀑布模型的适用与局限", "经典过程模型的再认识。",
                List.of("软件工程", "项目管理"), List.of("SE.PROCESS.WATERFALL", "SE.PROCESS",
                        "SE.REQ.MANAGEMENT", "SE.PM.SCHEDULE"),
                """
                ## 瀑布模型的适用与局限

                **阶段划分**
                需求分析 → 总体设计 → 详细设计 → 编码 → 测试 → 运行维护，
                阶段间有明确的评审与文档交付，前一阶段完成才能进入下一阶段。

                **优势**
                1. **文档驱动**：每个阶段都有明确交付物，便于评审与交接；
                2. **里程碑清晰**：进度易于度量与汇报；
                3. **适合合同型项目**：需求在合同中固定，变更需走正式流程。

                **被广泛批评的三点**
                1. **需求必须一次说清**：而实际上用户常在见到系统后才明确需求；
                2. **缺陷发现晚**：测试阶段才发现的错误，修复代价是需求阶段的数十倍；
                3. **交付周期长**：直到后期用户才看到可运行的系统，反馈太迟。

                **但瀑布从未过时**
                批评者常忽略一点：瀑布的「阶段划分」是**认知上的必需** ——
                无论用什么过程模型，需求、设计、编码、测试这些**活动**本身都存在。
                敏捷方法改变的是活动的**组织方式与迭代粒度**，不是取消这些活动。

                **何时应当用瀑布**
                - 需求明确且稳定（如按标准规范实现的系统）；
                - 技术成熟、团队有类似经验；
                - 合同或合规要求阶段化交付与评审。

                **在本课程设计中的体现**
                需求分析与数据库设计采用瀑布式一次做扎实
                （因为三阶段数据库设计本身有严格的先后依赖），
                而编码与界面调整则小步迭代。

                **相关**：迭代开发、敏捷开发、需求变更管理。
                """));

        cards.add(card("知识卡片：敏捷开发与 Scrum", "迭代、增量、拥抱变化的实践框架。",
                List.of("软件工程", "团队协作"), List.of("SE.PROCESS.AGILE", "SE.PROCESS.SCRUM",
                        "SE.PROCESS", "SE.PM.SCHEDULE"),
                """
                ## 敏捷开发与 Scrum

                ### 敏捷宣言的四条价值观
                1. **个体与互动** 高于 流程与工具；
                2. **可工作的软件** 高于 详尽的文档；
                3. **客户合作** 高于 合同谈判；
                4. **响应变化** 高于 遵循计划。

                注意「高于」不等于「不要」—— 敏捷不是拒绝文档与计划，
                而是在冲突时优先选择左边。

                ### Scrum 的三个角色
                | 角色 | 职责 |
                |---|---|
                | 产品负责人（PO） | 管理产品待办列表，决定做什么、优先级 |
                | Scrum Master | 保障流程运转，清除障碍，不是「项目经理」 |
                | 开发团队 | 自组织地完成冲刺目标 |

                ### 五个事件
                - **冲刺（Sprint）**：1~4 周的固定周期；
                - **冲刺计划会**：确定本冲刺的目标与待办项；
                - **每日站会**：15 分钟，同步进度与障碍（不是汇报会）；
                - **冲刺评审**：向干系人演示可工作的增量；
                - **冲刺回顾**：团队反思流程如何改进。

                ### 常见误用
                1. **站会变汇报会**：每人向 Scrum Master 汇报，而非团队间同步；
                2. **冲刺中插入新需求**：破坏冲刺稳定性，冲刺目标失去意义；
                3. **只学形式不学精神**：站会、看板都做了，
                   但依然「计划不可变、变更需层层审批」。

                ### 与本课程设计的对应
                两周的设计周期恰好是一个「大冲刺」：
                第 7 周完成需求与设计，第 8 周编码测试并演示。

                **相关**：软件过程模型对比、DevOps、关键路径法。
                """));

        cards.add(card("知识卡片：DevOps 与持续集成", "打通开发与运维的自动化实践。",
                List.of("软件工程", "开源工具"), List.of("SE.PROCESS.DEVOPS", "SE.PROCESS",
                        "SE.CONSTRUCT.REVIEW", "SE.TEST.REGRESSION"),
                """
                ## DevOps 与持续集成

                **要解决的问题**
                传统模式下开发与运维目标冲突：
                开发希望「快速上线新功能」，运维希望「系统稳定不出事」。
                DevOps 通过**自动化与共担责任**化解这一冲突。

                ### 持续集成（CI）
                开发者频繁地把代码合并到主干，每次合并都自动触发构建与测试。

                **为什么频繁合并更好**
                分支存活越久，与主干的差异越大，合并冲突越复杂、集成失败越难定位。
                「集成地狱」正是长时间不合并的结果。

                **CI 流水线的典型阶段**
                ```
                提交 → 静态检查 → 编译构建 → 单元测试 → 集成测试
                     → 代码质量门禁 → 制品归档 → 通知
                ```

                **质量门禁（Quality Gate）**：流水线中设置硬性条件，
                不满足则中断（如覆盖率低于阈值、存在严重级静态检查告警）。
                这让质量标准**自动执行**，而不依赖人的自觉。

                ### 持续交付与持续部署
                - **持续交付**：流水线保证每次提交的产物**随时可发布**，
                  但发布决策由人做出；
                - **持续部署**：通过全部测试后**自动发布到生产**，无需人工干预。

                两者之间差的是「决策是否自动化」，
                而这取决于系统的测试可信度与业务的风险容忍度。

                ### 基础设施即代码（IaC）
                用代码描述并版本化管理服务器、网络与中间件配置。
                好处是环境可复现、变更可评审、回滚可执行。

                **相关**：代码评审、回归测试、软件配置管理。
                """));

        cards.add(card("知识卡片：软件再工程与逆向工程", "让遗产系统重新可用。",
                List.of("软件维护", "软件工程"), List.of("SE.MAINT.REENGINEER", "SE.MAINT",
                        "SE.MAINT.PERFECTIVE", "SE.CONSTRUCT.REFACTOR"),
                """
                ## 软件再工程与逆向工程

                **问题的由来**
                大量仍承担关键业务的老系统（遗产系统）：
                文档缺失、原作者离职、技术栈过时、难以修改，
                但又**不能简单丢弃**（业务逻辑沉淀其中，重写风险极高）。

                ### 四个相关概念

                | 概念 | 含义 |
                |---|---|
                | **逆向工程** | 从代码反推设计与需求，恢复已丢失的信息 |
                | **重构** | 不改变行为，改善内部结构（在原有架构内） |
                | **再工程** | 逆向工程 + 重构 + 正向工程，可能改变架构 |
                | **重构（Restructuring）** | 仅改变代码的组织形式，不改变架构层次 |

                ### 再工程的典型流程
                ```
                1. 库存分析：盘点系统规模、依赖、业务价值，确定优先级
                2. 逆向工程：恢复设计模型与业务规则
                3. 文档重构：补齐缺失的文档
                4. 代码重构：改善结构，消除坏味道
                5. 数据再工程：迁移数据、清理冗余
                6. 正向工程：引入现代架构与技术栈
                ```

                ### 三种策略的取舍
                | 策略 | 成本 | 风险 | 适用 |
                |---|---|---|---|
                | 弃置重写 | 高 | 高（业务逻辑丢失） | 系统小、逻辑清楚 |
                | 再工程 | 中 | 中 | 系统大、逻辑有价值、可控 |
                | 维持现状 | 低 | 持续累积 | 系统即将退役 |

                **现实中的选择**：多数情况采用**渐进式再工程** ——
                不追求一次性改造完成，而是围绕新需求逐步替换模块（绞杀者模式），
                每次替换都保持系统可运行。

                **相关**：软件维护、重构、软件体系结构设计。
                """));

        cards.add(card("知识卡片：软件维护的四种类型", "维护不是只有「修 bug」。",
                List.of("软件维护", "软件工程"), List.of("SE.MAINT", "SE.MAINT.CORRECTIVE",
                        "SE.MAINT.ADAPTIVE", "SE.MAINT.PERFECTIVE"),
                """
                ## 软件维护的四种类型

                | 类型 | 目的 | 占比（经验值） | 示例 |
                |---|---|---|---|
                | **纠错性维护** | 修复缺陷 | ~20% | 修复计算错误、崩溃 |
                | **适应性维护** | 适应外部环境变化 | ~25% | 操作系统升级、法规变更 |
                | **完善性维护** | 提升性能或增加功能 | ~50% | 优化响应速度、新增报表 |
                | **预防性维护** | 提高未来的可维护性 | ~5% | 重构、补充文档、补充测试 |

                **关键认识**：维护成本通常占软件**全生命周期总成本的 60%~80%**，
                而其中真正「修 bug」的比例并不高 ——
                大部分工作量花在适应环境与满足新需求上。

                这也解释了为什么**可维护性**是重要的质量属性：
                它决定的不是今天的开发速度，而是未来数年的演进成本。

                **维护的难点**
                1. **理解成本高**：维护者往往不是原作者，需要先读懂代码与业务；
                2. **文档滞后**：代码改了文档没改，文档反而误导；
                3. **涟漪效应**：一处修改通过耦合关系影响意想不到的地方 ——
                   这正是回归测试存在的理由。

                **降低维护成本的措施**
                - 编码规范与有意义的命名（代码是最好的文档）；
                - 模块化降低耦合，让修改的影响范围可控；
                - 完善的自动化测试，使修改有信心；
                - 关键决策记录（ADR），保留「为什么这么做」的信息。

                **相关**：软件再工程、回归测试、软件度量。
                """));

        return cards;
    }

    /** 构造一张知识卡片的便捷方法。 */
    private ItemSeed card(String title, String summary, List<String> tags,
                          List<String> conceptCodes, String content) {
        return new ItemSeed(title, summary, "DOCUMENT", "course.cards", tags, conceptCodes,
                "课程知识卡片", null, content);
    }
}
