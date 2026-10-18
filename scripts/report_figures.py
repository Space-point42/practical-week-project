# -*- coding: utf-8 -*-
"""
生成课程设计报告所需的示意图。

为什么要用脚本画而不是手工截屏：
  1. 图是报告的一部分，需要可重复生成 —— 代码改了重跑一次即可同步；
  2. 统一配色、字号、线宽，整份报告的图看起来是一套；
  3. 矢量信息（结构关系）用代码表达比手绘更准确，不会漏掉模块。

输出目录：docs/report-figures/
用法：python scripts/report_figures.py
"""

import os
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'docs', 'report-figures')

# 与前端设计系统一致的配色（已通过色觉缺陷可辨识性校验）
INK = '#0b0b0b'
INK2 = '#52514e'
MUTED = '#898781'
LINE = '#c3c2b7'
GRID = '#e1e0d9'
S1 = '#2a78d6'   # 蓝
S2 = '#eb6834'   # 橙
S3 = '#1baf7a'   # 青绿
S4 = '#eda100'   # 黄
S5 = '#e87ba4'   # 品红
S6 = '#008300'   # 绿
S7 = '#4a3aa7'   # 紫

FONT_CANDIDATES = [
    'C:/Windows/Fonts/msyh.ttc',
    'C:/Windows/Fonts/simhei.ttf',
    'C:/Windows/Fonts/simsun.ttc',
    '/usr/share/fonts/truetype/arphic/uming.ttc',
    '/System/Library/Fonts/PingFang.ttc',
]
FONT_PATH = next((p for p in FONT_CANDIDATES if os.path.isfile(p)), None)
if FONT_PATH is None:
    raise SystemExit('未找到中文字体，无法生成示意图')

_font_cache = {}


def font(size, bold=False):
    key = (size, bold)
    if key not in _font_cache:
        idx = 0
        if FONT_PATH.endswith('.ttc') and bold:
            try:
                idx = 1  # ttc 集合中的第二个字重通常是粗体
            except Exception:
                idx = 0
        try:
            _font_cache[key] = ImageFont.truetype(FONT_PATH, size, index=idx)
        except Exception:
            _font_cache[key] = ImageFont.truetype(FONT_PATH, size)
    return _font_cache[key]


def canvas(w, h):
    img = Image.new('RGB', (w, h), 'white')
    return img, ImageDraw.Draw(img)


# ---------------------------------------------------------------- 绘图基元

def text(d, xy, s, size=18, color=INK, anchor='mm', bold=False):
    d.text(xy, s, font=font(size, bold), fill=color, anchor=anchor)


def text_w(d, s, size=18, bold=False):
    return d.textlength(s, font=font(size, bold))


def box(d, x, y, w, h, fill=None, outline=S1, width=2, radius=10, alpha_fill=None):
    if fill:
        d.rounded_rectangle([x, y, x + w, y + h], radius=radius, fill=fill,
                            outline=outline, width=width)
    else:
        d.rounded_rectangle([x, y, x + w, y + h], radius=radius,
                            outline=outline, width=width)


def tint(hex_color, alpha=0.12):
    """把颜色与白色混合，得到淡色填充。"""
    h = hex_color.lstrip('#')
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return (
        int(r * alpha + 255 * (1 - alpha)),
        int(g * alpha + 255 * (1 - alpha)),
        int(b * alpha + 255 * (1 - alpha)),
    )


def arrow(d, p1, p2, color=LINE, width=2, head=11, dashed=False):
    import math
    x1, y1 = p1
    x2, y2 = p2
    if dashed:
        _dashed_line(d, x1, y1, x2, y2, color, width)
    else:
        d.line([x1, y1, x2, y2], fill=color, width=width)

    ang = math.atan2(y2 - y1, x2 - x1)
    bx = x2 - head * math.cos(ang)
    by = y2 - head * math.sin(ang)
    d.polygon(
        [(x2, y2),
         (bx - head * 0.45 * math.sin(ang), by + head * 0.45 * math.cos(ang)),
         (bx + head * 0.45 * math.sin(ang), by - head * 0.45 * math.cos(ang))],
        fill=color,
    )


def _dashed_line(d, x1, y1, x2, y2, color, width, dash=9, gap=7):
    import math
    total = math.hypot(x2 - x1, y2 - y1)
    if total == 0:
        return
    ux, uy = (x2 - x1) / total, (y2 - y1) / total
    pos = 0.0
    while pos < total:
        end = min(pos + dash, total)
        d.line([x1 + ux * pos, y1 + uy * pos, x1 + ux * end, y1 + uy * end],
               fill=color, width=width)
        pos = end + gap


def caption(img_draw, w, h, text_str, color=MUTED, size=15):
    text(img_draw, (w / 2, h - 22), text_str, size=size, color=color)


