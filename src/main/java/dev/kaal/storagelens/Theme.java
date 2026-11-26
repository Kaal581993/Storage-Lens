package dev.kaal.storagelens;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.util.List;

final class Theme {
    static final Color BG = new Color(0xF3F5F4), PAPER = Color.WHITE, INK = new Color(0x172B2A),
        MUTED = new Color(0x657773), LINE = new Color(0xE2E9E6), TEAL = new Color(0x087E6C),
        MINT = new Color(0xE4F4EB), NAV = new Color(0x152A28), NAV_TEXT = new Color(0xA8BFBA),
        AMBER = new Color(0xB77521), RED = new Color(0xAB3E37);
    static final Font BODY = new Font("SansSerif", Font.PLAIN, 13);
    static final Font MONO = new Font("Monospaced", Font.PLAIN, 11);
    static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        var defaults = UIManager.getDefaults();
        for (Object key : List.copyOf(defaults.keySet())) {
            if (defaults.get(key) instanceof Font) defaults.put(key, new FontUIResource(BODY));
        }
        UIManager.put("Panel.background", new ColorUIResource(BG));
        UIManager.put("Label.foreground", new ColorUIResource(INK));
        UIManager.put("Table.background", new ColorUIResource(PAPER));
        UIManager.put("Table.foreground", new ColorUIResource(INK));
        UIManager.put("Table.selectionBackground", new ColorUIResource(MINT));
        UIManager.put("Table.selectionForeground", new ColorUIResource(INK));
        UIManager.put("Table.gridColor", new ColorUIResource(LINE));
        UIManager.put("TextField.selectionBackground", new ColorUIResource(MINT));
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("OptionPane.background", new ColorUIResource(BG));
        UIManager.put("ToolTip.background", new ColorUIResource(PAPER));
    }
    static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text); label.setFont(BODY.deriveFont(style, size)); label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height)); return label;
    }
    static JLabel micro(String text) {
        JLabel label = label(text, 11, Font.BOLD, MUTED); label.setFont(MONO.deriveFont(Font.BOLD)); return label;
    }
    static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); panel.setAlignmentX(Component.LEFT_ALIGNMENT); return panel; }
    static JPanel vertical() { JPanel panel = transparent(null); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    static JScrollPane scrollBody(JPanel body) {
        JScrollPane scroll = new JScrollPane(body); scroll.setBorder(null); scroll.getViewport().setBackground(BG);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.getVerticalScrollBar().setUnitIncrement(22); return scroll;
    }
    static final class ScrollBody extends JPanel implements Scrollable {
        ScrollBody(LayoutManager layout) { super(layout); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height; }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 22; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(22, visible.height - 22); }
    }
    static JButton button(String text, boolean primary, Runnable action) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
                Color fill = primary ? TEAL : PAPER;
                if (!isEnabled()) fill = LINE;
                else if (getModel().isRollover()) fill = primary ? TEAL.darker() : MINT;
                g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                if (!primary) { g.setColor(LINE); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12); }
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setFont(BODY.deriveFont(Font.BOLD, 12));
        button.setForeground(primary ? Color.WHITE : INK); button.setOpaque(false); button.setContentAreaFilled(false);
        button.setBorder(new EmptyBorder(11, 16, 11, 16)); button.setFocusPainted(false); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run()); return button;
    }
    static class Card extends JPanel {
        Card(LayoutManager layout) { super(layout); setOpaque(false); setAlignmentX(Component.LEFT_ALIGNMENT); setBorder(new EmptyBorder(22, 24, 22, 24)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
            g.setColor(PAPER); g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            g.setColor(LINE); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20); g.dispose();
            super.paintComponent(graphics);
        }
    }
    static class Ring extends JComponent {
        double fraction;
        String center = "—", caption = "USED";
        Ring() { setPreferredSize(new Dimension(196, 196)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); smooth(g);
            int diameter = Math.min(getWidth(), getHeight()) - 28;
            int x = (getWidth() - diameter) / 2, y = (getHeight() - diameter) / 2;
            g.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(MINT); g.drawOval(x, y, diameter, diameter);
            g.setColor(TEAL); g.draw(new Arc2D.Double(x, y, diameter, diameter, 90, -360 * Math.max(0, Math.min(1, fraction)), Arc2D.OPEN));
            g.setFont(BODY.deriveFont(Font.BOLD, 37)); g.setColor(INK);
            g.drawString(center, (getWidth() - g.getFontMetrics().stringWidth(center)) / 2, getHeight() / 2 + 5);
            g.setFont(MONO.deriveFont(Font.BOLD)); g.setColor(MUTED);
            g.drawString(caption, (getWidth() - g.getFontMetrics().stringWidth(caption)) / 2, getHeight() / 2 + 31);
            g.dispose();
        }
    }
    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    static BufferedImage icon() {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); smooth(g);
        g.setColor(NAV); g.fillRoundRect(4, 4, 248, 248, 58, 58);
        g.setStroke(new BasicStroke(22, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x3D5A53)); g.drawOval(53, 53, 150, 150);
        g.setColor(new Color(0x88DFC0)); g.draw(new Arc2D.Double(53, 53, 150, 150, 90, -258, Arc2D.OPEN));
        g.setColor(Color.WHITE); g.fillRoundRect(99, 128, 17, 34, 5, 5); g.fillRoundRect(127, 106, 17, 56, 5, 5); g.fillRoundRect(155, 83, 17, 79, 5, 5);
        g.dispose(); return image;
    }
}
