package MCModifier.ohhapple;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class InjectorApp extends JFrame {

    private JTextPane logPane;
    private StyledDocument logDocument;
    private Style programStyle;
    private Style mcStyle;

    private JButton refreshButton;
    private JButton injectButton;
    private JButton loadRulesButton;
    private JComboBox<ProcessItem> processCombo;
    private File agentTempFile;

    // 命令行输入框
    private JTextField commandInputField;

    private JPanel rulesPanel;
    private JPanel fixedHeaderPanel;
    private JScrollPane rulesScrollPane;
    private List<RuleUI> ruleUIs = new ArrayList<>();

    private List<Map<String, Object>> allRules = new ArrayList<>();
    private JComboBox<String> categoryFilterCombo;
    private JTextField searchField;
    private JPanel filterPanel;

    // MC日志监控相关（所有共享变量均使用 volatile 保证可见性）
    private JButton mcLogMonitorButton;
    private JComboBox<String> encodingCombo;
    private volatile Thread mcLogMonitorThread;
    private volatile Path mcLogPath;
    private final Object monitorLock = new Object();
    private volatile Charset currentCharset = StandardCharsets.UTF_8;
    private volatile boolean monitoringEnabled = true;    // 默认开启
    private volatile boolean waitingForLogPath = false;
    private volatile Thread pingWaitThread;               // 专门等待日志路径的线程

    // 配置文件
    private Properties config = new Properties();

    // 深色主题颜色定义
    private static final Color COLOR_BG_PRIMARY = new Color(43, 43, 43);
    private static final Color COLOR_BG_SECONDARY = new Color(60, 63, 65);
    private static final Color COLOR_BG_INPUT = new Color(90, 90, 90);
    private static final Color COLOR_BG_DROPDOWN = new Color(70, 70, 70);
    private static final Color COLOR_FG_PRIMARY = new Color(187, 187, 187);
    private static final Color COLOR_FG_BRIGHT = new Color(220, 220, 220);
    private static final Color COLOR_BORDER = new Color(140, 140, 140);
    private static final Color COLOR_BUTTON_NORMAL = new Color(70, 130, 200);
    private static final Color COLOR_BUTTON_HOVER = new Color(100, 160, 230);
    private static final Color COLOR_BUTTON_PERMANENT = new Color(160, 100, 50);
    private static final Color COLOR_CATEGORY = new Color(150, 150, 150);
    private static final Color COLOR_TYPE_BOOLEAN = new Color(90, 180, 90);
    private static final Color COLOR_TYPE_ENUM = new Color(90, 140, 210);
    private static final Color COLOR_TYPE_TEXT = new Color(180, 180, 180);

    // 日志颜色
    private static final Color COLOR_LOG_PROGRAM = new Color(100, 200, 255); // 青色
    private static final Color COLOR_LOG_MC = new Color(255, 200, 100);      // 橙色

    // 按钮状态颜色
    private static final Color COLOR_MONITOR_ON = new Color(0, 150, 0);      // 绿色
    private static final Color COLOR_MONITOR_OFF = new Color(200, 0, 0);    // 红色

    private static final int[] COL_MIN_WIDTHS = {120, 200, 60, 120, 60, 60, 140};
    private static final double[] COL_WEIGHTS = {0.12, 0.22, 0.06, 0.15, 0.08, 0.08, 0.29};
    private static final int COMPONENT_HEIGHT = 28;

    private static class ProcessItem {
        String id;
        String displayName;
        boolean isServer;

        ProcessItem(String id, String displayName, boolean isServer) {
            this.id = id;
            this.displayName = displayName;
            this.isServer = isServer;
        }

        @Override
        public String toString() {
            return id + " - " + displayName;
        }
    }

    private static class RuleUI {
        String name;
        JComponent valueEditor;
        JButton applyButton;
        JButton permanentButton;
        String originalValue;

        RuleUI(String name, JComponent editor, String originalValue) {
            this.name = name;
            this.valueEditor = editor;
            this.originalValue = originalValue;
        }
    }

    public InjectorApp() {
        setTitle("MCModifier - Minecraft 注入器 - Carpet 规则管理");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 800);
        setLocationRelativeTo(null);

        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception e) {
            e.printStackTrace();
        }

        setIconImage(loadIcon());
        getRootPane().setBackground(COLOR_BG_PRIMARY);
        getContentPane().setBackground(COLOR_BG_PRIMARY);
        overrideUIManagerColors();
        setUIFont("微软雅黑", Font.PLAIN, 14);

        ToolTipManager ttm = ToolTipManager.sharedInstance();
        ttm.setInitialDelay(0);
        ttm.setDismissDelay(Integer.MAX_VALUE);

        setLayout(new BorderLayout(5, 5));

        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = createCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        releaseAgentJar();
        refreshProcessList();

        // 加载配置
        loadConfig();

        // 显示欢迎信息
        logProgram("=========================================");
        logProgram(" MCModifier Made By ohhapple");
        logProgram(" Homepage: https://ohhapple.github.io");
        logProgram(" GitHub: https://github.com/ohhapple/MCModifier");
        logProgram(" Copyright © 2026 ohhapple. All rights reserved.");
        logProgram("=========================================");

        // 启动时清除上次遗留的日志路径文件
        Path logPathFile = getLogsDirectory().resolve("mc_log_path.txt");
        try {
            Files.deleteIfExists(logPathFile);
        } catch (IOException ignored) {}

        SwingUtilities.invokeLater(() -> this.requestFocusInWindow());
        processCombo.addActionListener(e -> SwingUtilities.invokeLater(() -> this.requestFocusInWindow()));
        setupGlobalFocusListener();
        SwingUtilities.invokeLater(() -> applyDarkTheme());
    }

    // ==================== 配置文件读写 ====================
    private Path getConfigFile() {
        return getLogsDirectory().resolve("config.properties");
    }

    private void loadConfig() {
        Path configFile = getConfigFile();
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config.load(reader);
            } catch (IOException e) {
                logProgram("读取配置文件失败，使用默认设置: " + e.getMessage());
            }
        }

        // 恢复监控开关状态
        monitoringEnabled = Boolean.parseBoolean(config.getProperty("monitor_enabled", "true"));
        updateMonitorButtonState();

        // 恢复编码选择
        String lastEncoding = config.getProperty("log_encoding", "自动");
        encodingCombo.setSelectedItem(lastEncoding);
        updateEncoding();

        // 确保三个功能按钮背景色正确
        refreshButton.setBackground(COLOR_BUTTON_NORMAL);
        injectButton.setBackground(COLOR_BUTTON_NORMAL);
        loadRulesButton.setBackground(COLOR_BUTTON_NORMAL);

        // 确保按钮颜色刷新（延迟确保UI已初始化）
        SwingUtilities.invokeLater(this::updateMonitorButtonState);
    }

    private void saveConfig() {
        try {
            Files.createDirectories(getConfigFile().getParent());
            try (Writer writer = Files.newBufferedWriter(getConfigFile(), StandardCharsets.UTF_8)) {
                String comments = "MCModifier Configuration\n" +
                        "Author: ohhapple\n" +
                        "Homepage: https://ohhapple.github.io\n" +
                        "GitHub: https://github.com/ohhapple/MCModifier\n" +
                        "Copyright (c) 2026 ohhapple. All rights reserved.";
                config.store(writer, comments);
            }
        } catch (IOException e) {
            logProgram("保存配置文件失败: " + e.getMessage());
        }
    }

    // ==================== 界面构建方法 ====================

    private void setupGlobalFocusListener() {
        getContentPane().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Component clicked = SwingUtilities.getDeepestComponentAt(getContentPane(), e.getX(), e.getY());
                if (!isFocusableComponent(clicked)) {
                    requestFocusInWindow();
                }
            }
        });
    }

    private boolean isFocusableComponent(Component comp) {
        while (comp != null) {
            if (comp instanceof JTextField || comp instanceof JComboBox || comp instanceof JButton) {
                return true;
            }
            comp = comp.getParent();
        }
        return false;
    }

    private Image loadIcon() {
        URL iconUrl = getClass().getClassLoader().getResource("icon.png");
        if (iconUrl != null) {
            return Toolkit.getDefaultToolkit().getImage(iconUrl);
        }
        return null;
    }

    private void overrideUIManagerColors() {
        UIManager.put("TextField.background", COLOR_BG_INPUT);
        UIManager.put("TextField.foreground", COLOR_FG_BRIGHT);
        UIManager.put("TextField.caretForeground", COLOR_FG_BRIGHT);
        UIManager.put("TextField.border", BorderFactory.createLineBorder(COLOR_BORDER));

        UIManager.put("TextArea.background", COLOR_BG_INPUT);
        UIManager.put("TextArea.foreground", COLOR_FG_BRIGHT);
        UIManager.put("TextArea.caretForeground", COLOR_FG_BRIGHT);
        UIManager.put("TextArea.border", BorderFactory.createLineBorder(COLOR_BORDER));

        UIManager.put("ComboBox.background", COLOR_BG_INPUT);
        UIManager.put("ComboBox.foreground", COLOR_FG_BRIGHT);
        UIManager.put("ComboBox.selectionBackground", new Color(70, 100, 150));
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.border", BorderFactory.createLineBorder(COLOR_BORDER));

        UIManager.put("List.background", COLOR_BG_DROPDOWN);
        UIManager.put("List.foreground", COLOR_FG_BRIGHT);
        UIManager.put("List.selectionBackground", new Color(70, 100, 150));
        UIManager.put("List.selectionForeground", Color.WHITE);

        UIManager.put("Button.background", COLOR_BUTTON_NORMAL);
        UIManager.put("Button.foreground", Color.WHITE);
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));

        UIManager.put("Label.foreground", COLOR_FG_BRIGHT);
        UIManager.put("OptionPane.messageForeground", COLOR_FG_BRIGHT);

        UIManager.put("Panel.background", COLOR_BG_PRIMARY);
        UIManager.put("OptionPane.background", COLOR_BG_SECONDARY);
        UIManager.put("TitledBorder.titleColor", COLOR_FG_PRIMARY);
    }

    private Font createFontSafely(String preferredFontName, int style, int size) {
        String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String font : availableFonts) {
            if (font.equalsIgnoreCase(preferredFontName)) {
                return new Font(preferredFontName, style, size);
            }
        }
        String[] fallbacks = {"SansSerif", "Dialog", "Arial", "Tahoma"};
        for (String fallback : fallbacks) {
            for (String font : availableFonts) {
                if (font.equalsIgnoreCase(fallback)) {
                    return new Font(fallback, style, size);
                }
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private void setUIFont(String fontName, int style, int size) {
        Font font = createFontSafely(fontName, style, size);
        UIManager.put("Button.font", font);
        UIManager.put("ToggleButton.font", font);
        UIManager.put("RadioButton.font", font);
        UIManager.put("CheckBox.font", font);
        UIManager.put("ColorChooser.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("Label.font", font);
        UIManager.put("List.font", font);
        UIManager.put("MenuBar.font", font);
        UIManager.put("MenuItem.font", font);
        UIManager.put("RadioButtonMenuItem.font", font);
        UIManager.put("CheckBoxMenuItem.font", font);
        UIManager.put("Menu.font", font);
        UIManager.put("PopupMenu.font", font);
        UIManager.put("OptionPane.font", font);
        UIManager.put("Panel.font", font);
        UIManager.put("ProgressBar.font", font);
        UIManager.put("ScrollPane.font", font);
        UIManager.put("Viewport.font", font);
        UIManager.put("TabbedPane.font", font);
        UIManager.put("Table.font", font);
        UIManager.put("TableHeader.font", font);
        UIManager.put("TextField.font", font);
        UIManager.put("PasswordField.font", font);
        UIManager.put("TextArea.font", font);
        UIManager.put("TextPane.font", font);
        UIManager.put("EditorPane.font", font);
        UIManager.put("TitledBorder.font", font);
        UIManager.put("ToolBar.font", font);
        UIManager.put("ToolTip.font", font);
        UIManager.put("Tree.font", font);
    }

    private void applyDarkTheme() {
        setDeepBackground(getContentPane(), COLOR_BG_PRIMARY);
        repaint();

        // 显式设置三个功能按钮的背景色为正常色（防止被覆盖）
        refreshButton.setBackground(COLOR_BUTTON_NORMAL);
        injectButton.setBackground(COLOR_BUTTON_NORMAL);
        loadRulesButton.setBackground(COLOR_BUTTON_NORMAL);
        refreshButton.repaint();
        injectButton.repaint();
        loadRulesButton.repaint();

        // 确保监控按钮颜色正确
        SwingUtilities.invokeLater(() -> {
            updateMonitorButtonState();
            mcLogMonitorButton.repaint();
        });
    }

    private void setDeepBackground(Container container, Color bg) {
        container.setBackground(bg);
        for (Component comp : container.getComponents()) {
            // 所有按钮：完全跳过背景设置，但继续处理子组件（如果有）
            if (comp instanceof JButton) {
                if (comp instanceof Container) {
                    setDeepBackground((Container) comp, bg);
                }
                continue;
            }

            // 非按钮组件，统一设置背景
            comp.setBackground(bg);

            if (comp instanceof JComboBox) {
                JComboBox<?> cb = (JComboBox<?>) comp;
                cb.setBackground(COLOR_BG_INPUT);
                cb.setForeground(COLOR_FG_BRIGHT);
                if (cb instanceof JComponent) {
                    ((JComponent) cb).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
                }
                fixComboEditor(cb);
                setupComboScrollBar(cb);
            } else if (comp instanceof JTextField) {
                JTextField tf = (JTextField) comp;
                tf.setBackground(COLOR_BG_INPUT);
                tf.setForeground(COLOR_FG_BRIGHT);
                tf.setCaretColor(COLOR_FG_BRIGHT);
                if (tf instanceof JComponent) {
                    tf.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
                }
            } else if (comp instanceof JPanel) {
                ((JPanel) comp).setOpaque(true);
            }

            if (comp instanceof Container) {
                setDeepBackground((Container) comp, bg);
            }
        }
    }

    private void setupComboScrollBar(JComboBox<?> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> setComboPopupScrollBarUI(combo));
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    private void fixComboEditor(JComboBox<?> combo) {
        Component editorComp = combo.getEditor().getEditorComponent();
        editorComp.setBackground(COLOR_BG_INPUT);
        editorComp.setForeground(COLOR_FG_BRIGHT);
        if (editorComp instanceof JComponent) {
            JComponent jc = (JComponent) editorComp;
            jc.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
            jc.setOpaque(true);
            jc.setPreferredSize(new Dimension(jc.getPreferredSize().width, COMPONENT_HEIGHT));
        }
        editorComp.repaint();
    }

    private void setComboPopupScrollBarUI(JComboBox<?> combo) {
        Object popup = combo.getAccessibleContext().getAccessibleChild(0);
        if (popup instanceof JComponent) {
            findAndSetScrollBarUI((JComponent) popup);
        }
    }

    private void findAndSetScrollBarUI(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JScrollPane) {
                JScrollPane scrollPane = (JScrollPane) comp;
                JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
                if (verticalBar != null) {
                    verticalBar.setUI(new DarkScrollBarUI());
                    verticalBar.setBackground(COLOR_BG_PRIMARY);
                }
                JScrollBar horizontalBar = scrollPane.getHorizontalScrollBar();
                if (horizontalBar != null) {
                    horizontalBar.setUI(new DarkScrollBarUI());
                    horizontalBar.setBackground(COLOR_BG_PRIMARY);
                }
            } else if (comp instanceof Container) {
                findAndSetScrollBarUI((Container) comp);
            }
        }
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(COLOR_BG_SECONDARY);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        processCombo = new JComboBox<>();
        processCombo.setPreferredSize(new Dimension(400, 30));
        processCombo.setBackground(COLOR_BG_INPUT);
        processCombo.setForeground(COLOR_FG_BRIGHT);
        processCombo.setRenderer(new ProcessItemRenderer());
        ((JComponent) processCombo).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        fixComboEditor(processCombo);
        setupComboScrollBar(processCombo);

        // 监控开关按钮：单独创建，不使用 createStyledButton 以避免鼠标悬停变色
        mcLogMonitorButton = new JButton("● 监控中");
        mcLogMonitorButton.setFocusPainted(false);
        mcLogMonitorButton.setForeground(Color.WHITE);
        mcLogMonitorButton.setFont(mcLogMonitorButton.getFont().deriveFont(Font.BOLD));
        mcLogMonitorButton.setPreferredSize(new Dimension(130, COMPONENT_HEIGHT));
        mcLogMonitorButton.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        mcLogMonitorButton.setOpaque(true);
        mcLogMonitorButton.addActionListener(e -> toggleMcLogMonitoring());
        updateMonitorButtonState(); // 确保初始状态正确

        // 编码选择
        encodingCombo = new JComboBox<>(new String[]{"自动", "UTF-8", "GBK", "GB2312"});
        encodingCombo.setBackground(COLOR_BG_INPUT);
        encodingCombo.setForeground(COLOR_FG_BRIGHT);
        encodingCombo.setRenderer(new DarkComboBoxRenderer());
        ((JComponent) encodingCombo).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        encodingCombo.setPreferredSize(new Dimension(100, 28));
        encodingCombo.addActionListener(e -> {
            updateEncoding();
            saveConfig();
        });
        fixComboEditor(encodingCombo);
        setupComboScrollBar(encodingCombo);

        processCombo.addActionListener(e -> {
            if (monitoringEnabled) {
                // 停止当前监控（如果有）
                stopMcLogMonitoring();
                // 重置日志路径，确保下次启动监控时会重新 ping
                synchronized (monitorLock) {
                    mcLogPath = null;
                    waitingForLogPath = false;
                    // 删除旧的路径文件
                    try {
                        Path logPathFile = getLogsDirectory().resolve("mc_log_path.txt");
                        Files.deleteIfExists(logPathFile);
                    } catch (IOException ignored) {}
                }
                // 开始新的监控流程（会触发 ping）
                startMcLogMonitoringIfNeeded();
            } else {
                stopMcLogMonitoring();
            }
        });

        refreshButton = createStyledButton("刷新进程列表", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);
        injectButton = createStyledButton("执行命令", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);
        loadRulesButton = createStyledButton("加载规则列表", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);

        refreshButton.addActionListener(this::onRefresh);
        injectButton.addActionListener(this::onInject);
        loadRulesButton.addActionListener(this::onLoadRules);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftPanel.setBackground(COLOR_BG_SECONDARY);
        leftPanel.add(new JLabel("目标进程:"));
        leftPanel.add(processCombo);
        leftPanel.add(mcLogMonitorButton);
        leftPanel.add(new JLabel("编码:"));
        leftPanel.add(encodingCombo);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightPanel.setBackground(COLOR_BG_SECONDARY);
        rightPanel.add(refreshButton);
        rightPanel.add(injectButton);
        rightPanel.add(loadRulesButton);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    private void updateMonitorButtonState() {
        if (monitoringEnabled) {
            mcLogMonitorButton.setText("● 监控中");
            mcLogMonitorButton.setBackground(COLOR_MONITOR_ON);
            mcLogMonitorButton.setToolTipText("点击停止监控MC日志");
        } else {
            mcLogMonitorButton.setText("○ 已停止");
            mcLogMonitorButton.setBackground(COLOR_MONITOR_OFF);
            mcLogMonitorButton.setToolTipText("点击开始监控MC日志");
        }
        mcLogMonitorButton.setOpaque(true);
        mcLogMonitorButton.repaint();
    }

    private void toggleMcLogMonitoring() {
        monitoringEnabled = !monitoringEnabled;
        updateMonitorButtonState();
        config.setProperty("monitor_enabled", String.valueOf(monitoringEnabled));
        saveConfig();
        if (monitoringEnabled) {
            startMcLogMonitoringIfNeeded();
        } else {
            stopMcLogMonitoring();
        }
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(COLOR_BG_PRIMARY);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // 过滤面板
        filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        filterPanel.setBackground(COLOR_BG_SECONDARY);
        filterPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                "筛选规则", TitledBorder.LEFT, TitledBorder.TOP));
        ((TitledBorder) filterPanel.getBorder()).setTitleColor(COLOR_FG_PRIMARY);

        JLabel lblCategory = new JLabel("分类:");
        lblCategory.setForeground(COLOR_FG_PRIMARY);
        filterPanel.add(lblCategory);

        categoryFilterCombo = new JComboBox<>();
        categoryFilterCombo.setBackground(COLOR_BG_INPUT);
        categoryFilterCombo.setForeground(COLOR_FG_BRIGHT);
        categoryFilterCombo.setRenderer(new DarkComboBoxRenderer());
        ((JComponent) categoryFilterCombo).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        categoryFilterCombo.addItem("全部");
        categoryFilterCombo.setPreferredSize(new Dimension(150, 28));
        categoryFilterCombo.addActionListener(e -> applyFilter());
        fixComboEditor(categoryFilterCombo);
        setupComboScrollBar(categoryFilterCombo);
        filterPanel.add(categoryFilterCombo);

        JLabel lblSearch = new JLabel("搜索:");
        lblSearch.setForeground(COLOR_FG_PRIMARY);
        filterPanel.add(lblSearch);

        searchField = new JTextField(20);
        searchField.setBackground(COLOR_BG_INPUT);
        searchField.setForeground(COLOR_FG_BRIGHT);
        searchField.setCaretColor(COLOR_FG_BRIGHT);
        searchField.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        searchField.setPreferredSize(new Dimension(200, 28));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        filterPanel.add(searchField);

        panel.add(filterPanel, BorderLayout.NORTH);

        // 规则区域
        JPanel rulesArea = new JPanel(new BorderLayout());
        rulesArea.setBackground(COLOR_BG_PRIMARY);

        fixedHeaderPanel = createHeaderRow();
        fixedHeaderPanel.setBackground(COLOR_BG_SECONDARY);
        fixedHeaderPanel.setBorder(BorderFactory.createEmptyBorder());

        rulesPanel = new JPanel();
        rulesPanel.setLayout(new BoxLayout(rulesPanel, BoxLayout.Y_AXIS));
        rulesPanel.setBackground(COLOR_BG_PRIMARY);

        rulesScrollPane = new JScrollPane(rulesPanel);
        rulesScrollPane.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        rulesScrollPane.getVerticalScrollBar().setUnitIncrement(24);
        rulesScrollPane.getViewport().setBackground(COLOR_BG_PRIMARY);

        rulesScrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        rulesScrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        rulesScrollPane.setColumnHeaderView(fixedHeaderPanel);

        rulesArea.add(rulesScrollPane, BorderLayout.CENTER);
        panel.add(rulesArea, BorderLayout.CENTER);
        return panel;
    }

    // 底部面板：日志区域 + 一体化输入框
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(COLOR_BG_SECONDARY);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                "日志", TitledBorder.LEFT, TitledBorder.TOP));
        ((TitledBorder) panel.getBorder()).setTitleColor(COLOR_FG_PRIMARY);

        // 日志显示区域 - 自定义 JTextPane 强制跟踪视口宽度并支持字符级换行
        logPane = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true; // 强制宽度跟随视口，实现自动换行
            }
        };

        // 自定义 EditorKit 强制字符级换行
        logPane.setEditorKit(new StyledEditorKit() {
            @Override
            public ViewFactory getViewFactory() {
                return new ViewFactory() {
                    @Override
                    public View create(Element elem) {
                        String kind = elem.getName();
                        if (kind != null && kind.equals(AbstractDocument.ContentElementName)) {
                            // 文本内容：返回自定义 LabelView，允许水平最小宽度为0
                            return new LabelView(elem) {
                                @Override
                                public float getMinimumSpan(int axis) {
                                    if (axis == View.X_AXIS) {
                                        return 0; // 允许压缩到0，强制字符换行
                                    }
                                    return super.getMinimumSpan(axis);
                                }
                            };
                        } else if (kind != null && kind.equals(AbstractDocument.ParagraphElementName)) {
                            return new ParagraphView(elem);
                        } else if (kind != null && kind.equals(AbstractDocument.SectionElementName)) {
                            return new BoxView(elem, View.Y_AXIS);
                        } else if (kind != null && kind.equals(StyleConstants.ComponentElementName)) {
                            return new ComponentView(elem);
                        } else if (kind != null && kind.equals(StyleConstants.IconElementName)) {
                            return new IconView(elem);
                        }
                        // 默认返回 LabelView，同样允许换行
                        return new LabelView(elem) {
                            @Override
                            public float getMinimumSpan(int axis) {
                                if (axis == View.X_AXIS) {
                                    return 0;
                                }
                                return super.getMinimumSpan(axis);
                            }
                        };
                    }
                };
            }
        });

        logPane.setEditable(false);
        logPane.setBackground(COLOR_BG_INPUT);
        logPane.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        logPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        logDocument = logPane.getStyledDocument();

        programStyle = logPane.addStyle("Program", null);
        StyleConstants.setForeground(programStyle, COLOR_LOG_PROGRAM);
        StyleConstants.setFontFamily(programStyle, "微软雅黑");
        StyleConstants.setFontSize(programStyle, 13);

        mcStyle = logPane.addStyle("MC", null);
        StyleConstants.setForeground(mcStyle, COLOR_LOG_MC);
        StyleConstants.setFontFamily(mcStyle, "微软雅黑");
        StyleConstants.setFontSize(mcStyle, 13);

        JScrollPane logScrollPane = new JScrollPane(logPane);
        logScrollPane.setPreferredSize(new Dimension(1400, 200));
        logScrollPane.getViewport().setBackground(COLOR_BG_INPUT);
        logScrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        logScrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
        logScrollPane.setBorder(BorderFactory.createEmptyBorder());
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // 监听视口大小变化，强制更新 logPane 的宽度
        logScrollPane.getViewport().addChangeListener(e -> {
            int width = logScrollPane.getViewport().getWidth();
            if (width > 0) {
                logPane.setSize(width, logPane.getHeight());
                logPane.revalidate();
            }
        });

        // 命令行输入区域（一体化设计）
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBackground(COLOR_BG_INPUT);
        inputPanel.setBorder(BorderFactory.createEmptyBorder());

        JLabel promptLabel = new JLabel("> ");
        promptLabel.setForeground(COLOR_FG_BRIGHT);
        promptLabel.setFont(promptLabel.getFont().deriveFont(Font.BOLD, 14f));
        inputPanel.add(promptLabel, BorderLayout.WEST);

        commandInputField = new JTextField();
        commandInputField.setBackground(COLOR_BG_INPUT);
        commandInputField.setForeground(COLOR_FG_BRIGHT);
        commandInputField.setCaretColor(COLOR_FG_BRIGHT);
        commandInputField.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 5));
        commandInputField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        commandInputField.addActionListener(e -> {
            String cmd = commandInputField.getText().trim();
            if (!cmd.isEmpty()) {
                sendCommand(cmd);
                commandInputField.setText("");
            }
        });
        inputPanel.add(commandInputField, BorderLayout.CENTER);

        // 将日志滚动面板和输入面板叠放在一起（输入面板在底部，无缝贴合）
        JPanel logContainer = new JPanel(new BorderLayout(0, 0));
        logContainer.setBackground(COLOR_BG_INPUT);
        logContainer.add(logScrollPane, BorderLayout.CENTER);
        logContainer.add(inputPanel, BorderLayout.SOUTH);

        panel.add(logContainer, BorderLayout.CENTER);
        return panel;
    }

    private JButton createStyledButton(String text, Color normalColor, Color hoverColor) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setBackground(normalColor);
        button.setForeground(Color.WHITE);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setPreferredSize(new Dimension(130, COMPONENT_HEIGHT));
        button.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        button.setOpaque(true);

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(hoverColor);
                button.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(normalColor);
                button.repaint();
            }
        });
        return button;
    }

    private class ProcessItemRenderer extends BasicComboBoxRenderer {
        @Override
        public Component getListCellRendererComponent(JList list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setOpaque(true);

            if (isSelected) {
                label.setBackground(new Color(70, 100, 150));
                label.setForeground(Color.WHITE);
            } else {
                label.setBackground(COLOR_BG_DROPDOWN);
                label.setForeground(COLOR_FG_BRIGHT);
            }

            if (value instanceof ProcessItem) {
                ProcessItem item = (ProcessItem) value;
                label.setText(item.toString());
                label.setToolTipText(item.displayName);
                if (item.isServer) {
                    label.setForeground(new Color(100, 200, 100));
                } else {
                    label.setForeground(COLOR_FG_BRIGHT);
                }
            }
            return label;
        }
    }

    private class DarkComboBoxRenderer extends BasicComboBoxRenderer {
        @Override
        public Component getListCellRendererComponent(JList list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setOpaque(true);

            if (isSelected) {
                label.setBackground(new Color(70, 100, 150));
                label.setForeground(Color.WHITE);
            } else {
                label.setBackground(COLOR_BG_DROPDOWN);
                label.setForeground(COLOR_FG_BRIGHT);
            }
            return label;
        }
    }

    private class DarkScrollBarUI extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            JButton button = super.createDecreaseButton(orientation);
            button.setBackground(COLOR_BG_SECONDARY);
            button.setForeground(COLOR_FG_BRIGHT);
            button.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
            return button;
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            JButton button = super.createIncreaseButton(orientation);
            button.setBackground(COLOR_BG_SECONDARY);
            button.setForeground(COLOR_FG_BRIGHT);
            button.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(COLOR_BG_PRIMARY);
            g2.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            g2.setColor(COLOR_BORDER);
            g2.drawRect(trackBounds.x, trackBounds.y, trackBounds.width - 1, trackBounds.height - 1);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;

            Graphics2D g2 = (Graphics2D) g;
            int w = thumbBounds.width;
            int h = thumbBounds.height;

            g2.setColor(COLOR_BG_SECONDARY);
            g2.fillRoundRect(thumbBounds.x, thumbBounds.y, w - 2, h - 2, 4, 4);
            g2.setColor(COLOR_BORDER);
            g2.drawRoundRect(thumbBounds.x, thumbBounds.y, w - 2, h - 2, 4, 4);

            if (scrollbar.getValueIsAdjusting()) {
                g2.setColor(new Color(70, 100, 150));
                g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, w - 6, h - 6, 2, 2);
            }
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            return new Dimension(12, 12);
        }
    }

    // ==================== 监控开关逻辑 ====================

    private void updateEncoding() {
        String selected = (String) encodingCombo.getSelectedItem();
        if ("自动".equals(selected)) {
            // 自动模式下，currentCharset 会在启动监控时动态设置，这里只保存配置
            config.setProperty("log_encoding", "自动");
            saveConfig();
            return;
        }
        if ("GBK".equals(selected)) {
            currentCharset = Charset.forName("GBK");
        } else if ("GB2312".equals(selected)) {
            currentCharset = Charset.forName("GB2312");
        } else {
            currentCharset = StandardCharsets.UTF_8;
        }
        config.setProperty("log_encoding", selected);
        saveConfig();
    }

    private void stopMcLogMonitoring() {
        synchronized (monitorLock) {
            waitingForLogPath = false; // 停止等待
            if (mcLogMonitorThread != null && mcLogMonitorThread.isAlive()) {
                mcLogMonitorThread.interrupt();
                mcLogMonitorThread = null;
                logProgram("MC日志监控已停止");
            }
        }
    }

    private void startMcLogMonitoringIfNeeded() {
        if (!monitoringEnabled) {
            return;
        }
        if (mcLogMonitorThread != null && mcLogMonitorThread.isAlive()) {
            return;
        }

        synchronized (monitorLock) {
            if (mcLogPath != null && Files.exists(mcLogPath)) {
                startMcLogMonitor(mcLogPath);
            } else {
                // 如果没有路径，主动尝试获取
                if (!waitingForLogPath) {
                    waitingForLogPath = true;
                    injectPingToGetLogPath();
                }
            }
        }
    }

    /**
     * 等待 mc_log_path.txt 出现并启动监控（由 ping 线程调用）
     */
    private void waitForMcLogPathAndStart() {
        // 方法开始时，确保自己是当前等待线程，并设置等待标志
        synchronized (monitorLock) {
            if (pingWaitThread != Thread.currentThread()) {
                // 不是自己（可能已被中断且新线程已启动），直接退出
                return;
            }
            waitingForLogPath = true;
        }

        Path logPathFile = getLogsDirectory().resolve("mc_log_path.txt");
        long start = System.currentTimeMillis();
        long timeout = 10000;
        Path newPath = null;

        while (System.currentTimeMillis() - start < timeout) {
            if (Thread.currentThread().isInterrupted()) {
                logProgram("等待日志路径被中断");
                synchronized (monitorLock) {
                    if (pingWaitThread == Thread.currentThread()) {
                        waitingForLogPath = false;
                        pingWaitThread = null;
                    }
                }
                return;
            }

            if (Files.exists(logPathFile)) {
                try {
                    String content = new String(Files.readAllBytes(logPathFile), StandardCharsets.UTF_8).trim(); // 指定 UTF-8
                    if (!content.isEmpty()) {
                        newPath = Paths.get(content);
                        logProgram("获取到MC日志路径: " + newPath);
                        break;
                    }
                } catch (IOException ex) {
                    logProgram("读取mc_log_path.txt失败: " + ex.getMessage());
                }
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logProgram("等待被中断");
                synchronized (monitorLock) {
                    if (pingWaitThread == Thread.currentThread()) {
                        waitingForLogPath = false;
                        pingWaitThread = null;
                    }
                }
                return;
            }
        }

        if (newPath != null) {
            synchronized (monitorLock) {
                mcLogPath = newPath;
                if (monitoringEnabled && (mcLogMonitorThread == null || !mcLogMonitorThread.isAlive())) {
                    startMcLogMonitor(mcLogPath);
                }
            }
        } else {
            logProgram("未能获取MC日志路径，无法监控MC日志");
            // 删除无效的路径文件
            try {
                Files.deleteIfExists(logPathFile);
            } catch (IOException ignored) {}
        }

        // 正常结束时清理，仅当自己仍是当前线程
        synchronized (monitorLock) {
            if (pingWaitThread == Thread.currentThread()) {
                waitingForLogPath = false;
                pingWaitThread = null;
            }
        }
    }

    /**
     * 根据字节数组内容选择最佳编码（UTF-8 或 GBK）
     * 优先规则：如果两种编码都没有出现替换字符，则默认 UTF-8；
     * 否则按得分比较，得分高者胜，得分相同时 UTF-8。
     */
    private Charset selectBestCharset(byte[] data) {
        Charset utf8 = StandardCharsets.UTF_8;
        Charset gbk = Charset.forName("GBK");

        // 分别解码并统计替换字符数量
        String utf8Str = new String(data, utf8);
        String gbkStr = new String(data, gbk);

        int utf8Replacement = countReplacement(utf8Str);
        int gbkReplacement = countReplacement(gbkStr);

        // 如果两种都没有替换字符，优先 UTF-8
        if (utf8Replacement == 0 && gbkReplacement == 0) {
            return utf8;
        }

        // 否则，比较得分（得分计算已包含替换字符惩罚）
        int scoreUTF8 = scoreCharset(data, utf8);
        int scoreGBK = scoreCharset(data, gbk);

        // 得分高者胜，相等时 UTF-8
        return scoreUTF8 >= scoreGBK ? utf8 : gbk;
    }

    /**
     * 统计字符串中替换字符（ ）的数量
     */
    private int countReplacement(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') {
                count++;
            }
        }
        return count;
    }

    private int scoreCharset(byte[] data, Charset charset) {
        try {
            String decoded = new String(data, charset);
            int score = 0;
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c >= 0x20 && c < 0x7F) {
                    score += 1; // 可读ASCII
                } else if (c >= 0x4E00 && c <= 0x9FFF) {
                    score += 3; // 中文字符
                } else if (c == 0xFFFD) {
                    score -= 10; // 替换字符（解码失败标记）
                } else if (c > 0x7F) {
                    score += 1; // 其他Unicode字符（如中文标点、日文等，视为有效）
                }
                // 其他控制字符（<0x20）不计分也不扣分
            }
            return score;
        } catch (Exception e) {
            return Integer.MIN_VALUE; // 解码异常，分数极低
        }
    }

    private void startMcLogMonitor(Path logFile) {
        stopMcLogMonitoring();

        // 自动模式初始设为 UTF-8
        if ("自动".equals(encodingCombo.getSelectedItem())) {
            currentCharset = StandardCharsets.UTF_8;
            logProgram("自动模式，初始编码设为 UTF-8，将根据内容动态选择编码");
        } else {
            updateEncoding();
        }

        mcLogMonitorThread = new Thread(() -> {
            long lastSize = 0;
            if (Files.exists(logFile)) {
                try {
                    lastSize = Files.size(logFile);
                } catch (IOException e) {
                    // ignore
                }
            }

            while (!Thread.currentThread().isInterrupted()) {
                if (Files.exists(logFile)) {
                    try {
                        long currentSize = Files.size(logFile);
                        if (currentSize < lastSize) {
                            lastSize = 0;
                        }
                        if (currentSize > lastSize) {
                            byte[] allBytes = Files.readAllBytes(logFile);
                            byte[] addedBytes = Arrays.copyOfRange(allBytes, (int) lastSize, allBytes.length);
                            lastSize = allBytes.length;

                            // 如果是自动模式，动态选择最佳编码
                            Charset selectedCharset;
                            if ("自动".equals(encodingCombo.getSelectedItem())) {
                                selectedCharset = selectBestCharset(addedBytes);
                                if (!selectedCharset.equals(currentCharset)) {
                                    currentCharset = selectedCharset;
                                    logProgram("自动切换编码为: " + currentCharset.displayName());
                                }
                            } else {
                                selectedCharset = currentCharset; // 使用用户指定的编码
                            }

                            String addedStr = new String(addedBytes, selectedCharset);
                            String[] lines = addedStr.split("\\R");
                            for (String line : lines) {
                                if (!line.isEmpty()) {
                                    final String finalLine = line;
                                    SwingUtilities.invokeLater(() -> logMC(finalLine));
                                }
                            }
                        }
                    } catch (IOException ex) {
                        // ignore
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        mcLogMonitorThread.setDaemon(true);
        mcLogMonitorThread.start();
        logProgram("MC日志监控已启动，编码: " + currentCharset.displayName());
    }

    // ==================== 原有功能方法（精简日志） ====================

    private Path getLogsDirectory() {
        Path jarDir = Paths.get(getJarDirectory());
        Path logsDir = jarDir.resolve("MCMlogs");
        if (!Files.exists(logsDir)) {
            try {
                Files.createDirectories(logsDir);
            } catch (IOException e) {
                e.printStackTrace();
                logProgram("创建日志目录失败: " + e.getMessage());
            }
        }
        return logsDir;
    }

    private String getJarDirectory() {
        try {
            String jarPath = InjectorApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);
            if (jarFile.isFile()) {
                return jarFile.getParentFile().getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return System.getProperty("user.dir");
    }

    private void releaseAgentJar() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File[] oldFiles = new File(tempDir).listFiles((dir, name) ->
                    name.startsWith("mcagent_") && name.endsWith(".jar"));
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }

            String jarPath = InjectorApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            if (jarPath.endsWith(".jar")) {
                try (JarFile jar = new JarFile(jarPath)) {
                    JarEntry entry = jar.getJarEntry("agent.jar");
                    if (entry == null) {
                        logProgram("错误：内嵌的agent.jar未找到，请重新打包。");
                        return;
                    }
                    agentTempFile = File.createTempFile("mcagent_", ".jar");
                    agentTempFile.deleteOnExit();
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, agentTempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    logProgram("Agent临时文件已创建");
                }
            } else {
                logProgram("警告：未从JAR运行，无法释放agent.jar，请先打包。");
            }
        } catch (Exception e) {
            e.printStackTrace();
            logProgram("释放agent.jar失败：" + e.getMessage());
        }
    }

    private void onRefresh(ActionEvent e) {
        refreshProcessList();
    }

    private void refreshProcessList() {
        processCombo.removeAllItems();
        try {
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            if (vms.isEmpty()) {
                logProgram("未找到任何Java进程。");
                return;
            }
            for (VirtualMachineDescriptor vmd : vms) {
                String pid = vmd.id();
                String displayName = vmd.displayName();
                boolean isServer = displayName.toLowerCase().contains("server")
                        || displayName.contains("minecraft_server")
                        || displayName.contains("fabric-server")
                        || displayName.contains("paper")
                        || displayName.contains("nogui")
                        || displayName.contains("fabric")
                        || displayName.contains("forge")
                        || displayName.contains("neoforge")
                        || displayName.contains("spigot");
                processCombo.addItem(new ProcessItem(pid, displayName, isServer));
            }
            logProgram("已刷新进程列表，共 " + vms.size() + " 个Java进程。");
        } catch (Exception e) {
            e.printStackTrace();
            logProgram("获取进程列表失败：" + e.getMessage());
        }
    }

    private String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (c > 0x7F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private void sendCommand(String command) {
        if (command.trim().isEmpty()) return;
        Path logsDir = getLogsDirectory();
        String escapedCommand = escapeJsonString(command.trim());
        String agentArgs = "{\"action\":\"commands\",\"commands\":\"" + escapedCommand + "\",\"outputDir\":\"" + logsDir.toString().replace("\\", "\\\\") + "\"}";
        injectAgent(agentArgs);
        mcLogPath = null; // volatile 赋值，保证可见性

        if (monitoringEnabled) {
            startMcLogMonitoringIfNeeded();
        }
    }

    private void onInject(ActionEvent e) {
        String commandStr = JOptionPane.showInputDialog(this,
                "输入要执行的命令（多个命令用分号分隔）:\n例如: carpet commandPlayer true; carpet creativeNoClip true",
                "执行命令", JOptionPane.PLAIN_MESSAGE);
        if (commandStr == null || commandStr.trim().isEmpty()) {
            return;
        }
        Path logsDir = getLogsDirectory();
        String escapedCommands = escapeJsonString(commandStr.trim());
        String agentArgs = "{\"action\":\"commands\",\"commands\":\"" + escapedCommands + "\",\"outputDir\":\"" + logsDir.toString().replace("\\", "\\\\") + "\"}";
        injectAgent(agentArgs);
        mcLogPath = null; // volatile 赋值，保证可见性

        if (monitoringEnabled) {
            startMcLogMonitoringIfNeeded();
        }
    }

    private void onLoadRules(ActionEvent e) {
        Path logsDir = getLogsDirectory();
        String agentArgs = "{\"action\":\"list\",\"outputDir\":\"" + logsDir.toString().replace("\\", "\\\\") + "\"}";
        Path jsonFile = logsDir.resolve("carpet_rules.json");
        try {
            Files.deleteIfExists(jsonFile);
        } catch (IOException ignored) {}

        injectAgent(agentArgs);

        new Thread(() -> {
            long start = System.currentTimeMillis();
            long timeout = 15000;
            while (System.currentTimeMillis() - start < timeout) {
                if (Files.exists(jsonFile)) {
                    SwingUtilities.invokeLater(() -> loadRulesFromFile(jsonFile));
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            logProgram("等待规则文件超时");
        }).start();
    }

    private void loadRulesFromFile(Path jsonFile) {
        try {
            String content = new String(Files.readAllBytes(jsonFile), StandardCharsets.UTF_8); // 指定 UTF-8
            allRules = parseJson(content);
            buildCategoryCombo();
            applyFilter();
            logProgram("已加载 " + allRules.size() + " 条规则");
        } catch (Exception e) {
            logProgram("解析规则文件失败: " + e);
            e.printStackTrace();
        }
    }

    private void buildCategoryCombo() {
        Set<String> categories = new TreeSet<>();
        for (Map<String, Object> rule : allRules) {
            Object catObj = rule.get("categories");
            if (catObj instanceof List) {
                categories.addAll((List<String>) catObj);
            } else if (catObj instanceof String) {
                categories.add((String) catObj);
            }
        }
        categoryFilterCombo.removeAllItems();
        categoryFilterCombo.addItem("全部");
        for (String cat : categories) {
            categoryFilterCombo.addItem(cat);
        }
    }

    private void applyFilter() {
        String selectedCategory = (String) categoryFilterCombo.getSelectedItem();
        String searchText = searchField.getText().toLowerCase().trim();

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> rule : allRules) {
            if (selectedCategory != null && !"全部".equals(selectedCategory)) {
                Object catObj = rule.get("categories");
                boolean match = false;
                if (catObj instanceof List) {
                    match = ((List<String>) catObj).contains(selectedCategory);
                } else if (catObj instanceof String) {
                    match = selectedCategory.equals(catObj);
                }
                if (!match) continue;
            }

            if (!searchText.isEmpty()) {
                String name = (String) rule.get("name");
                String desc = (String) rule.get("description");
                if ((name == null || !name.toLowerCase().contains(searchText)) &&
                        (desc == null || !desc.toLowerCase().contains(searchText))) {
                    continue;
                }
            }

            filtered.add(rule);
        }

        displayRules(filtered);
    }

    // ================== JSON 解析器 ==================
    private List<Map<String, Object>> parseJson(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            return result;
        }
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return result;

        List<String> objectStrings = splitJsonObjects(json);
        for (String objStr : objectStrings) {
            result.add(parseJsonObject(objStr));
        }
        return result;
    }

    private List<String> splitJsonObjects(String s) {
        List<String> objects = new ArrayList<>();
        int braceCount = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') {
                    if (braceCount == 0) start = i;
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        objects.add(s.substring(start, i + 1));
                    }
                }
            }
        }
        return objects;
    }

    private Map<String, Object> parseJsonObject(String objStr) {
        Map<String, Object> map = new HashMap<>();
        objStr = objStr.trim();
        if (!objStr.startsWith("{") || !objStr.endsWith("}")) {
            return map;
        }
        objStr = objStr.substring(1, objStr.length() - 1).trim();
        if (objStr.isEmpty()) return map;

        List<String> pairs = splitKeyValuePairs(objStr);
        for (String pair : pairs) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx == -1) continue;
            String key = pair.substring(0, colonIdx).trim();
            String value = pair.substring(colonIdx + 1).trim();

            key = unquote(key);

            if (value.startsWith("\"")) {
                map.put(key, unquote(value));
            } else if (value.startsWith("[")) {
                map.put(key, parseJsonArray(value));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    private List<String> splitKeyValuePairs(String s) {
        List<String> pairs = new ArrayList<>();
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
                else if (c == ',' && braceCount == 0 && bracketCount == 0) {
                    pairs.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) {
            pairs.add(s.substring(start).trim());
        }
        return pairs;
    }

    private List<String> parseJsonArray(String arrayStr) {
        List<String> list = new ArrayList<>();
        arrayStr = arrayStr.trim();
        if (!arrayStr.startsWith("[") || !arrayStr.endsWith("]")) {
            return list;
        }
        arrayStr = arrayStr.substring(1, arrayStr.length() - 1).trim();
        if (arrayStr.isEmpty()) return list;

        boolean inString = false;
        StringBuilder sb = new StringBuilder();
        for (char c : arrayStr.toCharArray()) {
            if (c == '"' && (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ',' && !inString) {
                list.add(unquote(sb.toString().trim()));
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(unquote(sb.toString().trim()));
        }
        return list;
    }

    private String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        return s;
    }
    // ====================================================

    private void displayRules(List<Map<String, Object>> rules) {
        rulesPanel.removeAll();
        ruleUIs.clear();

        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            JPanel row = createRuleRow(rule, i % 2 == 0 ? COLOR_BG_SECONDARY : COLOR_BG_PRIMARY);
            rulesPanel.add(row);
        }

        rulesPanel.revalidate();
        rulesPanel.repaint();
    }

    private JPanel createHeaderRow() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(COLOR_BG_SECONDARY);

        String[] headers = {"分类", "规则名称", "类型", "当前值", "更改", "永久更改", "默认值"};
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 8, 3, 8);
        gbc.weighty = 0;
        gbc.anchor = GridBagConstraints.CENTER;

        for (int i = 0; i < headers.length; i++) {
            gbc.gridx = i;
            gbc.weightx = COL_WEIGHTS[i];
            JLabel label = new JLabel(headers[i], SwingConstants.CENTER);
            label.setForeground(COLOR_FG_BRIGHT);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
            label.setPreferredSize(new Dimension(COL_MIN_WIDTHS[i], COMPONENT_HEIGHT));
            header.add(label, gbc);
        }
        return header;
    }

    private JPanel createRuleRow(Map<String, Object> rule, Color bgColor) {
        String name = (String) rule.get("name");
        String desc = (String) rule.get("description");
        String currentValue = (String) rule.get("value");
        String defaultValue = (String) rule.get("default");
        List<String> options = (List<String>) rule.get("options");
        List<String> categories = (List<String>) rule.get("categories");

        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(bgColor);
        row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER));
        row.setPreferredSize(new Dimension(0, 35));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 8, 3, 8);
        gbc.weighty = 0;
        gbc.anchor = GridBagConstraints.CENTER;

        // 分类
        JLabel catLabel;
        if (categories != null && !categories.isEmpty()) {
            String catStr = String.join(",", categories);
            catLabel = new JLabel(catStr, SwingConstants.CENTER);
            catLabel.setToolTipText("分类: " + catStr);
        } else {
            catLabel = new JLabel(" ", SwingConstants.CENTER);
        }
        catLabel.setFont(catLabel.getFont().deriveFont(Font.ITALIC, 13f));
        catLabel.setForeground(COLOR_CATEGORY);
        catLabel.setPreferredSize(new Dimension(COL_MIN_WIDTHS[0], COMPONENT_HEIGHT));
        gbc.gridx = 0; gbc.weightx = COL_WEIGHTS[0];
        row.add(catLabel, gbc);

        // 规则名称
        JLabel nameLabel = new JLabel(name, SwingConstants.CENTER);
        nameLabel.setToolTipText(desc != null ? desc : name);
        nameLabel.setForeground(COLOR_FG_BRIGHT);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 14f));
        nameLabel.setPreferredSize(new Dimension(COL_MIN_WIDTHS[1], COMPONENT_HEIGHT));
        gbc.gridx = 1; gbc.weightx = COL_WEIGHTS[1];
        row.add(nameLabel, gbc);

        // 类型标签
        String typeText = "";
        Color typeColor = COLOR_TYPE_TEXT;
        if (options != null && !options.isEmpty()) {
            if (isStrictBoolean(options)) {
                typeText = "Boolean";
                typeColor = COLOR_TYPE_BOOLEAN;
            } else {
                typeText = "Enum";
                typeColor = COLOR_TYPE_ENUM;
            }
        } else {
            typeText = "Text";
        }
        JLabel typeLabel = new JLabel(typeText, SwingConstants.CENTER);
        typeLabel.setForeground(typeColor);
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        typeLabel.setPreferredSize(new Dimension(COL_MIN_WIDTHS[2], COMPONENT_HEIGHT));
        gbc.gridx = 2; gbc.weightx = COL_WEIGHTS[2];
        row.add(typeLabel, gbc);

        // 编辑器
        JComponent editor;
        if (options != null && !options.isEmpty()) {
            String[] cleanedOptions = options.stream()
                    .map(String::trim)
                    .map(s -> {
                        if (s.startsWith("[") && s.endsWith("]")) {
                            return s.substring(1, s.length() - 1);
                        } else if (s.startsWith("[")) {
                            return s.substring(1);
                        } else if (s.endsWith("]")) {
                            return s.substring(0, s.length() - 1);
                        }
                        return s;
                    })
                    .map(s -> s.replaceAll("^\"|\"$", ""))
                    .toArray(String[]::new);
            JComboBox<String> combo = new JComboBox<>(cleanedOptions);
            combo.setEditable(true);
            combo.setBackground(COLOR_BG_INPUT);
            combo.setForeground(COLOR_FG_BRIGHT);
            combo.setRenderer(new DarkComboBoxRenderer());
            ((JComponent) combo).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
            fixComboEditor(combo);
            setupComboScrollBar(combo);

            String cleanCurrent = currentValue == null ? "" : currentValue.trim().replaceAll("^\"|\"$", "");
            if (isStrictBoolean(options)) {
                cleanCurrent = cleanCurrent.toLowerCase();
            }
            combo.setSelectedItem(cleanCurrent);
            combo.setPreferredSize(new Dimension(COL_MIN_WIDTHS[3], COMPONENT_HEIGHT));
            editor = combo;
        } else {
            JTextField text = new JTextField(currentValue);
            text.setBackground(COLOR_BG_INPUT);
            text.setForeground(COLOR_FG_BRIGHT);
            text.setCaretColor(COLOR_FG_BRIGHT);
            text.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
            text.setHorizontalAlignment(JTextField.CENTER);
            text.setPreferredSize(new Dimension(COL_MIN_WIDTHS[3], COMPONENT_HEIGHT));
            editor = text;
        }
        gbc.gridx = 3; gbc.weightx = COL_WEIGHTS[3];
        row.add(editor, gbc);

        // 应用按钮
        JButton apply = new JButton("√");
        apply.setToolTipText("临时更改此规则");
        apply.setBackground(COLOR_BUTTON_NORMAL);
        apply.setForeground(Color.WHITE);
        apply.setFocusPainted(false);
        apply.setFont(apply.getFont().deriveFont(Font.BOLD, 14f));
        apply.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        apply.setPreferredSize(new Dimension(COL_MIN_WIDTHS[4], COMPONENT_HEIGHT));
        apply.addActionListener(ev -> {
            String newValue;
            if (editor instanceof JComboBox) {
                newValue = (String) ((JComboBox<?>) editor).getSelectedItem();
            } else {
                newValue = ((JTextField) editor).getText();
            }
            newValue = normalizeValue(newValue, options);
            String command = "carpet " + name + " " + newValue;
            logProgram("发送命令: " + command);
            String escapedCommand = escapeJsonString(command);
            injectAgent("{\"action\":\"commands\",\"commands\":\"" + escapedCommand + "\",\"outputDir\":\"" + getLogsDirectory().toString().replace("\\", "\\\\") + "\"}");
            if (monitoringEnabled) {
                startMcLogMonitoringIfNeeded();
            }
        });
        gbc.gridx = 4; gbc.weightx = COL_WEIGHTS[4];
        row.add(apply, gbc);

        // 永久按钮
        JButton permanent = new JButton("★");
        permanent.setToolTipText("永久更改此规则（写入配置文件）");
        permanent.setBackground(COLOR_BUTTON_PERMANENT);
        permanent.setForeground(Color.WHITE);
        permanent.setFocusPainted(false);
        permanent.setFont(permanent.getFont().deriveFont(Font.BOLD, 14f));
        permanent.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        permanent.setPreferredSize(new Dimension(COL_MIN_WIDTHS[5], COMPONENT_HEIGHT));
        permanent.addActionListener(ev -> {
            String newValue;
            if (editor instanceof JComboBox) {
                newValue = (String) ((JComboBox<?>) editor).getSelectedItem();
            } else {
                newValue = ((JTextField) editor).getText();
            }
            newValue = normalizeValue(newValue, options);
            String command = "carpet setDefault " + name + " " + newValue;
            logProgram("发送永久更改命令: " + command);
            String escapedCommand = escapeJsonString(command);
            injectAgent("{\"action\":\"commands\",\"commands\":\"" + escapedCommand + "\",\"outputDir\":\"" + getLogsDirectory().toString().replace("\\", "\\\\") + "\"}");
            if (monitoringEnabled) {
                startMcLogMonitoringIfNeeded();
            }
        });
        gbc.gridx = 5; gbc.weightx = COL_WEIGHTS[5];
        row.add(permanent, gbc);

        // 默认值标签
        JLabel defLabel = new JLabel(defaultValue != null ? defaultValue : "", SwingConstants.CENTER);
        defLabel.setForeground(COLOR_FG_PRIMARY);
        defLabel.setFont(defLabel.getFont().deriveFont(Font.PLAIN, 13f));
        defLabel.setToolTipText("默认值: " + defaultValue);
        defLabel.setPreferredSize(new Dimension(COL_MIN_WIDTHS[6], COMPONENT_HEIGHT));
        gbc.gridx = 6; gbc.weightx = COL_WEIGHTS[6];
        row.add(defLabel, gbc);

        ruleUIs.add(new RuleUI(name, editor, currentValue));
        return row;
    }

    private boolean isStrictBoolean(List<String> options) {
        if (options == null || options.size() != 2) return false;
        boolean hasTrue = false;
        boolean hasFalse = false;
        for (String opt : options) {
            if ("true".equalsIgnoreCase(opt)) hasTrue = true;
            else if ("false".equalsIgnoreCase(opt)) hasFalse = true;
            else return false;
        }
        return hasTrue && hasFalse;
    }

    private String normalizeValue(String value, List<String> options) {
        if (value == null) return "";
        value = value.trim();
        value = value.replaceAll("^\"|\"$", "");
        value = value.replaceAll("^\\[|\\]$", "");
        if (isStrictBoolean(options)) {
            value = value.toLowerCase();
            if (!value.equals("true") && !value.equals("false")) {
                return "false";
            }
        }
        return value;
    }

    private void injectAgent(String agentArgs) {
        if (agentTempFile == null || !agentTempFile.exists()) {
            logProgram("Agent文件不存在，无法注入。");
            return;
        }

        ProcessItem selected = (ProcessItem) processCombo.getSelectedItem();
        if (selected == null) {
            logProgram("请先选择一个进程。");
            return;
        }

        String pid = selected.id;
        logProgram("选择进程 " + selected.displayName + " (PID: " + pid + ")");
        logProgram("正在注入Agent...");

        new Thread(() -> {
            try {
                VirtualMachine vm = VirtualMachine.attach(pid);
                vm.loadAgent(agentTempFile.getAbsolutePath(), agentArgs);
                vm.detach();
                SwingUtilities.invokeLater(() -> logProgram("注入完成"));
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> logProgram("注入失败：" + ex.getMessage()));
            }
        }).start();
    }

    private void injectPingToGetLogPath() {
        ProcessItem selected = (ProcessItem) processCombo.getSelectedItem();
        if (selected == null) {
            waitingForLogPath = false;
            return;
        }
        if (agentTempFile == null || !agentTempFile.exists()) {
            logProgram("Agent文件不存在，无法获取日志路径。");
            waitingForLogPath = false;
            return;
        }

        // 取消之前正在等待的线程（如果有）
        synchronized (monitorLock) {
            if (pingWaitThread != null && pingWaitThread.isAlive()) {
                pingWaitThread.interrupt();
            }
            waitingForLogPath = true; // 标记正在等待
        }

        Path logsDir = getLogsDirectory();
        String agentArgs = "{\"action\":\"ping\",\"outputDir\":\"" + logsDir.toString().replace("\\", "\\\\") + "\"}";
        logProgram("正在注入 ping 以获取MC日志路径...");
        new Thread(() -> {
            try {
                VirtualMachine vm = VirtualMachine.attach(selected.id);
                vm.loadAgent(agentTempFile.getAbsolutePath(), agentArgs);
                vm.detach();
                // 注入成功后，启动等待路径的线程
                synchronized (monitorLock) {
                    pingWaitThread = new Thread(() -> waitForMcLogPathAndStart());
                    pingWaitThread.setDaemon(true);
                    pingWaitThread.start();
                }
            } catch (Exception e) {
                logProgram("注入 ping 失败：" + e.getMessage());
                synchronized (monitorLock) {
                    waitingForLogPath = false;
                    pingWaitThread = null;
                    // 删除路径文件
                    try {
                        Path logPathFile = getLogsDirectory().resolve("mc_log_path.txt");
                        Files.deleteIfExists(logPathFile);
                    } catch (IOException ignored) {}
                }
            }
        }).start();
    }

    // 程序日志（青色）- 精简版
    private void logProgram(String msg) {
        SwingUtilities.invokeLater(() -> {
            try {
                // 确保当前宽度与视口一致（监听器已处理，这里作为双重保障）
                Container parent = logPane.getParent();
                if (parent != null && parent.getWidth() > 0) {
                    logPane.setSize(parent.getWidth(), logPane.getHeight());
                }

                logDocument.insertString(logDocument.getLength(), msg + "\n", programStyle);

                // 限制行数：删除最旧的一行
                Element root = logDocument.getDefaultRootElement();
                int lineCount = root.getElementCount();
                if (lineCount > 100) {
                    Element firstLine = root.getElement(0);
                    int end = firstLine.getEndOffset();
                    logDocument.remove(0, end);
                }

                // 强制重新布局以确保自动换行
                logPane.revalidate();
                logPane.repaint();

                logPane.setCaretPosition(logDocument.getLength());
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        });
    }

    private void logMC(String msg) {
        SwingUtilities.invokeLater(() -> {
            try {
                // 确保当前宽度与视口一致
                Container parent = logPane.getParent();
                if (parent != null && parent.getWidth() > 0) {
                    logPane.setSize(parent.getWidth(), logPane.getHeight());
                }

                logDocument.insertString(logDocument.getLength(), "[MC] " + msg + "\n", mcStyle);

                Element root = logDocument.getDefaultRootElement();
                int lineCount = root.getElementCount();
                if (lineCount > 100) {
                    Element firstLine = root.getElement(0);
                    int end = firstLine.getEndOffset();
                    logDocument.remove(0, end);
                }

                // 强制重新布局以确保自动换行
                logPane.revalidate();
                logPane.repaint();

                logPane.setCaretPosition(logDocument.getLength());
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new InjectorApp().setVisible(true));
    }
}