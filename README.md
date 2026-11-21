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

## Fase 3 — Opciones del lápiz (v0.2.1)
- Al tocar el lápiz en el editor se resalta y aparecen sus opciones a la derecha de las herramientas:
  tipo (lápiz / pluma / pincel, cambia también el ícono), suavizado (menú con valor y deslizador),
  3 grosores y 4 colores. Toque largo en un color: selector con círculo cromático, barra de valor,
  HEX (misma validación que el acento) y paleta de hasta 14 colores guardados.
- Datos: SharedPreferences `pen`. Aún NO hay trazo sobre la hoja: eso es la siguiente fase.

**No cambia:** datos de notas, carpetas, fondos, fuentes y acento.

## Fase 4 — Vistas del editor y menú lateral (v0.3.0)
- **Ojo** en la barra del editor: alterna entre *una hoja* (centrada, ajustada a la pantalla según su
  orientación; deslizar con un dedo cambia de hoja: las hojas van de izquierda a derecha: a la izquierda = siguiente, a la derecha = anterior) y
  *continua* (todas las hojas en columna con un pequeño espacio; solo visual, sin herramientas).
- Zoom con pellizco en ambas vistas (visual, no cambia la hoja): una hoja mínimo 25 % del ajuste;
  continua nunca más pequeña que su tamaño base. Dos dedos rotan la hoja en la vista de una hoja
  (no se guarda). Doble toque restablece. El stylus se ignora (reservado para dibujar).
- **Capas:** ícono con número independiente (`LayersView.count`), por ahora 1; solo en vista de una hoja.
- **Menú lateral** en la biblioteca (tres rayas o deslizar desde la izquierda): Todas las notas,
  Sin categorizar, Favoritos, Papelera, Categorías, Mis notas, Nueva categoría (todo "Próximamente").

**No cambia:** datos de notas, carpetas, fondos, fuentes, acento y opciones del lápiz.

### v0.3.1 — correcciones
- Orden de hojas de izquierda a derecha (deslizar a la izquierda avanza a la siguiente).
- Vista continua: ya se desplaza (la columna tenía la altura de la pantalla); hoja 1 arriba.
- Al reabrir una nota las hojas aparecían vacías hasta pulsar el ojo: la vista se reconstruía en
  mitad del layout y la hoja quedaba sin medir; ahora se reconstruye justo después.

## Fase 5 — Dibujar y borrar (v0.4.0)
- **Lápiz / pluma / pincel** dibujan en la hoja actual (vista de una hoja) con el color, grosor
  (fino/normal/denso) y suavizado elegidos. Pluma y pincel varían el grosor con la presión del stylus
  (con el dedo, según la velocidad). Dos dedos siguen haciendo zoom/rotación/desplazamiento; si se usa
  stylus, la palma (dedos sueltos) se ignora.
- **Borrador** quita partes de los trazos (parte los trazos) con tamaño fino/normal/grande
  (radios 1,5 / 4 / 9 mm); muestra un círculo donde borra.
- Los trazos se guardan por nota en `filesDir/strokes/<id>.bin` (milímetros de la hoja, binario compacto),
  en segundo plano; se borran junto con la nota. La vista continua los muestra (solo lectura).
- Las flechas junto al contador ahora pasan a la hoja anterior / siguiente.
- Pendiente: deshacer/rehacer, miniaturas con trazos en la biblioteca, resaltador.

### v0.4.1 — marca texto
- Nuevo ícono de marca texto y herramienta funcional: trazo de lápiz parcialmente transparente (alpha ≈ 45 %,
  se ve lo de debajo) con grosor fino/mediano/grande (2,5 / 5 / 9 mm) y sus propios 4 colores
  (toque largo = selector con círculo cromático, HEX y paleta guardada compartida con el lápiz).
- El borrador también quita partes del marca texto. Datos: `pen` (`hl_*`); en el archivo de trazos es el tipo 3.

## Fase 6 — Panel flotante de capas (v0.5.0)
- Al tocar el ícono de capas (vista de una hoja) aparece un **panel flotante** (`LayersPanel.kt`) sobre la hoja,
  abajo a la derecha. Se **arrastra por su barra superior** y nunca sale del área de hojas.
- Dos estados: **expandido** (barra "Capas" con arrastre, minimizar y cerrar; lista de capas con ojo; barra inferior con
  porcentaje y control deslizante) y **minimizado** (cuadro con el número de capas; se arrastra igual y un toque lo expande).
  Cerrar (X) devuelve el ícono de capas. En vista continua el panel se oculta y vuelve al regresar a una hoja.
