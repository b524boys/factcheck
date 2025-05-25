#!/usr/bin/env python
import os
import sys
import time
import json
import argparse
import requests
from urllib.parse import urljoin
import logging
from langchain_google_community import GoogleSearchAPIWrapper
from langchain_core.tools import Tool
from tqdm import tqdm
import concurrent.futures
import traceback
import http.client
import ssl
import random
import signal
import atexit

from search_query_filter import filter_search_queries

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("client.log"),
        logging.StreamHandler()
    ]
)

logger = logging.getLogger("fact_check_client")


class CircuitBreaker:
    """
    实现简单的断路器模式，用于防止持续请求失败的情况
    """

    def __init__(self, failure_threshold=5, recovery_timeout=60):
        self.failure_threshold = failure_threshold  # 失败次数阈值
        self.recovery_timeout = recovery_timeout  # 恢复超时时间(秒)
        self.failure_count = 0  # 当前失败计数
        self.last_failure_time = 0  # 上次失败时间
        self.state = "CLOSED"  # 断路器状态: CLOSED, OPEN, HALF-OPEN

    def record_success(self):
        """记录成功请求"""
        self.failure_count = 0
        self.state = "CLOSED"

    def record_failure(self):
        """记录失败请求"""
        self.failure_count += 1
        self.last_failure_time = time.time()

        if self.failure_count >= self.failure_threshold:
            self.state = "OPEN"

    def can_execute(self):
        """
        检查是否可以执行请求

        Returns:
            bool: 如果断路器允许执行请求则为True, 否则为False
        """
        now = time.time()

        # 断路器打开状态
        if self.state == "OPEN":
            # 检查是否超过恢复超时时间
            if now - self.last_failure_time > self.recovery_timeout:
                self.state = "HALF-OPEN"  # 进入半开状态允许一次尝试
                return True
            return False  # 仍在超时期内，拒绝请求

        # 断路器关闭或半开状态都允许请求
        return True

    def __str__(self):
        return f"CircuitBreaker(state={self.state}, failures={self.failure_count})"


