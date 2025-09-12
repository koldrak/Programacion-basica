import java.util.*;
import java.util.function.Predicate;

/**
 * Sistema de misiones para el editor ScratchMVP.
 * Cada misión incluye instrucciones detalladas y una condición
 * que detecta si fue completada para desbloquear la siguiente.
 */
public class TutorialSystem {
    /** Representa una misión del tutorial. */
    public static class Mission {
        public final String nombre;
        public final String instrucciones;
        private final Predicate<ScratchMVP.Project> completada;

        public Mission(String nombre, String instrucciones, Predicate<ScratchMVP.Project> completada) {
            this.nombre = nombre;
            this.instrucciones = instrucciones;
            this.completada = completada;
        }

        /** Evalúa si la misión está cumplida para el proyecto dado. */
        public boolean estaCompleta(ScratchMVP.Project p) {
            return completada.test(p);
        }
    }

    private final List<Mission> misiones;
    private int indiceActual = 0;

    public TutorialSystem() {
        misiones = List.of(
            m1(), m2(), m3(), m4(), m5(), m6(), m7(), m8(), m9()
        );
    }

    /** Devuelve la misión activa o null si todas fueron completadas. */
    public Mission obtenerMisionActual() {
        if (indiceActual >= misiones.size()) return null;
        return misiones.get(indiceActual);
    }

    /**
     * Revisa el proyecto y avanza si la misión actual está completa.
     */
    public void actualizar(ScratchMVP.Project proyecto) {
        Mission actual = obtenerMisionActual();
        if (actual != null && actual.estaCompleta(proyecto)) {
            indiceActual++;
        }
    }

    public List<Mission> getMisiones() {
        return misiones;
    }

    public int getIndiceActual() {
        return indiceActual;
    }

    // ====== Definición de misiones ======

    private static Mission m1() {
        String instr = "1. Arrastra el bloque 'Al iniciar'.\n" +
                "2. Conecta debajo un bloque 'Decir' y escribe 'Hola'.\n" +
                "3. Presiona Ejecutar: la entidad debe saludar al empezar.";
        return new Mission("Misión 1 – ¡Di hola!", instr,
                p -> tieneEventoAccion(p, ScratchMVP.EventType.ON_START,
                        ScratchMVP.ActionType.SAY));
    }

    private static Mission m2() {
        String instr = "1. Crea cuatro eventos 'Tecla' para W, A, S y D.\n" +
                "2. Bajo cada uno coloca un bloque 'Mover' en la dirección correspondiente.\n" +
                "3. Al probar, la entidad debe moverse con el teclado.";
        return new Mission("Misión 2 – Mover con WASD", instr,
                p -> tieneMovimientoWASD(p));
    }

