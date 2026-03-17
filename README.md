# 主页地址 https://ohhapple.github.io

# **中文** | [English](README_EN.md)

# MCModifier
> MCModifier

这是一个基于 Java 开发的Minecraft注入器，用于在Minecraft进程外动态修改carpet及其附属mod的规则并且可以执行自定义命令，同时可监控Minecraft控制台

## 📋 运行要求
- Java 版本：≥ 11（JDK 11 及以上版本）
- 操作系统：本程序使用windows系统开发，其他系统未测试
- Minecraft版本：>= 26.1（对应 Carpet Mod 版本需兼容）

## 🚀 使用方法
1. 确保你的电脑已安装 **Java 11 或更高版本**（可在终端/命令行输入 `java -version` 检查版本）；
2. 下载本项目的压缩包并解压；
3. 直接**双击文件MCModifier**即可启动程序。

## ⚠️ 注意事项（必看！！！）
- 若双击无反应：
    1. 检查 Java 版本是否符合要求（需 ≥ 11）；
    2. 若系统未识别 Java，可手动配置环境变量，或通过命令行运行。
    3. 不要移动文件结构，保持所有文件在同一目录。
- 放在中文路径下会导致无法注入，若进程名称存在中文会出现乱码。
- 不要给他人发送日志信息，若Minecraft为正版登录，进程名中会包含玩家uuid和鉴权秘钥等信息，注意保护隐私，后果自负。
- 如遇到BUG，请提issue：https://github.com/ohhapple/MCModifier/issues

## 📄 版权信息
Copyright © 2026 ohhapple. All rights reserved.