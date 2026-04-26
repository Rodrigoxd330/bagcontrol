package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AeropuertoLoader implements CommandLineRunner {

    private final AeropuertoRepository aeropuertoRepository;

    @Value("${tasf.b2b.data.aeropuertos}")
    private Resource aeropuertosResource;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println("✈️ 1. Iniciando carga de Aeropuertos desde archivo...");
        System.out.println("==================================================");

        aeropuertoRepository.deleteAll();

        List<Aeropuerto> aeropuertosNuevos = new ArrayList<>();
        String continenteActual = "Desconocido";

        try (BufferedReader br = new BufferedReader(new InputStreamReader(aeropuertosResource.getInputStream(), StandardCharsets.UTF_16))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();

                // Detectar continente ANTES de descartar la línea
                if (linea.contains("America") || linea.contains("América")) continenteActual = "America";
                else if (linea.contains("Europa")) continenteActual = "Europa";
                else if (linea.contains("Asia")) continenteActual = "Asia";

                // Ignorar líneas vacías, asteriscos o cabeceras del archivo
                if (linea.isEmpty() || linea.startsWith("*") || linea.startsWith("PDDS") || linea.contains("CAPACIDAD")) {
                    continue;
                }

                // Saltar líneas que no empiezan con número (cabeceras de continente, etc.)
                if (!linea.matches("^\\d{2}.*")) {
                    continue;
                }

                // Separar por 2 o más espacios consecutivos
                String[] partes = linea.split("\\s{2,}");

                if (partes.length >= 7) {
                    Aeropuerto aero = new Aeropuerto();
                    aero.setCodigoIata(partes[1]);
                    aero.setCiudad(partes[2]);
                    aero.setPais(partes[3]);
                    aero.setContinente(continenteActual);

                    aero.setGmt(Integer.parseInt(partes[5].replace("+", "")));
                    aero.setCapacidadAlmacen(Integer.parseInt(partes[6]));

                    aero.setLatitud(extraerCoordenada(linea, "Latitude"));
                    aero.setLongitud(extraerCoordenada(linea, "Longitude"));

                    aeropuertosNuevos.add(aero);
                }
            }
        }

        aeropuertoRepository.saveAll(aeropuertosNuevos);

        System.out.println("\n✅ Guardados " + aeropuertosNuevos.size() + " aeropuertos en la Base de Datos H2.");
        System.out.println("\n--- LISTA DE AEROPUERTOS CARGADOS ---");

        for (Aeropuerto a : aeropuertosNuevos) {
            System.out.println("IATA: " + a.getCodigoIata() +
                    " | Ciudad: " + String.format("%-15s", a.getCiudad()) +
                    " | Cont: " + String.format("%-8s", a.getContinente()) +
                    " | Cap: " + a.getCapacidadAlmacen() +
                    " | Lat: " + a.getLatitud() +
                    " | Lon: " + a.getLongitud());
        }
        System.out.println("==================================================\n");
    }

    private double extraerCoordenada(String linea, String tipo) {
        String regex = tipo + ":\\s*(\\d+)°\\s*(\\d+)'\\s*(\\d+)[\"']\\s*([NSEW])";
        Pattern patron = Pattern.compile(regex);
        Matcher matcher = patron.matcher(linea);

        if (matcher.find()) {
            double grados = Double.parseDouble(matcher.group(1));
            double minutos = Double.parseDouble(matcher.group(2));
            double segundos = Double.parseDouble(matcher.group(3));
            String direccion = matcher.group(4);

            double decimal = grados + (minutos / 60) + (segundos / 3600);

            if (direccion.equals("S") || direccion.equals("W")) {
                decimal = decimal * -1;
            }
            return Math.round(decimal * 1000000.0) / 1000000.0;
        }
        return 0.0;
    }
}