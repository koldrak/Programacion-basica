package view.tutorial;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Muestra un tutorial introductorio paso a paso usando JDialogs.
 * La primera vez que se abre la aplicación se ejecuta y luego
 * se marca una bandera en user_prefs.json para no volver a mostrarlo.
 */
public class IntroTutorial {

    private static final File PREFS_FILE = new File("user_prefs.json");
    private static final String FLAG_TRUE = "\"tutorialSeen\": true";

    public static class Step {
        public final JComponent component;
        public final String message;
        public Step(JComponent component, String message) {
            this.component = component;
            this.message = message;
        }
    }

    public static void showIfNeeded(JFrame parent, Step... steps) {
        if (hasSeenTutorial()) return;
        showTutorial(parent, steps);
        markTutorialSeen();
    }

    private static boolean hasSeenTutorial() {
        if (!PREFS_FILE.exists()) return false;
        try {
            String json = Files.readString(PREFS_FILE.toPath());
            return json.contains(FLAG_TRUE);
        } catch (IOException e) {
            return false;
        }
    }

    private static void markTutorialSeen() {
        try {
            Files.writeString(PREFS_FILE.toPath(), "{\n  \"tutorialSeen\": true\n}\n");
        } catch (IOException ignored) {
        }
    }

    private static void showTutorial(JFrame parent, Step... steps) {
        for (Step step : steps) {
            highlight(step.component);
            boolean cont = showDialog(parent, step.message);
            removeHighlight(step.component);
            if (!cont) break;
        }
    }

    private static boolean showDialog(JFrame parent, String message) {
        JDialog dialog = new JDialog(parent, "Tutorial", true);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(message), BorderLayout.CENTER);
        JButton skip = new JButton("Saltar");
        JButton next = new JButton("Siguiente");
        JPanel buttons = new JPanel();
        buttons.add(skip);
        buttons.add(next);
        panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        final boolean[] cont = {true};
        skip.addActionListener(e -> { cont[0] = false; dialog.dispose(); });
        next.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
        return cont[0];
    }

    private static void highlight(JComponent comp) {
        Border old = comp.getBorder();
        comp.putClientProperty("intro.oldBorder", old);
        comp.setBorder(BorderFactory.createLineBorder(Color.ORANGE, 3));
        comp.repaint();
    }

    private static void removeHighlight(JComponent comp) {
        Border old = (Border) comp.getClientProperty("intro.oldBorder");
        comp.setBorder(old);
        comp.repaint();
    }
}

