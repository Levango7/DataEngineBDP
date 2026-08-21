#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音乐爬虫测试脚本
用于验证爬虫功能是否正常
"""

import sys
import os

# 添加当前目录到路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from music_crawler import MusicCrawler

def test_search():
    """测试搜索功能"""
    print("="*60)
    print("测试1: 搜索功能")
    print("="*60)
    
    crawler = MusicCrawler(save_dir="./test_music")
    
    # 测试搜索
    test_keywords = ["周杰伦", "晴天", "七里香"]
    
    for keyword in test_keywords:
        print(f"\n正在测试搜索: {keyword}")
        songs = crawler.search_music(keyword, limit=3)
        
        if songs:
            print(f"✅ 搜索成功，找到 {len(songs)} 首歌曲")
            crawler.display_songs(songs)
        else:
            print(f"❌ 搜索失败或无结果")
    
    return True

def test_network():
    """测试网络连接"""
    print("\n" + "="*60)
    print("测试2: 网络连接")
    print("="*60)
    
    import requests
    
    test_urls = [
        "https://music.163.com",
        "https://music.163.com/api/search/get/web"
    ]
    
    for url in test_urls:
        try:
            response = requests.get(url, timeout=5)
            print(f"✅ {url} - 状态码: {response.status_code}")
        except Exception as e:
            print(f"❌ {url} - 错误: {e}")
    
    return True

def test_api():
    """测试API接口"""
    print("\n" + "="*60)
    print("测试3: API接口测试")
    print("="*60)
    
    import requests
    import json
    
    # 测试搜索API
    url = "https://music.163.com/api/search/get/web"
    params = {
        's': '周杰伦',
        'type': 1,
        'offset': 0,
        'limit': 5
    }
    
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Referer': 'https://music.163.com/',
    }
    
    try:
        response = requests.post(url, data=params, headers=headers, timeout=10)
        print(f"状态码: {response.status_code}")
        print(f"响应长度: {len(response.text)} 字节")
        print(f"响应前100字符: {response.text[:100]}")
        
        if response.text.strip() and response.text.startswith('{'):
            data = response.json()
            print(f"✅ JSON解析成功")
            print(f"响应代码: {data.get('code')}")
            if data.get('code') == 200:
                songs = data.get('result', {}).get('songs', [])
                print(f"✅ 找到 {len(songs)} 首歌曲")
            else:
                print(f"❌ API返回错误: {data.get('message')}")
        else:
            print(f"❌ 响应不是有效的JSON格式")
            
    except Exception as e:
        print(f"❌ 测试失败: {e}")
    
    return True

def main():
    """主测试函数"""
    print("\n" + "="*60)
    print("     音乐爬虫可行性测试")
    print("="*60 + "\n")
    
    # 运行所有测试
    tests = [
        ("网络连接测试", test_network),
        ("API接口测试", test_api),
        ("搜索功能测试", test_search),
    ]
    
    results = []
    for name, test_func in tests:
        try:
            result = test_func()
            results.append((name, result))
        except Exception as e:
            print(f"\n❌ {name} 异常: {e}")
            results.append((name, False))
    
    # 输出测试结果汇总
    print("\n" + "="*60)
    print("测试结果汇总")
    print("="*60)
    
    for name, result in results:
        status = "✅ 通过" if result else "❌ 失败"
        print(f"{name}: {status}")
    
    print("="*60)

if __name__ == "__main__":
    main()
