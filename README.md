# AloNotes

App Android personal de apuntes (estilo Penly Notes) con el aspecto de Alouncher.
Kotlin, `com.alonotes.app`, minSdk 26, targetSdk 34. Sin librerías extra (solo recyclerview y core).

## Fase 1 — Interfaz
- Biblioteca "Mis notas": carpetas y notas en cuadrícula, migas de pan, menú flotante (+):
  Nuevo documento, Importar documento (próximamente), Crear carpeta. Renombrar y eliminar funcionan.
- Editor: barra de herramientas completa (todo "Próximamente" salvo volver y crear páginas),
  diálogo "Crear páginas nuevas" (A5/A4/A3/A2, orientación, color, cantidad) y contador de páginas.
- Ajustes: color de acento (12 colores) y opciones "Próximamente".
- Ícono: A cursiva dibujada de un solo trazo que termina en un broche.

Datos: `SharedPreferences "notes"` (clave `items`, JSON) y `"settings"` (clave `accent`).
No cambiar `applicationId`.

## Fase 1b — Kotlin y limpieza
- Proyecto migrado de Java a Kotlin (Kotlin 1.9.24, AGP 8.5.2). Mismos formatos de datos y comportamiento.
- Eliminado el botón flotante de lápiz del editor (el lápiz del borde es el panel edge de la tablet).
- versionCode 2 / versionName 0.1.1.

## Fase 2 — Personalización estilo AloTube (v0.2.0)
- Quitados los íconos de correo y ayuda de la biblioteca.
- **Carpetas como AloTube:** toque largo + arrastre reordena (carpetas con carpetas, notas con notas;
  `local_order`); toque largo sin mover abre el menú de la carpeta: Establecer icono (imagen del
  dispositivo, `folder_icons`), Quitar fondo y Archivar/Desarchivar (`folder_archive`). Con ícono propio
  la tarjeta muestra solo la imagen.
- **Ajustes (tuerca):** Apariencia › Fondo / Estilos de letra / Color de acento; Archivados.
  - Fondos por pantalla (Mis notas, Editor, Ajustes): imagen o **video** (mudo, en bucle), ajuste
    Rellenar/Estirar/Ajustar, capa oscura al 60 % (`backgrounds`, `filesDir/backgrounds/`).
  - Estilos de letra: `assets/fonts/` (pon ahí tus .ttf/.otf), importadas (`filesDir/fonts/`) y 5 del sistema.
  - Acento: 12 colores + colores propios por **HEX** (máx. 12, `accent_custom`). El acento tiñe los íconos
    de barras y hojas.
- Cambiar acento o fuente recrea las pantallas abiertas.

**No cambia:** `applicationId`, claves `notes`/`items` y `settings`/`accent`, formato de las notas,
el ícono del launcher (color fijo).

## Compilar
Sube el proyecto a GitHub: el workflow `.github/workflows/build.yml` (Build APK, JDK 17, Gradle 8.9)
genera el APK en la pestaña Actions → artefacto `AloNotes-debug`.