def save(img, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    img.save(path, 'PNG')
    print('  已生成', name)


# ================================================================ 图 1 用例图

def fig_usecase():
    W, H = 1560, 1180
    img, d = canvas(W, H)

    text(d, (W / 2, 44), '知识管理系统 用例图', 26, INK, bold=True)

    # 参与者与其用例。三组等分系统边界的高度，每组用例在自己的区间内垂直居中，
    # 这样任何一个参与者都不需要迁就其他组的用例数量。
    groups = [
        ('浏览者', S3, ['检索知识', '浏览知识图谱', '向知识库提问', '下载附件']),
        ('编辑', S2, ['创建与编辑条目', '提交审核并发布', '管理版本与回滚', '归档与恢复']),
        ('管理员', S1, ['用户与角色管理', '维护知识本体', '配置版本策略', '重建检索索引']),
    ]

    BX, BY, BW, BH = 380, 120, 940, 940
    box(d, BX, BY, BW, BH, outline=GRID, width=2, radius=16)
    # 系统名放在边界左上角：居中会与第一组用例重叠，
    # 而用例本身必须保持水平居中，所以让标题让位。
    text(d, (BX + 26, BY + 30), '知识管理系统', 19, INK, anchor='lm', bold=True)

    row_h = 62
    seg = BH / len(groups)

    for gi, (actor, color, names) in enumerate(groups):
        seg_center = BY + seg * gi + seg / 2
        block_h = (len(names) - 1) * row_h
        first_cy = seg_center - block_h / 2

        # 参与者
        ax, ay = 150, int(seg_center)
        d.ellipse([ax + 40, ay - 70, ax + 90, ay - 20], outline=color, width=3)
        d.line([ax + 65, ay - 20, ax + 65, ay + 40], fill=color, width=3)
        d.line([ax + 32, ay + 2, ax + 98, ay + 2], fill=color, width=3)
        d.line([ax + 65, ay + 40, ax + 36, ay + 84], fill=color, width=3)
        d.line([ax + 65, ay + 40, ax + 94, ay + 84], fill=color, width=3)
        text(d, (ax + 65, ay + 112), actor, 19, INK, bold=True)

        # 用例
        for i, name in enumerate(names):
            cy = first_cy + i * row_h
            w = max(230, text_w(d, name, 17) + 62)
            cx = BX + BW / 2 - w / 2
            box(d, cx, cy - 25, w, 50, fill=tint(color), outline=color, width=2, radius=25)
            text(d, (BX + BW / 2, cy), name, 17, INK)
            arrow(d, (ax + 118, ay + 2), (cx - 8, cy), color=color,
                  width=1, dashed=True, head=7)

    # 外部系统：智能问答依赖在线大模型服务
    ex_x, ex_y, ex_w, ex_h = 1380, int(BY + seg * 0 + seg / 2 - 93), 175, 82
    box(d, ex_x, ex_y, ex_w, ex_h, fill=tint(S7), outline=S7, width=2, radius=12)
    text(d, (ex_x + ex_w / 2, ex_y + 30), '外部大模型服务', 15, INK)
    text(d, (ex_x + ex_w / 2, ex_y + 54), '（OpenAI 兼容）', 12.5, MUTED)
    arrow(d, (ex_x, ex_y + ex_h / 2), (BX + BW / 2 + 175, ex_y + ex_h / 2),
          color=S7, width=2, dashed=True, head=9)
    text(d, (ex_x + ex_w / 2, ex_y + ex_h + 22), '可选依赖', 12.5, MUTED)
    text(d, (ex_x + ex_w / 2, ex_y + ex_h + 44), '未配置时降级为', 12.5, MUTED)
    text(d, (ex_x + ex_w / 2, ex_y + ex_h + 66), '离线抽取式回答', 12.5, MUTED)

    caption(d, W, H, '图 1  知识管理系统用例图')
    save(img, 'fig1-usecase.png')


# ================================================================ 图 2 状态图

def fig_state():
    W, H = 1400, 700
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '知识条目状态流转图', 26, INK, bold=True)

    # 初始节点
    d.ellipse([80, 330, 116, 366], fill=INK)
    arrow(d, (116, 348), (200, 348), color=INK, width=2)

    states = [
        ('草稿', 200, S3, 'DRAFT'),
        ('待审核', 470, S4, 'PENDING'),
        ('已发布', 740, S1, 'PUBLISHED'),
        ('已归档', 1010, MUTED, 'ARCHIVED'),
    ]
    for name, x, color, code in states:
        box(d, x, 300, 190, 96, fill=tint(color), outline=color, width=3, radius=14)
        text(d, (x + 95, 336), name, 21, INK, bold=True)
        text(d, (x + 95, 366), code, 13, MUTED)

    trans = [
        (390, 470, '提交审核', 0),
        (660, 740, '发布', 0),
        (930, 1010, '归档', 0),
    ]
    for x1, x2, label, _ in trans:
        arrow(d, (x1, 348), (x2, 348), color=INK2, width=2)
        text(d, ((x1 + x2) / 2, 322), label, 15, INK2)

    # 撤回发布
    arrow(d, (835, 300), (565, 300), color=S2, width=2, dashed=True)
    d.line([835, 300, 835, 258, 565, 258, 565, 300], fill=S2, width=2)
    text(d, (700, 236), '撤回发布', 15, S2)

    # 恢复归档
    d.line([1105, 300, 1105, 180, 295, 180, 295, 300], fill=S3, width=2)
    arrow(d, (295, 260), (295, 300), color=S3, width=2)
    text(d, (700, 158), '恢复（回到草稿，需重新确认后再发布）', 15, S3)

    # 回滚：自环
    d.line([835, 396, 835, 500, 295, 500, 295, 396], fill=S7, width=2)
    arrow(d, (295, 430), (295, 396), color=S7, width=2)
    text(d, (560, 526), '版本回滚（正文还原为历史版本，版本链只增不减）', 15, S7)

    # 删除
    text(d, (700, 590), '所有状态均可逻辑删除：置 deleted=1，记录保留，不再出现在任何查询中',
         15, MUTED)

    caption(d, W, H, '图 2  知识条目状态流转图')
    save(img, 'fig2-state.png')


