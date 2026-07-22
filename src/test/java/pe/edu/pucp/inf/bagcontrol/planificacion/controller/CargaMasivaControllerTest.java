package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionManager;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.VueloTxtImportService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CargaMasivaControllerTest {

    private AeropuertoRepository aeropuertoRepository;
    private EnvioDataStore envioDataStore;
    private AuthService authService;
    private CargaMasivaController controller;

    @BeforeEach
    void setUp() {
        aeropuertoRepository = mock(AeropuertoRepository.class);
        envioDataStore = mock(EnvioDataStore.class);
        authService = mock(AuthService.class);
        controller = new CargaMasivaController(
                aeropuertoRepository,
                mock(VueloRepository.class),
                envioDataStore,
                authService,
                mock(SimulacionManager.class),
                mock(VueloTxtImportService.class)
        );
    }

    @Test
    void importaFormatoOrigenUsuarioConAeropuertoDeLaSesion() {
        Aeropuerto spim = aeropuerto("SPIM", -5);
        when(authService.resolverBearer("Bearer token-spim"))
                .thenReturn(new UsuarioSesion("usuario@bagcontrol.com", "Usuario", "REGISTRADOR", " SPIM "));
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(spim));
        when(aeropuertoRepository.existsById("SUAA")).thenReturn(true);

        ResponseEntity<Map<String, Object>> respuesta = controller.cargarEnviosCsv(
                archivo("000000001-20260102-00-47-SUAA-002-0007729\n"),
                "Bearer token-spim"
        );

        assertThat(respuesta.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(respuesta.getBody()).containsEntry("insertados", 1);
        ArgumentCaptor<Envio> captor = ArgumentCaptor.forClass(Envio.class);
        verify(envioDataStore).upsert(captor.capture());
        Envio envio = captor.getValue();
        assertThat(envio.getIdPedido()).isEqualTo("000000001");
        assertThat(envio.getOrigenIata()).isEqualTo("SPIM");
        assertThat(envio.getDestinoIata()).isEqualTo("SUAA");
        assertThat(envio.getCantidadMaletas()).isEqualTo(2);
        assertThat(envio.getIdCliente()).isEqualTo("0007729");
        assertThat(envio.getFechaHora().toString()).isEqualTo("2026-01-02T05:47");
    }

    @Test
    void rechazaCuentaSinAeropuertoAntesDeCrearEnvios() {
        when(authService.resolverBearer("Bearer token"))
                .thenReturn(new UsuarioSesion("admin@bagcontrol.com", "Admin", "ADMINISTRADOR", ""));

        ResponseEntity<Map<String, Object>> respuesta = controller.cargarEnviosCsv(
                archivo("000000001-20260102-00-47-SUAA-002-0007729\n"),
                "Bearer token"
        );

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody()).containsEntry(
                "error", "La cuenta actual no tiene un aeropuerto de origen asignado."
        );
        verify(envioDataStore, never()).upsert(any());
        verify(envioDataStore, never()).agregarEnvio(any(NuevoEnvioDTO.class), any(Aeropuerto.class));
    }

    @Test
    void informaLineaYDestinoInexistente() {
        when(authService.resolverBearer("Bearer token"))
                .thenReturn(new UsuarioSesion("usuario@bagcontrol.com", "Usuario", "REGISTRADOR", "SPIM"));
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(aeropuerto("SPIM", -5)));
        when(aeropuertoRepository.existsById("SUAA")).thenReturn(false);

        ResponseEntity<Map<String, Object>> respuesta = controller.cargarEnviosCsv(
                archivo("000000001-20260102-00-47-SUAA-002-0007729\n"),
                "Bearer token"
        );

        assertThat(respuesta.getBody()).containsEntry("insertados", 0);
        assertThat((Iterable<?>) respuesta.getBody().get("errores"))
                .anyMatch(error -> error.toString().contains("línea 1") && error.toString().contains("SUAA"));
        verify(envioDataStore, never()).upsert(any());
    }

    @Test
    void conservaLegacySoloParaElOrigenDeLaCuenta() {
        Aeropuerto spim = aeropuerto("SPIM", -5);
        when(authService.resolverBearer("Bearer token"))
                .thenReturn(new UsuarioSesion("usuario@bagcontrol.com", "Usuario", "REGISTRADOR", "SPIM"));
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(spim));

        ResponseEntity<Map<String, Object>> respuesta = controller.cargarEnviosCsv(
                archivo("SPIM,SUAA,2,0032535,2026-01-02T05:47:00Z\n"),
                "Bearer token"
        );

        assertThat(respuesta.getBody()).containsEntry("insertados", 1);
        verify(envioDataStore).agregarEnvio(
                any(NuevoEnvioDTO.class), org.mockito.ArgumentMatchers.same(spim)
        );
    }

    @Test
    void impideUsarOrigenLegacyDeOtraCuenta() {
        when(authService.resolverBearer("Bearer token"))
                .thenReturn(new UsuarioSesion("usuario@bagcontrol.com", "Usuario", "REGISTRADOR", "SPIM"));
        when(aeropuertoRepository.findById("SPIM")).thenReturn(Optional.of(aeropuerto("SPIM", -5)));

        ResponseEntity<Map<String, Object>> respuesta = controller.cargarEnviosCsv(
                archivo("SPZO,SUAA,2,0032535,2026-01-02T05:47:00Z\n"),
                "Bearer token"
        );

        assertThat(respuesta.getBody()).containsEntry("insertados", 0);
        verify(envioDataStore, never()).agregarEnvio(any(NuevoEnvioDTO.class), any(Aeropuerto.class));
    }

    private MockMultipartFile archivo(String contenido) {
        return new MockMultipartFile(
                "archivo", "envios.txt", "text/plain", contenido.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Aeropuerto aeropuerto(String iata, int gmt) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setGmt(gmt);
        return aeropuerto;
    }
}
