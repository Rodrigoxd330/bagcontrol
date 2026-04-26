package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

import java.util.ArrayList;
import java.util.List;

@Data
public class SolucionRuta {

    private List<RutaAsignada> asignaciones = new ArrayList<>();
    private double fitness;

    public void agregarAsignacion(Envio envio, VueloInstanciado vuelo) {
        asignaciones.add(new RutaAsignada(envio, vuelo));
    }

    public VueloInstanciado obtenerVueloAsignado(Envio envio) {
        return asignaciones.stream()
                .filter(a -> a.getEnvio().getIdPedido().equals(envio.getIdPedido()))
                .map(RutaAsignada::getVuelo)
                .findFirst()
                .orElse(null);
    }

    public List<Envio> obtenerEnviosConConflictos() {
        return asignaciones.stream()
                .filter(a -> a.getVuelo() == null)
                .map(RutaAsignada::getEnvio)
                .toList();
    }

    public SolucionRuta clonar() {
        SolucionRuta copia = new SolucionRuta();
        copia.setFitness(this.fitness);

        for (RutaAsignada asignacion : this.asignaciones) {
            copia.agregarAsignacion(asignacion.getEnvio(), asignacion.getVuelo());
        }

        return copia;
    }

    public void aplicarMovimientoDefinitivo(Movimiento movimiento) {
        for (RutaAsignada asignacion : asignaciones) {
            if (asignacion.getEnvio().getIdPedido().equals(movimiento.getEnvio().getIdPedido())) {
                asignacion.setVuelo(movimiento.getVueloNuevo());
                break;
            }
        }
    }
}