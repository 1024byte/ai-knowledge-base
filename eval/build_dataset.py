"""
测试集构建工具

功能：
  1. 从知识库 API 拉取文档列表，了解知识库内容范围
  2. 生成测试集模板，供人工标注
  3. 可选：用 LLM 根据文档名自动生成候选问题

使用方式：
  python build_dataset.py              # 拉取文档列表，生成模板
  python build_dataset.py --auto       # 拉取文档列表 + LLM 自动生成候选问题
"""

import json
import os
import sys
import argparse
from typing import List, Dict

import requests

# 允许从上级目录导入 config
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config


def fetch_documents() -> List[Dict]:
    """从知识库 API 获取文档列表"""
    url = f"{config.api_base_url}/api/documents/list"
    try:
        resp = requests.get(url, timeout=10)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") == 0:
            return body["data"]
        else:
            print(f"API 返回错误: {body.get('message')}")
            return []
    except requests.exceptions.ConnectionError:
        print(f"无法连接到 {url}，请确保 Java 应用已启动")
        return []
    except Exception as e:
        print(f"获取文档列表失败: {e}")
        return []


def generate_template(documents: List[Dict]) -> List[Dict]:
    """根据文档列表生成测试集模板"""
    template = []
    for doc in documents:
        filename = doc.get("filename", "unknown")
        template.append({
            "id": f"q{len(template) + 1:03d}",
            "query": f"【待填写：针对《{filename}》的问题】",
            "relevant_docs": [filename],
            "difficulty": "medium",
            "category": "【待填写】",
            "expected_answer": "【待填写：预期答案要点】"
        })
    return template


def auto_generate_questions(documents: List[Dict]) -> List[Dict]:
    """使用 LLM 根据文档名自动生成候选问题（需要 DEEPSEEK_API_KEY）"""
    api_key = config.llm_api_key
    if not api_key:
        print("未设置 DEEPSEEK_API_KEY 环境变量，无法自动生成问题，改为生成模板")
        return generate_template(documents)

    filenames = [doc.get("filename", "unknown") for doc in documents]
    prompt = f"""你是一个测试集构建助手。知识库中有以下文档：

{chr(10).join(f'- {f}' for f in filenames)}

请为每个文档生成 2~3 个用户可能提问的问题。问题要覆盖不同类型：
- 事实查询（如"XX是什么"、"XX的定义"）
- 流程查询（如"XX的流程是什么"、"怎么操作XX"）
- 条件查询（如"什么情况下可以XX"、"满足什么条件才能XX"）
- 比较查询（如"XX和YY有什么区别"）
- 数值查询（如"XX有多少天"、"XX的金额是多少"）
- 时间查询（如"XX的截止日期是什么"、"什么时候可以XX"）
- 人员查询（如"XX由谁负责"、"找谁审批XX"）
- 规则查询（如"XX的规定是什么"、"XX有什么限制"）
- 多跳查询（如"新员工入职后如何申请XX"，需要结合多个文档）
- 内容查询（如"XX的详细信息"、"XX的背景"等）

输出 JSON 数组，格式如下：
[
  {{
    "id": "q001",
    "query": "用户问题",
    "relevant_docs": ["文档名1"],
    "difficulty": "easy|medium|hard",
    "category": "事实查询|流程查询|..."
  }}
]

只输出 JSON，不要其他内容。"""

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": config.llm_model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.7
    }

    try:
        resp = requests.post(f"{config.llm_api_base}/v1/chat/completions",
                             headers=headers, json=payload, timeout=60)
        resp.raise_for_status()
        content = resp.json()["choices"][0]["message"]["content"]
        # 提取 JSON 部分
        content = content.strip()
        if content.startswith("```"):
            content = content.split("\n", 1)[1]
            if content.endswith("```"):
                content = content[:-3]
        return json.loads(content)
    except Exception as e:
        print(f"LLM 生成失败: {e}，改为生成模板")
        return generate_template(documents)


def save_test_queries(test_queries: List[Dict], output_path: str):
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(test_queries, f, ensure_ascii=False, indent=2)
    print(f"测试集已保存至: {output_path}")
    print(f"共 {len(test_queries)} 条候选问题")


def main():
    parser = argparse.ArgumentParser(description="构建 RAG 评估测试集")
    parser.add_argument("--auto", action="store_true", help="使用 LLM 自动生成候选问题")
    parser.add_argument("--output", default=config.test_queries_path, help="输出路径")
    args = parser.parse_args()

    print("=" * 50)
    print("RAG 测试集构建工具")
    print("=" * 50)

    # 1. 拉取文档列表
    print("\n[1/3] 拉取知识库文档列表...")
    documents = fetch_documents()
    if not documents:
        print("未获取到文档，请先上传文档到知识库")
        return

    print(f"共 {len(documents)} 个文档:")
    for doc in documents:
        print(f"  - {doc.get('filename')} ({doc.get('fileType', '?')}, {doc.get('chunkCount', 0)} chunks)")

    # 2. 生成候选问题
    print(f"\n[2/3] 生成候选问题...")
    if args.auto:
        test_queries = auto_generate_questions(documents)
    else:
        test_queries = generate_template(documents)

    # 3. 保存
    print(f"\n[3/3] 保存测试集...")
    save_test_queries(test_queries, args.output)

    print("\n" + "=" * 50)
    print("下一步:")
    print("  1. 打开 data/test_queries.json")
    print("  2. 人工审核每个问题，填写 relevant_docs 和 expected_answer")
    print("  3. 删除不合适的问题")
    print("  4. 运行 validate_dataset.py 校验格式")
    print("=" * 50)


if __name__ == "__main__":
    main()