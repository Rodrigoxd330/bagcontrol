package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VueloFactoryTest {

    private VueloFactory factory;
    private static final LocalDate FECHA_BASE = LocalDate.of(2026, 2, 2);

    @BeforeEach
    void setUp() {
        factory = new VueloFactory();
    }

    // ────────────────────────────────────────────────────────────
    // CASO 1: vuelo normal (no cruza medianoche)
    //   sale 08:00 → llega 12:30 → mismo día
    // ────────────────────────────────────────────────────────────
    @Test
    void vueloNormal_llegadaMismoDia() {
        Vuelo vuelo = new Vuelo("LIM", "BOG",
                LocalTime.of(8, 0), LocalTime.of(12, 30), 200);

        List<VueloInstanciado> instancias = factory.crearInstanciasDelDia(List.of(vuelo), FECHA_BASE);

        assertEquals(1, instancias.size());
        VueloInstanciado v = instancias.get(0);

        assertEquals(LocalDateTime.of(FECHA_BASE, LocalTime.of(8,  0)),  v.getFechaHoraSalida());
        assertEquals(LocalDateTime.of(FECHA_BASE, LocalTime.of(12, 30)), v.getFechaHoraLlegada());
        assertTrue(v.getFechaHoraLlegada().isAfter(v.getFechaHoraSalida()),
                "La llegada debe ser posterior a la salida");
    }

    // ────────────────────────────────────────────────────────────
    // CASO 2: vuelo nocturno (cruza medianoche)
    //   sale 23:00 → llega 02:00 → llegada debe ser día +1
    //   Este era el bug original: llegada quedaba ANTES de salida
    // ────────────────────────────────────────────────────────────
    @Test
    void vueloNocturno_llegadaEsDiaSiguiente() {
        Vuelo vuelo = new Vuelo("NRT", "SYD",
                LocalTime.of(23, 0), LocalTime.of(2, 0), 300);

        List<VueloInstanciado> instancias = factory.crearInstanciasDelDia(List.of(vuelo), FECHA_BASE);

        assertEquals(1, instancias.size());
        VueloInstanciado v = instancias.get(0);

        LocalDateTime salidaEsperada  = LocalDateTime.of(FECHA_BASE,             LocalTime.of(23, 0));
        LocalDateTime llegadaEsperada = LocalDateTime.of(FECHA_BASE.plusDays(1), LocalTime.of(2,  0));

        assertEquals(salidaEsperada,  v.getFechaHoraSalida(),  "La salida debe ser en FECHA_BASE");
        assertEquals(llegadaEsperada, v.getFechaHoraLlegada(), "La llegada debe ser el día siguiente");
        assertTrue(v.getFechaHoraLlegada().isAfter(v.getFechaHoraSalida()),
                "La llegada debe ser posterior a la salida (el bug causaba lo contrario)");
    }

    // ────────────────────────────────────────────────────────────
    // CASO 3: sale justo a medianoche (00:00) → no cruza
    //   00:00 < 05:00 es false → isBefore=false → no suma día
    // ────────────────────────────────────────────────────────────
    @Test
    void vueloSalidaMedianoche_noCruzaMedianoche() {
        Vuelo vuelo = new Vuelo("MAD", "GRU",
                LocalTime.of(0, 0), LocalTime.of(5, 0), 250);

        List<VueloInstanciado> instancias = factory.crearInstanciasDelDia(List.of(vuelo), FECHA_BASE);

        VueloInstanciado v = instancias.get(0);
        assertEquals(FECHA_BASE, v.getFechaHoraSalida().toLocalDate(),  "Salida debe ser FECHA_BASE");
        assertEquals(FECHA_BASE, v.getFechaHoraLlegada().toLocalDate(), "Llegada también debe ser FECHA_BASE");
    }

    // ────────────────────────────────────────────────────────────
    // CASO 4: duración en minutos debe ser positiva
    //   Esto es lo que excedePlazoMaximo verifica (horasTotales < 0)
    //   Antes del fix, un vuelo 22:00→10:00 tenía duración negativa
    //   y era penalizado como si hubiera vencido el SLA
    // ────────────────────────────────────────────────────────────
    @Test
    void vueloNocturno_duracionEsPositiva() {
        Vuelo vuelo = new Vuelo("JFK", "LHR",
                LocalTime.of(22, 0), LocalTime.of(10, 0), 400);

        VueloInstanciado v = factory.crearInstanciasDelDia(List.of(vuelo), FECHA_BASE).get(0);

        long minutos = Duration.between(v.getFechaHoraSalida(), v.getFechaHoraLlegada()).toMinutes();

        assertTrue(minutos > 0,
                "La duración debe ser positiva — era negativa antes del fix. Minutos obtenidos: " + minutos);
        assertEquals(12 * 60, minutos,
                "JFK→LHR 22:00→10:00 = 12 horas = 720 minutos");
    }

    // ────────────────────────────────────────────────────────────
    // CASO 5: batch con mezcla de vuelos normales y nocturnos
    //   Todos deben tener llegada estrictamente posterior a salida
    // ────────────────────────────────────────────────────────────
    @Test
    void batch_vuelosMixtos_todosConLlegadaValida() {
        List<Vuelo> vuelos = List.of(
                new Vuelo("LIM", "MIA", LocalTime.of(10,  0), LocalTime.of(17,  0), 200), // normal
                new Vuelo("LAX", "SYD", LocalTime.of(23, 30), LocalTime.of( 6,  0), 350), // nocturno
                new Vuelo("CDG", "NRT", LocalTime.of(21,  0), LocalTime.of(15,  0), 300)  // nocturno
        );

        List<VueloInstanciado> instancias = factory.crearInstanciasDelDia(vuelos, FECHA_BASE);

        assertEquals(3, instancias.size());
        for (VueloInstanciado v : instancias) {
            assertTrue(
                    v.getFechaHoraLlegada().isAfter(v.getFechaHoraSalida()),
                    "Llegada debe ser posterior a salida para " + v.getOrigenIata()
                            + "-" + v.getDestinoIata()
                            + " | Salida: "  + v.getFechaHoraSalida()
                            + " | Llegada: " + v.getFechaHoraLlegada()
            );
        }
    }
}
