package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;

import java.util.ArrayList;
import java.util.List;

@Data
public class SolucionRuta {

    private List<RutaAsignada> asignaciones = new ArrayList<>();
    private double fitness;

    public void agregarAsignacion(Envio envio, Itinerario itinerario) {
        asignaciones.add(new RutaAsignada(envio, itinerario));
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

        for (RutaAsignada asignacion : this.asignaciones) {
            copia.agregarAsignacion(asignacion.getEnvio(), asignacion.getItinerario());
        }

        return copia;
    }

    public void aplicarMovimientoDefinitivo(Movimiento movimiento) {
        for (RutaAsignada asignacion : asignaciones) {
            if (asignacion.getEnvio().getIdPedido().equals(movimiento.getEnvio().getIdPedido())) {
                asignacion.setItinerario(movimiento.getItinerarioNuevo());
                break;
            }
        }
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