# ================================================================ 图 3 时序图

def fig_sequence():
    W, H = 1500, 1080
    img, d = canvas(W, H)
    text(d, (W / 2, 38), '检索增强问答（RAG）时序图', 26, INK, bold=True)

    lifelines = [
        ('用户', 110, INK2),
        ('前端页面', 320, S1),
        ('RagService', 560, S3),
        ('本体服务\nOntology', 800, S7),
        ('混合检索器\nHybridRetriever', 1050, S2),
        ('生成模型\nChat/抽取式', 1330, S5),
    ]
    top, bottom = 150, 980
    for name, x, color in lifelines:
        box(d, x - 90, top - 56, 180, 56, fill=tint(color), outline=color, width=2, radius=10)
        for i, line in enumerate(name.split('\n')):
            text(d, (x, top - 38 + i * 20), line, 15, INK)
        d.line([x, top, x, bottom], fill=GRID, width=2)

    def msg(y, x1, x2, label, color=INK2, dashed=False, note=None):
        arrow(d, (x1, y), (x2, y), color=color, width=2, dashed=dashed, head=10)
        text(d, ((x1 + x2) / 2, y - 17), label, 15, color)
        if note:
            text(d, ((x1 + x2) / 2, y + 19), note, 13, MUTED)

    y = 190
    msg(y, 110, 320, '输入问题'); y += 60
    msg(y, 320, 560, 'POST /api/rag/ask', S1); y += 60
    msg(y, 560, 800, '① 实体链接 + 本体查询扩展', S3, note='识别问题中的知识点，沿关系补充相邻概念'); y += 62
    msg(y, 560, 1050, '② 检索（扩展后的查询串）', S3); y += 52

    # 检索器自处理
    d.rounded_rectangle([960, y - 16, 1140 + 190, y + 96], radius=10, outline=S2, width=2)
    text(d, (1145, y + 6), '向量通道：余弦相似度 Top-20', 14, INK2, anchor='mm')
    text(d, (1145, y + 34), '关键字通道：全文索引 Top-20', 14, INK2, anchor='mm')
    text(d, (1145, y + 64), 'RRF 融合 → Top-K', 14, S2, anchor='mm', bold=True)
    y += 150

    msg(y, 1050, 560, '候选分块 + 各通道排名', S2); y += 60
    msg(y, 560, 1330, '③ 组装上下文并生成', S3, note='Top-K 分块按【来源 N】编号拼入提示词'); y += 60
    msg(y, 1330, 560, '回答文本 / 超时则降级为抽取式', S5, dashed=True,
        note='无 API Key 或调用失败 → 直接抽取原文要点'); y += 60
    msg(y, 560, 320, '④ AnswerDTO（回答 + 引用 + 检索元数据）', S3); y += 60
    msg(y, 320, 110, '回答、引用来源、检索过程', S1); y += 60

    # 落库
    d.rounded_rectangle([470, y - 14, 650, y + 44], radius=10, outline=GRID, width=2)
    text(d, (560, y + 15), '会话与消息落库', 14, MUTED, anchor='mm')

    caption(d, W, H, '图 3  检索增强问答时序图')
    save(img, 'fig3-sequence-rag.png')


# ================================================================ 图 4 E-R 图

