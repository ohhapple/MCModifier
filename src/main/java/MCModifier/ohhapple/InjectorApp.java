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
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class InjectorApp extends JFrame {

    private JTextArea logArea;
    private JButton refreshButton;
    private JButton injectButton;
    private JButton loadRulesButton;
    private JComboBox<ProcessItem> processCombo;
    private File agentTempFile;

    private JPanel rulesPanel;          // 包含规则行的面板（可滚动）
    private JPanel fixedHeaderPanel;     // 固定表头面板
    private JScrollPane rulesScrollPane;
    private List<RuleUI> ruleUIs = new ArrayList<>();

    private List<Map<String, Object>> allRules = new ArrayList<>();
    private JComboBox<String> categoryFilterCombo;
    private JTextField searchField;
    private JPanel filterPanel;

    // 深色主题颜色定义（最终版）
    private static final Color COLOR_BG_PRIMARY = new Color(43, 43, 43);        // 主背景
    private static final Color COLOR_BG_SECONDARY = new Color(60, 63, 65);     // 面板背景
    private static final Color COLOR_BG_INPUT = new Color(90, 90, 90);         // 输入框/下拉框背景（提亮）
    private static final Color COLOR_BG_DROPDOWN = new Color(70, 70, 70);      // 下拉框弹出菜单背景
    private static final Color COLOR_FG_PRIMARY = new Color(187, 187, 187);    // 主要前景
    private static final Color COLOR_FG_BRIGHT = new Color(220, 220, 220);     // 高亮前景
    private static final Color COLOR_BORDER = new Color(140, 140, 140);        // 边框颜色
    private static final Color COLOR_BUTTON_NORMAL = new Color(70, 130, 200);  // 普通按钮（亮蓝色）
    private static final Color COLOR_BUTTON_HOVER = new Color(100, 160, 230);  // 按钮悬浮（更亮蓝）
    private static final Color COLOR_BUTTON_PERMANENT = new Color(160, 100, 50); // 永久按钮（保留棕色）
    private static final Color COLOR_CATEGORY = new Color(150, 150, 150);      // 分类文字
    private static final Color COLOR_TYPE_BOOLEAN = new Color(90, 180, 90);
    private static final Color COLOR_TYPE_ENUM = new Color(90, 140, 210);
    private static final Color COLOR_TYPE_TEXT = new Color(180, 180, 180);     // 类型文字

    // 列宽约束（最小宽度，权重）
    private static final int[] COL_MIN_WIDTHS = {120, 200, 60, 120, 60, 60, 140};
    private static final double[] COL_WEIGHTS = {0.12, 0.22, 0.06, 0.15, 0.08, 0.08, 0.29};

    // 组件高度（不占满整行）
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

        // 强制使用 Metal 外观，避免系统外观覆盖
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 设置窗口图标
        setIconImage(loadIcon());

        // 强制设置根面板和内容面板背景
        getRootPane().setBackground(COLOR_BG_PRIMARY);
        getContentPane().setBackground(COLOR_BG_PRIMARY);

        // 覆盖 UIManager 颜色
        overrideUIManagerColors();

        // 设置全局字体
        setUIFont("微软雅黑", Font.PLAIN, 14);

        // Tooltip 设置
        ToolTipManager ttm = ToolTipManager.sharedInstance();
        ttm.setInitialDelay(0);
        ttm.setDismissDelay(Integer.MAX_VALUE);

        // 主布局
        setLayout(new BorderLayout(5, 5));

        // 顶部面板
        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);

        // 中心面板（过滤 + 规则列表 + 固定表头）
        JPanel centerPanel = createCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        // 底部日志面板
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        // 释放 agent
        releaseAgentJar();
        refreshProcessList();

        // 初始时移除焦点（窗口本身获得焦点）
        SwingUtilities.invokeLater(() -> this.requestFocusInWindow());

        // 为进程下拉框添加选择后移除焦点的监听
        processCombo.addActionListener(e -> SwingUtilities.invokeLater(() -> this.requestFocusInWindow()));

        // 点击空白区域移除焦点
        setupGlobalFocusListener();

        // 在显示后强制刷新（递归应用深色，但跳过按钮）
        SwingUtilities.invokeLater(() -> applyDarkTheme());
    }

    /** 设置全局鼠标监听，点击非交互式组件时移除焦点 */
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

    /** 判断组件或其父组件是否为可聚焦的交互式组件 */
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

    /** 覆盖 UIManager 颜色 */
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
        // 获取系统所有可用字体名称
        String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        // 检查首选字体是否存在
        for (String font : availableFonts) {
            if (font.equalsIgnoreCase(preferredFontName)) {
                return new Font(preferredFontName, style, size);
            }
        }
        // 备选字体列表（按优先级排列）
        String[] fallbacks = {"SansSerif", "Dialog", "Arial", "Tahoma"};
        for (String fallback : fallbacks) {
            for (String font : availableFonts) {
                if (font.equalsIgnoreCase(fallback)) {
                    return new Font(fallback, style, size);
                }
            }
        }
        // 兜底使用逻辑字体 SansSerif
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

    /** 在窗口显示后强制应用深色主题（递归设置背景，但跳过按钮以保持动态颜色） */
    private void applyDarkTheme() {
        setDeepBackground(getContentPane(), COLOR_BG_PRIMARY);
        repaint();
    }

    /** 递归设置所有组件的背景、前景和边框（跳过按钮） */
    private void setDeepBackground(Container container, Color bg) {
        container.setBackground(bg);
        for (Component comp : container.getComponents()) {
            // 跳过按钮，保持其动态颜色
            if (comp instanceof JButton) {
                comp.setForeground(Color.WHITE);
                continue;
            }

            comp.setBackground(bg);

            if (comp instanceof JComboBox) {
                JComboBox<?> cb = (JComboBox<?>) comp;
                cb.setBackground(COLOR_BG_INPUT);
                cb.setForeground(COLOR_FG_BRIGHT);
                if (cb instanceof JComponent) {
                    ((JComponent) cb).setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
                }
                fixComboEditor(cb);
                // 统一为所有下拉框添加滚动条样式监听
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

    /** 为下拉框添加弹出菜单监听器，以设置其内部滚动条样式 */
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

    /** 强制修复下拉框编辑器背景，并设置合适高度 */
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

    /** 设置下拉框弹出菜单中的滚动条为深色样式 */
    private void setComboPopupScrollBarUI(JComboBox<?> combo) {
        Object popup = combo.getAccessibleContext().getAccessibleChild(0);
        if (popup instanceof JComponent) {
            findAndSetScrollBarUI((JComponent) popup);
        }
    }

    /** 递归查找容器中的 JScrollPane 并设置其滚动条UI */
    private void findAndSetScrollBarUI(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JScrollPane) {
                JScrollPane scrollPane = (JScrollPane) comp;
                scrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
                scrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
                scrollPane.getVerticalScrollBar().setBackground(COLOR_BG_PRIMARY);
                scrollPane.getHorizontalScrollBar().setBackground(COLOR_BG_PRIMARY);
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
        setupComboScrollBar(processCombo); // 统一设置滚动条样式

        refreshButton = createStyledButton("刷新进程列表", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);
        injectButton = createStyledButton("执行命令", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);
        loadRulesButton = createStyledButton("加载规则列表", COLOR_BUTTON_NORMAL, COLOR_BUTTON_HOVER);

        refreshButton.addActionListener(this::onRefresh);
        injectButton.addActionListener(this::onInject);
        loadRulesButton.addActionListener(this::onLoadRules);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftPanel.setBackground(COLOR_BG_SECONDARY);
        JLabel lblTarget = new JLabel("目标进程:");
        lblTarget.setForeground(COLOR_FG_PRIMARY);
        leftPanel.add(lblTarget);
        leftPanel.add(processCombo);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightPanel.setBackground(COLOR_BG_SECONDARY);
        rightPanel.add(refreshButton);
        rightPanel.add(injectButton);
        rightPanel.add(loadRulesButton);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(COLOR_BG_PRIMARY);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // ========== 过滤面板 ==========
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

        // ========== 规则区域（固定表头 + 可滚动行） ==========
        JPanel rulesArea = new JPanel(new BorderLayout());
        rulesArea.setBackground(COLOR_BG_PRIMARY);

        // 创建固定表头
        fixedHeaderPanel = createHeaderRow();
        fixedHeaderPanel.setBackground(COLOR_BG_SECONDARY);
        // 移除表头自身的边框（因为滚动面板会提供整体边框）
        fixedHeaderPanel.setBorder(BorderFactory.createEmptyBorder());

        // 可滚动规则行
        rulesPanel = new JPanel();
        rulesPanel.setLayout(new BoxLayout(rulesPanel, BoxLayout.Y_AXIS));
        rulesPanel.setBackground(COLOR_BG_PRIMARY);

        rulesScrollPane = new JScrollPane(rulesPanel);
        rulesScrollPane.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        rulesScrollPane.getVerticalScrollBar().setUnitIncrement(24);
        rulesScrollPane.getViewport().setBackground(COLOR_BG_PRIMARY);

        // 设置深色滚动条
        rulesScrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        rulesScrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        // ★★★ 关键：将 fixedHeaderPanel 设置为列标题 ★★★
        rulesScrollPane.setColumnHeaderView(fixedHeaderPanel);

        rulesArea.add(rulesScrollPane, BorderLayout.CENTER);

        panel.add(rulesArea, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(COLOR_BG_SECONDARY);
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                "日志", TitledBorder.LEFT, TitledBorder.TOP));
        ((TitledBorder) panel.getBorder()).setTitleColor(COLOR_FG_PRIMARY);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(COLOR_BG_INPUT);
        logArea.setForeground(COLOR_FG_BRIGHT);
        logArea.setCaretColor(COLOR_FG_BRIGHT);
        logArea.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setPreferredSize(new Dimension(1400, 180));
        logScrollPane.getViewport().setBackground(COLOR_BG_INPUT);
        logScrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        logScrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        panel.add(logScrollPane, BorderLayout.CENTER);
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

    // ==================== 原有功能方法（保持不变） ====================

    private Path getLogsDirectory() {
        Path jarDir = Paths.get(getJarDirectory());
        Path logsDir = jarDir.resolve("MCMlogs");
        if (!Files.exists(logsDir)) {
            try {
                Files.createDirectories(logsDir);
            } catch (IOException e) {
                e.printStackTrace();
                log("创建日志目录失败: " + e.getMessage());
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
                        log("错误：内嵌的agent.jar未找到，请重新打包。");
                        return;
                    }
                    agentTempFile = File.createTempFile("mcagent_", ".jar");
                    agentTempFile.deleteOnExit();
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, agentTempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    log("Agent临时文件已创建：" + agentTempFile.getAbsolutePath());
                }
            } else {
                log("警告：未从JAR运行，无法释放agent.jar，请先打包。");
            }
        } catch (Exception e) {
            e.printStackTrace();
            log("释放agent.jar失败：" + e.getMessage());
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
                log("未找到任何Java进程。");
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
            log("已刷新进程列表，共 " + vms.size() + " 个Java进程。");
        } catch (Exception e) {
            e.printStackTrace();
            log("获取进程列表失败：" + e.getMessage());
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
        String agentArgs = "{\"action\":\"commands\",\"commands\":\"" + commandStr.trim() + "\",\"outputDir\":\"" + logsDir.toString().replace("\\", "\\\\") + "\"}";
        log("生成的参数: " + agentArgs);
        injectAgent(agentArgs);
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
            log("等待规则文件超时");
        }).start();
    }

    private void loadRulesFromFile(Path jsonFile) {
        try {
            String content = new String(Files.readAllBytes(jsonFile));
            allRules = parseJson(content);
            buildCategoryCombo();
            applyFilter();
            log("已加载 " + allRules.size() + " 条规则");
        } catch (Exception e) {
            log("解析规则文件失败: " + e);
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

        // 类型标签（修改点：使用 isStrictBoolean 判断）
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
            // 选项保持原样，不进行大小写转换
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

            // 当前值处理：布尔类型转换为小写以匹配选项，枚举保留原样
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
            log("发送命令: " + command);
            injectAgent("{\"action\":\"commands\",\"commands\":\"" + command + "\",\"outputDir\":\"" + getLogsDirectory().toString().replace("\\", "\\\\") + "\"}");
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
            log("发送永久更改命令: " + command);
            injectAgent("{\"action\":\"commands\",\"commands\":\"" + command + "\",\"outputDir\":\"" + getLogsDirectory().toString().replace("\\", "\\\\") + "\"}");
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
            else return false; // 存在其他选项
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
            log("Agent文件不存在，无法注入。");
            return;
        }

        ProcessItem selected = (ProcessItem) processCombo.getSelectedItem();
        if (selected == null) {
            log("请先选择一个进程。");
            return;
        }

        String pid = selected.id;
        log("选择进程 PID: " + pid + " (" + selected.displayName + ")");
        log("正在注入Agent，参数: " + agentArgs);

        new Thread(() -> {
            try {
                VirtualMachine vm = VirtualMachine.attach(pid);
                log("正在注入到进程 " + pid);
                vm.loadAgent(agentTempFile.getAbsolutePath(), agentArgs);
                vm.detach();
                SwingUtilities.invokeLater(() -> log("注入完成"));
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> log("注入失败：" + ex.getMessage()));
            }
        }).start();
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");

            // 限制日志行数不超过100行
            int lineCount = logArea.getLineCount();
            if (lineCount > 100) {
                try {
                    // 获取需要删除的行数（多余的行数）
                    int linesToRemove = lineCount - 100;
                    // 获取第 linesToRemove 行的结束位置（即要保留的第一行的起始位置）
                    int endOffset = logArea.getLineEndOffset(linesToRemove - 1);
                    // 删除从开头到该位置的内容
                    logArea.getDocument().remove(0, endOffset);
                } catch (javax.swing.text.BadLocationException e) {
                    e.printStackTrace();
                }
            }

            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new InjectorApp().setVisible(true));
    }
}