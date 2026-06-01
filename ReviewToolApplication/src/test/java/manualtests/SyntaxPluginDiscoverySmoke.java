package manualtests;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Quick smoke runner for syntax highlighter plugin discovery.
 */
public class SyntaxPluginDiscoverySmoke {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyntaxPluginDiscoverySmoke.class);

    /**
     * Runs discovery checks for configured syntax extensions.
     */
    static void main() {
        configurePluginDirectory();

        PluginManager pluginManager = new PluginManager();
        pluginManager.start();

        List<String> extensions = List.of(
            "java", "py", "ts", "js", "html", "css", "tsx", "json", "yaml", "yml", "xml",
            "shell", "bash", "sh", "vue", "rs", "go", "golang", "cpp", "sql",
            "md", "toml", "ini", "dockerfile", "kt", "kts", "cs", "rb", "php", "swift", "scala", "lua", "ps1",
            "idl", "ada"
        );

        for (String extension : extensions) {
            String plugin = pluginManager.getSyntaxHighlighterFor(extension)
                .map(p -> p.getClass().getSimpleName())
                .orElse("<missing>");
            LOGGER.info("{} -> {}", extension, plugin);
        }

        pluginManager.shutdown();
    }

    private static void configurePluginDirectory() {
        Path workspaceRoot = resolveWorkspaceRoot();
        Path pluginsDirectory = workspaceRoot.resolve("plugins").toAbsolutePath().normalize();
        try {
            Files.createDirectories(pluginsDirectory);
        } catch (Exception ignored) {
        }

        System.setProperty("srt.plugins.dir", pluginsDirectory.toString());
        ensureLatestDefaultPluginJar(workspaceRoot, pluginsDirectory);
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("ReviewToolApplication"))
            && Files.isDirectory(current.resolve("ReviewToolDefaultPlugins"))) {
            return current;
        }
        if ("ReviewToolApplication".equalsIgnoreCase(current.getFileName().toString())
            && Files.isDirectory(current.getParent().resolve("ReviewToolDefaultPlugins"))) {
            return current.getParent();
        }
        return current;
    }

    private static void ensureLatestDefaultPluginJar(Path workspaceRoot, Path pluginsDir) {
        Path sourceJar = workspaceRoot
            .resolve("ReviewToolDefaultPlugins")
            .resolve("target")
            .resolve("review-tool-default-plugins-1.0.0.jar");
        Path targetJar = pluginsDir.resolve("review-tool-default-plugins-1.0.0.jar");

        if (!Files.exists(sourceJar)) {
            return;
        }

        try {
            Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}






