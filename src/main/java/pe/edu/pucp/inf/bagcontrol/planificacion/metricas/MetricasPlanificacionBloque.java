package pe.edu.pucp.inf.bagcontrol.planificacion.metricas;

import lombok.Data;

import java.time.Instant;

/**
 * Telemetria agregada de una ejecucion de planificacion. No contiene ids de envios
 * ni otros datos personales y no participa en las decisiones del algoritmo.
 */
@Data
public class MetricasPlanificacionBloque {
    private long numeroLote;
    private int k;
    private long saMs;
    private String tiempoSimuladoInicio;
    private String tiempoSimuladoFin;
    private Instant inicioRealCalculo;
    private Instant finRealCalculo;
    private Instant inicioEspera;
    private Instant publicacion;
    private Instant deadlinePublicacion;
    private boolean taSuperoSa;

    private long cargaEnviosMs;
    private long generacionVuelosMs;
    private long generacionItinerariosMs;
    private long construccionInicialMs;
    private long graspMs;
    private long tabuMs;
    private long validacionMs;
    private long postprocesamientoMs;
    private long generacionEventosMs;
    private long publicacionWebSocketMs;
    private long taTotalMs;

    private int enviosNuevos;
    private int pendientes;
    private int planificados;
    private int sinItinerario;
    private int directos;
    private int conEscala;
    private int candidatosGenerados;
    private int candidatosDirectos;
    private int candidatosConEscala;
    private double proporcionCandidatosConEscala;
    private int candidatosDirectosAntesRecorte;
    private int candidatosConEscalaAntesRecorte;
    private int candidatosDirectosDescartados;
    private int candidatosConEscalaDescartados;
    private int enviosConDirectaQueUsaronEscala;
    private int enviosSinDirectaResueltosConEscala;
    private long generacionEscalasMs;
    private boolean timeoutAlcanzado;

    public void sumarCargaEnviosMs(long valor) { cargaEnviosMs += Math.max(0, valor); }
    public void sumarGeneracionVuelosMs(long valor) { generacionVuelosMs += Math.max(0, valor); }
    public void sumarGeneracionItinerariosMs(long valor) { generacionItinerariosMs += Math.max(0, valor); }
    public void sumarConstruccionInicialMs(long valor) { construccionInicialMs += Math.max(0, valor); }
    public void sumarGraspMs(long valor) { graspMs += Math.max(0, valor); }
    public void sumarTabuMs(long valor) { tabuMs += Math.max(0, valor); }
    public void sumarValidacionMs(long valor) { validacionMs += Math.max(0, valor); }
    public void sumarPostprocesamientoMs(long valor) { postprocesamientoMs += Math.max(0, valor); }
    public void sumarGeneracionEventosMs(long valor) { generacionEventosMs += Math.max(0, valor); }
    public void sumarPublicacionWebSocketMs(long valor) { publicacionWebSocketMs += Math.max(0, valor); }
    public void sumarCandidatos(int directos, int conEscala) {
        candidatosDirectos += Math.max(0, directos);
        candidatosConEscala += Math.max(0, conEscala);
        candidatosGenerados = candidatosDirectos + candidatosConEscala;
        proporcionCandidatosConEscala = candidatosGenerados == 0
                ? 0.0 : candidatosConEscala / (double) candidatosGenerados;
    }

    public void registrarRecorteCandidatos(
            int directosAntes,
            int escalasAntes,
            int directosConservados,
            int escalasConservadas
    ) {
        candidatosDirectosAntesRecorte = Math.max(0, directosAntes);
        candidatosConEscalaAntesRecorte = Math.max(0, escalasAntes);
        candidatosDirectosDescartados = Math.max(0, directosAntes - directosConservados);
        candidatosConEscalaDescartados = Math.max(0, escalasAntes - escalasConservadas);
    }

    public void sumarGeneracionEscalasMs(long valor) { generacionEscalasMs += Math.max(0, valor); }
}
