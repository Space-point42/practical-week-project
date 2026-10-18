# -*- coding: utf-8 -*-
"""
生成《专业课程设计II报告》Word 文档。

严格遵循报告模板的排版要求：
  · 中文字体宋体，英文字体 Times New Roman
  · 题目：宋体三号加粗居中；一级标题：宋体四号加粗，两端对齐顶格不缩进
  · 二级标题：宋体小四不加粗，两端对齐不缩进
  · 正文：小四号，两端对齐，1.5 倍行距，首行缩进 2 字符，段前段后为 0
  · 图：统一编号、居中排版，图名为五号并居中
  · 源代码：Times New Roman 五号，单倍行距，首行缩进 2 字符

用法：python scripts/build_report.py
输出：docs/专业课程设计II报告_学号.docx
"""

import os

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIG_DIR = os.path.join(ROOT, 'docs', 'report-figures')
SHOT_DIR = os.path.join(ROOT, 'docs', 'screenshots')
OUT_PATH = os.path.join(ROOT, 'docs', '专业课程设计II报告_学号.docx')

CN_FONT = '宋体'
EN_FONT = 'Times New Roman'

# 中文字号 → 磅值
SZ_3 = Pt(16)      # 三号
SZ_4 = Pt(14)      # 四号
SZ_S4 = Pt(12)     # 小四
SZ_5 = Pt(10.5)    # 五号

_fig_no = [0]


# ================================================================ 排版基元

def cn_quotes(text):
    """把正文里的半角双引号成对转换为中文全角引号。

    中文文书里应当使用全角引号「“ ”」，半角引号是英文排版的习惯。
    只在正文与表格中做这个转换，源代码块不做处理 ——
    代码里的引号是 Java 字符串字面量的一部分，必须保持半角。
    """
    out = []
    opening = True
    for ch in text:
        if ch == '"':
            out.append('“' if opening else '”')
            opening = not opening
        else:
            out.append(ch)
    return ''.join(out)


def set_font(run, size=SZ_S4, bold=False, cn=CN_FONT, en=EN_FONT, color=None):
    run.font.size = size
    run.bold = bold
    run.font.name = en
    run._element.rPr.rFonts.set(qn('w:eastAsia'), cn)
    if color:
        run.font.color.rgb = color


def set_first_line_chars(paragraph, chars=200):
    """按"字符"设置首行缩进（w:firstLineChars 以百分之一字符为单位）。

    用字符单位而不是磅值，是因为字号变化时缩进会自动跟随，
    这正是中文公文排版所要求的「首行缩进 2 字符」。
    """
    p_pr = paragraph._p.get_or_add_pPr()
    ind = p_pr.find(qn('w:ind'))
    if ind is None:
        ind = OxmlElement('w:ind')
        p_pr.append(ind)
    ind.set(qn('w:firstLineChars'), str(chars))
    ind.set(qn('w:firstLine'), str(int(chars * 2.4)))  # 兼容不支持 Chars 的阅读器


def no_space(paragraph):
    paragraph.paragraph_format.space_before = Pt(0)
    paragraph.paragraph_format.space_after = Pt(0)


def body(doc, text, indent=True, align=WD_ALIGN_PARAGRAPH.JUSTIFY, size=SZ_S4):
    """正文段落：小四、1.5 倍行距、首行缩进 2 字符。"""
    p = doc.add_paragraph()
    p.alignment = align
    p.paragraph_format.line_spacing = 1.5
    no_space(p)
    if indent:
        set_first_line_chars(p)
    run = p.add_run(cn_quotes(text))
    set_font(run, size=size)
    return p


def h1(doc, text):
    """一级标题：宋体四号加粗，两端对齐，顶格不缩进。"""
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    p.paragraph_format.line_spacing = 1.5
    no_space(p)
    p.paragraph_format.space_before = Pt(12)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run(cn_quotes(text))
    set_font(run, size=SZ_4, bold=True)
    return p


def h2(doc, text):
    """二级标题：宋体小四不加粗，两端对齐，不缩进。"""
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    p.paragraph_format.line_spacing = 1.5
    no_space(p)
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(cn_quotes(text))
    set_font(run, size=SZ_S4, bold=False)
    return p


def title_center(doc, text, size=SZ_3):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.line_spacing = 1.5
    no_space(p)
    p.paragraph_format.space_after = Pt(18)
    run = p.add_run(text)
    set_font(run, size=size, bold=True)
    return p


