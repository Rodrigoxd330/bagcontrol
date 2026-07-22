package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VueloTxtImportService {

    private static final DateTimeFormatter HORA_ESTRICTA = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;

    @Transactional
    public ResultadoImportacion importar(MultipartFile archivo) {
        List<String> errores = new ArrayList<>();
        if (archivo == null) {
            return new ResultadoImportacion(0, 0, List.of("El archivo es obligatorio."), 0);
        }
        if (archivo.isEmpty()) {
            return new ResultadoImportacion(0, 0, List.of("El archivo TXT está vacío."), 0);
        }

        String nombre = archivo.getOriginalFilename();
        if (nombre == null || !nombre.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            errores.add("Solo se permiten archivos con extensión .txt.");
        }
        String mime = archivo.getContentType();
        if (mime != null && !mime.isBlank() && !"text/plain".equalsIgnoreCase(mime)) {
            errores.add("El tipo de contenido debe ser text/plain.");
        }
        if (!errores.isEmpty()) {
            return new ResultadoImportacion(0, 0, errores, 0);
        }

        List<Vuelo> candidatos = new ArrayList<>();
        Set<ClaveVuelo> clavesArchivo = new HashSet<>();
        Set<ClaveVuelo> clavesExistentes = new HashSet<>();
        vueloRepository.findAll().forEach(vuelo -> clavesExistentes.add(ClaveVuelo.de(vuelo)));
        int duplicados = 0;
        int totalLineas = 0;

        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(archivo.getInputStream(), decoder))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                totalLineas++;
                if (linea.isEmpty()) continue;
                try {
                    Vuelo vuelo = parsearLinea(linea);
                    ClaveVuelo clave = ClaveVuelo.de(vuelo);
                    if (!clavesArchivo.add(clave)) {
                        duplicados++;
                        errores.add("Línea " + totalLineas + ": vuelo duplicado dentro del archivo.");
                    } else if (clavesExistentes.contains(clave)) {
                        duplicados++;
                        errores.add("Línea " + totalLineas + ": el plan de vuelo ya existe.");
                    } else {
                        candidatos.add(vuelo);
                    }
                } catch (IllegalArgumentException ex) {
                    errores.add("Línea " + totalLineas + ": " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            errores.add("No se pudo leer el archivo como texto UTF-8: " + ex.getMessage());
        }

        if (totalLineas == 0) errores.add("El archivo TXT no contiene líneas.");
        if (candidatos.isEmpty() && errores.isEmpty()) errores.add("El archivo TXT no contiene planes de vuelo.");
        if (!errores.isEmpty()) return new ResultadoImportacion(totalLineas, 0, errores, duplicados);

        vueloRepository.saveAll(candidatos);
        return new ResultadoImportacion(totalLineas, candidatos.size(), List.of(), 0);
    }

    private Vuelo parsearLinea(String linea) {
        if (linea.contains(",")) throw new IllegalArgumentException("no se permiten comas ni formato CSV.");
        String[] campos = linea.split("-", -1);
        if (campos.length != 5) {
            throw new IllegalArgumentException("se esperaban exactamente 5 campos separados por '-' y se recibieron " + campos.length + ".");
        }
        String origen = validarIata(campos[0], "origen");
        String destino = validarIata(campos[1], "destino");
        if (origen.equals(destino)) throw new IllegalArgumentException("el origen y el destino no pueden ser iguales.");
        if (!aeropuertoRepository.existsById(origen)) throw new IllegalArgumentException("no existe el aeropuerto de origen " + origen + ".");
        if (!aeropuertoRepository.existsById(destino)) throw new IllegalArgumentException("no existe el aeropuerto de destino " + destino + ".");

        LocalTime salida = validarHora(campos[2], "salida");
        LocalTime llegada = validarHora(campos[3], "llegada");
        String capacidadTexto = campos[4];
        if (!capacidadTexto.matches("[0-9]+")) throw new IllegalArgumentException("la capacidad debe ser un entero positivo.");
        int capacidad;
        try {
            capacidad = Integer.parseInt(capacidadTexto);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("la capacidad excede el rango permitido.");
        }
        if (capacidad <= 0) throw new IllegalArgumentException("la capacidad debe ser mayor que cero.");

        Vuelo vuelo = new Vuelo(origen, destino, salida, llegada, capacidad);
        vuelo.setCreadoPorCrud(true);
        return vuelo;
    }

    private String validarIata(String valor, String campo) {
        if (!valor.matches("[A-Za-z]{4}")) {
            throw new IllegalArgumentException("el " + campo + " debe contener exactamente cuatro letras.");
        }
        return valor.toUpperCase(Locale.ROOT);
    }

    private LocalTime validarHora(String valor, String campo) {
        if (!valor.matches("[0-9]{2}:[0-9]{2}")) {
            throw new IllegalArgumentException("la hora de " + campo + " debe tener formato estricto HH:mm.");
        }
        try {
            return LocalTime.parse(valor, HORA_ESTRICTA);
        } catch (Exception ex) {
            throw new IllegalArgumentException("la hora de " + campo + " no es válida.");
        }
    }

    public record ResultadoImportacion(int totalLineas, int insertados, List<String> errores, int duplicadosDetectados) {
    }

    private record ClaveVuelo(String origen, String destino, LocalTime salida, LocalTime llegada) {
        static ClaveVuelo de(Vuelo vuelo) {
            return new ClaveVuelo(vuelo.getOrigenIata(), vuelo.getDestinoIata(), vuelo.getHoraSalida(), vuelo.getHoraLlegada());
        }
    }
}
