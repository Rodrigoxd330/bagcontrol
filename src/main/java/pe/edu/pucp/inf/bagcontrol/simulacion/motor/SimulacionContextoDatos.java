package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;

import java.util.List;
import java.time.Instant;

public record SimulacionContextoDatos(
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        List<Incidencia> incidencias,
        Instant creadoEn
) {
}
