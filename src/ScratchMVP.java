import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

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

    static final File PROJECTS_DIR = new File("proyectos");
    static final File BACKGROUNDS_DIR = new File("fondos");

    // ====== MODELO BÁSICO ======
    enum ShapeType { RECT, CIRCLE, TRIANGLE, PENTAGON, HEXAGON, STAR, POLYGON }

    static class Transform implements Serializable {
        double x = 100, y = 100, rot = 0, scaleX = 1, scaleY = 1;
    }

    static class Appearance implements Serializable {
        private static final long serialVersionUID = 9191861603754096743L;
        ShapeType shape = ShapeType.RECT;
        Color color = new Color(0x2E86DE);
        double width = 60, height = 60; // si CIRCLE, usa radius = width/2
        double opacity = 1.0;
        Polygon customPolygon = null; // para formas personalizadas
        String shapeName = null; // nombre de forma personalizada
        transient Map<String, BufferedImage> paintImages = new HashMap<>();
        Map<String, byte[]> paintImageBytes = new HashMap<>();
        Map<String, Color> colorByShape = new HashMap<>();

        String shapeKey() {
            return shape == ShapeType.POLYGON ? "POLYGON:" + (shapeName != null ? shapeName : "") : shape.name();
        }

        Color getColor() {
            return colorByShape.getOrDefault(shapeKey(), color);
        }

        void setColor(Color c) {
            colorByShape.put(shapeKey(), c);
        }

        BufferedImage getPaintImage() {
            return paintImages.get(shapeKey());
        }

        void setPaintImage(BufferedImage img) {
            if (img == null) paintImages.remove(shapeKey());
            else paintImages.put(shapeKey(), img);
        }

        private void writeObject(ObjectOutputStream out) throws IOException {
            paintImageBytes = new HashMap<>();
            for (Map.Entry<String, BufferedImage> e : paintImages.entrySet()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(e.getValue(), "png", baos);
                paintImageBytes.put(e.getKey(), baos.toByteArray());
            }
            out.defaultWriteObject();
            paintImageBytes = null;
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            paintImages = new HashMap<>();
            if (paintImageBytes != null) {
                for (Map.Entry<String, byte[]> e : paintImageBytes.entrySet()) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(e.getValue());
                    BufferedImage img = ImageIO.read(bais);
                    if (img != null) paintImages.put(e.getKey(), img);
                }
            }
            if (colorByShape == null) colorByShape = new HashMap<>();
            paintImageBytes = null;
        }
    }

    static class Entity implements Serializable {
        String id = UUID.randomUUID().toString();
        // Identificador de la entidad en la que se basó (plantilla)
        // Para las entidades de proyecto, coincide con su propio id
        String templateId = id;
        String name = "Entidad";
        Transform t = new Transform();
        Appearance a = new Appearance();
        Map<String, Double> vars = new HashMap<>();
        Set<String> visibleVars = new HashSet<>();

        // Runtime UI (globo de texto)
        String sayText = null;
        long sayUntilMs = 0;

        // Movimiento gradual hacia otra entidad
        String moveTargetId = null;
        double moveSpeed = 0;
        boolean moveFollow = false;

        // Movimiento por velocidad/dirección durante un tiempo
        double moveVx = 0, moveVy = 0; // velocidad en px/s
        long moveUntilMs = 0;          // instante en ms para detener
    }

    static class Scenario implements Serializable {
        List<Entity> entities = new ArrayList<>();
        Map<String, List<EventBlock>> scriptsByEntity = new HashMap<>();
        String backgroundImage;
        transient BufferedImage background;

        BufferedImage getBackground() {
            if (background == null && backgroundImage != null) {
                try {
                    background = ImageIO.read(new File(BACKGROUNDS_DIR, backgroundImage));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
            return background;
        }

        void setBackground(File f) {
            try {
                background = ImageIO.read(f);
                backgroundImage = f.getName();
            } catch (IOException ex) {
                ex.printStackTrace();
                background = null;
                backgroundImage = null;
            }
        }
    }

    static class Project implements Serializable {
        List<Entity> entities = new ArrayList<>();
        // scripts por entidad (raíces de eventos)
        Map<String, List<EventBlock>> scriptsByEntity = new HashMap<>();
        Map<String, Double> globalVars = new HashMap<>();
        Set<String> visibleGlobalVars = new HashSet<>();
        Map<String, Polygon> shapes = new LinkedHashMap<>();
        Dimension canvas = new Dimension(800, 600);
        List<Scenario> scenarios = new ArrayList<>();
        int currentScenario = 0;

        Project() {
            scenarios.add(new Scenario());
        }

        Entity getById(String id) {
            for (Entity e : entities) if (e.id.equals(id)) return e;
            return null;
        }
    }

    // Utilidades de formas
    static Shape makeRegularPolygon(int sides, double w, double h) {
        Path2D p = new Path2D.Double();
        double r = Math.min(w, h) / 2.0;
        for (int i = 0; i < sides; i++) {
            double ang = -Math.PI / 2 + i * 2 * Math.PI / sides;
            double x = Math.cos(ang) * r;
            double y = Math.sin(ang) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    static Shape makeStar(int points, double w, double h) {
        Path2D p = new Path2D.Double();
        double rOuter = Math.min(w, h) / 2.0;
        double rInner = rOuter / 2.0;
        for (int i = 0; i < points * 2; i++) {
            double r = (i % 2 == 0) ? rOuter : rInner;
            double ang = -Math.PI / 2 + i * Math.PI / points;
            double x = Math.cos(ang) * r;
            double y = Math.sin(ang) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    static Shape buildShape(Entity e) {
        Shape base;
        switch (e.a.shape) {
            case RECT -> base = new Rectangle2D.Double(-e.a.width / 2, -e.a.height / 2, e.a.width, e.a.height);
            case CIRCLE -> base = new Ellipse2D.Double(-e.a.width / 2, -e.a.width / 2, e.a.width, e.a.width);
            case TRIANGLE -> {
                Path2D t = new Path2D.Double();
                t.moveTo(0, -e.a.height / 2);
                t.lineTo(-e.a.width / 2, e.a.height / 2);
                t.lineTo(e.a.width / 2, e.a.height / 2);
                t.closePath();
                base = t;
            }
            case PENTAGON -> base = makeRegularPolygon(5, e.a.width, e.a.height);
            case HEXAGON -> base = makeRegularPolygon(6, e.a.width, e.a.height);
            case STAR -> base = makeStar(5, e.a.width, e.a.height);
            case POLYGON -> {
                Shape poly = (e.a.customPolygon != null ? e.a.customPolygon : new Rectangle2D.Double(0, 0, e.a.width, e.a.height));
                base = AffineTransform.getTranslateInstance(-e.a.width / 2, -e.a.height / 2)
                        .createTransformedShape(poly);
            }
            default -> base = new Rectangle2D.Double(-e.a.width / 2, -e.a.height / 2, e.a.width, e.a.height);
        }
        AffineTransform at = new AffineTransform();
        at.translate(e.t.x + e.a.width / 2, e.t.y + e.a.height / 2);
        at.rotate(Math.toRadians(e.t.rot));
        at.scale(e.t.scaleX, e.t.scaleY);
        return at.createTransformedShape(base);
    }

    static void saveProject(Project p, File f) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) {
            out.writeObject(p);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static void loadProject(Project target, File f) {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
            Project src = (Project) in.readObject();
            applyProject(target, src);
        } catch (InvalidClassException ex) {
            int opt = JOptionPane.showConfirmDialog(null,
                    "El proyecto es de una version anterior\n¿quieres abrirlo igualmente?",
                    "Version incompatible", JOptionPane.YES_NO_OPTION);
            if (opt == JOptionPane.YES_OPTION) {
                try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f)) {
                    @Override
                    protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                        ObjectStreamClass desc = super.readClassDescriptor();
                        try {
                            Class<?> localClass = Class.forName(desc.getName());
                            ObjectStreamClass localDesc = ObjectStreamClass.lookup(localClass);
                            return localDesc != null ? localDesc : desc;
                        } catch (ClassNotFoundException e) {
                            return desc;
                        }
                    }
                }) {
                    Project src = (Project) in.readObject();
                    applyProject(target, src);
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException | ClassNotFoundException ex) {
            ex.printStackTrace();
        }
    }

    private static void applyProject(Project target, Project src) {
        target.entities = src.entities;
        target.scriptsByEntity = src.scriptsByEntity;
        target.globalVars = src.globalVars;
        target.visibleGlobalVars = src.visibleGlobalVars != null ? src.visibleGlobalVars : new HashSet<>(target.globalVars.keySet());
        target.shapes = src.shapes != null ? src.shapes : new LinkedHashMap<>();
        target.canvas = src.canvas;
        target.scenarios = src.scenarios != null ? src.scenarios : new ArrayList<>();
        if (target.scenarios.isEmpty()) target.scenarios.add(new Scenario());
        target.currentScenario = src.currentScenario;
        // ensure visibility sets for entities
        for (Entity e : target.entities) {
            if (e.visibleVars == null) e.visibleVars = new HashSet<>(e.vars.keySet());
        }
        for (Scenario sc : target.scenarios) {
            for (Entity e : sc.entities) {
                if (e.visibleVars == null) e.visibleVars = new HashSet<>(e.vars.keySet());
            }
        }
    }

    static BufferedImage copyImage(BufferedImage img) {
        BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
        Graphics2D g = copy.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return copy;
    }

    // ====== BLOQUES ======
    enum BlockKind { EVENT, CONDITIONAL, ACTION }

    static final Color EVENT_COLOR = new Color(0xF5CBA7);
    static final Color CONDITIONAL_COLOR = new Color(0xD7BDE2);
    static final Color ACTION_COLOR = new Color(0xABEBC6);

    static Color colorFor(BlockKind kind) {
        return switch (kind) {
            case EVENT -> EVENT_COLOR;
            case CONDITIONAL -> CONDITIONAL_COLOR;
            case ACTION -> ACTION_COLOR;
        };
    }

    enum EventType { ON_START, ON_APPEAR, ON_TICK, ON_KEY_DOWN, ON_MOUSE, ON_EDGE, ON_VAR_CHANGE, ON_GLOBAL_VAR_CHANGE, ON_COLLIDE, ON_ENTITY_NEAR, ON_IDLE, ON_WHILE_VAR, ON_WHILE_GLOBAL_VAR }
    enum ActionType { MOVE_BY, SET_COLOR, SAY, SET_VAR, CHANGE_VAR, SET_GLOBAL_VAR, CHANGE_GLOBAL_VAR, WAIT, ROTATE_BY, ROTATE_TO, SCALE_BY, SET_SIZE, CHANGE_OPACITY, SET_SHAPE, SWITCH_SHAPES, RANDOM, IF_VAR, IF_GLOBAL_VAR, IF_RANDOM_CHANCE, MOVE_TO_ENTITY, SPAWN_ENTITY, DELETE_ENTITY, NEXT_SCENE, PREV_SCENE, GOTO_SCENE, STOP }

    static abstract class Block implements Serializable {
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
                case ON_APPEAR: return "Evento: Al aparecer";
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
                {
                    String var = String.valueOf(args.getOrDefault("var","var"));
                    String op = String.valueOf(args.getOrDefault("op", ">"));
                    Object val = args.getOrDefault("value",0);
                    return "Evento: Var de entidad " + var + " " + op + " " + val;
                }
                case ON_GLOBAL_VAR_CHANGE:
                {
                    String var = String.valueOf(args.getOrDefault("var","var"));
                    String op = String.valueOf(args.getOrDefault("op", ">"));
                    Object val = args.getOrDefault("value",0);
                    return "Evento: Var global " + var + " " + op + " " + val;
                }
                case ON_COLLIDE:
                {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> names = (java.util.List<String>) args.get("targetNames");
                    if (names == null || names.isEmpty()) return "Evento: Colisión con cualquiera";
                    return "Evento: Colisión con " + String.join(", ", names);
                }
                case ON_ENTITY_NEAR:
                {
                    String name = String.valueOf(args.getOrDefault("targetName", "entidad"));
                    double radius = ((Number) args.getOrDefault("radius", 50.0)).doubleValue();
                    return "Evento: Se acerca " + name + " r=" + radius;
                }
                case ON_IDLE:
                    return "Evento: Al estar libre";
                case ON_WHILE_VAR:
                {
                    String var = String.valueOf(args.getOrDefault("var","var"));
                    String op = String.valueOf(args.getOrDefault("op", ">"));
                    Object val = args.getOrDefault("value",0);
                    return "Evento: Mientras var de entidad " + var + " " + op + " " + val;
                }
                case ON_WHILE_GLOBAL_VAR:
                {
                    String var = String.valueOf(args.getOrDefault("var","var"));
                    String op = String.valueOf(args.getOrDefault("op", ">"));
                    Object val = args.getOrDefault("value",0);
                    return "Evento: Mientras var global " + var + " " + op + " " + val;
                }
            }
            return "Evento";
        }
    }

    static class ActionBlock extends Block {
        ActionType type;
        Map<String, Object> args = new HashMap<>(); // dx,dy,color,text,duration
        // ramas adicionales (p.ej. opciones de Aleatorio)
        List<Block> extraNext = new ArrayList<>();

        ActionBlock(ActionType t) { this.type = t; }

        @Override BlockKind kind() {
            return switch (type) {
                case RANDOM, IF_VAR, IF_GLOBAL_VAR, IF_RANDOM_CHANCE -> BlockKind.CONDITIONAL;
                default -> BlockKind.ACTION;
            };
        }
        @Override String title() {
            switch (type) {
                case MOVE_BY:
                    String dir = String.valueOf(args.getOrDefault("dir", "derecha"));
                    double speed = ((Number) args.getOrDefault("speed", 100.0)).doubleValue();
                    double secs = ((Number) args.getOrDefault("secs", 1.0)).doubleValue();
                    if ("seguir".equals(dir)) {
                        String name = String.valueOf(args.getOrDefault("targetName", "entidad"));
                        return "Acción: Seguir a " + name + " v=" + speed;
                    } else if ("lejos".equals(dir)) {
                        String name = String.valueOf(args.getOrDefault("targetName", "entidad"));
                        return "Acción: Mover lejos de " + name + " v=" + speed + " t=" + secs + "s";
                    }
                    return "Acción: Mover " + dir + " v=" + speed + " t=" + secs + "s";
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
                case SET_SHAPE:
                    String shp = String.valueOf(args.getOrDefault("shape","RECT"));
                    String lbl = switch (shp) {
                        case "CIRCLE" -> "círculo";
                        case "TRIANGLE" -> "triángulo";
                        case "PENTAGON" -> "pentágono";
                        case "HEXAGON" -> "hexágono";
                        case "STAR" -> "estrella";
                        case "POLYGON" -> String.valueOf(args.getOrDefault("shapeName","polígono"));
                        default -> "rectángulo";
                    };
                    return "Acción: Forma " + lbl;
                case SWITCH_SHAPES:
                    return "Acción: Alternar formas";
                case RANDOM:
                    return "Acción: Aleatorio";
                case IF_VAR:
                    String var = String.valueOf(args.getOrDefault("var", "var"));
                    String op = String.valueOf(args.getOrDefault("op", ">"));
                    Object val = args.getOrDefault("value", 0);
                    return "Acción: Si " + var + " " + op + " " + val;
                case IF_GLOBAL_VAR:
                    String gvar = String.valueOf(args.getOrDefault("var", "var"));
                    String gop = String.valueOf(args.getOrDefault("op", ">"));
                    Object gval = args.getOrDefault("value", 0);
                    return "Acción: Si global " + gvar + " " + gop + " " + gval;
                case IF_RANDOM_CHANCE:
                    Object prob = args.getOrDefault("prob", 0.5);
                    return "Acción: Si prob " + prob;
                case MOVE_TO_ENTITY:
                    String tgt = (String) args.get("targetName");
                    return "Acción: Mover a " + (tgt != null ? tgt : "entidad");
                case SPAWN_ENTITY:
                    String name = (String) args.get("templateName");
                    String mode = String.valueOf(args.getOrDefault("mode", "MAP"));
                    if ("REL".equals(mode)) {
                        String dirSpawn = String.valueOf(args.getOrDefault("dir", "abajo"));
                        Object dObj = args.getOrDefault("distance", 0);
                        double dist = dObj instanceof Number ? ((Number) dObj).doubleValue() : 0.0;
                        return "Acción: Crear " + (name != null ? name : "entidad") + " " + dirSpawn + " d=" + dist;
                    } else if ("RAND".equals(mode)) {
                        return "Acción: Crear " + (name != null ? name : "entidad") + " al azar";
                    } else {
                        Object xObj = args.getOrDefault("x", 0);
                        Object yObj = args.getOrDefault("y", 0);
                        double x = xObj instanceof Number ? ((Number) xObj).doubleValue() : 0.0;
                        double y = yObj instanceof Number ? ((Number) yObj).doubleValue() : 0.0;
                        return "Acción: Crear " + (name != null ? name : "entidad") + " (" + x + "," + y + ")";
                    }
                case DELETE_ENTITY:
                    return "Acción: Borrar entidad";
                case NEXT_SCENE:
                    return "Acción: Escenario siguiente";
                case PREV_SCENE:
                    return "Acción: Escenario anterior";
                case GOTO_SCENE:
                    return "Acción: Ir a esc " + args.getOrDefault("index",1);
                case STOP:
                    return "Acción: Detener";
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
        JCheckBox visibleCheck;
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
            visibleCheck = new JCheckBox();
            add(labeled("Visible", visibleCheck));
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
                visibleCheck.setEnabled(ok);
                if (ok) {
                    valueSpin.setValue(project.globalVars.getOrDefault(name, 0.0));
                    if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
                    visibleCheck.setSelected(project.visibleGlobalVars.contains(name));
                }
            });
            valueSpin.addChangeListener(ev -> {
                String name = list.getSelectedValue();
                if (name != null) {
                    project.globalVars.put(name, ((Number)valueSpin.getValue()).doubleValue());
                }
            });
            visibleCheck.addActionListener(ev -> {
                String name = list.getSelectedValue();
                if (name != null) {
                    if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
                    if (visibleCheck.isSelected()) project.visibleGlobalVars.add(name);
                    else project.visibleGlobalVars.remove(name);
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
                        if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>();
                        project.visibleGlobalVars.add(name);
                        refresh();
                    }
                }
            });
            btnDel.addActionListener(ev -> {
                String name = list.getSelectedValue();
                if (name != null) {
                    project.globalVars.remove(name);
                    if (project.visibleGlobalVars != null) project.visibleGlobalVars.remove(name);
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
            if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
            String sel = list.getSelectedValue();
            boolean ok = sel != null && project.globalVars.containsKey(sel);
            valueSpin.setEnabled(ok);
            btnDel.setEnabled(ok);
            visibleCheck.setEnabled(ok);
            if (ok) {
                valueSpin.setValue(project.globalVars.getOrDefault(sel, 0.0));
                if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
                visibleCheck.setSelected(project.visibleGlobalVars.contains(sel));
            }
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
        final List<Runnable> pendingOps = new ArrayList<>();
        final List<javax.swing.Timer> activeTimers = new ArrayList<>();
        Map<String, Integer> runningChains = new HashMap<>();
        Set<String> idleRunning = new HashSet<>();
        Set<String> idleTriggered = new HashSet<>();
        Map<String, List<javax.swing.Timer>> entityTimers = new HashMap<>();
        Map<String, List<javax.swing.Timer>> idleTimers = new HashMap<>();

        GameRuntime(Project p, StagePanel s, Set<Integer> keysDown) {
            this.project = p;
            this.stage = s;
            this.keysDown = keysDown;
        }

        void play() {
            stop();
            activeTimers.clear();
            entityTimers.clear();
            idleTimers.clear();
            tickLastFire.clear();
            varLast.clear();
            globalVarLast.clear();
            runningChains.clear();
            idleRunning.clear();
            idleTriggered.clear();
            // ON_START una vez
            for (Entity e : new ArrayList<>(stage.entities)) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(e.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_START || ev.type == EventType.ON_APPEAR) {
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
            for (javax.swing.Timer t : activeTimers) t.stop();
            activeTimers.clear();
            entityTimers.clear();
            idleTimers.clear();
            runningChains.clear();
            idleRunning.clear();
            idleTriggered.clear();
        }

        void update() {
            long nowNs = System.nanoTime();
            double dt = (nowNs - lastUpdateNs) / 1_000_000_000.0;
            lastUpdateNs = nowNs;

            long nowMs = System.currentTimeMillis();

            // Actualizar movimiento gradual hacia entidades objetivo
            for (Entity en : new ArrayList<>(stage.entities)) {
                if (en.moveTargetId != null) {
                    Entity target = new ArrayList<>(stage.entities).stream()
                            .filter(t -> t.id.equals(en.moveTargetId))
                            .findFirst().orElse(null);
                    if (target == null) {
                        en.moveTargetId = null;
                    } else {
                        double dx = target.t.x - en.t.x;
                        double dy = target.t.y - en.t.y;
                        double dist = Math.hypot(dx, dy);
                        double step = en.moveSpeed * dt;
                        if (dist <= step) {
                            en.t.x = target.t.x;
                            en.t.y = target.t.y;
                            if (!en.moveFollow) en.moveTargetId = null;
                        } else if (dist > 0) {
                            en.t.x += dx / dist * step;
                            en.t.y += dy / dist * step;
                        }
                    }
                }
            }

            // Movimiento por velocidad/duración
            for (Entity en : new ArrayList<>(stage.entities)) {
                if (en.moveUntilMs > nowMs) {
                    en.t.x += en.moveVx * dt;
                    en.t.y += en.moveVy * dt;
                    stage.clampEntity(en);
                } else {
                    en.moveVx = en.moveVy = 0;
                }
            }

            // ON_TICK
            for (Entity en : new ArrayList<>(stage.entities)) {
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
            for (Entity en : new ArrayList<>(stage.entities)) {
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
            Rectangle2D stageRect = new Rectangle2D.Double(0, 0, stage.stageWidth(), stage.stageHeight());
            Area stageBorder = new Area(new BasicStroke(1f).createStrokedShape(stageRect));
            for (Entity en : new ArrayList<>(stage.entities)) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_EDGE) {
                        Shape sh = buildShape(en);
                        Area outside = new Area(sh);
                        outside.subtract(new Area(stageRect));
                        if (!outside.isEmpty()) {
                            triggerEvent(en, ev);
                            continue;
                        }
                        Area edgeHit = new Area(sh);
                        edgeHit.intersect(stageBorder);
                        if (!edgeHit.isEmpty()) {
                            triggerEvent(en, ev);
                        }
                    }
                }
            }

            // ON_VAR_CHANGE / GLOBAL / WHILE
            for (Entity en : new ArrayList<>(stage.entities)) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        String op = String.valueOf(ev.args.getOrDefault("op", ">"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = en.vars.getOrDefault(var, 0.0);
                        double prev = varLast.getOrDefault(en.id, Collections.emptyMap()).getOrDefault(ev, cur);
                        boolean cond = op.equals(">") ? cur > target : cur < target;
                        boolean prevCond = op.equals(">") ? prev > target : prev < target;
                        if (cond && !prevCond) {
                            triggerEvent(en, ev);
                        }
                        varLast.computeIfAbsent(en.id, k -> new HashMap<>()).put(ev, cur);
                    } else if (ev.type == EventType.ON_GLOBAL_VAR_CHANGE) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        String op = String.valueOf(ev.args.getOrDefault("op", ">"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = project.globalVars.getOrDefault(var, 0.0);
                        double prev = globalVarLast.getOrDefault(ev, cur);
                        boolean cond = op.equals(">") ? cur > target : cur < target;
                        boolean prevCond = op.equals(">") ? prev > target : prev < target;
                        if (cond && !prevCond) {
                            triggerEvent(en, ev);
                        }
                        globalVarLast.put(ev, cur);
                    } else if (ev.type == EventType.ON_WHILE_VAR) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        String op = String.valueOf(ev.args.getOrDefault("op", ">"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = en.vars.getOrDefault(var, 0.0);
                        boolean cond = op.equals(">") ? cur > target : cur < target;
                        if (cond) triggerEvent(en, ev);
                    } else if (ev.type == EventType.ON_WHILE_GLOBAL_VAR) {
                        String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                        String op = String.valueOf(ev.args.getOrDefault("op", ">"));
                        double target = Double.parseDouble(String.valueOf(ev.args.getOrDefault("value", 0)));
                        double cur = project.globalVars.getOrDefault(var, 0.0);
                        boolean cond = op.equals(">") ? cur > target : cur < target;
                        if (cond) triggerEvent(en, ev);
                    }
                }
            }

            // ON_COLLIDE
            for (Entity en : new ArrayList<>(stage.entities)) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_COLLIDE) {
                        @SuppressWarnings("unchecked")
                        java.util.List<String> ids = (java.util.List<String>) ev.args.get("targetIds");
                        @SuppressWarnings("unchecked")
                        java.util.List<String> names = (java.util.List<String>) ev.args.get("targetNames");
                        for (Entity other : new ArrayList<>(stage.entities)) {
                            if (other == en) continue;
                            boolean matchAll = (ids == null || ids.isEmpty()) && (names == null || names.isEmpty());
                            boolean matchId = ids != null && ids.contains(other.id);
                            boolean matchName = names != null && names.contains(other.name);
                            if (!matchAll && !matchId && !matchName) continue;
                            if (collides(en, other)) {
                                triggerEvent(en, ev);
                                break;
                            }
                        }
                    }
                }
            }

            // ON_ENTITY_NEAR
            for (Entity en : new ArrayList<>(stage.entities)) {
                List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                for (EventBlock ev : roots) {
                    if (ev.type == EventType.ON_ENTITY_NEAR) {
                        String targetId = (String) ev.args.get("targetId");
                        Entity target = targetId == null ? null : new ArrayList<>(stage.entities).stream()
                                .filter(o -> o.id.equals(targetId)).findFirst().orElse(null);
                        if (target == null) {
                            String targetName = (String) ev.args.get("targetName");
                            for (Entity other : new ArrayList<>(stage.entities)) {
                                if (other == en) continue;
                                if (targetName != null && !targetName.equals(other.name)) continue;
                                target = other;
                                break;
                            }
                        }
                        if (target != null) {
                            double ex = en.t.x + en.a.width / 2;
                            double ey = en.t.y + en.a.height / 2;
                            double tx = target.t.x + target.a.width / 2;
                            double ty = target.t.y + target.a.height / 2;
                            double dist = Math.hypot(ex - tx, ey - ty);
                            double radius = ((Number) ev.args.getOrDefault("radius", 50.0)).doubleValue();
                            if (dist <= radius) {
                                triggerEvent(en, ev);
                            }
                        }
                    }
                }
            }

            // ON_IDLE
            for (Entity en : new ArrayList<>(stage.entities)) {
                int count = runningChains.getOrDefault(en.id, 0);
                if (count == 0) {
                    if (!idleTriggered.contains(en.id)) {
                        List<EventBlock> roots = project.scriptsByEntity.getOrDefault(en.id, Collections.emptyList());
                        for (EventBlock ev : roots) {
                            if (ev.type == EventType.ON_IDLE) {
                                triggerEvent(en, ev);
                            }
                        }
                        idleTriggered.add(en.id);
                    }
                } else {
                    idleTriggered.remove(en.id);
                }
            }

            // limpiar "decir" expirado
            long t = System.currentTimeMillis();
            for (Entity en : new ArrayList<>(stage.entities)) {
                if (en.sayText != null && t > en.sayUntilMs) {
                    en.sayText = null;
                }
            }

            while (!pendingOps.isEmpty()) {
                List<Runnable> ops = new ArrayList<>(pendingOps);
                pendingOps.clear();
                for (Runnable op : ops) op.run();
            }

            stage.repaint();
        }

        void abortIdle(Entity e) {
            List<javax.swing.Timer> timers = idleTimers.remove(e.id);
            if (timers != null) {
                for (javax.swing.Timer t : timers) {
                    t.stop();
                    activeTimers.remove(t);
                    List<javax.swing.Timer> list = entityTimers.get(e.id);
                    if (list != null) list.remove(t);
                }
            }
            if (idleRunning.remove(e.id)) {
                runningChains.merge(e.id, -1, Integer::sum);
                if (runningChains.getOrDefault(e.id, 0) <= 0) {
                    runningChains.remove(e.id);
                    entityTimers.remove(e.id);
                }
            }
            e.moveVx = e.moveVy = 0;
            e.moveUntilMs = 0;
            e.moveTargetId = null;
            e.moveFollow = false;
        }

        void abortChains(Entity e) {
            List<javax.swing.Timer> timers = entityTimers.remove(e.id);
            if (timers != null) {
                for (javax.swing.Timer t : timers) {
                    t.stop();
                    activeTimers.remove(t);
                }
            }
            idleTimers.remove(e.id);
            idleRunning.remove(e.id);
            runningChains.remove(e.id);
            e.moveVx = e.moveVy = 0;
            e.moveUntilMs = 0;
            e.moveTargetId = null;
            e.moveFollow = false;
        }

        void startChain(Entity e, Block b) {
            startChain(e, b, false);
        }

        void startChain(Entity e, Block b, boolean isIdle) {
            if (!isIdle && idleRunning.contains(e.id)) abortIdle(e);
            runningChains.merge(e.id, 1, Integer::sum);
            if (isIdle) idleRunning.add(e.id);
            idleTriggered.remove(e.id);
            entityTimers.computeIfAbsent(e.id, k -> new ArrayList<>());
            if (executeChain(e, b, isIdle)) {
                finishChain(e, isIdle);
            }
        }

        void finishChain(Entity e, boolean wasIdle) {
            runningChains.merge(e.id, -1, Integer::sum);
            if (wasIdle) {
                idleRunning.remove(e.id);
                idleTimers.remove(e.id);
            }
            if (runningChains.getOrDefault(e.id, 0) <= 0) {
                runningChains.remove(e.id);
                entityTimers.remove(e.id);
                idleTimers.remove(e.id);
            }
        }

        void triggerEvent(Entity e, EventBlock ev) {
            boolean isIdle = ev.type == EventType.ON_IDLE;
            if (!isIdle) {
                idleTriggered.remove(e.id);
            }
            if (ev.next != null) startChain(e, ev.next, isIdle);
            for (Block b : ev.extraNext) {
                startChain(e, b, isIdle);
            }
        }

        boolean collides(Entity a, Entity b) {
            Shape sa = buildShape(a);
            Shape sb = buildShape(b);
            Area inter = new Area(sa);
            inter.intersect(new Area(sb));
            if (!inter.isEmpty()) return true;
            Area edgeA = new Area(new BasicStroke(1f).createStrokedShape(sa));
            edgeA.intersect(new Area(new BasicStroke(1f).createStrokedShape(sb)));
            return !edgeA.isEmpty();
        }

        boolean contains(Entity e, Point p) {
            return buildShape(e).contains(p);
        }

        void applyShape(Entity e, String val) {
            if (val != null && val.startsWith("POLYGON:")) {
                String nm = val.substring(8);
                e.a.shape = ShapeType.POLYGON;
                e.a.shapeName = nm;
                e.a.customPolygon = project.shapes.get(nm);
            } else if (val != null) {
                try {
                    e.a.shape = ShapeType.valueOf(val);
                } catch (Exception ex) {
                    e.a.shape = ShapeType.RECT;
                }
                e.a.shapeName = null;
                e.a.customPolygon = null;
            }
        }

        boolean executeChain(Entity e, Block b, boolean isIdle) {
            Block current = b;
            while (current != null) {
                if (current instanceof ActionBlock) {
                    boolean cont = executeAction(e, (ActionBlock) current, isIdle);
                    if (!cont) return false;
                    current = current.next;
                } else {
                    current = current.next;
                }
            }
            return true;
        }

        boolean executeAction(Entity e, ActionBlock ab, boolean isIdle) {
            switch (ab.type) {
                case MOVE_BY -> {
                    String dir = String.valueOf(ab.args.getOrDefault("dir", "derecha"));
                    double speed = Double.parseDouble(String.valueOf(ab.args.getOrDefault("speed", 100.0)));
                    double secs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 1.0)));
                    if ("lejos".equals(dir)) {
                        String targetId = (String) ab.args.get("targetId");
                        Entity target = targetId == null ? null : new ArrayList<>(stage.entities).stream()
                                .filter(o -> o.id.equals(targetId)).findFirst().orElse(null);
                        if (target == null) {
                            String targetName = (String) ab.args.get("targetName");
                            for (Entity other : new ArrayList<>(stage.entities)) {
                                if (other == e) continue;
                                if (targetName != null && !targetName.equals(other.name)) continue;
                                target = other;
                                break;
                            }
                        }
                        double vx = 0, vy = 0;
                        if (target != null) {
                            double dx = e.t.x - target.t.x;
                            double dy = e.t.y - target.t.y;
                            double dist = Math.hypot(dx, dy);
                            if (dist != 0) {
                                vx = dx / dist * speed;
                                vy = dy / dist * speed;
                            }
                        }
                        e.moveTargetId = null;
                        e.moveFollow = false;
                        e.moveVx = vx;
                        e.moveVy = vy;
                        e.moveUntilMs = System.currentTimeMillis() + (long) (secs * 1000);
                    } else if ("seguir".equals(dir)) {
                        String targetId = (String) ab.args.get("targetId");
                        Entity target = targetId == null ? null : new ArrayList<>(stage.entities).stream()
                                .filter(o -> o.id.equals(targetId)).findFirst().orElse(null);
                        if (target == null) {
                            String targetName = (String) ab.args.get("targetName");
                            for (Entity other : new ArrayList<>(stage.entities)) {
                                if (other == e) continue;
                                if (targetName != null && !targetName.equals(other.name)) continue;
                                target = other;
                                break;
                            }
                        }
                        if (target != null) {
                            e.moveTargetId = target.id;
                            e.moveSpeed = speed;
                            e.moveFollow = true;
                        }
                    } else {
                        double vx = 0, vy = 0;
                        switch (dir) {
                            case "izquierda" -> vx = -speed;
                            case "derecha" -> vx = speed;
                            case "arriba" -> vy = -speed;
                            case "abajo" -> vy = speed;
                        }
                        e.moveTargetId = null;
                        e.moveFollow = false;
                        e.moveVx = vx;
                        e.moveVy = vy;
                        e.moveUntilMs = System.currentTimeMillis() + (long) (secs * 1000);
                    }
                    javax.swing.Timer tm = new javax.swing.Timer((int) (secs * 1000), null);
                    tm.addActionListener(ev -> {
                        activeTimers.remove(tm);
                        List<javax.swing.Timer> list = entityTimers.get(e.id);
                        if (list == null) return;
                        list.remove(tm);
                        if (isIdle) {
                            List<javax.swing.Timer> ilist = idleTimers.get(e.id);
                            if (ilist == null) return;
                            ilist.remove(tm);
                        }
                        if (executeChain(e, ab.next, isIdle)) {
                            finishChain(e, isIdle);
                        }
                    });
                    tm.setRepeats(false);
                    tm.start();
                    activeTimers.add(tm);
                    entityTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
                    if (isIdle) idleTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
                    return false;
                }
                case SET_COLOR -> {
                    Color chosenColor = (Color) ab.args.getOrDefault("color", new Color(0xE74C3C));
                    e.a.setColor(chosenColor);
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
                    if (e.visibleVars == null) e.visibleVars = new HashSet<>();
                    e.visibleVars.add(var);
                }
                case CHANGE_VAR -> {
                    String cvar = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double delta = Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1)));
                    double cur = e.vars.getOrDefault(cvar, 0.0);
                    e.vars.put(cvar, cur + delta);
                    if (e.visibleVars == null) e.visibleVars = new HashSet<>();
                    e.visibleVars.add(cvar);
                }
                case SET_GLOBAL_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double val = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                    project.globalVars.put(var, val);
                    if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>();
                    project.visibleGlobalVars.add(var);
                }
                case CHANGE_GLOBAL_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    double delta = Double.parseDouble(String.valueOf(ab.args.getOrDefault("delta", 1)));
                    double cur = project.globalVars.getOrDefault(var, 0.0);
                    project.globalVars.put(var, cur + delta);
                    if (project.visibleGlobalVars == null) project.visibleGlobalVars = new HashSet<>();
                    project.visibleGlobalVars.add(var);
                }
                case WAIT -> {
                    double secs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 1.0)));
                    javax.swing.Timer tm = new javax.swing.Timer((int) (secs * 1000), null);
                    tm.addActionListener(ev -> {
                        activeTimers.remove(tm);
                        List<javax.swing.Timer> list = entityTimers.get(e.id);
                        if (list == null) return;
                        list.remove(tm);
                        if (isIdle) {
                            List<javax.swing.Timer> ilist = idleTimers.get(e.id);
                            if (ilist == null) return;
                            ilist.remove(tm);
                        }
                        if (executeChain(e, ab.next, isIdle)) {
                            finishChain(e, isIdle);
                        }
                    });
                    tm.setRepeats(false);
                    tm.start();
                    activeTimers.add(tm);
                    entityTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
                    if (isIdle) idleTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
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
                case SET_SHAPE -> {
                    String form = String.valueOf(ab.args.getOrDefault("shape", "RECT"));
                    if ("POLYGON".equals(form)) {
                        String name = (String) ab.args.get("shapeName");
                        applyShape(e, "POLYGON:" + (name != null ? name : ""));
                    } else {
                        applyShape(e, form);
                    }
                }
                case SWITCH_SHAPES -> {
                    java.util.List<String> forms = (java.util.List<String>) ab.args.get("forms");
                    if (forms == null || forms.size() < 2) break;
                    double interval = Double.parseDouble(String.valueOf(ab.args.getOrDefault("interval", 0.2)));
                    double duration = Double.parseDouble(String.valueOf(ab.args.getOrDefault("duration", 1.0)));
                    ShapeType origType = e.a.shape;
                    String origName = e.a.shapeName;
                    Polygon origPoly = e.a.customPolygon;
                    applyShape(e, forms.get(0));
                    final int[] idx = {1};
                    long start = System.currentTimeMillis();
                    javax.swing.Timer tm = new javax.swing.Timer((int) (interval * 1000), null);
                    tm.addActionListener(ev -> {
                        if (System.currentTimeMillis() - start >= duration * 1000) {
                            tm.stop();
                            activeTimers.remove(tm);
                            List<javax.swing.Timer> list = entityTimers.get(e.id);
                            if (list != null) list.remove(tm);
                            if (isIdle) {
                                List<javax.swing.Timer> ilist = idleTimers.get(e.id);
                                if (ilist != null) ilist.remove(tm);
                            }
                            e.a.shape = origType;
                            e.a.shapeName = origName;
                            e.a.customPolygon = origPoly;
                            if (executeChain(e, ab.next, isIdle)) {
                                finishChain(e, isIdle);
                            }
                            return;
                        }
                        applyShape(e, forms.get(idx[0] % forms.size()));
                        idx[0]++;
                        stage.repaint();
                    });
                    tm.setRepeats(true);
                    tm.start();
                    activeTimers.add(tm);
                    entityTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
                    if (isIdle) idleTimers.computeIfAbsent(e.id, k -> new ArrayList<>()).add(tm);
                    return false;
                }
                case RANDOM -> {
                    if (!ab.extraNext.isEmpty()) {
                        Block chosen = ab.extraNext.get(new Random().nextInt(ab.extraNext.size()));
                        executeChain(e, chosen, isIdle);
                    }
                }
                case IF_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    String op = String.valueOf(ab.args.getOrDefault("op", ">"));
                    double target = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                    double cur = e.vars.getOrDefault(var, 0.0);
                    boolean cond = op.equals(">") ? cur > target : cur < target;
                    if (cond && !ab.extraNext.isEmpty()) {
                        executeChain(e, ab.extraNext.get(0), isIdle);
                    }
                }
                case IF_GLOBAL_VAR -> {
                    String var = String.valueOf(ab.args.getOrDefault("var", "var"));
                    String op = String.valueOf(ab.args.getOrDefault("op", ">"));
                    double target = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                    double cur = project.globalVars.getOrDefault(var, 0.0);
                    boolean cond = op.equals(">") ? cur > target : cur < target;
                    if (cond && !ab.extraNext.isEmpty()) {
                        executeChain(e, ab.extraNext.get(0), isIdle);
                    }
                }
                case IF_RANDOM_CHANCE -> {
                    double prob = Double.parseDouble(String.valueOf(ab.args.getOrDefault("prob", 0.5)));
                    if (Math.random() < prob && !ab.extraNext.isEmpty()) {
                        executeChain(e, ab.extraNext.get(0), isIdle);
                    }
                }
                case MOVE_TO_ENTITY -> {
                    String targetId = (String) ab.args.get("targetId");
                    Entity target = targetId == null ? null : new ArrayList<>(stage.entities).stream()
                            .filter(o -> o.id.equals(targetId)).findFirst().orElse(null);
                    if (target == null) {
                        String targetName = (String) ab.args.get("targetName");
                        for (Entity other : new ArrayList<>(stage.entities)) {
                            if (other == e) continue;
                            if (targetName != null && !targetName.equals(other.name)) continue;
                            target = other;
                            break;
                        }
                    }
                    if (target != null) {
                        e.moveTargetId = target.id;
                        e.moveSpeed = Double.parseDouble(String.valueOf(ab.args.getOrDefault("speed", 100.0)));
                        e.moveFollow = false;
                    }
                }
                case SPAWN_ENTITY -> {
                    String tplId = (String) ab.args.get("templateId");
                    Entity tpl = tplId != null ? project.getById(tplId) : null;
                    if (tpl == null) tpl = e;
                    // Al clonar entidades durante el juego siempre generamos un nuevo id
                    // para evitar conflictos con la entidad original o con otras copias.
                    Entity clone = stage.cloneEntity(tpl, false);

                    String mode = String.valueOf(ab.args.getOrDefault("mode", "MAP"));
                    if ("MAP".equals(mode)) {
                        double x = ((Number) ab.args.getOrDefault("x", clone.t.x)).doubleValue();
                        double y = ((Number) ab.args.getOrDefault("y", clone.t.y)).doubleValue();
                        clone.t.x = x;
                        clone.t.y = y;
                    } else if ("REL".equals(mode)) {
                        String dir = String.valueOf(ab.args.getOrDefault("dir", "abajo"));
                        double dist = ((Number) ab.args.getOrDefault("distance", 0.0)).doubleValue();
                        switch (dir.toLowerCase(Locale.ROOT)) {
                            case "arriba" -> {
                                clone.t.x = e.t.x + (e.a.width - clone.a.width) / 2;
                                clone.t.y = e.t.y - dist - clone.a.height;
                            }
                            case "izquierda" -> {
                                clone.t.x = e.t.x - dist - clone.a.width;
                                clone.t.y = e.t.y + (e.a.height - clone.a.height) / 2;
                            }
                            case "derecha" -> {
                                clone.t.x = e.t.x + e.a.width + dist;
                                clone.t.y = e.t.y + (e.a.height - clone.a.height) / 2;
                            }
                            default -> {
                                clone.t.x = e.t.x + (e.a.width - clone.a.width) / 2;
                                clone.t.y = e.t.y + e.a.height + dist;
                            }
                        }
                    } else {
                        double maxX = Math.max(0, stage.stageWidth() - clone.a.width);
                        double maxY = Math.max(0, stage.stageHeight() - clone.a.height);
                        clone.t.x = Math.random() * maxX;
                        clone.t.y = Math.random() * maxY;
                    }
                    stage.clampEntity(clone);

                    List<EventBlock> scripts = stage.cloneScripts(tpl.id);
                    pendingOps.add(() -> {
                        stage.entities.add(clone);
                        project.scriptsByEntity.put(clone.id, scripts);
                        List<EventBlock> roots = project.scriptsByEntity.getOrDefault(clone.id, Collections.emptyList());
                        long now = System.currentTimeMillis();
                        for (EventBlock ev : roots) {
                            switch (ev.type) {
                                case ON_APPEAR -> triggerEvent(clone, ev);
                                case ON_TICK -> tickLastFire
                                        .computeIfAbsent(clone.id, k -> new HashMap<>())
                                        .put(ev, now);
                                case ON_VAR_CHANGE -> {
                                    String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                                    double cur = clone.vars.getOrDefault(var, 0.0);
                                    varLast.computeIfAbsent(clone.id, k -> new HashMap<>()).put(ev, cur);
                                }
                                case ON_GLOBAL_VAR_CHANGE -> {
                                    String var = String.valueOf(ev.args.getOrDefault("var", "var"));
                                    double cur = project.globalVars.getOrDefault(var, 0.0);
                                    globalVarLast.put(ev, cur);
                                }
                                default -> {}
                            }
                        }
                    });
                }
                case DELETE_ENTITY -> {
                    String targetId = (String) ab.args.get("targetId");
                    Entity target = targetId == null ? e : new ArrayList<>(stage.entities).stream()
                            .filter(en -> en.id.equals(targetId)).findFirst().orElse(null);
                    if (target != null) {
                        pendingOps.add(() -> {
                            stage.entities.remove(target);
                            project.scriptsByEntity.remove(target.id);
                        });
                    }
                    if (target == e) {
                        return false;
                    }
                }
                case NEXT_SCENE -> stage.nextScenario();
                case PREV_SCENE -> stage.prevScenario();
                case GOTO_SCENE -> {
                    int idx = Integer.parseInt(String.valueOf(ab.args.getOrDefault("index", 1))) - 1;
                    stage.gotoScenarioIndex(idx);
                }
                case STOP -> {
                    e.moveTargetId = null;
                    e.moveVx = e.moveVy = 0;
                    e.moveUntilMs = 0;
                    e.sayText = null;
                }
            }
            return true;
        }

        void handleMouseEvent(MouseEvent e) {
            Point p = e.getPoint();
            for (Entity en : new ArrayList<>(stage.entities)) {
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
        if (!PROJECTS_DIR.exists()) {
            PROJECTS_DIR.mkdirs();
        }
        if (!BACKGROUNDS_DIR.exists()) {
            BACKGROUNDS_DIR.mkdirs();
        }
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

        // Sistema de tutorial
        final TutorialSystem tutorial = new TutorialSystem();
        TutorialSystem.Mission currentMission = tutorial.obtenerMisionActual();
        final TutorialPanel tutorialPanel;
        final JDialog tutorialDialog;

        MainFrame() {
            super("Scratch MVP (Java Swing) — Editor y Escenario");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1200, 760);
            setLocationRelativeTo(null);

            // Panels
            stagePanel = new StagePanel(project, keysDown);
            tutorialPanel = new TutorialPanel(tutorial);
            tutorialDialog = new JDialog(this, "Tutorial", false);
            tutorialDialog.setContentPane(tutorialPanel);
            tutorialDialog.pack();
            tutorialDialog.setSize(400, 300);
            tutorialDialog.setLocationRelativeTo(this);

            editorPanel = new EditorPanel(project, () -> {
                // al pulsar "Ir al Escenario"
                stagePanel.loadCurrentScenario();
                cards.show(root, "stage");
                stagePanel.requestFocusInWindow();
                stagePanel.repaint();
            }, () -> tutorialDialog.setVisible(true));

            // Runtime
            runtime = new GameRuntime(project, stagePanel, keysDown);
            stagePanel.setRuntimeControls(
                    () -> {
                        runtime.play();
                        tutorial.actualizar(project);
                        TutorialSystem.Mission m = tutorial.obtenerMisionActual();
                        if (m != currentMission) {
                            int completada = tutorial.getIndiceActual();
                            String titulo = "Tutorial " + completada + " completado";
                            String msg = "Has completado el Tutorial " + completada + ".";
                            if (m != null) {
                                String cont = "Continuar con tutorial " + (completada + 1);
                                JOptionPane.showOptionDialog(stagePanel, msg, titulo,
                                        JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                                        null, new Object[]{cont}, cont);
                                JOptionPane.showMessageDialog(stagePanel, m.instrucciones, m.nombre,
                                        JOptionPane.INFORMATION_MESSAGE);
                            } else {
                                JOptionPane.showMessageDialog(stagePanel,
                                        msg + "\n¡Has completado todas las misiones!",
                                        titulo, JOptionPane.INFORMATION_MESSAGE);
                            }
                            currentMission = m;
                        }
                        tutorialPanel.refresh();
                    },
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
            loadLastProject();
            tutorial.actualizar(project);
            tutorialPanel.refresh();
        }

        void loadLastProject() {
            File[] files = PROJECTS_DIR.listFiles();
            if (files == null || files.length == 0) return;
            File last = null;
            for (File f : files) {
                if (f.isFile()) {
                    if (last == null || f.lastModified() > last.lastModified()) last = f;
                }
            }
            if (last != null) {
                loadProject(project, last);
                stagePanel.size.setSize(project.canvas);
                project.canvas = stagePanel.size;
                if (stagePanel.widthSpin != null) stagePanel.widthSpin.setValue(stagePanel.size.width);
                if (stagePanel.heightSpin != null) stagePanel.heightSpin.setValue(stagePanel.size.height);
                if (stagePanel.canvasView != null) {
                    stagePanel.canvasView.setPreferredSize(stagePanel.size);
                    stagePanel.canvasView.revalidate();
                }
                editorPanel.refreshAll();
                stagePanel.repaint();
            }
        }
    }

    // ====== PANEL EDITOR ======
    static class EditorPanel extends JPanel {
        final Project project;
        final Runnable goStage;
        final Runnable showTutorial;

        final EntityListPanel entityListPanel;
        final GlobalVarPanel globalVarPanel;
        final InspectorPanel inspectorPanel;
        final PalettePanel palettePanel;
        final ScriptCanvasPanel scriptCanvas;

        EditorPanel(Project project, Runnable goStage, Runnable showTutorial) {
            super(new BorderLayout());
            this.project = project;
            this.goStage = goStage;
            this.showTutorial = showTutorial;

            // Top bar
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton btnNewEntity = new JButton("Nueva Entidad");
            JButton btnDelEntity = new JButton("Eliminar Entidad");
            JButton btnToStage   = new JButton("Ir al Escenario ▶");
            JButton btnCopyEntity = new JButton("Copiar Entidad");
            JButton btnRenameEntity = new JButton("Renombrar Entidad");
            JButton btnSaveProj  = new JButton("Guardar");
            JButton btnLoadProj  = new JButton("Cargar");
            JButton btnTutorial  = new JButton("Tutorial");
            bar.add(btnNewEntity);
            bar.add(btnCopyEntity);
            bar.add(btnRenameEntity);
            bar.add(btnDelEntity);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(btnToStage);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(btnSaveProj);
            bar.add(btnLoadProj);
            bar.add(btnTutorial);
            
            add(bar, BorderLayout.NORTH);

            btnTutorial.addActionListener(e -> showTutorial.run());

            // Left: Paleta y lista entidades
            JPanel left = new JPanel(new BorderLayout());
            left.setPreferredSize(new Dimension(280, 100));
            palettePanel = new PalettePanel();
            entityListPanel = new EntityListPanel(project);
            globalVarPanel = new GlobalVarPanel(project);
            left.add(palettePanel, BorderLayout.CENTER);
            JPanel bottom = new JPanel();
            bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
            bottom.add(entityListPanel);
            bottom.add(globalVarPanel);
            left.add(bottom, BorderLayout.SOUTH);

            // Center: Lienzo de scripts
            scriptCanvas = new ScriptCanvasPanel(project, entityListPanel);
            palettePanel.setDropTargetCanvas(scriptCanvas);
            JScrollPane canvasScroll = new JScrollPane(scriptCanvas,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
            canvasScroll.getVerticalScrollBar().addAdjustmentListener(ev -> {
                JScrollBar sb = (JScrollBar) ev.getAdjustable();
                if (!ev.getValueIsAdjusting() &&
                        sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum()) {
                    Dimension cur = scriptCanvas.getPreferredSize();
                    scriptCanvas.setPreferredSize(new Dimension(cur.width, cur.height + 400));
                    scriptCanvas.revalidate();
                }
            });

            // Right: Inspector
            inspectorPanel = new InspectorPanel(project, entityListPanel, scriptCanvas);

            add(left, BorderLayout.WEST);
            add(canvasScroll, BorderLayout.CENTER);
            add(inspectorPanel, BorderLayout.EAST);

            // Listeners
            btnNewEntity.addActionListener(e -> {
                String name = JOptionPane.showInputDialog(this, "Nombre de la entidad", "Entidad " + (project.entities.size() + 1));
                if (name != null) {
                    name = name.trim();
                    if (name.isEmpty()) name = "Entidad " + (project.entities.size() + 1);
                    Entity en = new Entity();
                    en.name = name;
                    project.entities.add(en);
                    project.scriptsByEntity.put(en.id, new ArrayList<>());
                    entityListPanel.refresh();
                    entityListPanel.select(en);
                    scriptCanvas.repaint();
                }
            });

            btnCopyEntity.addActionListener(e -> {
                Entity sel = entityListPanel.getSelected();
                if (sel != null) {
                    Entity copy = cloneEntity(sel);
                    copy.name = sel.name + " copia";
                    project.entities.add(copy);
                    project.scriptsByEntity.put(copy.id, cloneScripts(sel.id));
                    entityListPanel.refresh();
                    entityListPanel.select(copy);
                    scriptCanvas.repaint();
                }
            });

            btnRenameEntity.addActionListener(e -> {
                Entity sel = entityListPanel.getSelected();
                if (sel != null) {
                    String newName = JOptionPane.showInputDialog(this, "Nuevo nombre de la entidad", sel.name);
                    if (newName != null) {
                        newName = newName.trim();
                        if (!newName.isEmpty()) {
                            sel.name = newName;
                            // Propagar cambio de nombre a entidades en escenarios basadas en esta plantilla
                            for (Scenario sc : project.scenarios) {
                                for (Entity en : sc.entities) {
                                    if (sel.id.equals(en.templateId)) en.name = newName;
                                }
                            }
                            entityListPanel.refresh();
                            scriptCanvas.repaint();
                        }
                    }
                }
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

            btnSaveProj.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(PROJECTS_DIR);
                if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    saveProject(project, fc.getSelectedFile());
                }
            });
            btnLoadProj.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(PROJECTS_DIR);
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    loadProject(project, fc.getSelectedFile());
                    refreshAll();
                }
            });

            btnToStage.addActionListener(e -> goStage.run());
        }

        void refreshAll() {
            entityListPanel.refresh();
            globalVarPanel.refresh();
            scriptCanvas.repaint();
            inspectorPanel.refresh();
        }

        Entity cloneEntity(Entity src) {
            Entity c = new Entity();
            Set<String> used = new HashSet<>();
            for (Entity en : project.entities) used.add(en.id);
            while (used.contains(c.id)) c.id = UUID.randomUUID().toString();
            // como es una nueva plantilla, su templateId coincide con su propio id
            c.templateId = c.id;
            c.name = src.name;
            c.t.x = src.t.x;
            c.t.y = src.t.y;
            c.t.rot = src.t.rot;
            c.a.shape = src.a.shape;
            c.a.color = src.a.color;
            c.a.width = src.a.width;
            c.a.height = src.a.height;
            c.a.opacity = src.a.opacity;
            c.a.shapeName = src.a.shapeName;
            for (Map.Entry<String, Color> en : src.a.colorByShape.entrySet()) {
                c.a.colorByShape.put(en.getKey(), en.getValue());
            }
            for (Map.Entry<String, BufferedImage> en : src.a.paintImages.entrySet()) {
                c.a.paintImages.put(en.getKey(), copyImage(en.getValue()));
            }
            if (src.a.customPolygon != null) {
                c.a.customPolygon = new Polygon(src.a.customPolygon.xpoints, src.a.customPolygon.ypoints, src.a.customPolygon.npoints);
            }
            c.vars.putAll(src.vars);
            if (src.visibleVars != null) {
                c.visibleVars.addAll(src.visibleVars);
            } else {
                c.visibleVars.addAll(src.vars.keySet());
            }
            return c;
        }

        List<EventBlock> cloneScripts(String srcId) {
            List<EventBlock> roots = project.scriptsByEntity.getOrDefault(srcId, Collections.emptyList());
            List<EventBlock> out = new ArrayList<>();
            for (EventBlock ev : roots) {
                out.add((EventBlock) cloneBlock(ev));
            }
            return out;
        }

        Block cloneBlock(Block b) {
            if (b == null) return null;
            Block copy;
            if (b instanceof EventBlock ev) {
                EventBlock ev2 = new EventBlock(ev.type);
                ev2.args.putAll(ev.args);
                for (Block extra : ev.extraNext) ev2.extraNext.add(cloneBlock(extra));
                copy = ev2;
            } else if (b instanceof ActionBlock ab) {
                ActionBlock ab2 = new ActionBlock(ab.type);
                for (Map.Entry<String, Object> en : ab.args.entrySet()) {
                    Object v = en.getValue();
                    if (v instanceof java.util.List<?> list) {
                        ab2.args.put(en.getKey(), new ArrayList<>(list));
                    } else {
                        ab2.args.put(en.getKey(), v);
                    }
                }
                for (Block extra : ab.extraNext) ab2.extraNext.add(cloneBlock(extra));
                copy = ab2;
            } else {
                return null;
            }
            copy.x = b.x;
            copy.y = b.y;
            copy.next = cloneBlock(b.next);
            return copy;
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
        JButton paintBtn;
        JSpinner wSpin, hSpin;
        PreviewPanel preview;
        DefaultListModel<String> varModel;
        JList<String> varList;
        JSpinner varValue;
        JCheckBox varVisible;
        JButton btnAddVar, btnDelVar;
        boolean updating = false;

        InspectorPanel(Project p, EntityListPanel lp, ScriptCanvasPanel c) {
            super();
            this.project = p; this.listPanel = lp; this.canvas = c;
            setPreferredSize(new Dimension(260, 100));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(10,10,10,10));

            add(new JLabel("Inspector de Entidad"));
            add(Box.createVerticalStrut(8));
            preview = new PreviewPanel();
            add(preview);
            add(Box.createVerticalStrut(8));
            shapeBox = new JComboBox<>();
            paintBtn = new JButton("Pintado...");
            wSpin = new JSpinner(new SpinnerNumberModel(60, 10, 500, 5));
            hSpin = new JSpinner(new SpinnerNumberModel(60, 10, 500, 5));

            add(labeled("Forma", shapeBox));
            add(Box.createVerticalStrut(6));
            add(labeled("Ancho", wSpin));
            add(labeled("Alto/Radio", hSpin));
            add(Box.createVerticalStrut(6));
            add(paintBtn);
            add(Box.createVerticalStrut(10));

            add(new JLabel("Variables"));
            varModel = new DefaultListModel<>();
            varList = new JList<>(varModel);
            varList.setVisibleRowCount(5);
            add(new JScrollPane(varList));
            varValue = new JSpinner(new SpinnerNumberModel(0.0, -1e9, 1e9, 1.0));
            add(labeled("Valor", varValue));
            varVisible = new JCheckBox();
            add(labeled("Visible", varVisible));
            JPanel vb = new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
            btnAddVar = new JButton("Agregar");
            btnDelVar = new JButton("Eliminar");
            vb.add(btnAddVar); vb.add(btnDelVar);
            add(vb);

            add(Box.createVerticalGlue());

            updateShapeBoxModel();

            // Listeners
            shapeBox.addActionListener(e -> {
                if (updating) return;
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    int idx = shapeBox.getSelectedIndex();
                    int baseCount = 6;
                    int customCount = project.shapes.size();
                    if (idx < baseCount) {
                        sel.a.shapeName = null;
                        switch (idx) {
                            case 0 -> sel.a.shape = ShapeType.RECT;
                            case 1 -> sel.a.shape = ShapeType.CIRCLE;
                            case 2 -> sel.a.shape = ShapeType.TRIANGLE;
                            case 3 -> sel.a.shape = ShapeType.PENTAGON;
                            case 4 -> sel.a.shape = ShapeType.HEXAGON;
                            case 5 -> sel.a.shape = ShapeType.STAR;
                        }
                    } else if (idx == baseCount + customCount) {
                        Map.Entry<String, Polygon> res = promptPolygon();
                        if (res != null) {
                            String name = res.getKey();
                            Polygon poly = res.getValue();
                            project.shapes.put(name, poly);
                            sel.a.shape = ShapeType.POLYGON;
                            sel.a.customPolygon = new Polygon(poly.xpoints, poly.ypoints, poly.npoints);
                            sel.a.shapeName = name;
                            Rectangle b = poly.getBounds();
                            sel.a.width = b.width;
                            sel.a.height = b.height;
                            updateShapeBoxModel();
                            shapeBox.setSelectedItem(name);
                        } else {
                            refresh();
                            return;
                        }
                    } else {
                        String name = (String) shapeBox.getItemAt(idx);
                        Polygon poly = project.shapes.get(name);
                        if (poly != null) {
                            sel.a.shape = ShapeType.POLYGON;
                            sel.a.customPolygon = new Polygon(poly.xpoints, poly.ypoints, poly.npoints);
                            sel.a.shapeName = name;
                            Rectangle b = poly.getBounds();
                            sel.a.width = b.width;
                            sel.a.height = b.height;
                        }
                    }
                    canvas.repaint();
                    propagateToScenarios(sel);
                    refresh();
                }
            });

            paintBtn.addActionListener(e -> {
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    new PaintDialog(sel).setVisible(true);
                }
            });

            ChangeListener cl = e -> {
                if (updating) return;
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    double newW = ((Number) wSpin.getValue()).doubleValue();
                    double newH = ((Number) hSpin.getValue()).doubleValue();

                    if (sel.a.shape == ShapeType.POLYGON && sel.a.customPolygon != null) {
                        double oldW = sel.a.width;
                        double oldH = sel.a.height;
                        double sx = newW / (oldW == 0 ? 1 : oldW);
                        double sy = newH / (oldH == 0 ? 1 : oldH);
                        for (int i = 0; i < sel.a.customPolygon.npoints; i++) {
                            sel.a.customPolygon.xpoints[i] = (int) Math.round(sel.a.customPolygon.xpoints[i] * sx);
                            sel.a.customPolygon.ypoints[i] = (int) Math.round(sel.a.customPolygon.ypoints[i] * sy);
                        }
                        sel.a.customPolygon.invalidate();
                        sel.t.x += (oldW - newW) / 2.0;
                        sel.t.y += (oldH - newH) / 2.0;
                    }

                    sel.a.width = newW;
                    sel.a.height = newH;
                    canvas.repaint();
                    propagateToScenarios(sel);
                    preview.repaint();
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
                varVisible.setEnabled(ok);
                if (ok) {
                    varValue.setValue(sel.vars.getOrDefault(name, 0.0));
                    if (sel.visibleVars == null) sel.visibleVars = new HashSet<>(sel.vars.keySet());
                    varVisible.setSelected(sel.visibleVars.contains(name));
                }
            });
            varValue.addChangeListener(ev -> {
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                if (sel != null && name != null) {
                    sel.vars.put(name, ((Number)varValue.getValue()).doubleValue());
                    canvas.repaint();
                    propagateToScenarios(sel);
                }
            });
            varVisible.addActionListener(ev -> {
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                if (sel != null && name != null) {
                    if (sel.visibleVars == null) sel.visibleVars = new HashSet<>(sel.vars.keySet());
                    if (varVisible.isSelected()) sel.visibleVars.add(name);
                    else sel.visibleVars.remove(name);
                    canvas.repaint();
                    propagateToScenarios(sel);
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
                            if (sel.visibleVars == null) sel.visibleVars = new HashSet<>();
                            sel.visibleVars.add(name);
                            refresh();
                            propagateToScenarios(sel);
                        }
                    }
                }
            });
            btnDelVar.addActionListener(ev -> {
                Entity sel = listPanel.getSelected();
                String name = varList.getSelectedValue();
                if (sel != null && name != null) {
                    sel.vars.remove(name);
                    if (sel.visibleVars != null) sel.visibleVars.remove(name);
                    refresh();
                    propagateToScenarios(sel);
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

        class PreviewPanel extends JPanel {
            PreviewPanel() { setPreferredSize(new Dimension(120,120)); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Entity sel = listPanel.getSelected();
                if (sel != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    double scale = Math.min((getWidth()-10)/sel.a.width, (getHeight()-10)/sel.a.height);
                    Transform t = new Transform();
                    t.x = getWidth()/2 - sel.a.width/2;
                    t.y = getHeight()/2 - sel.a.height/2;
                    t.scaleX = sel.t.scaleX * scale;
                    t.scaleY = sel.t.scaleY * scale;
                    t.rot = sel.t.rot;
                    Entity tmp = new Entity();
                    tmp.a = sel.a;
                    tmp.t = t;
                    Composite old = g2.getComposite();
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) sel.a.opacity));
                    Shape s = buildShape(tmp);
                    g2.setColor(sel.a.getColor());
                    g2.fill(s);
                    BufferedImage img = sel.a.getPaintImage();
                    if (img != null) {
                        Graphics2D gImg = (Graphics2D) g2.create();
                        gImg.setClip(s);
                        AffineTransform at = new AffineTransform();
                        at.translate(t.x + sel.a.width/2, t.y + sel.a.height/2);
                        at.rotate(Math.toRadians(t.rot));
                        at.scale(t.scaleX, t.scaleY);
                        at.translate(-sel.a.width/2, -sel.a.height/2);
                        gImg.drawImage(img, at, null);
                        gImg.dispose();
                    }
                    g2.setColor(Color.DARK_GRAY);
                    g2.draw(s);
                    g2.setComposite(old);
                    g2.dispose();
                }
            }
        }

        class PaintDialog extends JDialog {
            final Entity entity;
            PaintDialog(Entity e) {
                super((Frame)SwingUtilities.getWindowAncestor(InspectorPanel.this), "Pintado", false);
                this.entity = e;
                PaintCanvas pc = new PaintCanvas(e);
                JSpinner sizeSpin = new JSpinner(new SpinnerNumberModel(10,1,100,1));
                JButton colorBtn = new JButton("Color...");
                colorBtn.addActionListener(ev -> {
                    Color c = JColorChooser.showDialog(this, "Color de pincel", pc.brushColor);
                    if (c != null) pc.brushColor = c;
                });
                sizeSpin.addChangeListener(ev -> pc.brushSize = ((Number)sizeSpin.getValue()).intValue());
                JPanel top = new JPanel();
                top.add(new JLabel("Grosor:"));
                top.add(sizeSpin);
                top.add(colorBtn);
                add(top, BorderLayout.NORTH);
                add(pc, BorderLayout.CENTER);
                pack();
                setLocationRelativeTo(InspectorPanel.this);
            }
        }

        class PaintCanvas extends JPanel implements MouseListener, MouseMotionListener {
            final Entity entity;
            Color brushColor = Color.BLACK;
            int brushSize = 10;
            PaintCanvas(Entity e) {
                this.entity = e;
                setPreferredSize(new Dimension((int)e.a.width, (int)e.a.height));
                addMouseListener(this);
                addMouseMotionListener(this);
            }
            Shape getShape() {
                switch (entity.a.shape) {
                    case RECT -> {
                        return new Rectangle2D.Double(0, 0, entity.a.width, entity.a.height);
                    }
                    case CIRCLE -> {
                        return new Ellipse2D.Double(0, 0, entity.a.width, entity.a.width);
                    }
                    case TRIANGLE -> {
                        Path2D t = new Path2D.Double();
                        t.moveTo(entity.a.width/2, 0);
                        t.lineTo(0, entity.a.height);
                        t.lineTo(entity.a.width, entity.a.height);
                        t.closePath();
                        return t;
                    }
                    case PENTAGON -> {
                        Shape s = makeRegularPolygon(5, entity.a.width, entity.a.height);
                        return AffineTransform.getTranslateInstance(entity.a.width/2, entity.a.height/2).createTransformedShape(s);
                    }
                    case HEXAGON -> {
                        Shape s = makeRegularPolygon(6, entity.a.width, entity.a.height);
                        return AffineTransform.getTranslateInstance(entity.a.width/2, entity.a.height/2).createTransformedShape(s);
                    }
                    case STAR -> {
                        Shape s = makeStar(5, entity.a.width, entity.a.height);
                        return AffineTransform.getTranslateInstance(entity.a.width/2, entity.a.height/2).createTransformedShape(s);
                    }
                    case POLYGON -> {
                        return entity.a.customPolygon != null ? entity.a.customPolygon : new Rectangle2D.Double(0,0,entity.a.width,entity.a.height);
                    }
                    default -> {
                        return new Rectangle2D.Double(0, 0, entity.a.width, entity.a.height);
                    }
                }
            }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                Shape s = getShape();
                g2.setColor(entity.a.getColor());
                g2.fill(s);
                BufferedImage img = entity.a.getPaintImage();
                if (img != null) {
                    g2.setClip(s);
                    g2.drawImage(img,0,0,null);
                    g2.setClip(null);
                }
                g2.setColor(Color.DARK_GRAY);
                g2.draw(s);
                g2.dispose();
            }
            void paintAt(int x, int y) {
                BufferedImage img = entity.a.getPaintImage();
                if (img == null || img.getWidth() != (int)entity.a.width || img.getHeight() != (int)entity.a.height) {
                    img = new BufferedImage((int)entity.a.width, (int)entity.a.height, BufferedImage.TYPE_INT_ARGB);
                    entity.a.setPaintImage(img);
                }
                Shape s = getShape();
                Graphics2D g2 = img.createGraphics();
                g2.setClip(s);
                g2.setColor(brushColor);
                g2.fillOval(x - brushSize/2, y - brushSize/2, brushSize, brushSize);
                g2.dispose();
                repaint();
                InspectorPanel.this.canvas.repaint();
                InspectorPanel.this.preview.repaint();
                InspectorPanel.this.propagateToScenarios(entity);
            }
            @Override public void mouseDragged(MouseEvent e) { paintAt(e.getX(), e.getY()); }
            @Override public void mousePressed(MouseEvent e) { paintAt(e.getX(), e.getY()); }
            @Override public void mouseClicked(MouseEvent e) {}
            @Override public void mouseReleased(MouseEvent e) {}
            @Override public void mouseEntered(MouseEvent e) {}
            @Override public void mouseExited(MouseEvent e) {}
            @Override public void mouseMoved(MouseEvent e) {}
        }

        void updateShapeBoxModel() {
            updating = true;
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            model.addElement("Rectángulo");
            model.addElement("Círculo");
            model.addElement("Triángulo");
            model.addElement("Pentágono");
            model.addElement("Hexágono");
            model.addElement("Estrella");
            for (String n : project.shapes.keySet()) model.addElement(n);
            model.addElement("Crear forma personalizada...");
            shapeBox.setModel(model);
            updating = false;
        }

        void refresh() {
            updateShapeBoxModel();
            Entity sel = listPanel.getSelected();
            boolean en = sel != null;
            shapeBox.setEnabled(en); paintBtn.setEnabled(en);
            // permitir redimensionar incluso en formas personalizadas
            wSpin.setEnabled(en); hSpin.setEnabled(en);
            btnAddVar.setEnabled(en); varList.setEnabled(en);
            if (sel != null) {
                updating = true;
                if (sel.visibleVars == null) sel.visibleVars = new HashSet<>(sel.vars.keySet());
                if (sel.a.shape == ShapeType.POLYGON) {
                    if (sel.a.shapeName != null && project.shapes.containsKey(sel.a.shapeName)) {
                        shapeBox.setSelectedItem(sel.a.shapeName);
                    } else {
                        shapeBox.setSelectedIndex(shapeBox.getItemCount()-1);
                    }
                } else {
                    int idx = switch(sel.a.shape){
                        case RECT -> 0;
                        case CIRCLE -> 1;
                        case TRIANGLE -> 2;
                        case PENTAGON -> 3;
                        case HEXAGON -> 4;
                        case STAR -> 5;
                        case POLYGON -> 0;
                    };
                    shapeBox.setSelectedIndex(idx);
                }
                wSpin.setValue((int)sel.a.width);
                hSpin.setValue((int)sel.a.height);
                updating = false;
                varModel.clear();
                for (String v : sel.vars.keySet()) varModel.addElement(v);
            } else {
                varModel.clear();
            }
            String name = varList.getSelectedValue();
            boolean vs = en && name != null;
            varValue.setEnabled(vs);
            btnDelVar.setEnabled(vs);
            varVisible.setEnabled(vs);
            if (vs) {
                varValue.setValue(sel.vars.getOrDefault(name, 0.0));
                varVisible.setSelected(sel.visibleVars.contains(name));
            }
            preview.repaint();
        }

        void propagateToScenarios(Entity tpl) {
            for (Scenario sc : project.scenarios) {
                for (Entity en : sc.entities) {
                    if (tpl.id.equals(en.templateId)) {
                        en.a.shape = tpl.a.shape;
                        en.a.color = tpl.a.color;
                        en.a.width = tpl.a.width;
                        en.a.height = tpl.a.height;
                        en.a.opacity = tpl.a.opacity;
                        en.a.shapeName = tpl.a.shapeName;
                        en.a.colorByShape.clear();
                        en.a.colorByShape.putAll(tpl.a.colorByShape);
                        en.a.paintImages.clear();
                        for (Map.Entry<String, BufferedImage> enImg : tpl.a.paintImages.entrySet()) {
                            en.a.paintImages.put(enImg.getKey(), copyImage(enImg.getValue()));
                        }
                        if (tpl.a.customPolygon != null) {
                            en.a.customPolygon = new Polygon(
                                    tpl.a.customPolygon.xpoints,
                                    tpl.a.customPolygon.ypoints,
                                    tpl.a.customPolygon.npoints);
                        } else {
                            en.a.customPolygon = null;
                        }
                        en.vars.clear();
                        en.vars.putAll(tpl.vars);
                        en.visibleVars.clear();
                        if (tpl.visibleVars != null) {
                            en.visibleVars.addAll(tpl.visibleVars);
                        } else {
                            en.visibleVars.addAll(tpl.vars.keySet());
                        }
                    }
                }
            }
        }

        Map.Entry<String, Polygon> promptPolygon() {
            class DrawPanel extends JPanel implements MouseListener, MouseMotionListener, KeyListener {
                final java.util.List<Point> pts = new ArrayList<>();
                int dragIdx = -1;
                int hoverIdx = -1;
                final int HIT = 6;
                Polygon onion = null;
                DrawPanel() {
                    setPreferredSize(new Dimension(300,300));
                    setBackground(Color.WHITE);
                    addMouseListener(this);
                    addMouseMotionListener(this);
                    addKeyListener(this);
                    setFocusable(true);
                }
                void setOnion(Polygon p) { onion = p; repaint(); }
                int findIndex(Point p) {
                    for (int i = 0; i < pts.size(); i++) {
                        if (p.distance(pts.get(i)) <= HIT) return i;
                    }
                    return -1;
                }
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;
                    if (onion != null) {
                        Rectangle b = onion.getBounds();
                        AffineTransform at = new AffineTransform();
                        at.translate(getWidth() / 2.0 - b.getCenterX(),
                                     getHeight() / 2.0 - b.getCenterY());
                        Shape s = at.createTransformedShape(onion);
                        g2.setColor(new Color(0,0,0,50));
                        g2.fill(s);
                        g2.setColor(new Color(0,0,0,100));
                        g2.draw(s);
                    }
                    for (int i=0;i<pts.size();i++) {
                        Point p = pts.get(i);
                        g2.setColor(i == hoverIdx ? Color.RED : Color.BLACK);
                        g2.fillOval(p.x-3,p.y-3,6,6);
                        if (i>0) {
                            Point q = pts.get(i-1);
                            g2.drawLine(q.x,q.y,p.x,p.y);
                        }
                    }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        int idx = findIndex(e.getPoint());
                        if (idx == -1) pts.add(e.getPoint());
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        int idx = findIndex(e.getPoint());
                        if (idx != -1) pts.remove(idx);
                        else if (!pts.isEmpty()) pts.remove(pts.size()-1);
                    }
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dragIdx = findIndex(e.getPoint());
                    }
                }
                @Override public void mouseReleased(MouseEvent e) { dragIdx = -1; }
                @Override public void mouseEntered(MouseEvent e) {}
                @Override public void mouseExited(MouseEvent e) {}
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragIdx != -1) {
                        pts.get(dragIdx).setLocation(e.getPoint());
                        repaint();
                    }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    hoverIdx = findIndex(e.getPoint());
                    repaint();
                }
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE || e.getKeyCode() == KeyEvent.VK_DELETE) {
                        if (hoverIdx != -1) pts.remove(hoverIdx);
                        else if (!pts.isEmpty()) pts.remove(pts.size()-1);
                        hoverIdx = -1;
                        repaint();
                    }
                }
                @Override public void keyReleased(KeyEvent e) {}
                @Override public void keyTyped(KeyEvent e) {}
            }

            DrawPanel dp = new DrawPanel();
            JButton ok = new JButton("Aceptar");
            JButton cancel = new JButton("Cancelar");
            JButton trace = new JButton("Papel calco");
            final Polygon[] polyRes = new Polygon[1];
            final String[] nameRes = new String[1];
            final JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Dibujar polígono", true);
            ok.addActionListener(ev -> {
                if (dp.pts.size() >= 3) {
                    Polygon p = new Polygon();
                    for (Point pt : dp.pts) p.addPoint(pt.x, pt.y);
                    Rectangle b = p.getBounds();
                    for (int i = 0; i < p.npoints; i++) {
                        p.xpoints[i] -= b.x; p.ypoints[i] -= b.y;
                    }
                    String nm = JOptionPane.showInputDialog(dp, "Nombre de la forma:");
                    if (nm != null && !nm.isBlank()) {
                        if (project.shapes.containsKey(nm)) {
                            JOptionPane.showMessageDialog(dp, "Ya existe una forma con ese nombre");
                        } else {
                            polyRes[0] = p;
                            nameRes[0] = nm;
                            dlg.dispose();
                        }
                    } else {
                        JOptionPane.showMessageDialog(dp, "Debe ingresar un nombre.");
                    }
                } else {
                    JOptionPane.showMessageDialog(dp, "Se requieren al menos 3 puntos");
                }
            });
            cancel.addActionListener(ev -> { polyRes[0] = null; dlg.dispose(); });
            trace.addActionListener(ev -> {
                if (project.shapes.isEmpty()) {
                    JOptionPane.showMessageDialog(dlg, "No hay formas disponibles");
                    return;
                }
                JSpinner sp = new JSpinner(new SpinnerListModel(project.shapes.keySet().toArray(new String[0])));
                int r = JOptionPane.showConfirmDialog(dlg, sp, "Seleccionar forma", JOptionPane.OK_CANCEL_OPTION);
                if (r == JOptionPane.OK_OPTION) {
                    String nm = sp.getValue().toString();
                    Polygon poly = project.shapes.get(nm);
                    dp.setOnion(poly);
                    dp.requestFocusInWindow();
                }
            });
            JPanel bp = new JPanel(); bp.add(trace); bp.add(ok); bp.add(cancel);
            dlg.getContentPane().add(dp, BorderLayout.CENTER);
            dlg.getContentPane().add(bp, BorderLayout.SOUTH);
            dlg.pack(); dlg.setLocationRelativeTo(this); dlg.setVisible(true);
            if (polyRes[0] != null) {
                return new AbstractMap.SimpleEntry<>(nameRes[0], polyRes[0]);
            }
            return null;
        }
    }

    // ====== PALETA DE BLOQUES ======
    static class WrapLayout extends FlowLayout {
        WrapLayout() { super(); }
        WrapLayout(int align) { super(align); }
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

        @Override
        public Dimension preferredLayoutSize(Container target) { return layoutSize(target, true); }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= (getHgap() + 1);
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
                int maxWidth = targetWidth - horizontalInsetsAndGap;

                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                int nmembers = target.getComponentCount();
                for (int i = 0; i < nmembers; i++) {
                    Component m = target.getComponent(i);
                    if (m.isVisible()) {
                        Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                        if (rowWidth + d.width > maxWidth) {
                            dim.width = Math.max(dim.width, rowWidth);
                            dim.height += rowHeight + vgap;
                            rowWidth = 0;
                            rowHeight = 0;
                        }
                        if (rowWidth != 0) rowWidth += hgap;
                        rowWidth += d.width;
                        rowHeight = Math.max(rowHeight, d.height);
                    }
                }

                dim.width = Math.max(dim.width, rowWidth);
                dim.height += rowHeight;
                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + vgap * 2;
                return dim;
            }
        }
    }

    /**
     * Panel desplazable que sigue el ancho del viewport, permitiendo que el
     * {@link WrapLayout} distribuya los botones en múltiples filas sin que se
     * desborden horizontalmente.
     */
    static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) { super(lm); }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static class PalettePanel extends JPanel {
        ScriptCanvasPanel dropTarget;

        PalettePanel() {
            super(new BorderLayout());
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Eventos", createEventsPanel());
            tabs.addTab("Condicionales", createConditionalsPanel());
            tabs.addTab("Acciones", createActionsPanel());
            add(new JLabel("Paleta de Bloques"), BorderLayout.NORTH);
            add(tabs, BorderLayout.CENTER);
        }

        private JScrollPane createEventsPanel() {
            ScrollablePanel p = new ScrollablePanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
            p.setBorder(new EmptyBorder(10,10,10,10));
            p.add(makeBtn("Al iniciar", "Se ejecuta una vez al comenzar la escena.", () -> new EventBlock(EventType.ON_START)));
            p.add(makeBtn("Al aparecer", "Se ejecuta cuando la entidad aparece en la escena.", () -> new EventBlock(EventType.ON_APPEAR)));
            p.add(makeBtn("Cada (ms)...", "Repite las acciones cada intervalo en milisegundos.", () -> {
                EventBlock b = new EventBlock(EventType.ON_TICK);
                b.args.put("intervalMs", 500);
                return b;
            }));
            p.add(makeBtn("Tecla ↓ ...", "Se ejecuta al presionar la tecla seleccionada.", () -> {
                EventBlock b = new EventBlock(EventType.ON_KEY_DOWN);
                b.args.put("keyCode", KeyEvent.VK_RIGHT);
                return b;
            }));
            p.add(makeBtn("Clic ratón", "Se ejecuta al hacer clic con el ratón.", () -> {
                EventBlock b = new EventBlock(EventType.ON_MOUSE);
                b.args.put("button", MouseEvent.BUTTON1);
                return b;
            }));
            p.add(makeBtn("Toca borde", "Se ejecuta cuando la entidad toca el borde del escenario.", () -> new EventBlock(EventType.ON_EDGE)));
            p.add(makeBtn("Var de entidad >o<", "Se dispara al cumplirse una condición sobre una variable de la entidad.", () -> {
                EventBlock b = new EventBlock(EventType.ON_VAR_CHANGE);
                b.args.put("var", "var");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Var global >o<", "Se dispara al cumplirse una condición sobre una variable global.", () -> {
                EventBlock b = new EventBlock(EventType.ON_GLOBAL_VAR_CHANGE);
                b.args.put("var", "var");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Colisión con...", "Se ejecuta al chocar con otra entidad.", () -> new EventBlock(EventType.ON_COLLIDE)));
            p.add(makeBtn("Se acerca entidad...", "Se ejecuta cuando otra entidad se acerca dentro del radio indicado.", () -> {
                EventBlock b = new EventBlock(EventType.ON_ENTITY_NEAR);
                b.args.put("radius", 50.0);
                return b;
            }));
            p.add(makeBtn("Al estar libre", "Se ejecuta cuando la entidad no tiene otra cadena de eventos activa.", () -> new EventBlock(EventType.ON_IDLE)));
            p.add(makeBtn("Mientras Var entidad", "Repite mientras la variable de la entidad cumpla la condición.", () -> {
                EventBlock b = new EventBlock(EventType.ON_WHILE_VAR);
                b.args.put("var", "var");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Mientras Var global", "Repite mientras la variable global cumpla la condición.", () -> {
                EventBlock b = new EventBlock(EventType.ON_WHILE_GLOBAL_VAR);
                b.args.put("var", "var");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            return wrap(p);
        }

        private JScrollPane createConditionalsPanel() {
            ScrollablePanel p = new ScrollablePanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
            p.setBorder(new EmptyBorder(10,10,10,10));
            p.add(makeBtn("Aleatorio", "Ejecuta una de las ramas conectadas al azar.", () -> new ActionBlock(ActionType.RANDOM)));
            p.add(makeBtn("Si variable...", "Ejecuta el bloque siguiente si la variable de la entidad cumple la condición.", () -> {
                ActionBlock b = new ActionBlock(ActionType.IF_VAR);
                b.args.put("var", "var");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Si global...", "Ejecuta el bloque siguiente si una variable global cumple la condición.", () -> {
                ActionBlock b = new ActionBlock(ActionType.IF_GLOBAL_VAR);
                b.args.put("var", "global");
                b.args.put("op", ">");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Si probabilidad...", "Ejecuta el bloque siguiente con la probabilidad indicada (0-1).", () -> {
                ActionBlock b = new ActionBlock(ActionType.IF_RANDOM_CHANCE);
                b.args.put("prob", 0.5);
                return b;
            }));
            return wrap(p);
        }

        private JScrollPane createActionsPanel() {
            ScrollablePanel p = new ScrollablePanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
            p.setBorder(new EmptyBorder(10,10,10,10));
            p.add(makeBtn("Mover", "Mueve la entidad en la dirección, velocidad y tiempo indicados.", () -> {
                ActionBlock b = new ActionBlock(ActionType.MOVE_BY);
                b.args.put("dir", "derecha");
                b.args.put("speed", 100.0);
                b.args.put("secs", 1.0);
                return b;
            }));
            p.add(makeBtn("Cambiar color", "Cambia el color de la entidad.", () -> new ActionBlock(ActionType.SET_COLOR)));
            p.add(makeBtn("Cambiar forma", "Cambia la forma de la entidad.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_SHAPE);
                b.args.put("shape", "RECT");
                return b;
            }));
            p.add(makeBtn("Alternar formas", "Alterna entre varias formas como animación.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SWITCH_SHAPES);
                java.util.List<String> forms = new ArrayList<>();
                forms.add("RECT");
                forms.add("CIRCLE");
                b.args.put("forms", forms);
                b.args.put("interval", 0.2);
                b.args.put("duration", 1.0);
                return b;
            }));
            p.add(makeBtn("Decir", "Muestra un mensaje sobre la entidad durante unos segundos.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SAY);
                b.args.put("text", "¡Hola!");
                b.args.put("secs", 2.0);
                return b;
            }));
            p.add(makeBtn("Asignar var entidad", "Fija el valor de una variable de la entidad.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_VAR);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("sum o res var entidad", "Suma o resta a una variable de la entidad.", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_VAR);
                b.args.put("var", "var");
                b.args.put("delta", 1);
                return b;
            }));
            p.add(makeBtn("Asignar var global", "Fija el valor de una variable global.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_GLOBAL_VAR);
                b.args.put("var", "var");
                b.args.put("value", 0);
                return b;
            }));
            p.add(makeBtn("Sum o res var global...", "Suma o resta a una variable global.", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_GLOBAL_VAR);
                b.args.put("var", "var");
                b.args.put("delta", 1);
                return b;
            }));
            p.add(makeBtn("Esperar", "Pausa la ejecución durante los segundos indicados.", () -> {
                ActionBlock b = new ActionBlock(ActionType.WAIT);
                b.args.put("secs", 1.0);
                return b;
            }));
            p.add(makeBtn("Girar forma", "Rota la entidad una cantidad de grados.", () -> {
                ActionBlock b = new ActionBlock(ActionType.ROTATE_BY);
                b.args.put("deg", 15);
                return b;
            }));
            p.add(makeBtn("Apuntar a", "Gira la entidad hacia un ángulo específico.", () -> {
                ActionBlock b = new ActionBlock(ActionType.ROTATE_TO);
                b.args.put("deg", 0);
                return b;
            }));
            p.add(makeBtn("Agrandar o achicar", "Multiplica el tamaño por el factor indicado.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SCALE_BY);
                b.args.put("factor", 1.1);
                return b;
            }));
            p.add(makeBtn("Asignar Tamaño", "Fija un tamaño exacto para la entidad.", () -> {
                ActionBlock b = new ActionBlock(ActionType.SET_SIZE);
                b.args.put("w", 60);
                b.args.put("h", 60);
                return b;
            }));
            p.add(makeBtn("Cambiar opacidad", "Cambia la transparencia de la entidad.", () -> {
                ActionBlock b = new ActionBlock(ActionType.CHANGE_OPACITY);
                b.args.put("delta", -0.1);
                return b;
            }));
            p.add(makeBtn("Mover a entidad", "Mueve la entidad hacia otra seleccionada.", () -> new ActionBlock(ActionType.MOVE_TO_ENTITY)));
            p.add(makeBtn("Crear entidad", "Crea una nueva copia de una entidad plantilla.", () -> new ActionBlock(ActionType.SPAWN_ENTITY)));
            p.add(makeBtn("Eliminar entidad", "Elimina la entidad actual de la escena.", () -> new ActionBlock(ActionType.DELETE_ENTITY)));
            p.add(makeBtn("Escenario siguiente", "Cambia al siguiente escenario.", () -> new ActionBlock(ActionType.NEXT_SCENE)));
            p.add(makeBtn("Escenario anterior", "Vuelve al escenario anterior.", () -> new ActionBlock(ActionType.PREV_SCENE)));
            p.add(makeBtn("Ir a escenario...", "Salta al número de escenario indicado.", () -> {
                ActionBlock b = new ActionBlock(ActionType.GOTO_SCENE);
                b.args.put("index", 1);
                return b;
            }));
            p.add(makeBtn("Detener", "Detiene la ejecución del programa.", () -> new ActionBlock(ActionType.STOP)));
            return wrap(p);
        }

        private JScrollPane wrap(JPanel p) {
            JScrollPane sp = new JScrollPane(p, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            sp.getVerticalScrollBar().setUnitIncrement(16);
            return sp;
        }

        JButton makeBtn(String text, String tip, Supplier<Block> factory) {
            JButton b = new JButton(text);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setToolTipText(tip);
            Block sample = factory.get();
            b.setBackground(colorFor(sample.kind()));
            b.setOpaque(true);
            b.setBorderPainted(false);
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
            // tamaño inicial amplio para permitir scroll
            setPreferredSize(new Dimension(800, 800));

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
            ensureFits(bv.getBounds());

            // si es evento y no existe aún en scripts, añadir raíz
            if (block instanceof EventBlock) {
                project.scriptsByEntity.computeIfAbsent(sel.id, k->new ArrayList<>()).add((EventBlock)block);
            }
            repaint();
            scriptChanged();
        }

        int getBlockCountFor(Entity e) {
            return getViews(e).size();
        }

        List<BlockView> getViews(Entity e) {
            return viewsByEntity.computeIfAbsent(e.id, k -> new ArrayList<>());
        }

        // Ajusta el tamaño del canvas para que contenga el rectángulo dado
        void ensureFits(Rectangle r) {
            int pad = 200; // margen extra
            Dimension cur = getPreferredSize();
            int nw = cur.width;
            int nh = cur.height;
            if (r.x + r.width + pad > cur.width) nw = r.x + r.width + pad;
            if (r.y + r.height + pad > cur.height) nh = r.y + r.height + pad;
            if (nw != cur.width || nh != cur.height) {
                setPreferredSize(new Dimension(nw, nh));
                revalidate();
            }
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
            ensureFits(v.getBounds());
            if (b instanceof ActionBlock ab && (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE)) {
                for (Block extra : ab.extraNext) addRecursive(extra, list);
            }
            if (b.next != null) {
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
                if (blk.next != null) {
                    BlockView child = findView(blk.next);
                    if (child != null) {
                        int x1 = v.getX() + v.getWidth()/2;
                        int y1 = v.getY() + v.getHeight();
                        int x2 = child.getX() + child.getWidth()/2;
                        int y2 = child.getY();
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
                if (blk instanceof ActionBlock ab && (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE)) {
                    for (Block extra : ab.extraNext) {
                        BlockView child = findView(extra);
                        if (child != null) {
                            int x1 = v.getX() + v.getWidth()/2;
                            int y1 = v.getY() + v.getHeight();
                            int x2 = child.getX() + child.getWidth()/2;
                            int y2 = child.getY();
                            g2.drawLine(x1, y1, x2, y2);
                        }
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
            if (b instanceof EventBlock ev) {
                ev.extraNext.remove(target);
                for (Block extra : new ArrayList<>(ev.extraNext)) detachLinks(extra, target);
            }
            if (b instanceof ActionBlock ab && (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE)) {
                ab.extraNext.remove(target);
                for (Block extra : new ArrayList<>(ab.extraNext)) detachLinks(extra, target);
            }
            detachLinks(b.next, target);
        }

        void tryAttach(BlockView candidate) {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;

            // No permitir encadenar un EVENT debajo de otro bloque
            if (candidate.block instanceof EventBlock) return;
            BlockView target = null;
            boolean inside = false;
            Point center = new Point(candidate.getX() + candidate.getWidth()/2,
                                     candidate.getY() + candidate.getHeight()/2);
            for (Component comp : getComponents()) {
                if (!(comp instanceof BlockView)) continue;
                BlockView other = (BlockView) comp;
                if (other == candidate) continue;
                Rectangle bounds = other.getBounds();
                Rectangle right = new Rectangle(bounds.x + bounds.width, bounds.y, 40, bounds.height);
                if (bounds.contains(center)) { target = other; inside = true; break; }
                if (right.contains(center)) { target = other; inside = false; break; }
            }
            if (target != null) {
                detach(candidate);
                if (target.block instanceof ActionBlock ab && (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE) && inside) {
                    if ((ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE) && !ab.extraNext.isEmpty()) {
                        Block tail = target.block;
                        while (tail.next != null) tail = tail.next;
                        tail.next = candidate.block;
                    } else {
                        ab.extraNext.add(candidate.block);
                    }
                } else {
                    Block tail = target.block;
                    while (tail.next != null) tail = tail.next;
                    tail.next = candidate.block;
                }
                repaint();
                scriptChanged();
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
            scriptChanged();
        }

        void scriptChanged() {
            Entity sel = listPanel.getSelected();
            if (sel == null) return;
            // Actualizar scripts de entidades basadas en la plantilla
            for (int i = 0; i < project.scenarios.size(); i++) {
                Scenario sc = project.scenarios.get(i);
                for (Entity en : sc.entities) {
                    if (sel.id.equals(en.templateId)) {
                        // Clonar scripts para el escenario almacenado
                        sc.scriptsByEntity.put(en.id, cloneScripts(sel.id));
                        // Si la entidad pertenece al escenario actual, también
                        // actualizamos el mapa de scripts en edición para reflejar
                        // el cambio inmediatamente en el canvas.
                        if (i == project.currentScenario) {
                            project.scriptsByEntity.put(en.id, cloneScripts(sel.id));
                        }
                    }
                }
            }
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
                for (Map.Entry<String, Object> en : ab.args.entrySet()) {
                    Object v = en.getValue();
                    if (v instanceof java.util.List<?> list) {
                        ab2.args.put(en.getKey(), new ArrayList<>(list));
                    } else {
                        ab2.args.put(en.getKey(), v);
                    }
                }
                if (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE) {
                    for (Block extra : ab.extraNext) ab2.extraNext.add(cloneBlock(extra));
                }
                copy = ab2;
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
                canvas.ensureFits(getBounds());
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

            Color base = colorFor(block.kind());
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
                } else if (ev.type == EventType.ON_VAR_CHANGE || ev.type == EventType.ON_GLOBAL_VAR_CHANGE ||
                        ev.type == EventType.ON_WHILE_VAR || ev.type == EventType.ON_WHILE_GLOBAL_VAR) {
                    java.util.List<String> vars = new ArrayList<>();
                    boolean isGlobal = (ev.type == EventType.ON_GLOBAL_VAR_CHANGE || ev.type == EventType.ON_WHILE_GLOBAL_VAR);
                    if (!isGlobal) {
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
                    String curOp = String.valueOf(ev.args.getOrDefault("op", ">"));
                    String opName = curOp.equals(">") ? "Mayor que" : "Menor que";
                    JSpinner opSpin = new JSpinner(new SpinnerListModel(new String[]{"Mayor que","Menor que"}));
                    opSpin.setValue(opName);
                    JSpinner valSpin = new JSpinner(new SpinnerNumberModel(
                            ((Number)ev.args.getOrDefault("value",0)).doubleValue(), -1e9,1e9,1.0));
                    JPanel pan = new JPanel(new GridLayout(3,2));
                    pan.add(new JLabel(isGlobal ? "Variable global" : "Variable"));
                    pan.add(varSpin);
                    pan.add(new JLabel("Comparación"));
                    pan.add(opSpin);
                    pan.add(new JLabel("Valor"));
                    pan.add(valSpin);
                    int r = JOptionPane.showConfirmDialog(this, pan, "Configurar", JOptionPane.OK_CANCEL_OPTION);
                    if (r == JOptionPane.OK_OPTION) {
                        ev.args.put("var", String.valueOf(varSpin.getValue()));
                        String selOpName = String.valueOf(opSpin.getValue());
                        ev.args.put("op", selOpName.equals("Mayor que") ? ">" : "<");
                        ev.args.put("value", ((Number)valSpin.getValue()).doubleValue());
                    }
                } else if (ev.type == EventType.ON_COLLIDE) {
                    Entity currentEnt = canvas.listPanel.getSelected();
                    java.util.List<JCheckBox> checks = new ArrayList<>();
                    java.util.List<Entity> candidates = new ArrayList<>();
                    JPanel pan = new JPanel();
                    pan.setLayout(new BoxLayout(pan, BoxLayout.Y_AXIS));
                    for (Entity en : canvas.project.entities) {
                        if (currentEnt != null && en.id.equals(currentEnt.id)) continue;
                        JCheckBox cb = new JCheckBox(en.name);
                        @SuppressWarnings("unchecked")
                        java.util.List<String> selected = (java.util.List<String>) ev.args.getOrDefault("targetIds", new ArrayList<>());
                        if (selected.contains(en.id)) cb.setSelected(true);
                        checks.add(cb);
                        candidates.add(en);
                        pan.add(cb);
                    }
                    int r = JOptionPane.showConfirmDialog(this, new JScrollPane(pan), "Entidades", JOptionPane.OK_CANCEL_OPTION);
                    if (r == JOptionPane.OK_OPTION) {
                        java.util.List<String> ids = new ArrayList<>();
                        java.util.List<String> names = new ArrayList<>();
                        for (int i = 0; i < checks.size(); i++) {
                            JCheckBox cb = checks.get(i);
                            Entity en = candidates.get(i);
                            if (cb.isSelected()) {
                                ids.add(en.id);
                                names.add(en.name);
                            }
                        }
                        ev.args.put("targetIds", ids);
                        ev.args.put("targetNames", names);
                    }
                } else if (ev.type == EventType.ON_ENTITY_NEAR) {
                    Entity currentEnt = canvas.listPanel.getSelected();
                    java.util.List<String> names = new ArrayList<>();
                    for (Entity en : canvas.project.entities) {
                        if (currentEnt != null && en.id.equals(currentEnt.id)) continue;
                        names.add(en.name);
                    }
                    if (names.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No hay entidades", "Se acerca entidad", JOptionPane.WARNING_MESSAGE);
                    } else {
                        String currentName = String.valueOf(ev.args.getOrDefault("targetName", names.get(0)));
                        if (!names.contains(currentName)) names.add(0, currentName);
                        JSpinner entSpin = new JSpinner(new SpinnerListModel(names));
                        entSpin.setValue(currentName);
                        double curRad = ((Number) ev.args.getOrDefault("radius", 50.0)).doubleValue();
                        JSpinner radSpin = new JSpinner(new SpinnerNumberModel(curRad, 1.0, 1000.0, 1.0));
                        JPanel pan2 = new JPanel(new GridLayout(2,2));
                        pan2.add(new JLabel("Entidad")); pan2.add(entSpin);
                        pan2.add(new JLabel("Radio")); pan2.add(radSpin);
                        int r2 = JOptionPane.showConfirmDialog(this, pan2, "Se acerca entidad", JOptionPane.OK_CANCEL_OPTION);
                        if (r2 == JOptionPane.OK_OPTION) {
                            String nameSel = (String) entSpin.getValue();
                            Entity target = canvas.project.entities.stream().filter(en -> en.name.equals(nameSel)).findFirst().orElse(null);
                            if (target != null) {
                                ev.args.put("targetId", target.id);
                                ev.args.put("targetName", target.name);
                            }
                            ev.args.put("radius", ((Number) radSpin.getValue()).doubleValue());
                        }
                    }
                }
            } else if (block instanceof ActionBlock) {
                ActionBlock ab = (ActionBlock) block;
                switch (ab.type) {
                    case MOVE_BY -> {
                        String[] dirs = {"Derecha","Izquierda","Arriba","Abajo","Lejos de...","Seguir a..."};
                        String curDir = String.valueOf(ab.args.getOrDefault("dir", "derecha"));
                        int selDir = switch (curDir) {
                            case "izquierda" -> 1;
                            case "arriba" -> 2;
                            case "abajo" -> 3;
                            case "lejos" -> 4;
                            case "seguir" -> 5;
                            default -> 0;
                        };
                        int dir = JOptionPane.showOptionDialog(this, "Dirección", "Dirección", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, dirs, dirs[Math.min(selDir, dirs.length-1)]);
                        if (dir == 0 || dir == 1 || dir == 2 || dir == 3) {
                            String spd = JOptionPane.showInputDialog(this, "Velocidad (px/s):", ab.args.getOrDefault("speed", 100.0));
                            String dur = JOptionPane.showInputDialog(this, "Duración (s):", ab.args.getOrDefault("secs", 1.0));
                            try {
                                double vs = Double.parseDouble(spd);
                                double se = Double.parseDouble(dur);
                                switch (dir) {
                                    case 0 -> ab.args.put("dir", "derecha");
                                    case 1 -> ab.args.put("dir", "izquierda");
                                    case 2 -> ab.args.put("dir", "arriba");
                                    case 3 -> ab.args.put("dir", "abajo");
                                }
                                ab.args.put("speed", vs);
                                ab.args.put("secs", se);
                            } catch (Exception ignored) {}
                        } else if (dir == 4) {
                            if (canvas.project.entities.isEmpty()) break;
                            java.util.List<String> names = new ArrayList<>();
                            Entity currentEnt = canvas.listPanel.getSelected();
                            for (Entity en : canvas.project.entities) {
                                if (currentEnt != null && en.id.equals(currentEnt.id)) continue;
                                names.add(en.name);
                            }
                            String currentName = String.valueOf(ab.args.getOrDefault("targetName", names.get(0)));
                            if (!names.contains(currentName)) names.add(0, currentName);
                            JSpinner entSpin = new JSpinner(new SpinnerListModel(names));
                            entSpin.setValue(currentName);
                            double curSpeed = Double.parseDouble(String.valueOf(ab.args.getOrDefault("speed", 100.0)));
                            JSpinner speedSpin = new JSpinner(new SpinnerNumberModel(curSpeed, 1.0, 1000.0, 10.0));
                            double curSecs = Double.parseDouble(String.valueOf(ab.args.getOrDefault("secs", 1.0)));
                            JSpinner secsSpin = new JSpinner(new SpinnerNumberModel(curSecs, 0.1, 1000.0, 0.1));
                            JPanel pan = new JPanel(new GridLayout(3,2));
                            pan.add(new JLabel("Entidad")); pan.add(entSpin);
                            pan.add(new JLabel("Velocidad")); pan.add(speedSpin);
                            pan.add(new JLabel("Segundos")); pan.add(secsSpin);
                            int r = JOptionPane.showConfirmDialog(this, pan, "Mover lejos de", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String name = (String) entSpin.getValue();
                                Entity tpl = canvas.project.entities.stream().filter(en -> en.name.equals(name)).findFirst().orElse(null);
                                if (tpl != null) {
                                    ab.args.put("targetId", tpl.id);
                                    ab.args.put("targetName", tpl.name);
                                }
                                ab.args.put("dir", "lejos");
                                ab.args.put("speed", ((Number) speedSpin.getValue()).doubleValue());
                                ab.args.put("secs", ((Number) secsSpin.getValue()).doubleValue());
                            }
                        } else if (dir == 5) {
                            if (canvas.project.entities.isEmpty()) break;
                            java.util.List<String> names = new ArrayList<>();
                            Entity currentEnt = canvas.listPanel.getSelected();
                            for (Entity en : canvas.project.entities) {
                                if (currentEnt != null && en.id.equals(currentEnt.id)) continue;
                                names.add(en.name);
                            }
                            String currentName = String.valueOf(ab.args.getOrDefault("targetName", names.get(0)));
                            if (!names.contains(currentName)) names.add(0, currentName);
                            JSpinner entSpin = new JSpinner(new SpinnerListModel(names));
                            entSpin.setValue(currentName);
                            double curSpeed = Double.parseDouble(String.valueOf(ab.args.getOrDefault("speed", 100.0)));
                            JSpinner speedSpin = new JSpinner(new SpinnerNumberModel(curSpeed, 1.0, 1000.0, 10.0));
                            JPanel pan = new JPanel(new GridLayout(2,2));
                            pan.add(new JLabel("Entidad")); pan.add(entSpin);
                            pan.add(new JLabel("Velocidad")); pan.add(speedSpin);
                            int r = JOptionPane.showConfirmDialog(this, pan, "Seguir a entidad", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                String name = (String) entSpin.getValue();
                                Entity tpl = canvas.project.entities.stream().filter(en -> en.name.equals(name)).findFirst().orElse(null);
                                if (tpl != null) {
                                    ab.args.put("targetId", tpl.id);
                                    ab.args.put("targetName", tpl.name);
                                }
                                ab.args.put("dir", "seguir");
                                ab.args.put("speed", ((Number) speedSpin.getValue()).doubleValue());
                            }
                        }
                    }
                    case SET_COLOR -> {
                        Color chosen = JColorChooser.showDialog(this, "Elegir color", (Color) ab.args.getOrDefault("color", new Color(0xE74C3C)));
                        if (chosen != null) ab.args.put("color", chosen);
                    }
                    case SET_SHAPE -> {
                        LinkedHashMap<String,String> map = new LinkedHashMap<>();
                        map.put("Rectángulo","RECT");
                        map.put("Círculo","CIRCLE");
                        map.put("Triángulo","TRIANGLE");
                        map.put("Pentágono","PENTAGON");
                        map.put("Hexágono","HEXAGON");
                        map.put("Estrella","STAR");
                        for (String nm : canvas.project.shapes.keySet()) {
                            map.put(nm, "POLYGON:" + nm);
                        }
                        JComboBox<String> box = new JComboBox<>(map.keySet().toArray(new String[0]));
                        String cur = String.valueOf(ab.args.getOrDefault("shape", "RECT"));
                        if ("POLYGON".equals(cur)) {
                            String nm = (String) ab.args.get("shapeName");
                            cur = "POLYGON:" + (nm != null ? nm : "");
                        }
                        for (Map.Entry<String,String> en : map.entrySet()) {
                            if (en.getValue().equals(cur)) { box.setSelectedItem(en.getKey()); break; }
                        }
                        int r = JOptionPane.showConfirmDialog(this, box, "Forma", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            String lbl = (String) box.getSelectedItem();
                            String val = map.get(lbl);
                            if (val.startsWith("POLYGON:")) {
                                ab.args.put("shape", "POLYGON");
                                ab.args.put("shapeName", val.substring(8));
                            } else {
                                ab.args.put("shape", val);
                                ab.args.remove("shapeName");
                            }
                        }
                    }
                    case SWITCH_SHAPES -> {
                        LinkedHashMap<String,String> map = new LinkedHashMap<>();
                        map.put("Rectángulo","RECT");
                        map.put("Círculo","CIRCLE");
                        map.put("Triángulo","TRIANGLE");
                        map.put("Pentágono","PENTAGON");
                        map.put("Hexágono","HEXAGON");
                        map.put("Estrella","STAR");
                        for (String nm : canvas.project.shapes.keySet()) {
                            map.put(nm, "POLYGON:" + nm);
                        }
                        String[] labels = map.keySet().toArray(new String[0]);
                        java.util.List<String> forms = (java.util.List<String>) ab.args.getOrDefault("forms", new ArrayList<>(Arrays.asList("RECT","CIRCLE")));
                        double interval = Double.parseDouble(String.valueOf(ab.args.getOrDefault("interval", 0.2)));
                        double duration = Double.parseDouble(String.valueOf(ab.args.getOrDefault("duration", 1.0)));
                        int max = 5;
                        int count = Math.max(2, Math.min(max, forms.size()));
                        JSpinner countSpin = new JSpinner(new SpinnerNumberModel(count, 2, max, 1));
                        JComboBox<String>[] boxes = new JComboBox[max];
                        JPanel pan = new JPanel(new GridLayout(max + 3, 2));
                        pan.add(new JLabel("Cantidad")); pan.add(countSpin);
                        for (int i = 0; i < max; i++) {
                            boxes[i] = new JComboBox<>(labels);
                            pan.add(new JLabel("Forma " + (i + 1)));
                            pan.add(boxes[i]);
                        }
                        for (int i = 0; i < max; i++) {
                            if (i < forms.size()) {
                                String val = forms.get(i);
                                for (Map.Entry<String,String> en : map.entrySet()) {
                                    if (en.getValue().equals(val)) { boxes[i].setSelectedItem(en.getKey()); break; }
                                }
                            }
                            boxes[i].setEnabled(i < count);
                        }
                        countSpin.addChangeListener(e -> {
                            int c = (int) ((JSpinner) e.getSource()).getValue();
                            for (int i = 0; i < max; i++) boxes[i].setEnabled(i < c);
                        });
                        JSpinner intSpin = new JSpinner(new SpinnerNumberModel(interval, 0.1, 1000.0, 0.1));
                        JSpinner durSpin = new JSpinner(new SpinnerNumberModel(duration, 0.1, 1000.0, 0.1));
                        pan.add(new JLabel("Intervalo (s)")); pan.add(intSpin);
                        pan.add(new JLabel("Duración (s)")); pan.add(durSpin);
                        int r = JOptionPane.showConfirmDialog(this, pan, "Alternar formas", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            int c = (int) countSpin.getValue();
                            java.util.List<String> newForms = new ArrayList<>();
                            for (int i = 0; i < c; i++) {
                                String lbl = (String) boxes[i].getSelectedItem();
                                newForms.add(map.get(lbl));
                            }
                            ab.args.put("forms", newForms);
                            ab.args.put("interval", ((Number) intSpin.getValue()).doubleValue());
                            ab.args.put("duration", ((Number) durSpin.getValue()).doubleValue());
                        }
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
                    case IF_VAR -> {
                        Entity sel = canvas.listPanel.getSelected();
                        java.util.Set<String> names = sel != null ? sel.vars.keySet() : java.util.Collections.emptySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        String curOp = String.valueOf(ab.args.getOrDefault("op", ">"));
                        JComboBox<String> opBox = new JComboBox<>(new String[]{"Mayor que","Menor que"});
                        opBox.setSelectedIndex(curOp.equals(">") ? 0 : 1);
                        double curVal = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                        JSpinner valSpin = new JSpinner(new SpinnerNumberModel(curVal, -1e9, 1e9, 1.0));
                        JPanel pan = new JPanel(new GridLayout(3,2));
                        pan.add(new JLabel("Variable:")); pan.add(varBox);
                        pan.add(new JLabel("Condición:")); pan.add(opBox);
                        pan.add(new JLabel("Valor:")); pan.add(valSpin);
                        if (names.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "No hay variables", "Si variable", JOptionPane.WARNING_MESSAGE);
                        } else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Si variable", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                ab.args.put("var", varBox.getSelectedItem());
                                ab.args.put("op", opBox.getSelectedIndex() == 0 ? ">" : "<");
                                ab.args.put("value", ((Number) valSpin.getValue()).doubleValue());
                            }
                        }
                    }
                    case IF_GLOBAL_VAR -> {
                        java.util.Set<String> names = canvas.project.globalVars.keySet();
                        JComboBox<String> varBox = new JComboBox<>(names.toArray(new String[0]));
                        String curOp = String.valueOf(ab.args.getOrDefault("op", ">"));
                        JComboBox<String> opBox = new JComboBox<>(new String[]{"Mayor que","Menor que"});
                        opBox.setSelectedIndex(curOp.equals(">") ? 0 : 1);
                        double curVal = Double.parseDouble(String.valueOf(ab.args.getOrDefault("value", 0)));
                        JSpinner valSpin = new JSpinner(new SpinnerNumberModel(curVal, -1e9, 1e9, 1.0));
                        JPanel pan = new JPanel(new GridLayout(3,2));
                        pan.add(new JLabel("Global:")); pan.add(varBox);
                        pan.add(new JLabel("Condición:")); pan.add(opBox);
                        pan.add(new JLabel("Valor:")); pan.add(valSpin);
                        if (names.isEmpty()) {
                            JOptionPane.showMessageDialog(this, "No hay variables", "Si global", JOptionPane.WARNING_MESSAGE);
                        } else {
                            int r = JOptionPane.showConfirmDialog(this, pan, "Si global", JOptionPane.OK_CANCEL_OPTION);
                            if (r == JOptionPane.OK_OPTION) {
                                ab.args.put("var", varBox.getSelectedItem());
                                ab.args.put("op", opBox.getSelectedIndex() == 0 ? ">" : "<");
                                ab.args.put("value", ((Number) valSpin.getValue()).doubleValue());
                            }
                        }
                    }
                    case IF_RANDOM_CHANCE -> {
                        double curProb = Double.parseDouble(String.valueOf(ab.args.getOrDefault("prob", 0.5)));
                        JSpinner probSpin = new JSpinner(new SpinnerNumberModel(curProb, 0.0, 1.0, 0.05));
                        JPanel pan = new JPanel(new GridLayout(1,2));
                        pan.add(new JLabel("Probabilidad:")); pan.add(probSpin);
                        int r = JOptionPane.showConfirmDialog(this, pan, "Si probabilidad", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            ab.args.put("prob", ((Number) probSpin.getValue()).doubleValue());
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
                    case MOVE_TO_ENTITY -> {
                        if (canvas.project.entities.isEmpty()) break;
                        java.util.List<String> names = new ArrayList<>();
                        for (Entity en : canvas.project.entities) names.add(en.name);
                        String currentName = String.valueOf(ab.args.getOrDefault("targetName", names.get(0)));
                        if (!names.contains(currentName)) names.add(0, currentName);
                        JSpinner entSpin = new JSpinner(new SpinnerListModel(names));
                        entSpin.setValue(currentName);
                        double curSpeed = Double.parseDouble(String.valueOf(ab.args.getOrDefault("speed", 100.0)));
                        JSpinner speedSpin = new JSpinner(new SpinnerNumberModel(curSpeed, 1.0, 1000.0, 10.0));
                        JPanel pan = new JPanel(new GridLayout(2,2));
                        pan.add(new JLabel("Entidad"));
                        pan.add(entSpin);
                        pan.add(new JLabel("Velocidad"));
                        pan.add(speedSpin);
                        int r = JOptionPane.showConfirmDialog(this, pan, "Mover a entidad", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            String name = (String) entSpin.getValue();
                            Entity tpl = canvas.project.entities.stream()
                                    .filter(en -> en.name.equals(name)).findFirst().orElse(null);
                            if (tpl != null) {
                                ab.args.put("targetId", tpl.id);
                                ab.args.put("targetName", tpl.name);
                                ab.args.put("speed", ((Number) speedSpin.getValue()).doubleValue());
                            }
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

                        String mode = String.valueOf(ab.args.getOrDefault("mode", "MAP"));
                        JRadioButton mapBtn = new JRadioButton("Coordenadas del mapa");
                        JRadioButton relBtn = new JRadioButton("Relativas a la entidad");
                        JRadioButton randBtn = new JRadioButton("Lugar aleatorio");
                        ButtonGroup grp = new ButtonGroup();
                        grp.add(mapBtn); grp.add(relBtn); grp.add(randBtn);
                        mapBtn.setSelected("MAP".equals(mode));
                        relBtn.setSelected("REL".equals(mode));
                        randBtn.setSelected("RAND".equals(mode));

                        double curX = ((Number) ab.args.getOrDefault("x", 0.0)).doubleValue();
                        double curY = ((Number) ab.args.getOrDefault("y", 0.0)).doubleValue();
                        JSpinner xSpin = new JSpinner(new SpinnerNumberModel(curX, 0.0, canvas.project.canvas.width, 1.0));
                        JSpinner ySpin = new JSpinner(new SpinnerNumberModel(curY, 0.0, canvas.project.canvas.height, 1.0));
                        JPanel mapPanel = new JPanel(new GridLayout(2,2));
                        mapPanel.add(new JLabel("X:")); mapPanel.add(xSpin);
                        mapPanel.add(new JLabel("Y:")); mapPanel.add(ySpin);

                        String curDir = String.valueOf(ab.args.getOrDefault("dir", "abajo"));
                        JComboBox<String> dirBox = new JComboBox<>(new String[]{"arriba","abajo","izquierda","derecha"});
                        dirBox.setSelectedItem(curDir);
                        double curDist = ((Number) ab.args.getOrDefault("distance", 0.0)).doubleValue();
                        JSpinner distSpin = new JSpinner(new SpinnerNumberModel(curDist, 0.0, 1000.0, 1.0));
                        JPanel relPanel = new JPanel(new GridLayout(2,2));
                        relPanel.add(new JLabel("Dirección:")); relPanel.add(dirBox);
                        relPanel.add(new JLabel("Distancia:")); relPanel.add(distSpin);

                        CardLayout card = new CardLayout();
                        JPanel cardPanel = new JPanel(card);
                        JPanel randPanel = new JPanel();
                        cardPanel.add(mapPanel, "MAP");
                        cardPanel.add(relPanel, "REL");
                        cardPanel.add(randPanel, "RAND");
                        if ("REL".equals(mode)) card.show(cardPanel, "REL");
                        else if ("RAND".equals(mode)) card.show(cardPanel, "RAND");
                        else card.show(cardPanel, "MAP");
                        mapBtn.addActionListener(ev -> card.show(cardPanel, "MAP"));
                        relBtn.addActionListener(ev -> card.show(cardPanel, "REL"));
                        randBtn.addActionListener(ev -> card.show(cardPanel, "RAND"));

                        JPanel pan = new JPanel();
                        pan.setLayout(new BoxLayout(pan, BoxLayout.Y_AXIS));
                        JPanel entPanel = new JPanel(new GridLayout(1,2));
                        entPanel.add(new JLabel("Entidad"));
                        entPanel.add(entSpin);
                        pan.add(entPanel);
                        pan.add(mapBtn);
                        pan.add(relBtn);
                        pan.add(randBtn);
                        pan.add(cardPanel);

                        int r = JOptionPane.showConfirmDialog(this, pan, "Crear entidad", JOptionPane.OK_CANCEL_OPTION);
                        if (r == JOptionPane.OK_OPTION) {
                            String name = (String) entSpin.getValue();
                            Entity tpl = canvas.project.entities.stream()
                                    .filter(en -> en.name.equals(name)).findFirst().orElse(null);
                            if (tpl != null) {
                                ab.args.put("templateId", tpl.id);
                                ab.args.put("templateName", tpl.name);
                            }
                            if (mapBtn.isSelected()) {
                                ab.args.put("mode", "MAP");
                                ab.args.put("x", ((Number) xSpin.getValue()).doubleValue());
                                ab.args.put("y", ((Number) ySpin.getValue()).doubleValue());
                            } else if (relBtn.isSelected()) {
                                ab.args.put("mode", "REL");
                                ab.args.put("dir", dirBox.getSelectedItem());
                                ab.args.put("distance", ((Number) distSpin.getValue()).doubleValue());
                            } else {
                                ab.args.put("mode", "RAND");
                            }
                        }
                    }
                    case NEXT_SCENE -> {}
                    case PREV_SCENE -> {}
                    case GOTO_SCENE -> {
                        String s = JOptionPane.showInputDialog(this, "Escenario:", ab.args.getOrDefault("index", 1));
                        if (s != null && s.matches("\\d+")) {
                            ab.args.put("index", Integer.parseInt(s));
                        }
                    }
                    case STOP -> {}
                }
                canvas.scriptChanged();
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

    // ====== PANEL TUTORIAL ======
    static class TutorialPanel extends JPanel {
        final TutorialSystem tutorial;
        final JList<TutorialSystem.Mission> list;
        final JTextArea info;

        TutorialPanel(TutorialSystem t) {
            super(new BorderLayout());
            this.tutorial = t;
            DefaultListModel<TutorialSystem.Mission> model = new DefaultListModel<>();
            list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            info = new JTextArea();
            info.setEditable(false);
            info.setLineWrap(true);
            info.setWrapStyleWord(true);
            list.addListSelectionListener(e -> {
                TutorialSystem.Mission m = list.getSelectedValue();
                info.setText(m != null ? m.instrucciones : "");
            });
            list.setCellRenderer((lst, value, index, isSelected, cellHasFocus) -> {
                JLabel lbl = new JLabel();
                int idx = tutorial.getIndiceActual();
                String prefix = index < idx ? "✔ " : index == idx ? "▶ " : "✖ ";
                lbl.setText(prefix + value.nombre);
                if (isSelected) {
                    lbl.setBackground(lst.getSelectionBackground());
                    lbl.setForeground(lst.getSelectionForeground());
                } else {
                    lbl.setBackground(lst.getBackground());
                    lbl.setForeground(lst.getForeground());
                }
                lbl.setOpaque(true);
                return lbl;
            });
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(list), new JScrollPane(info));
            split.setResizeWeight(0.5);
            add(split, BorderLayout.CENTER);
            refresh();
        }

        void refresh() {
            DefaultListModel<TutorialSystem.Mission> model =
                    (DefaultListModel<TutorialSystem.Mission>) list.getModel();
            model.removeAllElements();
            java.util.List<TutorialSystem.Mission> mis = tutorial.getMisiones();
            for (TutorialSystem.Mission m : mis) model.addElement(m);
            int idx = tutorial.getIndiceActual();
            if (idx < mis.size()) list.setSelectedIndex(idx);
            else if (!mis.isEmpty()) list.setSelectedIndex(mis.size() - 1);
        }
    }

    // ====== ESCENARIO ======
    static class StagePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener {
        final Project project;
        final Set<Integer> keysDown;
        Runnable onPlay, onStop, onBack;
        GameRuntime runtime;

        JSpinner widthSpin, heightSpin;

        final Dimension size;
        int border = 20;
        CanvasView canvasView;
        // Entidades actualmente presentes en el escenario (separadas de las plantillas del proyecto)
        final List<Entity> entities = new ArrayList<>();
        // Respaldo de scripts del editor para restaurar al volver
        Map<String, List<EventBlock>> editorScriptBackup = new HashMap<>();
        Entity dragEntity = null;
        Entity selectedEntity = null;
        Point dragOffset = null;
        boolean playing = false;
        boolean deleteMode = false;
        final List<Entity> snapshot = new ArrayList<>();
        Map<String, List<EventBlock>> scriptSnapshot = new HashMap<>();
        Map<String, Double> globalVarSnapshot = new HashMap<>();
        Set<String> globalVarVisibleSnapshot = new HashSet<>();
        int currentScenario = 0;
        JSpinner scenarioSpin;

        int stageWidth() { return size.width - border * 2; }
        int stageHeight() { return size.height - border * 2; }

        // Ajusta la entidad para que su forma completa permanezca dentro del escenario
        void clampEntity(Entity e) {
            Rectangle2D b = buildShape(e).getBounds2D();
            double dx = 0, dy = 0;
            if (b.getMinX() < 0) dx = -b.getMinX();
            else if (b.getMaxX() > stageWidth()) dx = stageWidth() - b.getMaxX();
            if (b.getMinY() < 0) dy = -b.getMinY();
            else if (b.getMaxY() > stageHeight()) dy = stageHeight() - b.getMaxY();
            e.t.x += dx;
            e.t.y += dy;
        }

        void loadCurrentScenario() {
            editorScriptBackup.clear();
            editorScriptBackup.putAll(project.scriptsByEntity);
            if (project.scenarios.isEmpty()) project.scenarios.add(new Scenario());
            switchScenario(project.currentScenario, false);
            updateScenarioSpin();
        }

        void saveToScenario() {
            if (project.scenarios.isEmpty()) project.scenarios.add(new Scenario());
            Scenario sc = project.scenarios.get(currentScenario);
            sc.entities = new ArrayList<>();
            sc.scriptsByEntity = new HashMap<>();
            for (Entity en : entities) {
                sc.entities.add(cloneEntity(en, true));
                sc.scriptsByEntity.put(en.id, cloneScripts(en.id));
            }
        }

        void loadFromScenario(int idx) {
            if (idx < 0 || idx >= project.scenarios.size()) return;
            currentScenario = idx;
            project.currentScenario = idx;
            project.scriptsByEntity.clear();
            project.scriptsByEntity.putAll(editorScriptBackup);
            entities.clear();
            Scenario sc = project.scenarios.get(idx);
            for (Entity e : sc.entities) {
                Entity c = cloneEntity(e, true);
                entities.add(c);
                List<EventBlock> copy = new ArrayList<>();
                for (EventBlock ev : sc.scriptsByEntity.getOrDefault(e.id, Collections.emptyList())) {
                    copy.add((EventBlock) cloneBlock(ev));
                }
                project.scriptsByEntity.put(c.id, copy);
            }
            selectedEntity = null;
            if (widthSpin != null && heightSpin != null) {
                widthSpin.setValue(size.width);
                heightSpin.setValue(size.height);
            }
            repaint();
        }

        void switchScenario(int idx, boolean saveCurrent) {
            if (saveCurrent) saveToScenario();
            loadFromScenario(idx);
            updateScenarioSpin();
        }

        void updateScenarioSpin() {
            if (scenarioSpin != null) {
                scenarioSpin.setModel(new SpinnerNumberModel(currentScenario + 1, 1, project.scenarios.size(), 1));
            }
        }

        public void nextScenario() {
            if (project.scenarios.isEmpty()) return;
            switchScenario((currentScenario + 1) % project.scenarios.size(), false);
        }

        public void prevScenario() {
            if (project.scenarios.isEmpty()) return;
            switchScenario((currentScenario - 1 + project.scenarios.size()) % project.scenarios.size(), false);
        }

        public void gotoScenarioIndex(int idx) {
            if (project.scenarios.isEmpty()) return;
            if (idx < 0 || idx >= project.scenarios.size()) return;
            switchScenario(idx, false);
        }

        StagePanel(Project p, Set<Integer> keysDown) {
            this.project = p; this.keysDown = keysDown; this.size = p.canvas;
            setLayout(new BorderLayout());

            // Top bar
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton btnBack = new JButton("◀ Volver al Editor");
            JButton btnPlay = new JButton("▶ Probar");
            JButton btnStop = new JButton("■ Detener");
            JButton btnAddEntity = new JButton("Agregar Entidad");
            JButton btnDelEntity = new JButton("Eliminar Entidad");
            JButton btnNewScene = new JButton("Crear Escenario");
            JButton btnDelScene = new JButton("Eliminar Escenario");
            JButton btnSetBg = new JButton("Fondo...");
            bar.add(btnBack);
            bar.add(btnPlay); bar.add(btnStop);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(btnAddEntity); bar.add(btnDelEntity);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(new JLabel("Escenario:"));
            scenarioSpin = new JSpinner(new SpinnerNumberModel(1,1,1,1));
            bar.add(scenarioSpin);
            bar.add(btnNewScene); bar.add(btnDelScene); bar.add(btnSetBg);
            bar.add(Box.createHorizontalStrut(20));
            bar.add(new JLabel("Borde:"));
            JSpinner borderSpin = new JSpinner(new SpinnerNumberModel(border, 0, 200, 5));
            bar.add(borderSpin);
            bar.add(new JLabel("Ancho:"));
            widthSpin = new JSpinner(new SpinnerNumberModel(size.width, 200, 1600, 20));
            bar.add(widthSpin);
            bar.add(new JLabel("Alto:"));
            heightSpin = new JSpinner(new SpinnerNumberModel(size.height, 200, 1200, 20));
            bar.add(heightSpin);
            add(bar, BorderLayout.NORTH);

            // Canvas
            canvasView = new CanvasView();
            canvasView.setPreferredSize(size);
            canvasView.setBackground(Color.WHITE);
            canvasView.setFocusable(true);
            canvasView.addKeyListener(this);
            canvasView.addMouseListener(this);
            canvasView.addMouseMotionListener(this);
            add(canvasView, BorderLayout.CENTER);

            ChangeListener szListener = e -> {
                size.width = (int) widthSpin.getValue();
                size.height = (int) heightSpin.getValue();
                canvasView.setPreferredSize(size);
                canvasView.revalidate();
                repaint();
            };
            widthSpin.addChangeListener(szListener);
            heightSpin.addChangeListener(szListener);

            scenarioSpin.addChangeListener(e -> {
                int idx = ((Number) scenarioSpin.getValue()).intValue() - 1;
                if (idx != currentScenario) {
                    switchScenario(idx, !playing);
                }
            });
            btnNewScene.addActionListener(e -> {
                if (!playing) saveToScenario();
                project.scenarios.add(new Scenario());
                switchScenario(project.scenarios.size()-1, false);
            });
            btnDelScene.addActionListener(e -> {
                if (project.scenarios.size() <= 1) return;
                if (!playing) saveToScenario();
                project.scenarios.remove(currentScenario);
                int idx = Math.min(currentScenario, project.scenarios.size()-1);
                switchScenario(idx, false);
            });

            btnSetBg.addActionListener(e -> {
                JFileChooser fc = new JFileChooser(BACKGROUNDS_DIR);
                fc.setFileFilter(new FileNameExtensionFilter(
                        "Imágenes (PNG, JPG, JPEG, GIF, BMP)",
                        "png", "jpg", "jpeg", "gif", "bmp"));
                if (fc.showOpenDialog(StagePanel.this) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    Scenario sc = project.scenarios.get(currentScenario);
                    sc.setBackground(f);
                    repaint();
                }
            });

            btnBack.addActionListener(e -> {
                playing = false;
                btnPlay.setBackground(null);
                btnPlay.setOpaque(false);
                deleteMode = false;
                btnDelEntity.setBackground(null);
                btnDelEntity.setOpaque(false);
                saveToScenario();
                entities.clear();
                selectedEntity = null;
                project.scriptsByEntity.clear();
                project.scriptsByEntity.putAll(editorScriptBackup);
                editorScriptBackup.clear();
                if (onBack!=null) onBack.run();
            });
            btnPlay.addActionListener(e -> {
                playing = true;
                deleteMode = false;
                btnDelEntity.setBackground(null);
                btnDelEntity.setOpaque(false);
                btnPlay.setBackground(new Color(0x2ECC71));
                btnPlay.setOpaque(true);
                snapshot.clear();
                scriptSnapshot.clear();
                globalVarSnapshot.clear();
                globalVarSnapshot.putAll(project.globalVars);
                if (project.visibleGlobalVars == null) {
                    project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
                }
                globalVarVisibleSnapshot.clear();
                globalVarVisibleSnapshot.addAll(project.visibleGlobalVars);
                for (Entity en : entities) {
                    snapshot.add(cloneEntity(en, true));
                    scriptSnapshot.put(en.id, cloneScripts(en.id));
                }
                requestFocusInWindow();
                canvasView.requestFocusInWindow();
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
                entities.clear();
                project.scriptsByEntity.clear();
                project.globalVars.clear();
                project.globalVars.putAll(globalVarSnapshot);
                project.visibleGlobalVars.clear();
                project.visibleGlobalVars.addAll(globalVarVisibleSnapshot);
                for (Entity en : snapshot) {
                    Entity c = cloneEntity(en, true);
                    entities.add(c);
                    List<EventBlock> sc = new ArrayList<>();
                    for (EventBlock ev : scriptSnapshot.getOrDefault(en.id, Collections.emptyList())) {
                        sc.add((EventBlock) cloneBlock(ev));
                    }
                    project.scriptsByEntity.put(c.id, sc);
                }
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
                        // Las nuevas entidades añadidas al escenario deben contar con un id único
                        // por lo que la clonación siempre se realiza con keepId=false.
                        Entity clone = cloneEntity(tpl, false);
                        String newName = JOptionPane.showInputDialog(this, "Nombre de la entidad", tpl.name);
                        if (newName != null && !newName.trim().isEmpty()) {
                            clone.name = newName.trim();
                        }
                        entities.add(clone);
                        project.scriptsByEntity.put(clone.id, cloneScripts(tpl.id));
                        saveToScenario();
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

            borderSpin.addChangeListener(e -> {
                border = ((Number) borderSpin.getValue()).intValue();
                int max = Math.min(size.width, size.height) / 2;
                if (border > max) { border = max; borderSpin.setValue(border); }
                for (Entity en : entities) {
                    clampEntity(en);
                }
                repaint();
            });
        }

        Entity cloneEntity(Entity src) { return cloneEntity(src, false); }

        Entity cloneEntity(Entity src, boolean keepId) {
            Entity c = new Entity();
            if (keepId) {
                // Verifica que no exista otra entidad diferente a 'src' con el mismo id
                c.id = src.id;
                c.templateId = src.templateId;
                boolean exists = entities.stream().anyMatch(en -> en != src && en.id.equals(c.id))
                        || project.entities.stream().anyMatch(en -> en != src && en.id.equals(c.id));
                if (exists) {
                    throw new IllegalStateException("Entity id already exists: " + c.id);
                }
            } else {
                // Genera un id único para la copia evitando colisiones con el proyecto o el escenario
                Set<String> used = new HashSet<>();
                for (Entity en : entities) used.add(en.id);
                for (Entity en : project.entities) used.add(en.id);
                while (used.contains(c.id)) {
                    c.id = UUID.randomUUID().toString();
                }
                c.templateId = src.templateId;
            }
            c.name = src.name;
            c.t.x = src.t.x;
            c.t.y = src.t.y;
            c.t.rot = src.t.rot;
            c.a.shape = src.a.shape;
            c.a.color = src.a.color;
            c.a.width = src.a.width;
            c.a.height = src.a.height;
            c.a.opacity = src.a.opacity;
            c.a.shapeName = src.a.shapeName;
            for (Map.Entry<String, Color> en : src.a.colorByShape.entrySet()) {
                c.a.colorByShape.put(en.getKey(), en.getValue());
            }
            for (Map.Entry<String, BufferedImage> en : src.a.paintImages.entrySet()) {
                c.a.paintImages.put(en.getKey(), copyImage(en.getValue()));
            }
            if (src.a.customPolygon != null) {
                c.a.customPolygon = new Polygon(src.a.customPolygon.xpoints, src.a.customPolygon.ypoints, src.a.customPolygon.npoints);
            }
            c.vars.putAll(src.vars);
            if (src.visibleVars != null) {
                c.visibleVars.addAll(src.visibleVars);
            } else {
                c.visibleVars.addAll(src.vars.keySet());
            }
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
                for (Map.Entry<String, Object> en : ab.args.entrySet()) {
                    Object v = en.getValue();
                    if (v instanceof java.util.List<?> list) {
                        ab2.args.put(en.getKey(), new ArrayList<>(list));
                    } else {
                        ab2.args.put(en.getKey(), v);
                    }
                }
                if (ab.type == ActionType.RANDOM || ab.type == ActionType.IF_VAR || ab.type == ActionType.IF_GLOBAL_VAR || ab.type == ActionType.IF_RANDOM_CHANCE) {
                    for (Block extra : ab.extraNext) ab2.extraNext.add(cloneBlock(extra));
                }
                copy = ab2;
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

                // zona fuera del escenario
                g2.setColor(Color.GRAY);
                g2.fillRect(0,0,getWidth(),getHeight());

                // trasladar a área del escenario
                g2.translate(border, border);
                int w = stageWidth();
                int h = stageHeight();

                  // fondo del escenario
                  Scenario sc = project.scenarios.get(currentScenario);
                  BufferedImage bg = sc.getBackground();
                  if (bg != null) {
                      g2.drawImage(bg, 0, 0, w, h, null);
                  } else {
                      g2.setColor(new Color(0xF4F6F7));
                      g2.fillRect(0,0,w,h);

                      // rejilla ligera
                      g2.setColor(new Color(0xEAECEE));
                      for (int x=0; x<w; x+=40) g2.drawLine(x,0,x,h);
                      for (int y=0; y<h; y+=40) g2.drawLine(0,y,w,y);
                  }

                g2.setColor(Color.DARK_GRAY);
                int gy = 15;
                if (project.visibleGlobalVars == null) {
                    project.visibleGlobalVars = new HashSet<>(project.globalVars.keySet());
                }
                for (Map.Entry<String, Double> gv : project.globalVars.entrySet()) {
                    if (project.visibleGlobalVars.contains(gv.getKey())) {
                        g2.drawString(gv.getKey() + ": " + gv.getValue(), 10, gy);
                        gy += 15;
                    }
                }

                // dibujar entidades
                for (Entity e : entities) {
                    drawEntity(g2, e);
                }
                g2.dispose();
            }

            void drawEntity(Graphics2D g2, Entity e) {
                Composite oldComp = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) e.a.opacity));
                Shape s = buildShape(e);
                g2.setColor(e.a.getColor());
                g2.fill(s);
                BufferedImage img = e.a.getPaintImage();
                if (img != null) {
                    Graphics2D gImg = (Graphics2D) g2.create();
                    gImg.setClip(s);
                    AffineTransform at = new AffineTransform();
                    at.translate(e.t.x + e.a.width/2, e.t.y + e.a.height/2);
                    at.rotate(Math.toRadians(e.t.rot));
                    at.scale(e.t.scaleX, e.t.scaleY);
                    at.translate(-e.a.width/2, -e.a.height/2);
                    gImg.drawImage(img, at, null);
                    gImg.dispose();
                }
                g2.setColor(Color.DARK_GRAY);
                g2.draw(s);
                g2.setComposite(oldComp);
                g2.setColor(Color.DARK_GRAY);
                if (e.visibleVars == null) {
                    e.visibleVars = new HashSet<>(e.vars.keySet());
                }
                int vy = (int)e.t.y - 18;
                for (Map.Entry<String, Double> var : e.vars.entrySet()) {
                    if (e.visibleVars.contains(var.getKey())) {
                        g2.drawString(var.getKey() + ":" + var.getValue(), (int)e.t.x + 4, vy);
                        vy -= 12;
                    }
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
                if (runtime != null) {
                    MouseEvent me = new MouseEvent(e.getComponent(), e.getID(), e.getWhen(), e.getModifiersEx(),
                            e.getX() - border, e.getY() - border, e.getClickCount(), e.isPopupTrigger(), e.getButton());
                    runtime.handleMouseEvent(me);
                }
                return;
            }
            Point p = new Point(e.getX() - border, e.getY() - border);
            if (deleteMode) {
                List<Entity> revDel = new ArrayList<>(entities);
                Collections.reverse(revDel);
                for (Entity en : revDel) {
                    if (hit(en, p)) {
                        entities.remove(en);
                        project.scriptsByEntity.remove(en.id);
                        StagePanel.this.saveToScenario();
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
                if (hit(en, p)) {
                    dragEntity = en;
                    selectedEntity = en;
                    dragOffset = new Point((int)(p.x - en.t.x), (int)(p.y - en.t.y));
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
                dragEntity.t.x = e.getX() - border - dragOffset.x;
                dragEntity.t.y = e.getY() - border - dragOffset.y;
                clampEntity(dragEntity);
                repaint();
            }
        }
        @Override public void mouseMoved(MouseEvent e) {}

        boolean hit(Entity e, Point p) {
            return buildShape(e).contains(p);
        }
    }
}
