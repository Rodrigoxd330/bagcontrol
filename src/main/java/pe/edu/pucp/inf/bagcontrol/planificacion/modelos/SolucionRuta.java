package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class SolucionRuta {

    private List<RutaAsignada> asignaciones = new ArrayList<>();
    private double fitness;
    private int sinItinerarioCount;
    private int excedeSlaCount;
    private int vuelosCanceladosUsadosCount;
    private int vuelosSobrecargadosCount;
    private int aeropuertosSaturadosCount;

    public RutaAsignada agregarAsignacion(Envio envio, Itinerario itinerario) {
        RutaAsignada asignacion = new RutaAsignada(envio, itinerario, false);
        asignaciones.add(new RutaAsignada(envio, itinerario, false)); //El excedeSLA se evalua después
        return asignacion;
    }

    public Itinerario obtenerItinerarioAsignado(Envio envio) {
        return asignaciones.stream()
                .filter(a -> a.getEnvio().getIdPedido().equals(envio.getIdPedido()))
                .map(RutaAsignada::getItinerario)
                .findFirst()
                .orElse(null);
    }

    public List<Envio> obtenerEnviosConConflictos() {
        return asignaciones.stream()
                .filter(a -> a.getItinerario() == null)
                .map(RutaAsignada::getEnvio)
                .toList();
    }

    public SolucionRuta clonar() {
        SolucionRuta copia = new SolucionRuta();
        copia.setFitness(this.fitness);
        copia.setSinItinerarioCount(this.sinItinerarioCount);
        copia.setExcedeSlaCount(this.excedeSlaCount);
        copia.setVuelosCanceladosUsadosCount(this.vuelosCanceladosUsadosCount);
        copia.setVuelosSobrecargadosCount(this.vuelosSobrecargadosCount);
        copia.setAeropuertosSaturadosCount(this.aeropuertosSaturadosCount);

        for (RutaAsignada asignacion : this.asignaciones) {
            copia.getAsignaciones().add(new RutaAsignada(
                    asignacion.getEnvio(),
                    asignacion.getItinerario(),
                    asignacion.isExcedeSla()
            ));
        }
        return copia;
    }

    public boolean aplicarMovimientoDefinitivo(Movimiento movimiento,
                                               Map<String, Aeropuerto> mapaAeropuertos,
                                               Map<String,Integer> inventarioInicial,
                                               Set<String> enviosNuevos) {
        boolean noRespeta = true;
        for (RutaAsignada asignacion : asignaciones) {
            if (asignacion.getEnvio().getIdPedido().equals(movimiento.getEnvio().getIdPedido())) {
                asignacion.setItinerario(movimiento.getItinerarioNuevo());
                noRespeta = false;
                if (!PlanificadorUtils.solucionRespetaCapacidadVuelos(this)
                        || !PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                                this, mapaAeropuertos, inventarioInicial, enviosNuevos
                        )) {
                    noRespeta = true;
                }
                break;
            }
        }
        return noRespeta;
    }

    public void deshacerMovimiento(Movimiento movimiento) {
        for (RutaAsignada asignacion : asignaciones) {
            if (asignacion.getEnvio().getIdPedido().equals(movimiento.getEnvio().getIdPedido())) {
                asignacion.setItinerario(movimiento.getItinerarioAnterior());
                break;
            }
        }
    }
}
