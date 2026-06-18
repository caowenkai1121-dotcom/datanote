package com.datanote.util;

import com.datanote.domain.orchestration.util.ProcessUtil;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessUtilTest {

    @Test
    void execTimesOutEvenWhenProcessEmitsNoOutput() throws Exception {
        long start = System.currentTimeMillis();

        ProcessUtil.ExecResult result = ProcessUtil.exec(new String[] {
                javaBin(), "-cp", System.getProperty("java.class.path"),
                Sleeper.class.getName(), "5000"
        }, 1);

        assertEquals(143, result.getExitCode());
        assertTrue(System.currentTimeMillis() - start < 4000);
    }

    @Test
    void execOutputIsTruncatedToTail() throws Exception {
        ProcessUtil.ExecResult result = ProcessUtil.exec(new String[] {
                javaBin(), "-cp", System.getProperty("java.class.path"),
                OutputFlood.class.getName()
        }, 5);

        assertEquals(0, result.getExitCode());
        assertTrue(result.getOutput().length() <= 1024 * 1024 + 128);
        assertTrue(result.getOutput().contains("[output truncated"));
        assertTrue(result.getOutput().contains("tail-marker"));
    }

    private static String javaBin() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + exe;
    }

    public static class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args[0]));
        }
    }

    public static class OutputFlood {
        public static void main(String[] args) {
            for (int i = 0; i < 120000; i++) {
                System.out.println("line-" + i + "-abcdefghijklmnopqrstuvwxyz");
            }
            System.out.println("tail-marker");
        }
    }
}
