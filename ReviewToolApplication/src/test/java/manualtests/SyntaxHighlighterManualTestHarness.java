package manualtests;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.plugin.SyntaxHighlighterPlugin;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual Swing harness for visually validating syntax highlighting plugins.
 */
public class SyntaxHighlighterManualTestHarness {

    /**
     * Starts the manual syntax highlighting viewer.

     */
    static void main() {
        SwingUtilities.invokeLater(() -> new SyntaxHighlighterManualTestHarness().show());
    }

    private final PluginManager pluginManager = new PluginManager();
    private final Map<String, String> samples = createSamples();

    private final JComboBox<String> languageSelector = new JComboBox<>();
    private final JCheckBox darkThemeToggle = new JCheckBox("Dark theme", true);
    private final JTextArea sourceArea = new JTextArea();
    private final JTextPane highlightedArea = new JTextPane();
    private final JLabel statusLabel = new JLabel();
    private Path pluginsDirectory;

    private void show() {
        configurePluginDirectory();
        pluginManager.initialize();

        JFrame frame = new JFrame("Manual Syntax Highlighter Harness");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1200, 750));

        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("Language:"));
        samples.keySet().forEach(languageSelector::addItem);
        topPanel.add(languageSelector);
        topPanel.add(darkThemeToggle);
        topPanel.add(statusLabel);

        sourceArea.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13));
        highlightedArea.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13));
        highlightedArea.setEditable(false);

        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Source"));
        JScrollPane highlightedScroll = new JScrollPane(highlightedArea);
        highlightedScroll.setBorder(BorderFactory.createTitledBorder("Highlighted"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourceScroll, highlightedScroll);
        splitPane.setResizeWeight(0.5);

        frame.setLayout(new BorderLayout());
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);

        languageSelector.addActionListener(e -> loadSelectedSample());
        darkThemeToggle.addActionListener(e -> renderSelected());
        sourceArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                renderSelected();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                renderSelected();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                renderSelected();
            }
        });

        languageSelector.setSelectedItem("js");
        loadSelectedSample();

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void configurePluginDirectory() {
        Path workspaceRoot = resolveWorkspaceRoot();
        pluginsDirectory = workspaceRoot.resolve("plugins").toAbsolutePath().normalize();
        try {
            Files.createDirectories(pluginsDirectory);
        } catch (Exception ignored) {
        }

        System.setProperty("srt.plugins.dir", pluginsDirectory.toString());
        ensureLatestDefaultPluginJar(workspaceRoot, pluginsDirectory);
    }

    private Path resolveWorkspaceRoot() {
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

    private void ensureLatestDefaultPluginJar(Path workspaceRoot, Path pluginsDir) {
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

    private void loadSelectedSample() {
        String language = (String) languageSelector.getSelectedItem();
        if (language == null) {
            return;
        }
        sourceArea.setText(samples.getOrDefault(language, ""));
        renderSelected();
    }

    private void renderSelected() {
        String extension = (String) languageSelector.getSelectedItem();
        if (extension == null) {
            return;
        }

        String source = sourceArea.getText();
        boolean darkTheme = darkThemeToggle.isSelected();

        Color background = darkTheme ? new Color(30, 30, 35) : new Color(252, 250, 245);
        Color foreground = darkTheme ? new Color(230, 230, 235) : new Color(35, 30, 25);

        highlightedArea.setBackground(background);
        highlightedArea.setForeground(foreground);
        highlightedArea.setText(source);

        StyledDocument doc = highlightedArea.getStyledDocument();
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setForeground(base, foreground);
        doc.setCharacterAttributes(0, doc.getLength(), base, true);

        SyntaxHighlighterPlugin plugin = pluginManager.getSyntaxHighlighterFor(extension).orElse(null);
        if (plugin == null) {
            statusLabel.setText("No plugin found for ." + extension + " (dir: " + pluginsDirectory + ")");
            return;
        }

        statusLabel.setText("Using: " + plugin.getClass().getSimpleName());

        List<SyntaxHighlighterPlugin.SyntaxToken> tokens = plugin.tokenize(source);
        for (SyntaxHighlighterPlugin.SyntaxToken token : tokens) {
            if (token.offset < 0 || token.length <= 0) {
                continue;
            }
            if (token.offset + token.length > doc.getLength()) {
                continue;
            }
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, resolveTokenColor(plugin, token.type, darkTheme, foreground));
            doc.setCharacterAttributes(token.offset, token.length, style, false);
        }
    }

    private Color resolveTokenColor(SyntaxHighlighterPlugin plugin,
                                    SyntaxHighlighterPlugin.TokenType tokenType,
                                    boolean darkTheme,
                                    Color fallback) {
        try {
            Object resolved = plugin.getClass()
                .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class, boolean.class)
                .invoke(plugin, tokenType, darkTheme);
            if (resolved instanceof Color color) {
                return color;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Object resolved = plugin.getClass()
                .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class)
                .invoke(plugin, tokenType);
            if (resolved instanceof Color color) {
                return color;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        return fallback;
    }

    private Map<String, String> createSamples() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("java", "class User { String name = \"Ada\"; }\nclass Demo {\n  public static String getName() {\n    User user = new User();\n    return user.name;\n  }\n}\n");
        map.put("py", "class User:\n    def __init__(self):\n        self.name = \"Ada\"\n\nuser = User()\nprint(user.name)\n");
        map.put("js", "const user = { name: \"Ada\" };\nif (user.name) {\n  console.log(user.name);\n}\n");
        map.put("html", "<section class=\"card\">\n  <h1 id=\"title\">Hello</h1>\n  <button @click=\"save\">Save</button>\n</section>\n");
        map.put("css", ".card {\n  color: #333;\n  margin: 12px;\n  --accent: #f90;\n}\n");
        map.put("tsx", "type User = { name: string };\nconst user: User = { name: \"Ada\" };\nexport function Card() {\n  return <div className=\"card\">{user.name}</div>;\n}\n");
        map.put("json", "{\n  \"name\": \"Ada\",\n  \"active\": true,\n  \"age\": 31\n}\n");
        map.put("yaml", "service:\n  name: review-tool\n  enabled: true\n  retries: 3\n");
        map.put("yml", "service:\n  name: review-tool\n  enabled: true\n  retries: 3\n");
        map.put("xml", "<config>\n  <service name=\"review-tool\" enabled=\"true\"/>\n</config>\n");
        map.put("shell", "#!/bin/sh\nname=\"Ada\"\nif [ -n \"$name\" ]; then\n  echo $name\nfi\n");
        map.put("bash", "#!/usr/bin/env bash\nname=\"Ada\"\nfor i in 1 2 3; do\n  echo ${name}-$i\ndone\n");
        map.put("sh", "#!/bin/sh\nname=\"Ada\"\necho ${name}\n");
        map.put("vue", "<template>\n  <div class=\"card\">{{ user.name }}</div>\n</template>\n<script setup>\nconst user = { name: 'Ada' }\n</script>\n");
        map.put("rs", "struct User { name: String }\nfn main() {\n  let user = User { name: String::from(\"Ada\") };\n  println!(\"{}\", user.name);\n}\n");
        map.put("golang", "package main\nimport \"fmt\"\nfunc main() {\n  user := map[string]string{\"name\": \"Ada\"}\n  fmt.Println(user[\"name\"])\n}\n");
        map.put("go", "package main\nimport \"fmt\"\nfunc main() {\n  user := map[string]string{\"name\": \"Ada\"}\n  fmt.Println(user[\"name\"])\n}\n");
        map.put("cpp", "#include <iostream>\nstruct User { std::string name; };\nint main() {\n  User user{\"Ada\"};\n  std::cout << user.name << std::endl;\n}\n");
        map.put("sql", "SELECT u.name, o.total\nFROM users u\nJOIN orders o ON o.user_id = u.id\nWHERE o.total > 100\nORDER BY o.total DESC;\n");
        map.put("ts", "type User = { name: string };\nconst user: User = { name: \"Ada\" };\nconsole.log(user.name);\n");
        map.put("md", "# Review Summary\n\n- status: in progress\n- owner: Ada\n\nUse `mvn test` to validate.\n");
        map.put("toml", "[service]\nname = \"review-tool\"\nenabled = true\nretries = 3\n");
        map.put("ini", "[service]\nname=review-tool\nenabled=true\nretries=3\n");
        map.put("dockerfile", "FROM eclipse-temurin:21-jre\nWORKDIR /app\nCOPY target/app.jar ./app.jar\nENV JAVA_OPTS=\"-Xmx512m\"\nENTRYPOINT [\"java\",\"-jar\",\"app.jar\"]\n");
        map.put("kt", "data class User(val name: String)\nfun main() {\n    val user = User(\"Ada\")\n    println(user.name)\n}\n");
        map.put("kts", "val name = \"Ada\"\nif (name.isNotBlank()) {\n    println(name)\n}\n");
        map.put("cs", "using System;\npublic class User { public string Name { get; set; } = \"Ada\"; }\nConsole.WriteLine(new User().Name);\n");
        map.put("rb", "class User\n  attr_reader :name\n  def initialize\n    @name = 'Ada'\n  end\nend\nputs User.new.name\n");
        map.put("php", "<?php\nclass User { public string $name = 'Ada'; }\n$user = new User();\necho $user->name;\n");
        map.put("swift", "struct User { let name: String }\nlet user = User(name: \"Ada\")\nprint(user.name)\n");
        map.put("scala", "case class User(name: String)\n@main def run(): Unit =\n  val user = User(\"Ada\")\n  println(user.name)\n");
        map.put("lua", "local user = { name = 'Ada' }\nif user.name then\n  print(user.name)\nend\n");
        map.put("ps1", "$user = [pscustomobject]@{ Name = 'Ada' }\nif ($user.Name) {\n  Write-Host $user.Name\n}\n");
        map.put("idl", "module Review {\n  interface ReviewService {\n    string get_status(in string review_id);\n  };\n};\n");
        map.put("ada", "with Ada.Text_IO; use Ada.Text_IO;\nprocedure Hello is\n   Name : String := \"Ada\";\nbegin\n   Put_Line(Name);\nend Hello;\n");
        return map;
    }
}
