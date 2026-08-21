#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载功能测试脚本
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from music_crawler import MusicCrawler

def test_download():
    """测试下载功能"""
    print("="*60)
    print("下载功能测试")
    print("="*60)
    
    crawler = MusicCrawler(save_dir="./test_music")
    
    # 先搜索一首歌
    print("\n1. 搜索歌曲...")
    songs = crawler.search_music("晴天 周杰伦", limit=1)
    
    if not songs:
        print("❌ 搜索失败")
        return
    
    print(f"\n找到歌曲: {songs[0]['name']} - {songs[0]['artist']}")
    print(f"歌曲ID: {songs[0]['id']}")
    
    # 尝试获取下载链接
    print("\n2. 获取下载链接...")
    song_id = songs[0]['id']
    url, need_decrypt = crawler.get_song_url(song_id)
    
    print(f"\n下载链接: {url}")
    print(f"需要解密: {need_decrypt}")
    
    # 尝试下载
    print("\n3. 尝试下载...")
    result = crawler.download_music(songs[0])
    
    if result:
        print(f"\n✅ 下载成功: {result}")
        # 检查文件
        import pathlib
        file_path = pathlib.Path(result)
        if file_path.exists():
            size = file_path.stat().st_size
            print(f"文件大小: {size/1024/1024:.2f} MB")
            
            # 检查文件头
            with open(file_path, 'rb') as f:
                header = f.read(10)
                print(f"文件头: {header[:10]}")
                
                # MP3文件通常以ID3或FF FB开头
                if header[:3] == b'ID3' or header[:2] == b'\xff\xfb':
                    print("✅ 文件格式正确（MP3）")
                elif b'<!DOCTYPE' in header or b'<html' in header:
                    print("❌ 文件是HTML，不是音频")
                else:
                    print("⚠️ 文件格式未知")
    else:
        print("\n❌ 下载失败")

if __name__ == "__main__":
    test_download()
