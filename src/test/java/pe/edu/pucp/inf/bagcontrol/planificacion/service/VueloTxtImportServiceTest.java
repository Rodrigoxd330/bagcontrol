package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueloTxtImportServiceTest {

    private VueloRepository vuelos;
    private AeropuertoRepository aeropuertos;
    private VueloTxtImportService service;

    @BeforeEach
    void setUp() {
        vuelos = mock(VueloRepository.class);
        aeropuertos = mock(AeropuertoRepository.class);
        service = new VueloTxtImportService(vuelos, aeropuertos);
        when(vuelos.findAll()).thenReturn(List.of());
        when(aeropuertos.existsById(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPIM-SCEL-20:10-04:10-0150", "SPIM-SCEL-20:10-04:10-0150\n"})
    void aceptaTxtConOSinSaltoFinalYCerosIniciales(String contenido) {
        var resultado = service.importar(archivo("planes.txt", "text/plain", contenido));
        assertThat(resultado.insertados()).isEqualTo(1);
        assertThat(resultado.errores()).isEmpty();
        verify(vuelos).saveAll(anyList());
    }

    @Test
    void aceptaVariasLineasVaciasIntermediasYExtensionMayuscula() {
        var resultado = service.importar(archivo("planes.TXT", "text/plain",
                "SPIM-SCEL-20:10-04:10-0150\n\nSABE-OMDB-22:20-17:20-150"));
        assertThat(resultado.insertados()).isEqualTo(2);
        assertThat(resultado.totalLineas()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ORIGEN,DESTINO,SALIDA,LLEGADA,CAPACIDAD",
            "SPIM-SCEL-20:10-04:10",
            "SPIM-SCEL-20:10-04:10-150-EXTRA",
            "SPI-SCEL-20:10-04:10-150",
            "SPIM-SPIM-20:10-04:10-150",
            "SPIM-SCEL-24:10-04:10-150",
            "SPIM-SCEL-20:10-04:10-0",
            "SPIM-SCEL-20:10-04:10--1"
    })
    void rechazaLineasInvalidasSinGuardar(String contenido) {
        var resultado = service.importar(archivo("planes.txt", "text/plain", contenido));
        assertThat(resultado.insertados()).isZero();
        assertThat(resultado.errores()).isNotEmpty();
        verify(vuelos, never()).saveAll(anyList());
    }

    @Test
    void rechazaAeropuertoInexistente() {
        when(aeropuertos.existsById("SCEL")).thenReturn(false);
        var resultado = service.importar(archivo("planes.txt", "text/plain", "SPIM-SCEL-20:10-04:10-150"));
        assertThat(resultado.errores()).anyMatch(error -> error.contains("SCEL"));
    }

    @Test
    void rechazaCsvAunqueContengaFormatoPorGuiones() {
        var resultado = service.importar(archivo("planes.csv", "text/csv", "SPIM-SCEL-20:10-04:10-150"));
        assertThat(resultado.insertados()).isZero();
        assertThat(resultado.errores()).anyMatch(error -> error.contains(".txt"));
        verify(vuelos, never()).findAll();
    }

    @Test
    void detectaDuplicadosInternosYMantieneAtomicidad() {
        String linea = "SPIM-SCEL-20:10-04:10-150";
        var resultado = service.importar(archivo("planes.txt", "text/plain", linea + "\n" + linea));
        assertThat(resultado.insertados()).isZero();
        assertThat(resultado.duplicadosDetectados()).isEqualTo(1);
        verify(vuelos, never()).saveAll(anyList());
    }

    @Test
    void detectaDuplicadoExistente() {
        when(vuelos.findAll()).thenReturn(List.of(new Vuelo("SPIM", "SCEL", LocalTime.of(20, 10), LocalTime.of(4, 10), 90)));
        var resultado = service.importar(archivo("planes.txt", "text/plain", "SPIM-SCEL-20:10-04:10-150"));
        assertThat(resultado.insertados()).isZero();
        assertThat(resultado.duplicadosDetectados()).isEqualTo(1);
    }

    @Test
    void unaLineaInvalidaImpideGuardarLasValidas() {
        var resultado = service.importar(archivo("planes.txt", "text/plain",
                "SPIM-SCEL-20:10-04:10-150\nSPIM-SCEL-99:10-04:10-150"));
        assertThat(resultado.insertados()).isZero();
        verify(vuelos, never()).saveAll(anyList());
    }

    @Test
    void vueloNocturnoConservaHorasParaQueFactoryResuelvaLlegada() {
        var resultado = service.importar(archivo("planes.txt", "text/plain", "SPIM-SCEL-20:10-04:10-150"));
        assertThat(resultado.insertados()).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vuelos).saveAll(captor.capture());
        Vuelo vuelo = (Vuelo) captor.getValue().get(0);
        assertThat(vuelo.getHoraSalida()).isEqualTo(LocalTime.of(20, 10));
        assertThat(vuelo.getHoraLlegada()).isEqualTo(LocalTime.of(4, 10));
        Aeropuerto origen = aeropuerto("SPIM", 0);
        Aeropuerto destino = aeropuerto("SCEL", 0);
        var instancia = new VueloFactory().crearInstanciasDelDia(
                List.of(vuelo), LocalDate.of(2026, 7, 21), Map.of("SPIM", origen, "SCEL", destino)
        ).get(0);
        assertThat(instancia.getFechaHoraLlegada().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    private Aeropuerto aeropuerto(String iata, int gmt) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setGmt(gmt);
        return aeropuerto;
    }

    private MockMultipartFile archivo(String nombre, String mime, String contenido) {
        return new MockMultipartFile("archivo", nombre, mime, contenido.getBytes(StandardCharsets.UTF_8));
    }
}
