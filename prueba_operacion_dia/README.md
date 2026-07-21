# Prueba de operación día a día

Esta carpeta contiene el generador de los archivos usados en la prueba. El generador solo crea archivos: no modifica capacidades, no carga vuelos y no inicia la operación.

## Generar archivos

Desde la carpeta `bagcontrol`, ejecuta el comando con la hora exacta programada para el inicio en Lima a las 14:00:

```powershell
.\prueba_operacion_dia\generar-envios-operacion.ps1 -FechaHoraLima '2026-07-21 14:00'
```

Para una nueva prueba, reemplaza la fecha y hora por la hora actual o programada exacta de Lima. No reutilices una hora de ejemplo.

El generador crea en `prueba_operacion_dia/generados/<fecha_hora>/`:

- Cuatro archivos de carga de envíos, uno por SPIM, SABE, EKCH y VIDP.
- Cuatro listas de registros manuales, con diez registros por sede.
- Un archivo de 48 planes de vuelo adicionales.
- Un archivo `INSTRUCCIONES_PRUEBA.md` específico para esa ejecución.

Los husos usados son los del dataset del curso: SPIM GMT-5, SABE GMT-3, EKCH GMT+2 y VIDP GMT+5.

## Paso a paso

1. Antes de la hora de inicio, acuerden una hora exacta de Lima, denominada `T0`, por ejemplo `14:00`.
2. Generen los archivos con `T0` usando el comando anterior.
3. Entre `T0` y `T0 + 5 min`, ingresa a `Operación día a día > Planes de vuelo` y carga el archivo `planes_vuelo_adicionales_*.txt` generado.
4. Entre `T0` y `T0 + 5 min`, ingresa a `Operación día a día > Aeropuertos` y cambia la capacidad de SPIM, SABE, EKCH y VIDP a `999`.
5. Cada estudiante inicia sesión con la cuenta asignada a su sede y muestra la fecha y hora local de su computadora.
6. Presenten los archivos de envíos generados y confirmen vuelos, capacidades, cuentas y husos horarios.
7. Inicien el visualizador de operación diaria.
8. Esperen un minuto. Cada registrador ingresa de 5 a 10 registros desde `registros_manuales_<SEDE>.md` y anuncia cada registro en voz alta.
9. Cada grupo de registros espera como máximo 5 segundos para agruparse y luego tarda `Ta` en planificarse. La ruta aparece aproximadamente después de `5 segundos + Ta`.
10. Esperen entre 10 y 15 minutos, según indique el docente. Luego carguen cada archivo `envios_<SEDE>_*.txt` desde la cuenta de su sede.
11. Esperen nuevamente entre 10 y 15 minutos. Seleccionen dos envíos planificados y muestren sus rutas en el mapa.
12. Cancelen un vuelo futuro desde la gestión de vuelos y verifiquen la reasignación de maletas.
13. Al finalizar, eliminen los vuelos añadidos y restauren las capacidades: SPIM `440`, SABE `460`, EKCH `480`, VIDP `480`.

## Tiempos importantes

- La preparación debe durar menos de 5 minutos.
- El registro manual debe comenzar antes de `T0 + 8 min`.
- Los vuelos generados salen entre `T0 + 30 min` y `T0 + 40 min`, para mantenerse disponibles durante la carga masiva y la demostración.
- Los archivos de envíos llevan la hora local equivalente de cada sede.
- Los IDs de los archivos son únicos entre sedes para evitar colisiones.
