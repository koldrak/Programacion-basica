import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * ScratchMVP - Editor + Escenario tipo Scratch (bloques mínimos).
 * Un solo archivo, sin dependencias externas (Swing/AWT puro).
 *
 * Funcionalidades:
 * - Editor: paleta de bloques (Eventos/Acciones), lienzo con drag&drop y encadenamiento,
 *   inspector de entidad (forma, color, tamaño), lista de entidades.
 * - Escenario: posicionar entidades (drag), ejecutar juego (runtime ~60 FPS),
 *   eventos ON_START, ON_TICK(ms), ON_KEY_DOWN(KeyCode).
 * - Acciones: MOVE_BY(dx, dy), SET_COLOR(Color), SAY(Texto, seg).
 *
 * Autor: ChatGPT (para Matías)
 * Fecha: 2025-08-31 (fix comp.)
 */
public class ScratchMVP {

    // ====== MODELO BÁSICO ======
    enum ShapeType { RECT, CIRCLE }

    static class Transform {
        double x = 100, y = 100, rot = 0, scaleX = 1, scaleY = 1;
    }

    static class Appearance {
        ShapeType shape = ShapeType.RECT;
        Color color = new Color(0x2E86DE);
        double width = 60, height = 60; // si CIRCLE, usa radius = width/2
        double opacity = 1.0;
    }

    static class Entity {
        String id = UUID.randomUUID().toString();
        String name = "Entidad";
        Transform t = new Transform();
        Appearance a = new Appearance();
        Map<String, Double> vars = new HashMap<>();

        // Runtime UI (globo de texto)
        String sayText = null;
        long sayUntilMs = 0;
    }

    static class Project {
        List<Entity> entities = new ArrayList<>();
        // scripts por entidad (raíces de eventos)
        Map<String, List<EventBlock>> scriptsByEntity = new HashMap<>();
        Map<String, Double> globalVars = new HashMap<>();
        Dimension canvas = new Dimension(800, 600);

        Entity getById(String id) {
            for (Entity e : entities) if (e.id.equals(id)) return e;
            return null;
        }
    }

    // ====== BLOQUES ======
    enum BlockKind { EVENT, ACTION }

    enum EventType { ON_START, ON_TICK, ON_KEY_DOWN, ON_MOUSE, ON_EDGE, ON_VAR_CHANGE, ON_GLOBAL_VAR_CHANGE, ON_COLLIDE }
    enum ActionType { MOVE_BY, SET_COLOR, SAY, SET_VAR, CHANGE_VAR, SET_GLOBAL_VAR, CHANGE_GLOBAL_VAR, WAIT, ROTATE_BY, ROTATE_TO, SCALE_BY, SET_SIZE, CHANGE_OPACITY, SPAWN_ENTITY, DELETE_ENTITY }

    static abstract class Block {
        final String id = UUID.randomUUID().toString();
        Block next;               // cadena secuencial
        // posición persistente en el lienzo del editor
        int x = 20, y = 20;
        abstract BlockKind kind();
        abstract String title();
    }

    static class EventBlock extends Block {
        EventType type;
        Map<String, Object> args = new HashMap<>(); // intervalMs, keyCode, etc.
        // permite disparar múltiples cadenas desde un mismo evento
        List<Block> extraNext = new ArrayList<>();

        EventBlock(EventType t) { this.type = t; }

        @Override BlockKind kind() { return BlockKind.EVENT; }
        @Override String title() {
            switch (type) {
                case ON_START: return "Evento: Al iniciar";
                case ON_TICK: return "Evento: Cada (ms)=" + args.getOrDefault("intervalMs", 500);
                case ON_KEY_DOWN:
                    int kc = (int) args.getOrDefault("keyCode", KeyEvent.VK_RIGHT);
                    return "Evento: Tecla " + KeyEvent.getKeyText(kc);
                case ON_MOUSE:
                    int btn = (int) args.getOrDefault("button", MouseEvent.BUTTON1);
                    return "Evento: Clic " + (btn==MouseEvent.BUTTON3?"Der":"Izq");
                case ON_EDGE:
                    return "Evento: Al tocar borde";
                case ON_VAR_CHANGE:
                    return "Evento: Var " + args.getOrDefault("var","var") + " = " + args.getOrDefault("value",0);
                case ON_GLOBAL_VAR_CHANGE:
                    return "Evento: Var Global " + args.getOrDefault("var","var") + " = " + args.getOrDefault("value",0);
                case ON_COLLIDE:
                    return "Evento: Colisión";
            }
            return "Evento";
        }
    }

    static class ActionBlock extends Block {
        ActionType type;
        Map<String, Object> args = new HashMap<>(); // dx,dy,color,text,duration

        ActionBlock(ActionType t) { this.type = t; }

