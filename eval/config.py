"""
RAG 系统评估配置模块

API Key 读取优先级:
  1. 环境变量 DEEPSEEK_API_KEY
  2. application-dev.yml 中的 deepseek.api-key

使用方式:
    from config import config
    print(config.api_base_url)
"""

import os
import re
from dataclasses import dataclass, field
from typing import List


def _read_api_key_from_yml() -> str:
    """从 application-dev.yml 中读取 deepseek.api-key"""
    yml_paths = [
        os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "application-dev.yml"),
        os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "application.yml"),
    ]
    for yml_path in yml_paths:
        abs_path = os.path.normpath(yml_path)
        if os.path.exists(abs_path):
            with open(abs_path, "r", encoding="utf-8") as f:
                content = f.read()
            match = re.search(r"api-key:\s*(\S+)", content)
            if match:
                return match.group(1).strip()
    return ""


def _read_dashscope_key_from_yml() -> str:
    """从 application-dev.yml 中读取 dashscope.api-key"""
    yml_paths = [
        os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "application-dev.yml"),
        os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "application.yml"),
    ]
    for yml_path in yml_paths:
        abs_path = os.path.normpath(yml_path)
        if os.path.exists(abs_path):
            with open(abs_path, "r", encoding="utf-8") as f:
                content = f.read()
            match = re.search(r"dashscope:\s*\r?\n\s*api-key:\s*(\S+)", content)
            if match:
                return match.group(1).strip()
    return ""


def _get_api_key() -> str:
    """获取 API Key，优先环境变量，其次 yml 文件"""
    key = os.getenv("DEEPSEEK_API_KEY", "")
    if key:
        return key
    return _read_api_key_from_yml()


def _get_dashscope_key() -> str:
    """获取 DashScope API Key，优先环境变量，其次 yml 文件"""
    key = os.getenv("DASHSCOPE_API_KEY", "")
    if key:
        return key
    return _read_dashscope_key_from_yml()


@dataclass
class EvalConfig:
    # ========== Java API 地址 ==========
    api_base_url: str = os.getenv("EVAL_API_BASE", "http://localhost:8080")

    # 评估专用检索接口
    eval_retrieve_url: str = field(default="")
    # 对话接口（用于生成评估）
    chat_url: str = field(default="")

    def __post_init__(self):
        if not self.eval_retrieve_url:
            self.eval_retrieve_url = f"{self.api_base_url}/api/eval/retrieve"
        if not self.chat_url:
            self.chat_url = f"{self.api_base_url}/api/chat/ask"

    # ========== 检索评估参数 ==========
    top_k: int = 10                     # 检索返回数量
    recall_k_values: List[int] = field(default_factory=lambda: [3, 5, 10])

    # ========== 生成评估参数 ==========
    llm_model: str = "deepseek-chat"    # RAGAS 使用的评判 LLM
    llm_api_key: str = field(default_factory=_get_api_key)
    llm_api_base: str = os.getenv("DEEPSEEK_API_BASE", "https://api.deepseek.com")

    # AnswerRelevancy 专用 LLM（需要支持 n>1，DeepSeek 不支持）
    # 阿里云 DashScope 兼容 OpenAI 格式，通义千问支持 n>1
    ar_api_key: str = field(default_factory=_get_dashscope_key)
    ar_api_base: str = os.getenv("DASHSCOPE_API_BASE", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    ar_model: str = os.getenv("DASHSCOPE_MODEL", "qwen-plus")

    # ========== 测试集路径 ==========
    data_dir: str = "data"
    test_queries_file: str = "test_queries.json"
    reports_dir: str = "reports"

    @property
    def test_queries_path(self) -> str:
        return os.path.join(self.data_dir, self.test_queries_file)


# 全局配置实例
config = EvalConfig()