def fig_er():
    W, H = 1560, 1000
    img, d = canvas(W, H)
    text(d, (W / 2, 38), '知识管理系统 E-R 图（核心部分）', 26, INK, bold=True)

    def entity(x, y, name, attrs, color):
        w, h = 250, 52 + len(attrs) * 26
        box(d, x, y, w, h, fill=tint(color), outline=color, width=3, radius=12)
        text(d, (x + w / 2, y + 28), name, 19, INK, bold=True)
        d.line([x, y + 48, x + w, y + 48], fill=color, width=2)
        for i, a in enumerate(attrs):
            text(d, (x + 14, y + 68 + i * 26), a, 14, INK2, anchor='lm')
        return (x, y, w, h)

    def diamond(cx, cy, label, cardinality, color=INK2):
        d.polygon([(cx - 62, cy), (cx, cy - 30), (cx + 62, cy), (cx, cy + 30)],
                  fill='white', outline=color, width=2)
        text(d, (cx, cy), label, 15, INK)
        text(d, (cx, cy - 48), cardinality, 14, color)

    e_item = entity(90, 210, '知识条目', ['条目主码 id', '标题 / 摘要 / 正文', '纯文本（检索用）', '资产类型 / 状态'], S1)
    e_cat = entity(560, 150, '分类', ['分类 id', '名称 / 编码', '父分类 id', '物化路径 path'], S3)
    e_tag = entity(560, 420, '标签', ['标签 id', '名称 / 颜色', '使用次数'], S2)
    e_con = entity(560, 650, '本体概念', ['概念 id', '名称 / 别名', '父概念 id / 路径', '知识域 / 难度'], S7)
    e_att = entity(1120, 150, '附件', ['附件 id', '原始文件名', '存储路径', '抽取正文'], S1)
    e_ver = entity(1120, 560, '历史版本', ['版本 id', '版本号', '正文快照', '变更说明 / 来源'], S3)

    # 联系
    diamond(415, 300, '属于', 'N : 1')
    arrow(d, (340, 300), (415 - 62, 300), color=LINE, width=2)
    arrow(d, (415 + 62, 300), (560, 240), color=LINE, width=2)

    diamond(415, 470, '标注', 'M : N')
    arrow(d, (340, 430), (415 - 62, 470), color=LINE, width=2)
    arrow(d, (415 + 62, 470), (560, 470), color=LINE, width=2)

    diamond(415, 690, '关联', 'M : N')
    arrow(d, (340, 520), (415 - 62, 690), color=LINE, width=2)
    arrow(d, (415 + 62, 690), (560, 690), color=LINE, width=2)

    diamond(970, 260, '包含', '1 : N')
    arrow(d, (340, 250), (970 - 62, 260), color=LINE, width=2)
    arrow(d, (970 + 62, 260), (1120, 230), color=LINE, width=2)

    diamond(970, 560, '留存', '1 : N')
    arrow(d, (340, 340), (970 - 62, 560), color=LINE, width=2)
    arrow(d, (970 + 62, 560), (1120, 600), color=LINE, width=2)

    # 关联表的说明
    box(d, 90, 740, 400, 150, fill=tint(MUTED), outline=GRID, width=2, radius=12)
    text(d, (290, 772), 'M:N 联系需单独建表', 16, INK, bold=True)
    text(d, (290, 806), '条目-标签(条目id, 标签id)', 14, INK2)
    text(d, (290, 834), '条目-概念(条目id, 概念id,', 14, INK2)
    text(d, (290, 862), '          标注来源, 置信度)', 14, INK2)

    caption(d, W, H, '图 4  知识管理系统 E-R 图（核心部分）')
    save(img, 'fig4-er.png')


# ================================================================ 图 5 体系结构

def fig_arch():
    W, H = 1400, 1000
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '系统体系结构图（三层架构）', 26, INK, bold=True)

    layers = [
        (110, '表现层', S1, ['首页看板', '知识库', '知识图谱', '高级检索', '智能问答',
                             '版本历史', '系统管理'], 'Vue 3 + Element Plus + ECharts + Pinia'),
        (370, '应用层', S3, ['知识库 kb', '知识本体 ontology', '搜索引擎 search',
                             'RAG rag', '内容管理 cms', '归档审计 stats/user'], 'Spring Boot 3.5 · Java 21'),
        (630, '数据层', S2, ['MySQL 8.0', 'utf8mb4', 'ngram 全文索引', '20 张表'],
         '业务表 / 关联表 / 日志表'),
    ]

    for y, name, color, mods, note in layers:
        box(d, 80, y, 1240, 190, fill=tint(color, 0.07), outline=color, width=3, radius=16)
        d.rounded_rectangle([80, y, 190, y + 190], radius=16, fill=color)
        for i, ch in enumerate(name):
            text(d, (135, y + 78 + i * 30), ch, 21, 'white', bold=True)

        n = len(mods)
        total_w = 1020
        gap = 14
        bw = (total_w - gap * (n - 1)) / n
        for i, m in enumerate(mods):
            bx = 210 + i * (bw + gap)
            box(d, bx, y + 34, bw, 62, fill='white', outline=color, width=2, radius=9)
            text(d, (bx + bw / 2, y + 65), m, 15, INK)
        text(d, (700, y + 140), note, 15, INK2)

    # 层间箭头
    for y in (300, 560):
        arrow(d, (700, y), (700, y + 70), color=INK2, width=3, head=13)
        text(d, (700, y + 35), 'HTTP / JSON（JWT 认证）' if y == 300 else 'Spring Data JPA', 15, INK2)

    # 存储
    box(d, 80, 860, 1240, 90, fill=tint(S7, 0.07), outline=S7, width=2, radius=12)
    text(d, (700, 892), '文件存储：kms/storage/YYYY/MM/<uuid>.<ext>',
         16, INK)
    text(d, (700, 922), '按年月分目录；上传时同步抽取文档正文，使附件内容可被检索命中',
         14, MUTED)

    caption(d, W, H, '图 5  系统体系结构图')
    save(img, 'fig5-arch.png')