    private static Mission m3() {
        String instr = "1. Añade un bloque 'Color' para cambiar el color de la entidad.\n" +
                "2. Inserta un bloque 'Forma' para elegir una figura distinta.\n" +
                "3. Usa 'Escalar' para modificar el tamaño.\n" +
                "4. Ejecuta y observa el nuevo aspecto.";
        return new Mission("Misión 3 – Apariencia", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.SET_COLOR)
                        && tieneAccion(p, ScratchMVP.ActionType.SET_SHAPE)
                        && tieneAccion(p, ScratchMVP.ActionType.SCALE_BY));
    }

    private static Mission m4() {
        String instr = "1. Crea una variable local llamada 'pasos' con 'Fijar pasos a 0'.\n" +
                "2. En cada evento de movimiento añade 'Cambiar pasos en 1'.\n" +
                "3. Ejecuta y verifica que el valor aumenta al mover.";
        return new Mission("Misión 4 – Contador de pasos", instr,
                p -> tieneVarInicializadaYCambio(p, "pasos"));
    }

    private static Mission m5() {
        String instr = "1. Inserta un bloque 'Si pasos > 10'.\n" +
                "2. Dentro del 'Si' coloca un bloque 'Decir \"¡Meta!\"'.\n" +
                "3. Mueve la entidad hasta superar el límite para ver el mensaje.";
        return new Mission("Misión 5 – Condición de meta", instr,
                p -> tieneCondicionalConSay(p, "pasos"));
    }

    private static Mission m6() {
        String instr = "1. Usa 'Crear entidad' para generar una moneda.\n" +
                "2. Programa 'Al colisionar' con la moneda: 'Cambiar puntaje en 1' y 'Eliminar entidad'.\n" +
                "3. Prueba recoger la moneda para aumentar el puntaje.";
        return new Mission("Misión 6 – Colisión y puntos", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.SPAWN_ENTITY)
                        && tieneEventoAccion(p, ScratchMVP.EventType.ON_COLLIDE,
                        ScratchMVP.ActionType.CHANGE_VAR)
                        && tieneEventoAccion(p, ScratchMVP.EventType.ON_COLLIDE,
                        ScratchMVP.ActionType.DELETE_ENTITY));
    }

    private static Mission m7() {
        String instr = "1. Inserta un bloque 'Aleatorio' o 'Si azar'.\n" +
                "2. Define dos resultados distintos para explorar decisiones al azar.";
        return new Mission("Misión 7 – Azar", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.RANDOM)
                        || tieneAccion(p, ScratchMVP.ActionType.IF_RANDOM_CHANCE));
    }

    private static Mission m8() {
        String instr = "1. Crea una variable global 'vidas' con valor 3.\n" +
                "2. Resta 1 cuando el jugador choque con un obstáculo.\n" +
                "3. Cuando 'vidas' sea 0 cambia a una escena de Game Over.";
        return new Mission("Misión 8 – Vidas y escenas", instr,
                p -> (tieneAccion(p, ScratchMVP.ActionType.SET_GLOBAL_VAR)
                        || tieneAccion(p, ScratchMVP.ActionType.CHANGE_GLOBAL_VAR))
                        && tieneAccion(p, ScratchMVP.ActionType.GOTO_SCENE));
    }

    private static Mission m9() {
        String instr = "1. Combina todas las funciones anteriores para construir un nivel completo.\n" +
                "2. Usa 'Ir a escena' y 'Detener' para mostrar victoria o derrota.";
        return new Mission("Misión 9 – Juego completo", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.GOTO_SCENE)
                        && tieneAccion(p, ScratchMVP.ActionType.STOP));
    }

    // ====== Funciones de ayuda ======

    private static boolean tieneMovimientoWASD(ScratchMVP.Project p) {
        int[] keys = {java.awt.event.KeyEvent.VK_W, java.awt.event.KeyEvent.VK_A,
                java.awt.event.KeyEvent.VK_S, java.awt.event.KeyEvent.VK_D};
        for (int k : keys) {
            boolean encontrado = false;
            for (var lista : p.scriptsByEntity.values()) {
                for (ScratchMVP.EventBlock eb : lista) {
                    if (eb.type == ScratchMVP.EventType.ON_KEY_DOWN
                            && k == (int) eb.args.getOrDefault("keyCode", -1)
                            && contieneAccion(eb.next, ScratchMVP.ActionType.MOVE_BY)) {
                        encontrado = true;
                        break;
                    }
                }
                if (encontrado) break;
            }
            if (!encontrado) return false;
        }
        return true;
    }

    private static boolean tieneVarInicializadaYCambio(ScratchMVP.Project p, String var) {
        return tieneAccion(p, ScratchMVP.ActionType.SET_VAR, ab -> var.equals(ab.args.get("var")))
                && tieneAccion(p, ScratchMVP.ActionType.CHANGE_VAR, ab -> var.equals(ab.args.get("var")));
    }

    private static boolean tieneCondicionalConSay(ScratchMVP.Project p, String var) {
        return tieneAccion(p, ScratchMVP.ActionType.IF_VAR, ab -> var.equals(ab.args.get("var"))
                && contieneAccion(ab.next, ScratchMVP.ActionType.SAY));
    }

    private static boolean tieneEventoAccion(ScratchMVP.Project p, ScratchMVP.EventType ev,
                                             ScratchMVP.ActionType ac) {
        return tieneEventoAccion(p, ev, ac, a -> true);
    }

    private static boolean tieneEventoAccion(ScratchMVP.Project p, ScratchMVP.EventType ev,
                                             ScratchMVP.ActionType ac,
                                             Predicate<ScratchMVP.ActionBlock> pred) {
        for (var lista : p.scriptsByEntity.values()) {
            for (ScratchMVP.EventBlock eb : lista) {
                if (eb.type == ev && contieneAccion(eb.next, ac, pred)) return true;
            }
        }
        return false;
    }

    private static boolean tieneAccion(ScratchMVP.Project p, ScratchMVP.ActionType ac) {
        return tieneAccion(p, ac, a -> true);
    }

    private static boolean tieneAccion(ScratchMVP.Project p, ScratchMVP.ActionType ac,
                                       Predicate<ScratchMVP.ActionBlock> pred) {
        for (var lista : p.scriptsByEntity.values()) {
            for (ScratchMVP.EventBlock eb : lista) {
                if (contieneAccion(eb.next, ac, pred)) return true;
            }
        }
        return false;
    }

    private static boolean contieneAccion(ScratchMVP.Block b, ScratchMVP.ActionType ac) {
        return contieneAccion(b, ac, a -> true);
    }

    private static boolean contieneAccion(ScratchMVP.Block b, ScratchMVP.ActionType ac,
                                          Predicate<ScratchMVP.ActionBlock> pred) {
        while (b != null) {
            if (b instanceof ScratchMVP.ActionBlock ab) {
                if (ab.type == ac && pred.test(ab)) return true;
                for (ScratchMVP.Block extra : ab.extraNext) {
                    if (contieneAccion(extra, ac, pred)) return true;
                }
            }
            b = b.next;
        }
        return false;
    }
}