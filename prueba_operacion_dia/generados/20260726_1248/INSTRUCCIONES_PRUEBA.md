# Instrucciones de prueba: operacion dia a dia

Este generador solo crea archivos; no cambia capacidades, no carga vuelos y no inicia la operacion.

## Generar los archivos

Desde la raiz del proyecto ejecuta:

```powershell
.\prueba_operacion_dia\generar-envios-operacion.ps1 -FechaHoraLima '2026-07-26 12:48'
```

Usa la hora exacta de inicio de la prueba en Lima. Los cuatro archivos de envios usan la hora local equivalente de cada sede.
Para una nueva prueba, reemplaza la fecha y hora del comando por la hora actual o programada exacta de Lima; no reutilices una hora de ejemplo.

## Secuencia de prueba

1. Antes de la hora de inicio, acuerden una hora exacta de Lima (T0), por ejemplo 14:00, y generen los archivos con esa hora.
2. Entre T0 y T0+5 min: en Operacion dia a dia > Planes de vuelo, carga el archivo de planes generado; luego, en Aeropuertos, cambia SPIM, SABE, EKCH y VIDP a capacidad 999.
3. Entre T0 y T0+5 min: cada registrador inicia sesion con su sede y muestra la fecha/hora local de su computadora. Presenten tambien los archivos generados.
4. Confirmen vuelos, capacidades, cuentas y archivos. Inicien el visualizador de operacion diaria.
5. Esperen 1 minuto. Cada registrador ingresa de 5 a 10 filas de su archivo registros_manuales_<SEDE>.md y anuncia cada registro en voz alta. El origen, cliente y hora los asigna el sistema.
6. Cada grupo recibido se planifica a demanda: espera como maximo 5 segundos para agrupar registros y luego tarda Ta en calcular. La ruta aparece despues de 5 segundos mas Ta.
7. Esperen entre 10 y 15 minutos segun indique el docente. Luego carguen cada envios_<SEDE>_*.txt desde la cuenta de la sede correspondiente.
8. Esperen nuevamente entre 10 y 15 minutos. Seleccionen dos envios planificados y muestren sus rutas en el mapa.
9. Cancelen un vuelo futuro desde la gestion de vuelos y verifiquen la reasignacion de las maletas.
10. Al finalizar, eliminen los vuelos anadidos y restauren las capacidades: SPIM 440, SABE 460, EKCH 480, VIDP 480.

La preparacion debe durar menos de 5 minutos y el inicio del registro manual debe ocurrir antes de T0+8 min.
Los vuelos generados salen entre T0+30 y T0+40 min para que sean futuros durante la carga masiva y la demostracion.
Los IDs de envios son unicos entre sedes para evitar colisiones.
