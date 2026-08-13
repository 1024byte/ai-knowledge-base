"""
生成质量评估脚本（RAGAS）

调用 POST /api/chat/ask 获取完整回答，结合检索上下文，
使用 RAGAS 框架计算 4 个核心指标：

  - Faithfulness（忠实度）：      回答是否完全基于上下文，有无编造
  - Answer Relevance（答案相关性）：回答是否切题
  - Context Precision（上下文精度）：上下文中无关内容比例
  - Context Recall（上下文召回）：  回答所需信息是否都在上下文中

使用方式:
  python generation_eval.py
  python generation_eval.py --file data/test_queries.json --output reports/generation_report.json
"""

import json
import os
import sys
import argparse
import time
import ssl
from typing import List, Dict

ssl._create_default_https_context = ssl._create_unverified_context
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config


def clean_ground_truth(text: str) -> str:
    """
    清理 expected_answer 格式，提取纯答案文本。
    处理 "答案：xxx；原文依据：xxx"、 "文档中提到了xxx" 等元描述格式。
    """
    if not text:
        return text
    # 移除 "答案：" 前缀
    if text.startswith("答案："):
        text = text[3:]
    elif text.startswith("答案:"):
        text = text[3:]
    # 移除 "原文依据：xxx" 部分
    import re
    text = re.sub(r'[；;]\s*原文依据：.+$', '', text)
    text = re.sub(r'[；;]\s*原文依据:.+$', '', text)
    # 移除常见的元描述前缀
    text = re.sub(r'^文档中提到了[：:]?\s*', '', text)
    text = re.sub(r'^文档中[：:]?\s*', '', text)
    text = re.sub(r'^根据文档[，,]?\s*', '', text)
    return text.strip()


def load_test_queries(file_path: str) -> List[Dict]:
    with open(file_path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_cached_samples(report_path: str) -> List[Dict]:
    """从已有的 generation_report.json 中加载样本，跳过收集步骤"""
    with open(report_path, "r", encoding="utf-8") as f:
        report = json.load(f)
    samples = report.get("samples", [])
    if not samples:
        print("缓存文件中没有样本数据")
        return []
    print(f"从缓存加载 {len(samples)} 条样本")
    return samples


def call_chat_api(query: str, session_id: str = "eval-session") -> Dict:
    """调用对话 API 获取完整回答"""
    payload = {
        "question": query,
        "sessionId": session_id,
        "topK": config.top_k
    }
    resp = requests.post(config.chat_url, json=payload, timeout=120)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"Chat API 返回错误: {body.get('message')}")
    return body["data"]


def call_retrieve_api(query: str) -> Dict:
    """调用评估 API 获取检索上下文"""
    payload = {"query": query, "topK": config.top_k}
    resp = requests.post(config.eval_retrieve_url, json=payload, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"Eval API 返回错误: {body.get('message')}")
    return body["data"]


def collect_samples(test_queries: List[Dict]) -> List[Dict]:
    """
    收集评估样本：对每个问题，调用 chat API 获取回答，调用 eval API 获取上下文
    """
    samples = []
    total = len(test_queries)

    for i, q in enumerate(test_queries):
        query_id = q.get("id", f"q{i}")
        query_text = q.get("query", "")
        expected_answer = q.get("expected_answer", "")

        if not query_text:
            print(f"[{i + 1}/{total}] 跳过 {query_id}: query 为空")
            continue

        print(f"[{i + 1}/{total}] 收集: {query_text[:60]}...")

        try:
            # 获取回答
            chat_response = call_chat_api(query_text, f"eval-{query_id}")
            answer = chat_response.get("answer", "")
            processing_time = chat_response.get("processingTimeMs", 0)

            # 获取检索上下文
            eval_response = call_retrieve_api(query_text)
            final_ranked = eval_response.get("finalRanked", [])
            contexts = [doc.get("text", "") for doc in final_ranked]

            samples.append({
                "query_id": query_id,
                "question": query_text,
                "answer": answer,
                "contexts": contexts,
                "ground_truth": expected_answer,
                "difficulty": q.get("difficulty", "unknown"),
                "category": q.get("category", "unknown"),
                "processing_time_ms": processing_time,
                "context_count": len(contexts)
            })

            time.sleep(0.3)  # 避免请求过快

        except Exception as e:
            print(f"  错误: {e}")

    return samples


