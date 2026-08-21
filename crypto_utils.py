#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网易云音乐解密工具
用于解密部分加密的音频文件
"""

import os
from pathlib import Path


def decrypt_song(input_file, output_file):
    """
    解密网易云音乐加密文件
    
    Args:
        input_file: 加密的输入文件路径
        output_file: 解密后的输出文件路径
    """
    # 网易云音乐的简单解密算法
    # 对于NCM格式文件需要更复杂的解密
    # 这里只处理简单的异或加密
    
    try:
        with open(input_file, 'rb') as f_in:
            data = f_in.read()
        
        # 检查是否是NCM格式
        if data[:8] == b'CTENFDAM':
            print("检测到NCM格式，需要完整解密（暂不支持）")
            # 这里只是简单复制，实际需要完整解密算法
            with open(output_file, 'wb') as f_out:
                f_out.write(data)
            return
        
        # 简单的异或解密（示例）
        # 实际网易云的加密更复杂
        key = 0x64  # 示例密钥
        decrypted = bytes([b ^ key for b in data])
        
        with open(output_file, 'wb') as f_out:
            f_out.write(decrypted)
            
    except Exception as e:
        print(f"解密失败: {e}")
        # 如果解密失败，直接复制文件
        with open(input_file, 'rb') as f_in:
            with open(output_file, 'wb') as f_out:
                f_out.write(f_in.read())


if __name__ == "__main__":
    # 测试解密
    print("网易云音乐解密工具")
    print("注意：此工具仅供学习研究使用")
