package pe.edu.pucp.inf.bagcontrol.planificacion.deadline;

import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.PlanificacionInstrumentacion;

/** Deadline absoluto cooperativo de la planificación ejecutada por el hilo del bloque. */
public final class DeadlinePlanificacion {
    private static final ThreadLocal<Contexto> ACTUAL = new ThreadLocal<>();
    private DeadlinePlanificacion() {}

    public static void iniciar(long deadlineEpochMs) { ACTUAL.set(new Contexto(deadlineEpochMs)); }
    public static long instanteO(long alternativo) {
        Contexto c = ACTUAL.get(); return c == null ? alternativo : Math.min(c.deadlineEpochMs, alternativo);
    }
    public static boolean alcanzado(String fase) {
        Contexto c = ACTUAL.get();
        if (c == null || System.currentTimeMillis() < c.deadlineEpochMs) return false;
        var metricas = PlanificacionInstrumentacion.actual();
        if (metricas != null) metricas.registrarDeadlineAlcanzado(fase);
        return true;
    }
    public static long restanteMs() {
        Contexto c = ACTUAL.get(); return c == null ? Long.MAX_VALUE : Math.max(0, c.deadlineEpochMs-System.currentTimeMillis());
    }
    public static void limpiar() { ACTUAL.remove(); }
    private record Contexto(long deadlineEpochMs) {}
}