def evaluate_with_ragas(samples: List[Dict]) -> Dict:
    """
    使用 RAGAS 评估生成质量

    需要设置环境变量 DEEPSEEK_API_KEY
    """
    api_key = config.llm_api_key
    if not api_key:
        print("=" * 60)
        print("未设置 DEEPSEEK_API_KEY 环境变量")
        print("RAGAS 评估需要 LLM 作为评判器，请设置环境变量后重试:")
        print("  set DEEPSEEK_API_KEY=sk-xxxx")
        print("=" * 60)
        return {"error": "DEEPSEEK_API_KEY not set", "samples": samples}

    try:
        from ragas import evaluate
        from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision, ContextRecall
        from ragas.llms import LangchainLLMWrapper
        from ragas.embeddings import LangchainEmbeddingsWrapper
        from langchain_openai import ChatOpenAI
        from datasets import Dataset
    except ImportError as e:
        print(f"缺少依赖: {e}")
        print("请运行: pip install ragas langchain-openai datasets")
        return {"error": str(e), "samples": samples}

    print("\n初始化 RAGAS 评判 LLM (DeepSeek)...")
    judge_llm = LangchainLLMWrapper(ChatOpenAI(
        model=config.llm_model,
        openai_api_key=api_key,
        openai_api_base=config.llm_api_base,
        temperature=0,
        n=1
    ))

    # RAGAS 内部需要 Embedding 模型（用于 AnswerRelevancy 等指标）
    # DeepSeek 不支持 Embedding API，使用本地轻量模型
    try:
        from langchain_huggingface import HuggingFaceEmbeddings
        judge_embedding = LangchainEmbeddingsWrapper(HuggingFaceEmbeddings(
            model_name="sentence-transformers/all-MiniLM-L6-v2",
            model_kwargs={"device": "cpu"}
        ))
        print("使用本地 Embedding 模型: all-MiniLM-L6-v2")
    except ImportError:
        print("langchain-huggingface 未安装，使用备选方案")
        print("请运行: pip install langchain-huggingface sentence-transformers")
        judge_embedding = None
    except Exception as e:
        print(f"Embedding 模型加载失败: {e}")
        print("将跳过需要 Embedding 的指标")
        judge_embedding = None

    # 构建 RAGAS 数据集
    eval_data = {
        "question": [],
        "answer": [],
        "contexts": []
    }
    # ground_truth 是可选的，用于 Context Recall
    has_ground_truth = any(s.get("ground_truth") for s in samples)
    if has_ground_truth:
        eval_data["ground_truth"] = []

    for s in samples:
        eval_data["question"].append(s["question"])
        eval_data["answer"].append(s["answer"])
        eval_data["contexts"].append(s["contexts"])
        if has_ground_truth:
            eval_data["ground_truth"].append(clean_ground_truth(s.get("ground_truth", "")))

    dataset = Dataset.from_dict(eval_data)

    # 选择指标
    # AnswerRelevancy 需要 n>1 生成，DeepSeek 不支持
    # 如果配置了 DashScope API Key，用通义千问跑 AnswerRelevancy
    metrics = [Faithfulness(), ContextPrecision()]
    if has_ground_truth:
        metrics.append(ContextRecall())

    if config.ar_api_key:
        try:
            ar_llm = LangchainLLMWrapper(ChatOpenAI(
                model=config.ar_model,
                openai_api_key=config.ar_api_key,
                openai_api_base=config.ar_api_base,
                temperature=0
            ))
            ar_metric = AnswerRelevancy()
            ar_metric.llm = ar_llm
            if judge_embedding:
                ar_metric.embeddings = judge_embedding
            metrics.append(ar_metric)
            print(f"AnswerRelevancy 使用 {config.ar_model} (DashScope)")
        except Exception as e:
            print(f"AnswerRelevancy 初始化失败: {e}，跳过该指标")
    else:
        print("(AnswerRelevancy 已跳过：DeepSeek 不支持 n>1)")
        print("  如需启用，设置环境变量: set DASHSCOPE_API_KEY=sk-xxxx")

    print(f"评估指标: {[m.__class__.__name__ for m in metrics]}")

    print(f"开始 RAGAS 评估，共 {len(samples)} 条样本...")
    if judge_embedding:
        result = evaluate(dataset, metrics=metrics, llm=judge_llm, embeddings=judge_embedding)
    else:
        result = evaluate(dataset, metrics=metrics, llm=judge_llm)
    print("评估完成")

    # 转换为可序列化格式
    result_dict = {}
    # EvaluationResult 对象转 dict
    result_df = result.to_pandas()
    for col in result_df.columns:
        result_dict[col] = result_df[col].tolist()

    return {
        "ragas_scores": result_dict,
        "samples": samples
    }