        @Override BlockKind kind() { return BlockKind.ACTION; }
        @Override String title() {
            switch (type) {
                case MOVE_BY:
                    int dx = (int) args.getOrDefault("dx", 5);
                    int dy = (int) args.getOrDefault("dy", 0);
                    String dir;
                    int pasos;
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        dir = dx >= 0 ? "derecha" : "izquierda";
                        pasos = Math.abs(dx);
                    } else {
                        dir = dy >= 0 ? "abajo" : "arriba";
                        pasos = Math.abs(dy);
                    }
                    return "Acción: Mover " + dir + " " + pasos;
                case SET_COLOR:
                    return "Acción: Color";
                case SAY:
                    return "Acción: Decir \"" + args.getOrDefault("text", "¡Hola!") + "\"";
                case SET_VAR:
                    return "Acción: Fijar " + args.getOrDefault("var", "var") + " a " + args.getOrDefault("value", 0);
                case CHANGE_VAR:
                    return "Acción: Cambiar " + args.getOrDefault("var", "var") + " en " + args.getOrDefault("delta", 1);
                case SET_GLOBAL_VAR:
                    return "Acción: Fijar global " + args.getOrDefault("var", "var") + " a " + args.getOrDefault("value", 0);
                case CHANGE_GLOBAL_VAR:
                    return "Acción: Cambiar global " + args.getOrDefault("var", "var") + " en " + args.getOrDefault("delta", 1);
                case WAIT:
                    return "Acción: Esperar " + args.getOrDefault("secs",1.0) + "s";
                case ROTATE_BY:
                    return "Acción: Girar " + args.getOrDefault("deg",15) + "°";
                case ROTATE_TO:
                    return "Acción: Girar a " + args.getOrDefault("deg",0) + "°";
                case SCALE_BY:
                    return "Acción: Escalar x" + args.getOrDefault("factor",1.1);
                case SET_SIZE:
                    return "Acción: Tamaño (" + args.getOrDefault("w",60) + "," + args.getOrDefault("h",60) + ")";
                case CHANGE_OPACITY:
                    return "Acción: Opacidad " + args.getOrDefault("delta",0.1);
                case SPAWN_ENTITY:
                    String name = (String) args.get("templateName");
                    return "Acción: Crear entidad" + (name != null ? " " + name : "");
                case DELETE_ENTITY:
                    return "Acción: Borrar entidad";
            }
            return "Acción";
        }
    }

    // ====== VARIABLES GLOBALES ======
    static class GlobalVarPanel extends JPanel {
        final Project project;
        final DefaultListModel<String> model = new DefaultListModel<>();
        final JList<String> list = new JList<>(model);
        JSpinner valueSpin;
        JButton btnAdd, btnDel;

        GlobalVarPanel(Project project) {
            super();
            this.project = project;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(8,8,8,8));
            add(new JLabel("Variables Globales"));
            list.setVisibleRowCount(4);
            add(new JScrollPane(list));
            valueSpin = new JSpinner(new SpinnerNumberModel(0.0, -1e9, 1e9, 1.0));
            add(labeled("Valor", valueSpin));
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
            btnAdd = new JButton("Agregar");
            btnDel = new JButton("Eliminar");
            b.add(btnAdd); b.add(btnDel);
            add(b);

            list.addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                String name = list.getSelectedValue();
                boolean ok = name != null;
                valueSpin.setEnabled(ok);
                btnDel.setEnabled(ok);
                if (ok) valueSpin.setValue(project.globalVars.getOrDefault(name, 0.0));
            });
            valueSpin.addChangeListener(ev -> {
                String name = list.getSelectedValue();
                if (name != null) {
                    project.globalVars.put(name, ((Number)valueSpin.getValue()).doubleValue());
                }
            });
            btnAdd.addActionListener(ev -> {
                JTextField nameField = new JTextField();
                JSpinner valSpin = new JSpinner(new SpinnerNumberModel(0.0, -1e9, 1e9, 1.0));
                JPanel pan = new JPanel(new GridLayout(2,2));
                pan.add(new JLabel("Nombre:")); pan.add(nameField);
                pan.add(new JLabel("Valor:")); pan.add(valSpin);
                int r = JOptionPane.showConfirmDialog(this, pan, "Agregar variable global", JOptionPane.OK_CANCEL_OPTION);
                if (r == JOptionPane.OK_OPTION) {
                    String name = nameField.getText().trim();
                    if (!name.isEmpty() && !project.globalVars.containsKey(name)) {
                        double v = ((Number)valSpin.getValue()).doubleValue();
                        project.globalVars.put(name, v);
                        refresh();
                    }
                }
            });
            btnDel.addActionListener(ev -> {
                String name = list.getSelectedValue();
                if (name != null) {
                    project.globalVars.remove(name);
                    refresh();
                }
            });

            refresh();
        }

        JPanel labeled(String name, JComponent comp) {
            JPanel p = new JPanel(new BorderLayout(6,0));
            p.add(new JLabel(name), BorderLayout.WEST);
            p.add(comp, BorderLayout.CENTER);
            return p;
        }

        void refresh() {
            model.clear();
            for (String v : project.globalVars.keySet()) model.addElement(v);
            String sel = list.getSelectedValue();
            boolean ok = sel != null && project.globalVars.containsKey(sel);
            valueSpin.setEnabled(ok);
            btnDel.setEnabled(ok);
            if (ok) valueSpin.setValue(project.globalVars.getOrDefault(sel, 0.0));
        }
    }

    static class IfElseBlock extends Block {
        String var = "var";
        double compare = 0;
        Block thenBranch;
        Block elseBranch;

        @Override BlockKind kind() { return BlockKind.ACTION; }

        @Override String title() {
            return "Si " + var + " > " + compare;
        }
    }

    static class WhileBlock extends Block {
        String var = "var";
        double compare = 0;
        Block body;

        @Override BlockKind kind() { return BlockKind.ACTION; }

        @Override String title() {
            return "Mientras " + var + " > " + compare;
        }
    }

    // ====== RUNTIME ======
    static class GameRuntime {
        final Project project;
        final StagePanel stage;
        final Set<Integer> keysDown;
        javax.swing.Timer swingTimer;
        long lastUpdateNs = 0;
        Map<String, Map<EventBlock, Long>> tickLastFire = new HashMap<>(); // por entidad -> evento -> tiempo última ejecución
        Map<String, Map<EventBlock, Double>> varLast = new HashMap<>();
        Map<EventBlock, Double> globalVarLast = new HashMap<>();

        GameRuntime(Project p, StagePanel s, Set<Integer> keysDown) {
            this.project = p;
            this.stage = s;
            this.keysDown = keysDown;
        }

        void play() {
            stop();
            tickLastFire.clear();
            varLast.clear();
            globalVarLast.clear();
            // ON_START una vez
            for (Entity e : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(e.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_START) {
                        triggerEvent(e, ev);
                    } else if (ev.type == EventType.ON_TICK) {
                        tickLastFire.computeIfAbsent(e.id, k -> new HashMap<>()).put(ev, System.currentTimeMillis());
                    } else if (ev.type == EventType.ON_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        double cur = e.vars.getOrDefault(var, 0.0);
                        varLast.computeIfAbsent(e.id, k -> new HashMap<>()).put(ev, cur);
                    } else if (ev.type == EventType.ON_GLOBAL_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        double cur = project.globalVars.getOrDefault(var, 0.0);
                        globalVarLast.put(ev, cur);
                    }
                }
            }
            lastUpdateNs = System.nanoTime();
            swingTimer = new javax.swing.Timer(16, e -> update());
            swingTimer.start();
        }

        void stop() {
            if (swingTimer != null) {
                swingTimer.stop();
                swingTimer = null;
            }
        }

        void update() {
            long nowNs = System.nanoTime();
            double dt = (nowNs - lastUpdateNs) / 1_000_000_000.0;
            lastUpdateNs = nowNs;

            long nowMs = System.currentTimeMillis();

            // ON_TICK
            for (Entity en : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_TICK) {
                        int interval = (int) ev.args.getOrDefault("intervalMs", 500);
                        long last = tickLastFire.getOrDefault(en.id, Collections.emptyMap()).getOrDefault(ev, nowMs);
                        if (nowMs - last >= interval) {
                            triggerEvent(en, ev);
                            tickLastFire.get(en.id).put(ev, nowMs);
                        }
                    }
                }
            }

            // ON_KEY_DOWN (se dispara cada frame si la tecla está presionada)
            for (Entity en : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_KEY_DOWN) {
                        int kc = (int) ev.args.getOrDefault("keyCode", KeyEvent.VK_RIGHT);
                        if (keysDown.contains(kc)) {
                            triggerEvent(en, ev);
                        }
                    }
                }
            }

            // ON_EDGE_TOUCH
            for (Entity en : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_EDGE) {
                        if (en.t.x <= 0 || en.t.y <= 0 ||
                                en.t.x + en.a.width >= stage.size.width ||
                                en.t.y + en.a.height >= stage.size.height) {
                            triggerEvent(en, ev);
                        }
                    }
                }
            }

            // ON_VAR_CHANGE
            for (Entity en : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = en.vars.getOrDefault(var, 0.0);
                        double prev = varLast.getOrDefault(en.id, Collections.emptyMap()).getOrDefault(ev, cur);
                        if (cur == target && prev != target) {
                            triggerEvent(en, ev);
                        }
                        varLast.computeIfAbsent(en.id, k -> new HashMap<>()).put(ev, cur);
                    } else if (ev.type == EventType.ON_GLOBAL_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = project.globalVars.getOrDefault(var, 0.0);
                        double prev = globalVarLast.getOrDefault(ev, cur);
                        if (cur == target && prev != target) {
                            triggerEvent(en, ev);
                        }
                        globalVarLast.put(ev, cur);
                    }
                }
            }

            // ON_COLLIDE
            for (Entity en : stage.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_COLLIDE) {
                        String targetId = (String) ev.args.get("otherId");
                        for (Entity other : stage.entities) {
                            if (other == en) continue;
                            if (targetId != null && !other.id.equals(targetId)) continue;
                            if (collides(en, other)) {
                                triggerEvent(en, ev);
                                break;
                            }
                        }
                    }
                }
            }

            // limpiar "decir" expirado
            long t = System.currentTimeMillis();
            for (Entity en : stage.entities) {
                if (en.sayText != null && t > en.sayUntilMs) {
                    en.sayText = null;
                }
            }

            stage.repaint();
        }

        void triggerEvent(Entity e, EventBlock ev) {
            if (ev.next != null) executeChain(e, ev.next);
            for (Block b : ev.extraNext) {
                executeChain(e, b);
            }
        }

        boolean collides(Entity a, Entity b) {
            Rectangle ra = new Rectangle((int) a.t.x, (int) a.t.y, (int) a.a.width, (int) a.a.height);
            Rectangle rb = new Rectangle((int) b.t.x, (int) b.t.y, (int) b.a.width, (int) b.a.height);
            return ra.intersects(rb);
        }

        boolean contains(Entity e, Point p) {
            if (e.a.shape == ShapeType.RECT) {
                Rectangle r = new Rectangle((int) e.t.x, (int) e.t.y, (int) e.a.width, (int) e.a.height);
                return r.contains(p);
            } else {
                int r = (int) (e.a.width / 2);
                int cx = (int) (e.t.x + r), cy = (int) (e.t.y + r);
                int dx = p.x - cx, dy = p.y - cy;
                return dx * dx + dy * dy <= r * r;
            }
        }

        void executeChain(Entity e, Block b) {
            Block current = b;
            while (current != null) {
                if (current instanceof ActionBlock) {
                    boolean cont = executeAction(e, (ActionBlock) current);
                    if (!cont) break;
                    current = current.next;
                } else if (current instanceof IfElseBlock) {
                    IfElseBlock ib = (IfElseBlock) current;
                    double val = e.vars.getOrDefault(ib.var, 0.0);
                    if (val > ib.compare) {
                        if (ib.thenBranch != null) executeChain(e, ib.thenBranch);
                    } else {
                        if (ib.elseBranch != null) executeChain(e, ib.elseBranch);
                    }
                    current = ib.next;
                } else if (current instanceof WhileBlock) {
                    WhileBlock wb = (WhileBlock) current;
                    while (e.vars.getOrDefault(wb.var, 0.0) > wb.compare) {
                        if (wb.body != null) executeChain(e, wb.body);
                    }
                    current = wb.next;
                } else {
                    current = current.next;
                }
            }
        }

        boolean executeAction(Entity e, ActionBlock ab) {
            switch (ab.type) {
                case MOVE_BY -> {
                    int dx = (int) ab.args.getOrDefault("dx", 5);
                    int dy = (int) ab.args.getOrDefault("dy", 0);
                    e.t.x += dx;
                    e.t.y += dy;
                    e.t.x = Math.max(0, Math.min(e.t.x, stage.size.width - e.a.width));
                    e.t.y = Math.max(0, Math.min(e.t.y, stage.size.height - e.a.height));
                }
                case SET_COLOR -> {
                    Color chosenColor = (Color) ab.args.getOrDefault("color", new Color(0xE74C3C));
                    e.a.color = chosenColor;
                }
                case SAY -> {
                    String text = String.valueOf(ab.args.getOrDefault("text", "¡Hola!"));
                    double secs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 2.0)));
                    e.sayText = text;
                    e.sayUntilMs = System.currentTimeMillis() + (long) (secs * 1000);
                }
                case SET_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double val = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                    e.vars.put(var, val);
                }
                case CHANGE_VAR -> {
                    String cvar = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double delta = Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1)));
                    double cur = e.vars.getOrDefault(cvar, 0.0);
                    e.vars.put(cvar, cur + delta);
                }
                case SET_GLOBAL_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double val = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                    project.globalVars.put(var, val);
                }
                case CHANGE_GLOBAL_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double delta = Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1)));
                    double cur = project.globalVars.getOrDefault(var, 0.0);
                    project.globalVars.put(var, cur + delta);
                }
                case WAIT -> {
                    double secs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 1.0)));
                    javax.swing.Timer tm = new javax.swing.Timer((int) (secs * 1000), ev -> executeChain(e, ab.next));
                    tm.setRepeats(false);
                    tm.start();
                    return false;
                }
                case ROTATE_BY -> {
                    double deg = Double.parseDouble(String.valueOf(ab.args.getOrDefault("deg", 15)));
                    e.t.rot += deg;
                }
                case ROTATE_TO -> {
                    double deg = Double.parseDouble(String.valueOf(ab.args.getOrDefault("deg", 0)));
                    e.t.rot = deg;
                }
                case SCALE_BY -> {
                    double factor = Double.parseDouble(String.valueOf(ab.args.getOrDefault("factor", 1.1)));
                    e.a.width *= factor;
                    e.a.height *= factor;
                }
                case SET_SIZE -> {
                    double w = Double.parseDouble(String.valueOf(ab.args.getOrDefault("w", 60)));
                    double h = Double.parseDouble(String.valueOf(ab.args.getOrDefault("h", 60)));
                    e.a.width = w;
                    e.a.height = h;
                }
                case CHANGE_OPACITY -> {
                    double delta = Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 0.1)));
                    e.a.opacity = Math.max(0, Math.min(1, e.a.opacity + delta));
                }
                case SPAWN_ENTITY -> {
                    String tplId = (String) ab.args.get("templateId");
                    Entity tpl = tplId != null ? project.getById(tplId) : null;
                    if (tpl == null) tpl = e;
                    Entity clone = stage.cloneEntity(tpl);
                    stage.entities.add(clone);
                    project.scriptsByEntity.put(clone.id, stage.cloneScripts(tpl.id));
                }
                case DELETE_ENTITY -> {
                    String targetId = (String) ab.args.get("targetId");
                    Entity target = targetId == null ? e : stage.entities.stream()
                            .filter(en -> en.id.equals(targetId)).findFirst().orElse(null);
                    if (target != null) {
                        stage.entities.remove(target);
                        project.scriptsByEntity.remove(target.id);
                    }
                    if (target == e) {
                        return false;
                    }
                }
            }
            return true;
        }

        void handleMouseEvent(MouseEvent e) {
            Point p = e.getPoint();
            for (Entity en : stage.entities) {
                if (!contains(en, p)) continue;
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_MOUSE) {
                        int btn = (int) ev.args.getOrDefault("button", MouseEvent.BUTTON1);
                        if (e.getButton() == btn) {
                            triggerEvent(en, ev);
                        }
                    }
                }
            }
        }
    }

    // ====== UI GENERAL ======
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }

    static class MainFrame extends JFrame {
        final CardLayout cards = new CardLayout();
        final JPanel root = new JPanel(cards);

        final Project project = new Project();
        final Set<Integer> keysDown = Collections.synchronizedSet(new HashSet<>());

        final EditorPanel editorPanel;
        final StagePanel stagePanel;
        GameRuntime runtime;

        MainFrame() {
            super("Scratch MVP (Java Swing) — Editor y Escenario");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1200, 760);
            setLocationRelativeTo(null);

            // Panels
            stagePanel = new StagePanel(project, keysDown);
            editorPanel = new EditorPanel(project, () -> {
                // al pulsar "Ir al Escenario"
                cards.show(root, "stage");
                stagePanel.requestFocusInWindow();
                stagePanel.repaint();
            });

            // Runtime
            runtime = new GameRuntime(project, stagePanel, keysDown);
            stagePanel.setRuntimeControls(
                    () -> runtime.play(),
                    () -> runtime.stop(),
                    () -> { // volver al editor
                        runtime.stop();
                        cards.show(root, "editor");
                        editorPanel.refreshAll();
                    });
            stagePanel.setRuntime(runtime);

            root.add(editorPanel, "editor");
            root.add(stagePanel, "stage");

            setContentPane(root);
            cards.show(root, "editor");
        }
    }

    // ====== PANEL EDITOR ======
    static class EditorPanel extends JPanel {
        final Project project;
        final Runnable goStage;

        final EntityListPanel entityListPanel;
        final GlobalVarPanel globalVarPanel;
        final InspectorPanel inspectorPanel;
        final PalettePanel palettePanel;
        final ScriptCanvasPanel scriptCanvas;

        EditorPanel(Project project, Runnable goStage) {
            super(new BorderLayout());
            this.project = project;
            this.goStage = goStage;

            // Top bar
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton btnNewEntity = new JButton("Nueva Entidad");
            JButton btnDelEntity = new JButton("Eliminar Entidad");
            JButton btnToStage   = new JButton("Ir al Escenario ▶");
            bar.add(btnNewEntity);
            bar.add(btnDelEntity);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(btnToStage);

            add(bar, BorderLayout.NORTH);

            // Left: Paleta y lista entidades
            JPanel left = new JPanel(new BorderLayout());
            left.setPreferredSize(new Dimension(280, 100));
            palettePanel = new PalettePanel();
            JScrollPane paletteScroll = new JScrollPane(palettePanel,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            paletteScroll.getVerticalScrollBar().setUnitIncrement(16);
            entityListPanel = new EntityListPanel(project);
            globalVarPanel = new GlobalVarPanel(project);
            left.add(paletteScroll, BorderLayout.CENTER);
            JPanel bottom = new JPanel();
            bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
            bottom.add(entityListPanel);
            bottom.add(globalVarPanel);
            left.add(bottom, BorderLayout.SOUTH);

            // Center: Lienzo de scripts
            scriptCanvas = new ScriptCanvasPanel(project, entityListPanel);
            palettePanel.setDropTargetCanvas(scriptCanvas);

            // Right: Inspector
            inspectorPanel = new InspectorPanel(project, entityListPanel, scriptCanvas);

            add(left, BorderLayout.WEST);
            add(scriptCanvas, BorderLayout.CENTER);
            add(inspectorPanel, BorderLayout.EAST);

            // Listeners
            btnNewEntity.addActionListener(e -> {
                Entity en = new Entity();
                en.name = "Entidad " + (project.entities.size() + 1);
                project.entities.add(en);
                project.scriptsByEntity.put(en.id, new ArrayList<>());
                entityListPanel.refresh();
                entityListPanel.select(en);
                scriptCanvas.repaint();
            });

            btnDelEntity.addActionListener(e -> {
                Entity sel = entityListPanel.getSelected();
                if (sel != null) {
                    project.entities.remove(sel);
                    project.scriptsByEntity.remove(sel.id);
                    entityListPanel.refresh();
                    scriptCanvas.repaint();
                }
            });

            btnToStage.addActionListener(e -> goStage.run());

            // Estado inicial
            if (project.entities.isEmpty()) {
                btnNewEntity.doClick();
            }
        }

        void refreshAll() {
            entityListPanel.refresh();
            globalVarPanel.refresh();
            scriptCanvas.repaint();
            inspectorPanel.refresh();
        }
    }

    // ====== LISTA ENTIDADES ======
    static class EntityListPanel extends JPanel {
        final Project project;
        final DefaultListModel<Entity> model = new DefaultListModel<>();
        final JList<Entity> list = new JList<>(model);

        EntityListPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            setBorder(new EmptyBorder(8,8,8,8));
            list.setCellRenderer((jlist, value, index, isSelected, cellHasFocus) -> {
                JLabel lab = new JLabel(value.name);
                lab.setOpaque(true);
                lab.setBorder(new EmptyBorder(4,8,4,8));
                lab.setBackground(isSelected ? new Color(0xD6EAF8) : Color.WHITE);
                return lab;
            });
            add(new JLabel("Entidades"), BorderLayout.NORTH);
            add(new JScrollPane(list), BorderLayout.CENTER);
            refresh();
        }

        void refresh() {
            model.clear();
            for (Entity e : project.entities) model.addElement(e);
            if (!model.isEmpty() && list.getSelectedIndex() == -1) list.setSelectedIndex(0);
        }

        Entity getSelected() { return list.getSelectedValue(); }

        void select(Entity e) {
            list.setSelectedValue(e, true);
        }
    }

    // ====== INSPECTOR ======
    static class InspectorPanel extends JPanel {
        final Project project;
        final EntityListPanel listPanel;
        final ScriptCanvasPanel canvas;

        JComboBox<String> shapeBox;
        JButton colorBtn;
        JSpinner wSpin, hSpin;
        DefaultListModel<String> varModel;
        JList<String> varList;
        JSpinner varValue;
        JButton btnAddVar, btnDelVar;

        InspectorPanel(Project p, EntityListPanel lp, ScriptCanvasPanel c) {
            super();
            this.project = p; this.listPanel = lp; this.canvas = c;
            setPreferredSize(new Dimension(260, 100));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(10,10,10,10));

            add(new JLabel("Inspector de Entidad"));
            add(Box.createVerticalStrut(8));
            shapeBox = new JComboBox<>(new String[]{"Rectángulo","Círculo"});
            colorBtn = new JButton("Color...");
            wSpin = new JSpinner(new SpinnerNumberModel(60, 10, 500, 5));
            hSpin = new JSpinner(new SpinnerNumberModel(60, 10, 500, 5));

            add(labeled("Forma", shapeBox));
            add(Box.createVerticalStrut(6));
            add(labeled("Ancho", wSpin));
            add(labeled("Alto/Radio", hSpin));
            add(Box.createVerticalStrut(6));
            add(colorBtn);
            add(Box.createVerticalStrut(10));

            add(new JLabel("Variables"));
            varModel = new DefaultListModel<>();
            varList = new JList<>(varModel);
            varList.setVisibleRowCount(5);
            add(new JScrollPane(varList));
            varValue = new JSpinner(new SpinnerNumberModel(0.0, -1e9, 1e9, 1.0));
            add(labeled("Valor", varValue));
            JPanel vb = new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
            btnAddVar = new JButton("Agregar");
            btnDelVar = new JButton("Eliminar");
            vb.add(btnAddVar); vb.add(btnDelVar);
            add(vb);

            add(Box.createVerticalGlue());

            // Listeners
            shapeBox.addActionListener(e -> {
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    sel.a.shape = shapeBox.getSelectedIndex()==0? ShapeType.RECT:ShapeType.CIRCLE;
                    canvas.repaint();
                }
            });

            colorBtn.addActionListener(e -> {
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    Color chosen = JColorChooser.showDialog(this, "Elegir color", sel.a.color);
                    if (chosen != null) sel.a.color = chosen;
                    canvas.repaint();
                }
            });

            ChangeListener cl = e -> {
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    sel.a.width = ((Number)wSpin.getValue()).doubleValue();
                    sel.a.height = ((Number)hSpin.getValue()).doubleValue();
                    canvas.repaint();
                }
            };
            wSpin.addChangeListener(cl);
            hSpin.addChangeListener(cl);

            varList.addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                boolean ok = sel != null && name != null;
                varValue.setEnabled(ok);
                btnDelVar.setEnabled(ok);
                if (ok) {
                    varValue.setValue(sel.vars.getOrDefault(name, 0.0));
                }
            });
            varValue.addChangeListener(ev -> {
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                if (sel != null && name != null) {
                    sel.vars.put(name, ((Number)varValue.getValue()).doubleValue());
                    canvas.repaint();
                }
            });
            btnAddVar.addActionListener(ev -> {
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    JTextField nameField = new JTextField();
                    JSpinner valSpin = new JSpinner(new SpinnerNumberModel(0.0, -1e9, 1e9, 1.0));
                    JPanel pan = new JPanel(new GridLayout(2,2));
                    pan.add(new JLabel("Nombre:")); pan.add(nameField);
                    pan.add(new JLabel("Valor:")); pan.add(valSpin);
                    int r = JOptionPane.showConfirmDialog(this, pan, "Agregar variable", JOptionPane.OK_CANCEL_OPTION);
                    if (r == JOptionPane.OK_OPTION) {
                        String name = nameField.getText().trim();
                        if (!name.isEmpty() && !sel.vars.containsKey(name)) {
                            double val = ((Number)valSpin.getValue()).doubleValue();
                            sel.vars.put(name, val);
                            refresh();
                        }
                    }
                }
            });
            btnDelVar.addActionListener(ev -> {
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                if (sel != null && name != null) {
                    sel.vars.remove(name);
                    refresh();
                }
            });

            // actualizar al cambiar selección
            listPanel.list.addListSelectionListener(e -> refresh());
            refresh();
        }

        JPanel labeled(String name, JComponent comp) {
            JPanel p = new JPanel(new BorderLayout(6,0));
            p.add(new JLabel(name), BorderLayout.WEST);
            p.add(comp, BorderLayout.CENTER);
            return p;
        }

        void refresh() {
            Entity sel = listPanel.getSelected();
            boolean en = sel != null;
            shapeBox.setEnabled(en); colorBtn.setEnabled(en); wSpin.setEnabled(en); hSpin.setEnabled(en);
            btnAddVar.setEnabled(en); varList.setEnabled(en);
            if (sel != null) {
                shapeBox.setSelectedIndex(sel.a.shape==ShapeType.RECT?0:1);
                wSpin.setValue((int)sel.a.width);
                hSpin.setValue((int)sel.a.height);
                varModel.clear();
                for (String v : sel.vars.keySet()) varModel.addElement(v);
            } else {
                varModel.clear();
            }
            String name = varList.getSelectedValue();
            boolean vs = en && name != null;
            varValue.setEnabled(vs);
            btnDelVar.setEnabled(vs);
            if (vs) {
                varValue.setValue(sel.vars.getOrDefault(name, 0.0));
            }
        }
    }

    // ====== PALETA DE BLOQUES ======
    static class PalettePanel extends JPanel {
        ScriptCanvasPanel dropTarget;

        PalettePanel() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(10,10,10,10));
            add(new JLabel("Paleta de Bloques"));
            add(Box.createVerticalStrut(6));

            add(section("Eventos"));
            add(makeBtn("Al iniciar", () -> new EventBlock(EventType.ON_START)));
            add(makeBtn("Cada (ms)...", () -> {
                EventBlock b = new EventBlock(EventType.ON_TICK);
                b.args.put("intervalMs", 500);
                return b;
            }));
            add(makeBtn("Tecla ↓ ...", () -> {
                EventBlock b = new EventBlock(EventType.ON_KEY_DOWN);
                b.args.put("keyCode", KeyEvent.VK_RIGHT);
                return b;
            }));
            add(makeBtn("Clic ratón", () -> {
                EventBlock b = new EventBlock(EventType.ON_MOUSE);
                b.args.put("button", MouseEvent.BUTTON1);
                return b;
            }));
            add(makeBtn("Toca borde", () -> new EventBlock(EventType.ON_EDGE)));
            add(makeBtn("Var alcanza...", () -> {
                EventBlock b = new EventBlock(EventType.ON_VAR_CHANGE);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            add(makeBtn("Var global alcanza...", () -> {
                EventBlock b = new EventBlock(EventType.ON_GLOBAL_VAR_CHANGE);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            add(makeBtn("Colisión", () -> new EventBlock(EventType.ON_COLLIDE)));

            add(Box.createVerticalStrut(10));
            add(section("Acciones"));
            add(makeBtn("Mover...", () -> {
                ActionBlock b = new ActionBlock(ActionType.MOVE_BY);
                b.args.put("dx", 5); b.args.put("dy", 0);
                return b;
            }));
            add(makeBtn("Color...", () -> new ActionBlock(ActionType.SET_COLOR)));
            add(makeBtn("Decir...", () -> {
                ActionBlock b = new ActionBlock(ActionType.SAY);
                b.args.put("text", "¡Hola!");
                b.args.put("secs", 2.0);
                return b;
            }));
            add(makeBtn("Asignar var entidad...", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_VAR);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            add(makeBtn("Cambiar var entidad...", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_VAR);
                b.args.put("var", "var");
                b.args.put("delta", 1);
                return b;
            }));
            add(makeBtn("Asignar var global...", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_GLOBAL_VAR);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            add(makeBtn("Cambiar var global...", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_GLOBAL_VAR);
                b.args.put("var", "var");
                b.args.put("delta", 1);
                return b;
            }));
            add(makeBtn("Esperar...", () -> {
                ActionBlock b = new ActionBlock(ActionType.WAIT);
                b.args.put("secs", 1.0);
                return b;
            }));
            add(makeBtn("Girar...", () -> {
                ActionBlock b = new ActionBlock(ActionType.ROTATE_BY);
                b.args.put("deg", 15);
                return b;
            }));
            add(makeBtn("Apuntar a...", () -> {
                ActionBlock b = new ActionBlock(ActionType.ROTATE_TO);
                b.args.put("deg", 0);
                return b;
            }));
            add(makeBtn("Escalar x...", () -> {
                ActionBlock b = new ActionBlock(ActionType.SCALE_BY);
                b.args.put("factor", 1.1);
                return b;
            }));
            add(makeBtn("Tamaño...", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_SIZE);
                b.args.put("w", 60);
                b.args.put("h", 60);
                return b;
            }));
            add(makeBtn("Opacidad...", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_OPACITY);
                b.args.put("delta", -0.1);
                return b;
            }));
            add(makeBtn("Crear entidad", () -> new ActionBlock(ActionType.SPAWN_ENTITY)));
            add(makeBtn("Eliminar entidad", () -> new ActionBlock(ActionType.DELETE_ENTITY)));
            add(makeBtn("Si / Si no", IfElseBlock::new));
            add(makeBtn("Mientras", WhileBlock::new));

            add(Box.createVerticalGlue());
        }

        JPanel section(String name) {
            JPanel p = new JPanel(new BorderLayout());
            JLabel l = new JLabel(name);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            p.add(l, BorderLayout.CENTER);
            p.setBorder(new EmptyBorder(6,0,4,0));
            return p;
        }

        JButton makeBtn(String text, Supplier<Block> factory) {
            JButton b = new JButton(text);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.addActionListener(e -> {
                if (dropTarget != null) dropTarget.spawnBlock(factory.get());
            });
            return b;
        }

        void setDropTargetCanvas(ScriptCanvasPanel c) {
            this.dropTarget = c;
        }

        interface Supplier<T> { T get(); }
    }

    // ====== LIENZO SCRIPT ======
    static class ScriptCanvasPanel extends JPanel {
        final Project project;
        final EntityListPanel listPanel;

        // Vistas actuales en el canvas (por entidad)
        Map<String, List<BlockView>> viewsByEntity = new HashMap<>();

        ScriptCanvasPanel(Project p, EntityListPanel lp) {
            super(null); // absolute layout
            this.project = p; this.listPanel = lp;
            setBackground(new Color(0xFAFAFA));
            setPreferredSize(new Dimension(600, 600));

            setBorder(BorderFactory.createTitledBorder("Editor de Bloques"));
            listPanel.list.addListSelectionListener(e -> redrawForSelected());

            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    requestFocusInWindow();
                }
            });
        }

        void spawnBlock(Block block) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;

            BlockView bv = new BlockView(block, this);
            add(bv);
            Dimension ps = bv.getPreferredSize();
            int bx = 20, by = 40;
            outer:
            for (int y=40; y<=getHeight()-ps.height; y+=80) {
                for (int x=20; x<=getWidth()-ps.width; x+=200) {
                    Rectangle r = new Rectangle(x, y, ps.width, ps.height);
                    boolean overlap = false;
                    for (BlockView existing : getViews(sel)) {
                        if (r.intersects(existing.getBounds())) { overlap = true; break; }
                    }
                    if (!overlap) { bx = x; by = y; break outer; }
                }
            }
            bv.setLocation(bx, by);
            bv.setSize(ps);
            block.x = bx; block.y = by;
            getViews(sel).add(bv);

            // si es evento y no existe aún en scripts, añadir raíz
            if (block instanceof EventBlock) {
                project.scriptsByEntity.computeIfAbsent(sel.id, k->new ArrayList<>()).add((EventBlock)block);
            }
            repaint();
        }

        int getBlockCountFor(Entity e) {
            return getViews(e).size();
        }

        List<BlockView> getViews(Entity e) {
            return viewsByEntity.computeIfAbsent(e.id, k -> new ArrayList<>());
        }

        void redrawForSelected() {
            removeAll();
            Entity sel = listPanel.getSelected();
            if (sel == null) { repaint(); return; }
            // reconstruir vistas desde modelo con posiciones guardadas
            List<BlockView> list = getViews(sel);
            list.clear();

            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(sel.id, Collections.emptyList());
            for (EventBlock ev : roots) {
                addRecursive(ev, list);
            }
            revalidate();
            repaint();
        }

        void addRecursive(Block b, List<BlockView> list) {
            BlockView v = new BlockView(b, this);
            add(v);
            v.setLocation(b.x, b.y);
            v.setSize(v.getPreferredSize());
            list.add(v);
            if (b instanceof IfElseBlock ib) {
                if (ib.thenBranch != null) addRecursive(ib.thenBranch, list);
                if (ib.elseBranch != null) addRecursive(ib.elseBranch, list);
                if (ib.next != null) addRecursive(ib.next, list);
            } else if (b.next != null) {
                addRecursive(b.next, list);
            }
        }

        BlockView findView(Block b) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return null;
            for (BlockView v : getViews(sel)) if (v.block == b) return v;
            return null;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Entity sel = listPanel.getSelected();
            if (sel == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(Color.GRAY);
            for (BlockView v : getViews(sel)) {
                Block blk = v.block;
                if (blk instanceof IfElseBlock ib) {
                    if (ib.thenBranch != null) {
                        BlockView child = findView(ib.thenBranch);
                        if (child != null) {
                            int x1 = v.getX() + v.getWidth()/2;
                            int y1 = v.getY() + v.getHeight();
                            int x2 = child.getX() + child.getWidth()/2;
                            int y2 = child.getY();
                            g2.drawLine(x1, y1, x2, y2);
                        }
                    }
                    if (ib.elseBranch != null) {
                        BlockView child = findView(ib.elseBranch);
                        if (child != null) {
                            int x1 = v.getX() + v.getWidth();
                            int y1 = v.getY() + v.getHeight()/2;
                            int x2 = child.getX();
                            int y2 = child.getY() + child.getHeight()/2;
                            g2.drawLine(x1, y1, x2, y2);
                        }
                    }
                    if (ib.next != null) {
                        BlockView child = findView(ib.next);
                        if (child != null) {
                            int x1 = v.getX() + v.getWidth()/2;
                            int y1 = v.getY() + v.getHeight();
                            int x2 = child.getX() + child.getWidth()/2;
                            int y2 = child.getY();
                            g2.drawLine(x1, y1, x2, y2);
                        }
                    }
                } else if (blk.next != null) {
                    BlockView child = findView(blk.next);
                    if (child != null) {
                        int x1 = v.getX() + v.getWidth()/2;
                        int y1 = v.getY() + v.getHeight();
                        int x2 = child.getX() + child.getWidth()/2;
                        int y2 = child.getY();
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
            }
            g2.dispose();
        }

        void detach(BlockView child) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;
            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(sel.id, Collections.emptyList());
            for (EventBlock ev : roots) {
                detachLinks(ev, child.block);
            }
        }

        void detachLinks(Block b, Block target) {
            if (b == null) return;
            if (b.next == target) b.next = null;
            if (b instanceof IfElseBlock ib) {
                if (ib.thenBranch == target) ib.thenBranch = null;
                if (ib.elseBranch == target) ib.elseBranch = null;
                detachLinks(ib.thenBranch, target);
                detachLinks(ib.elseBranch, target);
                detachLinks(ib.next, target);
            } else {
                detachLinks(b.next, target);
            }
        }

        void tryAttach(BlockView candidate) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;

            // No permitir encadenar un EVENT debajo de otro bloque
            if (candidate.block instanceof EventBlock) return;
            BlockView target = null;
            Point center = new Point(candidate.getX() + candidate.getWidth()/2,
                                     candidate.getY() + candidate.getHeight()/2);
            for (Component comp : getComponents()) {
                if (!(comp instanceof BlockView)) continue;
                BlockView other = (BlockView) comp;
                if (other == candidate) continue;
                Rectangle bounds = other.getBounds();
                Rectangle right = new Rectangle(bounds.x + bounds.width, bounds.y, 40, bounds.height);
                if (bounds.contains(center) || right.contains(center)) {
                    target = other;
                    break;
                }
            }
            if (target != null) {
                detach(candidate);
                if (target.block instanceof IfElseBlock ib) {
                    Rectangle bounds = target.getBounds();
                    if (center.x >= bounds.x + bounds.width) {
                        ib.elseBranch = candidate.block;
                    } else {
                        ib.thenBranch = candidate.block;
                    }
                } else {
                    Block tail = target.block;
                    while (tail.next != null) tail = tail.next;
                    tail.next = candidate.block;
                }
                repaint();
            }
        }

        void deleteBlock(BlockView bv) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;

            // eliminar del modelo
            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(sel.id, new ArrayList<>());
            if (bv.block instanceof EventBlock) {
                roots.remove(bv.block);
            } else {
                for (EventBlock ev : roots) {
                    detachLinks(ev, bv.block);
                }
            }

            // reconstruir vistas
            redrawForSelected();
        }
    }

    // ====== VISTA DE BLOQUE ======
    static class BlockView extends JComponent {
        final Block block;
        final ScriptCanvasPanel canvas;
        Point dragOffset = null;

        BlockView(Block block, ScriptCanvasPanel canvas) {
            this.block = block; this.canvas = canvas;
            enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
            setFocusable(true);
            setToolTipText("Doble clic para editar parámetros");
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(block.title()) + 30;
            return new Dimension(Math.max(180, w), 36);
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem del = new JMenuItem("Eliminar");
                    del.addActionListener(ev -> canvas.deleteBlock(this));
                    menu.add(del);
                    menu.show(this, e.getX(), e.getY());
                } else {
                    dragOffset = e.getPoint();
                    requestFocusInWindow();
                }
            } else if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount()==2) {
                editParams();
                repaint();
            } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                if (dragOffset != null) {
                    canvas.tryAttach(this);
                }
                dragOffset = null;
            }
            super.processMouseEvent(e);
        }

        @Override protected void processMouseMotionEvent(MouseEvent e) {
            if (dragOffset != null && e.getID() == MouseEvent.MOUSE_DRAGGED) {
                Point p = getLocation();
                p.translate(e.getX() - dragOffset.x, e.getY() - dragOffset.y);
                setLocation(p);
                block.x = p.x; block.y = p.y;
                repaint();
                canvas.repaint();
            }
            super.processMouseMotionEvent(e);
        }

        @Override protected void processKeyEvent(KeyEvent e) {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_DELETE) {
                canvas.deleteBlock(this);
            }
            super.processKeyEvent(e);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean isEvent = block.kind()==BlockKind.EVENT;
            Color base = isEvent ? new Color(0xF9E79F) : new Color(0xD6EAF8);
            g2.setColor(base);
            g2.fillRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);
            g2.setColor(Color.GRAY);
            g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.drawString(block.title(), 12, 22);

            g2.dispose();
        }

        void editParams() {
            if (block instanceof EventBlock) {
                EventBlock ev = (EventBlock) block;
                if (ev.type == EventType.ON_TICK) {
                    String s = JOptionPane.showInputDialog(this, "Intervalo (ms):", ev.args.getOrDefault("intervalMs", 500));
                    if (s != null && s.matches("\\d+")) ev.args.put("intervalMs", Integer.parseInt(s));
                } else if (ev.type == EventType.ON_KEY_DOWN) {
                    String s = JOptionPane.showInputDialog(this, "Tecla (ej: LEFT, RIGHT, UP, DOWN, SPACE, A..Z):", KeyEvent.getKeyText((int)ev.args.getOrDefault("keyCode", KeyEvent.VK_RIGHT)));
                    if (s != null && !s.isBlank()) {
                        int kc = parseKey(s.trim());
                        ev.args.put("keyCode", kc);
                    }
                } else if (ev.type == EventType.ON_MOUSE) {
                    String[] opts = {"Izquierdo","Derecho"};
                    int sel = JOptionPane.showOptionDialog(this, "Botón", "Botón", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
                    if (sel == 0) ev.args.put("button", MouseEvent.BUTTON1);
                    else if (sel == 1) ev.args.put("button", MouseEvent.BUTTON3);
                } else if (ev.type == EventType.ON_VAR_CHANGE || ev.type == EventType.ON_GLOBAL_VAR_CHANGE) {
                    java.util.List<String> vars = new ArrayList<>();
                    if (ev.type == EventType.ON_VAR_CHANGE) {
                        Entity selEnt = canvas.listPanel.getSelected();
                        if (selEnt != null) vars.addAll(selEnt.vars.keySet());
                    } else {
                        vars.addAll(canvas.project.globalVars.keySet());
                    }
                    if (vars.isEmpty()) vars.add("var");
                    String current = String.valueOf(ev.args.getOrDefault("var", vars.get(0)));
                    if (!vars.contains(current)) vars.add(0, current);
                    JSpinner varSpin = new JSpinner(new SpinnerListModel(vars));
                    varSpin.setValue(current);
                    JSpinner valSpin = new JSpinner(new SpinnerNumberModel(
                            ((Number)ev.args.getOrDefault("value",0)).doubleValue(), -1e9,1e9,1.0));
                    JPanel pan = new JPanel(new GridLayout(2,2));
                    pan.add(new JLabel(ev.type == EventType.ON_GLOBAL_VAR_CHANGE ? "Variable global" : "Variable"));
                    pan.add(varSpin);
                    pan.add(new JLabel("Valor"));
                    pan.add(valSpin);
                    int r = JOptionPane.showConfirmDialog(this, pan, "Configurar", JOptionPane.OK_CANCEL_OPTION);
                    if (r == JOptionPane.OK_OPTION) {
                        ev.args.put("var", String.valueOf(varSpin.getValue()));
                        ev.args.put("value", ((Number)valSpin.getValue()).doubleValue());
                    }
                }
            } else if (block instanceof ActionBlock) {
                ActionBlock ab = (ActionBlock) block;
                switch (ab.type) {
                    case MOVE_BY -> {
                        String[] dirs = {"Derecha","Izquierda","Arriba","Abajo"};
                        int curDx = (int) ab.args.getOrDefault("dx", 5);
                        int curDy = (int) ab.args.getOrDefault("dy", 0);
                        int selDir;
                        if (Math.abs(curDx) >= Math.abs(curDy)) selDir = curDx >= 0 ? 0 : 1; else selDir = curDy < 0 ? 2 : 3;
                        selDir = Math.max(0, Math.min(selDir, 3));
                        int dir = JOptionPane.showOptionDialog(this, "Dirección", "Dirección", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, dirs, dirs[selDir]);
                        if (dir >= 0) {
                            String pasos = JOptionPane.showInputDialog(this, "Pixeles:", Math.max(Math.abs(curDx), Math.abs(curDy)));
                            if (pasos != null && pasos.matches("\\d+")) {
                                int val = Integer.parseInt(pasos);
                                switch (dir) {
                                    case 0 -> { ab.args.put("dx", val); ab.args.put("dy", 0); }
                                    case 1 -> { ab.args.put("dx", -val); ab.args.put("dy", 0); }
                                    case 2 -> { ab.args.put("dx", 0); ab.args.put("dy", -val); }
                                    case 3 -> { ab.args.put("dx", 0); ab.args.put("dy", val); }
                                }
                            }
                        }
                    }
                    case SET_COLOR -> {
                        Color chosen = JColorChooser.showDialog(this, "Elegir color", (Color) ab.args.getOrDefault("color", new Color(0xE74C3C)));
                        if (chosen != null) ab.args.put("color", chosen);
                    }
                    case SAY -> {
                        String t = JOptionPane.showInputDialog(this, "Texto:", ab.args.getOrDefault("text", "¡Hola!"));
                        String secs = JOptionPane.showInputDialog(this, "Segundos:", ab.args.getOrDefault("secs", 2.0));
                        if (t != null && secs != null) {
                            try {
                                double s = Double.parseDouble(secs);
                                ab.args.put("text", t);
                                ab.args.put("secs", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case SET_VAR -> {
                        Entity sel = canvas.listPanel.getSelected();
                        java.util.Set<String> names = sel != null ? sel.vars.keySet() : java.util.Collections.emptySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        double cur = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                        JSpinner valSpin = new JSpinner(new SpinnerNumberModel(cur, -1e9, 1e9, 1.0));
                        JPanel pan = new JPanel(new GridLayout(2,2));
                        pan.add(new JLabel("Variable:")); pan.add(varBox);
                        pan.add(new JLabel("Valor:")); pan.add(valSpin);
                        if (names.isEmpty()) JOptionPane.showMessageDialog(this, "No hay variables", "Asignar variable", JOptionPane.WARNING_MESSAGE);
                        else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Asignar variable", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String v = (String) varBox.getSelectedItem();
                                double d = ((Number) valSpin.getValue()).doubleValue();
                                ab.args.put("var", v);
                                ab.args.put("value", d);
                            }
                        }
                    }
                    case CHANGE_VAR -> {
                        Entity sel = canvas.listPanel.getSelected();
                        java.util.Set<String> names = sel != null ? sel.vars.keySet() : java.util.Collections.emptySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        JComboBox<String> opBox = new JComboBox<>(new String[]{"Sumar","Restar"});
                        double cur = Math.abs(Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1))));
                        JSpinner amtSpin = new JSpinner(new SpinnerNumberModel(cur, 0.0, 1000.0, 1.0));
                        JPanel pan = new JPanel(new GridLayout(3,2));
                        pan.add(new JLabel("Variable:")); pan.add(varBox);
                        pan.add(new JLabel("Operación:")); pan.add(opBox);
                        pan.add(new JLabel("Cantidad:")); pan.add(amtSpin);
                        if (names.isEmpty()) JOptionPane.showMessageDialog(this, "No hay variables", "Cambiar variable", JOptionPane.WARNING_MESSAGE);
                        else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Cambiar variable", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String v = (String) varBox.getSelectedItem();
                                double d = ((Number) amtSpin.getValue()).doubleValue();
                                if (opBox.getSelectedIndex() == 1) d = -d;
                                ab.args.put("var", v);
                                ab.args.put("delta", d);
                            }
                        }
                    }
                    case SET_GLOBAL_VAR -> {
                        java.util.Set<String> names = canvas.project.globalVars.keySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        double cur = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                        JSpinner valSpin = new JSpinner(new SpinnerNumberModel(cur, -1e9, 1e9, 1.0));
                        JPanel pan = new JPanel(new GridLayout(2,2));
                        pan.add(new JLabel("Variable:")); pan.add(varBox);
                        pan.add(new JLabel("Valor:")); pan.add(valSpin);
                        if (names.isEmpty()) JOptionPane.showMessageDialog(this, "No hay variables", "Asignar variable", JOptionPane.WARNING_MESSAGE);
                        else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Asignar variable", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String v = (String) varBox.getSelectedItem();
                                double d = ((Number) valSpin.getValue()).doubleValue();
                                ab.args.put("var", v);
                                ab.args.put("value", d);
                            }
                        }
                    }
                    case CHANGE_GLOBAL_VAR -> {
                        java.util.Set<String> names = canvas.project.globalVars.keySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        JComboBox<String> opBox = new JComboBox<>(new String[]{"Sumar","Restar"});
                        double cur = Math.abs(Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1))));
                        JSpinner amtSpin = new JSpinner(new SpinnerNumberModel(cur, 0.0, 1000.0, 1.0));
                        JPanel pan = new JPanel(new GridLayout(3,2));
                        pan.add(new JLabel("Variable:")); pan.add(varBox);
                        pan.add(new JLabel("Operación:")); pan.add(opBox);
                        pan.add(new JLabel("Cantidad:")); pan.add(amtSpin);
                        if (names.isEmpty()) JOptionPane.showMessageDialog(this, "No hay variables", "Cambiar variable", JOptionPane.WARNING_MESSAGE);
                        else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Cambiar variable", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String v = (String) varBox.getSelectedItem();
                                double d = ((Number) amtSpin.getValue()).doubleValue();
                                if (opBox.getSelectedIndex() == 1) d = -d;
                                ab.args.put("var", v);
                                ab.args.put("delta", d);
                            }
                        }
                    }
                    case WAIT -> {
                        String secs = JOptionPane.showInputDialog(this, "Segundos:", ab.args.getOrDefault("secs", 1.0));
                        if (secs != null) {
                            try {
                                double s = Double.parseDouble(secs);
                                ab.args.put("secs", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case ROTATE_BY -> {
                        String deg = JOptionPane.showInputDialog(this, "Grados:", ab.args.getOrDefault("deg", 15));
                        if (deg != null) {
                            try {
                                double s = Double.parseDouble(deg);
                                ab.args.put("deg", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case ROTATE_TO -> {
                        String deg = JOptionPane.showInputDialog(this, "Grados destino:", ab.args.getOrDefault("deg", 0));
                        if (deg != null) {
                            try {
                                double s = Double.parseDouble(deg);
                                ab.args.put("deg", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case SCALE_BY -> {
                        String fac = JOptionPane.showInputDialog(this, "Factor:", ab.args.getOrDefault("factor", 1.1));
                        if (fac != null) {
                            try {
                                double s = Double.parseDouble(fac);
                                ab.args.put("factor", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case SET_SIZE -> {
                        String w = JOptionPane.showInputDialog(this, "Ancho:", ab.args.getOrDefault("w", 60));
                        String h = JOptionPane.showInputDialog(this, "Alto:", ab.args.getOrDefault("h", 60));
                        if (w != null && h != null) {
                            try {
                                double ww = Double.parseDouble(w);
                                double hh = Double.parseDouble(h);
                                ab.args.put("w", ww);
                                ab.args.put("h", hh);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case CHANGE_OPACITY -> {
                        String d = JOptionPane.showInputDialog(this, "Delta:", ab.args.getOrDefault("delta", 0.1));
                        if (d != null) {
                            try {
                                double s = Double.parseDouble(d);
                                ab.args.put("delta", s);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case SPAWN_ENTITY -> {
                        if (canvas.project.entities.isEmpty()) break;
                        java.util.List<String> names = new ArrayList<>();
                        for (Entity en : canvas.project.entities) names.add(en.name);
                        String currentName = String.valueOf(ab.args.getOrDefault("templateName", names.get(0)));
                        if (!names.contains(currentName)) names.add(0, currentName);
                        JSpinner entSpin = new JSpinner(new SpinnerListModel(names));
                        entSpin.setValue(currentName);
                        JPanel pan = new JPanel(new GridLayout(1,2));
                        pan.add(new JLabel("Entidad"));
                        pan.add(entSpin);
                        int r = JOptionPane.showConfirmDialog(this, pan, "Crear entidad", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            String name = (String) entSpin.getValue();
                            Entity tpl = canvas.project.entities.stream()
                                    .filter(en -> en.name.equals(name)).findFirst().orElse(null);
                            if (tpl != null) {
                                ab.args.put("templateId", tpl.id);
                                ab.args.put("templateName", tpl.name);
                            }
                        }
                    }
                }
            } else if (block instanceof IfElseBlock) {
                IfElseBlock ib = (IfElseBlock) block;
                String var = JOptionPane.showInputDialog(this, "Variable:", ib.var);
                String cmp = JOptionPane.showInputDialog(this, "> que:", ib.compare);
                if (var != null && cmp != null) {
                    try {
                        ib.var = var;
                        ib.compare = Double.parseDouble(cmp);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        int parseKey(String name) {
            name = name.toUpperCase(Locale.ROOT).trim();
            switch (name) {
                case "LEFT": return KeyEvent.VK_LEFT;
                case "RIGHT": return KeyEvent.VK_RIGHT;
                case "UP": return KeyEvent.VK_UP;
                case "DOWN": return KeyEvent.VK_DOWN;
                case "SPACE": return KeyEvent.VK_SPACE;
            }
            if (name.length()==1) {
                char c = name.charAt(0);
                if (c>='A' && c<='Z') return KeyEvent.getExtendedKeyCodeForChar(c);
                if (c>='0' && c<='9') return KeyEvent.getExtendedKeyCodeForChar(c);
            }
            // fallback
            return KeyEvent.VK_RIGHT;
        }
    }

    // ====== ESCENARIO ======
    static class StagePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener {
        final Project project;
        final Set<Integer> keysDown;
        Runnable onPlay, onStop, onBack;
        GameRuntime runtime;

        final Dimension size = new Dimension(900, 620);
        // Entidades actualmente presentes en el escenario (separadas de las plantillas del proyecto)
        final List<Entity> entities = new ArrayList<>();
        Entity dragEntity = null;
        Entity selectedEntity = null;
        Point dragOffset = null;
        boolean playing = false;
        boolean deleteMode = false;

        StagePanel(Project p, Set<Integer> keysDown) {
            this.project = p; this.keysDown = keysDown;
            setLayout(new BorderLayout());

            // Top bar
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton btnBack = new JButton("◀ Volver al Editor");
            JButton btnPlay = new JButton("▶ Probar");
            JButton btnStop = new JButton("■ Detener");
            JButton btnAddEntity = new JButton("Agregar Entidad");
            JButton btnDelEntity = new JButton("Eliminar Entidad");
            bar.add(btnBack);
            bar.add(btnPlay); bar.add(btnStop);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(btnAddEntity); bar.add(btnDelEntity);
            add(bar, BorderLayout.NORTH);

            // Canvas
            CanvasView cv = new CanvasView();
            cv.setPreferredSize(size);
            cv.setBackground(Color.WHITE);
            cv.setFocusable(true);
            cv.addKeyListener(this);
            cv.addMouseListener(this);
            cv.addMouseMotionListener(this);
            add(cv, BorderLayout.CENTER);

            btnBack.addActionListener(e -> {
                playing = false;
                btnPlay.setBackground(null);
                btnPlay.setOpaque(false);
                deleteMode = false;
                btnDelEntity.setBackground(null);
                btnDelEntity.setOpaque(false);
                // limpiar entidades temporales del escenario
                for (Entity en : new ArrayList<>(entities)) {
                    project.scriptsByEntity.remove(en.id);
                }
                entities.clear();
                selectedEntity = null;
                if (onBack!=null) onBack.run();
            });
            btnPlay.addActionListener(e -> {
                playing = true;
                deleteMode = false;
                btnDelEntity.setBackground(null);
                btnDelEntity.setOpaque(false);
                btnPlay.setBackground(new Color(0x2ECC71));
                btnPlay.setOpaque(true);
                requestFocusInWindow();
                cv.requestFocusInWindow();
                if (onPlay != null) onPlay.run();
            });
            btnStop.addActionListener(e -> {
                playing = false;
                btnPlay.setBackground(null);
                btnPlay.setOpaque(false);
                deleteMode = false;
                btnDelEntity.setBackground(null);
                btnDelEntity.setOpaque(false);
                if (onStop != null) onStop.run();
                repaint();
            });
            btnAddEntity.addActionListener(e -> {
                if (playing) return;
                if (project.entities.isEmpty()) return;
                String[] opts = project.entities.stream().map(en->en.name).toArray(String[]::new);
                String selName = (String) JOptionPane.showInputDialog(this, "Entidad", "Agregar entidad", JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
                if (selName != null) {
                    Entity tpl = project.entities.stream().filter(en->en.name.equals(selName)).findFirst().orElse(null);
                    if (tpl != null) {
                        Entity clone = cloneEntity(tpl);
                        entities.add(clone);
                        project.scriptsByEntity.put(clone.id, cloneScripts(tpl.id));
                        selectedEntity = clone;
                        repaint();
                    }
                }
            });
            btnDelEntity.addActionListener(e -> {
                deleteMode = !deleteMode;
                if (deleteMode) {
                    btnDelEntity.setBackground(Color.RED);
                    btnDelEntity.setOpaque(true);
                } else {
                    btnDelEntity.setBackground(null);
                    btnDelEntity.setOpaque(false);
                }
            });
        }

        Entity cloneEntity(Entity src) {
            Entity c = new Entity();
            c.name = src.name + "_copia";
            c.t.x = src.t.x;
            c.t.y = src.t.y;
            c.t.rot = src.t.rot;
            c.a.shape = src.a.shape;
            c.a.color = src.a.color;
            c.a.width = src.a.width;
            c.a.height = src.a.height;
            c.a.opacity = src.a.opacity;
            c.vars.putAll(src.vars);
            return c;
        }

        List<EventBlock> cloneScripts(String srcId) {
            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(srcId, new ArrayList<>());
            List<EventBlock> out = new ArrayList<>();
            for (EventBlock ev : roots) {
                out.add((EventBlock) cloneBlock(ev));
            }
            return out;
        }

        Block cloneBlock(Block b) {
            if (b == null) return null;
            Block copy;
            if (b instanceof EventBlock) {
                EventBlock ev = (EventBlock) b;
                EventBlock ev2 = new EventBlock(ev.type);
                ev2.args.putAll(ev.args);
                for (Block extra : ev.extraNext) ev2.extraNext.add(cloneBlock(extra));
                copy = ev2;
            } else if (b instanceof ActionBlock) {
                ActionBlock ab = (ActionBlock) b;
                ActionBlock ab2 = new ActionBlock(ab.type);
                ab2.args.putAll(ab.args);
                copy = ab2;
            } else if (b instanceof IfElseBlock) {
                IfElseBlock ib = (IfElseBlock) b;
                IfElseBlock ib2 = new IfElseBlock();
                ib2.var = ib.var;
                ib2.compare = ib.compare;
                ib2.thenBranch = cloneBlock(ib.thenBranch);
                ib2.elseBranch = cloneBlock(ib.elseBranch);
                copy = ib2;
            } else if (b instanceof WhileBlock) {
                WhileBlock wb = (WhileBlock) b;
                WhileBlock wb2 = new WhileBlock();
                wb2.var = wb.var;
                wb2.compare = wb.compare;
                wb2.body = cloneBlock(wb.body);
                copy = wb2;
            } else {
                copy = null;
            }
            if (copy != null) {
                copy.x = b.x;
                copy.y = b.y;
                copy.next = cloneBlock(b.next);
            }
            return copy;
        }

        void setRuntimeControls(Runnable play, Runnable stop, Runnable back) {
            this.onPlay = play; this.onStop = stop; this.onBack = back;
        }

        void setRuntime(GameRuntime rt) { this.runtime = rt; }

        @Override public void addNotify() {
            super.addNotify();
            requestFocusInWindow();
        }

        // ===== Canvas interno =====
        class CanvasView extends JPanel {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // fondo
                g2.setColor(new Color(0xF4F6F7));
                g2.fillRect(0,0,getWidth(),getHeight());

                // rejilla ligera
                g2.setColor(new Color(0xEAECEE));
                for (int x=0; x<getWidth(); x+=40) g2.drawLine(x,0,x,getHeight());
                for (int y=0; y<getHeight(); y+=40) g2.drawLine(0,y,getWidth(),y);

                g2.setColor(Color.DARK_GRAY);
                int gy = 15;
                for (Map.Entry<String, Double> gv : project.globalVars.entrySet()) {
                    g2.drawString(gv.getKey() + ": " + gv.getValue(), 10, gy);
                    gy += 15;
                }

                // dibujar entidades
                for (Entity e : entities) {
                    drawEntity(g2, e);
                }
                g2.dispose();
            }

            void drawEntity(Graphics2D g2, Entity e) {
                AffineTransform old = g2.getTransform();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) e.a.opacity));
                g2.translate(e.t.x + e.a.width/2, e.t.y + e.a.height/2);
                g2.rotate(Math.toRadians(e.t.rot));
                g2.setColor(e.a.color);
                if (e.a.shape == ShapeType.RECT) {
                    g2.fillRect((int)(-e.a.width/2), (int)(-e.a.height/2), (int)e.a.width, (int)e.a.height);
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawRect((int)(-e.a.width/2), (int)(-e.a.height/2), (int)e.a.width, (int)e.a.height);
                } else {
                    g2.fillOval((int)(-e.a.width/2), (int)(-e.a.width/2), (int)e.a.width, (int)e.a.width);
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawOval((int)(-e.a.width/2), (int)(-e.a.width/2), (int)e.a.width, (int)e.a.width);
                }
                g2.setTransform(old);
                g2.setColor(Color.DARK_GRAY);
                int vy = (int)e.t.y - 18;
                for (Map.Entry<String, Double> var : e.vars.entrySet()) {
                    g2.drawString(var.getKey() + ":" + var.getValue(), (int)e.t.x + 4, vy);
                    vy -= 12;
                }
                g2.drawString(e.name, (int)e.t.x + 4, (int)e.t.y - 4);
                if (e == selectedEntity) {
                    g2.setColor(Color.RED);
                    g2.drawRect((int)e.t.x - 2, (int)e.t.y - 2,
                            (int)e.a.width + 4, (int)e.a.height + 4);
                }

                if (e.sayText != null) {
                    g2.setColor(new Color(255,255,255,230));
                    int w = Math.min(220, Math.max(80, e.sayText.length()*7));
                    int h = 28;
                    int bx = (int)e.t.x; int by = (int)(e.t.y - h - 18);
                    g2.fillRoundRect(bx, by, w, h, 10,10);
                    g2.setColor(Color.GRAY);
                    g2.drawRoundRect(bx, by, w, h, 10,10);
                    g2.drawLine(bx+w/2, by+h, (int)e.t.x + (int)e.a.width/2, (int)e.t.y);
                    g2.setColor(Color.BLACK);
                    g2.drawString(e.sayText, bx+8, by+18);
                }
            }
        }

        // ====== Input ======
        @Override public void keyTyped(KeyEvent e) {}
        @Override public void keyPressed(KeyEvent e) { keysDown.add(e.getKeyCode()); }
        @Override public void keyReleased(KeyEvent e) { keysDown.remove(e.getKeyCode()); }

        // ====== Drag de entidades en modo edición ======
        @Override public void mouseClicked(MouseEvent e) {}
        @Override public void mousePressed(MouseEvent e) {
            if (playing) {
                if (runtime != null) runtime.handleMouseEvent(e);
                return;
            }
            if (deleteMode) {
                List<Entity> revDel = new ArrayList<>(entities);
                Collections.reverse(revDel);
                for (Entity en : revDel) {
                    if (hit(en, e.getPoint())) {
                        entities.remove(en);
                        project.scriptsByEntity.remove(en.id);
                        if (selectedEntity == en) selectedEntity = null;
                        if (dragEntity == en) { dragEntity = null; dragOffset = null; }
                        repaint();
                        break;
                    }
                }
                return;
            }
            // buscar entidad bajo ratón (de arriba hacia abajo)
            selectedEntity = null;
            List<Entity> rev = new ArrayList<>(entities);
            Collections.reverse(rev);
            for (Entity en : rev) {
                if (hit(en, e.getPoint())) {
                    dragEntity = en;
                    selectedEntity = en;
                    dragOffset = new Point((int)(e.getX() - en.t.x), (int)(e.getY() - en.t.y));
                    break;
                }
            }
            repaint();
        }
        @Override public void mouseReleased(MouseEvent e) { dragEntity = null; dragOffset = null; }
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}
        @Override public void mouseDragged(MouseEvent e) {
            if (playing) return;
            if (dragEntity != null && dragOffset != null) {
                dragEntity.t.x = e.getX() - dragOffset.x;
                dragEntity.t.y = e.getY() - dragOffset.y;
                dragEntity.t.x = Math.max(0, Math.min(dragEntity.t.x, size.width - dragEntity.a.width));
                dragEntity.t.y = Math.max(0, Math.min(dragEntity.t.y, size.height - dragEntity.a.height));
                repaint();
            }
        }
        @Override public void mouseMoved(MouseEvent e) {}

        boolean hit(Entity e, Point p) {
            if (e.a.shape == ShapeType.RECT) {
                Rectangle r = new Rectangle((int)e.t.x, (int)e.t.y, (int)e.a.width, (int)e.a.height);
                return r.contains(p);
            } else {
                int r = (int)(e.a.width/2);
                int cx = (int)(e.t.x + r), cy = (int)(e.t.y + r);
                int dx = p.x - cx, dy = p.y - cy;
                return (dx*dx + dy*dy) <= r*r;
            }
        }
    }
}
