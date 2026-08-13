"""
测试集构建工具

功能：
  1. 从知识库 API 拉取文档列表
  2. --rag 模式：通过检索 API 获取每个文档的真实内容片段，用 LLM 自动生成问题 + 答案
  3. --auto 模式：仅根据文档名生成问题（不含答案，需人工标注）
  4. 默认模式：生成模板，供人工填写

使用方式：
  python build_dataset.py --rag          # 推荐：基于真实文档内容 + LLM 自动生成完整测试集
  python build_dataset.py --auto         # 仅根据文档名生成问题，需人工补充答案
  python build_dataset.py                # 生成模板，需人工填写
"""

import json
import os
import sys
import argparse
import re
from typing import List, Dict

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config


# ===================== 文档 API =====================

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


def fetch_document_chunks(doc_name: str, top_k: int = 5) -> List[str]:
    """
    通过检索 API 获取文档的内容片段。
    用文档名作为查询词，检索出该文档的 chunk。
    """
    url = f"{config.api_base_url}/api/eval/retrieve"
    payload = {
        "query": doc_name,
        "topK": top_k,
        "mode": "no_rewrite"  # 不改写，直接用文档名搜
    }
    try:
        resp = requests.post(url, json=payload, timeout=30)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") == 0:
            data = body["data"]
            # 从 hybrid/results 中提取文本
            hybrid = data.get("hybrid", {})
            results = hybrid.get("results", [])
            chunks = []
            for r in results:
                metadata = r.get("metadata", {})
                text = metadata.get("text", "")
                doc = metadata.get("document", "")
                # 只保留当前文档的 chunk
                if doc_name in doc or doc in doc_name:
                    chunks.append(text)
                elif not chunks:  # 前几个可能匹配不到，放宽条件
                    chunks.append(text)
            return chunks[:top_k]
        return []
    except Exception as e:
        print(f"  获取文档内容失败: {e}")
        return []


# ===================== LLM 调用 =====================

def call_llm(prompt: str, temperature: float = 0.7) -> str:
    """调用 DeepSeek LLM"""
    headers = {
        "Authorization": f"Bearer {config.llm_api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": config.llm_model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature
    }
    resp = requests.post(
        f"{config.llm_api_base}/v1/chat/completions",
        headers=headers, json=payload, timeout=120
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def extract_json(text: str) -> str:
    """从 LLM 输出中提取 JSON 部分"""
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r'^```\w*\n?', '', text)
        text = re.sub(r'\n?```$', '', text)
    return text


# ===================== 生成策略 =====================

def generate_template(documents: List[Dict]) -> List[Dict]:
    """根据文档列表生成测试集模板（人工填写）"""
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
    """使用 LLM 根据文档名生成问题（不含答案）"""
    api_key = config.llm_api_key
    if not api_key:
        print("未找到 API Key，改为生成模板")
        return generate_template(documents)

    filenames = [doc.get("filename", "unknown") for doc in documents]
    prompt = f"""你是一个测试集构建助手。知识库中有以下文档：

{chr(10).join(f'- {f}' for f in filenames)}

请为每个文档生成 2~3 个用户可能提问的问题。问题要覆盖不同类型：
- 事实查询、流程查询、条件查询、比较查询、数值查询、时间查询、规则查询、内容查询

输出 JSON 数组，格式如下：
[
  {{
    "id": "q001",
    "query": "用户问题",
    "relevant_docs": ["文档名1"],
    "difficulty": "easy|medium|hard",
    "category": "事实查询"
  }}
]

只输出 JSON，不要其他内容。"""

    try:
        content = call_llm(prompt)
        return json.loads(extract_json(content))
    except Exception as e:
        print(f"LLM 生成失败: {e}，改为生成模板")
        return generate_template(documents)