# ================================================================ 图 6 模块结构

def fig_module():
    W, H = 1500, 1020
    img, d = canvas(W, H)
    text(d, (W / 2, 38), '后端模块结构图', 26, INK, bold=True)

    text(d, (W / 2, 92), 'cn.edu.njupt.kms', 19, INK, bold=True)

    # 顶层横排
    tops = [
        ('config', '配置与启动引导', S1),
        ('common', '统一响应 / 异常 / 事件', S1),
        ('security', 'JWT 认证与授权', S1),
        ('util', '文本处理 / 文件抽取', S1),
        ('module', '业务模块', S2),
    ]
    n = len(tops)
    bw, gap = 250, 20
    total = n * bw + (n - 1) * gap
    x0 = (W - total) / 2
    for i, (name, desc, color) in enumerate(tops):
        bx = x0 + i * (bw + gap)
        box(d, bx, 130, bw, 86, fill=tint(color), outline=color, width=2, radius=10)
        text(d, (bx + bw / 2, 158), name, 17, INK, bold=True)
        text(d, (bx + bw / 2, 188), desc, 13, MUTED)
        if i < n - 1:
            d.line([bx + bw, 173, bx + bw + gap, 173], fill=GRID, width=2)

    # module 展开
    modules = [
        ('kb', '知识库', ['知识条目 CRUD', '分类树（物化路径）', '标签与附件', '多格式正文抽取'], S3),
        ('ontology', '知识本体', ['概念层次维护', '关系三元组', '图谱与推理', '实体链接'], S7),
        ('search', '搜索引擎', ['ngram 全文检索', '高级检索', '应用层重排序', '联想与热搜'], S2),
        ('rag', '检索增强问答', ['文本分块', '向量化（可替换）', '混合检索 + RRF', '生成与引用'], S5),
        ('cms', '内容管理', ['状态流转', '版本快照', '策略判定', 'LCS 差异比对'], S4),
        ('其他', 'archive / audit / stats / user', ['归档与恢复', '审计日志', '统计看板', '用户与角色'], S1),
    ]
    cols = 3
    bw2, bh2, gap2 = 430, 210, 30
    x0 = (W - (cols * bw2 + (cols - 1) * gap2)) / 2
    for i, (pkg, name, items, color) in enumerate(modules):
        r, c = divmod(i, cols)
        bx = x0 + c * (bw2 + gap2)
        by = 300 + r * (bh2 + gap2)
        box(d, bx, by, bw2, bh2, fill=tint(color, 0.08), outline=color, width=2, radius=12)
        text(d, (bx + 20, by + 30), name, 19, INK, anchor='lm', bold=True)
        text(d, (bx + bw2 - 20, by + 30), 'module.' + pkg, 13, MUTED, anchor='rm')
        d.line([bx + 16, by + 52, bx + bw2 - 16, by + 52], fill=color, width=1)
        for j, it in enumerate(items):
            text(d, (bx + 24, by + 82 + j * 30), '· ' + it, 15, INK2, anchor='lm')

    # 依赖关系示例
    text(d, (W / 2, 900),
         '模块间依赖：search 与 rag 复用 kb 的分页与装配逻辑；rag 通过 ItemChangedEvent 监听 kb 的内容变更并异步重建索引',
         14, MUTED)
    text(d, (W / 2, 932),
         '依赖方向保持单向，不存在双向依赖，因此各模块可独立测试与替换',
         14, MUTED)

    caption(d, W, H, '图 6  后端模块结构图')
    save(img, 'fig6-module.png')


# ================================================================ 图 7 版本策略判定

