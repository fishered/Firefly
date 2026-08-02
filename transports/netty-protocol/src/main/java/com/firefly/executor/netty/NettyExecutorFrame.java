package com.firefly.executor.netty;

/** Typed protocol body decoded from a versioned executor message. */
public sealed interface NettyExecutorFrame
        permits RegisterExecutorFrame, AckJobFrame, ReportResultFrame {
}
