package com.datanote.domain.orchestration.util;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 外部进程执行工具 — 封装 ProcessBuilder 调用及超时控制
 */
public class ProcessUtil {

    private static final Logger log = LoggerFactory.getLogger(ProcessUtil.class);
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;
    private static final int OUTPUT_TRIM_BUFFER_CHARS = 64 * 1024;

    /**
     * 命令执行结果
     */
    @Data
    public static class ExecResult {
        private int exitCode;
        private String output;
        private long durationMs;
    }

    /**
     * 执行外部命令，返回结果
     *
     * @param cmd        命令数组
     * @param timeoutSec 超时时间（秒）
     */
    public static ExecResult exec(String[] cmd, int timeoutSec) throws Exception {
        return exec(cmd, timeoutSec, false);
    }

    /**
     * @param sanitizeEnv true 时清除子进程环境中的敏感变量(含 PASSWORD/SECRET/TOKEN/KEY 及
     *                    DATANOTE_ 、DB_ 、REDIS_ 前缀), 防 Shell 任务经 env 读取系统凭据(P1-03 强约束)。
     */
    public static ExecResult exec(String[] cmd, int timeoutSec, boolean sanitizeEnv) throws Exception {
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (sanitizeEnv) {
            pb.environment().keySet().removeIf(k -> {
                String u = k == null ? "" : k.toUpperCase();
                return u.contains("PASSWORD") || u.contains("SECRET") || u.contains("TOKEN")
                        || u.contains("KEY") || u.contains("PWD")
                        || u.startsWith("DATANOTE_") || u.startsWith("DB_") || u.startsWith("REDIS_");
            });
        }
        Process process = pb.start();

        ExecutorService outputReader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "process-output-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> outputFuture = outputReader.submit(() -> readOutput(process));

        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("命令执行超时（{}秒），已强制终止", timeoutSec);
        }
        String output = readOutputResult(outputFuture);
        outputReader.shutdownNow();

        ExecResult result = new ExecResult();
        result.setExitCode(finished ? process.exitValue() : 143);
        result.setOutput(output);
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private static String readOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > MAX_OUTPUT_CHARS + OUTPUT_TRIM_BUFFER_CHARS) {
                    truncated = true;
                    sb.delete(0, sb.length() - MAX_OUTPUT_CHARS);
                }
            }
        }
        if (sb.length() > MAX_OUTPUT_CHARS) {
            truncated = true;
            sb.delete(0, sb.length() - MAX_OUTPUT_CHARS);
        }
        return truncated ? "[output truncated to last 1048576 chars]\n" + sb : sb.toString();
    }

    private static String readOutputResult(Future<String> outputFuture) {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            outputFuture.cancel(true);
            return "";
        }
    }
}
