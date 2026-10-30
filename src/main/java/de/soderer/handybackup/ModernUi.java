package de.soderer.handybackup;

import java.awt.Adjustable;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicProgressBarUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * Small, dependency-free toolkit for a modern look of the HandyBackup main window:
 * gradient header, rounded "cards" with soft shadow, flat rounded buttons, pill shaped info chips,
 * a rounded progress bar, thin scroll bars and simple vector icons.
 *
 * All components paint themselves completely, so they look the same with every look and feel
 * (Windows, GTK, Metal). Native components like combo boxes and spinners are left untouched.
 */
public final class ModernUi {
	// Color palette
	public static final Color BACKGROUND = new Color(0xF2F4F8);
	public static final Color CARD = Color.WHITE;
	public static final Color CARD_BORDER = new Color(0xDDE2EA);
	public static final Color INNER_BORDER = new Color(0xE6E9F0);
	public static final Color TEXT = new Color(0x1F2633);
	public static final Color MUTED = new Color(0x6B7385);
	public static final Color ACCENT = new Color(0x3B6FE0);
	public static final Color ACCENT_HOVER = new Color(0x4A7DEC);
	public static final Color ACCENT_PRESSED = new Color(0x2C55B8);
	public static final Color ACCENT_LIGHT = new Color(0xE8EFFD);
	public static final Color HEADER_START = new Color(0x2F5BD3);
	public static final Color HEADER_END = new Color(0x7B4FD9);
	public static final Color SUCCESS = new Color(0x2E9E5B);
	public static final Color SUCCESS_LIGHT = new Color(0xE3F5EA);
	public static final Color WARNING = new Color(0xC97A12);
	public static final Color WARNING_LIGHT = new Color(0xFDF1DF);
	public static final Color DANGER = new Color(0xD64545);
	public static final Color DANGER_LIGHT = new Color(0xFDECEC);
	public static final Color NEUTRAL_LIGHT = new Color(0xEEF1F6);
	public static final Color DISABLED_BACKGROUND = new Color(0xE4E7ED);
	public static final Color DISABLED_TEXT = new Color(0xA3A9B6);
	public static final Color LOG_BACKGROUND = new Color(0xFAFBFD);

	private static final int CARD_ARC = 16;
	private static final int BUTTON_ARC = 10;

	private ModernUi() {
		// Utility class
	}

	/** Vector icons drawn with Java2D, so no image resources are needed */
	public enum IconKind {
		PHONE, SERVER, REFRESH, PLAY, STOP, CLOSE, LOG, SEARCH, FOLDER, GEAR, HELP
	}

	// ---------------------------------------------------------------- fonts and rendering

	public static Font baseFont() {
		final Font font = UIManager.getFont("Label.font");
		return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	}

	public static Font titleFont() {
		return baseFont().deriveFont(Font.BOLD, baseFont().getSize2D() + 1.5f);
	}

