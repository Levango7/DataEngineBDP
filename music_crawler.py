#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音乐爬虫脚本（增强版）
支持搜索音乐、获取音乐信息和下载音乐功能
已添加反爬虫机制绕过
"""

import os
import re
import json
import time
import random
import requests
from urllib.parse import quote
from pathlib import Path
from crypto_utils import decrypt_song


class MusicCrawler:
    """音乐爬虫类（增强版）"""
    
    def __init__(self, save_dir="./music"):
        """
        初始化爬虫
        
        Args:
            save_dir: 音乐保存目录
        """
        self.save_dir = Path(save_dir)
        self.save_dir.mkdir(parents=True, exist_ok=True)
        self.session = requests.Session()
        
        # 更完整的请求头，模拟真实浏览器
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'application/json, text/plain, */*',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Connection': 'keep-alive',
            'Referer': 'https://music.163.com/',
            'Origin': 'https://music.163.com',
            'Content-Type': 'application/x-www-form-urlencoded',
        })
        
        # 添加必要的Cookie（模拟登录状态）
        self.session.cookies.update({
            '_ntes_nnid': '7eced8f5a5a4f5f8a5f5a5f5a5f5a5f5',
            '_ntes_nuid': '7eced8f5a5a4f5f8a5f5a5f5a5f5a5f5',
        })
    
    def search_music(self, keyword, limit=10):
        """
        搜索音乐
        
        Args:
            keyword: 搜索关键词
            limit: 返回结果数量
            
        Returns:
            音乐列表
        """
        print(f"正在搜索: {keyword}")
        
        # 使用网易云音乐API（非官方，仅供学习）
        url = "https://music.163.com/api/search/get/web"
        params = {
            's': keyword,
            'type': 1,  # 1: 单曲
            'offset': 0,
            'limit': limit
        }
        
        try:
            response = self.session.post(url, data=params, timeout=10)
            
            # 检查响应状态码
            if response.status_code != 200:
                print(f"网络请求失败，状态码: {response.status_code}")
                print(f"响应内容: {response.text[:500]}")
                return []
            
            # 检查响应内容是否为空或非JSON格式
            if not response.text.strip() or not response.text.startswith('{'):
                print("服务器返回空内容")
                return []
            
            # 尝试解析JSON
            try:
                data = response.json()
            except json.JSONDecodeError as e:
                print(f"JSON解析失败: {e}")
                print(f"原始响应内容: {response.text[:500]}")
                return []
            
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
                        'duration': song.get('duration', 0) // 1000  # 转换为秒
                    })
                return result
            else:
                print(f"搜索失败: {data.get('message', '未知错误')}")
                return []
                
        except requests.RequestException as e:
            print(f"网络请求错误: {e}")
            return []
        except json.JSONDecodeError as e:
            print(f"JSON解析错误: {e}")
            return []
    
    def get_song_url(self, song_id, quality='standard'):
        """
        获取歌曲播放链接（增强版）
        
        Args:
            song_id: 歌曲ID
            quality: 音质 standard(标准), higher(较高), exhigh(极高), lossless(无损)
            
        Returns:
            播放链接和是否需要解密
        """
        br_map = {
            'standard': 128000,
            'higher': 192000,
            'exhigh': 320000,
            'lossless': 999000
        }
        
        # 方法1: 尝试使用官方API获取（需要登录）
        url = "https://music.163.com/weapi/song/enhance/player/url?csrf_token="
        params = {
            'ids': f'[{song_id}]',
            'br': br_map.get(quality, 128000),
            'csrf_token': ''
        }
        
        try:
            response = self.session.post(url, data=params, timeout=10)
            if response.status_code == 200:
                data = response.json()
                if data.get('code') == 200:
                    songs = data.get('data', [])
                    if songs and len(songs) > 0:
                        song_url = songs[0].get('url', '')
                        if song_url and 'http' in song_url:
                            print(f"✅ 获取到下载链接: {song_url[:80]}...")
                            return song_url, False
        except Exception as e:
            print(f"方法1失败: {e}")
        
        # 方法2: 尝试另一个API
        url = f"https://music.163.com/api/song/enhance/player/url"
        params = {
            'ids': f'[{song_id}]',
            'br': br_map.get(quality, 128000)
        }
        
        try:
            response = self.session.post(url, data=params, timeout=10)
            if response.status_code == 200:
                data = response.json()
                if data.get('code') == 200:
                    songs = data.get('data', [])
                    if songs and len(songs) > 0:
                        song_url = songs[0].get('url', '')
                        if song_url and 'http' in song_url:
                            print(f"✅ 获取到下载链接: {song_url[:80]}...")
                            return song_url, False
        except Exception as e:
            print(f"方法2失败: {e}")
        
        # 方法3: 使用外链（可能被限制）
        url = f"https://music.163.com/song/media/outer/url?id={song_id}.mp3"
        print(f"⚠️ 使用外链下载（可能受限）: {url}")
        return url, False
    
    def download_music(self, song_info, show_progress=True, quality='standard'):
        """
        下载音乐（增强版）
        
        Args:
            song_info: 歌曲信息字典，包含id, name, artist等
            show_progress: 是否显示下载进度
            quality: 音质选择
            
        Returns:
            保存路径，失败返回None
        """
        song_id = song_info['id']
        song_name = song_info['name']
        artist = song_info.get('artist', '未知歌手')
        
        # 清理文件名中的非法字符
        safe_name = re.sub(r'[<>:"/\\|?*]', '', f"{artist} - {song_name}")
        file_path = self.save_dir / f"{safe_name}.mp3"
        
        if file_path.exists():
            file_size = file_path.stat().st_size
            if file_size > 100000:  # 大于100KB才认为有效
                print(f"文件已存在: {file_path}")
                return str(file_path)
            else:
                print(f"文件可能损坏（{file_size}字节），重新下载...")
                file_path.unlink()
        
        print(f"正在下载: {artist} - {song_name}")
        
        try:
            # 添加随机延迟，避免请求过快
            time.sleep(random.uniform(0.5, 1.5))
            
            # 获取下载链接
            download_url, need_decrypt = self.get_song_url(song_id, quality)
            
            if not download_url:
                print("无法获取下载链接，可能因版权限制")
                return None
            
            # 发送请求
            response = self.session.get(download_url, stream=True, timeout=30, allow_redirects=True)
            
            # 检查响应
            if response.status_code == 200:
                # 检查内容类型
                content_type = response.headers.get('content-type', '')
                if 'audio' not in content_type and 'application/octet-stream' not in content_type:
                    print(f"错误: 响应类型异常 ({content_type})，不是音频文件")
                    # 尝试读取响应内容判断是否是HTML错误页
                    try:
                        text_preview = response.text[:500]
                        if '<!DOCTYPE' in text_preview or '<html' in text_preview:
                            print(f"服务器返回HTML页面: {text_preview[:200]}...")
                        else:
                            print(f"响应内容: {text_preview[:200]}...")
                    except:
                        pass
                    return None
                
                total_size = int(response.headers.get('content-length', 0))
                downloaded = 0
                
                # 先下载到临时文件
                temp_path = file_path.with_suffix('.tmp')
                
                with open(temp_path, 'wb') as f:
                    for chunk in response.iter_content(chunk_size=8192):
                        if chunk:
                            f.write(chunk)
                            downloaded += len(chunk)
                            if show_progress and total_size > 0:
                                progress = (downloaded / total_size) * 100
                                print(f"\r下载进度: {progress:.1f}%", end='')
                
                if show_progress:
                    print()  # 换行
                
                # 检查文件大小
                actual_size = temp_path.stat().st_size
                if actual_size < 100000:  # 小于100KB
                    print(f"警告: 文件过小（{actual_size}字节），可能下载失败")
                    # 读取前几个字节检查是否是HTML
                    with open(temp_path, 'rb') as f:
                        header = f.read(100)
                        if b'<!DOCTYPE' in header or b'<html' in header:
                            print("错误: 下载的是HTML页面，不是音频文件")
                            temp_path.unlink()
                            return None
                
                # 如果需要解密（部分加密音乐）
                if need_decrypt:
                    print("正在解密音频文件...")
                    decrypt_song(temp_path, file_path)
                    temp_path.unlink()
                else:
                    temp_path.rename(file_path)
                
                print(f"下载完成: {file_path} ({actual_size/1024/1024:.2f} MB)")
                return str(file_path)
            else:
                print(f"下载失败，状态码: {response.status_code}")
                return None
                
        except requests.RequestException as e:
            print(f"下载错误: {e}")
            return None
        except Exception as e:
            print(f"未知错误: {e}")
            return None
    
    def display_songs(self, songs):
        """
        显示歌曲列表
        
        Args:
            songs: 歌曲列表
        """
        if not songs:
            print("没有找到相关音乐")
            return
        
        print("\n" + "="*60)
        print(f"{'序号':<6}{'歌曲名':<25}{'歌手':<20}{'时长':<8}")
        print("="*60)
        
        for idx, song in enumerate(songs, 1):
            duration = f"{song['duration']//60}:{song['duration']%60:02d}"
            name = song['name'][:22] + '...' if len(song['name']) > 22 else song['name']
            artist = song['artist'][:17] + '...' if len(song['artist']) > 17 else song['artist']
            print(f"{idx:<6}{name:<25}{artist:<20}{duration:<8}")
        
        print("="*60 + "\n")


def main():
    """主函数"""
    print("="*60)
    print("          音乐爬虫脚本 - Music Crawler")
    print("="*60)
    print()
    
    # 创建爬虫实例
    crawler = MusicCrawler(save_dir="./downloaded_music")
    
    while True:
        try:
            # 获取搜索关键词
            keyword = input("请输入要搜索的音乐名称（输入 q 退出）: ").strip()
            
            if keyword.lower() == 'q':
                print("感谢使用，再见！")
                break
            
            if not keyword:
                print("关键词不能为空")
                continue
            
            # 搜索音乐
            songs = crawler.search_music(keyword, limit=10)
            
            if not songs:
                continue
            
            # 显示搜索结果
            crawler.display_songs(songs)
            
            # 选择下载
            choice = input("输入序号下载对应歌曲（输入 a 下载全部，n 取消）: ").strip().lower()
            
            if choice == 'n':
                continue
            elif choice == 'a':
                # 下载全部
                for song in songs:
                    crawler.download_music(song)
            else:
                # 下载指定歌曲
                try:
                    idx = int(choice) - 1
                    if 0 <= idx < len(songs):
                        crawler.download_music(songs[idx])
                    else:
                        print("序号超出范围")
                except ValueError:
                    print("请输入有效的序号")
            
            print()
            
        except KeyboardInterrupt:
            print("\n\n程序已中断")
            break
        except Exception as e:
            print(f"发生错误: {e}")


if __name__ == "__main__":
    main()
