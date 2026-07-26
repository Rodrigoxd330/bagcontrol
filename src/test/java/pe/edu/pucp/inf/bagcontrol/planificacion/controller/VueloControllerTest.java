package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionManager;

import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueloControllerTest {

    private VueloRepository vueloRepository;
    private AeropuertoRepository aeropuertoRepository;
    private SimulacionManager simulacionManager;
    private VueloController controller;

    @BeforeEach
    void setUp() {
        vueloRepository = mock(VueloRepository.class);
        aeropuertoRepository = mock(AeropuertoRepository.class);
        simulacionManager = mock(SimulacionManager.class);
        controller = new VueloController(vueloRepository, aeropuertoRepository, simulacionManager);
    }

    @Test
    void creaVueloConIatasNormalizadosYDosAeropuertosActuales() {
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(aeropuerto("SPIM")));
        when(aeropuertoRepository.findById("SPZO")).thenReturn(Optional.of(aeropuerto("SPZO")));
        when(vueloRepository.save(any(Vuelo.class))).thenAnswer(invocacion -> {
            Vuelo vuelo = invocacion.getArgument(0);
            vuelo.setCodigo(2869L);
            return vuelo;
        });
        VueloDTO dto = new VueloDTO(
                null, " spim ", "spzo", LocalTime.of(9, 30), LocalTime.of(10, 40), 300, false
        );

        ResponseEntity<?> respuesta = controller.crearVuelo(dto);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(201);
        VueloDTO cuerpo = (VueloDTO) respuesta.getBody();
        assertThat(cuerpo.getOrigenIata()).isEqualTo("SPIM");
        assertThat(cuerpo.getDestinoIata()).isEqualTo("SPZO");
        verify(simulacionManager).refrescarCatalogoOperacionDia();
    }

    @Test
    void rechazaDestinoInexistenteConMensajeClaro() {
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(aeropuerto("SPIM")));
        when(aeropuertoRepository.findById("SPZO")).thenReturn(Optional.empty());
        VueloDTO dto = new VueloDTO(
                null, "SPIM", "SPZO", LocalTime.of(9, 30), LocalTime.of(10, 40), 300, false
        );

        ResponseEntity<?> respuesta = controller.crearVuelo(dto);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) respuesta.getBody()).get("error"))
                .isEqualTo("No existe el aeropuerto destino SPZO.");
        verify(vueloRepository, never()).save(any());
    }

    @Test
    void rechazaAeropuertoIncompletoAntesDeGuardarVuelo() {
        Aeropuerto incompleto = aeropuerto("SPZO");
        incompleto.setCiudad("");
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(aeropuerto("SPIM")));
        when(aeropuertoRepository.findById("SPZO")).thenReturn(Optional.of(incompleto));
        VueloDTO dto = new VueloDTO(
                null, "SPIM", "SPZO", LocalTime.of(9, 30), LocalTime.of(10, 40), 300, false
        );

        ResponseEntity<?> respuesta = controller.crearVuelo(dto);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) respuesta.getBody()).get("error"))
                .isEqualTo("El aeropuerto destino SPZO no tiene datos válidos para la simulación.");
        verify(vueloRepository, never()).save(any());
    }

    private Aeropuerto aeropuerto(String iata) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setCiudad("Ciudad");
        aeropuerto.setPais("Perú");
        aeropuerto.setContinente("América");
        aeropuerto.setCapacidadAlmacen(1000);
        aeropuerto.setGmt(-5);
        aeropuerto.setLatitud(-12.0);
        aeropuerto.setLongitud(-77.0);
        return aeropuerto;
    }
}