	/**
	 * Creates a graphics copy with antialiasing and the desktop's text rendering hints.
	 */
	static Graphics2D prepare(final Graphics graphics) {
		final Graphics2D g2 = (Graphics2D) graphics.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		final Object desktopHints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
		if (desktopHints instanceof final Map<?, ?> hints) {
			g2.addRenderingHints(hints);
		} else {
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
		return g2;
	}

	static Color withAlpha(final Color color, final int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}

	static Color mix(final Color color, final Color other, final double otherShare) {
		final double share = 1 - otherShare;
		return new Color(
			(int) Math.round(color.getRed() * share + other.getRed() * otherShare),
			(int) Math.round(color.getGreen() * share + other.getGreen() * otherShare),
			(int) Math.round(color.getBlue() * share + other.getBlue() * otherShare));
	}

	public static String escapeHtml(final String text) {
		if (text == null) {
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String toHex(final Color color) {
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	// ---------------------------------------------------------------- icons

	/**
	 * Simple line icon in a fixed color. {@link ModernButton} repaints it in the button's text color.
	 */
	public static class ThemeIcon implements Icon {
		private final IconKind kind;
		private final int size;
		private final Color color;

		public ThemeIcon(final IconKind kind, final int size, final Color color) {
			this.kind = kind;
			this.size = size;
			this.color = color;
		}

		public IconKind getKind() {
			return kind;
		}

		@Override
		public int getIconWidth() {
			return size;
		}

		@Override
		public int getIconHeight() {
			return size;
		}

		@Override
		public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
			paint(graphics, x, y, color);
		}

		public void paint(final Graphics graphics, final int x, final int y, final Color paintColor) {
			final Graphics2D g2 = prepare(graphics);
			try {
				g2.translate(x, y);
				g2.setColor(paintColor);
				final double s = size;
				g2.setStroke(new BasicStroke((float) Math.max(1.4, s / 10.0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				switch (kind) {
					case PHONE -> {
						g2.draw(new RoundRectangle2D.Double(s * 0.27, s * 0.06, s * 0.46, s * 0.88, s * 0.18, s * 0.18));
						g2.draw(new Line2D.Double(s * 0.44, s * 0.78, s * 0.56, s * 0.78));
					}
					case SERVER -> {
						g2.draw(new RoundRectangle2D.Double(s * 0.1, s * 0.14, s * 0.8, s * 0.3, s * 0.12, s * 0.12));
						g2.draw(new RoundRectangle2D.Double(s * 0.1, s * 0.56, s * 0.8, s * 0.3, s * 0.12, s * 0.12));
						final double dot = s * 0.12;
						g2.fill(new Ellipse2D.Double(s * 0.68, s * 0.29 - dot / 2, dot, dot));
						g2.fill(new Ellipse2D.Double(s * 0.68, s * 0.71 - dot / 2, dot, dot));
					}
					case REFRESH -> {
						final double radius = s * 0.33;
						final double center = s * 0.5;
						g2.draw(new Arc2D.Double(center - radius, center - radius, radius * 2, radius * 2, 80, 280, Arc2D.OPEN));
						// Arrow head at the end of the arc (angle 0, moving upwards)
						final Path2D arrow = new Path2D.Double();
						arrow.moveTo(center + radius - s * 0.16, center - s * 0.02);
						arrow.lineTo(center + radius + s * 0.15, center - s * 0.02);
						arrow.lineTo(center + radius, center - s * 0.22);
						arrow.closePath();
						g2.fill(arrow);
					}
					case PLAY -> {
						final Path2D triangle = new Path2D.Double();
						triangle.moveTo(s * 0.28, s * 0.16);
						triangle.lineTo(s * 0.28, s * 0.84);
						triangle.lineTo(s * 0.84, s * 0.5);
						triangle.closePath();
						g2.fill(triangle);
						g2.draw(triangle);
					}
					case STOP -> g2.fill(new RoundRectangle2D.Double(s * 0.22, s * 0.22, s * 0.56, s * 0.56, s * 0.14, s * 0.14));
					case CLOSE -> {
						g2.draw(new Line2D.Double(s * 0.26, s * 0.26, s * 0.74, s * 0.74));
						g2.draw(new Line2D.Double(s * 0.74, s * 0.26, s * 0.26, s * 0.74));
					}
					case LOG -> {
						g2.draw(new Line2D.Double(s * 0.18, s * 0.28, s * 0.82, s * 0.28));
						g2.draw(new Line2D.Double(s * 0.18, s * 0.5, s * 0.82, s * 0.5));
						g2.draw(new Line2D.Double(s * 0.18, s * 0.72, s * 0.6, s * 0.72));
					}
					case SEARCH -> {
						g2.draw(new Ellipse2D.Double(s * 0.14, s * 0.14, s * 0.5, s * 0.5));
						g2.draw(new Line2D.Double(s * 0.58, s * 0.58, s * 0.86, s * 0.86));
					}
					case FOLDER -> {
						final Path2D folder = new Path2D.Double();
						folder.moveTo(s * 0.08, s * 0.24);
						folder.lineTo(s * 0.38, s * 0.24);
						folder.lineTo(s * 0.48, s * 0.34);
						folder.lineTo(s * 0.92, s * 0.34);
						folder.lineTo(s * 0.92, s * 0.82);
						folder.lineTo(s * 0.08, s * 0.82);
						folder.closePath();
						g2.draw(folder);
					}
					case GEAR -> {
						final double center = s * 0.5;
						g2.draw(new Ellipse2D.Double(center - s * 0.2, center - s * 0.2, s * 0.4, s * 0.4));
						g2.setStroke(new BasicStroke((float) (s / 7.0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
						for (int tooth = 0; tooth < 8; tooth++) {
							final double angle = Math.PI / 4 * tooth;
							g2.draw(new Line2D.Double(center + Math.cos(angle) * s * 0.3, center + Math.sin(angle) * s * 0.3,
								center + Math.cos(angle) * s * 0.4, center + Math.sin(angle) * s * 0.4));
						}
					}
					case HELP -> {
						g2.draw(new Ellipse2D.Double(s * 0.08, s * 0.08, s * 0.84, s * 0.84));
						final Font font = baseFont().deriveFont(Font.BOLD, (float) (s * 0.62));
						g2.setFont(font);
						final FontMetrics metrics = g2.getFontMetrics();
						g2.drawString("?", (float) ((s - metrics.stringWidth("?")) / 2.0), (float) ((s - metrics.getHeight()) / 2.0 + metrics.getAscent()));
					}
					default -> {
						// nothing
					}
				}
			} finally {
				g2.dispose();
			}
		}
	}

	// ---------------------------------------------------------------- buttons

	public enum ButtonStyle {
		/** Filled accent button for the main action */
		PRIMARY,
		/** White button with thin border */
		SECONDARY,
		/** White button with red text and border, e.g. for cancel */
		DANGEROUS,
		/** Transparent button on the gradient header */
		HEADER
	}

	/**
	 * Flat rounded button with hover and pressed effect, painted completely by itself.
	 */
	public static class ModernButton extends JButton {
		private static final long serialVersionUID = 1L;

		private final ButtonStyle style;

		public ModernButton(final String text, final IconKind iconKind, final ButtonStyle style) {
			super(text);
			this.style = style;
			if (iconKind != null) {
				setIcon(new ThemeIcon(iconKind, style == ButtonStyle.HEADER && (text == null || text.isEmpty()) ? 20 : 15, TEXT));
			}
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setIconTextGap(8);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			if (text == null || text.isEmpty()) {
				setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
			} else if (style == ButtonStyle.PRIMARY) {
				setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 22));
			} else {
				setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
			}
			if (style == ButtonStyle.PRIMARY) {
				setFont(baseFont().deriveFont(Font.BOLD, baseFont().getSize2D() + 1f));
			} else {
				setFont(baseFont());
			}
		}

		@Override
		public Dimension getPreferredSize() {
			if (isPreferredSizeSet()) {
				return super.getPreferredSize();
			}
			final Insets insets = getInsets();
			final FontMetrics metrics = getFontMetrics(getFont());
			final String text = getText();
			final int textWidth = text == null || text.isEmpty() ? 0 : metrics.stringWidth(text);
			final Icon icon = getIcon();
			final int iconWidth = icon == null ? 0 : icon.getIconWidth();
			final int iconHeight = icon == null ? 0 : icon.getIconHeight();
			final int gap = textWidth > 0 && iconWidth > 0 ? getIconTextGap() : 0;
			final int width = insets.left + insets.right + textWidth + gap + iconWidth;
			final int height = insets.top + insets.bottom + Math.max(textWidth > 0 ? metrics.getHeight() : 0, iconHeight);
			if (textWidth == 0) {
				final int side = Math.max(width, height);
				return new Dimension(side, side);
			}
			return new Dimension(width, Math.max(height, style == ButtonStyle.PRIMARY ? 36 : 32));
		}

		@Override
		public Dimension getMinimumSize() {
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(final Graphics graphics) {
			final Graphics2D g2 = prepare(graphics);
			try {
				final ButtonModel buttonModel = getModel();
				final boolean enabled = isEnabled();
				final boolean pressed = buttonModel.isArmed() && buttonModel.isPressed();
				final boolean hover = buttonModel.isRollover() && enabled;
				final int width = getWidth();
				final int height = getHeight();
				final RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, BUTTON_ARC, BUTTON_ARC);

				final Color foreground;
				switch (style) {
					case PRIMARY -> {
						if (enabled) {
							final Color base = pressed ? ACCENT_PRESSED : hover ? ACCENT_HOVER : ACCENT;
							// Soft glow below the button
							g2.setColor(withAlpha(ACCENT, hover ? 60 : 35));
							g2.fill(new RoundRectangle2D.Double(1, 3, width - 2.0, height - 3.0, BUTTON_ARC, BUTTON_ARC));
							g2.setPaint(new GradientPaint(0, 0, mix(base, Color.WHITE, 0.14), 0, height, base));
							g2.fill(new RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 2.5, BUTTON_ARC, BUTTON_ARC));
							foreground = Color.WHITE;
						} else {
							g2.setColor(DISABLED_BACKGROUND);
							g2.fill(new RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 2.5, BUTTON_ARC, BUTTON_ARC));
							foreground = DISABLED_TEXT;
						}
					}
					case SECONDARY -> {
						g2.setColor(!enabled ? new Color(0xF6F7F9) : pressed ? new Color(0xE3E9F5) : hover ? new Color(0xF0F4FB) : CARD);
						g2.fill(shape);
						g2.setColor(hover ? withAlpha(ACCENT, 150) : CARD_BORDER);
						g2.draw(shape);
						foreground = enabled ? TEXT : DISABLED_TEXT;
					}
					case DANGEROUS -> {
						g2.setColor(!enabled ? new Color(0xF6F7F9) : pressed ? new Color(0xF9DADA) : hover ? DANGER_LIGHT : CARD);
						g2.fill(shape);
						g2.setColor(enabled ? withAlpha(DANGER, hover ? 200 : 140) : CARD_BORDER);
						g2.draw(shape);
						foreground = enabled ? DANGER : DISABLED_TEXT;
					}
					case HEADER -> {
						if (enabled && (hover || pressed)) {
							g2.setColor(withAlpha(Color.WHITE, pressed ? 80 : 45));
							g2.fill(shape);
						}
						foreground = enabled ? Color.WHITE : withAlpha(Color.WHITE, 110);
					}
					default -> {
						if (enabled && (hover || pressed)) {
							g2.setColor(withAlpha(Color.WHITE, pressed ? 80 : 45));
							g2.fill(shape);
						}
						foreground = enabled ? Color.WHITE : withAlpha(Color.WHITE, 110);
					}
				}

				paintContent(g2, foreground, width, height - (style == ButtonStyle.PRIMARY ? 2 : 0));

				if (isFocusOwner() && enabled) {
					g2.setStroke(new BasicStroke(2f));
					g2.setColor(style == ButtonStyle.HEADER ? withAlpha(Color.WHITE, 160) : withAlpha(ACCENT, style == ButtonStyle.PRIMARY ? 0 : 110));
					g2.draw(new RoundRectangle2D.Double(1.5, 1.5, width - 3.0, height - 3.0, BUTTON_ARC, BUTTON_ARC));
				}
			} finally {
				g2.dispose();
			}
		}

		private void paintContent(final Graphics2D g2, final Color foreground, final int width, final int height) {
			final FontMetrics metrics = g2.getFontMetrics(getFont());
			final String text = getText();
			final int textWidth = text == null || text.isEmpty() ? 0 : metrics.stringWidth(text);
			final Icon icon = getIcon();
			final int iconWidth = icon == null ? 0 : icon.getIconWidth();
			final int gap = textWidth > 0 && iconWidth > 0 ? getIconTextGap() : 0;
			int x = (width - (iconWidth + gap + textWidth)) / 2;
			if (icon != null) {
				final int iconY = (height - icon.getIconHeight()) / 2;
				if (icon instanceof final ThemeIcon themeIcon) {
					themeIcon.paint(g2, x, iconY, foreground);
				} else {
					final Graphics2D iconGraphics = (Graphics2D) g2.create();
					if (!isEnabled()) {
						iconGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
					}
					icon.paintIcon(this, iconGraphics, x, iconY);
					iconGraphics.dispose();
				}
				x += iconWidth + gap;
			}
			if (textWidth > 0) {
				g2.setFont(getFont());
				g2.setColor(foreground);
				g2.drawString(text, x, (height - metrics.getHeight()) / 2 + metrics.getAscent());
			}
		}
	}

	// ---------------------------------------------------------------- header

	/**
	 * Header band with a horizontal color gradient and some decorative translucent circles.
	 */
	public static class GradientHeader extends JPanel {
		private static final long serialVersionUID = 1L;

		public GradientHeader() {
			super(new BorderLayout(14, 0));
			setOpaque(true);
			setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 14));
		}

		@Override
		protected void paintComponent(final Graphics graphics) {
			final Graphics2D g2 = prepare(graphics);
			try {
				final int width = getWidth();
				final int height = getHeight();
				g2.setPaint(new GradientPaint(0, 0, HEADER_START, width, height, HEADER_END));
				g2.fillRect(0, 0, width, height);
				g2.setColor(withAlpha(Color.WHITE, 18));
				g2.fill(new Ellipse2D.Double(width * 0.62, -height * 1.2, height * 2.6, height * 2.6));
				g2.fill(new Ellipse2D.Double(width * 0.80, height * 0.25, height * 1.6, height * 1.6));
				g2.setColor(withAlpha(Color.WHITE, 12));
				g2.fill(new Ellipse2D.Double(width * 0.45, height * 0.55, height * 1.1, height * 1.1));
			} finally {
				g2.dispose();
			}
		}
	}

	/**
	 * White round badge with the application icon (or a phone symbol if no icon is available) for the header.
	 */
	public static JComponent createLogo(final Icon applicationIcon) {
		return new JComponent() {
			private static final long serialVersionUID = 1L;

			@Override
			public Dimension getPreferredSize() {
				return new Dimension(44, 44);
			}

			@Override
			protected void paintComponent(final Graphics graphics) {
				final Graphics2D g2 = prepare(graphics);
				try {
					final int size = Math.min(getWidth(), getHeight());
					g2.setColor(withAlpha(Color.WHITE, 235));
					g2.fill(new RoundRectangle2D.Double(0, 0, size, size, 14, 14));
					if (applicationIcon != null && applicationIcon.getIconWidth() > 0) {
						final int iconSize = size - 12;
						final double scale = Math.min((double) iconSize / applicationIcon.getIconWidth(), (double) iconSize / applicationIcon.getIconHeight());
						final Graphics2D iconGraphics = (Graphics2D) g2.create();
						iconGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
						iconGraphics.translate((size - applicationIcon.getIconWidth() * scale) / 2, (size - applicationIcon.getIconHeight() * scale) / 2);
						iconGraphics.scale(scale, scale);
						applicationIcon.paintIcon(this, iconGraphics, 0, 0);
						iconGraphics.dispose();
					} else {
						new ThemeIcon(IconKind.PHONE, size - 14, HEADER_START).paintIcon(this, g2, 7, 7);
					}
				} finally {
					g2.dispose();
				}
			}
		};
	}

	// ---------------------------------------------------------------- cards

	/**
	 * White rounded panel with soft shadow and an optional title row.
	 * The content is added with {@link #setContent(Component)}, additional components for the right side of the
	 * title row with {@link #addHeaderComponent(Component)}.
	 */
	public static class Card extends JPanel {
		private static final long serialVersionUID = 1L;
		private static final int SHADOW = 5;

		private final JPanel headerAccessories = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

		public Card(final String title, final IconKind iconKind, final int padding) {
			super(new BorderLayout(0, 10));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(SHADOW - 2 + padding, SHADOW + padding, SHADOW + 2 + padding, SHADOW + padding));
			if (title != null) {
				final JPanel header = new JPanel(new BorderLayout(10, 0));
				header.setOpaque(false);
				header.add(createTitleLabel(title, iconKind), BorderLayout.WEST);
				headerAccessories.setOpaque(false);
				header.add(headerAccessories, BorderLayout.EAST);
				add(header, BorderLayout.NORTH);
			}
		}

		public void setContent(final Component content) {
			add(content, BorderLayout.CENTER);
		}

		public void addHeaderComponent(final Component component) {
			headerAccessories.add(component);
		}

		@Override
		protected void paintComponent(final Graphics graphics) {
			final Graphics2D g2 = prepare(graphics);
			try {
				final int width = getWidth();
				final int height = getHeight();
				// Soft shadow, slightly shifted downwards
				for (int layer = 0; layer < SHADOW; layer++) {
					g2.setColor(new Color(20, 30, 60, 4 + layer * 2));
					final double inset = layer;
					g2.fill(new RoundRectangle2D.Double(inset, inset + 2, width - inset * 2, height - inset * 2 - 1, CARD_ARC + SHADOW - layer, CARD_ARC + SHADOW - layer));
				}
				final RoundRectangle2D shape = new RoundRectangle2D.Double(SHADOW, SHADOW - 1, width - SHADOW * 2.0, height - SHADOW * 2.0, CARD_ARC, CARD_ARC);
				g2.setColor(CARD);
				g2.fill(shape);
				g2.setColor(CARD_BORDER);
				g2.draw(shape);
			} finally {
				g2.dispose();
			}
		}
	}

	/**
	 * Bold title with a small accent colored icon in a soft square.
	 */
	public static JLabel createTitleLabel(final String text, final IconKind iconKind) {
		final JLabel label = new JLabel(text);
		label.setFont(titleFont());
		label.setForeground(TEXT);
		if (iconKind != null) {
			label.setIcon(new BadgeIcon(iconKind));
			label.setIconTextGap(9);
		}
		return label;
	}

	/** Icon in a light accent colored rounded square, used for card titles */
	private static class BadgeIcon implements Icon {
		private static final int SIZE = 26;
		private final ThemeIcon icon;

		BadgeIcon(final IconKind kind) {
			icon = new ThemeIcon(kind, 16, ACCENT);
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}

		@Override
		public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
			final Graphics2D g2 = prepare(graphics);
			try {
				g2.setColor(ACCENT_LIGHT);
				g2.fill(new RoundRectangle2D.Double(x, y, SIZE, SIZE, 9, 9));
			} finally {
				g2.dispose();
			}
			icon.paintIcon(component, graphics, x + (SIZE - 16) / 2, y + (SIZE - 16) / 2);
		}
	}

	/** Small grey text, e.g. for labels in front of input fields */
	public static JLabel createMutedLabel(final String text) {
		final JLabel label = new JLabel(text);
		label.setForeground(MUTED);
		return label;
	}

	// ---------------------------------------------------------------- chips

	/** Color variants of {@link Chip} */
	public enum ChipStyle {
		NEUTRAL, ACCENTUATED, SUCCESSFUL, ALERT, DANGEROUS
	}

	/**
	 * Pill shaped label for compact information like "Model: Pixel 8".
	 */
	public static class Chip extends JLabel {
		private static final long serialVersionUID = 1L;
		private final Color background;

		public Chip(final String caption, final String value, final ChipStyle style) {
			final Color foreground = switch (style) {
				case ACCENTUATED -> ACCENT_PRESSED;
				case SUCCESSFUL -> SUCCESS;
				case ALERT -> WARNING;
				case DANGEROUS -> DANGER;
				case NEUTRAL -> TEXT;
				default -> TEXT;
			};
			background = switch (style) {
				case ACCENTUATED -> ACCENT_LIGHT;
				case SUCCESSFUL -> SUCCESS_LIGHT;
				case ALERT -> WARNING_LIGHT;
				case DANGEROUS -> DANGER_LIGHT;
				case NEUTRAL -> NEUTRAL_LIGHT;
				default -> NEUTRAL_LIGHT;
			};
			final StringBuilder html = new StringBuilder("<html><span style='color:").append(toHex(MUTED)).append("'>");
			if (caption != null && !caption.isEmpty()) {
				html.append(escapeHtml(caption)).append("&nbsp;</span>");
			} else {
				html.append("</span>");
			}
			html.append("<b style='color:").append(toHex(foreground)).append("'>").append(escapeHtml(value)).append("</b></html>");
			setText(html.toString());
			setFont(baseFont());
			setOpaque(false);
			setHorizontalAlignment(SwingConstants.CENTER);
			setBorder(BorderFactory.createEmptyBorder(4, 11, 4, 11));
		}

		@Override
		protected void paintComponent(final Graphics graphics) {
			final Graphics2D g2 = prepare(graphics);
			try {
				g2.setColor(background);
				g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
			} finally {
				g2.dispose();
			}
			super.paintComponent(graphics);
		}
	}

	// ---------------------------------------------------------------- progress bar

	/**
	 * Progress bar with rounded track, gradient fill and centered text.
	 */
	public static JProgressBar createProgressBar() {
		final JProgressBar progressBar = new JProgressBar() {
			private static final long serialVersionUID = 1L;

			@Override
			public void updateUI() {
				setUI(new ModernProgressBarUI());
			}
		};
		progressBar.setOpaque(false);
		progressBar.setBorderPainted(false);
		progressBar.setBorder(BorderFactory.createEmptyBorder());
		progressBar.setFont(baseFont().deriveFont(Font.BOLD, Math.max(10f, baseFont().getSize2D() - 1f)));
		return progressBar;
	}

	private static class ModernProgressBarUI extends BasicProgressBarUI {
		@Override
		public void paint(final Graphics graphics, final JComponent component) {
			final Graphics2D g2 = prepare(graphics);
			try {
				final int width = progressBar.getWidth();
				final int height = progressBar.getHeight();
				final RoundRectangle2D track = new RoundRectangle2D.Double(0, 0, width, height, height, height);
				g2.setColor(new Color(0xE3E7EF));
				g2.fill(track);
				g2.clip(track);
				final GradientPaint fill = new GradientPaint(0, 0, ACCENT, width, 0, HEADER_END);
				if (progressBar.isIndeterminate()) {
					final Rectangle box = getBox(new Rectangle());
					if (box != null) {
						g2.setPaint(fill);
						g2.fill(new RoundRectangle2D.Double(box.x, 0, box.width, height, height, height));
					}
				} else {
					final double percent = progressBar.getPercentComplete();
					if (percent > 0) {
						final double fillWidth = Math.max(height, width * percent);
						g2.setPaint(fill);
						g2.fill(new RoundRectangle2D.Double(0, 0, fillWidth, height, height, height));
					}
				}
				final String text = progressBar.isStringPainted() ? progressBar.getString() : null;
				if (text != null && !text.isEmpty()) {
					g2.setFont(progressBar.getFont());
					final FontMetrics metrics = g2.getFontMetrics();
					final int textX = (width - metrics.stringWidth(text)) / 2;
					final int textY = (height - metrics.getHeight()) / 2 + metrics.getAscent();
					g2.setColor(TEXT);
					g2.drawString(text, textX, textY);
					if (!progressBar.isIndeterminate() && progressBar.getPercentComplete() > 0) {
						// The part of the text lying on the colored fill is drawn in white
						final Graphics2D fillTextGraphics = (Graphics2D) g2.create();
						fillTextGraphics.clip(new Rectangle(0, 0, (int) Math.max(height, width * progressBar.getPercentComplete()), height));
						fillTextGraphics.setColor(Color.WHITE);
						fillTextGraphics.drawString(text, textX, textY);
						fillTextGraphics.dispose();
					}
				}
			} finally {
				g2.dispose();
			}
		}

		@Override
		protected int getBoxLength(final int availableLength, final int otherDimension) {
			return Math.max(otherDimension * 3, availableLength / 4);
		}
	}

	// ---------------------------------------------------------------- scroll and split panes

	/**
	 * Thin inner border, white viewport and slim rounded scroll bars.
	 */
	public static void styleScrollPane(final JScrollPane scrollPane, final Color viewportBackground) {
		scrollPane.setBorder(BorderFactory.createLineBorder(INNER_BORDER));
		scrollPane.setViewportBorder(null);
		scrollPane.getViewport().setBackground(viewportBackground);
		scrollPane.setBackground(viewportBackground);
		styleScrollBar(scrollPane.getVerticalScrollBar(), viewportBackground);
		styleScrollBar(scrollPane.getHorizontalScrollBar(), viewportBackground);
	}

	private static void styleScrollBar(final JScrollBar scrollBar, final Color trackColor) {
		scrollBar.setUI(new SlimScrollBarUI(trackColor));
		scrollBar.setOpaque(true);
		scrollBar.setBackground(trackColor);
		scrollBar.setUnitIncrement(16);
		if (scrollBar.getOrientation() == Adjustable.VERTICAL) {
			scrollBar.setPreferredSize(new Dimension(11, 0));
		} else {
			scrollBar.setPreferredSize(new Dimension(0, 11));
		}
	}

	private static class SlimScrollBarUI extends BasicScrollBarUI {
		private final Color barTrackColor;

		SlimScrollBarUI(final Color barTrackColor) {
			this.barTrackColor = barTrackColor;
		}

		@Override
		protected JButton createDecreaseButton(final int orientation) {
			return createInvisibleButton();
		}

		@Override
		protected JButton createIncreaseButton(final int orientation) {
			return createInvisibleButton();
		}

		private static JButton createInvisibleButton() {
			final JButton button = new JButton();
			button.setPreferredSize(new Dimension(0, 0));
			button.setMinimumSize(new Dimension(0, 0));
			button.setMaximumSize(new Dimension(0, 0));
			return button;
		}

		@Override
		protected void paintTrack(final Graphics graphics, final JComponent component, final Rectangle trackBounds) {
			graphics.setColor(barTrackColor);
			graphics.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
		}

		@Override
		protected void paintThumb(final Graphics graphics, final JComponent component, final Rectangle thumbBounds) {
			if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
				return;
			}
			final Graphics2D g2 = prepare(graphics);
			try {
				final boolean vertical = scrollbar.getOrientation() == Adjustable.VERTICAL;
				final double thickness = 6;
				final double x = vertical ? thumbBounds.x + (thumbBounds.width - thickness) / 2 : thumbBounds.x + 2;
				final double y = vertical ? thumbBounds.y + 2 : thumbBounds.y + (thumbBounds.height - thickness) / 2;
				final double width = vertical ? thickness : thumbBounds.width - 4;
				final double height = vertical ? thumbBounds.height - 4 : thickness;
				g2.setColor(isDragging || isThumbRollover() ? new Color(0x9AA3B5) : new Color(0xC6CCD8));
				g2.fill(new RoundRectangle2D.Double(x, y, width, height, thickness, thickness));
			} finally {
				g2.dispose();
			}
		}
	}

	/**
	 * Transparent split pane with an invisible divider, so the cards seem to float on the background.
	 */
	public static void styleSplitPane(final JSplitPane splitPane) {
		// Plain divider without grips or 3D effects of the look and feel
		splitPane.setUI(new BasicSplitPaneUI() {
			@Override
			public BasicSplitPaneDivider createDefaultDivider() {
				return new BasicSplitPaneDivider(this) {
					private static final long serialVersionUID = 1L;

					@Override
					public void paint(final Graphics graphics) {
						// transparent
					}
				};
			}
		});
		// Set after the UI, because installing the UI restores the look and feel's border
		splitPane.setBorder(null);
		splitPane.setOpaque(false);
		splitPane.setBackground(BACKGROUND);
		splitPane.setDividerSize(8);
		if (splitPane.getUI() instanceof final BasicSplitPaneUI splitPaneUI) {
			splitPaneUI.getDivider().setBorder(null);
		}
	}
}
