#!/usr/bin/env python
"""
搜索查询过滤器 - 过滤不适合搜索的内容

此模块应当加入到client_robust.py或fact_check_client.py中，
在执行搜索查询之前过滤查询内容。
"""

import re
import logging

logger = logging.getLogger("search_filter")

def filter_search_queries(queries):
    """
    过滤搜索查询列表，移除不适合搜索的内容
    
    Args:
        queries: 原始查询列表
        
    Returns:
        list: 过滤后的查询列表
    """
    if not queries or not isinstance(queries, list):
        logger.warning("无效的查询列表")
        return []
        
    filtered_queries = []
    skipped_count = 0
    
    for query in queries:
        # 跳过无效查询
        if not query or not isinstance(query, str):
            skipped_count += 1
            continue
            
        # 跳过过长的查询
        if len(query) > 150:
            logger.info(f"跳过过长查询: {query[:50]}...")
            skipped_count += 1
            continue
            
        # 跳过包含HTML、XML或markdown标记的查询
        if re.search(r'<[^>]+>|</[^>]+>|\*\*|\[|\]|##+|\+\+\+', query):
            logger.info(f"跳过包含标记的查询: {query}")
            skipped_count += 1
            continue
            
        # 跳过包含常见思考过程关键词的查询
        thinking_keywords = [
            'think', 'thought', 'breakdown', 'analyze', 'analysis', 
            'let me', 'i need to', 'i will', 'i would', 'i should', 
            'first,', 'second,', 'third,', 'finally,', 'step',
            'subject-predicate-object', 'subject:', 'predicate:', 'object:'
        ]
        
        if any(keyword in query.lower() for keyword in thinking_keywords):
            logger.info(f"跳过思考过程查询: {query}")
            skipped_count += 1
            continue
        
        # 跳过包含常见格式化指示语的查询
        format_keywords = [
            'sentence', 'breakdown', 'identify', 'component', 
            'structure', 'person is', 'condition is', 'summary'
        ]
        
        if any(keyword in query.lower() for keyword in format_keywords) and len(query.split()) > 10:
            logger.info(f"跳过格式化指示查询: {query}")
            skipped_count += 1
            continue
            
        # 跳过空格过多的查询
        if query.count(' ') > len(query) * 0.3:
            query = re.sub(r'\s+', ' ', query).strip()
        
        # 跳过非问句的问题形式
        if query.endswith('?') and not query.lower().startswith(('what', 'who', 'when', 'where', 'why', 'how', 'is', 'are', 'was', 'were', 'do', 'does', 'did', 'has', 'have', 'had', 'can', 'could', 'should', 'would')):
            logger.info(f"跳过非标准问句: {query}")
            skipped_count += 1
            continue
            
        # 最终检查：确保查询是一个合理的搜索词
        words = query.split()
        if len(words) < 2 or len(words) > 15:
            logger.info(f"跳过词数不合理的查询: {query}")
            skipped_count += 1
            continue
        
        # 添加到过滤后的查询列表
        filtered_queries.append(query)
    
    logger.info(f"原始查询数: {len(queries)}, 有效查询数: {len(filtered_queries)}, 跳过查询数: {skipped_count}")
    
    # 如果过滤后没有查询，尝试构建简单查询
    if not filtered_queries and queries:
        # 从原始查询中提取关键词
        all_text = " ".join(queries)
        # 提取中文名字和关键词
        chinese_names = re.findall(r'[\u4e00-\u9fff]{2,4}', all_text)
        keywords = re.findall(r'\b[A-Za-z]{4,}\b', all_text)
        
        if chinese_names:
            for name in chinese_names:
                filtered_queries.append(name)
                
        if keywords:
            top_keywords = sorted(set(keywords), key=len, reverse=True)[:5]
            for keyword in top_keywords:
                if keyword.lower() not in ('think', 'first', 'second', 'third', 'should', 'would', 'could'):
                    filtered_queries.append(keyword)
        
        logger.info(f"从原始查询中提取了 {len(filtered_queries)} 个关键词查询")
    
    return filtered_queries

# 使用示例
if __name__ == "__main__":
    # 设置日志格式
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # 测试数据
    test_queries = [
        "拜登罹患前列腺癌",  # 有效查询
        "<think>我需要分析这个声明</think>",  # 包含标记
        "**The person is拜登.**",  # 包含markdown标记
        "Subject-Predicate-Object Sentence Breakdown:",  # 思考过程
        "To start, I need to identify the key elements in the original claim.",  # 思考过程
        "Joe Biden has prostate cancer.",  # 有效查询
        "first, I will analyze the claim",  # 思考过程
        "白宫发言人证实拜登曾接受前列腺癌治疗",  # 有效查询
    ]
    
    # 过滤测试查询
    filtered = filter_search_queries(test_queries)
    
    # 显示结果
    print("\n过滤后的查询:")
    for i, query in enumerate(filtered, 1):
        print(f"{i}. {query}")
