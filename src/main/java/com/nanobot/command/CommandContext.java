package com.nanobot.command;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.session.Session;

/**
 * 命令上下文：封装命令处理器所需的所有信息
 *
 * <p>每个命令执行时都会创建一个 CommandContext 实例，包含：
 * <ul>
 *   <li>原始消息对象：用于获取渠道、聊天 ID 等元数据</li>
 *   <li>会话对象：用于访问和修改会话状态</li>
 *   <li>命令名：清理后的命令 key（如 "compact"）</li>
 *   <li>原始输入：用户输入的完整文本（如 "/compact arg1"）</li>
 *   <li>命令参数：命令名之后的所有内容（如 "arg1"）</li>
 * </ul>
 */
public record CommandContext(
        InboundMessage msg,      // 原始入站消息
        Session session,         // 当前会话
        String key,              // 清理后的命令名，如 "compact"（不含 / 前缀）
        String raw,              // 用户原始输入
        String args              // 命令参数：命令名之后的所有内容
) {}
