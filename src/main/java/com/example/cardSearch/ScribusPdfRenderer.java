package com.example.cardSearch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ScribusPdfRenderer {
    private final String executable;
    public ScribusPdfRenderer(@Value("${cards.pdf.scribus-command:scribus}") String executable) {
        this.executable = executable;
    }

    public void render(Path manifest, Path directory) throws Exception {
        var command = new ArrayList<String>();
        if (!System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("xvfb-run", "-a", "-e", "/dev/stderr"));
        command.addAll(List.of(executable, "--no-gui", "--no-splash", "--python-script",
                directory.resolve("render.py").toString(), manifest.toString()));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("scribus.log").toFile());
        builder.environment().put("HOME", directory.toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                throw new IOException("PDF rendering took too long. Please try again.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(directory.resolve("render-success.json"))) {
                throw new IOException("Scribus could not generate the PDFs. Check the export log or try again.");
            }
        } finally {
            process.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
