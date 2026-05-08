package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public class ZonaHorariaUtils {

    private ZonaHorariaUtils() {
    }

    public static ZoneOffset obtenerOffset(Aeropuerto aeropuerto) {
        if (aeropuerto == null) {
            throw new IllegalArgumentException("El aeropuerto no puede ser null.");
        }
        return ZoneOffset.ofHours(aeropuerto.getGmt());
    }

    public static OffsetDateTime convertirLocalAOffset(LocalDateTime fechaHoraLocal, Aeropuerto aeropuerto) {
        if (fechaHoraLocal == null) {
            throw new IllegalArgumentException("La fecha local no puede ser null.");
        }
        return fechaHoraLocal.atOffset(obtenerOffset(aeropuerto));
    }

    public static Instant convertirLocalAInstant(LocalDateTime fechaHoraLocal, Aeropuerto aeropuerto) {
        return convertirLocalAOffset(fechaHoraLocal, aeropuerto).toInstant();
    }

    public static LocalDateTime convertirInstantALocal(Instant instant, Aeropuerto aeropuerto) {
        if (instant == null) {
            throw new IllegalArgumentException("El instant no puede ser null.");
        }
        return LocalDateTime.ofInstant(instant, obtenerOffset(aeropuerto));
    }

    public static double calcularDuracionHorasUTC(VueloInstanciado vuelo) {
        if (vuelo == null) {
            throw new IllegalArgumentException("El vuelo no puede ser null.");
        }
        return calcularDuracionHorasUTC(vuelo.getFechaHoraSalidaUtc(), vuelo.getFechaHoraLlegadaUtc());
    }

    public static double calcularDuracionHorasUTC(Instant inicio, Instant fin) {
        return Duration.between(inicio, fin).toMinutes() / 60.0;
    }

    public static boolean esSalidaDespuesDeLlegadaUTC(VueloInstanciado primero, VueloInstanciado segundo) {
        return !segundo.getFechaHoraSalidaUtc().isBefore(primero.getFechaHoraLlegadaUtc());
    }

    public static long minutosEntreUTC(
            LocalDateTime fechaLocalA,
            Aeropuerto aeropuertoA,
            LocalDateTime fechaLocalB,
            Aeropuerto aeropuertoB
    ) {
        Instant instantA = convertirLocalAInstant(fechaLocalA, aeropuertoA);
        Instant instantB = convertirLocalAInstant(fechaLocalB, aeropuertoB);
        return Duration.between(instantA, instantB).toMinutes();
    }
}