def fig_version_policy():
    W, H = 1300, 900
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '版本快照建版判定流程图', 26, INK, bold=True)

    def step(x, y, w, h, label, color, sub=None):
        box(d, x, y, w, h, fill=tint(color), outline=color, width=2, radius=10)
        text(d, (x + w / 2, y + (h / 2 - 10 if sub else h / 2)), label, 16, INK)
        if sub:
            text(d, (x + w / 2, y + h / 2 + 16), sub, 13, MUTED)

    def decision(cx, cy, w, h, label):
        d.polygon([(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)],
                  fill=tint(S4), outline=S4, width=2)
        text(d, (cx, cy), label, 16, INK)

    step(510, 92, 280, 66, '内容保存', S1)

    arrow(d, (650, 158), (650, 200), color=INK2, width=2)
    decision(650, 250, 380, 96, '正文 MD5 是否变化？')
    text(d, (880, 250), '否 → 跳过建版', 14, S3)

    arrow(d, (650, 298), (650, 350), color=INK2, width=2)

    step(430, 350, 440, 76, '解析生效策略', S7, '条目级 → 分类级 → 全局级，命中即停止')

    arrow(d, (650, 426), (650, 478), color=INK2, width=2)

    decision(650, 528, 380, 96, '距上次快照天数\n≥ 天数阈值？')

    # 左分支
    d.line([460, 528, 300, 528, 300, 640], fill=S3, width=2)
    arrow(d, (300, 610), (300, 640), color=S3, width=2)
    step(160, 640, 280, 70, '生成新版本快照', S3)

    # 下分支
    arrow(d, (650, 576), (650, 640), color=INK2, width=2)
    text(d, (668, 608), '否', 14, MUTED)
    decision(650, 690, 380, 96, '累计修改次数\n≥ 次数阈值？')

    # 否分支 → 结束
    d.line([650, 738, 650, 800], fill=S2, width=2)
    arrow(d, (650, 800), (650, 820), color=S2, width=2)
    text(d, (672, 776), '否（不建版）', 14, S2)

    # 是分支
    d.line([840, 690, 1000, 690, 1000, 675], fill=S3, width=2)
    arrow(d, (1000, 700), (1000, 675), color=S3, width=2)
    text(d, (905, 666), '是', 14, S3)
    step(880, 600, 240, 70, '生成新版本快照', S3)

    # 汇总到右侧
    d.line([1140, 635, 1210, 635, 1210, 675, 1120, 675], fill=S3, width=2)
    d.line([440, 675, 1120, 675], fill=S3, width=2)
    arrow(d, (440, 700), (440, 675), color=S3, width=2)
    text(d, (780, 700), '满足任一条件即建版（两个阈值是「或」的关系）', 15, S3)

    # 收尾
    arrow(d, (780, 720), (780, 770), color=INK2, width=2)
    step(600, 770, 360, 74, '推进版本号、重置修改计数', S1,
         '超过保留上限时淘汰最旧版本')

    caption(d, W, H, '图 7  版本快照建版判定流程图')
    save(img, 'fig7-version-policy.png')


# ================================================================ 图 8 混合检索

def fig_hybrid():
    W, H = 1450, 900
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '混合检索与 RRF 融合流程图', 26, INK, bold=True)

    def step(x, y, w, h, label, color, sub=None):
        box(d, x, y, w, h, fill=tint(color), outline=color, width=2, radius=10)
        text(d, (x + w / 2, y + (h / 2 - 11 if sub else h / 2)), label, 16, INK)
        if sub:
            text(d, (x + w / 2, y + h / 2 + 17), sub, 13, MUTED)

    step(555, 92, 340, 68, '用户查询串', S1)

    arrow(d, (725, 160), (725, 200), color=INK2, width=2)
    step(530, 200, 390, 74, '本体查询扩展', S7, '实体链接 + 沿关系补充相邻概念')

    arrow(d, (725, 274), (725, 320), color=INK2, width=2)

    # 两条通道
    step(180, 320, 420, 96, '向量通道', S2, '问题向量化 → 余弦相似度 Top-20')
    step(850, 320, 420, 96, '关键字通道', S3, 'MySQL ngram 全文索引 Top-20')

    arrow(d, (725, 320), (390, 320), color=S2, width=2)
    d.line([725, 320, 1060, 320], fill=S3, width=2)
    arrow(d, (1000, 320), (1060, 320), color=S3, width=2)

    # 说明
    box(d, 180, 436, 420, 76, fill=tint(MUTED, 0.08), outline=GRID, width=1, radius=9)
    text(d, (390, 460), '擅长「问法与原文用词不同」', 14, INK2)
    text(d, (390, 486), '弱项：精度较低，易召回泛泛内容', 13, MUTED)

    box(d, 850, 436, 420, 76, fill=tint(MUTED, 0.08), outline=GRID, width=1, radius=9)
    text(d, (1060, 460), '词面命中精确、可解释、快', 14, INK2)
    text(d, (1060, 486), '弱项：无法处理同义改写', 13, MUTED)

    # 融合
    arrow(d, (390, 512), (390, 570), color=S2, width=2)
    arrow(d, (1060, 512), (1060, 570), color=S3, width=2)
    d.line([390, 570, 1060, 570], fill=INK2, width=2)
    arrow(d, (725, 570), (725, 604), color=INK2, width=2)

    step(490, 604, 470, 92, 'RRF 倒数排名融合', S4,
         'score = Σ 1/(k + rank)，k = 60')
    text(d, (1140, 640), '仅用排名、不用分数', 14, MUTED)
    text(d, (1140, 666), '规避两通道量纲不可比的问题', 14, MUTED)

    arrow(d, (725, 696), (725, 740), color=INK2, width=2)
    step(530, 740, 390, 80, '重排序并取 Top-K', S1, '字段命中率 65% + 全文相关度 25% + 热度 10%')

    caption(d, W, H, '图 8  混合检索与 RRF 融合流程图')
    save(img, 'fig8-hybrid-retrieval.png')


# ================================================================ 图 9 文本分块

