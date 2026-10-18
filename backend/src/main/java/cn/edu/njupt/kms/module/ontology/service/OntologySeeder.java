package cn.edu.njupt.kms.module.ontology.service;

import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.entity.Relation;
import cn.edu.njupt.kms.module.ontology.entity.RelationType;
import cn.edu.njupt.kms.module.ontology.repository.ConceptRepository;
import cn.edu.njupt.kms.module.ontology.repository.RelationRepository;
import cn.edu.njupt.kms.module.ontology.repository.RelationTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 知识本体初始化器 —— 构建《专业课程设计II》的知识本体。
 *
 * <p><b>本体范围：</b>覆盖本课程两大理论支柱（软件工程、数据库原理）
 * 以及本课题自身的技术领域（知识管理与智能检索），
 * 共 100+ 个知识点、3 个知识域、7 种语义关系。</p>
 *
 * <p><b>建模方式：</b></p>
 * <ul>
 *   <li><b>层次结构</b>（taxonomy）通过 {@code parentId} + 物化路径 {@code path} 表达，
 *       回答「这个知识点属于哪一类」；</li>
 *   <li><b>语义关系</b>通过三元组 {@code (source, relationType, target)} 表达，
 *       回答「这个知识点与其它知识点是什么关系」。</li>
 * </ul>
 *
 * <p>两者互补：层次树是骨架，语义关系是网络。仅有树则知识是孤立的分类，
 * 加上关系才能支撑推理（如沿 {@code PREREQUISITE_OF} 推导学习路径）与查询扩展。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OntologySeeder {

    private final ConceptRepository conceptRepository;
    private final RelationTypeRepository relationTypeRepository;
    private final RelationRepository relationRepository;

    /** 概念种子数据。 */
    private record ConceptSeed(String code, String name, String en, String parentCode, String domain,
                               int difficulty, int importance, String aliases, String description) {
    }

    /** 关系种子数据。 */
    private record RelationSeed(String source, String type, String target, double weight, String note) {
    }

    /** 是否已初始化。 */
    @Transactional(readOnly = true)
    public boolean isInitialized() {
        return conceptRepository.count() > 0;
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Transactional
    public void seed() {
        seedRelationTypes();
        Map<String, Long> codeToId = seedConcepts();
        seedRelations(codeToId);
        log.info("知识本体初始化完成：概念 {} 个，关系类型 {} 种，关系 {} 条",
                conceptRepository.count(), relationTypeRepository.count(), relationRepository.count());
    }

    // ==================================================================
    // 关系类型（7 种，满足题目「≥5 种关系」的要求）
    // ==================================================================

    private void seedRelationTypes() {
        if (relationTypeRepository.count() > 0) {
            return;
        }
        // 配色取自已通过校验的分类色板（相邻色对在色觉缺陷模拟下 ΔE ≥ 9，
        // 正常视觉 ΔE ≥ 19），顺序即图例顺序。图谱中的边同时带文字标签，
        // 使身份识别不依赖颜色单一通道。
        List<RelationType> types = List.of(
                type(RelationType.SUB_CLASS_OF, "上下位（is-a）", "SUPER_CLASS_OF", 1, "#2a78d6",
                        "子概念是父概念的一种特化，如「单元测试」是「软件测试」的一种。传递关系。"),
                type(RelationType.PART_OF, "组成（part-of）", "HAS_PART", 1, "#eb6834",
                        "部分与整体的构成关系，如「需求获取」是「需求工程」的一个环节。"),
                type(RelationType.PREREQUISITE_OF, "先修", "DEPENDS_ON", 1, "#1baf7a",
                        "学习顺序上的前置依赖，如「关系代数」应在「SQL 查询」之前掌握。用于推导学习路径。"),
                type(RelationType.RELATED_TO, "相关", "RELATED_TO", 0, "#eda100",
                        "语义相关但无严格从属关系。对称关系，用于知识导航与查询扩展。"),
                type(RelationType.APPLIED_IN, "应用于", "APPLIES", 1, "#e87ba4",
                        "理论、方法被应用到某个实践环节，如「关系代数」应用于「查询优化」。"),
                type(RelationType.PRODUCES, "产出", "PRODUCED_BY", 1, "#008300",
                        "某活动产生的制品，如「需求分析」产出「需求规格说明书」。"),
                type(RelationType.SYNONYM_OF, "近义/别名", "SYNONYM_OF", 0, "#4a3aa7",
                        "概念的不同表述方式。对称关系，用于实体链接时的同义归一。"));
        relationTypeRepository.saveAll(types);
    }

    private RelationType type(String code, String name, String inverse, int directed, String color, String desc) {
        RelationType t = new RelationType();
        t.setCode(code);
        t.setName(name);
        t.setInverseCode(inverse);
        t.setDirected(directed);
        t.setColor(color);
        t.setDescription(desc);
        t.setSortOrder(relationTypeRepository.findAll().size());
        return t;
    }

    // ==================================================================
    // 概念
    // ==================================================================

    private Map<String, Long> seedConcepts() {
        Map<String, Long> codeToId = new LinkedHashMap<>();
        if (conceptRepository.count() > 0) {
            conceptRepository.findAll().forEach(c -> codeToId.put(c.getCode(), c.getId()));
            return codeToId;
        }

        List<ConceptSeed> seeds = conceptSeeds();
        Map<String, Concept> created = new LinkedHashMap<>();

        // 先落库拿到自增 id，再依据父节点补齐 level 与物化路径
        for (ConceptSeed s : seeds) {
            Concept c = new Concept();
            c.setCode(s.code());
            c.setName(s.name());
            c.setEnName(s.en());
            c.setAliases(s.aliases());
            c.setDescription(s.description());
            c.setDomain(s.domain());
            c.setDifficulty(s.difficulty());
            c.setImportance(s.importance());
            c.setColor(domainColor(s.domain()));
            c.setBuiltin(1);
            c.setSortOrder(conceptRepository.maxSortOrder(null) + created.size());
            c.setLevel(1);
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            conceptRepository.save(c);
            created.put(s.code(), c);
        }

        // 第二遍：建立父子关系、计算层级与路径
        for (ConceptSeed s : seeds) {
            Concept c = created.get(s.code());
            if (s.parentCode() != null && created.containsKey(s.parentCode())) {
                Concept parent = created.get(s.parentCode());
                c.setParentId(parent.getId());
                c.setLevel(parent.getLevel() + 1);
            } else {
                c.setLevel(1);
            }
        }
        conceptRepository.saveAll(created.values());

        // 第三遍：路径依赖父节点路径，需自顶向下计算
        List<Concept> ordered = new ArrayList<>(created.values());
        ordered.sort(Comparator.comparingInt(Concept::getLevel));
        for (Concept c : ordered) {
            c.setPath(buildPath(c, created));
            conceptRepository.save(c);
        }

        created.forEach((code, c) -> codeToId.put(code, c.getId()));
        return codeToId;
    }

    private String buildPath(Concept c, Map<String, Concept> created) {
        if (c.getParentId() == null) {
            return "/" + c.getId() + "/";
        }
        for (Concept candidate : created.values()) {
            if (candidate.getId().equals(c.getParentId())) {
                return (candidate.getPath() == null ? "/" + candidate.getId() + "/" : candidate.getPath())
                        + c.getId() + "/";
            }
        }
        return "/" + c.getId() + "/";
    }

    /** 知识域配色：三个域在色觉缺陷模拟下两两可区分（ΔE ≥ 9）。 */
    private String domainColor(String domain) {
        return switch (domain == null ? "" : domain) {
            case "软件工程" -> "#2a78d6";
            case "数据库" -> "#1baf7a";
            case "知识管理" -> "#eb6834";
            default -> "#898781";
        };
    }

    // ==================================================================
    // 关系实例
    // ==================================================================

    private void seedRelations(Map<String, Long> codeToId) {
        if (relationRepository.count() > 0) {
            return;
        }
        Map<String, String> typeNames = new HashMap<>();
        relationTypeRepository.findAll().forEach(t -> typeNames.put(t.getCode(), t.getName()));

        List<Relation> relations = new ArrayList<>();
        Set<String> dedup = new HashSet<>();

        // (1) 层次结构自动转成 SUB_CLASS_OF 关系，把「分类树」纳入本体语义网络
        for (ConceptSeed s : conceptSeeds()) {
            if (s.parentCode() != null) {
                add(relations, dedup, codeToId, s.code(), RelationType.SUB_CLASS_OF, s.parentCode(),
                        0.95, typeNames, null);
            }
        }

        // (2) 人工标注的领域语义关系
        for (RelationSeed r : relationSeeds()) {
            add(relations, dedup, codeToId, r.source(), r.type(), r.target(), r.weight(), typeNames, r.note());
        }

        relationRepository.saveAll(relations);
    }

    private void add(List<Relation> out, Set<String> dedup, Map<String, Long> codeToId,
                     String sourceCode, String type, String targetCode, double weight,
                     Map<String, String> typeNames, String note) {
        Long sourceId = codeToId.get(sourceCode);
        Long targetId = codeToId.get(targetCode);
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return;
        }
        if (!dedup.add(sourceId + "|" + type + "|" + targetId)) {
            return;
        }
        Relation r = new Relation();
        r.setSourceId(sourceId);
        r.setTargetId(targetId);
        r.setRelationType(type);
        r.setWeight(BigDecimal.valueOf(weight));
        r.setDescription(note);
        r.setBuiltin(1);
        r.setCreatedAt(LocalDateTime.now());
        out.add(r);
    }

    // ==================================================================
    // 概念清单
    // ==================================================================

    private List<ConceptSeed> conceptSeeds() {
        return List.of(
                // ============ 知识域一：软件工程 ============
                new ConceptSeed("SE", "软件工程", "Software Engineering", null, "软件工程", 2, 5,
                        "软件工程学,SE", "将系统化、规范化、可度量的方法应用于软件的开发、运行和维护的学科。"),
                new ConceptSeed("SE.REQ", "软件需求", "Software Requirements", "SE", "软件工程", 2, 5,
                        "需求工程,需求", "关于待开发系统必须满足的能力与约束的描述，是软件开发的起点。"),
                new ConceptSeed("SE.REQ.ELICITATION", "需求获取", "Requirements Elicitation", "SE.REQ", "软件工程", 3, 5,
                        "需求采集,需求调研", "通过访谈、观察、原型等方式从利益相关者处获得需求的活动。"),
                new ConceptSeed("SE.REQ.ANALYSIS", "需求分析", "Requirements Analysis", "SE.REQ", "软件工程", 4, 5,
                        "需求分析技术", "对获取的原始需求进行提炼、建模、去冲突，形成一致且完整的需求集合。"),
                new ConceptSeed("SE.REQ.SPEC", "需求规格说明", "Software Requirements Specification", "SE.REQ", "软件工程", 3, 5,
                        "SRS,需求规格说明书", "以文档形式精确定义系统需求，是开发与验收的契约依据。"),
                new ConceptSeed("SE.REQ.VALIDATION", "需求验证", "Requirements Validation", "SE.REQ", "软件工程", 4, 4,
                        "需求确认", "确认需求规格说明正确、完整、无歧义且可验证的过程。"),
                new ConceptSeed("SE.REQ.MANAGEMENT", "需求管理", "Requirements Management", "SE.REQ", "软件工程", 4, 4,
                        "需求变更控制,需求追踪", "管理需求的基线、变更与双向可追溯性，控制需求蔓延。"),
                new ConceptSeed("SE.DESIGN", "软件设计", "Software Design", "SE", "软件工程", 4, 5,
                        "系统设计", "把需求转化为可实现的软件结构、接口与算法描述的过程。"),
                new ConceptSeed("SE.DESIGN.ARCH", "软件体系结构设计", "Software Architecture Design", "SE.DESIGN", "软件工程", 4, 5,
                        "架构设计,体系结构", "确定系统的整体组织结构、构件划分与交互方式，是最高层的设计决策。"),
                new ConceptSeed("SE.DESIGN.DETAIL", "详细设计", "Detailed Design", "SE.DESIGN", "软件工程", 3, 4,
                        "模块设计,过程设计", "对每个模块的内部算法、数据结构与接口进行精确描述。"),
                new ConceptSeed("SE.DESIGN.PATTERN", "设计模式", "Design Patterns", "SE.DESIGN", "软件工程", 4, 4,
                        "GoF模式", "对反复出现的面向对象设计问题的可复用解决方案的抽象总结。"),
                new ConceptSeed("SE.DESIGN.UML", "UML建模", "UML Modeling", "SE.DESIGN", "软件工程", 3, 4,
                        "统一建模语言,用例图,类图,时序图", "用统一的图形化语言表达系统的结构与行为视图。"),
                new ConceptSeed("SE.DESIGN.INTERFACE", "接口设计", "Interface Design", "SE.DESIGN", "软件工程", 3, 3,
                        "API设计", "定义模块之间、系统与外部之间的交互契约与数据格式。"),
                new ConceptSeed("SE.CONSTRUCT", "软件构造", "Software Construction", "SE", "软件工程", 3, 5,
                        "编码实现,软件实现", "把设计转化为可运行代码的活动，含编码、验证与调试。"),
                new ConceptSeed("SE.CONSTRUCT.CODE", "编码实现", "Coding", "SE.CONSTRUCT", "软件工程", 2, 5,
                        "程序设计,编程", "选用适当语言与规范把详细设计翻译为源代码。"),
                new ConceptSeed("SE.CONSTRUCT.REVIEW", "代码评审", "Code Review", "SE.CONSTRUCT", "软件工程", 3, 4,
                        "同行评审,走查", "由他人检查代码以发现缺陷、统一风格、传播知识的活动。"),
                new ConceptSeed("SE.CONSTRUCT.VCS", "版本控制", "Version Control", "SE.CONSTRUCT", "软件工程", 2, 4,
                        "版本管理,Git,配置管理工具", "记录代码与文档的变更历史，支持并行开发与回溯。"),
                new ConceptSeed("SE.CONSTRUCT.REFACTOR", "重构", "Refactoring", "SE.CONSTRUCT", "软件工程", 4, 4,
                        "代码重构", "在不改变外部行为的前提下改善代码内部结构的活动。"),
                new ConceptSeed("SE.TEST", "软件测试", "Software Testing", "SE", "软件工程", 3, 5,
                        "测试", "以发现缺陷、验证需求满足程度为目的的软件质量活动。"),
                new ConceptSeed("SE.TEST.UNIT", "单元测试", "Unit Testing", "SE.TEST", "软件工程", 2, 5,
                        "模块测试", "对软件最小可测单元（函数/类）进行的独立测试。"),
                new ConceptSeed("SE.TEST.INTEGRATION", "集成测试", "Integration Testing", "SE.TEST", "软件工程", 3, 4,
                        "组装测试", "验证多个模块组装后接口与协作是否正确，如自顶向下、自底向上策略。"),
                new ConceptSeed("SE.TEST.SYSTEM", "系统测试", "System Testing", "SE.TEST", "软件工程", 3, 4,
                        "整体测试", "在完整系统环境下验证功能与非功能需求是否满足。"),
                new ConceptSeed("SE.TEST.ACCEPTANCE", "验收测试", "Acceptance Testing", "SE.TEST", "软件工程", 3, 4,
                        "确认测试,UAT", "由用户依据需求规格说明确认系统是否满足业务要求。"),
                new ConceptSeed("SE.TEST.REGRESSION", "回归测试", "Regression Testing", "SE.TEST", "软件工程", 3, 4,
                        "回归验证", "在修改后重新执行测试，确认未引入新的缺陷或破坏原有功能。"),
                new ConceptSeed("SE.TEST.WHITEBOX", "白盒测试", "White-box Testing", "SE.TEST", "软件工程", 4, 4,
                        "结构测试,逻辑覆盖", "基于程序内部逻辑结构设计测试，关注语句、分支与路径覆盖。"),
                new ConceptSeed("SE.TEST.BLACKBOX", "黑盒测试", "Black-box Testing", "SE.TEST", "软件工程", 3, 5,
                        "功能测试", "不考虑内部实现，仅依据输入输出规格设计测试用例。"),
                new ConceptSeed("SE.TEST.CASE", "测试用例设计", "Test Case Design", "SE.TEST", "软件工程", 4, 5,
                        "用例设计,等价类划分,边界值分析", "运用等价类划分、边界值分析、判定表等方法设计高覆盖率的测试输入。"),
                new ConceptSeed("SE.TEST.COVERAGE", "覆盖率分析", "Coverage Analysis", "SE.TEST", "软件工程", 4, 3,
                        "代码覆盖率", "以语句、分支、路径等维度的覆盖比例度量测试的充分性。"),
                new ConceptSeed("SE.MAINT", "软件维护", "Software Maintenance", "SE", "软件工程", 3, 4,
                        "系统维护", "软件交付后为纠正错误、适应环境或改善性能而进行的修改。"),
                new ConceptSeed("SE.MAINT.CORRECTIVE", "纠错性维护", "Corrective Maintenance", "SE.MAINT", "软件工程", 2, 3,
                        "改正性维护", "修复交付后发现的缺陷与错误。"),
                new ConceptSeed("SE.MAINT.ADAPTIVE", "适应性维护", "Adaptive Maintenance", "SE.MAINT", "软件工程", 2, 3,
                        "适应型维护", "为适应外部环境（操作系统、硬件、法规）变化而作的修改。"),
                new ConceptSeed("SE.MAINT.PERFECTIVE", "完善性维护", "Perfective Maintenance", "SE.MAINT", "软件工程", 3, 3,
                        "改善性维护", "为提升性能或增加用户需要的新功能而作的修改。"),
                new ConceptSeed("SE.MAINT.REENGINEER", "软件再工程", "Software Reengineering", "SE.MAINT", "软件工程", 5, 3,
                        "逆向工程,遗产系统改造", "对既有系统进行逆向分析并重构，以延长其生命周期。"),
                new ConceptSeed("SE.PM", "软件项目管理", "Software Project Management", "SE", "软件工程", 3, 5,
                        "项目管理", "对软件项目的人员、进度、成本、风险与质量进行计划和控制。"),
                new ConceptSeed("SE.PM.SCHEDULE", "进度管理", "Schedule Management", "SE.PM", "软件工程", 3, 5,
                        "进度计划,甘特图,关键路径", "制定并跟踪项目时间计划，识别关键路径与里程碑偏差。"),
                new ConceptSeed("SE.PM.COST", "成本估算", "Cost Estimation", "SE.PM", "软件工程", 4, 4,
                        "工作量估算,COCOMO,功能点", "在项目早期估算工作量与成本，常用 COCOMO 模型与功能点法。"),
                new ConceptSeed("SE.PM.RISK", "风险管理", "Risk Management", "SE.PM", "软件工程", 4, 4,
                        "风险识别,风险应对", "识别、评估并制定应对策略以减少不确定性对项目的冲击。"),
                new ConceptSeed("SE.PM.CONFIG", "配置管理", "Configuration Management", "SE.PM", "软件工程", 3, 4,
                        "SCM,基线管理", "标识、控制、审计配置项及其变更，维护产品完整性。"),
                new ConceptSeed("SE.PM.QUALITY", "软件质量保证", "Software Quality Assurance", "SE.PM", "软件工程", 4, 5,
                        "SQA,质量保证", "通过过程评审与审计确保开发过程符合既定标准与规程。"),
                new ConceptSeed("SE.PM.METRIC", "软件度量", "Software Measurement", "SE.PM", "软件工程", 4, 3,
                        "软件指标,度量元", "用可量化的指标刻画软件产品、过程与资源的属性。"),
                new ConceptSeed("SE.PROCESS", "软件过程", "Software Process", "SE", "软件工程", 3, 4,
                        "开发过程,过程模型", "开发与维护软件所涉及的活动、方法与实践的集合。"),
                new ConceptSeed("SE.PROCESS.WATERFALL", "瀑布模型", "Waterfall Model", "SE.PROCESS", "软件工程", 2, 4,
                        "线性模型", "把开发活动按阶段顺序线性推进的经典过程模型，阶段间有严格评审。"),
                new ConceptSeed("SE.PROCESS.ITERATIVE", "迭代开发", "Iterative Development", "SE.PROCESS", "软件工程", 3, 4,
                        "增量开发", "把系统分多轮迭代构建，每轮都产出可运行的增量。"),
                new ConceptSeed("SE.PROCESS.AGILE", "敏捷开发", "Agile Development", "SE.PROCESS", "软件工程", 3, 4,
                        "敏捷方法", "以迭代、快速反馈和响应变化为核心的开发价值观与方法族。"),
                new ConceptSeed("SE.PROCESS.SCRUM", "Scrum框架", "Scrum", "SE.PROCESS", "软件工程", 3, 4,
                        "Scrum,冲刺", "以 Sprint、每日站会、评审与回顾为骨架的敏捷管理框架。"),
                new ConceptSeed("SE.PROCESS.SPIRAL", "螺旋模型", "Spiral Model", "SE.PROCESS", "软件工程", 4, 3,
                        "螺旋开发", "以风险驱动的迭代模型，每轮包含目标设定、风险分析、实施与评审。"),
                new ConceptSeed("SE.PROCESS.DEVOPS", "DevOps", "DevOps", "SE.PROCESS", "软件工程", 4, 3,
                        "持续集成,持续交付,CI/CD", "打通开发与运维的协作与自动化实践，实现持续集成与交付。"),

                // ============ 知识域二：数据库 ============
                new ConceptSeed("DB", "数据库系统", "Database System", null, "数据库", 2, 5,
                        "数据库,DBMS", "由数据库、数据库管理系统与应用组成的系统，提供数据的组织、存储与管理能力。"),
                new ConceptSeed("DB.MODEL", "数据模型", "Data Model", "DB", "数据库", 3, 5,
                        "数据建模", "对现实世界数据特征及其联系的抽象表示，是数据库设计的基础。"),
                new ConceptSeed("DB.MODEL.ER", "实体联系模型", "Entity-Relationship Model", "DB.MODEL", "数据库", 3, 5,
                        "E-R模型,ER图", "用实体、属性与联系三个要素描述现实世界的概念模型。"),
                new ConceptSeed("DB.MODEL.RELATIONAL", "关系模型", "Relational Model", "DB.MODEL", "数据库", 3, 5,
                        "关系数据模型", "以二维表（关系）组织数据，用关系代数作为操作基础的模型。"),
                new ConceptSeed("DB.MODEL.NOSQL", "非关系型数据模型", "NoSQL Data Model", "DB.MODEL", "数据库", 4, 3,
                        "NoSQL,文档数据库,键值数据库", "面向高并发与弹性扩展的多种非关系数据组织方式。"),
                new ConceptSeed("DB.THEORY", "关系理论", "Relational Theory", "DB", "数据库", 4, 5,
                        "关系数据库理论", "关于关系结构与操作的数学理论，是 SQL 与规范化的理论根基。"),
                new ConceptSeed("DB.THEORY.ALGEBRA", "关系代数", "Relational Algebra", "DB.THEORY", "数据库", 4, 5,
                        "选择,投影,连接,除", "以集合运算与专用运算描述关系操作的形式化语言，SQL 的理论等价物。"),
                new ConceptSeed("DB.THEORY.CALCULUS", "关系演算", "Relational Calculus", "DB.THEORY", "数据库", 5, 3,
                        "元组演算,域演算", "以谓词逻辑描述查询结果应满足的条件的非过程化语言。"),
                new ConceptSeed("DB.THEORY.DEPENDENCY", "函数依赖", "Functional Dependency", "DB.THEORY", "数据库", 4, 5,
                        "FD,多值依赖", "属性间的决定关系，是判断范式与分解关系的依据。"),
                new ConceptSeed("DB.THEORY.NORMALIZATION", "规范化理论", "Normalization Theory", "DB.THEORY", "数据库", 4, 5,
                        "范式理论,范式,1NF,2NF,3NF", "通过模式分解消除冗余与操作异常的设计理论。"),
                new ConceptSeed("DB.THEORY.BCNF", "BCNF范式", "Boyce-Codd Normal Form", "DB.THEORY.NORMALIZATION", "数据库", 5, 4,
                        "BCNF,巴斯-科德范式", "在 3NF 基础上进一步要求每个决定因素都包含候选码。"),
                new ConceptSeed("DB.SQL", "结构化查询语言", "Structured Query Language", "DB", "数据库", 2, 5,
                        "SQL,查询语言", "关系数据库的标准操作语言，含数据定义、操纵、控制等子语言。"),
                new ConceptSeed("DB.SQL.DDL", "数据定义语言", "Data Definition Language", "DB.SQL", "数据库", 2, 4,
                        "DDL,建表", "用于定义与修改数据库对象结构的语句集，如 CREATE、ALTER、DROP。"),
                new ConceptSeed("DB.SQL.DML", "数据操纵语言", "Data Manipulation Language", "DB.SQL", "数据库", 2, 4,
                        "DML,增删改", "用于查询与更新数据的语句集，如 SELECT、INSERT、UPDATE、DELETE。"),
                new ConceptSeed("DB.SQL.JOIN", "连接查询", "Join Query", "DB.SQL", "数据库", 3, 5,
                        "多表查询,内连接,外连接", "通过连接条件把多个关系组合起来进行查询，含内连接与外连接。"),
                new ConceptSeed("DB.SQL.SUBQUERY", "子查询", "Subquery", "DB.SQL", "数据库", 3, 4,
                        "嵌套查询,相关子查询", "嵌套在另一语句中的查询，可作为条件或派生表使用。"),
                new ConceptSeed("DB.SQL.VIEW", "视图", "View", "DB.SQL", "数据库", 3, 3,
                        "虚表,视图机制", "由查询定义、不实际存储数据的虚表，用于简化查询与权限控制。"),
                new ConceptSeed("DB.SQL.PROCEDURE", "存储过程与触发器", "Stored Procedure and Trigger", "DB.SQL", "数据库", 4, 4,
                        "存储过程,触发器", "在数据库端预编译保存的程序单元，用于封装业务逻辑与自动响应数据变更。"),
                new ConceptSeed("DB.SQL.INDEX", "索引与查询优化", "Index and Query Optimization", "DB.SQL", "数据库", 4, 5,
                        "索引,执行计划,查询优化", "通过 B+ 树等结构加速数据定位，并由优化器选择高效执行计划。"),
                new ConceptSeed("DB.DESIGN", "数据库设计", "Database Design", "DB", "数据库", 3, 5,
                        "数据库设计方法", "从需求出发构建数据库结构的过程，含概念、逻辑与物理三个阶段。"),
                new ConceptSeed("DB.DESIGN.CONCEPTUAL", "概念结构设计", "Conceptual Design", "DB.DESIGN", "数据库", 3, 5,
                        "概念设计,E-R设计", "用 E-R 模型表达用户需求，独立于具体 DBMS。"),
                new ConceptSeed("DB.DESIGN.LOGICAL", "逻辑结构设计", "Logical Design", "DB.DESIGN", "数据库", 4, 5,
                        "逻辑设计,E-R图转换", "把概念模型转换为与 DBMS 相关的逻辑模式，并按范式优化。"),
                new ConceptSeed("DB.DESIGN.PHYSICAL", "物理结构设计", "Physical Design", "DB.DESIGN", "数据库", 4, 4,
                        "物理设计,存储结构", "确定数据的存储结构、索引与存取路径，以匹配性能要求。"),
                new ConceptSeed("DB.DESIGN.DICTIONARY", "数据字典", "Data Dictionary", "DB.DESIGN", "数据库", 3, 4,
                        "元数据,数据字典设计", "描述数据库中数据项、数据结构与约束的元数据集合。"),
                new ConceptSeed("DB.IMPL", "数据库实现与运行", "Database Implementation and Operation", "DB", "数据库", 3, 4,
                        "数据库运行维护", "数据库建立后在运行期涉及的完整性、并发、恢复与安全工作。"),
                new ConceptSeed("DB.IMPL.INTEGRITY", "完整性约束", "Integrity Constraints", "DB.IMPL", "数据库", 3, 5,
                        "实体完整性,参照完整性,用户定义完整性", "保证数据正确性与一致性的规则，含主键、外键与检查约束。"),
                new ConceptSeed("DB.IMPL.TRANSACTION", "事务", "Transaction", "DB.IMPL", "数据库", 4, 5,
                        "ACID,事务管理", "一组要么全做要么全不做的数据库操作序列，具备 ACID 特性。"),
                new ConceptSeed("DB.IMPL.CONCURRENCY", "并发控制与封锁", "Concurrency Control and Locking", "DB.IMPL", "数据库", 5, 5,
                        "并发控制,两段锁协议,封锁协议", "在并发环境下保证事务隔离性、避免丢失修改与死锁的机制。"),
                new ConceptSeed("DB.IMPL.RECOVERY", "数据库恢复", "Database Recovery", "DB.IMPL", "数据库", 4, 4,
                        "日志,检查点,恢复技术", "在故障后利用日志与备份把数据库恢复到一致状态的技术。"),
                new ConceptSeed("DB.IMPL.SECURITY", "数据库安全性", "Database Security", "DB.IMPL", "数据库", 3, 3,
                        "权限控制,审计", "通过授权、视图与审计保护数据不被非法访问与篡改。"),
                new ConceptSeed("DB.IMPL.TUNING", "性能调优", "Performance Tuning", "DB.IMPL", "数据库", 5, 4,
                        "数据库调优,慢查询优化", "通过索引设计、SQL 改写与参数配置提升数据库吞吐与响应。"),
                new ConceptSeed("DB.NEW", "数据库新技术", "Advanced Database Technology", "DB", "数据库", 4, 3,
                        "新型数据库", "面向分布式、大数据与人工智能场景的数据库技术演进。"),
                new ConceptSeed("DB.NEW.DISTRIBUTED", "分布式数据库", "Distributed Database", "DB.NEW", "数据库", 5, 3,
                        "分库分表,数据分片", "数据分布在多个节点上但逻辑上统一管理的数据库系统。"),
                new ConceptSeed("DB.NEW.BIGDATA", "大数据与数据仓库", "Big Data and Data Warehouse", "DB.NEW", "数据库", 4, 3,
                        "数据仓库,OLAP,ETL", "面向分析场景的数据组织方式与分析型处理技术。"),
                new ConceptSeed("DB.NEW.VECTOR", "向量数据库", "Vector Database", "DB.NEW", "数据库", 4, 4,
                        "向量存储,相似度检索", "面向高维向量的存储与近邻检索的数据库，是 RAG 的常见基础设施。"),

                // ============ 知识域三：知识管理与智能检索 ============
                new ConceptSeed("KM", "知识管理", "Knowledge Management", null, "知识管理", 3, 4,
                        "KMS,知识管理系统", "帮助组织有效地创建、分享、应用和维护知识的理论与系统。"),
                new ConceptSeed("KM.KB", "知识库", "Knowledge Base", "KM", "知识管理", 3, 5,
                        "知识资产库", "结构化存储与管理知识资产，支持检索、浏览、分类与归档。"),
                new ConceptSeed("KM.KB.ASSET", "知识资产", "Knowledge Asset", "KM.KB", "知识管理", 2, 4,
                        "知识内容,知识条目", "具有复用价值的文档、报告、论文、视频等内容实体。"),
                new ConceptSeed("KM.KB.TAXONOMY", "分类体系", "Taxonomy", "KM.KB", "知识管理", 3, 4,
                        "知识分类,分类法", "按主题层次组织知识内容的分类结构。"),
                new ConceptSeed("KM.KB.ONTOLOGY", "知识本体建模", "Ontology Modeling", "KM.KB", "知识管理", 5, 5,
                        "本体,本构建模,OWL", "用概念、关系与公理显式刻画某领域知识结构的方法。"),
                new ConceptSeed("KM.SEARCH", "检索技术", "Search Technology", "KM", "知识管理", 4, 5,
                        "信息检索,搜索", "从大规模文本集合中快速定位相关内容的技术。"),
                new ConceptSeed("KM.SEARCH.FULLTEXT", "全文检索", "Full-text Search", "KM.SEARCH", "知识管理", 4, 5,
                        "关键词检索,全文搜索", "对文本全部内容建立索引并按相关度排序返回结果的检索方式。"),
                new ConceptSeed("KM.SEARCH.INVERTED", "倒排索引", "Inverted Index", "KM.SEARCH", "知识管理", 4, 5,
                        "反向索引,倒排表", "以词项为键、文档列表为值的索引结构，是全文检索的核心数据结构。"),
                new ConceptSeed("KM.SEARCH.VECTOR", "向量检索", "Vector Search", "KM.SEARCH", "知识管理", 5, 5,
                        "语义检索,相似度检索,embedding检索", "把文本映射为稠密向量，按向量相似度召回语义相近的内容。"),
                new ConceptSeed("KM.SEARCH.HYBRID", "混合检索与RRF融合", "Hybrid Retrieval and RRF", "KM.SEARCH", "知识管理", 5, 4,
                        "混合检索,RRF,倒数排名融合", "同时使用词面与语义通道召回，并用倒数排名融合合并排名。"),
                new ConceptSeed("KM.SEARCH.ANALYSIS", "中文分词", "Chinese Word Segmentation", "KM.SEARCH", "知识管理", 4, 4,
                        "分词,切词,jieba", "把连续的中文文本切分为词序列，是中文检索与关键词抽取的前提。"),
                new ConceptSeed("KM.RAG", "检索增强生成", "Retrieval-Augmented Generation", "KM", "知识管理", 5, 5,
                        "RAG,问答系统", "先检索相关知识再交由大模型生成回答，以降低幻觉并支持溯源。"),
                new ConceptSeed("KM.RAG.CHUNK", "文本分块策略", "Text Chunking", "KM.RAG", "知识管理", 4, 4,
                        "分块,切片,chunk", "把长文本切分为语义相对完整的片段，块大小与重叠影响检索质量。"),
                new ConceptSeed("KM.RAG.EMBED", "文本向量化", "Text Embedding", "KM.RAG", "知识管理", 4, 5,
                        "词向量,句向量,embedding", "把文本编码为固定维度的稠密向量，使语义相近的文本距离更近。"),
                new ConceptSeed("KM.RAG.PROMPT", "提示词与上下文组装", "Prompt and Context Assembly", "KM.RAG", "知识管理", 4, 4,
                        "提示工程,prompt,上下文窗口", "设计系统提示并组织检索片段，控制模型的回答边界与风格。"),
                new ConceptSeed("KM.RAG.CITATION", "引用溯源", "Citation and Grounding", "KM.RAG", "知识管理", 3, 4,
                        "答案溯源,引用标注", "在生成回答中标注依据来源，便于用户核对、降低幻觉风险。"),
                new ConceptSeed("KM.CMS", "内容管理系统", "Content Management System", "KM", "知识管理", 3, 5,
                        "CMS,内容管理", "用于内容创建、编辑、发布与生命周期管理的系统。"),
                new ConceptSeed("KM.CMS.WORKFLOW", "内容工作流", "Content Workflow", "KM.CMS", "知识管理", 3, 4,
                        "审核流程,状态流转", "内容从草稿到发布所经历的状态与审批路径。"),
                new ConceptSeed("KM.CMS.VERSION", "版本管理", "Version Management", "KM.CMS", "知识管理", 4, 5,
                        "版本控制,历史版本,回滚", "记录内容的历史快照，支持差异比对与回滚。"),
                new ConceptSeed("KM.CMS.MEDIA", "多媒体内容管理", "Multimedia Content Management", "KM.CMS", "知识管理", 3, 3,
                        "附件管理,视频,图片", "对文档、图片、音视频等多媒体素材的存储、预览与正文抽取。"),
                new ConceptSeed("KM.CMS.ARCHIVE", "归档与保留策略", "Archiving and Retention", "KM.CMS", "知识管理", 3, 3,
                        "知识归档,保留策略", "把低活跃度内容移出常规范围但保留可追溯性，控制知识库的信噪比。"),
                new ConceptSeed("KM.GRAPH", "知识图谱", "Knowledge Graph", "KM", "知识管理", 4, 4,
                        "KG,语义网络", "用图结构表示实体及其关系，支持推理与可视化导航。"),
                new ConceptSeed("KM.GRAPH.TRIPLE", "三元组表示", "Triple Representation", "KM.GRAPH", "知识管理", 3, 4,
                        "主语谓语宾语,SPO,三元组", "以（主语, 谓词, 宾语）形式表达一条知识断言。"),
                new ConceptSeed("KM.GRAPH.RELATION", "关系抽取与推理", "Relation Extraction and Reasoning", "KM.GRAPH", "知识管理", 5, 4,
                        "知识推理,关系推理", "从文本中识别关系，并基于关系规则推导隐含知识。"),
                new ConceptSeed("KM.GRAPH.VIZ", "图谱可视化", "Graph Visualization", "KM.GRAPH", "知识管理", 3, 3,
                        "知识地图,力导向布局", "以节点—连线方式呈现知识结构，支持交互式探索。")
        );
    }

    // ==================================================================
    // 语义关系实例
    // ==================================================================

    private List<RelationSeed> relationSeeds() {
        return List.of(
                // ---------------- 先修关系：构成学习路径 ----------------
                r("DB.THEORY.ALGEBRA", RelationType.PREREQUISITE_OF, "DB.SQL", 0.9, "关系代数是理解 SQL 查询语义的理论基础"),
                r("DB.THEORY.DEPENDENCY", RelationType.PREREQUISITE_OF, "DB.THEORY.NORMALIZATION", 0.95, "先掌握函数依赖才能判断范式"),
                r("DB.THEORY.NORMALIZATION", RelationType.PREREQUISITE_OF, "DB.THEORY.BCNF", 0.9, "BCNF 是范式的进阶"),
                r("DB.MODEL.ER", RelationType.PREREQUISITE_OF, "DB.DESIGN.LOGICAL", 0.95, "概念模型是逻辑结构设计的输入"),
                r("DB.MODEL.RELATIONAL", RelationType.PREREQUISITE_OF, "DB.THEORY.ALGEBRA", 0.85, "先建立关系模型的概念再学习关系运算"),
                r("DB.SQL", RelationType.PREREQUISITE_OF, "DB.SQL.INDEX", 0.8, "掌握查询写法后才能理解优化"),
                r("DB.IMPL.TRANSACTION", RelationType.PREREQUISITE_OF, "DB.IMPL.CONCURRENCY", 0.9, "并发控制以事务的 ACID 为前提"),
                r("DB.IMPL.CONCURRENCY", RelationType.PREREQUISITE_OF, "DB.IMPL.RECOVERY", 0.7, "并发与恢复共同保证一致性"),
                r("SE.REQ.ELICITATION", RelationType.PREREQUISITE_OF, "SE.REQ.ANALYSIS", 0.9, "先获取需求再做分析"),
                r("SE.REQ.ANALYSIS", RelationType.PREREQUISITE_OF, "SE.REQ.SPEC", 0.9, "分析结果是规格说明的素材"),
                r("SE.REQ", RelationType.PREREQUISITE_OF, "SE.DESIGN", 0.95, "需求是设计的输入"),
                r("SE.DESIGN", RelationType.PREREQUISITE_OF, "SE.CONSTRUCT", 0.95, "设计先于编码"),
                r("SE.CONSTRUCT", RelationType.PREREQUISITE_OF, "SE.TEST", 0.8, "有可运行代码才谈得上测试"),
                r("SE.TEST.BLACKBOX", RelationType.PREREQUISITE_OF, "SE.TEST.CASE", 0.75, "黑盒方法是用例设计的主要依据"),
                r("DB.NEW.VECTOR", RelationType.PREREQUISITE_OF, "KM.SEARCH.VECTOR", 0.7, "向量检索依赖向量存储能力"),
                r("KM.SEARCH.INVERTED", RelationType.PREREQUISITE_OF, "KM.SEARCH.FULLTEXT", 0.9, "倒排索引是全文检索的实现基础"),
                r("KM.SEARCH.FULLTEXT", RelationType.PREREQUISITE_OF, "KM.SEARCH.HYBRID", 0.8, "混合检索在词面通道之上做融合"),
                r("KM.RAG.EMBED", RelationType.PREREQUISITE_OF, "KM.SEARCH.VECTOR", 0.85, "向量化是把文本变成可检索向量的前提"),
                r("KM.RAG.CHUNK", RelationType.PREREQUISITE_OF, "KM.RAG", 0.8, "分块质量决定检索增强的效果"),
                r("KM.SEARCH", RelationType.PREREQUISITE_OF, "KM.RAG", 0.9, "检索是 RAG 的第一个环节"),
                r("KM.KB.ONTOLOGY", RelationType.PREREQUISITE_OF, "KM.GRAPH", 0.85, "本体为知识图谱提供模式层"),
                r("SE.PM.SCHEDULE", RelationType.PREREQUISITE_OF, "SE.PM.RISK", 0.6, "进度计划是识别风险的时间基线"),

                // ---------------- 组成关系 ----------------
                r("SE.REQ.ELICITATION", RelationType.PART_OF, "SE.REQ", 0.9, "需求获取是需求工程的一个环节"),
                r("SE.REQ.ANALYSIS", RelationType.PART_OF, "SE.REQ", 0.9, "需求分析是需求工程的一个环节"),
                r("SE.TEST.CASE", RelationType.PART_OF, "SE.TEST", 0.85, "用例设计是软件测试的核心工作"),
                r("SE.TEST.COVERAGE", RelationType.PART_OF, "SE.TEST", 0.7, "覆盖率分析是测试充分性评估的组成部分"),
                r("SE.PM.SCHEDULE", RelationType.PART_OF, "SE.PM", 0.9, "进度管理是项目管理的核心职能之一"),
                r("SE.PM.RISK", RelationType.PART_OF, "SE.PM", 0.9, "风险管理是项目管理的核心职能之一"),
                r("SE.PM.CONFIG", RelationType.PART_OF, "SE.PM", 0.85, "配置管理是项目管理的支撑职能"),
                r("DB.IMPL.TRANSACTION", RelationType.PART_OF, "DB.IMPL", 0.85, "事务管理属于数据库运行期技术"),
                r("DB.DESIGN.CONCEPTUAL", RelationType.PART_OF, "DB.DESIGN", 0.95, "概念结构设计是数据库设计的第一阶段"),
                r("DB.DESIGN.LOGICAL", RelationType.PART_OF, "DB.DESIGN", 0.95, "逻辑结构设计是数据库设计的第二阶段"),
                r("DB.DESIGN.PHYSICAL", RelationType.PART_OF, "DB.DESIGN", 0.95, "物理结构设计是数据库设计的第三阶段"),
                r("KM.RAG.CHUNK", RelationType.PART_OF, "KM.RAG", 0.85, "分块是 RAG 流水线的组成部分"),
                r("KM.CMS.VERSION", RelationType.PART_OF, "KM.CMS", 0.85, "版本管理是内容管理系统的关键能力"),
                r("KM.CMS.ARCHIVE", RelationType.PART_OF, "KM.CMS", 0.75, "归档是内容生命周期管理的组成部分"),

                // ---------------- 应用于 ----------------
                r("DB.THEORY.ALGEBRA", RelationType.APPLIED_IN, "DB.SQL.INDEX", 0.8, "关系代数用于推导查询的等价改写"),
                r("DB.THEORY.NORMALIZATION", RelationType.APPLIED_IN, "DB.DESIGN.LOGICAL", 0.9, "规范化理论用于优化逻辑模式"),
                r("DB.MODEL.ER", RelationType.APPLIED_IN, "DB.DESIGN.CONCEPTUAL", 0.95, "E-R 模型是概念设计的主要工具"),
                r("DB.IMPL.INTEGRITY", RelationType.APPLIED_IN, "DB.DESIGN.PHYSICAL", 0.7, "完整性约束在物理实现时落地"),
                r("DB.SQL.INDEX", RelationType.APPLIED_IN, "DB.IMPL.TUNING", 0.9, "索引是性能调优的首要手段"),
                r("SE.DESIGN.UML", RelationType.APPLIED_IN, "SE.DESIGN.ARCH", 0.85, "UML 用于表达体系结构视图"),
                r("SE.DESIGN.PATTERN", RelationType.APPLIED_IN, "SE.DESIGN.DETAIL", 0.8, "设计模式用于详细设计中的结构复用"),
                r("SE.TEST.WHITEBOX", RelationType.APPLIED_IN, "SE.TEST.UNIT", 0.85, "白盒方法主要用于单元测试"),
                r("SE.TEST.BLACKBOX", RelationType.APPLIED_IN, "SE.TEST.SYSTEM", 0.8, "黑盒方法主要用于系统与验收测试"),
                r("SE.PM.METRIC", RelationType.APPLIED_IN, "SE.PM.COST", 0.85, "度量数据是成本估算的依据"),
                r("SE.PM.QUALITY", RelationType.APPLIED_IN, "SE.PROCESS", 0.75, "质量保证通过过程规范落地"),
                r("KM.SEARCH.INVERTED", RelationType.APPLIED_IN, "KM.SEARCH.FULLTEXT", 0.95, "倒排索引是全文检索的核心数据结构"),
                r("KM.SEARCH.VECTOR", RelationType.APPLIED_IN, "KM.RAG", 0.95, "向量检索是 RAG 的召回通道之一"),
                r("KM.KB.ONTOLOGY", RelationType.APPLIED_IN, "KM.SEARCH", 0.8, "本体用于检索时的查询扩展"),
                r("KM.GRAPH.TRIPLE", RelationType.APPLIED_IN, "KM.GRAPH.VIZ", 0.85, "三元组是图谱可视化的数据来源"),

                // ---------------- 产出 ----------------
                r("SE.REQ.ELICITATION", RelationType.PRODUCES, "SE.REQ.SPEC", 0.7, "需求获取的产出经分析后形成规格说明"),
                r("SE.REQ.ANALYSIS", RelationType.PRODUCES, "SE.REQ.SPEC", 0.9, "需求分析产出需求规格说明书"),
                r("SE.DESIGN.ARCH", RelationType.PRODUCES, "SE.DESIGN.DETAIL", 0.8, "体系结构设计约束详细设计"),
                r("SE.TEST.CASE", RelationType.PRODUCES, "SE.TEST", 0.8, "测试用例设计产出可执行的测试用例集"),
                r("DB.DESIGN.CONCEPTUAL", RelationType.PRODUCES, "DB.MODEL.ER", 0.95, "概念结构设计产出 E-R 图"),
                r("DB.DESIGN.LOGICAL", RelationType.PRODUCES, "DB.SQL.DDL", 0.8, "逻辑设计产出建表脚本"),
                r("DB.DESIGN.LOGICAL", RelationType.PRODUCES, "DB.DESIGN.DICTIONARY", 0.85, "逻辑设计产出数据字典"),
                r("KM.RAG.PROMPT", RelationType.PRODUCES, "KM.RAG.CITATION", 0.6, "上下文组装决定了可引用的来源"),

                // ---------------- 相关 ----------------
                r("SE.PM.CONFIG", RelationType.RELATED_TO, "KM.CMS.VERSION", 0.8, "软件配置管理与内容版本管理同源同理"),
                r("SE.TEST.REGRESSION", RelationType.RELATED_TO, "SE.MAINT", 0.75, "回归测试是维护活动的常规伴随动作"),
                r("SE.MAINT.REENGINEER", RelationType.RELATED_TO, "SE.CONSTRUCT.REFACTOR", 0.7, "再工程与重构都以改善既有代码为目标"),
                r("SE.PM.COST", RelationType.RELATED_TO, "SE.PM.METRIC", 0.85, "成本估算依赖软件度量"),
                r("SE.PROCESS.AGILE", RelationType.RELATED_TO, "SE.PROCESS.DEVOPS", 0.8, "敏捷与 DevOps 在持续交付上相互支撑"),
                r("DB.DESIGN.DICTIONARY", RelationType.RELATED_TO, "KM.KB.ONTOLOGY", 0.75, "数据字典与本体都是对元数据的显式描述"),
                r("DB.NEW.VECTOR", RelationType.RELATED_TO, "KM.RAG.EMBED", 0.85, "向量数据库为向量化结果提供存储与近邻检索"),
                r("DB.NEW.BIGDATA", RelationType.RELATED_TO, "DB.IMPL.TUNING", 0.6, "分析型场景对调优提出不同要求"),
                r("KM.RAG", RelationType.RELATED_TO, "KM.SEARCH.HYBRID", 0.9, "混合检索直接服务于 RAG 的召回质量"),
                r("KM.GRAPH", RelationType.RELATED_TO, "KM.KB.ONTOLOGY", 0.9, "知识图谱是本体的实例化与可视化"),
                r("KM.CMS.MEDIA", RelationType.RELATED_TO, "KM.KB.ASSET", 0.8, "多媒体素材是知识资产的组成部分"),
                r("KM.SEARCH.VECTOR", RelationType.RELATED_TO, "DB.NEW.VECTOR", 0.85, "向量检索常由向量数据库承载"),
                r("KM.SEARCH.ANALYSIS", RelationType.RELATED_TO, "KM.SEARCH.INVERTED", 0.9, "中文分词结果决定倒排索引的粒度"),
                r("SE.TEST.COVERAGE", RelationType.RELATED_TO, "SE.TEST.WHITEBOX", 0.85, "覆盖率是白盒测试充分性的主要指标"),

                // ---------------- 近义 / 别名 ----------------
                r("KM.RAG", RelationType.SYNONYM_OF, "KM.SEARCH.HYBRID", 0.4, "检索增强生成在实践中常与混合检索并提"),
                r("KM.CMS.VERSION", RelationType.SYNONYM_OF, "SE.CONSTRUCT.VCS", 0.5, "内容版本管理与代码版本控制的基本原理相通"),
                r("DB.THEORY.NORMALIZATION", RelationType.SYNONYM_OF, "DB.THEORY.BCNF", 0.35, "BCNF 是规范化理论中的具体范式层次"),
                r("KM.SEARCH.FULLTEXT", RelationType.SYNONYM_OF, "KM.SEARCH.INVERTED", 0.4, "全文检索的实现几乎总是基于倒排索引"),
                r("DB.SQL", RelationType.SYNONYM_OF, "DB.SQL.DML", 0.3, "日常语境中 SQL 常特指其数据操纵部分"),
                r("SE.CONSTRUCT.VCS", RelationType.SYNONYM_OF, "SE.PM.CONFIG", 0.45, "版本控制是配置管理的主要工具手段")
        );
    }

    private RelationSeed r(String source, String type, String target, double weight, String note) {
        return new RelationSeed(source, type, target, weight, note);
    }
}
