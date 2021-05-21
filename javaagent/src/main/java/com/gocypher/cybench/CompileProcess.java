package com.gocypher.cybench;

import static com.gocypher.cybench.BenchmarkTest.log;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CompileProcess {

    static void printLines(String cmd, InputStream ins) throws Exception {
        String line;
        BufferedReader in = new BufferedReader(new InputStreamReader(ins));
        while ((line = in.readLine()) != null) {
            log(cmd + " " + line);
        }
    }

    static void runProcess(String command) throws Exception {
        log("Running command: " + command);
        Process pro = Runtime.getRuntime().exec(command);
        printLines(command + " stdout:", pro.getInputStream());
        printLines(command + " stderr:", pro.getErrorStream());
        pro.waitFor();
        log(command + " exitValue() " + pro.exitValue());
    }

    static class WindowsCompileProcess extends CompileProcess {
        static final String COMPILE = "javac -cp <CLASSPATH> @";

        public WindowsCompileProcess() {
            ClassLoader classloader = ClassLoader.getSystemClassLoader();
            URL[] urls = ((URLClassLoader) classloader).getURLs();
            String cp = Stream.of(urls).map(u -> u.getPath()).map(s -> s.substring(1)).peek(System.out::println)
                    .collect(Collectors.joining(System.getProperty("path.separator")));

            try {
                String s = makeSourcesList();
                CompileProcess.runProcess(COMPILE.replace("<CLASSPATH>", cp) + s);
                // runProcess(CLEANUP);
            } catch (Exception e) {
                log("Cannot run compile");
                e.printStackTrace();
            }
        }

        private static String makeSourcesList() {
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
                File f = File.createTempFile("sourcesList", "");
                // f.deleteOnExit();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    Files.walk(Paths.get(System.getProperty("buildDir") + "/..")).filter(fw -> matcher.matches(fw))
                            .filter(Files::isRegularFile).forEach(fw -> {
                                try {
                                    fos.write(fw.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
                                    fos.write('\n');
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            });
                    fos.flush();
                }
                log("Created sources file" + f.getAbsolutePath());

                return f.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }
    }
}
