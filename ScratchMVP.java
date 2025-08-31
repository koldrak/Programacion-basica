import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
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

        // Runtime UI (globo de texto)
        String sayText = null;
        long sayUntilMs = 0;
    }

    static class Project {
        List<Entity> entities = new ArrayList<>();
        // scripts por entidad (raíces de eventos)
        Map<String, List<EventBlock>> scriptsByEntity = new HashMap<>();
        Dimension canvas = new Dimension(800, 600);

        Entity getById(String id) {
            for (Entity e : entities) if (e.id.equals(id)) return e;
            return null;
        }
    }

    // ====== BLOQUES ======
    enum BlockKind { EVENT, ACTION }

    enum EventType { ON_START, ON_TICK, ON_KEY_DOWN }
    enum ActionType { MOVE_BY, SET_COLOR, SAY }

    static abstract class Block {
        final String id = UUID.randomUUID().toString();
        Block next;               // cadena secuencial
        abstract BlockKind kind();
        abstract String title();
    }

    static class EventBlock extends Block {
        EventType type;
        Map<String, Object> args = new HashMap<>(); // intervalMs, keyCode, etc.

        EventBlock(EventType t) { this.type = t; }

        @Override BlockKind kind() { return BlockKind.EVENT; }
        @Override String title() {
            switch (type) {
                case ON_START: return "Evento: Al iniciar";
                case ON_TICK: return "Evento: Cada (ms)=" + args.getOrDefault("intervalMs", 500);
                case ON_KEY_DOWN:
                    int kc = (int) args.getOrDefault("keyCode", KeyEvent.VK_RIGHT);
                    return "Evento: Tecla " + KeyEvent.getKeyText(kc);
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
                    return "Acción: Mover (" + args.getOrDefault("dx", 5) + "," + args.getOrDefault("dy", 0) + ")";
                case SET_COLOR:
                    return "Acción: Color";
                case SAY:
                    return "Acción: Decir \"" + args.getOrDefault("text", "¡Hola!") + "\"";
            }
            return "Acción";
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

        GameRuntime(Project p, StagePanel s, Set<Integer> keysDown) {
            this.project = p;
            this.stage = s;
            this.keysDown = keysDown;
        }

        void play() {
            stop();
            // ON_START una vez
            for (Entity e : project.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(e.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_START) {
                        executeChain(e, ev.next);
                    } else if (ev.type == EventType.ON_TICK) {
                        tickLastFire.computeIfAbsent(e.id, k -> new HashMap<>()).put(ev, System.currentTimeMillis());
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
            for (Entity en : project.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_TICK) {
                        int interval = (int) ev.args.getOrDefault("intervalMs", 500);
                        long last = tickLastFire.getOrDefault(en.id, Collections.emptyMap()).getOrDefault(ev, nowMs);
                        if (nowMs - last >= interval) {
                            executeChain(en, ev.next);
                            tickLastFire.get(en.id).put(ev, nowMs);
                        }
                    }
                }
            }

            // ON_KEY_DOWN (se dispara cada frame si la tecla está presionada)
            for (Entity en : project.entities) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_KEY_DOWN) {
                        int kc = (int) ev.args.getOrDefault("keyCode", KeyEvent.VK_RIGHT);
                        if (keysDown.contains(kc)) {
                            executeChain(en, ev.next);
                        }
                    }
                }
            }

            // limpiar "decir" expirado
            long t = System.currentTimeMillis();
            for (Entity en : project.entities) {
                if (en.sayText != null && t > en.sayUntilMs) {
                    en.sayText = null;
                }
            }

            stage.repaint();
        }

        void executeChain(Entity e, Block b) {
            while (b != null) {
                if (b instanceof ActionBlock) {
                    executeAction(e, (ActionBlock) b);
                }
                b = b.next;
            }
        }

        void executeAction(Entity e, ActionBlock ab) {
            switch (ab.type) {
                case MOVE_BY:
                    int dx = (int) ab.args.getOrDefault("dx", 5);
                    int dy = (int) ab.args.getOrDefault("dy", 0);
                    e.t.x += dx;
                    e.t.y += dy;
                    // Limitar a canvas
                    e.t.x = Math.max(0, Math.min(e.t.x, stage.size.width - e.a.width));
                    e.t.y = Math.max(0, Math.min(e.t.y, stage.size.height - e.a.height));
                    break;
                case SET_COLOR:
                    Color chosenColor = (Color) ab.args.getOrDefault("color", new Color(0xE74C3C));
                    e.a.color = chosenColor;
                    break;
                case SAY:
                    String text = String.valueOf(ab.args.getOrDefault("text", "¡Hola!"));
                    double secs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 2.0)));
                    e.sayText = text;
                    e.sayUntilMs = System.currentTimeMillis() + (long) (secs * 1000);
                    break;
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
            entityListPanel = new EntityListPanel(project);
            left.add(palettePanel, BorderLayout.CENTER);
            left.add(entityListPanel, BorderLayout.SOUTH);

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
            if (sel != null) {
                shapeBox.setSelectedIndex(sel.a.shape==ShapeType.RECT?0:1);
                wSpin.setValue((int)sel.a.width);
                hSpin.setValue((int)sel.a.height);
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

            add(Box.createVerticalStrut(10));
            add(section("Acciones"));
            add(makeBtn("Mover (dx,dy)", () -> {
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
            bv.setLocation(20, 40 + getBlockCountFor(sel)*80);
            bv.setSize(bv.getPreferredSize());
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
            // reconstruir vistas desde modelo
            List<BlockView> list = getViews(sel);
            list.clear();

            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(sel.id, Collections.emptyList());
            int y = 40;
            for (EventBlock ev : roots) {
                BlockView v = new BlockView(ev, this);
                add(v);
                v.setLocation(20, y);
                v.setSize(v.getPreferredSize());
                list.add(v);
                y += v.getHeight() + 20;

                // dibujar la cadena debajo (layout simple)
                int x = 40;
                Block cur = ev.next;
                int innerY = v.getY() + v.getHeight() + 10;
                while (cur != null) {
                    BlockView vc = new BlockView(cur, this);
                    add(vc);
                    vc.setLocation(x, innerY);
                    vc.setSize(vc.getPreferredSize());
                    list.add(vc);
                    innerY += vc.getHeight() + 10;
                    cur = cur.next;
                }
            }
            revalidate();
            repaint();
        }

        void detach(BlockView child) {
            // romper vínculo (si era next de alguien)
            Entity sel = listPanel.getSelected();
            if (sel == null) return;
            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(sel.id, Collections.emptyList());

            for (EventBlock ev : roots) {
                if (ev.next == child.block) ev.next = null;
                Block cur = ev.next;
                while (cur != null) {
                    if (cur.next == child.block) cur.next = null;
                    cur = cur.next;
                }
            }
        }

        void tryAttach(BlockView candidate) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;

            // No permitir encadenar un EVENT debajo de otro bloque
            if (candidate.block instanceof EventBlock) return;

            BlockView best = null;
            int bestDist = 24; // umbral
            for (Component comp : getComponents()) {
                if (!(comp instanceof BlockView)) continue;
                BlockView other = (BlockView) comp;
                if (other == candidate) continue;
                // solo se puede colgar bajo EVENT o ACCIÓN
                if (other.block.kind()==BlockKind.EVENT || other.block.kind()==BlockKind.ACTION) {
                    // punto de anclaje: borde inferior de "other"
                    Point attach = new Point(other.getX() + other.getWidth()/2, other.getY() + other.getHeight());
                    // punta superior de candidate
                    Point head = new Point(candidate.getX() + candidate.getWidth()/2, candidate.getY());
                    int dy = Math.abs(attach.y - head.y);
                    int dx = Math.abs(attach.x - head.x);
                    int dist = dy + dx;
                    if (dy < 20 && dx < 80 && dist < bestDist) {
                        bestDist = dist;
                        best = other;
                    }
                }
            }
            if (best != null) {
                // Romper vinculo anterior del candidato
                detach(candidate);
                // Enlazar en "next" del best (al final de su cadena)
                Block tail = best.block;
                while (tail.next != null) tail = tail.next;
                tail.next = candidate.block;

                // Alinear visualmente debajo
                candidate.setLocation(best.getX() + 20, best.getY() + best.getHeight() + 10);
                repaint();
            }
        }
    }

    // ====== VISTA DE BLOQUE ======
    static class BlockView extends JComponent {
        final Block block;
        final ScriptCanvasPanel canvas;
        Point dragOffset = null;

        BlockView(Block block, ScriptCanvasPanel canvas) {
            this.block = block; this.canvas = canvas;
            enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
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
                dragOffset = e.getPoint();
                requestFocusInWindow();
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
                repaint();
            }
            super.processMouseMotionEvent(e);
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
                }
            } else if (block instanceof ActionBlock) {
                ActionBlock ab = (ActionBlock) block;
                switch (ab.type) {
                    case MOVE_BY -> {
                        String dx = JOptionPane.showInputDialog(this, "dx:", ab.args.getOrDefault("dx", 5));
                        String dy = JOptionPane.showInputDialog(this, "dy:", ab.args.getOrDefault("dy", 0));
                        if (dx != null && dy != null && dx.matches("-?\\d+") && dy.matches("-?\\d+")) {
                            ab.args.put("dx", Integer.parseInt(dx));
                            ab.args.put("dy", Integer.parseInt(dy));
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

        final Dimension size = new Dimension(900, 620);
        Entity dragEntity = null;
        Point dragOffset = null;
        boolean playing = false;

        StagePanel(Project p, Set<Integer> keysDown) {
            this.project = p; this.keysDown = keysDown;
            setLayout(new BorderLayout());

            // Top bar
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton btnBack = new JButton("◀ Volver al Editor");
            JButton btnPlay = new JButton("▶ Probar");
            JButton btnStop = new JButton("■ Detener");
            bar.add(btnBack); bar.add(btnPlay); bar.add(btnStop);
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

            btnBack.addActionListener(e -> { if (onBack!=null) onBack.run(); });
            btnPlay.addActionListener(e -> {
                playing = true;
                requestFocusInWindow();
                cv.requestFocusInWindow();
                if (onPlay != null) onPlay.run();
            });
            btnStop.addActionListener(e -> {
                playing = false;
                if (onStop != null) onStop.run();
                repaint();
            });
        }

        void setRuntimeControls(Runnable play, Runnable stop, Runnable back) {
            this.onPlay = play; this.onStop = stop; this.onBack = back;
        }

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

                // dibujar entidades
                for (Entity e : project.entities) {
                    drawEntity(g2, e);
                }
                g2.dispose();
            }

            void drawEntity(Graphics2D g2, Entity e) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) e.a.opacity));
                g2.setColor(e.a.color);
                if (e.a.shape == ShapeType.RECT) {
                    g2.fillRect((int)e.t.x, (int)e.t.y, (int)e.a.width, (int)e.a.height);
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawRect((int)e.t.x, (int)e.t.y, (int)e.a.width, (int)e.a.height);
                } else {
                    g2.fillOval((int)(e.t.x), (int)(e.t.y), (int)e.a.width, (int)e.a.width);
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawOval((int)(e.t.x), (int)(e.t.y), (int)e.a.width, (int)e.a.width);
                }
                // nombre
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(e.name, (int)e.t.x + 4, (int)e.t.y - 4);

                // burbuja "decir"
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
            if (playing) return;
            // buscar entidad bajo ratón (de arriba hacia abajo)
            List<Entity> rev = new ArrayList<>(project.entities);
            Collections.reverse(rev);
            for (Entity en : rev) {
                if (hit(en, e.getPoint())) {
                    dragEntity = en;
                    dragOffset = new Point((int)(e.getX() - en.t.x), (int)(e.getY() - en.t.y));
                    break;
                }
            }
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
