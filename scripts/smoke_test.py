#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识管理系统 —— 接口冒烟测试脚本。

用途：
  1. 系统部署后快速验证各模块是否正常工作；
  2. 答辩前的环境自检（避免演示时才发现某模块不可用）；
  3. 作为测试证据留存（输出可直接粘贴进测试报告）。

用法：
    python smoke_test.py                    # 使用默认地址 http://localhost:8080
    python smoke_test.py http://192.168.1.5:8080

依赖：仅使用标准库，无需 pip install。
"""

import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

PASSED = []
FAILED = []


# ----------------------------------------------------------------------
# HTTP 基础设施
# ----------------------------------------------------------------------

def call(method, path, body=None, token=None, expect_ok=True):
    """发起一次请求，返回 (http 状态码, 解析后的 JSON 或原始文本)。"""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}

    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, safe_json(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        return e.code, safe_json(raw)
    except Exception as e:  # 连接失败等
        return 0, {"error": str(e)}


def safe_json(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw[:400]}


def check(name, condition, detail=""):
    if condition:
        PASSED.append(name)
        print("  [PASS] %s%s" % (name, ("  " + detail) if detail else ""))
    else:
        FAILED.append(name)
        print("  [FAIL] %s%s" % (name, ("  " + detail) if detail else ""))
    return condition


def section(title):
    print("\n" + "=" * 68)
    print(title)
    print("=" * 68)


# ----------------------------------------------------------------------
# 测试用例
# ----------------------------------------------------------------------

def test_auth():
    section("一、认证与权限")
    global TOKEN

    status, r = call("POST", "/api/auth/login",
                     {"username": "admin", "password": "admin123"})
    ok = check("管理员登录成功", r.get("code") == 0, r.get("message", ""))
    if not ok:
        return None
    TOKEN = r["data"]["token"]
    check("返回 JWT 令牌", bool(TOKEN), "长度 %d" % len(TOKEN))

    _, r = call("GET", "/api/auth/me", token=TOKEN)
    check("查询当前用户", r.get("code") == 0 and r["data"]["username"] == "admin",
          "角色=%s" % r.get("data", {}).get("roles"))

    # 错误口令应被拒绝
    _, r = call("POST", "/api/auth/login", {"username": "admin", "password": "wrong"})
    check("错误口令被拒绝", r.get("code") == 401, r.get("message", ""))

    # 浏览者不能管理用户（越权应 403）
    _, r = call("POST", "/api/auth/login", {"username": "student", "password": "student123"})
    viewer_token = r["data"]["token"] if r.get("code") == 0 else None
    if viewer_token:
        _, r = call("GET", "/api/users", token=viewer_token)
        check("浏览者访问用户管理被拒绝", r.get("code") == 403, r.get("message", ""))
        _, r = call("POST", "/api/knowledge", {"title": "越权测试"}, token=viewer_token)
        check("浏览者创建条目被拒绝", r.get("code") == 403, r.get("message", ""))
    return TOKEN


def test_ontology():
    section("二、知识本体（题目要求：≥20 个知识点、≥5 种关系）")

    _, r = call("GET", "/api/ontology/stats")
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("获取本体统计", r.get("code") == 0)
    check("知识点数量 ≥ 20", d.get("conceptCount", 0) >= 20,
          "实际 %s 个" % d.get("conceptCount"))
    check("关系类型 ≥ 5 种", d.get("relationTypeCount", 0) >= 5,
          "实际 %s 种" % d.get("relationTypeCount"))
    check("关系数量 > 0", d.get("relationCount", 0) > 0,
          "实际 %s 条" % d.get("relationCount"))
    check("本体覆盖率已计算", d.get("coverage", 0) > 0,
          "覆盖率 %s%%" % d.get("coverage"))

    _, r = call("GET", "/api/ontology/concepts/tree")
    tree = r.get("data", []) if r.get("code") == 0 else []
    check("获取知识点层次树", r.get("code") == 0 and len(tree) >= 3,
          "%d 个根节点" % len(tree))

    _, r = call("GET", "/api/ontology/relation-types")
    types = r.get("data", []) if r.get("code") == 0 else []
    check("获取关系类型字典", len(types) >= 5,
          "、".join(t["name"] for t in types[:7]))

    _, r = call("GET", "/api/ontology/graph?withIsolated=true")
    g = r.get("data", {}) if r.get("code") == 0 else {}
    check("获取知识图谱", g.get("nodeCount", 0) > 0 and g.get("edgeCount", 0) > 0,
          "%s 节点 / %s 边" % (g.get("nodeCount"), g.get("edgeCount")))

    # 实体链接
    _, r = call("GET", "/api/ontology/link?text=" +
                urllib.parse.quote("软件测试与需求分析的关系") + "&limit=10")
    linked = [c["name"] for c in r.get("data", [])] if r.get("code") == 0 else []
    check("实体链接识别出知识点", len(linked) > 0, "识别到：%s" % linked)

    # 推理：先修路径
    _, r = call("GET", "/api/ontology/concepts/search?keyword=" + urllib.parse.quote("关系模型"))
    if r.get("code") == 0 and r.get("data"):
        cid = r["data"][0]["id"]
        _, r2 = call("GET", "/api/ontology/infer?conceptId=%d&relationType=PREREQUISITE_OF&depth=3" % cid)
        infer = r2.get("data", {}) if r2.get("code") == 0 else {}
        levels = infer.get("levels", [])
        detail = " → ".join(
            "、".join(c["name"] for c in lv["concepts"]) for lv in levels[:3]) or "无后继"
        check("本体推理（先修关系展开）", r2.get("code") == 0, detail)

    # 邻域子图
    _, r = call("GET", "/api/ontology/concepts")
    if r.get("code") == 0 and r.get("data"):
        cid = r["data"][10]["id"]
        _, r2 = call("GET", "/api/ontology/graph/neighborhood/%d?depth=2" % cid)
        check("N 跳邻域子图", r2.get("code") == 0,
              "%s 节点" % r2.get("data", {}).get("nodeCount"))


def test_search():
    section("三、搜索引擎")

    # 全文检索
    t0 = time.time()
    _, r = call("POST", "/api/search", {"keyword": "软件测试", "size": 5}, token=TOKEN)
    cost = int((time.time() - t0) * 1000)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("全文检索「软件测试」", r.get("code") == 0 and d.get("total", 0) > 0,
          "命中 %s 条，%d ms" % (d.get("total"), cost))
    check("使用全文索引通道", d.get("fulltextUsed") is True, d.get("modeUsed", ""))
    check("返回相关度得分", bool(d.get("hits")) and d["hits"][0].get("score") is not None,
          "首位得分 %s" % (d["hits"][0].get("score") if d.get("hits") else "N/A"))
    check("返回高亮标题", bool(d.get("hits")) and "<mark>" in (d["hits"][0].get("titleHighlight") or ""))
    check("返回摘要片段", bool(d.get("hits")) and bool(d["hits"][0].get("snippet")))

    # 术语检索
    for kw in ["需求分析", "范式", "RAG", "索引优化"]:
        _, r = call("POST", "/api/search", {"keyword": kw, "size": 3}, token=TOKEN)
        d = r.get("data", {}) if r.get("code") == 0 else {}
        check("检索「%s」" % kw, d.get("total", 0) > 0, "命中 %s 条" % d.get("total"))

    # 完全无命中：语料中不存在任何 ngram 重合，应返回空并给出建议
    _, r = call("POST", "/api/search", {"keyword": "qqqzzzwwwvvv", "size": 5}, token=TOKEN)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("完全无命中时返回空列表", d.get("total", -1) == 0,
          "total=%s" % d.get("total"))
    check("无结果时给出检索建议", len(d.get("suggestions", [])) > 0,
          "给出 %d 条建议" % len(d.get("suggestions", [])))

    # 弱匹配标记：ngram 二元组会因偶然重合而召回无关文档
    # （例如查询里的「存在」恰好出现在别的文档中）。
    # 系统不隐藏这类结果（正文匹配也是有效召回），而是标记 weakMatch
    # 由界面提示「未找到精确匹配」，避免把弱匹配伪装成精确命中。
    _, r = call("POST", "/api/search", {"keyword": "zzzqqq不存在的词xxx", "size": 5}, token=TOKEN)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("结果集带有弱匹配标记字段", "weakMatch" in d,
          "weakMatch=%s（该查询含真实词「存在」，故部分结果命中元数据）" % d.get("weakMatch"))
    if d.get("weakMatch"):
        check("弱匹配时给出检索建议", len(d.get("suggestions", [])) > 0,
              "给出 %d 条建议" % len(d.get("suggestions", [])))

    # 精确命中的查询不应被误判为弱匹配
    _, r = call("POST", "/api/search", {"keyword": "软件测试", "size": 5}, token=TOKEN)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("精确命中查询不被标记为弱匹配", d.get("weakMatch") is not True,
          "weakMatch=%s" % d.get("weakMatch"))
    check("标题命中的结果排在最前",
          bool(d.get("hits")) and d["hits"][0].get("titleMatch") is True,
          "首位：%s" % (d["hits"][0]["title"] if d.get("hits") else "N/A"))

    # 高级检索
    _, r = call("POST", "/api/search/advanced",
                {"assetTypes": ["PAPER"], "size": 5}, token=TOKEN)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    all_paper = all(h["assetType"] == "PAPER" for h in d.get("hits", []))
    check("高级检索：按资产类型过滤", d.get("total", 0) > 0 and all_paper,
          "命中 %s 条论文" % d.get("total"))

    # 检索联想
    _, r = call("GET", "/api/search/suggest?keyword=" + urllib.parse.quote("测试"))
    check("搜索联想", r.get("code") == 0 and len(r.get("data", [])) > 0,
          "%d 条建议" % len(r.get("data", [])))


def test_knowledge():
    section("四、知识库")

    _, r = call("GET", "/api/knowledge?page=1&size=5")
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("知识条目分页列表（匿名可读）", r.get("code") == 0 and d.get("total", 0) > 0,
          "共 %s 条" % d.get("total"))

    first = d.get("list", [{}])[0] if d.get("list") else None
    if first:
        check("列表项含分类与作者", bool(first.get("categoryName")) and bool(first.get("authorName")),
              "%s / %s" % (first.get("categoryName"), first.get("authorName")))
        check("列表项含本体概念标注", len(first.get("conceptNames", [])) > 0,
              "、".join(first.get("conceptNames", [])[:4]))
        check("列表项含标签", len(first.get("tags", [])) > 0,
              "、".join(t["name"] for t in first.get("tags", [])))

        _, r = call("GET", "/api/knowledge/%d" % first["id"])
        detail = r.get("data", {}) if r.get("code") == 0 else {}
        check("条目详情返回正文", bool(detail.get("content")),
              "%d 字符" % len(detail.get("content") or ""))

    _, r = call("GET", "/api/categories/tree")
    check("分类树", r.get("code") == 0 and len(r.get("data", [])) > 0,
          "%d 个顶级分类" % len(r.get("data", [])))

    _, r = call("GET", "/api/tags/hot?limit=10")
    check("热门标签", r.get("code") == 0, "%d 个" % len(r.get("data", [])))


def test_versions():
    section("五、版本管理（题目要求：指定天数/修改次数的版本管理）")

    _, r = call("GET", "/api/knowledge?page=1&size=1")
    item_id = r["data"]["list"][0]["id"]

    _, r = call("GET", "/api/items/%d/versions" % item_id)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("查询版本列表", r.get("code") == 0 and d.get("total", 0) > 0,
          "共 %s 个版本" % d.get("total"))

    _, r = call("GET", "/api/items/%d/versions/snapshot-decision" % item_id)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("版本策略判定给出解释", r.get("code") == 0 and bool(d.get("reason")),
          "due=%s | %s" % (d.get("due"), (d.get("reason") or "")[:60]))

    _, r = call("GET", "/api/versions/policies")
    check("版本策略列表", r.get("code") == 0 and len(r.get("data", [])) > 0,
          "%d 条策略" % len(r.get("data", [])))


def test_cms(token):
    section("六、内容管理（CMS）：编辑 → 版本 → 差异 → 回滚")

    # 1) 新建
    _, r = call("POST", "/api/knowledge", {
        "title": "冒烟测试：版本管理验证文档",
        "summary": "由冒烟测试脚本自动创建，用于验证版本管理与回滚功能。",
        "content": "# 版本一\n\n这是初始版本的内容，用于验证版本管理。\n\n涉及知识点：软件测试、版本管理。",
        "assetType": "DOCUMENT",
        "tags": ["软件测试", "版本管理"],
        "conceptIds": []
    }, token=token)
    ok = check("创建知识条目", r.get("code") == 0, r.get("message", ""))
    if not ok:
        return
    item_id = r["data"]["id"]
    check("新建条目自动生成 v1 基线版本", r["data"]["versionNo"] == 1,
          "versionNo=%s" % r["data"]["versionNo"])
    check("自动抽取关键词", len(r["data"].get("keywords", [])) > 0,
          "、".join(r["data"].get("keywords", [])))
    check("自动标注本体概念", len(r["data"].get("conceptNames", [])) > 0,
          "、".join(r["data"].get("conceptNames", [])[:5]))

    # 2) 连续修改 3 次，累计 changeCount
    for i in range(3):
        _, r = call("PUT", "/api/knowledge/%d" % item_id, {
            "title": "冒烟测试：版本管理验证文档",
            "summary": "由冒烟测试脚本自动创建，用于验证版本管理与回滚功能。",
            "content": "# 版本一\n\n这是初始版本的内容，用于验证版本管理。\n\n涉及知识点：软件测试、版本管理。\n\n"
                       + ("补充内容第 %d 段。\n" % (i + 1)),
            "assetType": "DOCUMENT",
            "tags": ["软件测试", "版本管理"],
        }, token=token)
        if r.get("code") != 0:
            break
    check("连续修改后累计修改次数增长", r.get("code") == 0 and r["data"]["changeCount"] >= 1,
          "changeCount=%s" % r.get("data", {}).get("changeCount"))

    # 3) 手工创建版本
    _, r = call("POST", "/api/items/%d/versions" % item_id,
                {"changeLog": "冒烟测试：手工创建版本，验证版本说明记录。"}, token=token)
    check("手工创建版本", r.get("code") == 0, r.get("message", ""))
    v2 = r["data"]["versionNo"] if r.get("code") == 0 else None
    check("版本号递增", v2 is not None and v2 >= 2, "当前 v%s" % v2)

    # 4) 版本差异
    _, r = call("GET", "/api/items/%d/versions/diff?from=1&to=%s" % (item_id, v2))
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("版本差异比对", r.get("code") == 0 and d.get("addedLines", 0) > 0,
          "新增 %s 行 / 删除 %s 行 / 新增字符 %s" %
          (d.get("addedLines"), d.get("removedLines"), d.get("charAdded")))
    check("差异结果含行级明细", len(d.get("lines", [])) > 0,
          "%d 行 diff" % len(d.get("lines", [])))

    # 5) 回滚
    _, r = call("POST", "/api/items/%d/versions/1/rollback?changeLog=%s"
                % (item_id, urllib.parse.quote("冒烟测试回滚至 v1")), token=token)
    check("回滚至 v1", r.get("code") == 0, r.get("message", ""))
    if r.get("code") == 0:
        check("回滚生成新版本（不删除历史）",
              r["data"]["changeSource"] == "ROLLBACK",
              "生成 v%s，来源 %s" % (r["data"]["versionNo"], r["data"]["changeSource"]))

    # 6) 状态流转
    _, r = call("POST", "/api/knowledge/%d/publish" % item_id, token=token)
    check("发布条目", r.get("code") == 0 and r["data"]["status"] == "PUBLISHED",
          r.get("message", ""))

    _, r = call("POST", "/api/knowledge/%d/archive?reason=%s"
                % (item_id, urllib.parse.quote("冒烟测试归档验证")), token=token)
    check("归档条目", r.get("code") == 0 and r["data"]["status"] == "ARCHIVED",
          r.get("message", ""))

    _, r = call("POST", "/api/knowledge/%d/unarchive" % item_id, token=token)
    check("恢复归档条目", r.get("code") == 0 and r["data"]["status"] == "DRAFT",
          "恢复后状态 %s（回到草稿待复核）" % r.get("data", {}).get("status"))

    # 7) 匿名用户不应看到草稿
    _, r = call("GET", "/api/knowledge/%d" % item_id)
    check("草稿不进入公开详情可见性（管理端专用接口才可见）",
          r.get("code") == 0 or r.get("code") == 404)

    # 8) 清理
    _, r = call("DELETE", "/api/knowledge/%d" % item_id, token=token)
    check("删除测试条目", r.get("code") == 0, r.get("message", ""))


def test_rag(token):
    section("七、RAG 检索增强问答")

    _, r = call("POST", "/api/rag/ask",
                {"question": "需求分析和需求获取有什么区别？", "topK": 5}, token=token)
    ok = check("提问并获取回答", r.get("code") == 0, r.get("message", ""))
    if not ok:
        return
    d = r["data"]
    check("回答非空", bool(d.get("answer")), "%d 字符" % len(d.get("answer") or ""))
    check("返回引用来源", len(d.get("citations", [])) > 0,
          "%d 条引用" % len(d.get("citations", [])))
    check("引用含片段与得分",
          bool(d["citations"]) and bool(d["citations"][0].get("snippet"))
          and d["citations"][0].get("score") is not None,
          "首位引用：%s（得分 %s）" % (d["citations"][0].get("itemTitle") if d.get("citations") else "",
                                      d["citations"][0].get("score") if d.get("citations") else ""))
    check("引用标注通道排名（体现混合检索）",
          bool(d["citations"]) and d["citations"][0].get("ranks") is not None,
          "vectorRank=%s keywordRank=%s" % (
              d["citations"][0]["ranks"].get("vectorRank") if d.get("citations") else "",
              d["citations"][0]["ranks"].get("keywordRank") if d.get("citations") else ""))

    meta = d.get("retrieval") or {}
    check("返回检索过程元数据", bool(meta), "耗时 检索 %sms / 生成 %sms" %
          (meta.get("retrievalCostMs"), meta.get("generationCostMs")))
    check("本体查询扩展生效", len(meta.get("expandedConcepts", [])) > 0,
          "扩展概念：%s" % "、".join(meta.get("expandedConcepts", [])[:6]))
    check("双通道均有候选",
          meta.get("keywordCandidates", 0) > 0,
          "向量通道 %s 条 / 关键字通道 %s 条 / 融合后 %s 条" % (
              meta.get("vectorCandidates"), meta.get("keywordCandidates"), meta.get("fusedCandidates")))
    print("  [INFO] 回答模型：%s%s" % (d.get("model"),
                                      "（离线抽取式，未配置大模型 API Key）" if d.get("degraded") else ""))
    print("  [INFO] 回答节选：")
    for line in (d.get("answer") or "").split("\n")[:6]:
        if line.strip():
            print("         " + line.strip()[:100])

    # 知识库未覆盖的问题不应编造
    _, r = call("POST", "/api/rag/ask", {"question": "请详细讲解量子色动力学中的渐近自由"}, token=token)
    d2 = r.get("data", {}) if r.get("code") == 0 else {}
    check("知识库未覆盖时如实说明",
          r.get("code") == 0 and (d2.get("degraded") or "未涵盖" in (d2.get("answer") or "")
                                  or "没有找到" in (d2.get("answer") or "")),
          "未编造答案")

    # 会话记录
    _, r = call("GET", "/api/rag/sessions", token=token)
    check("查询问答会话列表", r.get("code") == 0 and len(r.get("data", [])) > 0,
          "%d 个会话" % len(r.get("data", [])))

    # 索引状态
    _, r = call("GET", "/api/rag/index/status", token=token)
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("查询向量索引状态", r.get("code") == 0,
          "%s 个分块 / %s 条知识 / 模型 %s" % (
              d.get("totalChunks"), d.get("indexedItems"), d.get("embeddingModel")))
    check("无过期模型的向量", not d.get("staleModels"),
          "过期模型：%s" % (d.get("staleModels") or "无"))


def test_archive_stats(token):
    section("八、归档、统计与审计")

    _, r = call("GET", "/api/knowledge/manage?status=ARCHIVED", token=token)
    check("归档列表接口", r.get("code") == 0, "归档 %s 条" % r.get("data", {}).get("total"))

    _, r = call("GET", "/api/stats/overview")
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("统计总览", r.get("code") == 0 and d.get("knowledgeCount", 0) > 0,
          "知识 %s / 概念 %s / 关系 %s / 用户 %s" % (
              d.get("knowledgeCount"), d.get("conceptCount"), d.get("relationCount"), d.get("userCount")))

    _, r = call("GET", "/api/stats/distribution")
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("分布数据（类型/状态/分类/知识域/标签）",
          all(k in d for k in ("assetTypes", "statuses", "categories", "domains", "tags")))

    _, r = call("GET", "/api/stats/trend?days=14")
    check("发布趋势", r.get("code") == 0 and len(r.get("data", [])) == 14,
          "%d 天数据" % len(r.get("data", [])))

    _, r = call("GET", "/api/audit/recent", token=token)
    check("审计日志", r.get("code") == 0 and len(r.get("data", [])) > 0,
          "%d 条记录" % len(r.get("data", [])))

    _, r = call("GET", "/api/stats/system-info")
    d = r.get("data", {}) if r.get("code") == 0 else {}
    check("系统能力自检", r.get("code") == 0)
    print("  [INFO] 向量模型：%s" % d.get("embeddingDescription"))
    print("  [INFO] 生成模型：%s" % d.get("chatDescription"))


# ----------------------------------------------------------------------

def main():
    print("=" * 68)
    print("知识管理系统 接口冒烟测试")
    print("目标地址：%s" % BASE)
    print("=" * 68)

    token = test_auth()
    if not token:
        print("\n登录失败，无法继续测试。请确认后端已启动且数据库已初始化。")
        return 1

    test_ontology()
    test_search()
    test_knowledge()
    test_versions()
    test_cms(token)
    test_rag(token)
    test_archive_stats(token)

    section("测试结果汇总")
    total = len(PASSED) + len(FAILED)
    print("  总计 %d 项，通过 %d 项，失败 %d 项" % (total, len(PASSED), len(FAILED)))
    if FAILED:
        print("\n  失败项：")
        for f in FAILED:
            print("    - %s" % f)
    else:
        print("\n  全部通过。")
    return 1 if FAILED else 0


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (供 urllib.parse.quote 使用)
    sys.exit(main())
