#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试多平台爬虫
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from music_crawler_v2 import MusicCrawlerV2

def test():
    print("="*60)
    print("多平台音乐爬虫测试")
    print("="*60)
    
    crawler = MusicCrawlerV2(save_dir="./test_music_v2")
    
    # 测试搜索
    keyword = "晴天"
    print(f"\n测试搜索: {keyword}")
    
    songs = crawler.search_all(keyword, limit=3)
    
    if songs:
        crawler.display_songs(songs)
        
        # 尝试下载第一首
        print("\n尝试下载第一首歌曲...")
        if songs:
            result = crawler.download_song(songs[0])
            if result:
                print(f"\n✅ 测试成功！文件已保存: {result}")
            else:
                print("\n❌ 下载失败")
    else:
        print("\n❌ 搜索失败")

if __name__ == "__main__":
    test()
