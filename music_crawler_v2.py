#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音乐爬虫脚本 V2 - 多平台支持版
支持网易云音乐、酷狗音乐、酷我音乐等多个平台
解决网易云外链失效问题
"""

import os
import re
import json
import time
import random
import requests
from urllib.parse import quote
from pathlib import Path


class MusicCrawlerV2:
    """多平台音乐爬虫"""
    
    def __init__(self, save_dir="./downloaded_music"):
        self.save_dir = Path(save_dir)
        self.save_dir.mkdir(parents=True, exist_ok=True)
        self.session = requests.Session()
        
        # 设置请求头
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/plain, */*',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Connection': 'keep-alive',
        })
    
    def search_kugou(self, keyword, limit=10):
        """
        从酷狗音乐搜索歌曲
        酷狗音乐的接口相对开放
        """
        print(f"[酷狗] 搜索: {keyword}")
        
        url = "https://mobileservice.kugou.com/api/v3/search/song"
        params = {
            'format': 'json',
            'keyword': keyword,
            'page': 1,
            'pagesize': limit,
            'showtype': 1
        }
        
        try:
            response = self.session.get(url, params=params, timeout=10)
            if response.status_code == 200:
                data = response.json()
                songs = data.get('data', {}).get('info', [])
                
                result = []
                for song in songs:
                    result.append({
                        'id': song.get('hash', ''),
                        'name': song.get('songname', ''),
                        'artist': song.get('singername', ''),
                        'album': song.get('album_name', ''),
                        'duration': song.get('duration', 0) // 1000,
                        'platform': 'kugou',
                        'hash': song.get('hash', '')
                    })
                return result
        except Exception as e:
            print(f"[酷狗] 搜索失败: {e}")
        
        return []
    
    def search_kuwo(self, keyword, limit=10):
        """
        从酷我音乐搜索歌曲
        """
        print(f"[酷我] 搜索: {keyword}")
        
        # 方法1: 尝试新API
        url = "http://www.kuwo.cn/api/www/search/searchMusicBykeyWord"
        params = {
            'type': 'music',
            'httpAccept': '*/*',
            'key': keyword,
            'pn': 1,
            'rn': limit
        }
        
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Referer': 'http://www.kuwo.cn/search/list?key=' + quote(keyword),
            'Cookie': 'Hm_lvt_a8051b06c822ec5f6a8f96d2b7a9c73d=1700000000; kw_token=TEST123456'
        }
        
        try:
            response = self.session.get(url, params=params, headers=headers, timeout=10)
            if response.status_code == 200:
                data = response.json()
                songs = data.get('data', {}).get('list', [])
                
                if songs:
                    result = []
                    for song in songs:
                        result.append({
                            'id': song.get('rid', 0),
                            'name': song.get('name', ''),
                            'artist': song.get('artist', ''),
                            'album': song.get('album', ''),
                            'duration': 0,
                            'platform': 'kuwo'
                        })
                    return result
        except Exception as e:
            print(f"  酷我方法1失败: {e}")
        
        # 方法2: 尝试移动端API
        try:
            url = "http://m.kuwo.cn/newh5app/api/search/searchkey"
            params = {
                'key': keyword,
                'pn': 1,
                'rn': limit
            }
            response = self.session.get(url, params=params, timeout=10)
            if response.status_code == 200:
                data = response.json()
                songs = data.get('abslist', [])
                
                if songs:
                    result = []
                    for song in songs:
                        result.append({
                            'id': song.get('MUSICRID', '').split('_')[1] if '_' in song.get('MUSICRID', '') else 0,
                            'name': song.get('SONGNAME', ''),
                            'artist': song.get('ARTIST', ''),
                            'album': song.get('ALBUM', ''),
                            'duration': 0,
                            'platform': 'kuwo'
                        })
                    return result
        except Exception as e:
            print(f"  酷我方法2失败: {e}")
        
        return []
    
    def search_netease(self, keyword, limit=10):
        """
        从网易云音乐搜索歌曲
        """
        print(f"[网易] 搜索: {keyword}")
        
        url = "https://music.163.com/api/search/get/web"
        params = {
            's': keyword,
            'type': 1,
            'offset': 0,
            'limit': limit
        }
        
        try:
            response = self.session.post(url, data=params, timeout=10)
            if response.status_code == 200 and response.text.strip():
                data = response.json()
                if data.get('code') == 200:
                    songs = data.get('result', {}).get('songs', [])
                    
                    result = []
                    for song in songs:
                        artists = ', '.join([ar['name'] for ar in song.get('artists', [])])
                        result.append({
                            'id': song['id'],
                            'name': song['name'],
                            'artist': artists,
                            'album': song.get('album', {}).get('name', ''),
                            'duration': song.get('duration', 0) // 1000,
                            'platform': 'netease'
                        })
                    return result
        except Exception as e:
            print(f"[网易] 搜索失败: {e}")
        
        return []
    
    def search_all(self, keyword, limit=5):
        """从所有平台搜索"""
        print(f"\n{'='*60}")
        print(f"多平台搜索: {keyword}")
        print(f"{'='*60}")
        
        all_songs = []
        
        # 尝试各个平台
        platforms = [
            ('酷狗音乐', self.search_kugou),
            ('酷我音乐', self.search_kuwo),
            ('网易云音乐', self.search_netease),
        ]
        
        for name, search_func in platforms:
            try:
                songs = search_func(keyword, limit)
                if songs:
                    print(f"  ✅ {name}: 找到 {len(songs)} 首")
                    all_songs.extend(songs)
                else:
                    print(f"  ❌ {name}: 无结果")
            except Exception as e:
                print(f"  ❌ {name}: 错误 - {e}")
        
        return all_songs
    
    def get_kugou_url(self, song_hash):
        """
        获取酷狗音乐下载链接
        酷狗的接口相对开放
        """
        # 方法1: 尝试新接口
        url = "https://wwwapi.kugou.com/yy/index.php"
        params = {
            'r': 'play/getdata',
            'hash': song_hash
        }
        
        try:
            response = self.session.get(url, params=params, timeout=10)
            if response.status_code == 200:
                data = response.json()
                # 处理不同的响应格式
                if isinstance(data, dict):
                    # data可能是字典或列表
                    data_content = data.get('data', {})
                    if isinstance(data_content, dict):
                        audio_url = data_content.get('play_url', '') or data_content.get('url', '')
                        if audio_url and 'http' in audio_url:
                            return audio_url
                    elif isinstance(data_content, list) and len(data_content) > 0:
                        # 如果data是列表，取第一个元素
                        first_item = data_content[0] if isinstance(data_content[0], dict) else {}
                        audio_url = first_item.get('play_url', '') or first_item.get('url', '')
                        if audio_url and 'http' in audio_url:
                            return audio_url
        except Exception as e:
            print(f"  酷狗方法1失败: {e}")
        
        # 方法2: 尝试另一个接口
        url = f"https://wwwapi.kugou.com/play/songinfo?hash={song_hash}"
        try:
            response = self.session.get(url, timeout=10)
            if response.status_code == 200:
                data = response.json()
                if isinstance(data, dict):
                    audio_url = data.get('url', '') or data.get('play_url', '')
                    if audio_url and 'http' in audio_url:
                        return audio_url
        except Exception as e:
            print(f"  酷狗方法2失败: {e}")
        
        # 方法3: 尝试直接构造下载链接
        try:
            url = f"https://webfs.tx.kugou.com/{song_hash}.mp3"
            # 测试链接是否有效
            response = self.session.head(url, timeout=5)
            if response.status_code == 200:
                return url
        except:
            pass
        
        return None
    
    def get_kuwo_url(self, song_id):
        """
        获取酷我音乐下载链接
        """
        url = f"http://www.kuwo.cn/url?format=mp3&rid={song_id}&response=url&type=convert_url3&br=128kmp3"
        
        try:
            response = self.session.get(url, timeout=10)
            if response.status_code == 200:
                audio_url = response.text.strip()
                if audio_url and 'http' in audio_url:
                    return audio_url
        except Exception as e:
            print(f"获取酷我链接失败: {e}")
        
        return None
    
    def get_netease_url(self, song_id):
        """
        获取网易云音乐下载链接
        注意：网易云的下载接口限制较多
        """
        # 尝试API获取
        url = "https://music.163.com/api/song/enhance/player/url"
        params = {
            'ids': f'[{song_id}]',
            'br': 320000
        }
        
        try:
            response = self.session.post(url, data=params, timeout=10)
            if response.status_code == 200 and response.text.strip():
                data = response.json()
                if data.get('code') == 200:
                    songs = data.get('data', [])
                    if songs:
                        audio_url = songs[0].get('url', '')
                        if audio_url and 'http' in audio_url:
                            return audio_url
        except:
            pass
        
        print("  ⚠️ 网易云音乐下载受限，可能需要VIP或登录")
        return None
    
    def download_song(self, song_info, show_progress=True):
        """
        下载歌曲
        """
        platform = song_info.get('platform', '')
        song_name = song_info['name']
        artist = song_info.get('artist', '未知歌手')
        
        print(f"\n正在下载 [{platform}]: {artist} - {song_name}")
        
        # 获取下载链接
        audio_url = None
        
        if platform == 'kugou':
            audio_url = self.get_kugou_url(song_info.get('hash', ''))
        elif platform == 'kuwo':
            audio_url = self.get_kuwo_url(song_info.get('id', 0))
        elif platform == 'netease':
            audio_url = self.get_netease_url(song_info.get('id', 0))
        
        if not audio_url:
            print(f"  ❌ 无法获取下载链接")
            return None
        
        print(f"  ✅ 获取到下载链接")
        
        # 清理文件名
        safe_name = re.sub(r'[<>:"/\\|?*]', '', f"{artist} - {song_name}")
        file_path = self.save_dir / f"{safe_name}.mp3"
        
        # 下载文件
        try:
            time.sleep(random.uniform(0.5, 1.5))
            
            response = self.session.get(audio_url, stream=True, timeout=30, allow_redirects=True)
            
            if response.status_code != 200:
                print(f"  ❌ 下载失败，状态码: {response.status_code}")
                return None
            
            # 检查内容类型
            content_type = response.headers.get('content-type', '')
            if 'audio' not in content_type and 'application/octet-stream' not in content_type:
                print(f"  ❌ 响应类型错误: {content_type}")
                return None
            
            # 下载到临时文件
            temp_path = file_path.with_suffix('.tmp')
            total_size = int(response.headers.get('content-length', 0))
            downloaded = 0
            
            with open(temp_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if show_progress and total_size > 0:
                            progress = (downloaded / total_size) * 100
                            print(f"\r  下载进度: {progress:.1f}%", end='')
            
            if show_progress:
                print()
            
            # 验证文件
            actual_size = temp_path.stat().st_size
            if actual_size < 50000:
                print(f"  ❌ 文件过小 ({actual_size} 字节)，可能下载失败")
                temp_path.unlink()
                return None
            
            # 检查文件头
            with open(temp_path, 'rb') as f:
                header = f.read(10)
                if b'<!DOCTYPE' in header or b'<html' in header:
                    print("  ❌ 下载的是HTML页面，不是音频文件")
                    temp_path.unlink()
                    return None
            
            # 重命名临时文件
            temp_path.rename(file_path)
            print(f"  ✅ 下载完成: {file_path.name} ({actual_size/1024/1024:.2f} MB)")
            return str(file_path)
            
        except Exception as e:
            print(f"  ❌ 下载失败: {e}")
            return None
    
    def display_songs(self, songs, start_idx=1):
        """显示歌曲列表"""
        if not songs:
            print("没有找到歌曲")
            return
        
        print(f"\n{'='*60}")
        print(f"{'序号':<6} {'平台':<8} {'歌曲名':<25} {'歌手':<20}")
        print(f"{'='*60}")
        
        for i, song in enumerate(songs, start_idx):
            name = song['name'][:22] + '...' if len(song['name']) > 25 else song['name']
            artist = song['artist'][:17] + '...' if len(song['artist']) > 20 else song['artist']
            platform = song.get('platform', '未知')
            print(f"{i:<6} {platform:<8} {name:<25} {artist:<20}")


def main():
    """主函数"""
    print("\n" + "="*60)
    print("     音乐爬虫 V2 - 多平台支持版")
    print("="*60)
    print("\n支持平台: 酷狗音乐、酷我音乐、网易云音乐")
    print("提示: 酷狗和酷我的下载成功率较高")
    print("\n输入 'q' 退出\n")
    
    crawler = MusicCrawlerV2(save_dir="./downloaded_music")
    
    while True:
        try:
            keyword = input("\n请输入要搜索的音乐名称: ").strip()
            
            if keyword.lower() == 'q':
                print("\n感谢使用，再见！")
                break
            
            if not keyword:
                print("请输入有效的搜索关键词")
                continue
            
            # 搜索歌曲
            songs = crawler.search_all(keyword, limit=5)
            
            if not songs:
                print("\n未找到相关歌曲，请尝试其他关键词")
                continue
            
            # 显示结果
            crawler.display_songs(songs)
            
            # 选择下载
            print(f"\n{'='*60}")
            choice = input("输入序号下载对应歌曲 (输入 'a' 下载全部, 'n' 新搜索): ").strip().lower()
            
            if choice == 'n':
                continue
            elif choice == 'a':
                # 下载全部
                success = 0
                for song in songs:
                    if crawler.download_song(song):
                        success += 1
                print(f"\n下载完成: 成功 {success}/{len(songs)} 首")
            else:
                # 下载指定歌曲
                try:
                    idx = int(choice) - 1
                    if 0 <= idx < len(songs):
                        crawler.download_song(songs[idx])
                    else:
                        print("序号超出范围")
                except ValueError:
                    print("请输入有效的序号")
        
        except KeyboardInterrupt:
            print("\n\n已取消操作")
            break
        except Exception as e:
            print(f"\n发生错误: {e}")


if __name__ == "__main__":
    main()
