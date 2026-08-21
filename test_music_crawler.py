#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动测试音乐爬虫脚本
"""

import sys
import time
from music_crawler import MusicCrawler
from music_crawler_v2 import MusicCrawlerV2

def test_v1():
    """测试版本1 - 网易云音乐"""
    print("\n" + "="*60)
    print("测试 Music Crawler V1 (网易云音乐)")
    print("="*60)
    
    try:
        crawler = MusicCrawler(save_dir="./test_music_v1")
        
        # 测试搜索功能
        print("\n[测试1] 搜索功能测试")
        keyword = "周杰伦"
        print(f"搜索关键词: {keyword}")
        songs = crawler.search_music(keyword, limit=3)
        
        if songs:
            print(f"✅ 搜索成功，找到 {len(songs)} 首歌曲")
            crawler.display_songs(songs)
            
            # 测试获取下载链接
            print("\n[测试2] 获取下载链接测试")
            song = songs[0]
            print(f"测试歌曲: {song['name']} - {song['artist']}")
            url, need_decrypt = crawler.get_song_url(song['id'])
            
            if url:
                print(f"✅ 获取下载链接成功")
                print(f"   链接: {url[:80]}...")
            else:
                print("❌ 获取下载链接失败")
        else:
            print("❌ 搜索失败，未找到歌曲")
            
        return True
        
    except Exception as e:
        print(f"❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False

def test_v2():
    """测试版本2 - 多平台"""
    print("\n" + "="*60)
    print("测试 Music Crawler V2 (多平台)")
    print("="*60)
    
    try:
        crawler = MusicCrawlerV2(save_dir="./test_music_v2")
        
        # 测试多平台搜索
        print("\n[测试1] 多平台搜索测试")
        keyword = "稻香"
        print(f"搜索关键词: {keyword}")
        songs = crawler.search_all(keyword, limit=2)
        
        if songs:
            print(f"\n✅ 搜索成功，总共找到 {len(songs)} 首歌曲")
            crawler.display_songs(songs)
            
            # 测试获取下载链接
            print("\n[测试2] 获取下载链接测试")
            for i, song in enumerate(songs[:3], 1):  # 只测试前3首
                print(f"\n测试歌曲 {i}: {song['name']} - {song['artist']} [{song['platform']}]")
                
                audio_url = None
                if song['platform'] == 'kugou':
                    audio_url = crawler.get_kugou_url(song.get('hash', ''))
                elif song['platform'] == 'kuwo':
                    audio_url = crawler.get_kuwo_url(song.get('id', 0))
                elif song['platform'] == 'netease':
                    audio_url = crawler.get_netease_url(song.get('id', 0))
                
                if audio_url:
                    print(f"  ✅ 获取下载链接成功")
                else:
                    print(f"  ❌ 获取下载链接失败")
        else:
            print("❌ 搜索失败，未找到歌曲")
            
        return True
        
    except Exception as e:
        print(f"❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        return False

def main():
    """主测试函数"""
    print("\n" + "="*60)
    print("     音乐爬虫自动测试程序")
    print("="*60)
    
    results = []
    
    # 测试V1
    results.append(("V1-网易云音乐", test_v1()))
    time.sleep(2)
    
    # 测试V2
    results.append(("V2-多平台", test_v2()))
    
    # 输出测试结果
    print("\n" + "="*60)
    print("测试结果汇总")
    print("="*60)
    for name, result in results:
        status = "✅ 通过" if result else "❌ 失败"
        print(f"{name}: {status}")
    print("="*60)

if __name__ == "__main__":
    main()
