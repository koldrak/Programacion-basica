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
        String instr = "Paso 1: En la paleta de la izquierda, busca el bloque 'Al iniciar' y arrástralo al área de scripts.\n" +
                "Paso 2: Desde la sección de Acciones, arrastra un bloque 'Decir' y conéctalo justo debajo de 'Al iniciar'.\n" +
                "Paso 3: Haz doble clic sobre el texto del bloque 'Decir' y escribe 'Hola'.\n" +
                "Paso 4: Presiona el botón Ejecutar para ver cómo la entidad saluda al comenzar.";
        return new Mission("Misión 1 – ¡Di hola!", instr,
                p -> tieneEventoAccion(p, ScratchMVP.EventType.ON_START,
                        ScratchMVP.ActionType.SAY));
    }

    private static Mission m2() {
        String instr = "Paso 1: Arrastra un evento 'Tecla' y configúralo con la tecla W.\n" +
                "Paso 2: Conecta un bloque 'Mover' hacia arriba debajo de ese evento.\n" +
                "Paso 3: Repite el proceso para las teclas A, S y D moviendo izquierda, abajo y derecha respectivamente.\n" +
                "Paso 4: Ejecuta el proyecto y verifica que la entidad se mueva con el teclado.";
        return new Mission("Misión 2 – Mover con WASD", instr,
                p -> tieneMovimientoWASD(p));
    }

    private static Mission m3() {
        String instr = "Paso 1: En la paleta, arrastra un bloque 'Color' y elige un color nuevo para la entidad.\n" +
                "Paso 2: Añade un bloque 'Forma' y selecciona una figura diferente.\n" +
                "Paso 3: Inserta un bloque 'Escalar' para aumentar o disminuir el tamaño.\n" +
                "Paso 4: Ejecuta el proyecto para apreciar el cambio de apariencia.";
        return new Mission("Misión 3 – Apariencia", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.SET_COLOR)
                        && tieneAccion(p, ScratchMVP.ActionType.SET_SHAPE)
                        && tieneAccion(p, ScratchMVP.ActionType.SCALE_BY));
    }

    private static Mission m4() {
        String instr = "Paso 1: Crea una variable local llamada 'pasos'.\n" +
                "Paso 2: Usa el bloque 'Fijar pasos a 0' para inicializarla al comenzar.\n" +
                "Paso 3: En cada evento de movimiento agrega el bloque 'Cambiar pasos en 1'.\n" +
                "Paso 4: Ejecuta y observa cómo la variable aumenta mientras te desplazas.";
        return new Mission("Misión 4 – Contador de pasos", instr,
                p -> tieneVarInicializadaYCambio(p, "pasos"));
    }

    private static Mission m5() {
        String instr = "Paso 1: Añade un bloque condicional 'Si pasos > 10'.\n" +
                "Paso 2: Dentro del condicional, coloca un bloque 'Decir' con el texto '¡Meta!'.\n" +
                "Paso 3: Ejecuta y mueve la entidad hasta superar el límite para que aparezca el mensaje.";
        return new Mission("Misión 5 – Condición de meta", instr,
                p -> tieneCondicionalConSay(p, "pasos"));
    }

    private static Mission m6() {
        String instr = "Paso 1: Utiliza el bloque 'Crear entidad' para generar una moneda en el escenario.\n" +
                "Paso 2: Programa un evento 'Al colisionar' con la moneda.\n" +
                "Paso 3: Dentro del evento, agrega 'Cambiar puntaje en 1' y luego 'Eliminar entidad'.\n" +
                "Paso 4: Ejecuta y recoge la moneda para aumentar el puntaje.";
        return new Mission("Misión 6 – Colisión y puntos", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.SPAWN_ENTITY)
                        && tieneEventoAccion(p, ScratchMVP.EventType.ON_COLLIDE,
                        ScratchMVP.ActionType.CHANGE_VAR)
                        && tieneEventoAccion(p, ScratchMVP.EventType.ON_COLLIDE,
                        ScratchMVP.ActionType.DELETE_ENTITY));
    }

    private static Mission m7() {
        String instr = "Paso 1: Inserta un bloque 'Aleatorio' o 'Si azar'.\n" +
                "Paso 2: Define dos acciones distintas para cada resultado posible.\n" +
                "Paso 3: Ejecuta varias veces para observar decisiones al azar.";
        return new Mission("Misión 7 – Azar", instr,
                p -> tieneAccion(p, ScratchMVP.ActionType.RANDOM)
                        || tieneAccion(p, ScratchMVP.ActionType.IF_RANDOM_CHANCE));
    }

    private static Mission m8() {
        String instr = "Paso 1: Crea una variable global llamada 'vidas' e inicialízala en 3.\n" +
                "Paso 2: Configura que al colisionar con un obstáculo se reste 1 a 'vidas'.\n" +
                "Paso 3: Añade una condición que cambie a una escena de Game Over cuando 'vidas' sea 0.";
        return new Mission("Misión 8 – Vidas y escenas", instr,
                p -> (tieneAccion(p, ScratchMVP.ActionType.SET_GLOBAL_VAR)
                        || tieneAccion(p, ScratchMVP.ActionType.CHANGE_GLOBAL_VAR))
                        && tieneAccion(p, ScratchMVP.ActionType.GOTO_SCENE));
    }

    private static Mission m9() {
        String instr = "Paso 1: Diseña un nivel usando las técnicas de las misiones anteriores.\n" +
                "Paso 2: Emplea 'Ir a escena' y 'Detener' para mostrar mensajes de victoria o derrota.\n" +
                "Paso 3: Ajusta detalles y prueba hasta que el juego quede completo.";
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