class FactCheckClient:
    def __init__(self, server_url, api_key=None, search_engine_id=None, max_retries=3, max_workers=4):
        """
        初始化事实核查客户端

        Args:
            server_url: 服务器URL
            api_key: Google API密钥
            search_engine_id: Google搜索引擎ID
            max_retries: 最大重试次数
            max_workers: 并行查询工作线程数
        """
        self.server_url = server_url.rstrip('/')
        self.api_key = api_key or os.environ.get("GOOGLE_API_KEY")
        self.search_engine_id = search_engine_id or os.environ.get("GOOGLE_CSE_ID")
        self.max_retries = max_retries
        self.max_workers = max_workers

        # 添加声明缓存
        self.claim_cache = {}  # 用于存储任务ID和对应的声明

        # 当前正在处理的任务ID，用于清理
        self.current_task_id = None

        # 添加断路器
        self.search_circuit_breaker = CircuitBreaker(failure_threshold=5, recovery_timeout=120)

        # 增强Http会话，提供重试机制
        self.session = self._create_robust_session()

        # 检查API密钥和搜索引擎ID
        if not self.api_key or not self.search_engine_id:
            logger.warning("未设置Google API密钥或搜索引擎ID！将无法执行搜索查询")
            self.search = None
            self.search_tool = None
        else:
            # 设置环境变量
            os.environ["GOOGLE_API_KEY"] = self.api_key
            os.environ["GOOGLE_CSE_ID"] = self.search_engine_id

            try:
                # 初始化搜索工具
                logger.info(f"初始化GoogleSearchAPIWrapper，返回前5条结果...")
                self.search = GoogleSearchAPIWrapper(k=5)

                # 使用Tool包装搜索功能
                self.search_tool = Tool(
                    name="Google Search",
                    description="Search Google for recent results.",
                    func=self.search.run
                )
                logger.info("已成功初始化Google搜索工具")
            except Exception as e:
                logger.error(f"初始化搜索工具时出错: {str(e)}")
                logger.debug(traceback.format_exc())
                self.search = None
                self.search_tool = None

        # 注册清理函数
        self._register_cleanup_handlers()

    def _register_cleanup_handlers(self):
        """注册清理处理函数"""

        def cleanup_handler(signum, frame):
            logger.info("接收到退出信号，正在清理资源...")
            self._cleanup_on_exit()
            sys.exit(0)

        def cleanup_at_exit():
            logger.info("程序正常退出，正在清理资源...")
            self._cleanup_on_exit()

        # 注册信号处理器
        signal.signal(signal.SIGINT, cleanup_handler)  # Ctrl+C
        signal.signal(signal.SIGTERM, cleanup_handler)  # 终止信号

        # 注册退出时清理
        atexit.register(cleanup_at_exit)

    def _cleanup_on_exit(self):
        """客户端退出时的清理工作"""
        if self.current_task_id:
            try:
                logger.info(f"通知服务器客户端退出，任务ID: {self.current_task_id}")
                self._notify_server_client_exit(self.current_task_id)
            except Exception as e:
                logger.error(f"通知服务器失败: {e}")

    def _notify_server_client_exit(self, task_id):
        """通知服务器客户端退出"""
        try:
            url = urljoin(self.server_url, f"client_exit/{task_id}")
            response = self._make_request('post', url, timeout=10)
            if response and response.status_code == 200:
                logger.info("成功通知服务器客户端退出")
            else:
                logger.warning("通知服务器失败或无响应")
        except Exception as e:
            logger.error(f"通知服务器时出错: {e}")

    def _create_robust_session(self):
        """创建具有重试能力的请求会话"""
        from requests.adapters import HTTPAdapter
        from urllib3.util.retry import Retry

        # 定义重试策略
        retry_strategy = Retry(
            total=self.max_retries,
            backoff_factor=0.5,
            status_forcelist=[429, 500, 502, 503, 504],
            allowed_methods=["GET", "POST"]
        )

        # 创建会话并挂载适配器
        session = requests.Session()
        adapter = HTTPAdapter(max_retries=retry_strategy)
        session.mount("http://", adapter)
        session.mount("https://", adapter)

        return session

    def _make_request(self, method, url, **kwargs):
        """
        发送请求，并处理异常和重试

        Args:
            method: 请求方法 ('get' 或 'post')
            url: 请求URL
            **kwargs: 请求参数

        Returns:
            响应对象或None(如果所有重试都失败)
        """
        # 设置默认超时和重试次数
        kwargs.setdefault('timeout', 120)
        retries = kwargs.pop('retries', self.max_retries)

        # 默认请求函数
        request_func = getattr(self.session, method.lower())

        for attempt in range(retries + 1):
            try:
                if attempt > 0:
                    delay = 2 ** (attempt - 1)  # 指数退避策略
                    logger.info(f"第 {attempt} 次重试，等待 {delay} 秒...")
                    time.sleep(delay)

                logger.debug(f"发送{method.upper()}请求: {url}")
                response = request_func(url, **kwargs)
                return response

            except requests.exceptions.Timeout as e:
                logger.warning(f"请求超时: {e}")
                if attempt == retries:
                    logger.error("达到最大重试次数，放弃请求")
                    return None

            except requests.exceptions.ConnectionError as e:
                logger.warning(f"连接错误: {e}")
                if "RemoteDisconnected" in str(e) or "ConnectionResetError" in str(e):
                    logger.warning("服务器断开连接，可能是服务器忙或请求太大")
                    # 增加超时时间
                    kwargs['timeout'] = kwargs.get('timeout', 120) + 60

                if attempt == retries:
                    logger.error("达到最大重试次数，放弃请求")
                    return None

            except Exception as e:
                logger.error(f"请求出错: {e}")
                logger.debug(traceback.format_exc())
                if attempt == retries:
                    logger.error("达到最大重试次数，放弃请求")
                    return None

        return None

    def check_server(self):
        """检查服务器是否在线"""
        try:
            response = self._make_request('get', urljoin(self.server_url, "health"), timeout=5)
            if response and response.status_code == 200:
                logger.info("服务器在线且运行正常")
                return True
            else:
                status_code = response.status_code if response else "未知"
                logger.error(f"服务器返回错误状态码: {status_code}")
                return False
        except Exception as e:
            logger.error(f"无法连接到服务器: {e}")
            return False

    def submit_task(self, claim, media_path=None):
        """
        提交事实核查任务，并缓存声明

        Args:
            claim: 需要核查的声明
            media_path: 媒体文件路径(可选)

        Returns:
            task_id 或 None(如果出错)
        """
        try:
            # 准备请求数据
            data = {'claim': claim}
            files = {}

            if media_path and os.path.exists(media_path):
                # 获取文件大小，用于打印日志
                file_size = os.path.getsize(media_path) / (1024 * 1024)  # 转换为MB
                logger.info(f"正在准备上传媒体文件: {media_path} (大小: {file_size:.2f} MB)")

                # 对于大文件，使用更长的超时时间
                timeout = max(120, int(file_size * 3))  # 根据文件大小设置超时时间，最少120秒
                logger.info(f"设置超时时间为 {timeout} 秒")

                files['media'] = (os.path.basename(media_path), open(media_path, 'rb'))
            elif media_path:
                logger.error(f"媒体文件不存在: {media_path}")
                return None

            # 发送请求
            url = urljoin(self.server_url, "submit_task")

            # 在请求中使用流式上传模式，避免内存问题
            logger.info("开始上传文件和提交任务...")
            response = self._make_request('post', url, data=data, files=files,
                                          timeout=timeout if 'timeout' in locals() else 180,
                                          stream=True)

            # 关闭文件
            if media_path and os.path.exists(media_path) and 'media' in files:
                files['media'][1].close()

            if response and response.status_code == 200:
                result = response.json()
                task_id = result.get('task_id')
                logger.info(f"任务提交成功，任务ID: {task_id}")

                # 缓存声明
                self.claim_cache[task_id] = claim
                logger.info(f"已缓存声明: '{claim}' 对应任务ID: {task_id}")

                # 设置当前任务ID
                self.current_task_id = task_id

                return task_id
            else:
                status_code = response.status_code if response else "连接失败"
                error_text = response.text if response else "无响应"
                logger.error(f"任务提交失败: {status_code} - {error_text}")

                # 更详细的错误分析
                if response and response.status_code == 413:
                    logger.error("文件可能太大，超出服务器限制")
                elif not response:
                    logger.error("服务器没有响应，可能是处理媒体文件时崩溃或超时")

                return None

        except Exception as e:
            logger.error(f"提交任务时出错: {e}")
            logger.debug(traceback.format_exc())
            return None

    def perform_search(self, query, retry=0):
        """
        执行Google搜索查询，增强版本

        Args:
            query: 搜索查询
            retry: 当前重试次数

        Returns:
            搜索结果列表
        """
        if not self.search_tool:
            logger.error("搜索工具未初始化，无法执行搜索")
            return []

        try:
            logger.info(f"正在搜索: '{query}'")
            start_time = time.time()

            # 执行搜索
            result = self.search_tool.run(query)

            elapsed_time = time.time() - start_time
            logger.info(f"搜索完成，耗时: {elapsed_time:.2f}秒")

            # 处理结果
            if result:
                # 分析结果并分成段落
                paragraphs = [p.strip() for p in result.split("\n\n") if p.strip()]

                # 如果没有得到明确的段落，尝试通过句号拆分
                if len(paragraphs) <= 1 and len(result) > 100:
                    paragraphs = [s.strip() + "." for s in result.split(". ") if s.strip()]

                # 确保我们至少返回原始结果
                if not paragraphs:
                    paragraphs = [result]

                logger.info(f"查询 '{query}' 找到 {len(paragraphs)} 个段落")
                return paragraphs
            else:
                logger.warning(f"查询 '{query}' 没有返回结果")
                return []

        except (requests.exceptions.ConnectionError,
                requests.exceptions.ChunkedEncodingError,
                requests.exceptions.ReadTimeout,
                requests.exceptions.SSLError,
                http.client.RemoteDisconnected,
                http.client.IncompleteRead,
                ConnectionResetError,
                ssl.SSLError) as e:
            # 特定的网络连接错误处理
            error_type = type(e).__name__
            logger.error(f"搜索查询 '{query}' 发生网络错误: {error_type} - {str(e)}")

            if retry < self.max_retries:
                # 计算退避时间，添加随机抖动防止同时重试
                base_delay = min(30, 2 ** retry)  # 最大等待30秒
                jitter = random.uniform(0, 1)  # 添加0到1秒的随机抖动
                delay = base_delay + jitter

                logger.info(f"网络错误，等待 {delay:.2f} 秒后重试 ({retry + 1}/{self.max_retries})...")
                time.sleep(delay)

                # 如果是SSL相关错误，尝试重新初始化搜索工具
                if isinstance(e, ssl.SSLError) or "SSL" in str(e):
                    logger.info("检测到SSL错误，尝试重新初始化搜索工具...")
                    try:
                        # 重新初始化搜索工具
                        self.search = GoogleSearchAPIWrapper(k=5)
                        self.search_tool = Tool(
                            name="Google Search",
                            description="Search Google for recent results.",
                            func=self.search.run
                        )
                    except Exception as init_error:
                        logger.error(f"重新初始化搜索工具失败: {str(init_error)}")

                return self.perform_search(query, retry + 1)

            logger.error(f"搜索查询 '{query}' 达到最大重试次数 {self.max_retries}，返回空结果")
            return []

        except Exception as e:
            # 其他类型的错误
            logger.error(f"搜索查询 '{query}' 出错: {str(e)}")
            logger.debug(traceback.format_exc())

            if retry < self.max_retries:
                # 计算退避时间
                delay = 2 ** retry + random.uniform(0, 1)  # 指数退避+随机抖动
                logger.info(f"搜索失败，等待 {delay:.2f} 秒后重试 ({retry + 1}/{self.max_retries})...")
                time.sleep(delay)
                return self.perform_search(query, retry + 1)

            return []

    def process_queries(self, task_id, max_wait_time=300):
        """
        从服务器获取查询请求并执行搜索

        Args:
            task_id: 任务ID
            max_wait_time: 最大等待时间(秒)，默认5分钟

        Returns:
            True成功，False失败
        """
        try:
            # 循环检查查询，使用可配置的等待时间
            start_time = time.time()
            claim = None  # 用于存储任务的claim内容

            # 尝试从缓存中获取声明
            if task_id in self.claim_cache:
                claim = self.claim_cache[task_id]
                logger.info(f"从缓存中获取到声明: '{claim}'")

            logger.info(f"等待服务器处理任务，最长等待 {max_wait_time} 秒...")

            while time.time() - start_time < max_wait_time:
                # 获取查询列表和claim
                url = urljoin(self.server_url, f"get_queries/{task_id}")
                response = self._make_request('get', url, timeout=30)

                if not response:
                    logger.error("获取查询请求失败，服务器无响应")
                    return False

                if response.status_code != 200:
                    logger.error(f"获取查询失败: {response.status_code} - {response.text}")
                    return False

                result = response.json()

                # 如果尚未从缓存获取claim，尝试从响应中提取
                if not claim and "claim" in result:
                    claim = result.get("claim")
                    logger.info(f"从服务器响应获取到声明: '{claim}'")
                    # 更新缓存
                    self.claim_cache[task_id] = claim

                if result.get("status") == "error":
                    logger.error(f"任务出错: {result.get('error')}")
                    logger.debug(f"错误详情: {result.get('traceback')}")
                    return False

                if result.get("status") == "processing":
                    elapsed = int(time.time() - start_time)
                    remaining = max_wait_time - elapsed
                    logger.info(f"任务仍在处理中，已等待 {elapsed}s，剩余 {remaining}s，等待10秒后重试...")
                    time.sleep(10)
                    continue

                if result.get("status") == "success":
                    queries = result.get("queries", [])
                    queries = filter_search_queries(queries)

                    # 将claim添加到查询列表（如果不存在）
                    if claim and claim not in queries:
                        queries.append(claim)
                        logger.info(f"将声明添加到查询列表: '{claim}'")

                    total_queries = len(queries)

                    if not queries:
                        logger.info("没有需要处理的查询")
                        return True

                    logger.info(f"收到 {total_queries} 个需要处理的查询")
                    break

                # 其他状态，等待片刻后重试
                elapsed = int(time.time() - start_time)
                remaining = max_wait_time - elapsed
                logger.warning(
                    f"任务状态为 '{result.get('status')}', 已等待 {elapsed}s，剩余 {remaining}s，等待10秒后重试...")
                time.sleep(10)

            # 如果循环结束但没有成功获取查询
            if not 'queries' in locals() or not queries:
                logger.error(f"等待超时({max_wait_time}秒)，无法获取查询")
                return False

            # 使用线程池并行执行查询
            query_results = {}

            # 如果断路器打开，警告并返回空结果
            if not self.search_circuit_breaker.can_execute():
                logger.warning("搜索断路器已打开，跳过所有搜索查询。系统将在稍后尝试恢复。")
                # 返回空结果集
                for query in queries:
                    query_results[query] = []
                # 继续流程，不执行搜索
            else:
                with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                    # 直接使用原始查询，不进行增强
                    future_to_query = {
                        executor.submit(self.perform_search, query): query
                        for query in queries
                    }

                    # 收集查询失败计数
                    search_failures = 0

                    # 使用tqdm创建进度条
                    with tqdm(total=len(future_to_query), desc="执行搜索查询", unit="query") as pbar:
                        for future in concurrent.futures.as_completed(future_to_query):
                            query = future_to_query[future]
                            try:
                                results = future.result()
                                query_results[query] = results

                                # 记录成功
                                if results:  # 只有返回非空结果才算成功
                                    self.search_circuit_breaker.record_success()
                                    logger.info(f"查询 '{query}' 成功获取 {len(results)} 条结果")
                                else:
                                    logger.warning(f"查询 '{query}' 未返回结果")
                            except Exception as e:
                                logger.error(f"处理查询 '{query}' 时出错: {e}")
                                query_results[query] = []

                                # 记录失败
                                self.search_circuit_breaker.record_failure()
                                search_failures += 1
                            pbar.update(1)

                    # 如果失败太多，记录警告
                    if search_failures > len(queries) // 2:
                        logger.warning(
                            f"超过一半的查询失败 ({search_failures}/{len(queries)})，断路器状态: {self.search_circuit_breaker}")

            # 提交查询结果（使用改进的提交机制）
            logger.info(f"正在提交 {len(query_results)} 个查询的结果")

            # 使用新的确认机制提交结果
            success = self.submit_query_results_with_confirmation(task_id, query_results)

            if success:
                logger.info("所有查询结果已成功提交并确认")
                return True
            else:
                logger.warning("部分查询结果可能未成功提交，但将继续处理")
                return True

        except Exception as e:
            logger.error(f"处理查询时出错: {e}")
            logger.debug(traceback.format_exc())
            return False

    def submit_query_results_with_confirmation(self, task_id, query_results, max_retries=5):
        """
        提交查询结果并确保服务器接收，支持部分失败重试

        Args:
            task_id: 任务ID
            query_results: 查询结果字典
            max_retries: 最大重试次数

        Returns:
            bool: 是否全部成功提交
        """
        submit_url = urljoin(self.server_url, f"submit_query_results/{task_id}")

        # 跟踪未成功提交的查询
        pending_results = dict(query_results)
        all_successfully_received = []

        for attempt in range(max_retries):
            if not pending_results:
                logger.info("所有查询结果已成功提交")
                return True

            logger.info(f"尝试提交 {len(pending_results)} 个查询结果（第 {attempt + 1} 次尝试）")

            # 分批提交，但保持合理的批次大小
            batch_size = 20
            queries_to_submit = list(pending_results.keys())

            for i in range(0, len(queries_to_submit), batch_size):
                batch_queries = queries_to_submit[i:i + batch_size]
                batch_results = {q: pending_results[q] for q in batch_queries}

                try:
                    # 提交这批查询
                    response = self._make_request('post', submit_url, json=batch_results, timeout=60)

                    if response and response.status_code == 200:
                        result = response.json()

                        # 处理确认信息
                        successfully_received = result.get("successfully_received", [])
                        failed_queries = result.get("failed_queries", [])

                        logger.info(f"批次提交结果: 成功 {len(successfully_received)}, 失败 {len(failed_queries)}")

                        # 从待提交列表中移除成功的查询
                        for query in successfully_received:
                            if query in pending_results:
                                del pending_results[query]
                                all_successfully_received.append(query)

                        # 检查是否所有查询都已完成
                        if result.get("all_completed", False):
                            logger.info("服务器确认所有查询已完成，开始验证流程")
                            return True
                    else:
                        # HTTP错误，这批全部需要重试
                        logger.error(f"批次提交失败: HTTP {response.status_code if response else '无响应'}")

                except Exception as e:
                    logger.error(f"提交批次时出错: {e}")

            # 如果还有未成功的查询，等待后重试
            if pending_results and attempt < max_retries - 1:
                # 先尝试从服务器获取已接收的查询列表，以防响应丢失
                try:
                    check_url = urljoin(self.server_url, f"get_received_queries/{task_id}")
                    check_response = self._make_request('get', check_url, timeout=30)

                    if check_response and check_response.status_code == 200:
                        result = check_response.json()
                        received_queries = result.get("received_queries", [])

                        # 更新待提交列表
                        for query in list(pending_results.keys()):
                            if query in received_queries:
                                logger.info(f"查询 '{query}' 已被服务器接收（通过确认检查）")
                                del pending_results[query]
                                if query not in all_successfully_received:
                                    all_successfully_received.append(query)

                except Exception as e:
                    logger.warning(f"检查已接收查询时出错: {e}")

                if pending_results:
                    wait_time = min(10 * (attempt + 1), 30)  # 递增等待时间，最多30秒
                    logger.warning(f"还有 {len(pending_results)} 个查询未成功提交，{wait_time}秒后重试...")
                    time.sleep(wait_time)

        # 达到最大重试次数
        if pending_results:
            logger.error(f"达到最大重试次数，仍有 {len(pending_results)} 个查询未能提交")
            logger.error(f"未提交的查询: {list(pending_results.keys())[:5]}...")  # 只显示前5个

            # 保存未提交的结果到本地文件，以便后续恢复
            failed_results_path = f"failed_results_{task_id}_{int(time.time())}.json"
            try:
                with open(failed_results_path, 'w', encoding='utf-8') as f:
                    json.dump({
                        "task_id": task_id,
                        "timestamp": time.time(),
                        "failed_queries": pending_results
                    }, f, ensure_ascii=False, indent=2)
                logger.info(f"未提交的查询结果已保存到: {failed_results_path}")
            except Exception as e:
                logger.error(f"保存失败结果时出错: {e}")

        # 返回是否全部成功
        return len(pending_results) == 0

    def check_task_status(self, task_id):
        """
        检查任务状态

        Args:
            task_id: 任务ID

        Returns:
            任务状态字典或None(如果出错)
        """
        try:
            url = urljoin(self.server_url, f"get_task_status/{task_id}")
            response = self._make_request('get', url, timeout=30)

            if response and response.status_code == 200:
                return response.json()
            else:
                status_code = response.status_code if response else "连接失败"
                error_text = response.text if response else "无响应"
                logger.error(f"检查任务状态失败: {status_code} - {error_text}")
                return None

        except Exception as e:
            logger.error(f"检查任务状态时出错: {e}")
            return None

    def download_result(self, task_id, output_path):
        """
        下载任务结果

        Args:
            task_id: 任务ID
            output_path: 输出文件路径

        Returns:
            True成功，False失败
        """
        try:
            url = urljoin(self.server_url, f"download_result/{task_id}")
            response = self._make_request('get', url, timeout=30)

            if response and response.status_code == 200:
                with open(output_path, 'wb') as f:
                    f.write(response.content)
                logger.info(f"结果已下载到: {output_path}")
                return True
            else:
                status_code = response.status_code if response else "连接失败"
                error_text = response.text if response else "无响应"
                logger.error(f"下载结果失败: {status_code} - {error_text}")
                return False

        except Exception as e:
            logger.error(f"下载结果时出错: {e}")
            return False

    def direct_verify(self, task_id):
        """
        使用Qwen直接验证(不需要外部查询)

        Args:
            task_id: 任务ID

        Returns:
            验证结果或None(如果出错)
        """
        try:
            url = urljoin(self.server_url, f"direct_verify/{task_id}")
            response = self._make_request('get', url, timeout=180)  # 增加直接验证的超时时间

            if response and response.status_code == 200:
                return response.json()
            else:
                status_code = response.status_code if response else "连接失败"
                error_text = response.text if response else "无响应"
                logger.error(f"直接验证失败: {status_code} - {error_text}")
                return None

        except Exception as e:
            logger.error(f"直接验证时出错: {e}")
            return None

    def run_complete_workflow(self, claim, media_path=None, output_path=None, direct_verify=False,
                              max_wait_time=600, query_wait_time=300):
        """
        执行完整的事实核查工作流程

        Args:
            claim: 需要核查的声明
            media_path: 媒体文件路径(可选)
            output_path: 输出文件路径(可选)
            direct_verify: 是否使用Qwen直接验证
            max_wait_time: 最大等待验证完成时间(秒)，默认10分钟
            query_wait_time: 等待查询生成的最大时间(秒)，默认5分钟

        Returns:
            结果字典或None(如果出错)
        """
        logger.info(f"开始完整工作流程，声明: '{claim}'")

        # 1. 检查服务器状态
        if not self.check_server():
            logger.error("服务器不可用，无法继续")
            return None

        # 2. 提交任务
        for attempt in range(3):  # 提交任务最多尝试3次
            if attempt > 0:
                logger.info(f"第 {attempt + 1} 次尝试提交任务...")

            task_id = self.submit_task(claim, media_path)
            if task_id:
                break

            logger.warning(f"提交任务失败，等待5秒后重试...")
            time.sleep(5)

        if not task_id:
            logger.error("多次尝试提交任务均失败，无法继续")
            return None

        # 3. 如果是直接验证，则调用直接验证接口
        if direct_verify:
            logger.info("使用Qwen直接验证...")

            for attempt in range(3):  # 直接验证最多尝试3次
                if attempt > 0:
                    logger.info(f"第 {attempt + 1} 次尝试直接验证...")

                result = self.direct_verify(task_id)
                if result:
                    break

                logger.warning(f"直接验证失败，等待5秒后重试...")
                time.sleep(5)

            # 保存结果到文件(如果指定了输出路径)
            if result and output_path:
                with open(output_path, 'w', encoding='utf-8') as f:
                    json.dump(result, f, ensure_ascii=False, indent=2)
                logger.info(f"直接验证结果已保存到: {output_path}")

            return result

        # 4. 等待服务器处理任务并获取查询
        logger.info("等待服务器处理任务...")
        time.sleep(5)  # 给服务器一些处理时间

        # 5. 处理查询，最多尝试3次
        for attempt in range(3):
            if attempt > 0:
                logger.info(f"第 {attempt + 1} 次尝试处理查询...")

            # 使用可配置的查询等待时间
            if self.process_queries(task_id, max_wait_time=query_wait_time):
                break

            logger.warning(f"处理查询失败，等待10秒后重试...")
            time.sleep(10)

        # 6. 等待服务器完成验证
        start_time = time.time()
        completed = False
        result = None

        logger.info(f"等待服务器完成验证，最长等待 {max_wait_time} 秒...")
        with tqdm(total=max_wait_time, desc="等待验证完成", unit="s") as pbar:
            while time.time() - start_time < max_wait_time:
                status = self.check_task_status(task_id)

                if status and status.get("status") == "completed":
                    completed = True
                    result = status.get("result")
                    break

                if status and status.get("status") == "error":
                    logger.error(f"任务出错: {status.get('error')}")
                    logger.debug(f"错误详情: {status.get('traceback')}")
                    break

                # 更新进度条
                elapsed = min(int(time.time() - start_time), max_wait_time)
                pbar.update(elapsed - pbar.n)

                # 短暂休眠
                time.sleep(5)

        if not completed:
            logger.warning(f"等待超时，任务可能仍在处理中。任务ID: {task_id}")
            # 清理当前任务ID
            self.current_task_id = None
            return None

        # 7. 下载结果(如果指定了输出路径)
        if completed and output_path:
            if not self.download_result(task_id, output_path):
                # 如果下载失败但我们有结果，则直接保存
                if result:
                    with open(output_path, 'w', encoding='utf-8') as f:
                        json.dump(result, f, ensure_ascii=False, indent=2)
                    logger.info(f"结果已保存到: {output_path}")

        # 清理当前任务ID
        self.current_task_id = None
        return result