def rag_generate_questions(documents: List[Dict], questions_per_doc: int = 5) -> List[Dict]:
    """
    基于真实文档内容生成问题 + 答案。
    流程：
      1. 对每个文档，通过检索 API 获取 chunk
      2. 将 chunk 内容喂给 LLM，生成问题 + 答案 + 难度 + 分类
    """
    api_key = config.llm_api_key
    if not api_key:
        print("未找到 API Key，无法生成")
        return []

    all_questions = []
    q_idx = 1

    for doc in documents:
        filename = doc.get("filename", "unknown")
        print(f"\n处理文档: {filename}")

        # 获取文档内容
        print(f"  获取文档内容片段...")
        chunks = fetch_document_chunks(filename, top_k=questions_per_doc)
        if not chunks:
            print(f"  ⚠️ 未获取到内容，跳过")
            continue

        # 合并 chunk（限制长度，避免超 token）
        chunk_text = "\n\n---\n\n".join(chunks)
        if len(chunk_text) > 8000:
            chunk_text = chunk_text[:8000] + "\n\n...(内容截断)"

        print(f"  获取到 {len(chunks)} 个片段，共 {len(chunk_text)} 字符")

        # 让 LLM 生成问题
        prompt = f"""你是一个 RAG 测试集构建助手。以下是文档《{filename}》的部分内容：

{chunk_text}

请根据上述内容，生成 3~5 个用户可能提问的问题。要求：
1. 问题必须基于文档内容，答案必须能在文档中找到
2. 覆盖不同难度：easy（简单事实查询）、medium（需要理解）、hard（需要推理或跨段落）
3. 覆盖不同类别：事实查询、流程查询、条件查询、数值查询、时间查询、规则查询、内容查询、比较查询
4. expected_answer 是直接答案，用自然语言描述，像是用户在问"答案是什么"时你会给出的回答
5. **禁止**在 expected_answer 中使用"答案："、"原文依据："、"文档中提到了"、"根据文档"等元描述前缀
6. **禁止**在 expected_answer 中引用出处或原文，只写答案本身

输出 JSON 数组，格式如下：
[
  {{
    "id": "q{filename}_01",
    "query": "用户问题",
    "relevant_docs": ["{filename}"],
    "difficulty": "easy",
    "category": "事实查询",
    "expected_answer": "现在时、过去时、将来时、现在完成时、过去完成时、将来完成时"
  }}
]

只输出 JSON，不要其他内容。"""

        try:
            llm_response = call_llm(prompt, temperature=0.8)
            questions = json.loads(extract_json(llm_response))

            # 重新编号
            for q in questions:
                q["id"] = f"q{q_idx:03d}"
                q_idx += 1

            all_questions.extend(questions)
            print(f"  ✅ 生成了 {len(questions)} 个问题")

        except Exception as e:
            print(f"  ❌ 生成失败: {e}")

    return all_questions


# ===================== 保存 =====================

def save_test_queries(test_queries: List[Dict], output_path: str):
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(test_queries, f, ensure_ascii=False, indent=2)
    print(f"\n测试集已保存至: {output_path}")
    print(f"共 {len(test_queries)} 条问题")


# ===================== 主流程 =====================

def main():
    parser = argparse.ArgumentParser(description="构建 RAG 评估测试集")
    parser.add_argument("--rag", action="store_true",
                        help="基于真实文档内容 + LLM 自动生成完整测试集（推荐）")
    parser.add_argument("--auto", action="store_true",
                        help="仅根据文档名生成问题，不含答案")
    parser.add_argument("--output", default=config.test_queries_path, help="输出路径")
    parser.add_argument("--questions-per-doc", type=int, default=5,
                        help="每个文档生成的问题数（仅 --rag 模式）")
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

    # 2. 生成问题
    print(f"\n[2/3] 生成候选问题...")
    if args.rag:
        print("模式: 基于真实文档内容 + LLM 自动生成")
        test_queries = rag_generate_questions(documents, args.questions_per_doc)
    elif args.auto:
        print("模式: 仅根据文档名生成（需人工补充答案）")
        test_queries = auto_generate_questions(documents)
    else:
        print("模式: 生成模板（需人工填写）")
        test_queries = generate_template(documents)

    if not test_queries:
        print("未生成任何问题")
        return

    # 3. 保存
    print(f"\n[3/3] 保存测试集...")
    save_test_queries(test_queries, args.output)

    print("\n" + "=" * 50)
    print("统计:")
    docs_covered = set(q["relevant_docs"][0] for q in test_queries if q.get("relevant_docs"))
    print(f"  覆盖文档: {len(docs_covered)}/{len(documents)}")
    if args.rag:
        print(f"  已含答案: ✅")
    else:
        print(f"  已含答案: ❌ (需人工填写)")
    print("=" * 50)


if __name__ == "__main__":
    main()