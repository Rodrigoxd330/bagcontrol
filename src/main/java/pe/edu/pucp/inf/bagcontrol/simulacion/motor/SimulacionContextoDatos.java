package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;

import java.util.List;

public record SimulacionContextoDatos(
        List<Vuelo> vuelos,
        List<Aeropuerto> aeropuertos,
        List<Incidencia> incidencias
) {
}
