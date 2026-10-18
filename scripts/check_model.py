# -*- coding: utf-8 -*-
"""
检查 RAG 生成模型与向量模型的配置是否可用。

用途：答辩前或部署后自检。当智能问答返回"离线抽取式回答"时，
本脚本能直接指出是**没配 API Key**、**Key 无效**、还是**接口返回格式不符合预期**。

用法：
    python scripts/check_model.py
"""

import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8080'


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as resp:
        return json.loads(resp.read().decode('utf-8'))


def post(path, body, token=None, timeout=180):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method='POST')
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8'))


def login(username='admin', password='admin123'):
    """问答接口需要登录，这里用演示管理员账号换取令牌。"""
    return post('/api/auth/login', {'username': username, 'password': password})['data']['token']


def main():
    print('=' * 66)
    print('知识管理系统 · 模型配置自检')
    print('目标：%s' % BASE)
    print('=' * 66)

    try:
        info = get('/api/stats/system-info')['data']
    except Exception as e:
        print('\n无法连接后端：%s' % e)
        print('请先启动后端（scripts/start-backend.sh）')
        return 1

    print('\n【生成模型】')
    print('  模型      : %s' % info.get('chatModel'))
    print('  说明      : %s' % info.get('chatDescription'))
    print('  可用      : %s' % ('是' if info.get('chatAvailable') else '否'))

    print('\n【向量模型】')
    print('  模型      : %s' % info.get('embeddingModel'))
    print('  说明      : %s' % info.get('embeddingDescription'))
    print('  在线      : %s' % ('是' if info.get('embeddingOnline') else '否（本地哈希向量）'))

    if not info.get('chatAvailable'):
        print('\n诊断：生成模型不可用，问答会走离线抽取式回答。')
        print('      请检查 application-local.yml 中 kms.rag.chat.openai.api-key 是否已填写。')
        return 1

    # 真正发一次请求：只有实际调用才能暴露"Key 有效但接口返回格式不对"这类问题
    print('\n【实际调用测试】')
    try:
        token = login()
    except Exception as e:
        print('  登录失败：%s' % e)
        print('  请确认数据库已初始化（scripts/init-db.sh），演示账号为 admin/admin123')
        return 1

    print('  正在提问……', end='', flush=True)
    try:
        r = post('/api/rag/ask', {'question': '需求分析和需求获取有什么区别？', 'topK': 6}, token)
        d = r['data']
    except Exception as e:
        print(' 失败')
        print('  请求异常：%s' % e)
        return 1
    print(' 完成')

    print('  回答模型  : %s' % d.get('model'))
    print('  是否降级  : %s' % ('是 —— 说明调用失败，已回退到离线抽取式' if d.get('degraded') else '否'))
    print('  引用条数  : %d' % len(d.get('citations') or []))
    print('  总耗时    : %d ms' % (d.get('latencyMs') or 0))
    if d.get('notice'):
        print('  提示      : %s' % d['notice'])

    answer = (d.get('answer') or '').strip()
    print('\n  回答节选：')
    for line in answer.split('\n')[:5]:
        if line.strip():
            print('    ' + line.strip()[:90])

    print('\n' + '=' * 66)
    if d.get('degraded'):
        print('结论：生成模型配置存在但调用失败，问答正在使用离线抽取式回答。')
        print('      请查看后端日志中 "在线生成模型调用失败" 一行以获取具体原因。')
        return 1
    print('结论：生成模型工作正常，问答由大模型归纳作答。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
