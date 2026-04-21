package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class SolucionRuta {

    private List<RutaAsignada> asignaciones = new ArrayList<>();
    private double fitness = Double.MAX_VALUE;

    public void agregarAsignacion(Envio envio, Vuelo vuelo) {
        asignaciones.add(new RutaAsignada(envio, vuelo));
    }

    public Vuelo obtenerVueloAsignado(Envio envio) {
        return asignaciones.stream()
                .filter(a -> a.getEnvio().getIdPedido().equals(envio.getIdPedido()))
                .map(RutaAsignada::getVuelo)
                .findFirst()
                .orElse(null);
    }

    public List<Envio> obtenerEnviosConConflictos() {
        return asignaciones.stream()
                .filter(a -> a.getVuelo() == null || a.getVuelo().isEstaCancelado())
                .map(RutaAsignada::getEnvio)
                .collect(Collectors.toList());
    }

    public SolucionRuta clonar() {
        SolucionRuta copia = new SolucionRuta();
        copia.setFitness(this.fitness);

        List<RutaAsignada> nuevasAsignaciones = this.asignaciones.stream()
                .map(a -> new RutaAsignada(a.getEnvio(), a.getVuelo()))
                .collect(Collectors.toList());

        copia.setAsignaciones(nuevasAsignaciones);
        return copia;
    }

    public void aplicarMovimientoDefinitivo(Movimiento movimiento) {
        for (RutaAsignada asignacion : asignaciones) {
            if (asignacion.getEnvio().getIdPedido().equals(movimiento.getEnvio().getIdPedido())) {
                asignacion.setVuelo(movimiento.getVueloNuevo());
                return;
            }
        }
    }
}