def figure(doc, filename, caption, width=6.1, subdir=None):
    """插入居中图片并自动编号图注（五号、居中、不缩进）。"""
    _fig_no[0] += 1
    folder = subdir or FIG_DIR
    path = os.path.join(folder, filename)
    if not os.path.exists(path):
        body(doc, '[缺少图片：%s]' % filename)
        return

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.line_spacing = 1.0
    no_space(p)
    p.paragraph_format.space_before = Pt(6)
    p.add_run().add_picture(path, width=Inches(width))

    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.line_spacing = 1.0
    no_space(cap)
    cap.paragraph_format.space_after = Pt(12)
    run = cap.add_run('图 %d  %s' % (_fig_no[0], caption))
    set_font(run, size=SZ_5)


def code_block(doc, lines, caption=None):
    """源代码块：Times New Roman 五号、单倍行距、首行缩进 2 字符。"""
    for line in lines:
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        p.paragraph_format.line_spacing = 1.0
        no_space(p)
        set_first_line_chars(p)
        run = p.add_run(line if line else ' ')
        set_font(run, size=SZ_5)
    if caption:
        cap = doc.add_paragraph()
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cap.paragraph_format.line_spacing = 1.0
        no_space(cap)
        cap.paragraph_format.space_after = Pt(10)
        run = cap.add_run(caption)
        set_font(run, size=SZ_5)


def table(doc, headers, rows, widths=None, caption=None):
    """插入表格；表头加粗，正文小四，全部居中。"""
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = 'Table Grid'
    t.alignment = WD_TABLE_ALIGNMENT.CENTER

    for i, htext in enumerate(headers):
        cell = t.rows[0].cells[i]
        cell.text = ''
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.line_spacing = 1.0
        no_space(p)
        run = p.add_run(str(htext))
        set_font(run, size=SZ_5, bold=True)

    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ''
            p = cells[i].paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER if i == 0 else WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.line_spacing = 1.0
            no_space(p)
            run = p.add_run(cn_quotes(str(val)))
            set_font(run, size=SZ_5)

    if widths:
        for r in t.rows:
            for i, w in enumerate(widths):
                r.cells[i].width = Cm(w)

    if caption:
        cap = doc.add_paragraph()
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cap.paragraph_format.line_spacing = 1.0
        no_space(cap)
        cap.paragraph_format.space_before = Pt(6)
        cap.paragraph_format.space_after = Pt(12)
        run = cap.add_run(caption)
        set_font(run, size=SZ_5)
    else:
        doc.add_paragraph().paragraph_format.space_after = Pt(6)
    return t


def page_break(doc):
    doc.add_page_break()


# ================================================================ 封面与评分表

def build_cover(doc):
    for _ in range(3):
        p = doc.add_paragraph()
        no_space(p)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    no_space(p)
    run = p.add_run('专业课程设计II报告')
    set_font(run, size=Pt(36), bold=True, cn='黑体')

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    no_space(p)
    p.paragraph_format.space_after = Pt(60)
    run = p.add_run('（ 2026 / 2027 学年   第 一 学期 ）')
    set_font(run, size=SZ_4)

    rows = [
        ('题　　目：', '知识管理系统'),
        ('专　　业：', '【专业】'),
        ('学生姓名：', '【姓名】'),
        ('班级学号：', '【班级】  【学号】'),
        ('指导教师：', '朱　曼'),
        ('指导单位：', '计算机科学与技术系'),
        ('日　　期：', '2026.10.12 – 2026.10.23'),
    ]
    t = doc.add_table(rows=0, cols=2)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for label, value in rows:
        cells = t.add_row().cells
        for idx, txt in enumerate((label, value)):
            cells[idx].text = ''
            p = cells[idx].paragraphs[0]
            # 标签右对齐，使各行的冒号在竖直方向对齐（中文公文封面的习惯做法）
            p.alignment = WD_ALIGN_PARAGRAPH.RIGHT if idx == 0 else WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.line_spacing = 1.5
            no_space(p)
            run = p.add_run(txt)
            set_font(run, size=SZ_4, bold=(idx == 0))
        cells[0].width = Cm(3.6)
        cells[1].width = Cm(8.4)

    page_break(doc)


