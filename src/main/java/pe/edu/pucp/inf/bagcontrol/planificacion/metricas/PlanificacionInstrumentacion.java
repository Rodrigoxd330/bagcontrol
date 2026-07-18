package pe.edu.pucp.inf.bagcontrol.planificacion.metricas;

/** Contexto por hilo para instrumentar sin alterar las firmas del planificador. */
public final class PlanificacionInstrumentacion {
    private static final ThreadLocal<MetricasPlanificacionBloque> ACTUAL = new ThreadLocal<>();

    private PlanificacionInstrumentacion() { }

    public static MetricasPlanificacionBloque iniciar() {
        MetricasPlanificacionBloque metricas = new MetricasPlanificacionBloque();
        ACTUAL.set(metricas);
        return metricas;
    }

    public static MetricasPlanificacionBloque actual() {
        return ACTUAL.get();
    }

    public static MetricasPlanificacionBloque actualOIniciar() {
        MetricasPlanificacionBloque metricas = ACTUAL.get();
        return metricas != null ? metricas : iniciar();
    }

    public static void limpiar() {
        ACTUAL.remove();
    }
}