- Estado (abierto, minimizado, posición) se conserva al girar la tablet o cambiar acento/fuente; no se guarda al cerrar la nota.
- Solo visual: la lista muestra "Capa 1" y la opacidad aún no cambia nada. Las capas reales (crear, ocultar, reordenar, opacidad)
  y su formato de guardado son la siguiente fase. Íconos nuevos: `MINIMIZE` y `GRIP`.

**No cambia:** datos de notas, trazos (`strokes/*.bin`), carpetas, fondos, fuentes, acento y opciones del lápiz.

## Fase 6b — Herramientas del panel de capas (v0.5.1)
- El panel ahora es una fila de tres partes: **barra izquierda** (40 dp: agregar, duplicar, bajar capa), **columna central**
  (lista, modo de fusión y opacidad) y **barra derecha** (40 dp: configuración, bloquear alfa, subir, eliminar, más opciones).
- La fila "Capa 1" lleva miniatura con fondo de tablero (transparencia), ojo, nombre y menú (≡) a la derecha.
- Sobre la opacidad hay una fila con flecha-a-línea, candado pequeño y el modo "Normal".
- **Todos los botones nuevos muestran "Próximamente"** (`UiKit.soon`); no hay lógica real de capas todavía.
- Íconos nuevos en `Icons.kt`: `COPY`, `ARROW_DOWN`, `ARROW_UP`, `ARROW_DOWN_LINE`, `CHECKER` y `DOTS_V`. Se usan colores existentes.

## Ajustes de diseño del panel de capas (v0.5.2)
- Duplicar lleva un pequeño **+** en la esquina superior derecha (ícono `COPY_PLUS`).
- Barra derecha: se quitaron candado y flecha arriba; ahora es tablero, **mover/transformar** (`MOVE`), basura y más opciones.
- Fila sobre la opacidad: solo flecha-a-línea y candado a la izquierda; sin texto "Normal" ni flecha desplegable.

## Fase 7 — Capas reales, primera parte (v0.6.0)
- Cada hoja tiene ahora **capas** (`Layer`: id, name, isVisible, opacity y canvas = sus trazos; `PageLayers` en `Strokes.kt`).
  Siempre hay al menos una. Las capas viven en memoria mientras la nota está abierta.
- **+** agrega una capa vacía justo encima de la seleccionada y la selecciona (máx. 30). **Basura** elimina la seleccionada
  (pide confirmación si tiene trazos; la última capa no se puede eliminar). Tocar una fila la selecciona.
- Se dibuja y se borra solo en la capa seleccionada. El render respeta `isVisible` y `opacity` (aún sin controles).
- El número del ícono de capas y del panel minimizado muestra cuántas capas tiene la hoja.
- **Guardado:** el formato `strokes/*.bin` **no cambió**: al guardar se juntan los trazos de todas las capas (de abajo hacia arriba).
  Al volver a abrir la nota, cada hoja carga en una sola capa. Guardar capas por separado es la siguiente parte.

## Fase 7b — Ojo de capa (v0.6.1)
- El ojo de cada capa **alterna `isVisible`**: visible = ojo en acento; oculta = ojo tachado (`EYE_OFF`) y nombre en gris.
  El dibujo de la hoja salta las capas ocultas. No se puede dibujar ni borrar en una capa oculta (avisa con un aviso).
- La visibilidad **aún no se guarda**: al reabrir la nota todo vuelve a verse (los trazos ocultos siguen guardados).

## Fase 8 — Guardado de la estructura de capas (v0.7.0)
- `LayerRepository.kt` guarda `filesDir/layers/<idNota>.json` con kotlinx.serialization: por hoja, cada capa (nombre, orden,
  visibilidad, opacidad), cuál está seleccionada y **cuántos trazos tiene**. Se guarda al agregar, eliminar, mostrar/ocultar,
  seleccionar y al dibujar o borrar; se carga en `onCreate` justo después de los trazos.
- `strokes/<id>.bin` **no cambió**: sigue guardando todos los trazos juntos (abajo → arriba). Con el conteo por capa, al abrir cada
  trazo vuelve a su capa. Si los datos no cuadran (archivo dañado o a medias), todo cae en la primera capa y se conserva la estructura.
- `Layer` ahora es `@Serializable` (su `canvas` es `@Transient`). Gradle: plugin `org.jetbrains.kotlin.plugin.serialization`
  1.9.24 y `kotlinx-serialization-json:1.6.3`. Al eliminar una nota también se borra su archivo de capas.

## Compilar
Sube el proyecto a GitHub: el workflow `.github/workflows/build.yml` (Build APK, JDK 17, Gradle 8.9)
genera el APK en la pestaña Actions → artefacto `AloNotes-debug`.