def build_score_sheet(doc):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    no_space(p)
    p.paragraph_format.space_after = Pt(10)
    run = p.add_run('专业课程设计II能力测评表')
    set_font(run, size=SZ_4, bold=True)

    goals = [
        ('课程目标1：通过课程设计提高学生的理论联系实际、查阅资料、自学和独立分析与解决复杂工程问题的能力（30分）', [
            '1、能够掌握计算机软件设计的相关基础知识，并能够针对求解的工程问题，进行合理的分析与设计。',
            '2、能够给出数据结构和算法的设计描述，给出关键算法的流程图或伪代码，并给出各算法之间的结构关系描述。',
            '3、具备一定的人机交互设计意识，人机交互设计合理、友好，操作简便。',
        ]),
        ('课程目标2：要求学生在深入理解软件系统的相关知识基础上，综合运用所学的方法和理论自主地完成课程设计。要求学生通过软件课题的开发实践活动熟悉一种主流的数据库管理系统产品和软件开发工具，掌握软件设计和开发的理论方法与实施步骤。要求学生在软件开发的过程中考虑到可持续发展的因素，例如安全以及环保等。（30分）', [
            '4、能够结合计算机软硬件资源，合理选用算法、数据结构、数据存储方式等技术手段，对求解的工程问题进行有效建模和求解。',
            '5、能够选择合适的程序设计语言与软件开发平台，对求解的工程问题进行编程实现。掌握调试方法与工具，对程序开发过程中出现的问题进行分析、跟踪与调试，并能够进行充分测试。',
            '6、在完成课题要求的各项任务和指标时能够充分考虑可持续发展因素，例如安全和环保等。',
        ]),
        ('课程目标3：要求学生完成一次项目设计与开发的全过程，掌握软件需求分析、软件设计、软件测试和团队协同开发的基本理论和技术实践。应用工程项目管理原理和经济决策方法划分和管理课题任务，培养项目组织管理能力。（20分）', [
            '7、能够正确理解课题目标任务，并做好需求分析、软件设计、软件测试等关键环节的实践工作，具备团队协调、协作和组织管理能力。',
            '8、具备一定的语言表达能力与文字处理能力，能够结合复杂工程问题撰写报告，报告内容和实验数据详实，格式规范。',
        ]),
        ('课程目标4：要求学生能够通过参与项目设计与开发的全过程，建立解决实际问题需要不断探索和学习的主动性意识，能够结合实际问题的特征需求，通过自主学习、终生学习，拓展自身的知识和能力。（20分）', [
            '9、具备一定自学能力与探索创新意识，能够充分利用教科书及其资源（如网络等）自学新知识与新技能，以解决实际问题。',
            '10、能够正确、完整地回答指导教师关于课题开发全过程的问询，能够掌握解决具体问题的一般性规律和方法，具备自主学习、终生学习的意识。',
        ]),
    ]

    t = doc.add_table(rows=1, cols=3)
    t.style = 'Table Grid'
    for i, htext in enumerate(['课程目标', '评价准则', '计分\n（每项10分）']):
        cell = t.rows[0].cells[i]
        cell.text = ''
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        no_space(p)
        run = p.add_run(htext)
        set_font(run, size=SZ_5, bold=True)

    for goal, criteria in goals:
        cells = t.add_row().cells
        cells[0].text = ''
        p = cells[0].paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        p.paragraph_format.line_spacing = 1.0
        no_space(p)
        run = p.add_run(goal)
        set_font(run, size=SZ_5)

        cells[1].text = ''
        for idx, c in enumerate(criteria):
            p = cells[1].paragraphs[0] if idx == 0 else cells[1].add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            p.paragraph_format.line_spacing = 1.0
            no_space(p)
            run = p.add_run(c)
            set_font(run, size=SZ_5)
        cells[2].text = ''

    widths = [6.0, 8.6, 2.4]
    for r in t.rows:
        for i, w in enumerate(widths):
            r.cells[i].width = Cm(w)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    no_space(p)
    p.paragraph_format.space_before = Pt(6)
    run = p.add_run('专业课程设计II能力测评总分：')
    set_font(run, size=SZ_5, bold=True)

    p = doc.add_paragraph()
    no_space(p)
    p.paragraph_format.space_before = Pt(12)
    run = p.add_run('指导教师：　　　　　　　　　　　　　　　2026 年 12 月 31 日')
    set_font(run, size=SZ_5)

    p = doc.add_paragraph()
    no_space(p)
    p.paragraph_format.space_before = Pt(8)
    run = p.add_run('备注：')
    set_font(run, size=SZ_5, bold=True)

    page_break(doc)
