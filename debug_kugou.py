#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
调试酷狗音乐API
"""

import requests
import json

def test_kugou_api():
    """测试酷狗音乐API"""
    session = requests.Session()
    session.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    })
    
    # 1. 搜索歌曲
    print("="*60)
    print("测试酷狗音乐搜索API")
    print("="*60)
    
    search_url = "https://mobileservice.kugou.com/api/v3/search/song"
    params = {
        'format': 'json',
        'keyword': '稻香',
        'page': 1,
        'pagesize': 2,
        'showtype': 1
    }
    
    response = session.get(search_url, params=params, timeout=10)
    print(f"搜索状态码: {response.status_code}")
    
    if response.status_code == 200:
        data = response.json()
        songs = data.get('data', {}).get('info', [])
        
        print(f"\n找到 {len(songs)} 首歌曲:")
        for i, song in enumerate(songs, 1):
            print(f"{i}. {song.get('songname')} - {song.get('singername')}")
            print(f"   Hash: {song.get('hash')}")
            print(f"   320hash: {song.get('320hash', 'N/A')}")
            print(f"   sqhash: {song.get('sqhash', 'N/A')}")
        
        # 2. 测试获取下载链接
        if songs:
            print("\n" + "="*60)
            print("测试获取下载链接")
            print("="*60)
            
            song_hash = songs[0].get('hash')
            print(f"测试Hash: {song_hash}")
            
            # 方法1
            print("\n[方法1] wwwapi.kugou.com/yy/index.php")
            url1 = "https://wwwapi.kugou.com/yy/index.php"
            params1 = {'r': 'play/getdata', 'hash': song_hash}
            
            try:
                resp1 = session.get(url1, params=params1, timeout=10)
                print(f"状态码: {resp1.status_code}")
                if resp1.status_code == 200:
                    print(f"响应内容: {resp1.text[:500]}")
                    data1 = resp1.json()
                    print(f"JSON数据: {json.dumps(data1, ensure_ascii=False, indent=2)[:500]}")
            except Exception as e:
                print(f"错误: {e}")
            
            # 方法2
            print("\n[方法2] wwwapi.kugou.com/play/songinfo")
            url2 = f"https://wwwapi.kugou.com/play/songinfo?hash={song_hash}"
            
            try:
                resp2 = session.get(url2, timeout=10)
                print(f"状态码: {resp2.status_code}")
                if resp2.status_code == 200:
                    print(f"响应内容: {resp2.text[:500]}")
                    data2 = resp2.json()
                    print(f"JSON数据: {json.dumps(data2, ensure_ascii=False, indent=2)[:500]}")
            except Exception as e:
                print(f"错误: {e}")
            
            # 方法3 - 尝试新的API
            print("\n[方法3] 尝试trackercdn接口")
            url3 = f"https://trackercdn.kugou.com/i/v2/?cmd=25&pid=1&behavior=play&album_audio_id=0&hash={song_hash}"
            
            try:
                resp3 = session.get(url3, timeout=10)
                print(f"状态码: {resp3.status_code}")
                if resp3.status_code == 200:
                    print(f"响应内容: {resp3.text[:500]}")
                    data3 = resp3.json()
                    print(f"JSON数据: {json.dumps(data3, ensure_ascii=False, indent=2)[:500]}")
                    
                    # 尝试提取URL
                    if 'url' in data3:
                        print(f"\n✅ 找到下载URL: {data3['url']}")
            except Exception as e:
                print(f"错误: {e}")

if __name__ == "__main__":
    test_kugou_api()
