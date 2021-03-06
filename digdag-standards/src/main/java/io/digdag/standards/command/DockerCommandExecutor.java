package io.digdag.standards.command;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import com.google.inject.Inject;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.hash.Hashing;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.TaskRequest;
import io.digdag.client.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.util.Locale.ENGLISH;
import static java.nio.charset.StandardCharsets.UTF_8;

public class DockerCommandExecutor
    implements CommandExecutor
{
    private final SimpleCommandExecutor simple;

    private static Logger logger = LoggerFactory.getLogger(DockerCommandExecutor.class);

    @Inject
    public DockerCommandExecutor(SimpleCommandExecutor simple)
    {
        this.simple = simple;
    }

    public Process start(Path projectPath, TaskRequest request, ProcessBuilder pb)
        throws IOException
    {
        // TODO set TZ environment variable
        Config config = request.getConfig();
        if (config.has("docker")) {
            return startWithDocker(projectPath, request, pb);
        }
        else {
            return simple.start(projectPath.toAbsolutePath(), request, pb);
        }
    }

    private Process startWithDocker(Path projectPath, TaskRequest request, ProcessBuilder pb)
    {
        Config dockerConfig = request.getConfig().getNestedOrGetEmpty("docker");
        String baseImageName = dockerConfig.get("image", String.class);

        String imageName;
        if (dockerConfig.has("build")) {
            List<String> buildCommands = dockerConfig.getList("build", String.class);
            imageName = uniqueImageName(request, baseImageName, buildCommands);
            buildImage(imageName, projectPath, baseImageName, buildCommands);
        }
        else {
            imageName = baseImageName;
            if (dockerConfig.get("pull_always", Boolean.class, false)) {
                pullImage(imageName);
            }
        }

        ImmutableList.Builder<String> command = ImmutableList.builder();
        command.add("docker").add("run");

        try {
            // misc
            command.add("-i");  // enable stdin
            command.add("--rm");  // remove container when exits

            // mount
            command.add("-v").add(String.format(ENGLISH,
                        "%s:%s:rw", projectPath, projectPath));  // use projectPath to keep pb.directory() valid

            // workdir
            Path workdir = (pb.directory() == null) ? Paths.get("") : pb.directory().toPath();
            command.add("-w").add(workdir.normalize().toAbsolutePath().toString());

            logger.debug("Running in docker: {} {}", command.build().stream().collect(Collectors.joining(" ")), imageName);

            // env var
            // TODO deleting temp file right after start() causes "no such file or directory." error
            // because command execution is asynchronous. but using command-line is insecure.
            //Path envFile = Files.createTempFile("docker-env-", ".list");
            //tempFiles.add(envFile);
            //try (BufferedWriter out = Files.newBufferedWriter(envFile)) {
            //    for (Map.Entry<String, String> pair : pb.environment().entrySet()) {
            //        out.write(pair.getKey());
            //        out.write("=");
            //        out.write(pair.getValue());
            //        out.newLine();
            //    }
            //}
            //command.add("--env-file").add(envFile.toAbsolutePath().toString());
            for (Map.Entry<String, String> pair : pb.environment().entrySet()) {
                command.add("-e").add(pair.getKey() + "=" + pair.getValue());
            }

            // image name
            command.add(imageName);

            // command and args
            command.addAll(pb.command());

            ProcessBuilder docker = new ProcessBuilder(command.build());
            docker.redirectError(pb.redirectError());
            docker.redirectErrorStream(pb.redirectErrorStream());
            docker.redirectInput(pb.redirectInput());
            docker.redirectOutput(pb.redirectOutput());
            docker.directory(projectPath.toFile());

            return docker.start();
        }
        catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private static String uniqueImageName(TaskRequest request,
            String baseImageName, List<String> buildCommands)
    {
        // Name should include project "id" for security reason because
        // conflicting SHA1 hash means that attacker can reuse an image
        // built by someone else.
        String name = "digdag-project-" + Integer.toString(request.getProjectId());

        Config config = request.getConfig().getFactory().create();
        config.set("image", baseImageName);
        config.set("build", buildCommands);
        config.set("revision", request.getRevision().or(UUID.randomUUID().toString()));
        String tag = Hashing.sha1().hashString(config.toString(), UTF_8).toString();

        return name + ':' + tag;
    }

    private void buildImage(String imageName, Path projectPath,
            String baseImageName, List<String> buildCommands)
    {
        try {
            String[] nameTag = imageName.split(":", 2);
            Pattern pattern;
            if (nameTag.length > 1) {
                pattern = Pattern.compile("\n" + Pattern.quote(nameTag[0]) + " +" + Pattern.quote(nameTag[1]));
            }
            else {
                pattern = Pattern.compile("\n" + Pattern.quote(imageName) + " ");
            }

            int ecode;
            String message;
            try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                ProcessBuilder pb = new ProcessBuilder("docker", "images");
                pb.redirectErrorStream(true);
                Process p = pb.start();

                // read stdout to buffer
                try (InputStream stdout = p.getInputStream()) {
                    ByteStreams.copy(stdout, buffer);
                }

                ecode = p.waitFor();
                message = buffer.toString();
            }

            Matcher m = pattern.matcher(message);
            if (m.find()) {
                // image is already available
                logger.debug("Reusing docker image {}", imageName);
                return;
            }
        }
        catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }

        logger.info("Building docker image {}", imageName);
        try {
            // create Dockerfile
            Path tmpPath = projectPath.resolve(".digdag/tmp/docker");  // TODO this should be configurable
            Files.createDirectories(tmpPath);
            Path dockerFilePath = tmpPath.resolve("Dockerfile." + imageName.replaceAll(":", "."));

            try (BufferedWriter out = Files.newBufferedWriter(dockerFilePath)) {
                out.write("FROM ");
                out.write(baseImageName.replace("\n", ""));
                out.write("\n");

                // Here shouldn't use 'ADD' because it spoils caching. Using the same base image
                // and build commands should share pre-build revisions. Using revision name
                // as the unique key is not good enough for local mode because revision name
                // is automatically generated based on execution time.

                for (String command : buildCommands) {
                    for (String line : command.split("\n")) {
                        out.write("RUN ");
                        out.write(line);
                        out.write("\n");
                    }
                }
            }

            ImmutableList.Builder<String> command = ImmutableList.builder();
            command.add("docker").add("build");
            command.add("-f").add(dockerFilePath.toString());
            command.add("--force-rm");
            command.add("-t").add(imageName);
            command.add(projectPath.toString());

            ProcessBuilder docker = new ProcessBuilder(command.build());
            docker.redirectError(ProcessBuilder.Redirect.INHERIT);
            docker.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            docker.directory(projectPath.toFile());

            Process p = docker.start();
            int ecode = p.waitFor();
            if (ecode != 0) {
                throw new RuntimeException("Docker build failed");
            }
        }
        catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void pullImage(String imageName)
    {
        logger.info("Pulling docker image {}", imageName);
        try {
            ImmutableList.Builder<String> command = ImmutableList.builder();
            command.add("docker").add("pull").add(imageName);

            ProcessBuilder docker = new ProcessBuilder(command.build());
            docker.redirectError(ProcessBuilder.Redirect.INHERIT);
            docker.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process p = docker.start();
            int ecode = p.waitFor();
            if (ecode != 0) {
                throw new RuntimeException("Docker pull failed");
            }
        }
        catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
}
