package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.ZonaHorariaUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class SelectorCancelacionVuelo {

    private SelectorCancelacionVuelo() {
    }

    static VueloInstanciado siguienteOcurrencia(
            Vuelo vuelo,
            Instant instanteRegistro,
            List<Aeropuerto> aeropuertos
    ) {
        Map<String, Aeropuerto> porCodigo = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, aeropuerto -> aeropuerto));
        return siguienteOcurrencia(vuelo, instanteRegistro, porCodigo);
    }

    static VueloInstanciado siguienteOcurrencia(
            Vuelo vuelo,
            Instant instanteRegistro,
            Map<String, Aeropuerto> porCodigo
    ) {
        Aeropuerto origen = porCodigo.get(vuelo.getOrigenIata());
        if (origen == null) {
            throw new IllegalArgumentException("No existe el aeropuerto de origen " + vuelo.getOrigenIata());
        }

        Instant umbral = instanteRegistro.plus(Duration.ofHours(1));
        LocalDate fechaLocal = ZonaHorariaUtils.convertirInstantALocal(umbral, origen).toLocalDate();
        VueloFactory factory = new VueloFactory();
        VueloInstanciado candidato = factory.crearInstanciasDelDia(List.of(vuelo), fechaLocal, porCodigo).get(0);
        if (candidato.getFechaHoraSalidaUtc().isBefore(umbral)) {
            candidato = factory.crearInstanciasDelDia(List.of(vuelo), fechaLocal.plusDays(1), porCodigo).get(0);
        }
        return candidato;
    }

    static String clave(Long codigoVuelo, Instant salidaUtc) {
        return codigoVuelo + "|" + salidaUtc;
    }
}
