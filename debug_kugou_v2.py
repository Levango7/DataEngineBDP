#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
调试酷狗音乐API - 尝试更多方法
"""

import requests
import json
import time

def test_kugou_download():
    """测试酷狗音乐下载"""
    session = requests.Session()
    session.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://www.kugou.com/',
    })
    
    # 搜索获取hash
    search_url = "https://mobileservice.kugou.com/api/v3/search/song"
    params = {
        'format': 'json',
        'keyword': '稻香',
        'page': 1,
        'pagesize': 1,
        'showtype': 1
    }
    
    response = session.get(search_url, params=params, timeout=10)
    data = response.json()
    song = data.get('data', {}).get('info', [])[0]
    song_hash = song.get('hash')
    hash_320 = song.get('320hash')
    
    print(f"歌曲: {song.get('songname')} - {song.get('singername')}")
    print(f"Hash: {song_hash}")
    print(f"320Hash: {hash_320}")
    
    # 尝试多种方法获取下载链接
    print("\n" + "="*60)
    
    # 方法1: 使用公开API
    print("\n[方法1] 使用kugou.com的play/getdata")
    try:
        url = "https://wwwapi.kugou.com/yy/index.php"
        params = {
            'r': 'play/getdata',
            'hash': song_hash,
            'album_id': 0,
            'dfid': '-',
            'mid': 'd862d862d862d862d862d862d862d862',
            'platid': 4,
            '_': int(time.time() * 1000)
        }
        resp = session.get(url, params=params, timeout=10)
        print(f"状态码: {resp.status_code}")
        print(f"响应: {resp.text[:300]}")
        if resp.status_code == 200:
            data = resp.json()
            if data.get('data'):
                play_url = data['data'].get('play_url', '')
                if play_url:
                    print(f"✅ 找到URL: {play_url}")
                    return
    except Exception as e:
        print(f"错误: {e}")
    
    # 方法2: 尝试使用320hash
    print("\n[方法2] 使用320hash")
    if hash_320:
        try:
            url = "https://wwwapi.kugou.com/yy/index.php"
            params = {
                'r': 'play/getdata',
                'hash': hash_320,
                'album_id': 0,
                'dfid': '-',
                'mid': 'd862d862d862d862d862d862d862d862',
                'platid': 4,
                '_': int(time.time() * 1000)
            }
            resp = session.get(url, params=params, timeout=10)
            print(f"状态码: {resp.status_code}")
            print(f"响应: {resp.text[:300]}")
        except Exception as e:
            print(f"错误: {e}")
    
    # 方法3: 尝试其他API
    print("\n[方法3] 尝试mobileservice接口")
    try:
        url = f"https://mobileservice.kugou.com/api/v2/song/info"
        params = {
            'cmd': 'songinfo',
            'hash': song_hash,
        }
        resp = session.get(url, params=params, timeout=10)
        print(f"状态码: {resp.status_code}")
        print(f"响应: {resp.text[:500]}")
    except Exception as e:
        print(f"错误: {e}")
    
    # 方法4: 尝试直接构造URL
    print("\n[方法4] 尝试直接构造URL")
    direct_urls = [
        f"https://webfs.tx.kugou.com/{song_hash}.mp3",
        f"https://webfs.cloud.kugou.com/{song_hash}.mp3",
        f"https://fs.web.kugou.com/{song_hash}.mp3",
    ]
    
    for url in direct_urls:
        try:
            resp = session.head(url, timeout=5, allow_redirects=True)
            print(f"测试 {url}")
            print(f"  状态码: {resp.status_code}")
            if resp.status_code == 200:
                print(f"  ✅ URL有效!")
                return
        except Exception as e:
            print(f"  错误: {e}")

if __name__ == "__main__":
    test_kugou_download()
