package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;

import java.lang.reflect.Method;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ItinerarioDominanceTest {

    @Test
    void directaConLlegadaAnteriorEliminaEscalaDominada() throws Exception {
        Itinerario escala = escala(1, 2, 9, 10, 11, 14);
        Itinerario directa = directa(3, 10, 13, 100, 0, false);
        Map<String, List<Itinerario>> rutas = rutas(directa, escala);
        assertThat(podar(rutas)).isEqualTo(1);
        assertThat(rutas.get("LIM-BOG")).containsExactly(directa);
    }

    @Test
    void directaConMismaLlegadaDominaPorMenosTramos() throws Exception {
        Itinerario escala = escala(1, 2, 9, 10, 11, 14);
        Itinerario directa = directa(3, 10, 14, 100, 0, false);
        Map<String, List<Itinerario>> rutas = rutas(escala, directa);
        assertThat(podar(rutas)).isEqualTo(1);
        assertThat(rutas.get("LIM-BOG")).containsExactly(directa);
    }

    @Test
    void conservaEscalaSinDirectaOSiDirectaNoTieneCapacidad() throws Exception {
        Itinerario escala = escala(1, 2, 9, 10, 11, 14);
        Map<String, List<Itinerario>> sinDirecta = rutas(escala);
        assertThat(podar(sinDirecta)).isZero();
        Itinerario llena = directa(3, 10, 13, 10, 10, false);
        Map<String, List<Itinerario>> sinCapacidad = rutas(llena, escala);
        assertThat(podar(sinCapacidad)).isZero();
        assertThat(sinCapacidad.get("LIM-BOG")).contains(escala);
    }

    @Test
    void conservaEscalaSiDirectaEstaCanceladaOEsTemporalmenteInviable() throws Exception {
        Itinerario escala = escala(1, 2, 9, 10, 11, 14);
        Itinerario cancelada = directa(3, 10, 13, 100, 0, true);
        Map<String, List<Itinerario>> porCancelacion = rutas(cancelada, escala);
        assertThat(podar(porCancelacion)).isZero();
        Itinerario demasiadoTemprana = directa(4, 8, 13, 100, 0, false);
        Map<String, List<Itinerario>> porDisponibilidad = rutas(demasiadoTemprana, escala);
        assertThat(podar(porDisponibilidad)).isZero();
    }

    @Test
    void conservaEscalaQueLlegaAntesYPuedeSerNecesariaParaSla() throws Exception {
        Itinerario escala = escala(1, 2, 9, 10, 11, 13);
        Itinerario directaLenta = directa(3, 10, 15, 100, 0, false);
        Map<String, List<Itinerario>> rutas = rutas(directaLenta, escala);
        assertThat(podar(rutas)).isZero();
        assertThat(rutas.get("LIM-BOG")).contains(escala);
    }

    @Test
    void podaNoCreaRutasParcialesNiRompeContinuidad() throws Exception {
        Itinerario escalaUtil = escala(1, 2, 9, 10, 11, 13);
        Map<String, List<Itinerario>> rutas = rutas(escalaUtil);
        podar(rutas);
        Itinerario conservada = rutas.get("LIM-BOG").getFirst();
        assertThat(conservada.getVuelos()).hasSize(2);
        assertThat(conservada.getVuelos().get(0).getDestinoIata())
                .isEqualTo(conservada.getVuelos().get(1).getOrigenIata());
        assertThat(Duration.between(conservada.getVuelos().get(0).getFechaHoraLlegadaUtc(),
                conservada.getVuelos().get(1).getFechaHoraSalidaUtc())).isBetween(
                Duration.ofMinutes(30), Duration.ofHours(12));
    }

    @SuppressWarnings("unchecked")
    private int podar(Map<String, List<Itinerario>> rutas) throws Exception {
        Method m = ItinerarioService.class.getDeclaredMethod("podarEscalasDominadas", Map.class);
        m.setAccessible(true); return (int) m.invoke(new ItinerarioService(), rutas);
    }
    private Map<String, List<Itinerario>> rutas(Itinerario... valores) {
        return new HashMap<>(Map.of("LIM-BOG", new ArrayList<>(List.of(valores))));
    }
    private Itinerario directa(long id, int salida, int llegada, int capacidad, int ocupacion, boolean cancelada) {
        VueloInstanciado vuelo = vuelo(id, "LIM", "BOG", salida, llegada, capacidad);
        vuelo.setOcupacionActual(ocupacion); vuelo.setCanceladoPorIncidencia(cancelada);
        return new Itinerario(List.of(vuelo));
    }
    private Itinerario escala(long id1, long id2, int s1, int l1, int s2, int l2) {
        return new Itinerario(List.of(vuelo(id1, "LIM", "UIO", s1, l1, 100),
                vuelo(id2, "UIO", "BOG", s2, l2, 100)));
    }
    private VueloInstanciado vuelo(long id, String o, String d, int s, int l, int capacidad) {
        Vuelo base = new Vuelo(o, d, LocalTime.of(s, 0), LocalTime.of(l, 0), capacidad); base.setCodigo(id);
        LocalDate fecha = LocalDate.of(2026, 7, 20);
        LocalDateTime salida = fecha.atTime(s, 0), llegada = fecha.atTime(l, 0);
        return new VueloInstanciado(base, salida, llegada, salida.toInstant(ZoneOffset.UTC),
                llegada.toInstant(ZoneOffset.UTC), 0);
    }
}