def main():
    parser = argparse.ArgumentParser(description="事实核查系统客户端 (增强版)")
    parser.add_argument("--server", default="http://workspace.featurize.cn:35407", help="服务器URL")
    parser.add_argument("--api-key", help="Google API密钥")
    parser.add_argument("--search-engine-id", help="Google搜索引擎ID")
    parser.add_argument("--claim", required=True, help="待核查的声明")
    parser.add_argument("--media", help="媒体文件路径(mp4或jpg)")
    parser.add_argument("--output", default="result.json", help="输出文件路径")
    parser.add_argument("--direct", action="store_true", help="使用Qwen直接验证(不执行网络查询)")
    parser.add_argument("--max-wait", type=int, default=600, help="最大等待验证完成时间(秒)")
    parser.add_argument("--query-wait", type=int, default=300, help="等待查询生成的最大时间(秒)")
    parser.add_argument("--debug", action="store_true", help="启用调试日志")
    parser.add_argument("--max-retries", type=int, default=3, help="最大重试次数")
    parser.add_argument("--test-search", help="测试搜索查询而不执行完整流程")

    args = parser.parse_args()

    # 设置日志级别
    if args.debug:
        logger.setLevel(logging.DEBUG)

    # 从环境变量获取API密钥和搜索引擎ID(如果命令行未提供)
    api_key = args.api_key or os.environ.get("GOOGLE_API_KEY")
    search_engine_id = args.search_engine_id or os.environ.get("GOOGLE_CSE_ID")

    if not api_key or not search_engine_id:
        logger.warning("未设置Google API密钥或搜索引擎ID")

    # 创建客户端实例
    client = FactCheckClient(args.server, api_key, search_engine_id, args.max_retries)

    try:
        # 如果指定了测试搜索，则只执行搜索测试
        if args.test_search:
            print(f"\n===== 测试搜索查询: '{args.test_search}' =====")
            results = client.perform_search(args.test_search)
            print(f"找到 {len(results)} 条结果:")
            for i, result in enumerate(results, 1):
                print(f"\n[{i}] {result}")
            return

        # 执行完整工作流程
        result = client.run_complete_workflow(
            args.claim,
            args.media,
            args.output,
            args.direct,
            args.max_wait,
            args.query_wait
        )

        # 打印结果摘要
        if result:
            print("\n===== 事实核查结果摘要 =====")

            if "direct_verification" in result:
                print(f"直接验证结果: {result['direct_verification']}")
            else:
                judgment = result.get("final_judgment", {}).get("final_judgment", "uncertain")
                confidence = result.get("final_judgment", {}).get("confidence", 0)

                print(f"声明: {result.get('claim')}")
                print(f"最终判断: {judgment}")
                print(f"置信度: {confidence}")

                evidence_count = len(result.get("evidence", []))
                print(f"证据数量: {evidence_count}")

            print(f"\n完整结果已保存到: {args.output}")
        else:
            print("\n❌ 核查失败，请查看日志以获取详细信息")

    except Exception as e:
        logger.critical(f"程序执行过程中发生致命错误: {str(e)}")
        logger.critical(traceback.format_exc())
        print(f"\n❌ 程序崩溃: {str(e)}")
        print("请查看日志获取详细信息")
        sys.exit(1)


if __name__ == "__main__":
    main()