def print_report(report: Dict):
    ragas = report.get("ragas_scores", {})
    samples = report.get("samples", [])

    print("\n" + "=" * 60)
    print("生成质量评估报告 (RAGAS)")
    print("=" * 60)

    if "error" in report:
        print(f"\n错误: {report['error']}")
        if samples:
            print(f"\n已收集 {len(samples)} 条样本（未评估），可手动检查")
        return

    print(f"\n评估样本数: {len(samples)}")

    print(f"\n{'指标':<25} {'分数':>10} {'目标':>10}")
    print("-" * 48)
    metric_labels = {
        "faithfulness": ("Faithfulness（忠实度）", "> 0.80"),
        "answer_relevancy": ("Answer Relevance（答案相关性）", "> 0.80"),
        "context_precision": ("Context Precision（上下文精度）", "> 0.80"),
        "context_recall": ("Context Recall（上下文召回）", "> 0.80"),
    }
    for key, (label, target) in metric_labels.items():
        if key in ragas:
            val = ragas[key]
            if isinstance(val, list):
                avg = sum(v for v in val if v is not None) / max(1, len([v for v in val if v is not None]))
                print(f"{label:<25} {avg:>10.4f} {target:>10}")
            else:
                print(f"{label:<25} {val:>10.4f} {target:>10}")

    # 性能统计
    times = [s.get("processing_time_ms", 0) for s in samples if s.get("processing_time_ms")]
    if times:
        avg_time = sum(times) / len(times)
        print(f"\n平均处理时间: {avg_time:.0f}ms")

    # 答案长度统计
    lens = [len(s.get("answer", "")) for s in samples]
    if lens:
        avg_len = sum(lens) / len(lens)
        print(f"平均回答长度: {avg_len:.0f} 字符")


def main():
    parser = argparse.ArgumentParser(description="RAG 生成质量评估 (RAGAS)")
    parser.add_argument("--file", default=config.test_queries_path, help="测试集路径")
    parser.add_argument("--output", default=os.path.join(config.reports_dir, "generation_report.json"),
                        help="报告输出路径")
    parser.add_argument("--skip-ragas", action="store_true", help="跳过 RAGAS 评估，仅收集样本")
    parser.add_argument("--from-cache", action="store_true",
                        help="从已有 generation_report.json 加载样本，跳过收集，直接跑 RAGAS")
    args = parser.parse_args()

    # 从缓存加载模式
    if args.from_cache:
        cache_path = os.path.join(config.reports_dir, "generation_cache.json")
        if not os.path.exists(cache_path):
            print(f"缓存文件不存在: {cache_path}")
            print("请先运行 python generation_eval.py 收集样本")
            return

        samples = load_cached_samples(cache_path)
        if not samples:
            return

        report = evaluate_with_ragas(samples)
        print_report(report)

        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        report_slim = report.copy()
        if "samples" in report_slim:
            report_slim["samples"] = [
                {k: v for k, v in s.items() if k != "contexts"}
                for s in report_slim["samples"]
            ]
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report_slim, f, ensure_ascii=False, indent=2)
        print(f"\n报告已保存至: {args.output}")
        return

    if not os.path.exists(args.file):
        print(f"测试集不存在: {args.file}")
        return

    print("=" * 60)
    print("RAG 生成质量评估")
    print(f"Chat API: {config.chat_url}")
    print(f"Eval API: {config.eval_retrieve_url}")
    print(f"测试集: {args.file}")
    print("=" * 60)

    test_queries = load_test_queries(args.file)
    print(f"\n加载 {len(test_queries)} 条测试问题")

    # 收集样本
    samples = collect_samples(test_queries)
    print(f"\n收集完成: {len(samples)} 条有效样本")

    if not samples:
        print("无有效样本，退出")
        return

    # RAGAS 评估
    if args.skip_ragas:
        report = {"samples": samples, "note": "RAGAS evaluation skipped"}
    else:
        report = evaluate_with_ragas(samples)

    print_report(report)

    # 保存报告
    os.makedirs(os.path.dirname(args.output), exist_ok=True)

    # 完整版（含 contexts，供 --from-cache 使用）
    cache_path = os.path.join(os.path.dirname(args.output), "generation_cache.json")
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    # 精简版（去掉 contexts，供查看）
    report_slim = report.copy()
    if "samples" in report_slim:
        report_slim["samples"] = [
            {k: v for k, v in s.items() if k != "contexts"}
            for s in report_slim["samples"]
        ]
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report_slim, f, ensure_ascii=False, indent=2)
    print(f"\n报告已保存至: {args.output}")
    print(f"缓存已保存至: {cache_path}")


if __name__ == "__main__":
    main()