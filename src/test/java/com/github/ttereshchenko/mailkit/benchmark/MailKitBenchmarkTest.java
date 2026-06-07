package com.github.ttereshchenko.mailkit.benchmark;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.pst.Message;
import com.github.ttereshchenko.mailkit.conversion.pst.PstFile;
import com.github.ttereshchenko.mailkit.conversion.pst.PstToEmlConverter;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.Test;

public class MailKitBenchmarkTest extends BasePlatformTestCase {

    @Test
    public void testMailKitConversion() throws Exception {
        Path samplesPst = Paths.get(".tmp/samples/pst").toAbsolutePath();
        Path samplesOst = Paths.get(".tmp/samples/ost").toAbsolutePath();
        Path outputDir = Paths.get(".tmp/benchmark/out/mailkit").toAbsolutePath();

        PstToEmlConverter.Options options = new PstToEmlConverter.Options(
                PstToEmlConverter.DuplicateHandling.SUFFIX_COUNTER,
                null,
                true,
                true,
                Message.AddressPreference.PREFER_SMTP,
                true,
                true,
                100 * 1024 * 1024);

        processDirectory(samplesPst, outputDir, options);
        processDirectory(samplesOst, outputDir, options);
    }

    private void processDirectory(Path sourceDir, Path outputBaseDir, PstToEmlConverter.Options options)
            throws IOException {
        if (!Files.exists(sourceDir)) return;
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".pst") || fileName.endsWith(".ost")) {
                    Path targetDir = outputBaseDir.resolve(fileName);
                    System.out.println("Processing with MailKit: " + fileName);
                    try (PstFile pstFile = new PstFile(file, options.maxNodeSize())) {
                        PstToEmlConverter.convert(
                                pstFile, targetDir, options, new EmptyProgressIndicator(), ConversionLog.NOOP);
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                }
            });
        }
    }
}