def fig_chunking():
    W, H = 1400, 880
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '文本分块流程图', 26, INK, bold=True)

    def step(x, y, w, h, label, color, sub=None):
        box(d, x, y, w, h, fill=tint(color), outline=color, width=2, radius=10)
        text(d, (x + w / 2, y + (h / 2 - 11 if sub else h / 2)), label, 16, INK)
        if sub:
            text(d, (x + w / 2, y + h / 2 + 17), sub, 13, MUTED)

    def decision(cx, cy, w, h, label):
        d.polygon([(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)],
                  fill=tint(S4), outline=S4, width=2)
        text(d, (cx, cy), label, 15, INK)

    step(490, 92, 420, 70, '条目正文 + 附件抽取文本', S1)

    arrow(d, (700, 162), (700, 206), color=INK2, width=2)
    step(490, 206, 420, 70, '归一化空白字符', S1)

    arrow(d, (700, 276), (700, 320), color=INK2, width=2)
    step(490, 320, 420, 70, '按空行切分为自然段', S3)

    arrow(d, (700, 390), (700, 434), color=INK2, width=2)
    decision(700, 484, 400, 96, '段落长度 > 目标块长？')

    # 否分支
    text(d, (516, 448), '否', 14, MUTED)
    d.line([500, 484, 240, 484, 240, 560], fill=S3, width=2)
    arrow(d, (240, 560), (240, 560), color=S3, width=2)
    step(90, 560, 300, 74, '与相邻段落拼接', S3, '拼到接近目标长度')

    # 是分支
    text(d, (712, 532), '是', 14, S2)
    arrow(d, (700, 532), (700, 560), color=S2, width=2)
    step(470, 560, 460, 74, '按句末标点二次切分', S2,
         '仍超长则按长度硬切')

    # 汇合
    d.line([240, 634, 240, 680, 700, 680], fill=INK2, width=2)
    d.line([700, 634, 700, 680], fill=INK2, width=2)
    arrow(d, (700, 680), (700, 710), color=INK2, width=2)

    step(470, 710, 460, 74, '添加 80 字符重叠窗口', S7,
         '避免关键信息被切分点割裂')

    # 右侧参数说明
    box(d, 1010, 92, 330, 240, fill=tint(MUTED, 0.07), outline=GRID, width=2, radius=12)
    text(d, (1175, 126), '分块参数', 17, INK, bold=True)
    d.line([1030, 148, 1320, 148], fill=GRID, width=1)
    for i, (k, v) in enumerate([('目标块长', '420 字符'), ('重叠长度', '80 字符'),
                                ('切分优先级', '段落 > 句子 > 长度'),
                                ('保留上限', '单块 ≤ 500 字符')]):
        text(d, (1032, 176 + i * 38), k, 14, MUTED, anchor='lm')
        text(d, (1318, 176 + i * 38), v, 14, INK, anchor='rm')

    box(d, 1010, 360, 330, 200, fill=tint(S1, 0.07), outline=S1, width=2, radius=12)
    text(d, (1175, 392), '为什么这样分', 17, INK, bold=True)
    d.line([1030, 414, 1320, 414], fill=S1, width=1)
    for i, s in enumerate(['块太大：噪声多、向量语义模糊',
                           '块太小：上下文不足、论述被割裂',
                           '按段落切：保持论述完整',
                           '加重叠：避免信息落在切点上']):
        text(d, (1034, 444 + i * 32), '· ' + s, 13.5, INK2, anchor='lm')

    caption(d, W, H, '图 9  文本分块流程图')
    save(img, 'fig9-chunking.png')


# ================================================================ 图 10 本体层次

def fig_ontology():
    W, H = 1520, 940
    img, d = canvas(W, H)
    text(d, (W / 2, 38), '知识本体层次结构示意（局部）', 26, INK, bold=True)

    def node(x, y, w, label, color, level=0, count=None):
        h = 46
        box(d, x, y, w, h, fill=tint(color), outline=color,
            width=3 if level == 0 else 2, radius=9)
        text(d, (x + w / 2, y + h / 2), label, 16 if level == 0 else 15, INK,
             bold=(level == 0))
        if count:
            text(d, (x + w + 14, y + h / 2), count, 13, MUTED, anchor='lm')

    domains = [
        ('软件工程', S1, 60, [
            ('软件需求', ['需求获取', '需求分析', '需求规格说明', '需求验证']),
            ('软件设计', ['体系结构设计', '详细设计', '设计模式', 'UML 建模']),
            ('软件构造', ['编码实现', '代码评审', '版本控制']),
            ('软件测试', ['单元测试', '集成测试', '黑盒/白盒测试', '测试用例设计']),
            ('项目管理', ['进度管理', '成本估算', '风险管理', '质量保证']),
        ]),
        ('数据库', S3, 510, [
            ('数据模型', ['E-R 模型', '关系模型', 'NoSQL 模型']),
            ('关系理论', ['关系代数', '函数依赖', '规范化理论']),
            ('SQL', ['连接查询', '子查询', '索引与查询优化']),
            ('数据库设计', ['概念结构设计', '逻辑结构设计', '数据字典']),
            ('实现与运行', ['完整性约束', '事务', '并发控制']),
        ]),
    ]

    # 根
    box(d, 610, 92, 300, 56, fill=tint(S2), outline=S2, width=3, radius=12)
    text(d, (760, 120), '课程知识本体', 20, INK, bold=True)

    for dname, color, x, branches in domains:
        # 域节点
        box(d, x, 200, 400, 54, fill=color, outline=color, width=2, radius=10)
        text(d, (x + 200, 227), dname, 19, 'white', bold=True)
        d.line([760, 148, 760, 174, x + 200, 174, x + 200, 200], fill=color, width=2)
        arrow(d, (x + 200, 180), (x + 200, 200), color=color, width=2)

        for i, (branch, children) in enumerate(branches):
            by = 288 + i * 124
            node(x - 120, by, 240, branch, color, level=1)
            d.line([x + 200, 227, x + 200, by + 23, x + 120, by + 23], fill=color, width=1)
            d.line([x + 120, by + 23, x + 120, by + 23], fill=color, width=1)

            for j, ch in enumerate(children):
                cy = by + 8 + (j - (len(children) - 1) / 2) * 30
                cx = x + 250
                box(d, cx, cy - 13, 230, 28, fill='white', outline=color, width=1, radius=6)
                text(d, (cx + 115, cy), ch, 13, INK2)

    # 关系标注
    box(d, 60, 900 - 60, 1400, 48, fill=tint(S7, 0.07), outline=S7, width=1, radius=9)
    text(d, (760, 900 - 36),
         '层次关系（上下位）表达「属于哪一类」；跨分支的语义关系（先修、组成、应用于、产出、相关）表达「与谁相关、如何相关」',
         14, INK2)

    caption(d, W, H, '图 10  知识本体层次结构示意（局部）')
    save(img, 'fig10-ontology.png')


# ================================================================ 图 11 重排序

def fig_rerank():
    W, H = 1450, 880
    img, d = canvas(W, H)
    text(d, (W / 2, 40), '检索结果重排序示意图', 26, INK, bold=True)

    def bar(x, y, w, h, score, color, label, sub):
        d.rounded_rectangle([x, y, x + w, y + h], radius=6, fill=tint(color, 0.55),
                            outline=color, width=2)
        text(d, (x + 12, y + h / 2), label, 14, INK, anchor='lm')
        text(d, (x + w - 12, y + h / 2), '%.3f' % score, 14, INK, anchor='rm', bold=True)
        text(d, (x + w + 16, y + h / 2), sub, 13, MUTED, anchor='lm')

    # 左：数据库召回顺序
    text(d, (390, 96), '数据库召回顺序（ngram 相关度）', 18, INK, bold=True)
    text(d, (390, 126), '长文档因二元组多而被推高，标题命中者被挤出候选', 13.5, MUTED)
    left = [
        (0.94, '知识库全文检索方案选型报告', '正文含大量「软件/测试」二元组'),
        (0.81, '知识卡片：软件质量保证与软件度量', '标题部分命中'),
        (0.76, '测试数据准备说明与验收自查清单', '标题部分命中'),
        (0.68, '知识卡片：倒排索引', '正文命中'),
        (0.61, '演示录屏：知识管理系统功能全流程演示', '正文命中'),
    ]
    for i, (s, t, sub) in enumerate(left):
        bar(60, 176 + i * 76, 520, 54, s, MUTED, t, '')

    # 箭头
    arrow(d, (700, 380), (790, 380), color=INK2, width=3, head=15)
    text(d, (745, 348), '重排序', 15, INK2)

    # 右：应用层重排序后
    text(d, (1090, 96), '应用层重排序后', 18, INK, bold=True)
    text(d, (1090, 126), '字段命中率主导，标题精确命中者升到首位', 13.5, MUTED)
    right = [
        (0.899, '软件测试用例设计与覆盖率分析报告', '标题完整命中 + 全文相关度'),
        (0.470, '知识库全文检索方案选型报告', '仅正文命中，降权'),
        (0.367, '知识卡片：软件质量保证与软件度量', '部分命中'),
        (0.330, '测试数据准备说明与验收自查清单', '部分命中'),
        (0.291, '知识卡片：倒排索引', '正文命中'),
    ]
    for i, (s, t, sub) in enumerate(right):
        bar(760, 176 + i * 76, 520, 54, s, S1, t, '')

    # 公式
    box(d, 200, 600, 1050, 120, fill=tint(S4, 0.08), outline=S4, width=2, radius=12)
    text(d, (725, 638), '最终得分 = 字段命中率 × 0.65  +  全文相关度 × 0.25  +  热度 × 0.10',
         17, INK, bold=True)
    text(d, (725, 674), '其中字段命中率 = 标题覆盖 0.8 + 摘要与关键词覆盖 0.2；标题完整包含查询串时额外 +0.20',
         14, MUTED)
    text(d, (725, 700), '候选集放大 6 倍召回（上限 300 条），排序与分页均在应用层完成',
         14, MUTED)

    caption(d, W, H, '图 11  检索结果重排序示意图')
    save(img, 'fig11-rerank.png')


# ================================================================

def main():
    print('生成报告示意图……')
    fig_usecase()
    fig_state()
    fig_sequence()
    fig_er()
    fig_arch()
    fig_module()
    fig_version_policy()
    fig_hybrid()
    fig_chunking()
    fig_ontology()
    fig_rerank()
    print('完成，输出目录：docs/report-figures/')


if __name__ == '__main__':
    main()
